package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.application.GovernanceFindingRepository.GovernanceFinding;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class GovernanceObservationReportingService
        implements GovernanceObservationReporter {

    private final GovernanceFindingRepository findings;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;

    public GovernanceObservationReportingService(
            GovernanceFindingRepository findings,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this.findings = Objects.requireNonNull(findings, "findings");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public void report(
            TenantContext tenant,
            UUID connectorBindingId,
            Set<ObservationCondition> currentConditions,
            Instant observedAt) {
        transactions.required(() -> {
            Map<String,ObservationCondition> byKey = new LinkedHashMap<>();
            for (ObservationCondition condition : currentConditions) {
                String key = findingKey(connectorBindingId, condition);
                byKey.put(key, condition);
                GovernanceFinding existing = findings.findByKey(tenant, key).orElse(null);
                if (existing == null) {
                    findings.insert(
                            tenant, ids.nextId(), key,
                            condition.findingType(), condition.subjectKind(),
                            connectorBindingId, condition.providerStableId(),
                            condition.relatedProviderStableId(), observedAt);
                } else if ("RESOLVED".equals(existing.lifecycleState())) {
                    findings.reopen(
                            tenant, existing.id(), existing.revision(), observedAt);
                } else {
                    findings.touchOpen(
                            tenant, existing.id(), existing.revision(), observedAt);
                }
            }
            for (GovernanceFinding open :
                    findings.findOpenByBinding(tenant, connectorBindingId)) {
                if (!byKey.containsKey(open.findingKey())) {
                    findings.resolve(
                            tenant, open.id(), open.revision(), observedAt);
                }
            }
            return null;
        });
    }

    static String findingKey(
            UUID connectorBindingId, ObservationCondition condition) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, connectorBindingId.toString());
            update(digest, condition.findingType());
            update(digest, condition.subjectKind());
            update(digest, condition.providerStableId());
            update(digest, condition.relatedProviderStableId());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
