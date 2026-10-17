import { apiRequest, V1_ROOT } from './api';

export type OverviewStatus = 'AVAILABLE' | 'NOT_AUTHORIZED' | 'UNAVAILABLE' | 'UNSUPPORTED';
export type OverviewFreshness = 'FRESH' | 'STALE' | 'UNKNOWN';
export type OverviewWindow = '24h' | '7d' | '30d';
export type OverviewWorkType = 'ALL' | 'APPROVAL' | 'REVIEW';
export type OverviewWorkSort = 'OLDEST' | 'NEWEST';

export type OverviewMetric = { status: OverviewStatus; value: number | null };
export type OverviewWorkItem = {
  kind: 'APPROVAL' | 'REVIEW';
  id: string;
  subjectKind: string;
  subjectId: string;
  stageOrdinal: number;
  createdAt: string;
};
export type OverviewAttemptBucket = {
  bucketStart: string;
  succeeded: number;
  failedRetryable: number;
  failedFinal: number;
};
export type OverviewFailureCategory = { category: string; count: number };
export type OverviewReconciliationRun = {
  id: string;
  connectorBindingId: string;
  state: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';
  effectiveCompleteness: 'COMPLETE' | 'PARTIAL' | 'UNKNOWN';
  failureCode?: string | null;
  startedAt: string;
  completedAt?: string | null;
};
export type OverviewAuditEvidence = {
  id: string;
  occurredAt: string;
  actionType: string;
  resourceType: string;
  resourceId?: string | null;
  outcome: 'DENIED' | 'FAILURE';
  correlationId?: string | null;
};
export type OverviewBreakdown = {
  status: OverviewStatus;
  items: Array<{ key: string; value: number }>;
};
export type OverviewTenantOwner = {
  identityId: string;
  displayName?: string | null;
  administrativelyEligible: boolean;
};
export type OverviewSourceSystem = {
  id: string;
  code: string;
  name: string;
  linkedIdentityCount: number;
  operational: {
    status: OverviewStatus;
    activeBindingCount: number | null;
    activeConnectorCount: number | null;
    failedReconciliationsInWindow: number | null;
    lastCompleteAt?: string | null;
  };
};
export type OverviewRecentActivity = {
  id: string;
  occurredAt: string;
  actorId?: string | null;
  actorDisplayLabel?: string | null;
  actionType: string;
  resourceType: string;
  resourceId?: string | null;
  resourceDisplayLabel?: string | null;
  outcome: 'SUCCESS' | 'DENIED' | 'FAILURE';
};

export type IamOperationsOverview = {
  asOf: string;
  partial: boolean;
  tenantId: string;
  window: { preset: OverviewWindow; from: string; until: string };
  attention: {
    pendingApprovals: OverviewMetric;
    pendingReviews: OverviewMetric;
    finalProvisioningFailures: OverviewMetric;
    failedReconciliations: OverviewMetric;
  };
  myWork: {
    status: OverviewStatus;
    freshness: OverviewFreshness;
    asOf: string;
    appliedType: OverviewWorkType;
    appliedSort: OverviewWorkSort;
    totalCount: number | null;
    approvalCount: number | null;
    reviewCount: number | null;
    items: OverviewWorkItem[];
  };
  provisioning: {
    status: OverviewStatus;
    freshness: OverviewFreshness;
    asOf: string;
    summary: {
      runningTasksNow: number;
      succeededAttempts: number;
      retryableFailedAttempts: number;
      finalFailedAttempts: number;
    } | null;
    trend: OverviewAttemptBucket[];
    failureCategories: OverviewFailureCategory[];
  };
  reconciliation: {
    status: OverviewStatus;
    freshness: OverviewFreshness;
    asOf: string;
    summary: {
      runningNow: number;
      failedInWindow: number;
      completeSucceededInWindow: number;
      lastCompleteAt?: string | null;
      lastCompleteRunId?: string | null;
    } | null;
    recentRuns: OverviewReconciliationRun[];
  };
  myGovernanceActivity: {
    status: OverviewStatus;
    freshness: OverviewFreshness;
    asOf: string;
    approvals: number | null;
    rejections: number | null;
    keeps: number | null;
    revokes: number | null;
  };
  securityEvidence: {
    status: OverviewStatus;
    freshness: OverviewFreshness;
    asOf: string;
    deniedCount: number | null;
    failureCount: number | null;
    recent: OverviewAuditEvidence[];
  };
  environment: {
    asOf: string;
    activeIdentities: OverviewMetric;
    uncorrelatedPrincipals: OverviewMetric;
    activeAccessAssignments: OverviewMetric;
    applications: OverviewMetric;
    activeConnectors: OverviewMetric;
  };
  tenantDashboard: {
    identityTypes: OverviewBreakdown;
    applicationOrigins: OverviewBreakdown;
    tenantOwners: {
      status: OverviewStatus;
      effectiveOwnerCount: number | null;
      viableOwnerCount: number | null;
      owners: OverviewTenantOwner[];
    };
    sources: {
      status: OverviewStatus;
      totalCount: number | null;
      items: OverviewSourceSystem[];
    };
    recentActivity: {
      status: OverviewStatus;
      items: OverviewRecentActivity[];
    };
  };
};

export async function getIamOperationsOverview(
  window: OverviewWindow,
  workType: OverviewWorkType,
  workSort: OverviewWorkSort,
  signal?: AbortSignal,
): Promise<IamOperationsOverview> {
  const query = new URLSearchParams({ window, workType, workSort });
  return (await apiRequest<IamOperationsOverview>(
    `${V1_ROOT}/console/iam-operations?${query.toString()}`,
    { signal },
  )).data;
}
