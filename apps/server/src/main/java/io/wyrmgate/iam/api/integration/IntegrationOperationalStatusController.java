package io.wyrmgate.iam.api.integration;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.IntegrationOperationalPermissions;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.integration.application.IntegrationOperationalStatusRepository.PagePosition;
import io.wyrmgate.iam.integration.application.IntegrationOperationalStatusRepository.ProvisioningAttemptStatus;
import io.wyrmgate.iam.integration.application.IntegrationOperationalStatusRepository.ProvisioningJobStatus;
import io.wyrmgate.iam.integration.application.IntegrationOperationalStatusRepository.ProvisioningTaskStatus;
import io.wyrmgate.iam.integration.application.IntegrationOperationalStatusRepository.ReconciliationRunStatus;
import io.wyrmgate.iam.integration.application.IntegrationOperationalStatusService;
import io.wyrmgate.iam.platform.id.IdGenerator;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public final class IntegrationOperationalStatusController {
    private static final int DEFAULT_LIMIT = 50;

    private static final String JOB_CURSOR_KIND = "integration.provisioning-job.v1";
    private static final String TASK_CURSOR_KIND = "integration.provisioning-task.v1";
    private static final String ATTEMPT_CURSOR_KIND = "integration.provisioning-attempt.v1";
    private static final String RUN_CURSOR_KIND = "integration.reconciliation-run.v1";

    private final IntegrationOperationalStatusService queries;
    private final AdministrativeAuthorizationService authorization;
    private final IntegrationOperationalCursorCodec cursors;
    private final IdGenerator ids;

    IntegrationOperationalStatusController(
            IntegrationOperationalStatusService queries,
            AdministrativeAuthorizationService authorization,
            IntegrationOperationalCursorCodec cursors,
            IdGenerator ids) {
        this.queries = queries;
        this.authorization = authorization;
        this.cursors = cursors;
        this.ids = ids;
    }

    @GetMapping("/provisioning-jobs")
    ResponseEntity<IntegrationOperationalApiModels.ProvisioningJobPage> listProvisioningJobs(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        UUID correlationId = IntegrationAdminApiRequestContext.resolve(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        requireRead(
                actor,
                IntegrationOperationalPermissions.PROVISIONING_JOB_READ,
                AdministrativeResource.collection("provisioning-job"),
                correlationId);
        int pageSize = validateLimit(limit, correlationId);
        var page = queries.listProvisioningJobs(
                actor.tenant(), decode(cursor, JOB_CURSOR_KIND, actor, correlationId), pageSize);
        return ok(
                new IntegrationOperationalApiModels.ProvisioningJobPage(
                        page.items().stream().map(IntegrationOperationalStatusController::job).toList(),
                        cursors.encode(JOB_CURSOR_KIND, actor.tenant(), page.nextPosition())),
                correlationId);
    }

    @GetMapping("/provisioning-jobs/{id}")
    ResponseEntity<IntegrationOperationalApiModels.ProvisioningJobResource> getProvisioningJob(
            @PathVariable UUID id,
            HttpServletRequest request) {
        UUID correlationId = IntegrationAdminApiRequestContext.resolve(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        requireRead(
                actor,
                IntegrationOperationalPermissions.PROVISIONING_JOB_READ,
                new AdministrativeResource("provisioning-job", id),
                correlationId);
        var value = queries.findProvisioningJob(actor.tenant(), id)
                .orElseThrow(() -> IntegrationAdminApiException.notFound(correlationId));
        return ok(job(value), correlationId);
    }

    @GetMapping("/provisioning-tasks")
    ResponseEntity<IntegrationOperationalApiModels.ProvisioningTaskPage> listProvisioningTasks(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        UUID correlationId = IntegrationAdminApiRequestContext.resolve(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        requireRead(
                actor,
                IntegrationOperationalPermissions.PROVISIONING_TASK_READ,
                AdministrativeResource.collection("provisioning-task"),
                correlationId);
        int pageSize = validateLimit(limit, correlationId);
        var page = queries.listProvisioningTasks(
                actor.tenant(), decode(cursor, TASK_CURSOR_KIND, actor, correlationId), pageSize);
        return ok(
                new IntegrationOperationalApiModels.ProvisioningTaskPage(
                        page.items().stream().map(IntegrationOperationalStatusController::task).toList(),
                        cursors.encode(TASK_CURSOR_KIND, actor.tenant(), page.nextPosition())),
                correlationId);
    }

    @GetMapping("/provisioning-tasks/{id}")
    ResponseEntity<IntegrationOperationalApiModels.ProvisioningTaskResource> getProvisioningTask(
            @PathVariable UUID id,
            HttpServletRequest request) {
        UUID correlationId = IntegrationAdminApiRequestContext.resolve(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        requireRead(
                actor,
                IntegrationOperationalPermissions.PROVISIONING_TASK_READ,
                new AdministrativeResource("provisioning-task", id),
                correlationId);
        var value = queries.findProvisioningTask(actor.tenant(), id)
                .orElseThrow(() -> IntegrationAdminApiException.notFound(correlationId));
        return ok(task(value), correlationId);
    }

    @GetMapping("/provisioning-tasks/{taskId}/attempts")
    ResponseEntity<IntegrationOperationalApiModels.ProvisioningAttemptPage> listProvisioningAttempts(
            @PathVariable UUID taskId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        UUID correlationId = IntegrationAdminApiRequestContext.resolve(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        requireRead(
                actor,
                IntegrationOperationalPermissions.PROVISIONING_ATTEMPT_READ,
                AdministrativeResource.collection("provisioning-attempt"),
                correlationId);
        if (queries.findProvisioningTask(actor.tenant(), taskId).isEmpty()) {
            throw IntegrationAdminApiException.notFound(correlationId);
        }
        int pageSize = validateLimit(limit, correlationId);
        var page = queries.listProvisioningAttempts(
                actor.tenant(),
                taskId,
                decode(cursor, ATTEMPT_CURSOR_KIND + ":" + taskId, actor, correlationId),
                pageSize);
        return ok(
                new IntegrationOperationalApiModels.ProvisioningAttemptPage(
                        page.items().stream().map(IntegrationOperationalStatusController::attempt).toList(),
                        cursors.encode(
                                ATTEMPT_CURSOR_KIND + ":" + taskId,
                                actor.tenant(),
                                page.nextPosition())),
                correlationId);
    }

    @GetMapping("/provisioning-attempts/{id}")
    ResponseEntity<IntegrationOperationalApiModels.ProvisioningAttemptResource> getProvisioningAttempt(
            @PathVariable UUID id,
            HttpServletRequest request) {
        UUID correlationId = IntegrationAdminApiRequestContext.resolve(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        requireRead(
                actor,
                IntegrationOperationalPermissions.PROVISIONING_ATTEMPT_READ,
                new AdministrativeResource("provisioning-attempt", id),
                correlationId);
        var value = queries.findProvisioningAttempt(actor.tenant(), id)
                .orElseThrow(() -> IntegrationAdminApiException.notFound(correlationId));
        return ok(attempt(value), correlationId);
    }

    @GetMapping("/reconciliation-runs")
    ResponseEntity<IntegrationOperationalApiModels.ReconciliationRunPage> listReconciliationRuns(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        UUID correlationId = IntegrationAdminApiRequestContext.resolve(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        requireRead(
                actor,
                IntegrationOperationalPermissions.RECONCILIATION_RUN_READ,
                AdministrativeResource.collection("reconciliation-run"),
                correlationId);
        int pageSize = validateLimit(limit, correlationId);
        var page = queries.listReconciliationRuns(
                actor.tenant(), decode(cursor, RUN_CURSOR_KIND, actor, correlationId), pageSize);
        return ok(
                new IntegrationOperationalApiModels.ReconciliationRunPage(
                        page.items().stream().map(IntegrationOperationalStatusController::run).toList(),
                        cursors.encode(RUN_CURSOR_KIND, actor.tenant(), page.nextPosition())),
                correlationId);
    }

    @GetMapping("/reconciliation-runs/{id}")
    ResponseEntity<IntegrationOperationalApiModels.ReconciliationRunResource> getReconciliationRun(
            @PathVariable UUID id,
            HttpServletRequest request) {
        UUID correlationId = IntegrationAdminApiRequestContext.resolve(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        requireRead(
                actor,
                IntegrationOperationalPermissions.RECONCILIATION_RUN_READ,
                new AdministrativeResource("reconciliation-run", id),
                correlationId);
        var value = queries.findReconciliationRun(actor.tenant(), id)
                .orElseThrow(() -> IntegrationAdminApiException.notFound(correlationId));
        return ok(run(value), correlationId);
    }

    private void requireRead(
            AuthenticatedAdministrativeActor actor,
            AdministrativePermission permission,
            AdministrativeResource resource,
            UUID correlationId) {
        if (!authorization.authorize(actor, permission, resource, Instant.now()).allowed()) {
            throw IntegrationAdminApiException.forbidden(correlationId);
        }
    }

    private PagePosition decode(
            String cursor,
            String kind,
            AuthenticatedAdministrativeActor actor,
            UUID correlationId) {
        if (cursor == null) return null;
        try {
            return cursors.decode(cursor, kind, actor.tenant());
        } catch (IllegalArgumentException invalid) {
            throw IntegrationAdminApiException.validation(
                    correlationId,
                    "cursor is invalid or does not match this Integration operational query.");
        }
    }

    private static int validateLimit(Integer limit, UUID correlationId) {
        int value = limit == null ? DEFAULT_LIMIT : limit;
        if (value < 1 || value > IntegrationOperationalStatusService.MAX_PAGE_SIZE) {
            throw IntegrationAdminApiException.validation(
                    correlationId,
                    "limit must be between 1 and " + IntegrationOperationalStatusService.MAX_PAGE_SIZE + ".");
        }
        return value;
    }

    private static <T> ResponseEntity<T> ok(T body, UUID correlationId) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Correlation-Id", correlationId.toString())
                .body(body);
    }

    private static IntegrationOperationalApiModels.ProvisioningJobResource job(
            ProvisioningJobStatus value) {
        return new IntegrationOperationalApiModels.ProvisioningJobResource(
                value.id(), value.connectorBindingId(), value.state(), value.revision(),
                value.correlationId(), value.causationId(), value.createdAt(), value.updatedAt());
    }

    private static IntegrationOperationalApiModels.ProvisioningTaskResource task(
            ProvisioningTaskStatus value) {
        return new IntegrationOperationalApiModels.ProvisioningTaskResource(
                value.id(), value.provisioningJobId(), value.operationId(), value.operationType(),
                value.subjectKind(), value.subjectId(), value.desiredRevision(), value.contractId(),
                value.contractVersion(), value.state(), value.attemptCount(), value.nextAttemptAt(),
                value.failureCode(), value.revision(), value.correlationId(), value.causationId(),
                value.createdAt(), value.updatedAt());
    }

    private static IntegrationOperationalApiModels.ProvisioningAttemptResource attempt(
            ProvisioningAttemptStatus value) {
        return new IntegrationOperationalApiModels.ProvisioningAttemptResource(
                value.id(), value.taskId(), value.attemptNumber(), value.startedAt(),
                value.completedAt(), value.outcome(), value.failureCategory(),
                value.correlationId(), value.causationId());
    }

    private static IntegrationOperationalApiModels.ReconciliationRunResource run(
            ReconciliationRunStatus value) {
        return new IntegrationOperationalApiModels.ReconciliationRunResource(
                value.id(), value.connectorBindingId(), value.operationId(), value.scopeObjectClass(),
                value.state(), value.reportedCoverage(), value.effectiveCompleteness(),
                value.configurationVersion(), value.runtimeId(), value.runtimeVersion(),
                value.contractId(), value.contractVersion(), value.nextAttemptAt(), value.failureCode(),
                value.correlationId(), value.causationId(), value.revision(), value.startedAt(),
                value.completedAt(), value.createdAt(), value.updatedAt());
    }
}
