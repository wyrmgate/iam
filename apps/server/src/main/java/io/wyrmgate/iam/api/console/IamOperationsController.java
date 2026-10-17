package io.wyrmgate.iam.api.console;

import io.wyrmgate.iam.access.application.AccessOperationsOverviewQuery;
import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.application.TenantOwnerOverviewQuery;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.administration.domain.IntegrationOperationalPermissions;
import io.wyrmgate.iam.api.console.IamOperationsApiModels.*;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.audit.application.AuditOperationsOverviewQuery;
import io.wyrmgate.iam.catalog.application.CatalogOperationsOverviewQuery;
import io.wyrmgate.iam.governance.application.GovernanceOperationsOverviewQuery;
import io.wyrmgate.iam.governance.application.GovernanceOperationsOverviewQuery.WorkSort;
import io.wyrmgate.iam.governance.application.GovernanceOperationsOverviewQuery.WorkType;
import io.wyrmgate.iam.identity.application.IdentityOperationsOverviewQuery;
import io.wyrmgate.iam.integration.application.IntegrationOperationsOverviewQuery;
import io.wyrmgate.iam.integration.application.IntegrationOperationsOverviewQuery.BucketUnit;
import io.wyrmgate.iam.platform.id.IdGenerator;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/console")
public final class IamOperationsController {
    private static final String AVAILABLE = "AVAILABLE";
    private static final String NOT_AUTHORIZED = "NOT_AUTHORIZED";
    private static final String UNAVAILABLE = "UNAVAILABLE";
    private static final String FRESH = "FRESH";
    private static final String UNKNOWN = "UNKNOWN";
    private static final String CORRELATION_ATTRIBUTE = IamOperationsController.class.getName() + ".correlationId";

    private final GovernanceOperationsOverviewQuery governance;
    private final IntegrationOperationsOverviewQuery integration;
    private final AuditOperationsOverviewQuery audit;
    private final IdentityOperationsOverviewQuery identity;
    private final AccessOperationsOverviewQuery access;
    private final CatalogOperationsOverviewQuery catalog;
    private final TenantOwnerOverviewQuery tenantOwners;
    private final AdministrativeAuthorizationService authorization;
    private final IdGenerator ids;

    public IamOperationsController(
            GovernanceOperationsOverviewQuery governance,
            IntegrationOperationsOverviewQuery integration,
            AuditOperationsOverviewQuery audit,
            IdentityOperationsOverviewQuery identity,
            AccessOperationsOverviewQuery access,
            CatalogOperationsOverviewQuery catalog,
            TenantOwnerOverviewQuery tenantOwners,
            AdministrativeAuthorizationService authorization,
            IdGenerator ids) {
        this.governance = governance;
        this.integration = integration;
        this.audit = audit;
        this.identity = identity;
        this.access = access;
        this.catalog = catalog;
        this.tenantOwners = tenantOwners;
        this.authorization = authorization;
        this.ids = ids;
    }

