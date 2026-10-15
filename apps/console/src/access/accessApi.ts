import { apiRequest, createIdempotencyKey, type ApiResult } from '../api';
import type {
  AssignmentFilters,
  AssignmentPage,
  AssignmentResource,
  AuditRecordPage,
  EffectiveDetail,
  EffectivePage,
  LifecyclePolicyResource,
  LifecyclePolicyRule,
  OptionPage,
  PrincipalConstraintKind,
  TargetKind,
} from './accessTypes';

const ROOT = '/api/v1';
const WORKBENCH = `${ROOT}/console/access`;

function queryPath(path: string, values: Record<string, string | number | null | undefined>) {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(values)) {
    if (value !== undefined && value !== null && value !== '') query.set(key, String(value));
  }
  const suffix = query.toString();
  return suffix ? `${path}?${suffix}` : path;
}

export function listAssignments(
  filters: AssignmentFilters,
  cursor?: string | null,
  signal?: AbortSignal,
): Promise<ApiResult<AssignmentPage>> {
  return apiRequest<AssignmentPage>(queryPath(`${WORKBENCH}/assignments`, {
    identityId: filters.identityId,
    lifecycleState: filters.lifecycleState,
    targetKind: filters.targetKind,
    targetId: filters.targetId,
    provenance: filters.provenance,
    validity: filters.validity,
    cursor,
    limit: 50,
  }), { signal });
}

export function getAssignment(assignmentId: string, signal?: AbortSignal): Promise<ApiResult<AssignmentResource>> {
  return apiRequest<AssignmentResource>(`${WORKBENCH}/assignments/${encodeURIComponent(assignmentId)}`, { signal });
}

export function mutateAssignment(
  assignmentId: string,
  action: 'suspend' | 'resume' | 'cancel' | 'revoke',
  etag: string,
  idempotencyKey: string,
) {
  return apiRequest<Record<string, unknown>>(`${ROOT}/access-assignments/${encodeURIComponent(assignmentId)}/${action}`, {
    method: 'POST',
    etag,
    idempotencyKey,
  });
}

export type CreateAssignmentInput = {
  identityId: string;
  targetKind: TargetKind;
  roleId: string | null;
  entitlementId: string | null;
  principalConstraintKind: PrincipalConstraintKind;
  specificPrincipalId: string | null;
  validFrom: string | null;
  validUntil: string | null;
};

export function createAssignment(input: CreateAssignmentInput, idempotencyKey: string) {
  return apiRequest<{ id: string; lifecycleState: string }>(`${ROOT}/access-assignments`, {
    method: 'POST',
    body: input,
    idempotencyKey,
  });
}

export function listEffective(identityId?: string, cursor?: string | null, signal?: AbortSignal) {
  return apiRequest<EffectivePage>(queryPath(`${WORKBENCH}/effective`, { identityId, cursor, limit: 50 }), { signal });
}

export function getEffective(effectiveAccessId: string, signal?: AbortSignal) {
  return apiRequest<EffectiveDetail>(`${WORKBENCH}/effective/${encodeURIComponent(effectiveAccessId)}`, { signal });
}

export function identityOptions(query: string, signal?: AbortSignal) {
  return apiRequest<OptionPage>(queryPath(`${WORKBENCH}/identity-options`, { query, limit: 20 }), { signal });
}

export function targetOptions(targetKind: TargetKind, query: string, signal?: AbortSignal) {
  return apiRequest<OptionPage>(queryPath(`${WORKBENCH}/target-options`, { targetKind, query, limit: 20 }), { signal });
}

export function principalOptions(identityId: string, signal?: AbortSignal) {
  return apiRequest<OptionPage>(queryPath(`${WORKBENCH}/principal-options`, { identityId, limit: 25 }), { signal });
}

export function getLifecyclePolicy(signal?: AbortSignal) {
  return apiRequest<LifecyclePolicyResource>(`${ROOT}/lifecycle-access-policy`, { signal });
}

export function activateLifecyclePolicy(rules: LifecyclePolicyRule[], idempotencyKey: string) {
  return apiRequest<LifecyclePolicyResource>(`${ROOT}/lifecycle-access-policy:activate`, {
    method: 'POST',
    body: { rules },
    idempotencyKey,
  });
}

export function listAssignmentAudit(assignmentId: string, signal?: AbortSignal) {
  return apiRequest<AuditRecordPage>(queryPath(`${ROOT}/audit-records`, {
    resourceType: 'access-assignment',
    resourceId: assignmentId,
    limit: 5,
  }), { signal });
}

export function newCausalKey() {
  return createIdempotencyKey();
}
