package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Identity-owned bounded point-in-time projections used by first-party Console overviews. */
public interface IdentityOperationsOverviewQuery {

    record IdentityTypeCount(String identityType, long count) {}

    record SourceSystemSummary(UUID sourceSystemId, String code, String name, long linkedIdentityCount) {}

    record SourceSystems(long totalCount, List<SourceSystemSummary> items) {
        public SourceSystems {
            items = List.copyOf(items);
        }
    }

    long activeIdentities(TenantContext tenant);

    long uncorrelatedPrincipals(TenantContext tenant);

    List<IdentityTypeCount> activeIdentityTypes(TenantContext tenant);

    SourceSystems sourceSystems(TenantContext tenant, int limit);

    Optional<String> identityDisplayName(TenantContext tenant, UUID identityId);
}