    @GetMapping("/iam-operations")
    ResponseEntity<IamOperationsOverviewResource> overview(
            @RequestParam(defaultValue = "7d") String window,
            @RequestParam(defaultValue = "ALL") String workType,
            @RequestParam(defaultValue = "OLDEST") String workSort,
            HttpServletRequest request) {
        UUID correlationId = correlationId(request);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        Instant asOf = Instant.now();
        WindowSpec spec = window(window, asOf, correlationId);
        WorkType parsedType = workType(workType, correlationId);
        WorkSort parsedSort = workSort(workSort, correlationId);

        Result<GovernanceOperationsOverviewQuery.Snapshot> governanceResult = safe(() ->
                governance.snapshot(actor.tenant(), actor.identityId(), spec.from(), spec.until(), parsedType, parsedSort, 10));

        boolean attemptRead = allowed(actor, IntegrationOperationalPermissions.PROVISIONING_ATTEMPT_READ, "provisioning-attempt", asOf);
        boolean taskRead = allowed(actor, IntegrationOperationalPermissions.PROVISIONING_TASK_READ, "provisioning-task", asOf);
        boolean reconciliationRead = allowed(actor, IntegrationOperationalPermissions.RECONCILIATION_RUN_READ, "reconciliation-run", asOf);
        boolean auditRead = allowed(actor, AdministrativePermissions.AUDIT_READ, "audit", asOf);
        boolean identityRead = allowed(actor, AdministrativePermissions.IDENTITY_READ, "identity", asOf);
        boolean applicationRead = allowed(actor, AdministrativePermissions.APPLICATION_READ, "application", asOf);
        boolean connectorRead = allowed(actor, AdministrativePermissions.CONNECTOR_READ, "connector", asOf);
        boolean bindingRead = allowed(actor, AdministrativePermissions.CONNECTOR_BINDING_READ, "connector-binding", asOf);
        boolean administrationRead = allowed(actor, AdministrativePermissions.MANAGE_AUTHORIZATION, "administration", asOf);

        Result<Long> finalFailures = attemptRead
                ? safe(() -> integration.finalProvisioningFailures(actor.tenant(), spec.from(), spec.until()))
                : Result.notAuthorized();
        Result<IntegrationOperationsOverviewQuery.Provisioning> provisioning = attemptRead && taskRead
                ? safe(() -> integration.provisioning(actor.tenant(), spec.from(), spec.until(), spec.bucketUnit(), spec.bucketCount()))
                : Result.notAuthorized();
        Result<IntegrationOperationsOverviewQuery.Reconciliation> reconciliation = reconciliationRead
                ? safe(() -> integration.reconciliation(actor.tenant(), spec.from(), spec.until(), 6))
                : Result.notAuthorized();
        Result<AuditOperationsOverviewQuery.Evidence> evidence = auditRead
                ? safe(() -> audit.evidence(actor.tenant(), spec.from(), spec.until(), 5))
                : Result.notAuthorized();
        Result<List<AuditOperationsOverviewQuery.ActivityItem>> recentActivity = auditRead
                ? safe(() -> audit.recentActivity(actor.tenant(), spec.from(), spec.until(), 5))
                : Result.notAuthorized();

        MetricResource activeIdentities = metric(actor, AdministrativePermissions.IDENTITY_READ, "identity", asOf,
                () -> identity.activeIdentities(actor.tenant()));
        MetricResource uncorrelatedPrincipals = metric(actor, AdministrativePermissions.PRINCIPAL_READ, "principal", asOf,
                () -> identity.uncorrelatedPrincipals(actor.tenant()));
        MetricResource activeAssignments = metric(actor, AdministrativePermissions.ACCESS_ASSIGNMENT_READ, "access-assignment", asOf,
                () -> access.activeAssignments(actor.tenant()));
        MetricResource applications = metric(actor, AdministrativePermissions.APPLICATION_READ, "application", asOf,
                () -> catalog.activeApplications(actor.tenant()));
        MetricResource connectors = metric(actor, AdministrativePermissions.CONNECTOR_READ, "connector", asOf,
                () -> integration.activeConnectors(actor.tenant()));

        Result<List<IdentityOperationsOverviewQuery.IdentityTypeCount>> identityTypes = identityRead
                ? safe(() -> identity.activeIdentityTypes(actor.tenant()))
                : Result.notAuthorized();
        Result<List<CatalogOperationsOverviewQuery.ApplicationOriginCount>> applicationOrigins = applicationRead
                ? safe(() -> catalog.activeApplicationOrigins(actor.tenant()))
                : Result.notAuthorized();
        Result<TenantOwnerOverviewQuery.Snapshot> ownerSnapshot = administrationRead
                ? safe(() -> tenantOwners.snapshot(actor.tenant(), asOf, 5))
                : Result.notAuthorized();
        Result<IdentityOperationsOverviewQuery.SourceSystems> sourceSystems = identityRead
                ? safe(() -> identity.sourceSystems(actor.tenant(), 10))
                : Result.notAuthorized();
        Result<List<IntegrationOperationsOverviewQuery.SourceOperationalSummary>> sourceOperations =
                sourceOperations(actor, sourceSystems, connectorRead && bindingRead && reconciliationRead, spec);

        AttentionResource attention = new AttentionResource(
                governanceResult.metric(GovernanceOperationsOverviewQuery.Snapshot::pendingApprovals),
                governanceResult.metric(GovernanceOperationsOverviewQuery.Snapshot::pendingReviews),
                finalFailures.metric(Long::longValue),
                reconciliation.metric(IntegrationOperationsOverviewQuery.Reconciliation::failedInWindow));
        MyWorkResource myWork = work(governanceResult, parsedType, parsedSort, asOf);
        GovernanceActivityResource governanceActivity = governanceActivity(governanceResult, asOf);
        ProvisioningResource provisioningResource = provisioning(provisioning, asOf);
        ReconciliationResource reconciliationResource = reconciliation(reconciliation, asOf);
        SecurityEvidenceResource securityResource = evidence(evidence, asOf);
        EnvironmentResource environment = new EnvironmentResource(
                asOf, activeIdentities, uncorrelatedPrincipals, activeAssignments, applications, connectors);
        TenantDashboardResource tenantDashboard = new TenantDashboardResource(
                identityBreakdown(identityTypes),
                applicationBreakdown(applicationOrigins),
                owners(ownerSnapshot, identityRead, actor),
                sources(sourceSystems, sourceOperations),
                recentActivity(recentActivity));

        boolean partial = !governanceResult.available()
                || !finalFailures.available()
                || !provisioning.available()
                || !reconciliation.available()
                || !evidence.available()
                || !recentActivity.available()
                || !identityTypes.available()
                || !applicationOrigins.available()
                || !ownerSnapshot.available()
                || !sourceSystems.available()
                || !sourceOperations.available()
                || !AVAILABLE.equals(activeIdentities.status())
                || !AVAILABLE.equals(uncorrelatedPrincipals.status())
                || !AVAILABLE.equals(activeAssignments.status())
                || !AVAILABLE.equals(applications.status())
                || !AVAILABLE.equals(connectors.status());

        IamOperationsOverviewResource body = new IamOperationsOverviewResource(
                asOf,
                partial,
                actor.tenant().tenantId(),
                new WindowResource(spec.preset(), spec.from(), spec.until()),
                attention,
                myWork,
                provisioningResource,
                reconciliationResource,
                governanceActivity,
                securityResource,
                environment,
                tenantDashboard);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Correlation-Id", correlationId.toString())
                .body(body);
    }

