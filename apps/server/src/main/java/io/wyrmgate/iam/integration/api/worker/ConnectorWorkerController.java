package io.wyrmgate.iam.integration.api.worker;

import io.wyrmgate.iam.integration.api.security.ConnectorWorkerRequestContext;
import io.wyrmgate.iam.integration.application.ConnectorWorkRepository;
import io.wyrmgate.iam.integration.application.ConnectorWorkerProtocolService;
import io.wyrmgate.iam.integration.application.WorkerProtocolException;
import io.wyrmgate.iam.integration.domain.ReconciliationCompleteness;
import io.wyrmgate.iam.integration.domain.WorkerCapability;
import io.wyrmgate.iam.integration.domain.WorkerSession;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/connector-worker/v1")
public final class ConnectorWorkerController {

    private final ConnectorWorkerProtocolService service;

    public ConnectorWorkerController(ConnectorWorkerProtocolService service) {
        this.service = service;
    }

    @PostMapping("/sessions")
    public ResponseEntity<ConnectorWorkerApiModels.SessionResponse> establishSession(
            @RequestBody ConnectorWorkerApiModels.SessionRequest request,
            HttpServletRequest http) {
        var worker = ConnectorWorkerRequestContext.require(http);
        WorkerSession session = service.establishSession(
                worker,
                requireText(request.workerInstanceId(), "workerInstanceId"),
                requireList(request.supportedProtocolMajors(), "supportedProtocolMajors"),
                requireList(request.runtimes(), "runtimes").stream()
                        .map(ConnectorWorkerController::runtimeAdvertisement)
                        .toList());
        var limits = service.limits();
        return ResponseEntity.ok(new ConnectorWorkerApiModels.SessionResponse(
                session.id(),
                session.protocolMajor(),
                limits.maxClaimSize(),
                limits.maxLongPollSeconds(),
                limits.maxObservationBatchSize(),
                limits.leaseDurationSeconds(),
                session.runtimes().stream().map(ConnectorWorkerController::runtimeResponse).toList()));
    }

    @PostMapping("/sessions/{sessionId}/work:claim")
    public ResponseEntity<ConnectorWorkerApiModels.ClaimResponse> claim(
            @PathVariable UUID sessionId,
            @RequestBody ConnectorWorkerApiModels.ClaimRequest request,
            HttpServletRequest http) {
        var worker = ConnectorWorkerRequestContext.require(http);
        var items = service.claim(worker, sessionId, request.maxItems(), request.waitSeconds())
                .stream()
                .map(item -> new ConnectorWorkerApiModels.LeasedWorkItem(
                        item.workId(),
                        item.operationId(),
                        new ConnectorWorkerApiModels.LeaseToken(
                                item.lease().leaseId(),
                                item.lease().leaseEpoch(),
                                item.lease().leaseExpiresAt()),
                        item.tenant().tenantId(),
                        item.connectorBindingId(),
                        item.workKind().name(),
                        item.contractId(),
                        item.contractVersion(),
                        item.desiredRevision(),
                        item.checkpoint(),
                        item.idempotencyKey(),
                        item.correlationId(),
                        item.causationId(),
                        item.payload()))
                .toList();
        return ResponseEntity.ok(new ConnectorWorkerApiModels.ClaimResponse(items));
    }

    @PostMapping("/sessions/{sessionId}/work/{workId}/lease:renew")
    public ResponseEntity<ConnectorWorkerApiModels.LeaseToken> renew(
            @PathVariable UUID sessionId,
            @PathVariable UUID workId,
            @RequestBody ConnectorWorkerApiModels.LeaseToken request,
            HttpServletRequest http) {
        var worker = ConnectorWorkerRequestContext.require(http);
        if (request.leaseId() == null || request.leaseEpoch() < 1 || request.leaseExpiresAt() == null) {
            throw invalid("invalid_lease", "leaseId, leaseEpoch and leaseExpiresAt are required");
        }
        var lease = service.renew(
                worker, sessionId, workId, request.leaseId(), request.leaseEpoch());
        return ResponseEntity.ok(new ConnectorWorkerApiModels.LeaseToken(
                lease.leaseId(), lease.leaseEpoch(), lease.leaseExpiresAt()));
    }

