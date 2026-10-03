package io.wyrmgate.iam.api.identity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class SourcePolicyApiModels {
    private SourcePolicyApiModels() {}

    record CorrelationPolicyResource(
            UUID id,
            UUID sourceSystemId,
            UUID matchAttributeDefinitionVersionId,
            UUID matchMappingVersionId,
            long versionNumber,
            boolean createIdentityOnNoMatch,
            String createdIdentityType,
            String displayNameSourcePath,
            String state,
            Instant createdAt,
            Instant activatedAt,
            Instant supersededAt) {}

    record LifecycleRuleResource(
            String sourceValue,
            String targetState) {}

    record LifecyclePolicyResource(
            UUID id,
            UUID sourceSystemId,
            String sourcePath,
            long versionNumber,
            List<LifecycleRuleResource> rules,
            String state,
            Instant createdAt,
            Instant activatedAt,
            Instant supersededAt) {}

    record AbsencePolicyResource(
            UUID id,
            UUID sourceSystemId,
            long versionNumber,
            int maxInferredTransitions,
            String state,
            Instant createdAt,
            Instant activatedAt,
            Instant supersededAt) {}
}
