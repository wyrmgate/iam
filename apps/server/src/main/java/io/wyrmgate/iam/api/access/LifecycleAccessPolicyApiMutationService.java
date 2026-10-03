package io.wyrmgate.iam.api.access;

import io.wyrmgate.iam.access.application.LifecycleAccessPolicyRepository;
import io.wyrmgate.iam.access.application.LifecycleAccessPolicyService;
import io.wyrmgate.iam.access.domain.LifecycleAccessPolicyVersion;
import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.audit.application.AuditRecordDraft;
import io.wyrmgate.iam.audit.application.SecurityAuditPort;
import io.wyrmgate.iam.audit.domain.AuditOutcome;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.RegistrationKind;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class LifecycleAccessPolicyApiMutationService {

    private static final Logger LOG =
            LoggerFactory.getLogger(LifecycleAccessPolicyApiMutationService.class);
    private static final String NAMESPACE = "api.access.lifecycle-policy.activate.v1";
    private static final String RESOURCE_TYPE = "lifecycle-access-policy-version";

    private final AdministrativeAuthorizationService authorization;
    private final LifecycleAccessPolicyService service;
    private final LifecycleAccessPolicyRepository repository;
    private final JdbcIdempotencyRepository idempotency;
    private final TransactionExecutor transactions;
    private final SecurityAuditPort audit;
    private final IdGenerator ids;

    LifecycleAccessPolicyApiMutationService(
            AdministrativeAuthorizationService authorization,
            LifecycleAccessPolicyService service,
            LifecycleAccessPolicyRepository repository,
            JdbcIdempotencyRepository idempotency,
            TransactionExecutor transactions,
            SecurityAuditPort audit,
            IdGenerator ids) {
        this.authorization = Objects.requireNonNull(authorization);
        this.service = Objects.requireNonNull(service);
        this.repository = Objects.requireNonNull(repository);
        this.idempotency = Objects.requireNonNull(idempotency);
        this.transactions = Objects.requireNonNull(transactions);
        this.audit = Objects.requireNonNull(audit);
        this.ids = Objects.requireNonNull(ids);
    }

    LifecycleAccessPolicyVersion activate(
            AuthenticatedAdministrativeActor actor,
            List<LifecycleAccessPolicyVersion.Rule> rules,
            String idempotencyKey,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        try {
            LifecycleAccessPolicyVersion result = transactions.required(() -> {
                require(actor, AdministrativePermissions.LIFECYCLE_ACCESS_POLICY_ACTIVATE, now, correlationId);
                var registration = idempotency.register(
                        actor.tenant(), NAMESPACE, idempotencyKey, fingerprint, now, null);
                if (registration.kind() == RegistrationKind.REPLAY) {
                    return replay(actor, registration, correlationId);
                }

                LifecycleAccessPolicyVersion activated;
                try {
                    activated = service.activate(actor.tenant(), rules, now);
                } catch (IllegalArgumentException | IllegalStateException invalid) {
                    throw AccessApiException.validation(
                            correlationId,
                            "rules",
                            "invalid_policy",
                            "Lifecycle access policy does not satisfy the active governed schema/target contract.");
                }

                idempotency.complete(
                        actor.tenant(),
                        NAMESPACE,
                        idempotencyKey,
                        fingerprint,
                        RESOURCE_TYPE,
                        activated.id(),
                        now);
                return activated;
            });
            recordOutcome(actor, result.id(), AuditOutcome.SUCCESS, now, correlationId);
            return result;
        } catch (RuntimeException failure) {
            recordOutcome(actor, null, auditOutcome(failure), now, correlationId);
            throw failure;
        }
    }

    private LifecycleAccessPolicyVersion replay(
            AuthenticatedAdministrativeActor actor,
            JdbcIdempotencyRepository.Registration registration,
            UUID correlationId) {
        if (!"COMPLETED".equals(registration.operationState())) {
            throw AccessApiException.conflict(
                    correlationId,
                    "idempotency_in_progress",
                    "The same idempotency key is already being processed.");
        }
        if (!RESOURCE_TYPE.equals(registration.resourceType()) || registration.resourceId() == null) {
            throw new IllegalStateException("completed lifecycle policy idempotency result is invalid");
        }
        return repository.findById(actor.tenant(), registration.resourceId())
                .orElseThrow(() -> new IllegalStateException(
                        "idempotent lifecycle policy result no longer exists"));
    }

    private void require(
            AuthenticatedAdministrativeActor actor,
            io.wyrmgate.iam.administration.domain.AdministrativePermission permission,
            Instant now,
            UUID correlationId) {
        if (!authorization.authorize(
                actor,
                permission,
                AdministrativeResource.collection("lifecycle-access-policy"),
                now).allowed()) {
            throw AccessApiException.forbidden(correlationId);
        }
    }

    private static AuditOutcome auditOutcome(RuntimeException failure) {
        return failure instanceof AccessApiException api
                        && api.status() == org.springframework.http.HttpStatus.FORBIDDEN
                ? AuditOutcome.DENIED
                : AuditOutcome.FAILURE;
    }

    private void recordOutcome(
            AuthenticatedAdministrativeActor actor,
            UUID policyVersionId,
            AuditOutcome outcome,
            Instant occurredAt,
            UUID correlationId) {
        try {
            audit.append(
                    actor.tenant(),
                    new AuditRecordDraft(
                            ids.nextId(),
                            occurredAt,
                            actor.identityId(),
                            "lifecycle-access-policy:activate",
                            RESOURCE_TYPE,
                            policyVersionId,
                            outcome,
                            correlationId,
                            null));
        } catch (RuntimeException auditFailure) {
            LOG.warn(
                    "Lifecycle access policy AuditRecord append failed; correlationId={} outcome={}",
                    correlationId,
                    outcome);
        }
    }
}
