package io.wyrmgate.iam.api.console;

import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.api.console.RequestsAndApprovalsApiModels.*;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.governance.application.RequestsAndApprovalsPageQuery;
import io.wyrmgate.iam.governance.application.RequestsAndApprovalsPageQuery.ApprovalSort;
import io.wyrmgate.iam.platform.id.IdGenerator;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/console")
public final class RequestsAndApprovalsController {
    private static final String CORRELATION_ATTRIBUTE =
            RequestsAndApprovalsController.class.getName() + ".correlationId";

    private final RequestsAndApprovalsPageQuery query;
    private final IdGenerator ids;

    public RequestsAndApprovalsController(
            RequestsAndApprovalsPageQuery query,
            IdGenerator ids) {
        this.query = query;
        this.ids = ids;
    }

    @GetMapping("/requests-and-approvals")
    ResponseEntity<RequestsAndApprovalsResource> page(
            @RequestParam(defaultValue = "7d") String window,
            @RequestParam(defaultValue = "OLDEST") String approvalSort,
            HttpServletRequest request) {
        UUID correlationId = correlationId(request);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(request);
        Instant asOf = Instant.now();
        WindowSpec windowSpec = window(window, asOf, correlationId);
        ApprovalSort sort = approvalSort(approvalSort, correlationId);

        RequestsAndApprovalsPageQuery.Snapshot snapshot;
        try {
            snapshot = query.snapshot(
                    actor.tenant(),
                    actor.identityId(),
                    windowSpec.from(),
                    windowSpec.until(),
                    sort,
                    10,
                    10);
        } catch (DataAccessException unavailable) {
            throw RequestsAndApprovalsApiException.unavailable(correlationId);
        }

        RequestsAndApprovalsResource body = new RequestsAndApprovalsResource(
                asOf,
                actor.tenant().tenantId(),
                actor.identityId(),
                new WindowResource(
                        windowSpec.preset(),
                        windowSpec.from(),
                        windowSpec.until()),
                sort.name(),
                new AttentionResource(
                        snapshot.pendingApprovals(),
                        snapshot.draftRequests(),
                        snapshot.inFlightRequestItems(),
                        snapshot.appliedRequestItemsInWindow()),
                new RequestStatusTotalsResource(
                        snapshot.requestStatusTotals().draft(),
                        snapshot.requestStatusTotals().inProgress(),
                        snapshot.requestStatusTotals().completedApplied(),
                        snapshot.requestStatusTotals().completedMixed(),
                        snapshot.requestStatusTotals().completedNoAccess()),
                snapshot.approvalWork().stream()
                        .map(item -> new ApprovalWorkResource(
                                item.approvalCaseId(),
                                item.subjectKind(),
                                item.subjectId(),
                                item.accessRequestId(),
                                item.targetKind(),
                                item.targetId(),
                                item.currentStageOrdinal(),
                                item.createdAt()))
                        .toList(),
                snapshot.recentRequests().stream()
                        .map(item -> new RequestSummaryResource(
                                item.requestId(),
                                item.presentationStatus(),
                                item.itemCount(),
                                item.draftItems(),
                                item.submittedItems(),
                                item.evaluatingItems(),
                                item.pendingApprovalItems(),
                                item.authorizedItems(),
                                item.appliedItems(),
                                item.deniedItems(),
                                item.rejectedItems(),
                                item.cancelledItems(),
                                item.expiredItems(),
                                item.singleTargetKind(),
                                item.singleTargetId(),
                                item.createdAt(),
                                item.updatedAt()))
                        .toList());

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Correlation-Id", correlationId.toString())
                .body(body);
    }

    private static ApprovalSort approvalSort(
            String raw,
            UUID correlationId) {
        try {
            return ApprovalSort.valueOf(
                    raw.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException invalid) {
            throw RequestsAndApprovalsApiException.validation(
                    correlationId,
                    "approvalSort must be OLDEST or NEWEST.");
        }
    }

    private static WindowSpec window(
            String raw,
            Instant now,
            UUID correlationId) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if ("24h".equals(value)) {
            Instant from = now.truncatedTo(ChronoUnit.HOURS)
                    .minus(23, ChronoUnit.HOURS);
            return new WindowSpec("24h", from, now);
        }
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        if ("7d".equals(value)) {
            return new WindowSpec(
                    "7d",
                    today.minusDays(6)
                            .atStartOfDay(ZoneOffset.UTC)
                            .toInstant(),
                    now);
        }
        if ("30d".equals(value)) {
            return new WindowSpec(
                    "30d",
                    today.minusDays(29)
                            .atStartOfDay(ZoneOffset.UTC)
                            .toInstant(),
                    now);
        }
        throw RequestsAndApprovalsApiException.validation(
                correlationId,
                "window must be 24h, 7d, or 30d.");
    }

    private UUID correlationId(HttpServletRequest request) {
        Object current = request.getAttribute(CORRELATION_ATTRIBUTE);
        if (current instanceof UUID id) return id;
        String header = request.getHeader("X-Correlation-Id");
        if (header == null) {
            UUID id = ids.nextId();
            request.setAttribute(CORRELATION_ATTRIBUTE, id);
            return id;
        }
        try {
            UUID id = UUID.fromString(header);
            request.setAttribute(CORRELATION_ATTRIBUTE, id);
            return id;
        } catch (IllegalArgumentException invalid) {
            UUID id = ids.nextId();
            request.setAttribute(CORRELATION_ATTRIBUTE, id);
            throw RequestsAndApprovalsApiException.validation(
                    id,
                    "X-Correlation-Id must be a UUID.");
        }
    }

    private record WindowSpec(
            String preset,
            Instant from,
            Instant until) {}
}
