package io.wyrmgate.iam.integration.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.wyrmgate.iam.integration.event.IdentityPublicIntegrationEvents.CreatedV1;
import io.wyrmgate.iam.integration.event.IdentityPublicIntegrationEvents.MetadataChangedV1;
import io.wyrmgate.iam.platform.persistence.ClaimedOutboxEvent;
import io.wyrmgate.iam.platform.persistence.OutboxEvent;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentityIntegrationEventMapperTest {

    private static final UUID TENANT_ID = UUID.fromString("0199e100-0000-7000-8000-000000000001");
    private static final UUID EVENT_ID = UUID.fromString("0199e100-0000-7000-8000-000000000002");
    private static final UUID IDENTITY_ID = UUID.fromString("0199e100-0000-7000-8000-000000000003");
    private static final UUID CORRELATION_ID = UUID.fromString("0199e100-0000-7000-8000-000000000004");
    private static final UUID CAUSATION_ID = UUID.fromString("0199e100-0000-7000-8000-000000000005");
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-21T04:00:00Z");

    private final IdentityIntegrationEventMapper mapper = new IdentityIntegrationEventMapper();
    private final IntegrationEventJsonEncoder encoder = new IntegrationEventJsonEncoder();

    @Test
    void mapsCreatedFactToCuratedV1Envelope() {
        ClaimedOutboxEvent claimed = claimed(
                IdentityIntegrationEventMapper.IDENTITY_CREATED_FACT,
                1L,
                "{\"identityType\":\"SERVICE\",\"lifecycleState\":\"PENDING\"}");

        CreatedV1 event = (CreatedV1) mapper.map(claimed);
        assertThat(event.address()).isEqualTo("iam.identity.created.v1");
        assertThat(event.eventType()).isEqualTo("iam.identity.created");
        assertThat(event.eventVersion()).isEqualTo(1);
        assertThat(event.tenantId()).isEqualTo(TENANT_ID);
        assertThat(event.resource().id()).isEqualTo(IDENTITY_ID);
        assertThat(event.resource().revision()).isEqualTo(1);
        assertThat(event.payload().identityType().name()).isEqualTo("SERVICE");
        assertThat(event.payload().lifecycleState().name()).isEqualTo("PENDING");

        String json = new String(encoder.encode(event).payload(), StandardCharsets.UTF_8);
        assertThat(json).contains("\"eventType\":\"iam.identity.created\"");
        assertThat(json).contains("\"identityType\":\"SERVICE\"");
        assertThat(json).contains("\"lifecycleState\":\"PENDING\"");
        assertThat(json).doesNotContain("displayName");
    }

    @Test
    void mapsDisplayNameFactWithoutPublishingTheChangedValue() {
        ClaimedOutboxEvent claimed = claimed(
                IdentityIntegrationEventMapper.DISPLAY_NAME_CHANGED_FACT,
                2L,
                "{\"displayNameChanged\":true}");

        MetadataChangedV1 event = (MetadataChangedV1) mapper.map(claimed);
        assertThat(event.address()).isEqualTo("iam.identity.metadata-changed.v1");
        assertThat(event.payload().changedFields())
                .containsExactly(IdentityPublicIntegrationEvents.MetadataField.DISPLAY_NAME);

        String json = new String(encoder.encode(event).payload(), StandardCharsets.UTF_8);
        assertThat(json).contains("\"changedFields\":[\"displayName\"]");
        assertThat(json).doesNotContain("displayNameChanged");
    }

    @Test
    void rejectsMalformedInternalCreatedFactInsteadOfInventingPublicData() {
        ClaimedOutboxEvent malformed = claimed(
                IdentityIntegrationEventMapper.IDENTITY_CREATED_FACT,
                1L,
                "{\"identityType\":\"PERSON\"}");

        assertThatThrownBy(() -> mapper.map(malformed))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ClaimedOutboxEvent claimed(String type, long revision, String payload) {
        return new ClaimedOutboxEvent(
                new TenantContext(TENANT_ID),
                new OutboxEvent(
                        EVENT_ID,
                        type,
                        1,
                        "identity",
                        IDENTITY_ID,
                        revision,
                        OCCURRED_AT,
                        CORRELATION_ID,
                        CAUSATION_ID,
                        payload),
                1);
    }
}