    @PostMapping("/sessions/{sessionId}/work/{workId}/observations")
    public ResponseEntity<Void> observations(
            @PathVariable UUID sessionId,
            @PathVariable UUID workId,
            @RequestBody ConnectorWorkerApiModels.ObservationBatch request,
            HttpServletRequest http) {
        var worker = ConnectorWorkerRequestContext.require(http);
        if (request.leaseId() == null || request.leaseEpoch() < 1 || request.batchId() == null || request.sequence() < 0) {
            throw invalid("invalid_observation_batch", "observation batch fencing/idempotency fields are invalid");
        }
        List<ConnectorWorkRepository.ProviderObservation> observations =
                requireList(request.observations(), "observations").stream()
                        .map(value -> {
                            if (!List.of("PRINCIPAL", "ENTITLEMENT", "GRANT").contains(value.objectClass())) {
                                throw invalid(
                                        "unsupported_object_class",
                                        "runtime supports PRINCIPAL, ENTITLEMENT and GRANT observations");
                            }
                            if (value.providerStableId() == null || value.providerStableId().isBlank()) {
                                throw invalid("invalid_observation", "providerStableId must not be blank");
                            }
                            return new ConnectorWorkRepository.ProviderObservation(
                                    value.objectClass(),
                                    value.providerStableId(),
                                    value.providerVersion(),
                                    value.observedState() == null ? Map.of() : value.observedState());
                        })
                        .toList();
        service.appendObservations(
                worker, sessionId, workId, request.leaseId(), request.leaseEpoch(),
                request.batchId(), request.sequence(), observations);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/sessions/{sessionId}/work/{workId}:complete")
    public ResponseEntity<Void> complete(
            @PathVariable UUID sessionId,
            @PathVariable UUID workId,
            @RequestBody ConnectorWorkerApiModels.WorkCompletion request,
            HttpServletRequest http) {
        var worker = ConnectorWorkerRequestContext.require(http);
        if (request.leaseId() == null || request.leaseEpoch() < 1) {
            throw invalid("invalid_completion", "completion lease fencing fields are invalid");
        }
        ConnectorWorkerApiModels.NormalizedResult result = request.result();
        if (result == null) throw invalid("invalid_completion", "result is required");
        ReconciliationCompleteness coverage = null;
        if (request.discoveryCoverage() != null) {
            try {
                coverage = ReconciliationCompleteness.valueOf(request.discoveryCoverage());
            } catch (IllegalArgumentException invalid) {
                throw invalid("invalid_completion", "unsupported discoveryCoverage");
            }
        }
        ConnectorWorkRepository.WorkCompletion completion = new ConnectorWorkRepository.WorkCompletion(
                requireOutcome(request.outcome()),
                result.failureCategory(),
                result.providerErrorCode(),
                result.providerRequestId(),
                result.retryAfterSeconds(),
                result.providerObjectId(),
                result.providerVersion(),
                result.metadata() == null ? Map.of() : result.metadata(),
                coverage,
                request.nextCheckpoint());
        service.complete(
                worker, sessionId, workId, request.leaseId(), request.leaseEpoch(), completion);
        return ResponseEntity.accepted().build();
    }

    private static ConnectorWorkerProtocolService.RuntimeAdvertisement runtimeAdvertisement(
            ConnectorWorkerApiModels.RuntimeAdvertisement value) {
        return new ConnectorWorkerProtocolService.RuntimeAdvertisement(
                requireText(value.runtimeId(), "runtimeId"),
                requireText(value.runtimeVersion(), "runtimeVersion"),
                requireList(value.capabilities(), "capabilities").stream()
                        .map(capability -> {
                            try {
                                return WorkerCapability.valueOf(capability);
                            } catch (IllegalArgumentException invalid) {
                                throw invalid("protocol_incompatible", "unsupported worker capability");
                            }
                        })
                        .toList(),
                requireList(value.contractSchemas(), "contractSchemas").stream()
                        .map(contract -> new ConnectorWorkerProtocolService.ContractAdvertisement(
                                requireText(contract.contractId(), "contractId"),
                                requireList(contract.versions(), "versions")))
                        .toList());
    }

    private static ConnectorWorkerApiModels.RuntimeAdvertisement runtimeResponse(
            WorkerSession.NegotiatedRuntime runtime) {
        return new ConnectorWorkerApiModels.RuntimeAdvertisement(
                runtime.runtimeId(),
                runtime.runtimeVersion(),
                runtime.capabilities().stream().map(Enum::name).toList(),
                runtime.contracts().stream()
                        .map(c -> new ConnectorWorkerApiModels.ContractSupport(
                                c.contractId(), c.versions()))
                        .toList());
    }

    private static String requireOutcome(String value) {
        if (value == null || !List.of(
                "SUCCEEDED", "FAILED_RETRYABLE", "FAILED_FINAL", "SUPERSEDED", "SKIPPED").contains(value)) {
            throw invalid("invalid_completion", "unsupported outcome");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw invalid("invalid_request", field + " must not be blank");
        return value;
    }

    private static <T> List<T> requireList(List<T> value, String field) {
        if (value == null || value.isEmpty()) throw invalid("invalid_request", field + " must not be empty");
        return value;
    }

    private static WorkerProtocolException invalid(String code, String message) {
        return new WorkerProtocolException(code, message);
    }
}
