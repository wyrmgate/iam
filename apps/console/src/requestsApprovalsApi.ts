import { apiRequest, type ApiResult, V1_ROOT } from './api';

export type RequestsWindow = '24h' | '7d' | '30d';
export type ApprovalSort = 'OLDEST' | 'NEWEST';

export type RequestsAttention = {
  pendingApprovals: number;
  draftRequests: number;
  inFlightRequestItems: number;
  appliedRequestItems: number;
};

export type RequestStatusTotals = {
  draft: number;
  inProgress: number;
  completedApplied: number;
  completedMixed: number;
  completedNoAccess: number;
};

export type ApprovalWork = {
  approvalCaseId: string;
  subjectKind: string;
  subjectId: string;
  accessRequestId?: string | null;
  targetKind?: string | null;
  targetId?: string | null;
  currentStageOrdinal: number;
  createdAt: string;
};

export type RequestSummary = {
  requestId: string;
  presentationStatus: 'DRAFT' | 'IN_PROGRESS' | 'COMPLETED_APPLIED' | 'COMPLETED_MIXED' | 'COMPLETED_NO_ACCESS';
  itemCount: number;
  draftItems: number;
  submittedItems: number;
  evaluatingItems: number;
  pendingApprovalItems: number;
  authorizedItems: number;
  appliedItems: number;
  deniedItems: number;
  rejectedItems: number;
  cancelledItems: number;
  expiredItems: number;
  singleTargetKind?: string | null;
  singleTargetId?: string | null;
  createdAt: string;
  updatedAt: string;
};

export type RequestsAndApprovals = {
  asOf: string;
  tenantId: string;
  actorIdentityId: string;
  window: { preset: RequestsWindow; from: string; until: string };
  approvalSort: ApprovalSort;
  attention: RequestsAttention;
  requestStatusTotals: RequestStatusTotals;
  approvals: ApprovalWork[];
  requests: RequestSummary[];
};

export type RequestItem = {
  id: string;
  accessRequestId: string;
  targetKind: 'ROLE' | 'ENTITLEMENT';
  roleId?: string | null;
  entitlementId?: string | null;
  principalConstraintKind: 'ANY' | 'SPECIFIC';
  specificPrincipalId?: string | null;
  validFrom?: string | null;
  validUntil?: string | null;
  state: string;
  approvalCaseId?: string | null;
  accessAssignmentId?: string | null;
  evaluationCode?: string | null;
  revision: number;
  createdAt: string;
  updatedAt: string;
};

export type AccessRequest = {
  id: string;
  requesterIdentityId: string;
  beneficiaryIdentityId: string;
  state: string;
  revision: number;
  createdAt: string;
  submittedAt?: string | null;
  updatedAt: string;
  items: RequestItem[];
};

export type ApprovalDecision = {
  id: string;
  approverIdentityId: string;
  decision: 'APPROVE' | 'REJECT';
  reason?: string | null;
  decidedAt: string;
};

export type ApprovalStage = {
  id: string;
  ordinal: number;
  decisionMode: 'ANY_ONE' | 'ALL';
  approvers: Array<{ approverIdentityId: string }>;
  decisions: ApprovalDecision[];
};

export type Approval = {
  id: string;
  subjectKind: string;
  subjectId: string;
  initiatorIdentityId: string;
  state: string;
  currentStageOrdinal: number;
  revision: number;
  createdAt: string;
  updatedAt: string;
  completedAt?: string | null;
  planId: string;
  planNumber: number;
  planCreatedAt: string;
  stages: ApprovalStage[];
};

export async function getRequestsAndApprovals(
  window: RequestsWindow,
  approvalSort: ApprovalSort,
  signal?: AbortSignal,
): Promise<RequestsAndApprovals> {
  const params = new URLSearchParams({ window, approvalSort });
  return (await apiRequest<RequestsAndApprovals>(`${V1_ROOT}/console/requests-and-approvals?${params}`, { signal })).data;
}

export function getApproval(id: string, signal?: AbortSignal): Promise<ApiResult<Approval>> {
  return apiRequest<Approval>(`${V1_ROOT}/governance/approvals/${encodeURIComponent(id)}`, { signal });
}

export function getAccessRequest(id: string, signal?: AbortSignal): Promise<ApiResult<AccessRequest>> {
  return apiRequest<AccessRequest>(`${V1_ROOT}/governance/access-requests/${encodeURIComponent(id)}`, { signal });
}

export function getRequestItem(id: string, signal?: AbortSignal): Promise<ApiResult<RequestItem>> {
  return apiRequest<RequestItem>(`${V1_ROOT}/governance/request-items/${encodeURIComponent(id)}`, { signal });
}

export function decideApproval(
  id: string,
  decision: 'approve' | 'reject',
  etag: string,
  reason: string,
): Promise<ApiResult<Approval>> {
  return apiRequest<Approval>(`${V1_ROOT}/governance/approvals/${encodeURIComponent(id)}/${decision}`, {
    method: 'POST',
    etag,
    idempotent: true,
    body: { reason: reason.trim() || null },
  });
}

export type NewAccessRequest = {
  beneficiaryIdentityId: string;
  items: Array<{
    targetKind: 'ROLE' | 'ENTITLEMENT';
    targetId: string;
    principalConstraintKind: 'ANY' | 'SPECIFIC';
    specificPrincipalId: string | null;
    validFrom: string | null;
    validUntil: string | null;
  }>;
};

export function createAccessRequest(input: NewAccessRequest): Promise<ApiResult<AccessRequest>> {
  return apiRequest<AccessRequest>(`${V1_ROOT}/governance/access-requests`, {
    method: 'POST',
    idempotent: true,
    body: input,
  });
}

export function submitAccessRequest(id: string, etag: string): Promise<ApiResult<AccessRequest>> {
  return apiRequest<AccessRequest>(`${V1_ROOT}/governance/access-requests/${encodeURIComponent(id)}/submit`, {
    method: 'POST',
    etag,
    idempotent: true,
  });
}
