import {
  V1_ROOT,
  apiRequest,
  createIdempotencyKey,
  type ApiResult,
  type IdentityPage,
} from './api';

export type AssignmentLifecycle = 'SCHEDULED' | 'ACTIVE' | 'SUSPENDED' | 'REVOKED' | 'EXPIRED' | 'CANCELLED';
export type AssignmentValidity = 'CURRENT' | 'FUTURE' | 'ENDED';
export type AccessTargetKind = 'ROLE' | 'ENTITLEMENT';
export type PrincipalConstraintKind = 'ANY' | 'SPECIFIC';
export type AssignmentProvenance = 'MANUAL' | 'REQUEST_ITEM' | 'LIFECYCLE_POLICY_RULE';

export type AccessAssignmentView = {
  id: string;
  identityId: string;
  identityLabel?: string | null;
  targetKind: AccessTargetKind;
  targetId: string;
  targetLabel?: string | null;
  principalConstraintKind: PrincipalConstraintKind;
  specificPrincipalId?: string | null;
  specificPrincipalLabel?: string | null;
  provenanceKind: AssignmentProvenance;
  provenanceRefId?: string | null;
  lifecycleState: AssignmentLifecycle;
  validityState: AssignmentValidity;
  evaluatedAt: string;
  validFrom?: string | null;
  validUntil?: string | null;
  revision: number;
  createdAt: string;
  updatedAt: string;
};

export type AccessAssignmentViewPage = {
  items: AccessAssignmentView[];
  nextCursor?: string | null;
  evaluatedAt: string;
};

export type AssignmentFilters = {
  identityId?: string;
  lifecycleState?: AssignmentLifecycle;
  targetKind?: AccessTargetKind;
  targetId?: string;
  provenance?: AssignmentProvenance;
  validity?: AssignmentValidity;
};

export type EffectiveAccessSummary = {
  id: string;
  identityId: string;
  identityLabel?: string | null;
  entitlementId: string;
  entitlementLabel?: string | null;
  principalConstraintKey: string;
  supportCount: number;
  computedAt: string;
  projectionGeneration: number;
};

export type EffectiveAccessSupport = {
  accessAssignmentId: string;
  pathHash: string;
  pathDepth: number;
  roleVersionPath: string[];
};

export type EffectiveAccessResource = EffectiveAccessSummary & {
  supports: EffectiveAccessSupport[];
};

export type EffectiveAccessPage = {
  items: EffectiveAccessSummary[];
  nextCursor?: string | null;
};

export type PrincipalResource = {
  id: string;
  identityId?: string | null;
  applicationTargetId: string;
  kind: string;
  nativePrincipalKey: string;
  lifecycleState: string;
  revision: number;
};

export type PrincipalPage = { items: PrincipalResource[]; nextCursor?: string | null };

export type CatalogAccessTarget = {
  id: string;
  kind: AccessTargetKind;
  label: string;
  code: string;
  secondaryLabel?: string | null;
};

export type CatalogAccessTargetPage = { items: CatalogAccessTarget[] };

export type LifecyclePolicyExpectedValue = {
  type: 'STRING' | 'BOOLEAN' | 'INTEGER' | 'DECIMAL' | 'DATE' | 'DATETIME' | 'ENUM';
  stringValue?: string | null;
  booleanValue?: boolean | null;
  integerValue?: number | null;
  decimalValue?: string | null;
  dateValue?: string | null;
  dateTimeValue?: string | null;
  enumValue?: string | null;
};

export type LifecycleAccessPolicyRule = {
  ruleId: string;
  predicateKind: string;
  canonicalKey?: string | null;
  expectedString?: string | null;
  expectedBoolean?: boolean | null;
  expectedInteger?: number | null;
  expectedDecimal?: string | null;
  expectedDate?: string | null;
  expectedDateTime?: string | null;
  expectedEnum?: string | null;
  expectedSet?: LifecyclePolicyExpectedValue[];
  targetKind: AccessTargetKind;
  targetId: string;
};

export type LifecycleAccessPolicy = {
  id: string;
  versionNumber: number;
  state: 'ACTIVE' | 'SUPERSEDED';
  rules: LifecycleAccessPolicyRule[];
  createdAt: string;
  activatedAt: string;
  supersededAt?: string | null;
};

export type AuditRecord = {
  id: string;
  occurredAt: string;
  recordedAt?: string;
  actionType: string;
  resourceType: string;
  resourceId?: string | null;
  outcome: 'SUCCESS' | 'DENIED' | 'FAILURE';
  correlationId?: string | null;
};
export type AuditRecordPage = { items: AuditRecord[]; nextCursor?: string | null };

