package io.wyrmgate.iam.testsupport;

import io.wyrmgate.iam.access.application.AccessAssignmentRepository;
import io.wyrmgate.iam.access.application.EffectiveAccessSemanticSupport;
import io.wyrmgate.iam.catalog.application.CatalogAccessTargetAvailabilityQuery;
import io.wyrmgate.iam.catalog.application.CatalogAccessTargetAvailabilityQueryService;
import io.wyrmgate.iam.catalog.application.CatalogAccessReferenceQuery;
import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQuery;
import io.wyrmgate.iam.catalog.application.CatalogAvailabilityPropagationService;
import io.wyrmgate.iam.catalog.application.CatalogAvailabilityPropagationStarter;
import io.wyrmgate.iam.catalog.application.CatalogRepository;
import io.wyrmgate.iam.catalog.application.RoleExpansionQueryService;
import io.wyrmgate.iam.catalog.application.RoleRepository;
import io.wyrmgate.iam.catalog.domain.CatalogAccessTargetAvailability;
import io.wyrmgate.iam.catalog.domain.CatalogAccessTargetKind;
import io.wyrmgate.iam.catalog.domain.CatalogAvailabilityCauseKind;
import io.wyrmgate.iam.catalog.persistence.JdbcCatalogAvailabilityPropagationRepository;
import io.wyrmgate.iam.catalog.persistence.JdbcCatalogAvailabilityPropagationWorkSink;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/** Shared integration-test composition for the accepted Catalog/Access semantic graph. */
public final class CatalogAccessTestSupport {

    private CatalogAccessTestSupport() {
    }

    public static CatalogAvailabilityPropagationStarter propagationStarter(
            JdbcTemplate jdbc,
            IdGenerator ids,
            TransactionExecutor transactions) {
        return new CatalogAvailabilityPropagationStarter(
                new JdbcCatalogAvailabilityPropagationRepository(jdbc),
                new JdbcCatalogAvailabilityPropagationWorkSink(
                        new JdbcOutboxRepository(jdbc), ids),
                ids,
                transactions);
    }

    public static CatalogAvailabilityPropagationService propagationService(
            JdbcTemplate jdbc,
            IdGenerator ids,
            TransactionExecutor transactions) {
        var outbox = new JdbcOutboxRepository(jdbc);
        var repository = new JdbcCatalogAvailabilityPropagationRepository(jdbc);
        var work = new JdbcCatalogAvailabilityPropagationWorkSink(outbox, ids);
        return new CatalogAvailabilityPropagationService(
                outbox,
                repository,
                new io.wyrmgate.iam.catalog.persistence
                        .JdbcCatalogAccessTargetAvailabilityFactSink(
                                outbox, ids, new com.fasterxml.jackson.databind.ObjectMapper()),
                new io.wyrmgate.iam.catalog.persistence.JdbcRoleExpansionFactSink(
                        outbox, ids),
                work,
                transactions);
    }

    public static CatalogAccessTargetAvailabilityQuery availability(
            CatalogRepository catalog,
            RoleRepository roles) {
        var expansion = new RoleExpansionQueryService(catalog, roles);
        return new CatalogAccessTargetAvailabilityQueryService(
                catalog, roles, expansion);
    }

    public static CatalogAccessTargetAvailabilityQuery alwaysAssignable() {
        return (tenant, targetKind, targetId) -> Optional.of(
                new CatalogAccessTargetAvailability(
                        targetKind,
                        targetId,
                        CatalogAccessTargetAvailability.Status.ASSIGNABLE,
                        targetKind == CatalogAccessTargetKind.ROLE
                                ? CatalogAvailabilityCauseKind.ROLE
                                : CatalogAvailabilityCauseKind.ENTITLEMENT,
                        targetId,
                        1));
    }

    public static EffectiveAccessSemanticSupport semanticSupport(
            AccessAssignmentRepository assignments,
            CatalogAccessTargetAvailabilityQuery availability) {
        return semanticSupport(
                assignments,
                availability,
                (tenant, roleId) ->
                        io.wyrmgate.iam.catalog.application.RoleExpansionQuery.Result
                                .unavailable(
                                        io.wyrmgate.iam.catalog.application
                                                .RoleExpansionQuery.Status.NOT_FOUND,
                                        roleId));
    }

    public static EffectiveAccessSemanticSupport semanticSupport(
            AccessAssignmentRepository assignments,
            CatalogAccessTargetAvailabilityQuery availability,
            io.wyrmgate.iam.catalog.application.RoleExpansionQuery roleExpansion) {
        IdentityAccessReferenceQuery identities = new IdentityAccessReferenceQuery() {
            @Override
            public boolean identityExists(
                    io.wyrmgate.iam.platform.tenant.TenantContext tenant,
                    java.util.UUID identityId) {
                return true;
            }

            @Override
            public IdentityAccessStatus accessStatus(
                    io.wyrmgate.iam.platform.tenant.TenantContext tenant,
                    java.util.UUID identityId) {
                return IdentityAccessStatus.eligible("ACTIVE", 1, 1);
            }

            @Override
            public PrincipalReference principal(
                    io.wyrmgate.iam.platform.tenant.TenantContext tenant,
                    java.util.UUID principalId) {
                return PrincipalReference.notFound();
            }

            @Override
            public PrincipalSelection selectUniqueActivePrincipal(
                    io.wyrmgate.iam.platform.tenant.TenantContext tenant,
                    java.util.UUID identityId,
                    java.util.UUID applicationTargetId) {
                return PrincipalSelection.none();
            }
        };
        CatalogAccessReferenceQuery catalogReferences =
                (tenant, entitlementId) ->
                        CatalogAccessReferenceQuery.EntitlementReference.notFound();
        return new EffectiveAccessSemanticSupport(
                assignments,
                identities,
                catalogReferences,
                availability,
                roleExpansion);
    }
}
