package io.wyrmgate.iam.integration.event;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Typed curated public Identity integration-event contracts for version 1. */
public final class IdentityPublicIntegrationEvents {

    private IdentityPublicIntegrationEvents() {
    }

    public sealed interface Event permits CreatedV1, MetadataChangedV1 {
        String address();
        String eventType();
        int eventVersion();
        UUID eventId();
        Instant occurredAt();
        UUID tenantId();
        ResourceReference resource();
        UUID correlationId();
        UUID causationId();
    }

    public enum PublicIdentityType {
        PERSON,
        SERVICE,
        WORKLOAD
    }

    public enum PublicIdentityLifecycleState {
        PENDING,
        ACTIVE,
        SUSPENDED,
        INACTIVE,
        DECOMMISSIONED
    }

    public enum MetadataField {
        DISPLAY_NAME("displayName");

        private final String wireName;

        MetadataField(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    public record ResourceReference(
            String type,
            UUID id,
            long revision) {

        public ResourceReference {
            if (!"Identity".equals(type)) {
                throw new IllegalArgumentException("public Identity resource type must be Identity");
            }
            Objects.requireNonNull(id, "id");
            if (revision < 1) {
                throw new IllegalArgumentException("revision must be positive");
            }
        }
    }

    public record CreatedPayload(
            PublicIdentityType identityType,
            PublicIdentityLifecycleState lifecycleState) {

        public CreatedPayload {
            Objects.requireNonNull(identityType, "identityType");
            Objects.requireNonNull(lifecycleState, "lifecycleState");
        }
    }

    public record MetadataChangedPayload(List<MetadataField> changedFields) {
        public MetadataChangedPayload {
            Objects.requireNonNull(changedFields, "changedFields");
            changedFields = List.copyOf(changedFields);
            if (changedFields.isEmpty()) {
                throw new IllegalArgumentException("changedFields must not be empty");
            }
        }
    }

    public record CreatedV1(
            UUID eventId,
            Instant occurredAt,
            UUID tenantId,
            ResourceReference resource,
            UUID correlationId,
            UUID causationId,
            CreatedPayload payload) implements Event {

        public CreatedV1 {
            requireEnvelope(eventId, occurredAt, tenantId, resource, correlationId);
            Objects.requireNonNull(payload, "payload");
        }

        @Override
        public String address() {
            return "iam.identity.created.v1";
        }

        @Override
        public String eventType() {
            return "iam.identity.created";
        }

        @Override
        public int eventVersion() {
            return 1;
        }
    }

    public record MetadataChangedV1(
            UUID eventId,
            Instant occurredAt,
            UUID tenantId,
            ResourceReference resource,
            UUID correlationId,
            UUID causationId,
            MetadataChangedPayload payload) implements Event {

        public MetadataChangedV1 {
            requireEnvelope(eventId, occurredAt, tenantId, resource, correlationId);
            Objects.requireNonNull(payload, "payload");
        }

        @Override
        public String address() {
            return "iam.identity.metadata-changed.v1";
        }

        @Override
        public String eventType() {
            return "iam.identity.metadata-changed";
        }

        @Override
        public int eventVersion() {
            return 1;
        }
    }

    private static void requireEnvelope(
            UUID eventId,
            Instant occurredAt,
            UUID tenantId,
            ResourceReference resource,
            UUID correlationId) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(correlationId, "correlationId");
    }
}
