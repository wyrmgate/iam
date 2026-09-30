package io.wyrmgate.iam.access.domain;

import java.time.Instant;
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
        CANONICAL_STRING_EQUALS
    }

    public record Rule(
            UUID ruleId,
            PredicateKind predicateKind,
            String canonicalKey,
            String expectedString,
            AccessAssignment.TargetKind targetKind,
            UUID targetId) {
        public Rule {
            Objects.requireNonNull(ruleId, "ruleId");
            Objects.requireNonNull(predicateKind, "predicateKind");
            Objects.requireNonNull(targetKind, "targetKind");
            Objects.requireNonNull(targetId, "targetId");
            if (predicateKind == PredicateKind.ALWAYS) {
                if (canonicalKey != null || expectedString != null) {
                    throw new IllegalArgumentException("ALWAYS rule must not carry canonical predicate data");
                }
            } else {
                if (canonicalKey == null || canonicalKey.isBlank()) {
                    throw new IllegalArgumentException("canonicalKey is required");
                }
                if (expectedString == null) {
                    throw new IllegalArgumentException("expectedString is required");
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
