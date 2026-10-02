package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.identity.domain.Identity;
import io.wyrmgate.iam.identity.domain.IdentityLifecycleState;
import io.wyrmgate.iam.identity.domain.SourceLifecyclePolicyVersion;
import io.wyrmgate.iam.identity.domain.SourceRecord;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Identity-owned activation and application of versioned explicit source lifecycle policy. */
public final class SourceLifecyclePolicyService {

    private final SourceCorrelationRepository sources;
    private final IdentityRepository identities;
    private final IdentityCommandService commands;
    private final SourceMappedValueExtractor extractor;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;

    public SourceLifecyclePolicyService(
            SourceCorrelationRepository sources,
            IdentityRepository identities,
            IdentityCommandService commands,
            SourceMappedValueExtractor extractor,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this.sources = Objects.requireNonNull(sources, "sources");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public SourceLifecyclePolicyVersion activate(
            TenantContext tenant,
            UUID sourceSystemId,
            String sourcePath,
            Map<String, IdentityLifecycleState> mappings,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(sourceSystemId, "sourceSystemId");
        Objects.requireNonNull(mappings, "mappings");
        Objects.requireNonNull(now, "now");
        requireSourcePath(sourcePath);
        if (mappings.isEmpty()) {
            throw new IllegalArgumentException("source lifecycle policy requires at least one mapping");
        }
        List<SourceLifecyclePolicyVersion.Rule> rules = mappings.entrySet().stream()
                .map(entry -> new SourceLifecyclePolicyVersion.Rule(entry.getKey(), entry.getValue()))
                .toList();
        return transactions.required(() -> {
            if (sources.findSourceSystem(tenant, sourceSystemId).isEmpty()) {
                throw new IllegalArgumentException("source system does not exist");
            }
            return sources.replaceActiveLifecyclePolicy(
                    tenant, sourceSystemId, sourcePath, rules, now, ids.nextId());
        });
    }

    public void applyCurrentObservation(
            TenantContext tenant,
            UUID sourceRecordId,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(sourceRecordId, "sourceRecordId");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(correlationId, "correlationId");

        transactions.required(() -> {
            SourceRecord record = sources.findSourceRecordForUpdate(tenant, sourceRecordId)
                    .orElseThrow(() -> new IllegalArgumentException("source record does not exist"));
            var link = sources.findActiveAcceptedLink(tenant, sourceRecordId);
            if (link.isEmpty()) {
                return null;
            }
            SourceLifecyclePolicyVersion policy =
                    sources.findActiveLifecyclePolicy(tenant, record.sourceSystemId()).orElse(null);
            if (policy == null) {
                return null;
            }
            String sourceValue = extractor.nonBlankText(
                            record.observedAttributesJson(), policy.sourcePath())
                    .orElse(null);
            if (sourceValue == null) {
                return null;
            }
            IdentityLifecycleState target = policy.rules().stream()
                    .filter(rule -> rule.sourceValue().equals(sourceValue))
                    .map(SourceLifecyclePolicyVersion.Rule::targetState)
                    .findFirst()
                    .orElse(null);
            if (target == null) {
                return null;
            }

            Identity current = identities.findById(tenant, link.get().identityId())
                    .orElseThrow(() -> new IllegalArgumentException("linked identity does not exist"));
            boolean inferredAbsenceRestoration = target == IdentityLifecycleState.ACTIVE
                    && current.lifecycleState() == IdentityLifecycleState.INACTIVE
                    && sources.hasCurrentAbsenceTransitionEvidence(
                            tenant,
                            record.id(),
                            link.get().id(),
                            current.id(),
                            current.revision());
            boolean sourceSuspensionRestoration = target == IdentityLifecycleState.ACTIVE
                    && current.lifecycleState() == IdentityLifecycleState.SUSPENDED
                    && sources.hasCurrentSourceSuspensionTransitionEvidence(
                            tenant,
                            record.id(),
                            link.get().id(),
                            current.id(),
                            current.revision());
            if (!shouldApply(current.lifecycleState(), target)
                    && !inferredAbsenceRestoration
                    && !sourceSuspensionRestoration) {
                return null;
            }
            Instant transitionTime = now.isBefore(current.updatedAt()) ? current.updatedAt() : now;
            Identity updated = commands.changeLifecycle(
                    tenant,
                    current.id(),
                    target,
                    current.revision(),
                    transitionTime,
                    correlationId,
                    causationId);
            if (target == IdentityLifecycleState.SUSPENDED
                    && current.lifecycleState() == IdentityLifecycleState.ACTIVE) {
                sources.recordSourceSuspensionTransitionEvidence(
                        tenant,
                        ids.nextId(),
                        record.sourceSystemId(),
                        record.id(),
                        link.get().id(),
                        current.id(),
                        policy.id(),
                        current.revision(),
                        updated.revision(),
                        transitionTime);
            }
            return null;
        });
    }

    private static boolean shouldApply(
            IdentityLifecycleState current,
            IdentityLifecycleState target) {
        if (current == target || current == IdentityLifecycleState.DECOMMISSIONED) {
            return false;
        }
        return switch (target) {
            case ACTIVE -> current == IdentityLifecycleState.PENDING;
            case SUSPENDED -> current == IdentityLifecycleState.ACTIVE;
            case INACTIVE -> current == IdentityLifecycleState.PENDING
                    || current == IdentityLifecycleState.ACTIVE
                    || current == IdentityLifecycleState.SUSPENDED;
            case DECOMMISSIONED -> true;
            case PENDING -> false;
        };
    }

    private static void requireSourcePath(String value) {
        if (value == null || !value.startsWith("$.") || value.length() < 3) {
            throw new IllegalArgumentException("sourcePath must use the bounded $.field mapping form");
        }
    }
}
