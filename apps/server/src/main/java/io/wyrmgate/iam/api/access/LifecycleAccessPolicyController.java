package io.wyrmgate.iam.api.access;

import io.wyrmgate.iam.access.application.LifecycleAccessPolicyRepository;
import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.access.domain.LifecycleAccessPolicyVersion;
import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.api.access.LifecycleAccessPolicyApiModels.ExpectedValueResource;
import io.wyrmgate.iam.api.access.LifecycleAccessPolicyApiModels.PolicyResource;
import io.wyrmgate.iam.api.access.LifecycleAccessPolicyApiModels.RuleResource;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public final class LifecycleAccessPolicyController {

    private final LifecycleAccessPolicyRepository repository;
    private final LifecycleAccessPolicyApiMutationService mutations;
    private final AdministrativeAuthorizationService authorization;
    private final IdGenerator ids;

    public LifecycleAccessPolicyController(
            LifecycleAccessPolicyRepository repository,
            LifecycleAccessPolicyApiMutationService mutations,
            AdministrativeAuthorizationService authorization,
            IdGenerator ids) {
        this.repository = repository;
        this.mutations = mutations;
        this.authorization = authorization;
        this.ids = ids;
    }

    @GetMapping("/lifecycle-access-policy")
    public ResponseEntity<PolicyResource> current(HttpServletRequest request) {
        UUID correlationId = AccessApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        if (!authorization.authorize(
                actor,
                AdministrativePermissions.LIFECYCLE_ACCESS_POLICY_READ,
                AdministrativeResource.collection("lifecycle-access-policy"),
                Instant.now()).allowed()) {
            throw AccessApiException.forbidden(correlationId);
        }
        var policy = repository.findActive(actor.tenant())
                .orElseThrow(() -> AccessApiException.notFound(correlationId));
        return ResponseEntity.ok()
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(resource(policy));
    }

    @PostMapping("/lifecycle-access-policy:activate")
    public ResponseEntity<PolicyResource> activate(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String,Object> body,
            HttpServletRequest request) {
        UUID correlationId = AccessApiRequestContext.resolveCorrelationId(request, ids);
        String key = requireIdempotencyKey(idempotencyKey, correlationId);
        List<LifecycleAccessPolicyVersion.Rule> rules = parseRules(body, correlationId);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        LifecycleAccessPolicyVersion result = mutations.activate(
                actor,
                rules,
                key,
                fingerprint(rules),
                Instant.now(),
                correlationId);
        return ResponseEntity.ok()
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(resource(result));
    }

    private static List<LifecycleAccessPolicyVersion.Rule> parseRules(
            Map<String,Object> body,
            UUID correlationId) {
        exact(body, Set.of("rules"), correlationId);
        Object raw = body.get("rules");
        if (!(raw instanceof List<?> items) || items.isEmpty() || items.size() > 100) {
            throw AccessApiException.validation(
                    correlationId, "rules", "invalid_size", "rules must contain 1 to 100 entries.");
        }
        List<LifecycleAccessPolicyVersion.Rule> rules = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (!(items.get(i) instanceof Map<?,?> rawRule)) {
                throw AccessApiException.validation(
                        correlationId, "rules", "invalid_shape", "each rule must be an object.");
            }
            rules.add(parseRule(stringMap(rawRule, correlationId), correlationId));
        }
        if (rules.stream().map(LifecycleAccessPolicyVersion.Rule::ruleId).distinct().count() != rules.size()) {
            throw AccessApiException.validation(
                    correlationId, "rules", "duplicate_rule_id", "ruleId values must be distinct.");
        }
        rules.sort(Comparator.comparing(rule -> rule.ruleId().toString()));
        return List.copyOf(rules);
    }

    private static LifecycleAccessPolicyVersion.Rule parseRule(
            Map<String,Object> rule,
            UUID correlationId) {
        Set<String> allowed = Set.of(
                "ruleId", "predicateKind", "canonicalKey",
                "expectedString", "expectedBoolean", "expectedInteger",
                "expectedDecimal", "expectedDate", "expectedDateTime", "expectedEnum",
                "expectedSet", "targetKind", "targetId");
        unknownOnly(rule, allowed, correlationId);

        UUID ruleId = uuid(rule.get("ruleId"), "ruleId", correlationId);
        LifecycleAccessPolicyVersion.PredicateKind kind =
                enumValue(rule.get("predicateKind"), LifecycleAccessPolicyVersion.PredicateKind.class,
                        "predicateKind", correlationId);
        String canonicalKey = optionalText(rule.get("canonicalKey"), "canonicalKey", correlationId);
        String expectedString = optionalText(rule.get("expectedString"), "expectedString", correlationId);
        Boolean expectedBoolean = optionalBoolean(rule.get("expectedBoolean"), "expectedBoolean", correlationId);
        Long expectedInteger = optionalLong(rule.get("expectedInteger"), "expectedInteger", correlationId);
        BigDecimal expectedDecimal = optionalDecimal(rule.get("expectedDecimal"), "expectedDecimal", correlationId);
        LocalDate expectedDate = optionalDate(rule.get("expectedDate"), "expectedDate", correlationId);
        Instant expectedDateTime = optionalInstant(rule.get("expectedDateTime"), "expectedDateTime", correlationId);
        String expectedEnum = optionalText(rule.get("expectedEnum"), "expectedEnum", correlationId);
        List<LifecycleAccessPolicyVersion.ExpectedValue> expectedSet =
                parseExpectedSet(rule.get("expectedSet"), correlationId);
        AccessAssignment.TargetKind targetKind =
                enumValue(rule.get("targetKind"), AccessAssignment.TargetKind.class, "targetKind", correlationId);
        UUID targetId = uuid(rule.get("targetId"), "targetId", correlationId);

        try {
            return new LifecycleAccessPolicyVersion.Rule(
                    ruleId, kind, canonicalKey, expectedString, expectedBoolean, expectedInteger,
                    expectedDecimal, expectedDate, expectedDateTime, expectedEnum,
                    expectedSet, targetKind, targetId);
        } catch (IllegalArgumentException invalid) {
            throw AccessApiException.validation(
                    correlationId, "rules", "invalid_rule", "rule typed shape is invalid for predicateKind.");
        }
    }

    private static List<LifecycleAccessPolicyVersion.ExpectedValue> parseExpectedSet(
            Object raw,
            UUID correlationId) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> items)) {
            throw AccessApiException.validation(
                    correlationId, "expectedSet", "invalid_shape", "expectedSet must be an array.");
        }
        List<LifecycleAccessPolicyVersion.ExpectedValue> values = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?,?> map)) {
                throw AccessApiException.validation(
                        correlationId, "expectedSet", "invalid_shape", "expectedSet values must be objects.");
            }
            Map<String,Object> value = stringMap(map, correlationId);
            unknownOnly(
                    value,
                    Set.of("type","stringValue","booleanValue","integerValue","decimalValue",
                            "dateValue","dateTimeValue","enumValue"),
                    correlationId);
            var type = enumValue(
                    value.get("type"),
                    LifecycleAccessPolicyVersion.ExpectedValue.Type.class,
                    "expectedSet.type",
                    correlationId);
            try {
                values.add(new LifecycleAccessPolicyVersion.ExpectedValue(
                        type,
                        optionalText(value.get("stringValue"), "stringValue", correlationId),
                        optionalBoolean(value.get("booleanValue"), "booleanValue", correlationId),
                        optionalLong(value.get("integerValue"), "integerValue", correlationId),
                        optionalDecimal(value.get("decimalValue"), "decimalValue", correlationId),
                        optionalDate(value.get("dateValue"), "dateValue", correlationId),
                        optionalInstant(value.get("dateTimeValue"), "dateTimeValue", correlationId),
                        optionalText(value.get("enumValue"), "enumValue", correlationId)));
            } catch (IllegalArgumentException invalid) {
                throw AccessApiException.validation(
                        correlationId, "expectedSet", "invalid_value", "expectedSet value typed shape is invalid.");
            }
        }
        values.sort(Comparator.comparing(v -> v.type().name() + ":" + String.valueOf(v.value())));
        return List.copyOf(values);
    }

    private static PolicyResource resource(LifecycleAccessPolicyVersion policy) {
        return new PolicyResource(
                policy.id(),
                policy.versionNumber(),
                policy.state().name(),
                policy.rules().stream().map(LifecycleAccessPolicyController::resource).toList(),
                policy.createdAt(),
                policy.activatedAt(),
                policy.supersededAt());
    }

    private static RuleResource resource(LifecycleAccessPolicyVersion.Rule rule) {
        return new RuleResource(
                rule.ruleId(),
                rule.predicateKind().name(),
                rule.canonicalKey(),
                rule.expectedString(),
                rule.expectedBoolean(),
                rule.expectedInteger(),
                rule.expectedDecimal() == null ? null : rule.expectedDecimal().toPlainString(),
                rule.expectedDate() == null ? null : rule.expectedDate().toString(),
                rule.expectedDateTime() == null ? null : rule.expectedDateTime().toString(),
                rule.expectedEnum(),
                rule.expectedSet().stream().map(LifecycleAccessPolicyController::resource).toList(),
                rule.targetKind().name(),
                rule.targetId());
    }

    private static ExpectedValueResource resource(LifecycleAccessPolicyVersion.ExpectedValue value) {
        return new ExpectedValueResource(
                value.type().name(),
                value.stringValue(),
                value.booleanValue(),
                value.integerValue(),
                value.decimalValue() == null ? null : value.decimalValue().toPlainString(),
                value.dateValue() == null ? null : value.dateValue().toString(),
                value.dateTimeValue() == null ? null : value.dateTimeValue().toString(),
                value.enumValue());
    }

    private static RequestFingerprint fingerprint(List<LifecycleAccessPolicyVersion.Rule> rules) {
        StringBuilder canonical = new StringBuilder("v1|");
        for (var rule : rules) {
            canonical.append(part(rule.ruleId().toString()));
            canonical.append(part(rule.predicateKind().name()));
            canonical.append(part(rule.canonicalKey()));
            canonical.append(part(rule.expectedString()));
            canonical.append(part(rule.expectedBoolean()));
            canonical.append(part(rule.expectedInteger()));
            canonical.append(part(rule.expectedDecimal()));
            canonical.append(part(rule.expectedDate()));
            canonical.append(part(rule.expectedDateTime()));
            canonical.append(part(rule.expectedEnum()));
            for (var value : rule.expectedSet()) {
                canonical.append(part(value.type().name()));
                canonical.append(part(value.value()));
            }
            canonical.append(part(rule.targetKind().name()));
            canonical.append(part(rule.targetId().toString()));
        }
        return RequestFingerprint.sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String part(Object value) {
        String text = value == null ? "<null>" : value.toString();
        return text.length() + ":" + text + "|";
    }

    private static String requireIdempotencyKey(String value, UUID correlationId) {
        if (value == null || value.isBlank() || value.length() > 200) {
            throw AccessApiException.validation(
                    correlationId, "Idempotency-Key", "invalid_header",
                    "Idempotency-Key is required and must be at most 200 characters.");
        }
        return value;
    }

    private static Map<String,Object> exact(
            Map<String,Object> body,
            Set<String> expected,
            UUID correlationId) {
        if (body == null) {
            throw AccessApiException.validation(
                    correlationId, "request", "required_object", "Request body must be an object.");
        }
        for (String field : expected) {
            if (!body.containsKey(field)) {
                throw AccessApiException.validation(
                        correlationId, field, "required", field + " is required.");
            }
        }
        unknownOnly(body, expected, correlationId);
        return body;
    }

    private static void unknownOnly(
            Map<String,Object> body,
            Set<String> allowed,
            UUID correlationId) {
        Set<String> unknown = new HashSet<>(body.keySet());
        unknown.removeAll(allowed);
        if (!unknown.isEmpty()) {
            String field = unknown.stream().sorted().findFirst().orElseThrow();
            throw AccessApiException.validation(
                    correlationId, field, "unknown_field", "Unknown field is not permitted.");
        }
    }

    private static Map<String,Object> stringMap(Map<?,?> map, UUID correlationId) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw AccessApiException.validation(
                        correlationId, "request", "invalid_object", "Object keys must be strings.");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static UUID uuid(Object raw, String field, UUID correlationId) {
        if (!(raw instanceof String text)) {
            throw AccessApiException.validation(
                    correlationId, field, "required_uuid", field + " must be a UUID string.");
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException invalid) {
            throw AccessApiException.validation(
                    correlationId, field, "invalid_uuid", field + " must be a UUID.");
        }
    }

    private static <E extends Enum<E>> E enumValue(
            Object raw, Class<E> type, String field, UUID correlationId) {
        if (!(raw instanceof String text)) {
            throw AccessApiException.validation(
                    correlationId, field, "required_enum", field + " must be a string enum.");
        }
        try {
            return Enum.valueOf(type, text);
        } catch (IllegalArgumentException invalid) {
            throw AccessApiException.validation(
                    correlationId, field, "invalid_enum", field + " contains an unsupported value.");
        }
    }

    private static String optionalText(Object raw, String field, UUID correlationId) {
        if (raw == null) return null;
        if (!(raw instanceof String text)) {
            throw AccessApiException.validation(
                    correlationId, field, "invalid_string", field + " must be a string.");
        }
        return text;
    }

    private static Boolean optionalBoolean(Object raw, String field, UUID correlationId) {
        if (raw == null) return null;
        if (!(raw instanceof Boolean value)) {
            throw AccessApiException.validation(
                    correlationId, field, "invalid_boolean", field + " must be boolean.");
        }
        return value;
    }

    private static Long optionalLong(Object raw, String field, UUID correlationId) {
        if (raw == null) return null;
        if (!(raw instanceof Number value)) {
            throw AccessApiException.validation(
                    correlationId, field, "invalid_integer", field + " must be an integer.");
        }
        long parsed = value.longValue();
        if (value instanceof Double d && d.doubleValue() != parsed
                || value instanceof Float f && f.floatValue() != parsed) {
            throw AccessApiException.validation(
                    correlationId, field, "invalid_integer", field + " must be an exact integer.");
        }
        return parsed;
    }

    private static BigDecimal optionalDecimal(Object raw, String field, UUID correlationId) {
        if (raw == null) return null;
        if (!(raw instanceof String text)) {
            throw AccessApiException.validation(
                    correlationId, field, "invalid_decimal", field + " must be an exact decimal string.");
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException invalid) {
            throw AccessApiException.validation(
                    correlationId, field, "invalid_decimal", field + " must be an exact decimal string.");
        }
    }

    private static LocalDate optionalDate(Object raw, String field, UUID correlationId) {
        if (raw == null) return null;
        if (!(raw instanceof String text)) {
            throw AccessApiException.validation(
                    correlationId, field, "invalid_date", field + " must be an ISO date string.");
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException invalid) {
            throw AccessApiException.validation(
                    correlationId, field, "invalid_date", field + " must be an ISO date string.");
        }
    }

    private static Instant optionalInstant(Object raw, String field, UUID correlationId) {
        if (raw == null) return null;
        if (!(raw instanceof String text)) {
            throw AccessApiException.validation(
                    correlationId, field, "invalid_datetime", field + " must be an ISO instant string.");
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException invalid) {
            throw AccessApiException.validation(
                    correlationId, field, "invalid_datetime", field + " must be an ISO instant string.");
        }
    }
}
