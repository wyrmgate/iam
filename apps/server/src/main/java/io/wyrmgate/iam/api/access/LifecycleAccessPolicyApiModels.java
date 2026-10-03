package io.wyrmgate.iam.api.access;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class LifecycleAccessPolicyApiModels {
    private LifecycleAccessPolicyApiModels() {}

    record ExpectedValueResource(
            String type,
            String stringValue,
            Boolean booleanValue,
            Long integerValue,
            String decimalValue,
            String dateValue,
            String dateTimeValue,
            String enumValue) {}

    record RuleResource(
            UUID ruleId,
            String predicateKind,
            String canonicalKey,
            String expectedString,
            Boolean expectedBoolean,
            Long expectedInteger,
            String expectedDecimal,
            String expectedDate,
            String expectedDateTime,
            String expectedEnum,
            List<ExpectedValueResource> expectedSet,
            String targetKind,
            UUID targetId) {}

    record PolicyResource(
            UUID id,
            long versionNumber,
            String state,
            List<RuleResource> rules,
            Instant createdAt,
            Instant activatedAt,
            Instant supersededAt) {}
}
