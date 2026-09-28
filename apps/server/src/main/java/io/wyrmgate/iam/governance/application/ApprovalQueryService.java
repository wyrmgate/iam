package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.domain.ApprovalCase;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class ApprovalQueryService {

    private final ApprovalRepository repository;

    public ApprovalQueryService(ApprovalRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public Optional<ApprovalCase> findCase(
            TenantContext tenant, UUID approvalCaseId) {
        return repository.findCase(tenant, approvalCaseId);
    }

    public InboxPage inbox(
            TenantContext tenant,
            UUID participantIdentityId,
            InboxPosition after,
            int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and 200");
        }
        List<ApprovalRepository.InboxItem> fetched =
                repository.findInboxPage(
                        tenant,
                        participantIdentityId,
                        after == null ? null : after.createdAt(),
                        after == null ? null : after.caseId(),
                        limit + 1);
        boolean more = fetched.size() > limit;
        List<ApprovalRepository.InboxItem> items = List.copyOf(
                fetched.subList(0, Math.min(limit, fetched.size())));
        InboxPosition next = more && !items.isEmpty()
                ? new InboxPosition(
                        items.getLast().approvalCase().createdAt(),
                        items.getLast().approvalCase().id())
                : null;
        return new InboxPage(items, next);
    }

    public record InboxPosition(Instant createdAt, UUID caseId) {}

    public record InboxPage(
            List<ApprovalRepository.InboxItem> items,
            InboxPosition nextPosition) {
        public InboxPage {
            items = List.copyOf(items);
        }
    }
}
