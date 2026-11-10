package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.identity.domain.Organization;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Identity-owned semantic commands for tenant-local Organization structure. */
public final class OrganizationCommandService {

    private final OrganizationRepository repository;
    private final IdGenerator idGenerator;
    private final TransactionExecutor transactions;

    public OrganizationCommandService(
            OrganizationRepository repository,
            IdGenerator idGenerator,
            TransactionExecutor transactions) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public Organization create(
            TenantContext tenant,
            String name,
            String organizationType,
            UUID parentId,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(now, "now");
        Organization desired = new Organization(
                idGenerator.nextId(),
                name,
                organizationType,
                parentId,
                1,
                now,
                now);

        return transactions.required(() -> {
            requireParentExists(tenant, parentId);
            repository.insert(tenant, desired);
            return repository.findById(tenant, desired.id())
                    .orElseThrow(() -> new IllegalStateException(
                            "created Organization could not be reloaded"));
        });
    }

    public Organization changeMetadata(
            TenantContext tenant,
            UUID organizationId,
            String name,
            String organizationType,
            long expectedRevision,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(now, "now");
        requireExpectedRevision(expectedRevision);

        return transactions.required(() -> {
            Organization current = requireCurrent(tenant, organizationId, expectedRevision);
            Organization validated = new Organization(
                    current.id(),
                    name,
                    organizationType,
                    current.parentId(),
                    current.revision(),
                    current.createdAt(),
                    now);
            return repository.updateMetadata(
                    tenant,
                    organizationId,
                    validated.name(),
                    validated.organizationType(),
                    expectedRevision,
                    now);
        });
    }

    public Organization changeParent(
            TenantContext tenant,
            UUID organizationId,
            UUID parentId,
            long expectedRevision,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(now, "now");
        requireExpectedRevision(expectedRevision);

        return transactions.required(() -> {
            repository.lockHierarchyMutation(tenant);
            Organization current = requireCurrent(tenant, organizationId, expectedRevision);
            if (Objects.equals(current.parentId(), parentId)) {
                return current;
            }
            requireParentExists(tenant, parentId);
            if (organizationId.equals(parentId)
                    || (parentId != null
                    && repository.wouldCreateCycle(tenant, organizationId, parentId))) {
                throw new IllegalArgumentException(
                        "Organization parent would create a hierarchy cycle");
            }
            return repository.updateParent(
                    tenant,
                    organizationId,
                    parentId,
                    expectedRevision,
                    now);
        });
    }

    private Organization requireCurrent(
            TenantContext tenant,
            UUID organizationId,
            long expectedRevision) {
        Organization current = repository.findById(tenant, organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Organization does not exist"));
        if (current.revision() != expectedRevision) {
            throw new StaleWriteException("organization", organizationId, expectedRevision);
        }
        return current;
    }

    private void requireParentExists(TenantContext tenant, UUID parentId) {
        if (parentId != null && repository.findById(tenant, parentId).isEmpty()) {
            throw new IllegalArgumentException("parent Organization does not exist");
        }
    }

    private static void requireExpectedRevision(long expectedRevision) {
        if (expectedRevision < 1) {
            throw new IllegalArgumentException("expectedRevision must be positive");
        }
    }
}
