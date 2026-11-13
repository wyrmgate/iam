package io.wyrmgate.iam.access.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQuery;
import io.wyrmgate.iam.identity.application.PrincipalFactSink;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.ClaimedOutboxEvent;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.OutboxEvent;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Bounded Access-owned desired-state repair for Identity-owned Principal changes.
 *
 * <p>Principal change facts and page continuations are at-least-once. Every page
 * re-reads current Principal/EffectiveAccess semantics and commits its next continuation
 * with the projection changes before acknowledging the input event.</p>
 */
public final class DesiredStateProcessingService {

    public static final String PRINCIPAL_REFRESH_PAGE_REQUESTED =
            "access.desired-principal-refresh-page-requested";

    private static final Duration CLAIM_LEASE = Duration.ofSeconds(30);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(5);
    private static final int CLAIM_BATCH = 50;
    private static final int PAGE_SIZE = 100;

    private final JdbcOutboxRepository outbox;
    private final IdentityAccessReferenceQuery identityReferences;
    private final DesiredStateDerivationService derivation;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;
    private final ObjectMapper json;
    private final Clock clock;

    public DesiredStateProcessingService(
            JdbcOutboxRepository outbox,
            IdentityAccessReferenceQuery identityReferences,
            DesiredStateDerivationService derivation,
            IdGenerator ids,
            TransactionExecutor transactions,
            ObjectMapper json) {
        this(
                outbox,
                identityReferences,
                derivation,
                ids,
                transactions,
                json,
                Clock.systemUTC());
    }

    /**
     * Compatibility constructor for focused unit composition. Production wiring supplies
     * the shared transaction executor and ID generator explicitly.
     */
    public DesiredStateProcessingService(
            JdbcOutboxRepository outbox,
            IdentityAccessReferenceQuery identityReferences,
            DesiredStateDerivationService derivation) {
        this(
                outbox,
                identityReferences,
                derivation,
                new UuidV7Generator(),
                new DirectTransactionExecutor(),
                new ObjectMapper(),
                Clock.systemUTC());
    }

