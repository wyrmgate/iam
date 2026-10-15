import { ApiError } from '../api';
import type { AccessAssignmentView, AssignmentLifecycle, AssignmentProvenance } from '../accessApi';

export function shortId(value: string | null | undefined): string {
  if (!value) return '—';
  return value.length <= 24 ? value : `${value.slice(0, 8)}…${value.slice(-6)}`;
}

export function formatDateTime(value: string | null | undefined): string {
  if (!value) return 'No end date';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString([], { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

export function formatDate(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleDateString([], { year: 'numeric', month: 'short', day: 'numeric' });
}

export function lifecycleLabel(state: AssignmentLifecycle): string {
  switch (state) {
    case 'SCHEDULED': return 'Scheduled intent';
    case 'ACTIVE': return 'Active intent';
    case 'SUSPENDED': return 'Suspended intent';
    case 'REVOKED': return 'Revoked';
    case 'EXPIRED': return 'Expired';
    case 'CANCELLED': return 'Cancelled';
  }
}

export function validityLabel(value: AccessAssignmentView): string {
  if (value.validityState === 'FUTURE') return value.validFrom ? `Starts ${formatDate(value.validFrom)}` : 'Future';
  if (value.validityState === 'ENDED') return value.validUntil ? `Ended ${formatDate(value.validUntil)}` : 'Ended';
  if (value.validUntil) return `Valid now · ends ${formatDate(value.validUntil)}`;
  return 'Valid now · no end date';
}

export function provenanceLabel(value: AssignmentProvenance): string {
  if (value === 'MANUAL') return 'Manual grant';
  if (value === 'REQUEST_ITEM') return 'Request item';
  return 'Lifecycle policy rule';
}

export function stateClass(value: string): string {
  return `access-state access-state-${value.toLowerCase().replaceAll('_', '-')}`;
}

export function errorTitle(error: unknown): string {
  if (!(error instanceof ApiError)) return 'Unavailable';
  if (error.status === 403) return 'Not authorized';
  if (error.status === 404) return 'Not found';
  if (error.status === 412) return 'Resource changed';
  if (error.status === 409) return 'State conflict';
  return 'Unavailable';
}

export function semanticErrorCopy(error: unknown): string {
  if (!(error instanceof ApiError)) return 'Wyrmgate could not complete this operation.';
  if (error.status === 403) return 'Administrative Authorization denied this operation. Server authorization is final.';
  if (error.status === 412) return 'Refresh and review the latest authoritative state before taking another action.';
  switch (error.code) {
    case 'access_assignment_not_active': return 'This assignment is no longer active.';
    case 'access_assignment_not_suspended': return 'This assignment can no longer be resumed.';
    case 'access_assignment_expired': return 'Temporal validity has already ended.';
    case 'access_assignment_not_cancellable': return 'This scheduled assignment can no longer be cancelled.';
    case 'access_assignment_not_revocable': return 'This assignment can no longer be revoked.';
    default: return error.message;
  }
}

export function readQuery(search: string): URLSearchParams {
  return new URLSearchParams(search.startsWith('?') ? search.slice(1) : search);
}

export function accessPath(mutator: (query: URLSearchParams) => void): string {
  const query = new URLSearchParams(window.location.search);
  mutator(query);
  const text = query.toString();
  return text ? `/access?${text}` : '/access';
}
