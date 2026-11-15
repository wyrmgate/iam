package io.wyrmgate.iam.access.application;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;

/** Exact, current authoritative intent for a normalized technical tuple; never projection absence. */
@FunctionalInterface
public interface ExactTechnicalGrantDemandQuery {
    enum Demand { REQUIRED, NOT_REQUIRED, UNAVAILABLE }
    Demand demand(TenantContext tenant, UUID target, UUID entitlement, UUID principal, Instant at);
}
