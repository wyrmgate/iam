package io.wyrmgate.iam.api.catalog;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.catalog.application.RoleCommandService;
import io.wyrmgate.iam.catalog.application.RoleRepository;
import io.wyrmgate.iam.catalog.domain.Role;
import io.wyrmgate.iam.catalog.domain.RoleVersion;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.Registration;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.RegistrationKind;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

final class CatalogRoleApiMutationService {

    private final AdministrativeAuthorizationService authorization;
    private final RoleCommandService commands;
    private final RoleRepository roles;
    private final JdbcIdempotencyRepository idempotency;
    private final TransactionExecutor transactions;

    CatalogRoleApiMutationService(
            AdministrativeAuthorizationService authorization,
            RoleCommandService commands,
            RoleRepository roles,
            JdbcIdempotencyRepository idempotency,
            TransactionExecutor transactions) {
        this.authorization = Objects.requireNonNull(
                authorization, "authorization");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.roles = Objects.requireNonNull(roles, "roles");
        this.idempotency = Objects.requireNonNull(
                idempotency, "idempotency");
        this.transactions = Objects.requireNonNull(
                transactions, "transactions");
    }

    Role createRole(
            AuthenticatedAdministrativeActor actor,
            Role.RoleType type,
            UUID applicationId,
            String code,
            String name,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return transactions.required(() -> {
            require(
                    actor,
                    AdministrativePermissions.ROLE_CREATE,
                    AdministrativeResource.collection("role"),
                    now,
                    correlationId);
            Registration registration = register(
                    actor, "api.catalog.role.create.v1",
                    key, fingerprint, now);
            if (registration.kind() == RegistrationKind.REPLAY) {
                return replayRole(actor, registration, correlationId);
            }
            Role created = commands.createRole(
                    actor.tenant(),
                    type,
                    applicationId,
                    code,
                    name,
                    now);
            complete(
                    actor,
                    "api.catalog.role.create.v1",
                    key,
                    fingerprint,
                    "role",
                    created.id(),
                    now);
            return created;
        });
    }

    Role renameRole(
            AuthenticatedAdministrativeActor actor,
            UUID roleId,
            String name,
            long expectedRevision,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return transactions.required(() -> {
            require(
                    actor,
                    AdministrativePermissions.ROLE_UPDATE,
                    new AdministrativeResource("role", roleId),
                    now,
                    correlationId);
            ensureRole(actor, roleId, correlationId);
            Registration registration = register(
                    actor, "api.catalog.role.rename.v1",
                    key, fingerprint, now);
            if (registration.kind() == RegistrationKind.REPLAY) {
                return replayRole(actor, registration, correlationId);
            }
            Role updated = commands.renameRole(
                    actor.tenant(),
                    roleId,
                    name,
                    expectedRevision,
                    now);
            complete(
                    actor,
                    "api.catalog.role.rename.v1",
                    key,
                    fingerprint,
                    "role",
                    updated.id(),
                    now);
            return updated;
        });
    }

    Role retireRole(
            AuthenticatedAdministrativeActor actor,
            UUID roleId,
            long expectedRevision,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return transactions.required(() -> {
            require(
                    actor,
                    AdministrativePermissions.ROLE_RETIRE,
                    new AdministrativeResource("role", roleId),
                    now,
                    correlationId);
            ensureRole(actor, roleId, correlationId);
            Registration registration = register(
                    actor, "api.catalog.role.retire.v1",
                    key, fingerprint, now);
            if (registration.kind() == RegistrationKind.REPLAY) {
                return replayRole(actor, registration, correlationId);
            }
            Role retired = commands.retireRole(
                    actor.tenant(),
                    roleId,
                    expectedRevision,
                    now);
            complete(
                    actor,
                    "api.catalog.role.retire.v1",
                    key,
                    fingerprint,
                    "role",
                    retired.id(),
                    now);
            return retired;
        });
    }

    RoleVersion createVersion(
            AuthenticatedAdministrativeActor actor,
            UUID roleId,
            List<RoleCommandService.MemberSpec> members,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return transactions.required(() -> {
            require(
                    actor,
                    AdministrativePermissions.ROLE_VERSION_CREATE,
                    AdministrativeResource.collection("role-version"),
                    now,
                    correlationId);
            ensureRole(actor, roleId, correlationId);
            Registration registration = register(
                    actor, "api.catalog.role-version.create.v1",
                    key, fingerprint, now);
            if (registration.kind() == RegistrationKind.REPLAY) {
                return replayVersion(
                        actor, roleId, registration, correlationId);
            }
            RoleVersion created = commands.createDraftVersion(
                    actor.tenant(), roleId, members, now);
            complete(
                    actor,
                    "api.catalog.role-version.create.v1",
                    key,
                    fingerprint,
                    "role-version",
                    created.id(),
                    now);
            return created;
        });
    }

