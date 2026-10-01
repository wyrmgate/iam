package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.administration.application.AdministrativeElevationApprovalCommand;
import io.wyrmgate.iam.governance.application.ApprovalModels.CaseState;
import io.wyrmgate.iam.governance.application.ApprovalModels.SubjectKind;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class GovernanceAdministrativeElevationApprovalService
        implements AdministrativeElevationApprovalCommand {

    private final AdministrativeElevationApproverResolver resolver;
    private final ApprovalCaseStartService starter;
    private final ApprovalRepository repository;

    public GovernanceAdministrativeElevationApprovalService(
            AdministrativeElevationApproverResolver resolver,
            ApprovalCaseStartService starter,
            ApprovalRepository repository) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.starter = Objects.requireNonNull(starter, "starter");
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public ApprovalReference requestApproval(
            TenantContext tenant,
            UUID elevationId,
            UUID initiatorIdentityId,
            UUID beneficiaryIdentityId,
            Instant now) {
        var plan = resolver.resolve(
                tenant, elevationId, initiatorIdentityId, beneficiaryIdentityId);
        boolean selfApproval = plan.stages().stream()
                .flatMap(stage -> stage.approverIdentityIds().stream())
                .anyMatch(approver -> approver.equals(initiatorIdentityId)
                        || approver.equals(beneficiaryIdentityId));
        if (selfApproval) {
            throw new ApprovalCommandException(
                    "elevation_self_approval_forbidden",
                    "Administrative elevation approvers must exclude the initiator and beneficiary.");
        }
        String fingerprint = ApprovalCaseStartService.contentHash(plan);
        var approvalCase = starter.start(
                tenant,
                SubjectKind.ADMINISTRATIVE_ELEVATION,
                elevationId,
                initiatorIdentityId,
                plan,
                now);
        return new ApprovalReference(approvalCase.id(), fingerprint);
    }

    @Override
    public ApprovalStatus currentApproval(TenantContext tenant, UUID elevationId) {
        var approvalCase = repository.findLatestBySubject(
                        tenant, SubjectKind.ADMINISTRATIVE_ELEVATION, elevationId)
                .orElse(null);
        if (approvalCase == null) {
            return new ApprovalStatus(null, null, Outcome.UNAVAILABLE);
        }
        String fingerprint = repository.findPlan(tenant, approvalCase.id()).contentHash();
        Outcome outcome = switch (approvalCase.state()) {
            case PENDING -> Outcome.PENDING;
            case APPROVED -> Outcome.APPROVED;
            case REJECTED -> Outcome.REJECTED;
            case CANCELLED, SUPERSEDED -> Outcome.REJECTED;
        };
        return new ApprovalStatus(approvalCase.id(), fingerprint, outcome);
    }
}
