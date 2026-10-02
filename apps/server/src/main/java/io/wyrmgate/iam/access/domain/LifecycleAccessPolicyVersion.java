package io.wyrmgate.iam.access.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable Access-owned policy version for Joiner baseline and Mover access reconciliation. */
public record LifecycleAccessPolicyVersion(
        UUID id,
        long versionNumber,
        State state,
        List<Rule> rules,
        Instant createdAt,
        Instant activatedAt,
        Instant supersededAt) {

    public enum State {
        ACTIVE,
        SUPERSEDED
    }

    public enum PredicateKind {
        ALWAYS,
        CANONICAL_STRING_EQUALS,
        CANONICAL_BOOLEAN_EQUALS,
        CANONICAL_INTEGER_EQUALS,
        CANONICAL_DECIMAL_EQUALS,
        CANONICAL_DATE_EQUALS,
        CANONICAL_DATETIME_EQUALS,
        CANONICAL_ENUM_EQUALS
    }

    public record Rule(
            UUID ruleId,
            PredicateKind predicateKind,
            String canonicalKey,
            String expectedString,
            Boolean expectedBoolean,
            Long expectedInteger,
            BigDecimal expectedDecimal,
            LocalDate expectedDate,
            Instant expectedDateTime,
            String expectedEnum,
            AccessAssignment.TargetKind targetKind,
            UUID targetId) {
        public Rule {
            Objects.requireNonNull(ruleId, "ruleId");
            Objects.requireNonNull(predicateKind, "predicateKind");
            Objects.requireNonNull(targetKind, "targetKind");
            Objects.requireNonNull(targetId, "targetId");
            if (predicateKind == PredicateKind.ALWAYS) {
                if (canonicalKey != null || expectedString != null || expectedBoolean != null
                        || expectedInteger != null || expectedDecimal != null || expectedDate != null
                        || expectedDateTime != null || expectedEnum != null) {
                    throw new IllegalArgumentException("ALWAYS rule must not carry canonical predicate data");
                }
            } else {
                if (canonicalKey == null || canonicalKey.isBlank()) {
                    throw new IllegalArgumentException("canonicalKey is required");
                }
                int expectedValues = (expectedString == null ? 0 : 1)
                        + (expectedBoolean == null ? 0 : 1)
                        + (expectedInteger == null ? 0 : 1)
                        + (expectedDecimal == null ? 0 : 1)
                        + (expectedDate == null ? 0 : 1)
                        + (expectedDateTime == null ? 0 : 1)
                        + (expectedEnum == null ? 0 : 1);
                if (expectedValues != 1
                        || (predicateKind == PredicateKind.CANONICAL_STRING_EQUALS) != (expectedString != null)
                        || (predicateKind == PredicateKind.CANONICAL_BOOLEAN_EQUALS) != (expectedBoolean != null)
                        || (predicateKind == PredicateKind.CANONICAL_INTEGER_EQUALS) != (expectedInteger != null)
                        || (predicateKind == PredicateKind.CANONICAL_DECIMAL_EQUALS) != (expectedDecimal != null)
                        || (predicateKind == PredicateKind.CANONICAL_DATE_EQUALS) != (expectedDate != null)
                        || (predicateKind == PredicateKind.CANONICAL_DATETIME_EQUALS) != (expectedDateTime != null)
                        || (predicateKind == PredicateKind.CANONICAL_ENUM_EQUALS) != (expectedEnum != null)) {
                    throw new IllegalArgumentException("predicate kind must carry exactly its matching typed expected value");
                }
                if (expectedDecimal != null) {
                    expectedDecimal = expectedDecimal.stripTrailingZeros();
                    int integerDigits = expectedDecimal.precision() - expectedDecimal.scale();
                    if (expectedDecimal.scale() > 12 || integerDigits > 26) {
                        throw new IllegalArgumentException("expectedDecimal must fit numeric(38,12) without rounding");
                    }
                }
                if (expectedDateTime != null
                        && !expectedDateTime.equals(expectedDateTime.truncatedTo(ChronoUnit.MICROS))) {
                    throw new IllegalArgumentException("expectedDateTime must use microsecond precision");
                }
                if (expectedEnum != null && expectedEnum.isBlank()) {
                    throw new IllegalArgumentException("expectedEnum must not be blank");
                }
            }
        }
    }

    public LifecycleAccessPolicyVersion {
        Objects.requireNonNull(id, "id");
        if (versionNumber < 1) {
            throw new IllegalArgumentException("versionNumber must be positive");
        }
        Objects.requireNonNull(state, "state");
        rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        if (rules.size() > 100) {
            throw new IllegalArgumentException("lifecycle access policy supports at most 100 rules");
        }
        if (rules.stream().map(Rule::ruleId).distinct().count() != rules.size()) {
            throw new IllegalArgumentException("ruleId must be unique within policy version");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(activatedAt, "activatedAt");
        if (state == State.ACTIVE && supersededAt != null) {
            throw new IllegalArgumentException("ACTIVE policy must not be superseded");
        }
        if (state == State.SUPERSEDED
                && (supersededAt == null || supersededAt.isBefore(activatedAt))) {
            throw new IllegalArgumentException("SUPERSEDED policy requires ordered timestamps");
        }
    }
}
