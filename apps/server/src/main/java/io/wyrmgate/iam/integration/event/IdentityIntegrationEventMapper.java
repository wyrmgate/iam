package io.wyrmgate.iam.integration.event;

import io.wyrmgate.iam.integration.event.IdentityPublicIntegrationEvents.CreatedPayload;
import io.wyrmgate.iam.integration.event.IdentityPublicIntegrationEvents.CreatedV1;
import io.wyrmgate.iam.integration.event.IdentityPublicIntegrationEvents.MetadataChangedPayload;
import io.wyrmgate.iam.integration.event.IdentityPublicIntegrationEvents.MetadataChangedV1;
import io.wyrmgate.iam.integration.event.IdentityPublicIntegrationEvents.MetadataField;
import io.wyrmgate.iam.integration.event.IdentityPublicIntegrationEvents.PublicIdentityLifecycleState;
import io.wyrmgate.iam.integration.event.IdentityPublicIntegrationEvents.PublicIdentityType;
import io.wyrmgate.iam.integration.event.IdentityPublicIntegrationEvents.ResourceReference;
import io.wyrmgate.iam.platform.persistence.ClaimedOutboxEvent;
import io.wyrmgate.iam.platform.persistence.OutboxEvent;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Curates internal Identity facts into independently versioned public events. */
public final class IdentityIntegrationEventMapper {

    public static final String IDENTITY_CREATED_FACT = "identity.identity-created";
    public static final String DISPLAY_NAME_CHANGED_FACT = "identity.identity-display-name-changed";

    private static final Set<String> SUPPORTED =
            Set.of(IDENTITY_CREATED_FACT, DISPLAY_NAME_CHANGED_FACT);

    public Set<String> supportedInternalEventTypes() {
        return SUPPORTED;
    }

    public IdentityPublicIntegrationEvents.Event map(ClaimedOutboxEvent claimed) {
        OutboxEvent fact = claimed.event();
        requireIdentityAggregate(fact);
        if (fact.eventVersion() != 1) {
            throw new IllegalArgumentException("unsupported internal Identity fact version");
        }
        if (fact.correlationId() == null) {
            throw new IllegalArgumentException("public Identity event requires correlationId");
        }

        ResourceReference resource =
                new ResourceReference("Identity", fact.aggregateId(), fact.aggregateRevision());

        return switch (fact.eventType()) {
            case IDENTITY_CREATED_FACT -> new CreatedV1(
                    fact.eventId(),
                    fact.occurredAt(),
                    claimed.tenant().tenantId(),
                    resource,
                    fact.correlationId(),
                    fact.causationId(),
                    new CreatedPayload(
                            PublicIdentityType.valueOf(stringField(fact.payloadJson(), "identityType")),
                            PublicIdentityLifecycleState.valueOf(
                                    stringField(fact.payloadJson(), "lifecycleState"))));
            case DISPLAY_NAME_CHANGED_FACT -> {
                if (!booleanField(fact.payloadJson(), "displayNameChanged")) {
                    throw new IllegalArgumentException("display-name fact does not record a change");
                }
                yield new MetadataChangedV1(
                        fact.eventId(),
                        fact.occurredAt(),
                        claimed.tenant().tenantId(),
                        resource,
                        fact.correlationId(),
                        fact.causationId(),
                        new MetadataChangedPayload(List.of(MetadataField.DISPLAY_NAME)));
            }
            default -> throw new IllegalArgumentException("unsupported internal Identity fact type");
        };
    }

    private static void requireIdentityAggregate(OutboxEvent fact) {
        if (!"identity".equals(fact.aggregateType())
                || fact.aggregateId() == null
                || fact.aggregateRevision() == null) {
            throw new IllegalArgumentException("Identity fact requires complete Identity aggregate reference");
        }
    }

    private static String stringField(String json, String field) {
        Pattern pattern = Pattern.compile(
                "\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            throw new IllegalArgumentException("required internal fact field is missing");
        }
        return matcher.group(1);
    }

    private static boolean booleanField(String json, String field) {
        Pattern pattern = Pattern.compile(
                "\"" + Pattern.quote(field) + "\"\\s*:\\s*(true|false)");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            throw new IllegalArgumentException("required internal fact field is missing");
        }
        return Boolean.parseBoolean(matcher.group(1));
    }
}
