package io.wyrmgate.iam.integration.application;

import io.wyrmgate.iam.integration.domain.WorkerExternalSubject;
import io.wyrmgate.iam.integration.domain.WorkerRegistration;
import io.wyrmgate.iam.integration.domain.WorkerSession;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkerRegistrationRepository {
    Optional<WorkerRegistration> findEnabledByExternalSubject(WorkerExternalSubject subject);
    List<WorkerPermission> findPermissions(UUID workerRegistrationId);
    WorkerSession insertSession(
            WorkerRegistration worker,
            String workerInstanceId,
            int protocolMajor,
            Instant createdAt,
            Instant expiresAt,
            List<WorkerSession.NegotiatedRuntime> runtimes);
    Optional<WorkerSession> findActiveSession(
            UUID sessionId,
            UUID workerRegistrationId,
            Instant now);

    record WorkerPermission(
            String runtimeId,
            String runtimeVersion,
            io.wyrmgate.iam.integration.domain.WorkerCapability capability,
            String contractId,
            int contractVersion) {}
}
