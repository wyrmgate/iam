package io.wyrmgate.iam.identity.domain;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable activated policy mapping one bounded source field to authoritative Identity lifecycle intent. */
public record SourceLifecyclePolicyVersion(
        UUID id,
        UUID sourceSystemId,
        String sourcePath,
        long versionNumber,
        List<Rule> rules,
        State state,
        Instant createdAt,
        Instant activatedAt,
        Instant supersededAt) {

    public enum State {
        ACTIVE,
        SUPERSEDED
    }

    public record Rule(String sourceValue, IdentityLifecycleState targetState) {
        public Rule {
            if (sourceValue == null || sourceValue.isBlank()) {
                throw new IllegalArgumentException("sourceValue must not be blank");
            }
            Objects.requireNonNull(targetState, "targetState");
            if (targetState == IdentityLifecycleState.PENDING) {
                throw new IllegalArgumentException("source lifecycle policy cannot target PENDING");
            }
        }
    }

    public SourceLifecyclePolicyVersion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sourceSystemId, "sourceSystemId");
        if (sourcePath == null || !sourcePath.startsWith("$.") || sourcePath.length() < 3) {
            throw new IllegalArgumentException("sourcePath must use the bounded $.field mapping form");
        }
        if (versionNumber < 1) {
            throw new IllegalArgumentException("versionNumber must be positive");
        }
        rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("source lifecycle policy requires at least one rule");
        }
        Set<String> values = new HashSet<>();
        for (Rule rule : rules) {
            if (!values.add(rule.sourceValue())) {
                throw new IllegalArgumentException("duplicate source lifecycle value " + rule.sourceValue());
            }
        }
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(activatedAt, "activatedAt");
        if (state == State.ACTIVE && supersededAt != null) {
            throw new IllegalArgumentException("active policy must not be superseded");
        }
        if (state == State.SUPERSEDED
                && (supersededAt == null || supersededAt.isBefore(activatedAt))) {
            throw new IllegalArgumentException("superseded policy requires ordered timestamps");
        }
    }
}
