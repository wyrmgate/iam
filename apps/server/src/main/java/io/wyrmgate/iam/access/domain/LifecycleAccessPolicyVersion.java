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
        CANONICAL_ENUM_EQUALS,
        CANONICAL_STRING_CONTAINS,
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
        CANONICAL_ENUM_CONTAINS_ALL
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
            List<ExpectedValue> expectedSet,
            AccessAssignment.TargetKind targetKind,
            UUID targetId) {
        public Rule {
            Objects.requireNonNull(ruleId, "ruleId");
            Objects.requireNonNull(predicateKind, "predicateKind");
            Objects.requireNonNull(targetKind, "targetKind");
            Objects.requireNonNull(targetId, "targetId");
            expectedSet = List.copyOf(Objects.requireNonNull(expectedSet, "expectedSet"));
            if (predicateKind == PredicateKind.ALWAYS) {
                if (canonicalKey != null || expectedString != null || expectedBoolean != null
                        || expectedInteger != null || expectedDecimal != null || expectedDate != null
                        || expectedDateTime != null || expectedEnum != null || !expectedSet.isEmpty()) {
                    throw new IllegalArgumentException("ALWAYS rule must not carry canonical predicate data");
                }
            } else {
                if (canonicalKey == null || canonicalKey.isBlank()) {
                    throw new IllegalArgumentException("canonicalKey is required");
                }
                if (isExpectedSetPredicate(predicateKind)) {
                    if (expectedString != null || expectedBoolean != null || expectedInteger != null
                            || expectedDecimal != null || expectedDate != null || expectedDateTime != null
                            || expectedEnum != null) {
                        throw new IllegalArgumentException("expected-set predicate must not carry scalar expected value");
                    }
                    if (expectedSet.size() < 2 || expectedSet.size() > 20) {
                        throw new IllegalArgumentException("expected-set predicate requires 2 to 20 values");
                    }
                    ExpectedValue.Type required = expectedType(predicateKind);
                    if (expectedSet.stream().anyMatch(value -> value.type() != required)) {
                        throw new IllegalArgumentException("expected-set values must match predicate type");
                    }
                    if (expectedSet.stream().distinct().count() != expectedSet.size()) {
                        throw new IllegalArgumentException("expected-set values must be distinct");
                    }
                } else {
                    if (!expectedSet.isEmpty()) {
                        throw new IllegalArgumentException("scalar/contains predicate must not carry expected set");
                    }
                    int expectedValues = (expectedString == null ? 0 : 1)
                            + (expectedBoolean == null ? 0 : 1)
                            + (expectedInteger == null ? 0 : 1)
                            + (expectedDecimal == null ? 0 : 1)
                            + (expectedDate == null ? 0 : 1)
                            + (expectedDateTime == null ? 0 : 1)
                            + (expectedEnum == null ? 0 : 1);
                    if (expectedValues != 1
                            || expectsString(predicateKind) != (expectedString != null)
                            || expectsBoolean(predicateKind) != (expectedBoolean != null)
                            || expectsInteger(predicateKind) != (expectedInteger != null)
                            || expectsDecimal(predicateKind) != (expectedDecimal != null)
                            || expectsDate(predicateKind) != (expectedDate != null)
                            || expectsDateTime(predicateKind) != (expectedDateTime != null)
                            || expectsEnum(predicateKind) != (expectedEnum != null)) {
                        throw new IllegalArgumentException("predicate kind must carry exactly its matching typed expected value");
                    }
                    if (expectedDecimal != null) {
                        expectedDecimal = normalizeDecimal(expectedDecimal);
                    }
                    if (expectedDateTime != null) {
                        validateDateTime(expectedDateTime);
                    }
                    if (expectedEnum != null && expectedEnum.isBlank()) {
                        throw new IllegalArgumentException("expectedEnum must not be blank");
                    }
                }
            }
        }

        public Rule(
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
            this(
                    ruleId, predicateKind, canonicalKey, expectedString, expectedBoolean, expectedInteger,
                    expectedDecimal, expectedDate, expectedDateTime, expectedEnum, List.of(), targetKind, targetId);
        }
    }

    public record ExpectedValue(
            Type type,
            String stringValue,
            Boolean booleanValue,
            Long integerValue,
            BigDecimal decimalValue,
            LocalDate dateValue,
            Instant dateTimeValue,
            String enumValue) {

        public enum Type {
            STRING,
            BOOLEAN,
            INTEGER,
            DECIMAL,
            DATE,
            DATETIME,
            ENUM
        }

        public ExpectedValue {
            Objects.requireNonNull(type, "type");
            int present = (stringValue == null ? 0 : 1)
                    + (booleanValue == null ? 0 : 1)
                    + (integerValue == null ? 0 : 1)
                    + (decimalValue == null ? 0 : 1)
                    + (dateValue == null ? 0 : 1)
                    + (dateTimeValue == null ? 0 : 1)
                    + (enumValue == null ? 0 : 1);
            if (present != 1
                    || (type == Type.STRING) != (stringValue != null)
                    || (type == Type.BOOLEAN) != (booleanValue != null)
                    || (type == Type.INTEGER) != (integerValue != null)
                    || (type == Type.DECIMAL) != (decimalValue != null)
                    || (type == Type.DATE) != (dateValue != null)
                    || (type == Type.DATETIME) != (dateTimeValue != null)
                    || (type == Type.ENUM) != (enumValue != null)) {
                throw new IllegalArgumentException("expected set value must carry exactly its typed value");
            }
            if (decimalValue != null) {
                decimalValue = normalizeDecimal(decimalValue);
            }
            if (dateTimeValue != null) {
                validateDateTime(dateTimeValue);
            }
            if (enumValue != null && enumValue.isBlank()) {
                throw new IllegalArgumentException("enumValue must not be blank");
            }
        }

        public static ExpectedValue string(String value) {
            return new ExpectedValue(Type.STRING, Objects.requireNonNull(value), null, null, null, null, null, null);
        }

        public static ExpectedValue bool(boolean value) {
            return new ExpectedValue(Type.BOOLEAN, null, value, null, null, null, null, null);
        }

        public static ExpectedValue integer(long value) {
            return new ExpectedValue(Type.INTEGER, null, null, value, null, null, null, null);
        }

        public static ExpectedValue decimal(BigDecimal value) {
            return new ExpectedValue(Type.DECIMAL, null, null, null, Objects.requireNonNull(value), null, null, null);
        }

        public static ExpectedValue date(LocalDate value) {
            return new ExpectedValue(Type.DATE, null, null, null, null, Objects.requireNonNull(value), null, null);
        }

        public static ExpectedValue dateTime(Instant value) {
            return new ExpectedValue(Type.DATETIME, null, null, null, null, null, Objects.requireNonNull(value), null);
        }

        public static ExpectedValue enumKey(String value) {
            return new ExpectedValue(Type.ENUM, null, null, null, null, null, null, Objects.requireNonNull(value));
        }

        public Object value() {
            return switch (type) {
                case STRING -> stringValue;
                case BOOLEAN -> booleanValue;
                case INTEGER -> integerValue;
                case DECIMAL -> decimalValue;
                case DATE -> dateValue;
                case DATETIME -> dateTimeValue;
                case ENUM -> enumValue;
            };
        }
    }

    private static boolean isExpectedSetPredicate(PredicateKind kind) {
        return switch (kind) {
            case CANONICAL_STRING_CONTAINS_ANY,
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

    private static ExpectedValue.Type expectedType(PredicateKind kind) {
        return switch (kind) {
            case CANONICAL_STRING_CONTAINS_ANY, CANONICAL_STRING_CONTAINS_ALL -> ExpectedValue.Type.STRING;
            case CANONICAL_BOOLEAN_CONTAINS_ANY, CANONICAL_BOOLEAN_CONTAINS_ALL -> ExpectedValue.Type.BOOLEAN;
            case CANONICAL_INTEGER_CONTAINS_ANY, CANONICAL_INTEGER_CONTAINS_ALL -> ExpectedValue.Type.INTEGER;
            case CANONICAL_DECIMAL_CONTAINS_ANY, CANONICAL_DECIMAL_CONTAINS_ALL -> ExpectedValue.Type.DECIMAL;
            case CANONICAL_DATE_CONTAINS_ANY, CANONICAL_DATE_CONTAINS_ALL -> ExpectedValue.Type.DATE;
            case CANONICAL_DATETIME_CONTAINS_ANY, CANONICAL_DATETIME_CONTAINS_ALL -> ExpectedValue.Type.DATETIME;
            case CANONICAL_ENUM_CONTAINS_ANY, CANONICAL_ENUM_CONTAINS_ALL -> ExpectedValue.Type.ENUM;
            default -> throw new IllegalArgumentException("predicate is not an expected-set predicate");
        };
    }

    private static BigDecimal normalizeDecimal(BigDecimal value) {
        BigDecimal normalized = Objects.requireNonNull(value, "decimal").stripTrailingZeros();
        int integerDigits = normalized.precision() - normalized.scale();
        if (normalized.scale() > 12 || integerDigits > 26) {
            throw new IllegalArgumentException("decimal must fit numeric(38,12) without rounding");
        }
        return normalized;
    }

    private static void validateDateTime(Instant value) {
        Objects.requireNonNull(value, "dateTime");
        if (!value.equals(value.truncatedTo(ChronoUnit.MICROS))) {
            throw new IllegalArgumentException("dateTime must use microsecond precision");
        }
    }

    private static boolean expectsString(PredicateKind kind) {
        return kind == PredicateKind.CANONICAL_STRING_EQUALS
                || kind == PredicateKind.CANONICAL_STRING_CONTAINS;
    }

    private static boolean expectsBoolean(PredicateKind kind) {
        return kind == PredicateKind.CANONICAL_BOOLEAN_EQUALS
                || kind == PredicateKind.CANONICAL_BOOLEAN_CONTAINS;
    }

    private static boolean expectsInteger(PredicateKind kind) {
        return kind == PredicateKind.CANONICAL_INTEGER_EQUALS
                || kind == PredicateKind.CANONICAL_INTEGER_CONTAINS;
    }

    private static boolean expectsDecimal(PredicateKind kind) {
        return kind == PredicateKind.CANONICAL_DECIMAL_EQUALS
                || kind == PredicateKind.CANONICAL_DECIMAL_CONTAINS;
    }

    private static boolean expectsDate(PredicateKind kind) {
        return kind == PredicateKind.CANONICAL_DATE_EQUALS
                || kind == PredicateKind.CANONICAL_DATE_CONTAINS;
    }

    private static boolean expectsDateTime(PredicateKind kind) {
        return kind == PredicateKind.CANONICAL_DATETIME_EQUALS
                || kind == PredicateKind.CANONICAL_DATETIME_CONTAINS;
    }

    private static boolean expectsEnum(PredicateKind kind) {
        return kind == PredicateKind.CANONICAL_ENUM_EQUALS
                || kind == PredicateKind.CANONICAL_ENUM_CONTAINS;
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
