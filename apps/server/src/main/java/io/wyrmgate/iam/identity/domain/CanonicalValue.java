package io.wyrmgate.iam.identity.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/** Strongly typed canonical attribute value; provider-native JSON never substitutes for these semantics. */
public sealed interface CanonicalValue permits CanonicalValue.StringValue,
        CanonicalValue.BooleanValue,
        CanonicalValue.IntegerValue,
        CanonicalValue.DecimalValue,
        CanonicalValue.DateValue,
        CanonicalValue.DateTimeValue,
        CanonicalValue.EnumValue {

    CanonicalAttributeType type();

    record StringValue(String value) implements CanonicalValue {
        public StringValue { Objects.requireNonNull(value, "value"); }
        @Override public CanonicalAttributeType type() { return CanonicalAttributeType.STRING; }
    }

    record BooleanValue(boolean value) implements CanonicalValue {
        @Override public CanonicalAttributeType type() { return CanonicalAttributeType.BOOLEAN; }
    }

    record IntegerValue(long value) implements CanonicalValue {
        @Override public CanonicalAttributeType type() { return CanonicalAttributeType.INTEGER; }
    }

    record DecimalValue(BigDecimal value) implements CanonicalValue {
        public DecimalValue { Objects.requireNonNull(value, "value"); }
        @Override public CanonicalAttributeType type() { return CanonicalAttributeType.DECIMAL; }
    }

    record DateValue(LocalDate value) implements CanonicalValue {
        public DateValue { Objects.requireNonNull(value, "value"); }
        @Override public CanonicalAttributeType type() { return CanonicalAttributeType.DATE; }
    }

    record DateTimeValue(Instant value) implements CanonicalValue {
        public DateTimeValue { Objects.requireNonNull(value, "value"); }
        @Override public CanonicalAttributeType type() { return CanonicalAttributeType.DATETIME; }
    }

    record EnumValue(String key) implements CanonicalValue {
        public EnumValue {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("enum key must not be blank");
            }
        }
        @Override public CanonicalAttributeType type() { return CanonicalAttributeType.ENUM; }
    }
}
