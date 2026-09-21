package io.wyrmgate.iam.integration.domain;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record WorkerSession(
        UUID id,
        UUID workerRegistrationId,
        TenantContext tenant,
        String workerInstanceId,
        int protocolMajor,
        Instant expiresAt,
        List<NegotiatedRuntime> runtimes) {

    public WorkerSession {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(workerRegistrationId, "workerRegistrationId");
        Objects.requireNonNull(tenant, "tenant");
        if (workerInstanceId == null || workerInstanceId.isBlank()) throw new IllegalArgumentException("workerInstanceId must not be blank");
        Objects.requireNonNull(expiresAt, "expiresAt");
        runtimes = List.copyOf(Objects.requireNonNull(runtimes, "runtimes"));
        if (runtimes.isEmpty()) throw new IllegalArgumentException("session must negotiate at least one runtime");
    }

    public record NegotiatedRuntime(
            String runtimeId,
            String runtimeVersion,
            List<WorkerCapability> capabilities,
            List<ContractSupport> contracts) {
        public NegotiatedRuntime {
            if (runtimeId == null || runtimeId.isBlank()) throw new IllegalArgumentException("runtimeId must not be blank");
            if (runtimeVersion == null || runtimeVersion.isBlank()) throw new IllegalArgumentException("runtimeVersion must not be blank");
            capabilities = List.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
            contracts = List.copyOf(Objects.requireNonNull(contracts, "contracts"));
        }
    }

    public record ContractSupport(String contractId, List<Integer> versions) {
        public ContractSupport {
            if (contractId == null || contractId.isBlank()) throw new IllegalArgumentException("contractId must not be blank");
            versions = List.copyOf(Objects.requireNonNull(versions, "versions"));
            if (versions.isEmpty() || versions.stream().anyMatch(v -> v == null || v < 1)) {
                throw new IllegalArgumentException("contract versions must be positive");
            }
        }
    }
}
