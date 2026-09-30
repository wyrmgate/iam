package io.wyrmgate.iam.identity.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.identity.domain.AttributeDefinitionVersion;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeCardinality;
import io.wyrmgate.iam.identity.domain.CanonicalValue;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded source-value mapper for the existing $.field[.nested] mapping convention.
 *
 * <p>Arrays are supported only as the terminal value of MULTI canonical attributes. Arbitrary
 * JSONPath expressions, filters, indexes and scripts are intentionally unsupported.</p>
 */
public final class SourceMappedValueExtractor {

    private final ObjectMapper json;

    public SourceMappedValueExtractor(ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    public Optional<String> nonBlankText(String sourceJson, String sourcePath) {
        return node(sourceJson, sourcePath)
                .filter(JsonNode::isTextual)
                .map(JsonNode::textValue)
                .filter(value -> !value.isBlank());
    }

    public Optional<List<CanonicalValue>> values(
            String sourceJson,
            String sourcePath,
            AttributeDefinitionVersion definition) {
        Objects.requireNonNull(definition, "definition");
        Optional<JsonNode> selected = node(sourceJson, sourcePath);
        if (selected.isEmpty() || selected.get().isNull()) {
            return Optional.empty();
        }

        if (definition.cardinality() == CanonicalAttributeCardinality.SINGLE) {
            return scalar(selected.get(), definition)
                    .map(value -> List.of(value));
        }

        JsonNode array = selected.get();
        if (!array.isArray() || array.isEmpty()) {
            return Optional.empty();
        }
        List<CanonicalValue> values = new ArrayList<>();
        for (JsonNode item : array) {
            Optional<CanonicalValue> value = scalar(item, definition);
            if (value.isEmpty()) {
                return Optional.empty();
            }
            values.add(value.get());
        }
        return Optional.of(List.copyOf(values));
    }

    private Optional<JsonNode> node(String sourceJson, String sourcePath) {
        if (sourceJson == null || sourceJson.isBlank()) {
            return Optional.empty();
        }
        if (sourcePath == null || !sourcePath.startsWith("$.") || sourcePath.length() < 3) {
            return Optional.empty();
        }
        String[] segments = sourcePath.substring(2).split("\\.");
        for (String segment : segments) {
            if (segment.isBlank()) {
                return Optional.empty();
            }
        }

        try {
            JsonNode current = json.readTree(sourceJson);
            if (current == null || !current.isObject()) {
                return Optional.empty();
            }
            for (String segment : segments) {
                current = current.get(segment);
                if (current == null) {
                    return Optional.empty();
                }
            }
            return Optional.of(current);
        } catch (JsonProcessingException invalid) {
            return Optional.empty();
        }
    }

    private static Optional<CanonicalValue> scalar(
            JsonNode node,
            AttributeDefinitionVersion definition) {
        try {
            return switch (definition.dataType()) {
                case STRING -> node.isTextual()
                        ? Optional.of(new CanonicalValue.StringValue(node.textValue()))
                        : Optional.empty();
                case BOOLEAN -> node.isBoolean()
                        ? Optional.of(new CanonicalValue.BooleanValue(node.booleanValue()))
                        : Optional.empty();
                case INTEGER -> node.isIntegralNumber()
                        ? Optional.of(new CanonicalValue.IntegerValue(node.longValue()))
                        : Optional.empty();
                case DECIMAL -> node.isNumber()
                        ? Optional.of(new CanonicalValue.DecimalValue(node.decimalValue()))
                        : Optional.empty();
                case DATE -> node.isTextual()
                        ? Optional.of(new CanonicalValue.DateValue(LocalDate.parse(node.textValue())))
                        : Optional.empty();
                case DATETIME -> node.isTextual()
                        ? Optional.of(new CanonicalValue.DateTimeValue(Instant.parse(node.textValue())))
                        : Optional.empty();
                case ENUM -> node.isTextual() && !node.textValue().isBlank()
                        ? Optional.of(new CanonicalValue.EnumValue(node.textValue()))
                        : Optional.empty();
            };
        } catch (DateTimeParseException invalidTemporal) {
            return Optional.empty();
        }
    }
}