export function assignmentViewPath(filters: AssignmentFilters, cursor?: string | null): string {
  const query = new URLSearchParams({ limit: '50' });
  if (cursor) query.set('cursor', cursor);
  if (filters.identityId) query.set('identityId', filters.identityId);
  if (filters.lifecycleState) query.set('lifecycleState', filters.lifecycleState);
  if (filters.targetKind) query.set('targetKind', filters.targetKind);
  if (filters.targetId) query.set('targetId', filters.targetId);
  if (filters.provenance) query.set('provenance', filters.provenance);
  if (filters.validity) query.set('validity', filters.validity);
  return `${V1_ROOT}/access-assignment-views?${query.toString()}`;
}

export async function listAssignmentViews(
  filters: AssignmentFilters,
  cursor?: string | null,
  signal?: AbortSignal,
): Promise<AccessAssignmentViewPage> {
  return (await apiRequest<AccessAssignmentViewPage>(assignmentViewPath(filters, cursor), { signal })).data;
}

export function getAssignmentView(id: string, signal?: AbortSignal): Promise<ApiResult<AccessAssignmentView>> {
  return apiRequest<AccessAssignmentView>(`${V1_ROOT}/access-assignment-views/${encodeURIComponent(id)}`, { signal });
}

export type CreateAssignmentInput = {
  identityId: string;
  targetKind: AccessTargetKind;
  roleId: string | null;
  entitlementId: string | null;
  principalConstraintKind: PrincipalConstraintKind;
  specificPrincipalId: string | null;
  validFrom: string | null;
  validUntil: string | null;
};

export async function createAssignment(
  input: CreateAssignmentInput,
  idempotencyKey = createIdempotencyKey(),
): Promise<ApiResult<{ id: string }>> {
  return apiRequest<{ id: string }>(`${V1_ROOT}/access-assignments`, {
    method: 'POST',
    body: input,
    idempotencyKey,
  });
}

export type AssignmentAction = 'suspend' | 'resume' | 'cancel' | 'revoke';

export function mutateAssignment(
  id: string,
  action: AssignmentAction,
  etag: string,
  idempotencyKey: string,
): Promise<ApiResult<unknown>> {
  return apiRequest(`${V1_ROOT}/access-assignments/${encodeURIComponent(id)}/${action}`, {
    method: 'POST',
    etag,
    idempotencyKey,
  });
}

export async function searchIdentities(q: string, signal?: AbortSignal): Promise<IdentityPage> {
  const query = new URLSearchParams({ q: q.trim(), limit: '8' });
  return (await apiRequest<IdentityPage>(`${V1_ROOT}/identities?${query.toString()}`, { signal })).data;
}

export async function listIdentityPrincipals(identityId: string, signal?: AbortSignal): Promise<PrincipalPage> {
  const query = new URLSearchParams({ identityId, limit: '20' });
  return (await apiRequest<PrincipalPage>(`${V1_ROOT}/principals?${query.toString()}`, { signal })).data;
}

export async function searchAccessTargets(
  kind: AccessTargetKind,
  q: string,
  signal?: AbortSignal,
): Promise<CatalogAccessTargetPage> {
  const query = new URLSearchParams({ kind, q: q.trim(), limit: '20' });
  return (await apiRequest<CatalogAccessTargetPage>(`${V1_ROOT}/catalog/access-targets?${query.toString()}`, { signal })).data;
}

export async function listEffectiveAccess(
  identityId?: string,
  cursor?: string | null,
  signal?: AbortSignal,
): Promise<EffectiveAccessPage> {
  const query = new URLSearchParams({ limit: '50' });
  if (identityId) query.set('identityId', identityId);
  if (cursor) query.set('cursor', cursor);
  return (await apiRequest<EffectiveAccessPage>(`${V1_ROOT}/effective-access-views?${query.toString()}`, { signal })).data;
}

export function getEffectiveAccess(id: string, signal?: AbortSignal): Promise<ApiResult<EffectiveAccessResource>> {
  return apiRequest<EffectiveAccessResource>(`${V1_ROOT}/effective-access-views/${encodeURIComponent(id)}`, { signal });
}

export async function getLifecycleAccessPolicy(signal?: AbortSignal): Promise<LifecycleAccessPolicy> {
  return (await apiRequest<LifecycleAccessPolicy>(`${V1_ROOT}/lifecycle-access-policy`, { signal })).data;
}

export function activateLifecycleAccessPolicy(
  rules: LifecycleAccessPolicyRule[],
  idempotencyKey: string,
): Promise<ApiResult<LifecycleAccessPolicy>> {
  return apiRequest<LifecycleAccessPolicy>(`${V1_ROOT}/lifecycle-access-policy:activate`, {
    method: 'POST',
    body: { rules },
    idempotencyKey,
  });
}

export async function listAssignmentAudit(
  assignmentId: string,
  signal?: AbortSignal,
): Promise<AuditRecordPage> {
  const query = new URLSearchParams({
    resourceType: 'access-assignment',
    resourceId: assignmentId,
    limit: '5',
  });
  return (await apiRequest<AuditRecordPage>(`${V1_ROOT}/audit-records?${query.toString()}`, { signal })).data;
}
