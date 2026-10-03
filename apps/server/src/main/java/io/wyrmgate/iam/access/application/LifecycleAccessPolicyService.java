package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.access.domain.LifecycleAccessPolicyVersion;
import io.wyrmgate.iam.catalog.application.CatalogAccessReferenceQuery;
import io.wyrmgate.iam.catalog.application.RoleExpansionQuery;
import io.wyrmgate.iam.identity.application.IdentityLifecycleAccessQuery;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class LifecycleAccessPolicyService {

    private final LifecycleAccessPolicyRepository policies;
    private final IdentityLifecycleAccessQuery identities;
    private final CatalogAccessReferenceQuery catalog;
    private final RoleExpansionQuery roles;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;

    public LifecycleAccessPolicyService(
            LifecycleAccessPolicyRepository policies,
            IdentityLifecycleAccessQuery identities,
            CatalogAccessReferenceQuery catalog,
            RoleExpansionQuery roles,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this.policies=Objects.requireNonNull(policies);
        this.identities=Objects.requireNonNull(identities);
        this.catalog=Objects.requireNonNull(catalog);
        this.roles=Objects.requireNonNull(roles);
        this.ids=Objects.requireNonNull(ids);
        this.transactions=Objects.requireNonNull(transactions);
    }

    public LifecycleAccessPolicyVersion activate(
            TenantContext tenant,
            List<LifecycleAccessPolicyVersion.Rule> rules,
            Instant now) {
        rules=List.copyOf(Objects.requireNonNull(rules));
        if (rules.isEmpty() || rules.size()>100) {
            throw new IllegalArgumentException("policy requires between 1 and 100 rules");
        }
        for (var rule:rules) {
            if (rule.predicateKind()!=LifecycleAccessPolicyVersion.PredicateKind.ALWAYS) {
                boolean supported = isMultiPredicate(rule.predicateKind())
                        ? identities.supportsPolicyMultiAttribute(
                                tenant, rule.canonicalKey(), scalarType(rule.predicateKind()))
                        : identities.supportsPolicyScalarAttribute(
                                tenant, rule.canonicalKey(), scalarType(rule.predicateKind()));
                if (!supported) {
                    throw new IllegalArgumentException(
                            "canonical predicate must reference matching active policy-addressable supported attribute/cardinality");
                }
            }
            if (rule.targetKind()==AccessAssignment.TargetKind.ENTITLEMENT) {
                if (catalog.resolveActiveEntitlement(tenant,rule.targetId()).status()
                        != CatalogAccessReferenceQuery.Status.VALID) {
                    throw new IllegalArgumentException("policy Entitlement target is not active/assignable");
                }
            } else {
                var expansion=roles.expandCurrent(tenant,rule.targetId());
                if (expansion.status()!=RoleExpansionQuery.Status.AVAILABLE || expansion.paths().isEmpty()) {
                    throw new IllegalArgumentException("policy Role target is not active/assignable");
                }
            }
        }
        var immutable=rules;
        return transactions.required(() -> policies.replaceActive(
                tenant,immutable,now,ids.nextId()));
    }
    private static boolean isMultiPredicate(
            LifecycleAccessPolicyVersion.PredicateKind kind) {
        return switch (kind) {
            case CANONICAL_STRING_CONTAINS,
                    CANONICAL_BOOLEAN_CONTAINS,
                    CANONICAL_INTEGER_CONTAINS,
                    CANONICAL_DECIMAL_CONTAINS,
                    CANONICAL_DATE_CONTAINS,
                    CANONICAL_DATETIME_CONTAINS,
                    CANONICAL_ENUM_CONTAINS,
                    CANONICAL_STRING_CONTAINS_ANY,
                    CANONICAL_BOOLEAN_CONTAINS_ANY,
                    CANONICAL_INTEGER_CONTAINS_ANY,
                    CANONICAL_DECIMAL_CONTAINS_ANY,
                    CANONICAL_DATE_CONTAINS_ANY,
                    CANONICAL_DATETIME_CONTAINS_ANY,
                    CANONICAL_ENUM_CONTAINS_ANY,
                    CANONICAL_STRING_CONTAINS_ALL,
                    CANONICAL_BOOLEAN_CONTAINS_ALL,
                    CANONICAL_INTEGER_CONTAINS_ALL,
                    CANONICAL_DECIMAL_CONTAINS_ALL,
                    CANONICAL_DATE_CONTAINS_ALL,
                    CANONICAL_DATETIME_CONTAINS_ALL,
                    CANONICAL_ENUM_CONTAINS_ALL -> true;
            default -> false;
        };
    }

    private static IdentityLifecycleAccessQuery.ScalarType scalarType(
            LifecycleAccessPolicyVersion.PredicateKind kind) {
        return switch (kind) {
            case CANONICAL_STRING_EQUALS, CANONICAL_STRING_CONTAINS,
                    CANONICAL_STRING_CONTAINS_ANY, CANONICAL_STRING_CONTAINS_ALL ->
                    IdentityLifecycleAccessQuery.ScalarType.STRING;
            case CANONICAL_BOOLEAN_EQUALS, CANONICAL_BOOLEAN_CONTAINS,
                    CANONICAL_BOOLEAN_CONTAINS_ANY, CANONICAL_BOOLEAN_CONTAINS_ALL ->
                    IdentityLifecycleAccessQuery.ScalarType.BOOLEAN;
            case CANONICAL_INTEGER_EQUALS, CANONICAL_INTEGER_CONTAINS,
                    CANONICAL_INTEGER_CONTAINS_ANY, CANONICAL_INTEGER_CONTAINS_ALL ->
                    IdentityLifecycleAccessQuery.ScalarType.INTEGER;
            case CANONICAL_DECIMAL_EQUALS, CANONICAL_DECIMAL_CONTAINS,
                    CANONICAL_DECIMAL_CONTAINS_ANY, CANONICAL_DECIMAL_CONTAINS_ALL ->
                    IdentityLifecycleAccessQuery.ScalarType.DECIMAL;
            case CANONICAL_DATE_EQUALS, CANONICAL_DATE_CONTAINS,
                    CANONICAL_DATE_CONTAINS_ANY, CANONICAL_DATE_CONTAINS_ALL ->
                    IdentityLifecycleAccessQuery.ScalarType.DATE;
            case CANONICAL_DATETIME_EQUALS, CANONICAL_DATETIME_CONTAINS,
                    CANONICAL_DATETIME_CONTAINS_ANY, CANONICAL_DATETIME_CONTAINS_ALL ->
                    IdentityLifecycleAccessQuery.ScalarType.DATETIME;
            case CANONICAL_ENUM_EQUALS, CANONICAL_ENUM_CONTAINS,
                    CANONICAL_ENUM_CONTAINS_ANY, CANONICAL_ENUM_CONTAINS_ALL ->
                    IdentityLifecycleAccessQuery.ScalarType.ENUM;
            case ALWAYS -> throw new IllegalArgumentException("ALWAYS has no scalar type");
        };
    }
}
