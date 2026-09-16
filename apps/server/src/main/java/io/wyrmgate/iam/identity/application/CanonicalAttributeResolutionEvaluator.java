package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.identity.domain.AttributeAuthorityRuleVersion;
import io.wyrmgate.iam.identity.domain.AttributeDefinitionVersion;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeCandidate;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeOverride;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeState;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeState.ResolutionStatus;
import io.wyrmgate.iam.identity.domain.CanonicalValue;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Pure canonical-resolution evaluator shared by write-side materialization and effective reads. */
public final class CanonicalAttributeResolutionEvaluator {

    public EffectiveResolution evaluate(
            CanonicalAttributeState current,
            AttributeDefinitionVersion version,
            CanonicalAttributeOverride override,
            List<CanonicalAttributeCandidate> candidates,
            List<AttributeAuthorityRuleVersion> authorityRules,
            Instant now) {
        Objects.requireNonNull(version, "version");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        authorityRules = List.copyOf(Objects.requireNonNull(authorityRules, "authorityRules"));
        Objects.requireNonNull(now, "now");

        if (override != null
                && override.attributeDefinitionVersionId().equals(version.id())
                && override.effectiveAt(now)) {
            return new EffectiveResolution(
                    ResolutionStatus.OVERRIDDEN,
                    null,
                    null,
                    override.id(),
                    override.values());
        }

        if (candidates.isEmpty()) {
            return new EffectiveResolution(ResolutionStatus.NO_VALUE, null, null, null, List.of());
        }

        Map<UUID, AttributeAuthorityRuleVersion> rules = authorityRules.stream()
                .collect(Collectors.toMap(
                        AttributeAuthorityRuleVersion::sourceSystemId,
                        Function.identity(),
                        (first, second) -> first));
        List<CanonicalAttributeCandidate> authorized = candidates.stream()
                .filter(candidate -> rules.containsKey(candidate.sourceSystemId()))
                .toList();
        if (authorized.isEmpty()) {
            return preserveTrusted(current, version.id(), ResolutionStatus.UNRESOLVED);
        }

        int bestPriority = authorized.stream()
                .map(candidate -> rules.get(candidate.sourceSystemId()).priority())
                .min(Integer::compareTo)
                .orElseThrow();
        List<CanonicalAttributeCandidate> top = authorized.stream()
                .filter(candidate -> rules.get(candidate.sourceSystemId()).priority() == bestPriority)
                .toList();
        List<CanonicalValue> firstValues = top.getFirst().values();
        boolean conflict = top.stream().anyMatch(candidate -> !candidate.values().equals(firstValues));
        if (conflict) {
            return preserveTrusted(current, version.id(), ResolutionStatus.CONFLICT);
        }

        CanonicalAttributeCandidate selected = top.stream()
                .min(Comparator.comparing(candidate -> candidate.sourceSystemId().toString()))
                .orElseThrow();
        AttributeAuthorityRuleVersion rule = rules.get(selected.sourceSystemId());
        return new EffectiveResolution(
                ResolutionStatus.RESOLVED,
                selected.id(),
                rule.id(),
                null,
                selected.values());
    }

    public boolean sameOutcome(CanonicalAttributeState current, EffectiveResolution effective) {
        return current != null
                && current.resolutionStatus() == effective.resolutionStatus()
                && Objects.equals(current.selectedCandidateId(), effective.selectedCandidateId())
                && Objects.equals(current.authorityRuleVersionId(), effective.authorityRuleVersionId())
                && current.values().equals(effective.values());
    }

    public long effectiveValueRevision(CanonicalAttributeState current, EffectiveResolution effective) {
        if (current == null) {
            return 1;
        }
        return sameOutcome(current, effective) ? current.valueRevision() : current.valueRevision() + 1;
    }

    private EffectiveResolution preserveTrusted(
            CanonicalAttributeState current,
            UUID definitionVersionId,
            ResolutionStatus status) {
        if (current != null
                && current.resolutionStatus() != ResolutionStatus.OVERRIDDEN
                && current.attributeDefinitionVersionId().equals(definitionVersionId)
                && !current.values().isEmpty()) {
            return new EffectiveResolution(
                    status,
                    current.selectedCandidateId(),
                    current.authorityRuleVersionId(),
                    null,
                    current.values());
        }
        return new EffectiveResolution(status, null, null, null, List.of());
    }

    public record EffectiveResolution(
            ResolutionStatus resolutionStatus,
            UUID selectedCandidateId,
            UUID authorityRuleVersionId,
            UUID overrideId,
            List<CanonicalValue> values) {
        public EffectiveResolution {
            Objects.requireNonNull(resolutionStatus, "resolutionStatus");
            values = List.copyOf(Objects.requireNonNull(values, "values"));
        }
    }
}
