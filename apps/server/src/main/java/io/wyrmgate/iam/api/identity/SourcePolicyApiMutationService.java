package io.wyrmgate.iam.api.identity;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.audit.application.AuditRecordDraft;
import io.wyrmgate.iam.audit.application.SecurityAuditPort;
import io.wyrmgate.iam.audit.domain.AuditOutcome;
import io.wyrmgate.iam.identity.application.SourceAbsencePolicyService;
import io.wyrmgate.iam.identity.application.SourceCorrelationPolicyService;
import io.wyrmgate.iam.identity.application.SourceCorrelationRepository;
import io.wyrmgate.iam.identity.application.SourceLifecyclePolicyService;
import io.wyrmgate.iam.identity.domain.IdentityLifecycleState;
import io.wyrmgate.iam.identity.domain.IdentityType;
import io.wyrmgate.iam.identity.domain.SourceAbsencePolicyVersion;
import io.wyrmgate.iam.identity.domain.SourceCorrelationPolicyVersion;
import io.wyrmgate.iam.identity.domain.SourceLifecyclePolicyVersion;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.Registration;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.RegistrationKind;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SourcePolicyApiMutationService {

    private static final Logger LOG = LoggerFactory.getLogger(SourcePolicyApiMutationService.class);

    private final AdministrativeAuthorizationService authorization;
    private final SourceCorrelationPolicyService correlationPolicies;
    private final SourceLifecyclePolicyService lifecyclePolicies;
    private final SourceAbsencePolicyService absencePolicies;
    private final SourceCorrelationRepository repository;
    private final JdbcIdempotencyRepository idempotency;
    private final TransactionExecutor transactions;
    private final SecurityAuditPort audit;
    private final IdGenerator ids;

    SourcePolicyApiMutationService(
            AdministrativeAuthorizationService authorization,
            SourceCorrelationPolicyService correlationPolicies,
            SourceLifecyclePolicyService lifecyclePolicies,
            SourceAbsencePolicyService absencePolicies,
            SourceCorrelationRepository repository,
            JdbcIdempotencyRepository idempotency,
            TransactionExecutor transactions,
            SecurityAuditPort audit,
            IdGenerator ids) {
        this.authorization=Objects.requireNonNull(authorization);
        this.correlationPolicies=Objects.requireNonNull(correlationPolicies);
        this.lifecyclePolicies=Objects.requireNonNull(lifecyclePolicies);
        this.absencePolicies=Objects.requireNonNull(absencePolicies);
        this.repository=Objects.requireNonNull(repository);
        this.idempotency=Objects.requireNonNull(idempotency);
        this.transactions=Objects.requireNonNull(transactions);
        this.audit=Objects.requireNonNull(audit);
        this.ids=Objects.requireNonNull(ids);
    }

    SourceCorrelationPolicyVersion activateCorrelation(
            AuthenticatedAdministrativeActor actor,
            UUID sourceSystemId,
            String canonicalKey,
            boolean createIdentityOnNoMatch,
            IdentityType createdIdentityType,
            String displayNameSourcePath,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return execute(
                actor,
                sourceSystemId,
                AdministrativePermissions.SOURCE_CORRELATION_POLICY_ACTIVATE,
                "source-correlation-policy",
                "api.identity.source-correlation-policy.activate.v1",
                "source-correlation-policy-version",
                "source-correlation-policy:activate",
                key,
                fingerprint,
                now,
                correlationId,
                () -> correlationPolicies.activate(
                        actor.tenant(),
                        sourceSystemId,
                        canonicalKey,
                        createIdentityOnNoMatch,
                        createdIdentityType,
                        displayNameSourcePath,
                        now),
                registration -> repository.findCorrelationPolicyById(
                                actor.tenant(), registration.resourceId())
                        .orElseThrow(() -> new IllegalStateException(
                                "idempotent source correlation policy result no longer exists")));
    }

    SourceLifecyclePolicyVersion activateLifecycle(
            AuthenticatedAdministrativeActor actor,
            UUID sourceSystemId,
            String sourcePath,
            Map<String, IdentityLifecycleState> mappings,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return execute(
                actor,
                sourceSystemId,
                AdministrativePermissions.SOURCE_LIFECYCLE_POLICY_ACTIVATE,
                "source-lifecycle-policy",
                "api.identity.source-lifecycle-policy.activate.v1",
                "source-lifecycle-policy-version",
                "source-lifecycle-policy:activate",
                key,
                fingerprint,
                now,
                correlationId,
                () -> lifecyclePolicies.activate(
                        actor.tenant(), sourceSystemId, sourcePath, mappings, now),
                registration -> repository.findLifecyclePolicyById(
                                actor.tenant(), registration.resourceId())
                        .orElseThrow(() -> new IllegalStateException(
                                "idempotent source lifecycle policy result no longer exists")));
    }

    SourceAbsencePolicyVersion activateAbsence(
            AuthenticatedAdministrativeActor actor,
            UUID sourceSystemId,
            int maxInferredTransitions,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return execute(
                actor,
                sourceSystemId,
                AdministrativePermissions.SOURCE_ABSENCE_POLICY_ACTIVATE,
                "source-absence-policy",
                "api.identity.source-absence-policy.activate.v1",
                "source-absence-policy-version",
                "source-absence-policy:activate",
                key,
                fingerprint,
                now,
                correlationId,
                () -> absencePolicies.activate(
                        actor.tenant(), sourceSystemId, maxInferredTransitions, now),
                registration -> repository.findAbsencePolicyById(
                                actor.tenant(), registration.resourceId())
                        .orElseThrow(() -> new IllegalStateException(
                                "idempotent source absence policy result no longer exists")));
    }

    private <T> T execute(
            AuthenticatedAdministrativeActor actor,
            UUID sourceSystemId,
            AdministrativePermission permission,
            String authorizationResourceType,
            String namespace,
            String resultResourceType,
            String auditAction,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId,
            java.util.function.Supplier<T> create,
            java.util.function.Function<Registration,T> replay) {
        try {
            T result = transactions.required(() -> {
                require(actor, sourceSystemId, permission, authorizationResourceType, now, correlationId);
                Registration registration=idempotency.register(
                        actor.tenant(), namespace, key, fingerprint, now, null);
                if (registration.kind()==RegistrationKind.REPLAY) {
                    requireReplay(registration, resultResourceType, correlationId);
                    return replay.apply(registration);
                }
                T created;
                try {
                    created=create.get();
                } catch (IllegalArgumentException | IllegalStateException invalid) {
                    throw IdentityApiException.validation(
                            correlationId,
                            "policy",
                            "invalid_policy",
                            "Source policy does not satisfy the current governed source/schema contract.");
                }
                UUID resourceId=resourceId(created);
                idempotency.complete(
                        actor.tenant(), namespace, key, fingerprint, resultResourceType, resourceId, now);
                return created;
            });
            recordOutcome(
                    actor, resourceId(result), resultResourceType, auditAction,
                    AuditOutcome.SUCCESS, now, correlationId);
            return result;
        } catch (RuntimeException failure) {
            recordOutcome(
                    actor, null, resultResourceType, auditAction,
                    auditOutcome(failure), now, correlationId);
            throw failure;
        }
    }

    private static UUID resourceId(Object value) {
        if (value instanceof SourceCorrelationPolicyVersion v) return v.id();
        if (value instanceof SourceLifecyclePolicyVersion v) return v.id();
        if (value instanceof SourceAbsencePolicyVersion v) return v.id();
        throw new IllegalArgumentException("unsupported policy version result");
    }

    private void require(
            AuthenticatedAdministrativeActor actor,
            UUID sourceSystemId,
            AdministrativePermission permission,
            String resourceType,
            Instant now,
            UUID correlationId) {
        if (!authorization.authorize(
                actor,
                permission,
                new AdministrativeResource(resourceType, sourceSystemId),
                now).allowed()) {
            throw IdentityApiException.forbidden(correlationId);
        }
    }

    private static void requireReplay(
            Registration registration,
            String resourceType,
            UUID correlationId) {
        if (!"COMPLETED".equals(registration.operationState())) {
            throw IdentityApiException.conflict(
                    correlationId,
                    "idempotency_in_progress",
                    "The same idempotency key is already being processed.");
        }
        if (!resourceType.equals(registration.resourceType()) || registration.resourceId()==null) {
            throw new IllegalStateException("completed source policy idempotency result is invalid");
        }
    }

    private static AuditOutcome auditOutcome(RuntimeException failure) {
        return failure instanceof IdentityApiException api
                        && api.status()==org.springframework.http.HttpStatus.FORBIDDEN
                ? AuditOutcome.DENIED
                : AuditOutcome.FAILURE;
    }

    private void recordOutcome(
            AuthenticatedAdministrativeActor actor,
            UUID resourceId,
            String resourceType,
            String action,
            AuditOutcome outcome,
            Instant now,
            UUID correlationId) {
        try {
            audit.append(
                    actor.tenant(),
                    new AuditRecordDraft(
                            ids.nextId(),
                            now,
                            actor.identityId(),
                            action,
                            resourceType,
                            resourceId,
                            outcome,
                            correlationId,
                            null));
        } catch (RuntimeException auditFailure) {
            LOG.warn(
                    "Source policy AuditRecord append failed; correlationId={} action={} outcome={}",
                    correlationId,
                    action,
                    outcome);
        }
    }
}
