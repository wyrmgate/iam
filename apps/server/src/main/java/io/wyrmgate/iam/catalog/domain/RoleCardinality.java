package io.wyrmgate.iam.catalog.domain;

/** Catalog-owned bounded synchronous composition contract (ADR-0057). */
public final class RoleCardinality {
    public static final int MAX_DIRECT_MEMBERS = 1000;
    public static final int MAX_EXPANDED_PATHS = 1000;

    private RoleCardinality() {}

    public static void requireDirectMembers(int count) {
        if (count < 1 || count > MAX_DIRECT_MEMBERS) {
            throw new IllegalArgumentException("RoleVersion must contain between 1 and 1,000 direct members");
        }
    }

    public static void requireExpandedPaths(int count) {
        if (count < 0 || count > MAX_EXPANDED_PATHS) {
            throw new IllegalArgumentException("RoleVersion must not exceed 1,000 expanded entitlement paths");
        }
    }
}
