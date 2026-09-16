package io.wyrmgate.iam.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.wyrmgate.iam.identity.domain.AttributeDefinitionVersion;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeCardinality;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeOverride;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeState;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeType;
import io.wyrmgate.iam.identity.domain.CanonicalValue;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CanonicalAttributeResolutionEvaluatorTest {

    private final CanonicalAttributeResolutionEvaluator evaluator =
            new CanonicalAttributeResolutionEvaluator();

    @Test
    void expiredOverrideStopsGoverningWithoutMutatingPersistedState() {
        Instant now = Instant.parse("2026-09-16T12:00:00Z");
        UUID identityId = UUID.randomUUID();
        UUID definitionId = UUID.randomUUID();
        AttributeDefinitionVersion version = version(definitionId, UUID.randomUUID(), now.minusSeconds(100));
        CanonicalAttributeState persisted = new CanonicalAttributeState(
                UUID.randomUUID(),
                identityId,
                definitionId,
                version.id(),
                CanonicalAttributeState.ResolutionStatus.OVERRIDDEN,
                null,
                null,
                7,
                now.minusSeconds(90),
                now.minusSeconds(30),
                List.of(new CanonicalValue.StringValue("temporary")));
        CanonicalAttributeOverride expired = new CanonicalAttributeOverride(
                UUID.randomUUID(),
                identityId,
                definitionId,
                version.id(),
                CanonicalAttributeOverride.State.ACTIVE,
                "temporary correction",
                now.minusSeconds(60),
                now,
                1,
                now.minusSeconds(60),
                null,
                UUID.randomUUID(),
                null,
                List.of(new CanonicalValue.StringValue("temporary")));

        var effective = evaluator.evaluate(
                persisted, version, expired, List.of(), List.of(), now);

        assertThat(effective.resolutionStatus())
                .isEqualTo(CanonicalAttributeState.ResolutionStatus.NO_VALUE);
        assertThat(effective.values()).isEmpty();
        assertThat(evaluator.effectiveValueRevision(persisted, effective)).isEqualTo(8);
        assertThat(persisted.resolutionStatus())
                .isEqualTo(CanonicalAttributeState.ResolutionStatus.OVERRIDDEN);
        assertThat(persisted.valueRevision()).isEqualTo(7);
    }

    @Test
    void activeSchemaDefinitionVersionChangeAdvancesEffectiveRevisionEvenForSameOutcome() {
        Instant now = Instant.parse("2026-09-16T12:00:00Z");
        UUID identityId = UUID.randomUUID();
        UUID definitionId = UUID.randomUUID();
        AttributeDefinitionVersion oldVersion = version(definitionId, UUID.randomUUID(), now.minusSeconds(200));
        AttributeDefinitionVersion newVersion = version(definitionId, UUID.randomUUID(), now.minusSeconds(100));
        CanonicalAttributeState persisted = new CanonicalAttributeState(
                UUID.randomUUID(),
                identityId,
                definitionId,
                oldVersion.id(),
                CanonicalAttributeState.ResolutionStatus.NO_VALUE,
                null,
                null,
                4,
                now.minusSeconds(180),
                now.minusSeconds(180),
                List.of());

        var effective = evaluator.evaluate(
                persisted, newVersion, null, List.of(), List.of(), now);

        assertThat(effective.resolutionStatus())
                .isEqualTo(CanonicalAttributeState.ResolutionStatus.NO_VALUE);
        assertThat(effective.attributeDefinitionVersionId()).isEqualTo(newVersion.id());
        assertThat(evaluator.sameOutcome(persisted, effective)).isFalse();
        assertThat(evaluator.effectiveValueRevision(persisted, effective)).isEqualTo(5);
    }

    private static AttributeDefinitionVersion version(
            UUID definitionId,
            UUID versionId,
            Instant createdAt) {
        return new AttributeDefinitionVersion(
                versionId,
                UUID.randomUUID(),
                definitionId,
                CanonicalAttributeType.STRING,
                CanonicalAttributeCardinality.SINGLE,
                "INTERNAL",
                true,
                true,
                true,
                createdAt);
    }
}
