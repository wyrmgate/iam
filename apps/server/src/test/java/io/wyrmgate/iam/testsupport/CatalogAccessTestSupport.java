package io.wyrmgate.iam.testsupport;

import io.wyrmgate.iam.access.application.AccessAssignmentRepository;
import io.wyrmgate.iam.access.application.EffectiveAccessSemanticSupport;
import io.wyrmgate.iam.catalog.application.CatalogAccessTargetAvailabilityQuery;
import io.wyrmgate.iam.catalog.application.CatalogAccessTargetAvailabilityQueryService;
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
            CatalogAccessTargetAvailabilityQuery availability,
            io.wyrmgate.iam.catalog.application.RoleExpansionQuery roleExpansion) {
        return new EffectiveAccessSemanticSupport(
                assignments, availability, roleExpansion);
    }
}