    private Result<List<IntegrationOperationsOverviewQuery.SourceOperationalSummary>> sourceOperations(
            AuthenticatedAdministrativeActor actor,
            Result<IdentityOperationsOverviewQuery.SourceSystems> sources,
            boolean authorized,
            WindowSpec spec) {
        if (!sources.available()) return new Result<>(sources.status(), null);
        if (!authorized) return Result.notAuthorized();
        Set<UUID> sourceIds = sources.value().items().stream()
                .map(IdentityOperationsOverviewQuery.SourceSystemSummary::sourceSystemId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (sourceIds.isEmpty()) return Result.available(List.of());
        return safe(() -> integration.sourceOperationalSummaries(
                actor.tenant(), sourceIds, spec.from(), spec.until()));
    }

    private TenantOwnersResource owners(
            Result<TenantOwnerOverviewQuery.Snapshot> result,
            boolean identityRead,
            AuthenticatedAdministrativeActor actor) {
        if (!result.available()) {
            return new TenantOwnersResource(result.status(), null, null, List.of());
        }
        List<TenantOwnerItemResource> items = result.value().owners().stream()
                .map(owner -> new TenantOwnerItemResource(
                        owner.identityId(),
                        identityRead ? safeIdentityDisplayName(actor, owner.identityId()) : null,
                        owner.administrativelyEligible()))
                .toList();
        return new TenantOwnersResource(
                AVAILABLE,
                result.value().effectiveOwnerCount(),
                result.value().viableOwnerCount(),
                items);
    }

    private String safeIdentityDisplayName(AuthenticatedAdministrativeActor actor, UUID identityId) {
        try {
            return identity.identityDisplayName(actor.tenant(), identityId).orElse(null);
        } catch (DataAccessException unavailable) {
            return null;
        }
    }

    private static SourceSystemsResource sources(
            Result<IdentityOperationsOverviewQuery.SourceSystems> sourceResult,
            Result<List<IntegrationOperationsOverviewQuery.SourceOperationalSummary>> operationalResult) {
        if (!sourceResult.available()) {
            return new SourceSystemsResource(sourceResult.status(), null, List.of());
        }
        Map<UUID, IntegrationOperationsOverviewQuery.SourceOperationalSummary> operational = new HashMap<>();
        if (operationalResult.available()) {
            for (var item : operationalResult.value()) operational.put(item.sourceSystemId(), item);
        }
        List<SourceSystemResource> items = sourceResult.value().items().stream()
                .map(source -> {
                    var op = operational.get(source.sourceSystemId());
                    SourceOperationalResource opResource = operationalResult.available()
                            ? new SourceOperationalResource(
                                    AVAILABLE,
                                    op == null ? 0L : op.activeBindingCount(),
                                    op == null ? 0L : op.activeConnectorCount(),
                                    op == null ? 0L : op.failedReconciliationsInWindow(),
                                    op == null ? null : op.lastCompleteAt())
                            : new SourceOperationalResource(
                                    operationalResult.status(), null, null, null, null);
                    return new SourceSystemResource(
                            source.sourceSystemId(),
                            source.code(),
                            source.name(),
                            source.linkedIdentityCount(),
                            opResource);
                })
                .toList();
        return new SourceSystemsResource(AVAILABLE, sourceResult.value().totalCount(), items);
    }

    private static BreakdownResource identityBreakdown(
            Result<List<IdentityOperationsOverviewQuery.IdentityTypeCount>> result) {
        if (!result.available()) return new BreakdownResource(result.status(), List.of());
        return new BreakdownResource(
                AVAILABLE,
                result.value().stream()
                        .map(item -> new BreakdownItemResource(item.identityType(), item.count()))
                        .toList());
    }

    private static BreakdownResource applicationBreakdown(
            Result<List<CatalogOperationsOverviewQuery.ApplicationOriginCount>> result) {
        if (!result.available()) return new BreakdownResource(result.status(), List.of());
        return new BreakdownResource(
                AVAILABLE,
                result.value().stream()
                        .map(item -> new BreakdownItemResource(item.origin(), item.count()))
                        .toList());
    }

    private static RecentActivityResource recentActivity(
            Result<List<AuditOperationsOverviewQuery.ActivityItem>> result) {
        if (!result.available()) return new RecentActivityResource(result.status(), List.of());
        return new RecentActivityResource(
                AVAILABLE,
                result.value().stream()
                        .map(item -> new RecentActivityItemResource(
                                item.id(), item.occurredAt(), item.actorId(), item.actorDisplayLabel(),
                                item.actionType(), item.resourceType(), item.resourceId(),
                                item.resourceDisplayLabel(), item.outcome()))
                        .toList());
    }

    private MetricResource metric(
            AuthenticatedAdministrativeActor actor,
            AdministrativePermission permission,
            String resourceType,
            Instant now,
            Supplier<Long> query) {
        if (!allowed(actor, permission, resourceType, now)) {
            return new MetricResource(NOT_AUTHORIZED, null);
        }
        return safe(query).metric(Long::longValue);
    }

    private boolean allowed(
            AuthenticatedAdministrativeActor actor,
            AdministrativePermission permission,
            String resourceType,
            Instant now) {
        return authorization.authorize(
                actor,
                permission,
                AdministrativeResource.collection(resourceType),
                now).allowed();
    }

    private static MyWorkResource work(
            Result<GovernanceOperationsOverviewQuery.Snapshot> result,
            WorkType type,
            WorkSort sort,
            Instant asOf) {
        if (!result.available()) {
            return new MyWorkResource(result.status(), UNKNOWN, asOf, type.name(), sort.name(), null, null, null, List.of());
        }
        var value = result.value();
        List<WorkItemResource> items = value.workItems().stream()
                .map(item -> new WorkItemResource(
                        item.kind(), item.id(), item.subjectKind(), item.subjectId(), item.stageOrdinal(), item.createdAt()))
                .toList();
        return new MyWorkResource(
                AVAILABLE, FRESH, asOf, type.name(), sort.name(), value.pendingApprovals() + value.pendingReviews(),
                value.pendingApprovals(), value.pendingReviews(), items);
    }

    private static GovernanceActivityResource governanceActivity(
            Result<GovernanceOperationsOverviewQuery.Snapshot> result,
            Instant asOf) {
        if (!result.available()) {
            return new GovernanceActivityResource(result.status(), UNKNOWN, asOf, null, null, null, null);
        }
        var totals = result.value().decisions();
        return new GovernanceActivityResource(
                AVAILABLE, FRESH, asOf, totals.approvals(), totals.rejections(), totals.keeps(), totals.revokes());
    }

    private static ProvisioningResource provisioning(
            Result<IntegrationOperationsOverviewQuery.Provisioning> result,
            Instant asOf) {
        if (!result.available()) {
            return new ProvisioningResource(result.status(), UNKNOWN, asOf, null, List.of(), List.of());
        }
        var value = result.value();
        return new ProvisioningResource(
                AVAILABLE,
                FRESH,
                asOf,
                new ProvisioningSummaryResource(
                        value.runningTasksNow(), value.succeededAttempts(), value.retryableFailedAttempts(), value.finalFailedAttempts()),
                value.trend().stream()
                        .map(bucket -> new AttemptBucketResource(
                                bucket.bucketStart(), bucket.succeeded(), bucket.failedRetryable(), bucket.failedFinal()))
                        .toList(),
                value.failureCategories().stream()
                        .map(category -> new FailureCategoryResource(category.category(), category.count()))
                        .toList());
    }

    private static ReconciliationResource reconciliation(
            Result<IntegrationOperationsOverviewQuery.Reconciliation> result,
            Instant asOf) {
        if (!result.available()) {
            return new ReconciliationResource(result.status(), UNKNOWN, asOf, null, List.of());
        }
        var value = result.value();
        return new ReconciliationResource(
                AVAILABLE,
                FRESH,
                asOf,
                new ReconciliationSummaryResource(
                        value.runningNow(), value.failedInWindow(), value.completeSucceededInWindow(),
                        value.lastCompleteAt(), value.lastCompleteRunId()),
                value.recentRuns().stream()
                        .map(run -> new ReconciliationRunResource(
                                run.id(), run.connectorBindingId(), run.state(), run.effectiveCompleteness(), run.failureCode(),
                                run.startedAt(), run.completedAt()))
                        .toList());
    }

    private static SecurityEvidenceResource evidence(
            Result<AuditOperationsOverviewQuery.Evidence> result,
            Instant asOf) {
        if (!result.available()) {
            return new SecurityEvidenceResource(result.status(), UNKNOWN, asOf, null, null, List.of());
        }
        var value = result.value();
        return new SecurityEvidenceResource(
                AVAILABLE,
                FRESH,
                asOf,
                value.deniedCount(),
                value.failureCount(),
                value.recent().stream()
                        .map(item -> new AuditEvidenceItemResource(
                                item.id(), item.occurredAt(), item.actionType(), item.resourceType(), item.resourceId(),
                                item.outcome(), item.correlationId()))
                        .toList());
    }

    private static WorkType workType(String raw, UUID correlationId) {
        try {
            return WorkType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException invalid) {
            throw IamOperationsApiException.validation(correlationId, "workType must be ALL, APPROVAL, or REVIEW.");
        }
    }

    private static WorkSort workSort(String raw, UUID correlationId) {
        try {
            return WorkSort.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException invalid) {
            throw IamOperationsApiException.validation(correlationId, "workSort must be OLDEST or NEWEST.");
        }
    }

    private static WindowSpec window(String raw, Instant now, UUID correlationId) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if ("24h".equals(value)) {
            Instant from = now.truncatedTo(ChronoUnit.HOURS).minus(23, ChronoUnit.HOURS);
            return new WindowSpec("24h", from, now, BucketUnit.HOUR, 24);
        }
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        if ("7d".equals(value)) {
            Instant from = today.minusDays(6).atStartOfDay(ZoneOffset.UTC).toInstant();
            return new WindowSpec("7d", from, now, BucketUnit.DAY, 7);
        }
        if ("30d".equals(value)) {
            Instant from = today.minusDays(29).atStartOfDay(ZoneOffset.UTC).toInstant();
            return new WindowSpec("30d", from, now, BucketUnit.DAY, 30);
        }
        throw IamOperationsApiException.validation(correlationId, "window must be 24h, 7d, or 30d.");
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
            throw IamOperationsApiException.validation(id, "X-Correlation-Id must be a UUID.");
        }
    }

    private static <T> Result<T> safe(Supplier<T> supplier) {
        try {
            return Result.available(Objects.requireNonNull(supplier.get()));
        } catch (DataAccessException unavailable) {
            return Result.unavailable();
        }
    }

    private record WindowSpec(String preset, Instant from, Instant until, BucketUnit bucketUnit, int bucketCount) {}

    private record Result<T>(String status, T value) {
        static <T> Result<T> available(T value) { return new Result<>(AVAILABLE, value); }
        static <T> Result<T> notAuthorized() { return new Result<>(NOT_AUTHORIZED, null); }
        static <T> Result<T> unavailable() { return new Result<>(UNAVAILABLE, null); }
        boolean available() { return AVAILABLE.equals(status); }
        MetricResource metric(java.util.function.ToLongFunction<T> extractor) {
            return available()
                    ? new MetricResource(AVAILABLE, extractor.applyAsLong(value))
                    : new MetricResource(status, null);
        }
    }
}