    DesiredStateProcessingService(
            JdbcOutboxRepository outbox,
            IdentityAccessReferenceQuery identityReferences,
            DesiredStateDerivationService derivation,
            IdGenerator ids,
            TransactionExecutor transactions,
            ObjectMapper json,
            Clock clock) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.identityReferences = Objects.requireNonNull(
                identityReferences, "identityReferences");
        this.derivation = Objects.requireNonNull(derivation, "derivation");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public int processAvailable() {
        List<ClaimedOutboxEvent> claimed = outbox.claimPending(
                Set.of(
                        PrincipalFactSink.ACCESS_PROJECTION_INPUT_CHANGED,
                        PRINCIPAL_REFRESH_PAGE_REQUESTED),
                clock.instant(),
                CLAIM_LEASE,
                CLAIM_BATCH);
        int processed = 0;
        for (ClaimedOutboxEvent item : claimed) {
            try {
                processOne(item, clock.instant());
                processed++;
            } catch (IllegalArgumentException invalid) {
                outbox.markTerminalFailure(
                        item.tenant(),
                        item.event().eventId(),
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

    private void processOne(
            ClaimedOutboxEvent item,
            Instant now) {
        var event = item.event();
        if (event.eventVersion() != 1
                || !"principal".equals(event.aggregateType())
                || event.aggregateId() == null
                || event.aggregateRevision() == null) {
            throw new IllegalArgumentException(
                    "unsupported Principal desired-state repair fact");
        }

        RefreshContext context = PrincipalFactSink.ACCESS_PROJECTION_INPUT_CHANGED
                .equals(event.eventType())
                ? initialContext(item)
                : continuationContext(event.payloadJson());

        UUID correlationId = event.correlationId() == null
                ? event.eventId()
                : event.correlationId();

        transactions.required(() -> {
            var page = derivation.reconcilePrincipalChangePage(
                    item.tenant(),
                    event.aggregateId(),
                    context.applicationTargetId(),
                    context.currentIdentityId(),
                    context.previousIdentityId(),
                    context.afterDesiredGrantId(),
                    PAGE_SIZE,
                    now);

            if (page.hasMore()) {
                appendContinuation(
                        item,
                        context.withAfter(page.nextAfterId()),
                        correlationId,
                        now);
            } else {
                derivation.revalidatePrincipalForPrincipalChange(
                        item.tenant(),
                        context.previousIdentityId(),
                        context.applicationTargetId());
                if (!Objects.equals(
                        context.previousIdentityId(),
                        context.currentIdentityId())) {
                    derivation.revalidatePrincipalForPrincipalChange(
                            item.tenant(),
                            context.currentIdentityId(),
                            context.applicationTargetId());
                }
            }

            if (!outbox.markPublished(
                    item.tenant(),
                    event.eventId(),
                    now)) {
                throw new IllegalStateException(
                        "Principal desired-state repair input could not be acknowledged");
            }
            return null;
        });
    }

    private RefreshContext initialContext(
            ClaimedOutboxEvent item) {
        JsonNode payload = payload(item.event().payloadJson());
        UUID applicationTargetId = requiredUuid(
                payload, "applicationTargetId");
        UUID previousIdentityId = optionalUuid(
                payload, "previousIdentityId");

        var current = identityReferences.principal(
                item.tenant(), item.event().aggregateId());
        UUID currentIdentityId = current.status()
                        == IdentityAccessReferenceQuery.Status.RESOLVED
                ? current.identityId()
                : null;
        if (current.status()
                        != IdentityAccessReferenceQuery.Status.NOT_FOUND
                && !applicationTargetId.equals(
                        current.applicationTargetId())) {
            throw new IllegalArgumentException(
                    "Principal desired-state repair target changed unexpectedly");
        }
        return new RefreshContext(
                applicationTargetId,
                currentIdentityId,
                previousIdentityId,
                null);
    }

    private RefreshContext continuationContext(
            String payloadJson) {
        JsonNode payload = payload(payloadJson);
        return new RefreshContext(
                requiredUuid(payload, "applicationTargetId"),
                optionalUuid(payload, "currentIdentityId"),
                optionalUuid(payload, "previousIdentityId"),
                requiredUuid(payload, "afterDesiredGrantId"));
    }

    private void appendContinuation(
            ClaimedOutboxEvent item,
            RefreshContext context,
            UUID correlationId,
            Instant now) {
        var payload = json.createObjectNode()
                .put("applicationTargetId",
                        context.applicationTargetId().toString())
                .put("afterDesiredGrantId",
                        context.afterDesiredGrantId().toString());
        if (context.currentIdentityId() != null) {
            payload.put(
                    "currentIdentityId",
                    context.currentIdentityId().toString());
        }
        if (context.previousIdentityId() != null) {
            payload.put(
                    "previousIdentityId",
                    context.previousIdentityId().toString());
        }

        outbox.append(
                item.tenant(),
                new OutboxEvent(
                        ids.nextId(),
                        PRINCIPAL_REFRESH_PAGE_REQUESTED,
                        1,
                        "principal",
                        item.event().aggregateId(),
                        item.event().aggregateRevision(),
                        now,
                        correlationId,
                        item.event().eventId(),
                        payload.toString()),
                now);
    }

    private JsonNode payload(String value) {
        try {
            JsonNode node = json.readTree(value);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException(
                        "Principal desired-state repair payload must be an object");
            }
            return node;
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw new IllegalArgumentException(
                    "Principal desired-state repair payload is invalid JSON",
                    invalid);
        }
    }

    private static UUID requiredUuid(
            JsonNode payload,
            String field) {
        UUID value = optionalUuid(payload, field);
        if (value == null) {
            throw new IllegalArgumentException(
                    "Principal desired-state repair payload is missing " + field);
        }
        return value;
    }

    private static UUID optionalUuid(
            JsonNode payload,
            String field) {
        JsonNode node = payload.get(field);
        if (node == null || node.isNull()) return null;
        if (!node.isTextual() || node.textValue().isBlank()) {
            throw new IllegalArgumentException(
                    "Principal desired-state repair " + field + " must be a UUID");
        }
        try {
            return UUID.fromString(node.textValue());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Principal desired-state repair " + field + " must be a UUID",
                    invalid);
        }
    }

    private record RefreshContext(
            UUID applicationTargetId,
            UUID currentIdentityId,
            UUID previousIdentityId,
            UUID afterDesiredGrantId) {
        private RefreshContext {
            Objects.requireNonNull(
                    applicationTargetId, "applicationTargetId");
        }

        private RefreshContext withAfter(UUID afterId) {
            return new RefreshContext(
                    applicationTargetId,
                    currentIdentityId,
                    previousIdentityId,
                    Objects.requireNonNull(afterId, "afterId"));
        }
    }

    private static final class DirectTransactionExecutor
            implements TransactionExecutor {
        @Override
        public <T> T required(Supplier<T> work) {
            return work.get();
        }
    }
}