    RoleVersion validateVersion(
            AuthenticatedAdministrativeActor actor,
            UUID roleId,
            UUID roleVersionId,
            long expectedRevision,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return transactions.required(() -> {
            require(
                    actor,
                    AdministrativePermissions.ROLE_VERSION_VALIDATE,
                    new AdministrativeResource(
                            "role-version", roleVersionId),
                    now,
                    correlationId);
            ensureVersion(
                    actor, roleId, roleVersionId, correlationId);
            Registration registration = register(
                    actor, "api.catalog.role-version.validate.v1",
                    key, fingerprint, now);
            if (registration.kind() == RegistrationKind.REPLAY) {
                return replayVersion(
                        actor, roleId, registration, correlationId);
            }
            RoleVersion ready = commands.markReady(
                    actor.tenant(),
                    roleVersionId,
                    expectedRevision,
                    now);
            complete(
                    actor,
                    "api.catalog.role-version.validate.v1",
                    key,
                    fingerprint,
                    "role-version",
                    ready.id(),
                    now);
            return ready;
        });
    }

    RoleVersion activateVersion(
            AuthenticatedAdministrativeActor actor,
            UUID roleId,
            UUID roleVersionId,
            long expectedRevision,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return transactions.required(() -> {
            require(
                    actor,
                    AdministrativePermissions.ROLE_VERSION_ACTIVATE,
                    new AdministrativeResource(
                            "role-version", roleVersionId),
                    now,
                    correlationId);
            ensureVersion(
                    actor, roleId, roleVersionId, correlationId);
            Registration registration = register(
                    actor, "api.catalog.role-version.activate.v1",
                    key, fingerprint, now);
            if (registration.kind() == RegistrationKind.REPLAY) {
                return replayVersion(
                        actor, roleId, registration, correlationId);
            }
            RoleVersion active = commands.activate(
                    actor.tenant(),
                    roleVersionId,
                    expectedRevision,
                    now);
            complete(
                    actor,
                    "api.catalog.role-version.activate.v1",
                    key,
                    fingerprint,
                    "role-version",
                    active.id(),
                    now);
            return active;
        });
    }

    private Registration register(
            AuthenticatedAdministrativeActor actor,
            String namespace,
            String key,
            RequestFingerprint fingerprint,
            Instant now) {
        return idempotency.register(
                actor.tenant(),
                namespace,
                key,
                fingerprint,
                now,
                null);
    }

    private void complete(
            AuthenticatedAdministrativeActor actor,
            String namespace,
            String key,
            RequestFingerprint fingerprint,
            String resourceType,
            UUID resourceId,
            Instant now) {
        idempotency.complete(
                actor.tenant(),
                namespace,
                key,
                fingerprint,
                resourceType,
                resourceId,
                now);
    }

    private Role replayRole(
            AuthenticatedAdministrativeActor actor,
            Registration registration,
            UUID correlationId) {
        return replay(
                registration,
                "role",
                correlationId,
                () -> roles.findRole(
                                actor.tenant(),
                                registration.resourceId())
                        .orElseThrow());
    }

    private RoleVersion replayVersion(
            AuthenticatedAdministrativeActor actor,
            UUID roleId,
            Registration registration,
            UUID correlationId) {
        return replay(
                registration,
                "role-version",
                correlationId,
                () -> {
                    RoleVersion value = roles.findVersion(
                                    actor.tenant(),
                                    registration.resourceId())
                            .orElseThrow();
                    if (!roleId.equals(value.roleId())) {
                        throw new IllegalStateException(
                                "completed RoleVersion idempotency result belongs to another Role");
                    }
                    return value;
                });
    }

    private static <T> T replay(
            Registration registration,
            String resourceType,
            UUID correlationId,
            Supplier<T> loader) {
        if (!"COMPLETED".equals(registration.operationState())) {
            throw CatalogApiException.conflict(
                    correlationId,
                    "idempotency_in_progress",
                    "The same idempotency key is already being processed.");
        }
        if (!resourceType.equals(registration.resourceType())
                || registration.resourceId() == null) {
            throw new IllegalStateException(
                    "completed Catalog Role idempotency result is invalid");
        }
        return loader.get();
    }

    private void require(
            AuthenticatedAdministrativeActor actor,
            AdministrativePermission permission,
            AdministrativeResource resource,
            Instant now,
            UUID correlationId) {
        if (!authorization.authorize(
                actor, permission, resource, now).allowed()) {
            throw CatalogApiException.forbidden(correlationId);
        }
    }

    private void ensureRole(
            AuthenticatedAdministrativeActor actor,
            UUID roleId,
            UUID correlationId) {
        if (roles.findRole(actor.tenant(), roleId).isEmpty()) {
            throw CatalogApiException.notFound(correlationId);
        }
    }

    private void ensureVersion(
            AuthenticatedAdministrativeActor actor,
            UUID roleId,
            UUID roleVersionId,
            UUID correlationId) {
        RoleVersion version = roles.findVersion(
                        actor.tenant(), roleVersionId)
                .orElseThrow(() ->
                        CatalogApiException.notFound(correlationId));
        if (!roleId.equals(version.roleId())) {
            throw CatalogApiException.notFound(correlationId);
        }
    }
}
