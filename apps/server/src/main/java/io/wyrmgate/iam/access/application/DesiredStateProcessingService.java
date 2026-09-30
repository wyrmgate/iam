package io.wyrmgate.iam.access.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQuery;
import io.wyrmgate.iam.identity.application.PrincipalFactSink;
import io.wyrmgate.iam.platform.persistence.ClaimedOutboxEvent;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class DesiredStateProcessingService {

    private static final Duration CLAIM_LEASE = Duration.ofSeconds(30);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(5);
    private static final int BATCH_SIZE = 100;

    private final JdbcOutboxRepository outbox;
    private final IdentityAccessReferenceQuery identityReferences;
    private final DesiredStateDerivationService derivation;
    private final ObjectMapper json;
    private final Clock clock;

    public DesiredStateProcessingService(
            JdbcOutboxRepository outbox,
            IdentityAccessReferenceQuery identityReferences,
            DesiredStateDerivationService derivation) {
        this(outbox, identityReferences, derivation, new ObjectMapper(), Clock.systemUTC());
    }

    DesiredStateProcessingService(
            JdbcOutboxRepository outbox,
            IdentityAccessReferenceQuery identityReferences,
            DesiredStateDerivationService derivation,
            Clock clock) {
        this(outbox, identityReferences, derivation, new ObjectMapper(), clock);
    }

    DesiredStateProcessingService(
            JdbcOutboxRepository outbox,
            IdentityAccessReferenceQuery identityReferences,
            DesiredStateDerivationService derivation,
            ObjectMapper json,
            Clock clock) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.identityReferences = Objects.requireNonNull(
                identityReferences, "identityReferences");
        this.derivation = Objects.requireNonNull(derivation, "derivation");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public int processAvailable() {
        Instant now = clock.instant();
        List<ClaimedOutboxEvent> claimed = outbox.claimPending(
                Set.of(PrincipalFactSink.ACCESS_PROJECTION_INPUT_CHANGED),
                now,
                CLAIM_LEASE,
                BATCH_SIZE);
        int processed = 0;
        for (ClaimedOutboxEvent item : claimed) {
            try {
                var event = item.event();
                if (event.eventVersion() != 1
                        || !"principal".equals(event.aggregateType())
                        || event.aggregateId() == null) {
                    throw new IllegalArgumentException(
                            "unsupported Principal access projection fact");
                }
                var principal = identityReferences.principal(
                        item.tenant(), event.aggregateId());
                if (principal.status()
                        == IdentityAccessReferenceQuery.Status.RESOLVED) {
                    java.util.UUID previousIdentityId = previousIdentityId(
                            event.payloadJson());
                    if (previousIdentityId != null
                            && !previousIdentityId.equals(principal.identityId())) {
                        derivation.reconcileAnyForPrincipalChange(
                                item.tenant(),
                                previousIdentityId,
                                principal.applicationTargetId(),
                                clock.instant());
                    }
                    derivation.reconcileAnyForPrincipalChange(
                            item.tenant(),
                            principal.identityId(),
                            principal.applicationTargetId(),
                            clock.instant());
                }
                outbox.markPublished(
                        item.tenant(), event.eventId(), clock.instant());
                processed++;
            } catch (IllegalArgumentException invalid) {
                outbox.markTerminalFailure(
                        item.tenant(), item.event().eventId(),
                        "desired_state_principal_fact_invalid");
            } catch (RuntimeException retryable) {
                outbox.markFailed(
                        item.tenant(),
                        item.event().eventId(),
                        clock.instant().plus(RETRY_DELAY),
                        "desired_state_principal_projection_failed");
            }
        }
        return processed;
    }

    private java.util.UUID previousIdentityId(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return null;
        }
        try {
            var payload = json.readTree(payloadJson);
            var node = payload.get("previousIdentityId");
            if (node == null || !node.isTextual() || node.textValue().isBlank()) {
                return null;
            }
            return java.util.UUID.fromString(node.textValue());
        } catch (Exception invalid) {
            throw new IllegalArgumentException(
                    "invalid Principal access projection payload", invalid);
        }
    }
}
