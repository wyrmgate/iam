package io.wyrmgate.iam.integration.application;

import io.wyrmgate.iam.integration.application.IntegrationOperationalStatusRepository.PagePosition;
import io.wyrmgate.iam.integration.application.IntegrationOperationalStatusRepository.ProvisioningAttemptStatus;
import io.wyrmgate.iam.integration.application.IntegrationOperationalStatusRepository.ProvisioningJobStatus;
import io.wyrmgate.iam.integration.application.IntegrationOperationalStatusRepository.ProvisioningTaskStatus;
import io.wyrmgate.iam.integration.application.IntegrationOperationalStatusRepository.ReconciliationRunStatus;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** Bounded Integration-owned queries for operator-visible process status. */
public final class IntegrationOperationalStatusService {
    public static final int MAX_PAGE_SIZE = 200;

    public record Page<T>(List<T> items, PagePosition nextPosition) {
        public Page {
            items = List.copyOf(Objects.requireNonNull(items, "items"));
        }
    }

    private final IntegrationOperationalStatusRepository repository;

    public IntegrationOperationalStatusService(IntegrationOperationalStatusRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public Optional<ProvisioningJobStatus> findProvisioningJob(TenantContext tenant, UUID id) {
        return repository.findProvisioningJob(requireTenant(tenant), Objects.requireNonNull(id, "id"));
    }

    public Page<ProvisioningJobStatus> listProvisioningJobs(
            TenantContext tenant, PagePosition after, int limit) {
        return page(repository.findProvisioningJobPage(requireTenant(tenant), after, fetchLimit(limit)),
                limit, value -> new PagePosition(value.createdAt(), value.id()));
    }

    public Optional<ProvisioningTaskStatus> findProvisioningTask(TenantContext tenant, UUID id) {
        return repository.findProvisioningTask(requireTenant(tenant), Objects.requireNonNull(id, "id"));
    }

    public Page<ProvisioningTaskStatus> listProvisioningTasks(
            TenantContext tenant, PagePosition after, int limit) {
        return page(repository.findProvisioningTaskPage(requireTenant(tenant), after, fetchLimit(limit)),
                limit, value -> new PagePosition(value.createdAt(), value.id()));
    }

    public Optional<ProvisioningAttemptStatus> findProvisioningAttempt(TenantContext tenant, UUID id) {
        return repository.findProvisioningAttempt(requireTenant(tenant), Objects.requireNonNull(id, "id"));
    }

    public Page<ProvisioningAttemptStatus> listProvisioningAttempts(
            TenantContext tenant, UUID taskId, PagePosition after, int limit) {
        Objects.requireNonNull(taskId, "taskId");
        return page(repository.findProvisioningAttemptPage(
                        requireTenant(tenant), taskId, after, fetchLimit(limit)),
                limit, value -> new PagePosition(value.completedAt(), value.id()));
    }

    public Optional<ReconciliationRunStatus> findReconciliationRun(TenantContext tenant, UUID id) {
        return repository.findReconciliationRun(requireTenant(tenant), Objects.requireNonNull(id, "id"));
    }

    public Page<ReconciliationRunStatus> listReconciliationRuns(
            TenantContext tenant, PagePosition after, int limit) {
        return page(repository.findReconciliationRunPage(requireTenant(tenant), after, fetchLimit(limit)),
                limit, value -> new PagePosition(value.createdAt(), value.id()));
    }

    private static TenantContext requireTenant(TenantContext tenant) {
        return Objects.requireNonNull(tenant, "tenant");
    }

    private static int fetchLimit(int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE_SIZE);
        }
        return limit + 1;
    }

    private static <T> Page<T> page(
            List<T> fetched, int limit, Function<T, PagePosition> position) {
        Objects.requireNonNull(fetched, "fetched");
        boolean hasMore = fetched.size() > limit;
        List<T> items = hasMore
                ? List.copyOf(fetched.subList(0, limit))
                : List.copyOf(fetched);
        PagePosition next = hasMore
                ? position.apply(items.get(items.size() - 1))
                : null;
        return new Page<>(items, next);
    }
}
