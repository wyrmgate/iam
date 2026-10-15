import { useEffect, useMemo, useState } from 'react';
import {
  activateLifecycleAccessPolicy,
  createAssignment,
  getAssignmentView,
  getEffectiveAccess,
  getLifecycleAccessPolicy,
  listAssignmentAudit,
  listAssignmentViews,
  listEffectiveAccess,
  listIdentityPrincipals,
  mutateAssignment,
  searchAccessTargets,
  searchIdentities,
  type AccessAssignmentView,
  type AccessTargetKind,
  type AssignmentAction,
  type AssignmentFilters,
  type AssignmentLifecycle,
  type AssignmentProvenance,
  type AssignmentValidity,
  type AuditRecord,
  type CatalogAccessTarget,
  type EffectiveAccessResource,
  type EffectiveAccessSummary,
  type LifecycleAccessPolicy,
  type LifecycleAccessPolicyRule,
  type PrincipalConstraintKind,
  type PrincipalResource,
} from '../accessApi';
import { ApiError, createIdempotencyKey, type IdentityResource } from '../api';
import { useConsoleLocation } from '../consoleLocation';
import { identityDetailPath, navigateConsole } from '../consoleNavigation';
import { Box, Button, ConfirmDialog, Modal, StatePanel, TextInput } from '../ui';
import '../access-golden.css';

type AccessView = 'assignments' | 'effective' | 'policy';
type LoadState = 'loading' | 'ready' | 'error';

const LIFECYCLES: AssignmentLifecycle[] = ['SCHEDULED', 'ACTIVE', 'SUSPENDED', 'REVOKED', 'EXPIRED', 'CANCELLED'];
const VALIDITIES: AssignmentValidity[] = ['CURRENT', 'FUTURE', 'ENDED'];
const PROVENANCES: AssignmentProvenance[] = ['MANUAL', 'REQUEST_ITEM', 'LIFECYCLE_POLICY_RULE'];

function shortId(value: string | null | undefined): string {
  if (!value) return '—';
  return value.length <= 24 ? value : `${value.slice(0, 8)}…${value.slice(-6)}`;
}

function fmt(value: string | null | undefined): string {
  if (!value) return 'No end date';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString([], { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

function compact(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleDateString([], { year: 'numeric', month: 'short', day: 'numeric' });
}

function lifecycleLabel(state: AssignmentLifecycle): string {
  switch (state) {
    case 'SCHEDULED': return 'Scheduled intent';
    case 'ACTIVE': return 'Active intent';
    case 'SUSPENDED': return 'Suspended intent';
    case 'REVOKED': return 'Revoked';
    case 'EXPIRED': return 'Expired';
    case 'CANCELLED': return 'Cancelled';
  }
}

function validityLabel(value: AccessAssignmentView): string {
  if (value.validityState === 'FUTURE') return value.validFrom ? `Starts ${compact(value.validFrom)}` : 'Future';
  if (value.validityState === 'ENDED') return value.validUntil ? `Ended ${compact(value.validUntil)}` : 'Ended';
  if (value.validUntil) return `Valid now · ends ${compact(value.validUntil)}`;
  return 'Valid now · no end date';
}

function provenanceLabel(value: AssignmentProvenance): string {
  if (value === 'MANUAL') return 'Manual grant';
  if (value === 'REQUEST_ITEM') return 'Request item';
  return 'Lifecycle policy rule';
}

function stateClass(value: string): string {
  const normalized = value.toLowerCase().replaceAll('_', '-');
  return `access-state access-state-${normalized}`;
}

function errorTitle(error: unknown): string {
  if (!(error instanceof ApiError)) return 'Unavailable';
  if (error.status === 403) return 'Not authorized';
  if (error.status === 404) return 'Not found';
  if (error.status === 412) return 'Resource changed';
  if (error.status === 409) return 'State conflict';
  return 'Unavailable';
}

function semanticErrorCopy(error: unknown): string {
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

function readQuery(search: string): URLSearchParams {
  return new URLSearchParams(search.startsWith('?') ? search.slice(1) : search);
}

function currentView(search: string): AccessView {
  const requested = readQuery(search).get('view');
  return requested === 'effective' || requested === 'policy' ? requested : 'assignments';
}

function accessPath(mutator: (query: URLSearchParams) => void): string {
  const query = new URLSearchParams(window.location.search);
  mutator(query);
  const text = query.toString();
  return text ? `/access?${text}` : '/access';
}

function setAccessQuery(mutator: (query: URLSearchParams) => void): void {
  navigateConsole(accessPath(mutator));
}

function TabButton({ id, label, hint, selected }: { id: AccessView; label: string; hint: string; selected: boolean }) {
  return (
    <button
      type="button"
      role="tab"
      aria-selected={selected}
      className={`access-golden-tab${selected ? ' is-selected' : ''}`}
      onClick={() => setAccessQuery((query) => {
        query.set('view', id);
        query.delete('assignmentId');
        query.delete('effectiveAccessId');
      })}
    >
      <span>{label}</span><small>{hint}</small>
    </button>
  );
}

function InlineError({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  return (
    <StatePanel
      state={error instanceof ApiError && error.status === 403 ? 'warning' : 'error'}
      title={errorTitle(error)}
      action={onRetry ? <Button variant="outlined" onClick={onRetry}>Retry</Button> : undefined}
    >
      {semanticErrorCopy(error)}
    </StatePanel>
  );
}

function IdentityPicker({
  value,
  onSelect,
  label = 'Governed identity',
}: {
  value: IdentityResource | null;
  onSelect: (identity: IdentityResource | null) => void;
  label?: string;
}) {
  const [query, setQuery] = useState(value?.displayName ?? '');
  const [results, setResults] = useState<IdentityResource[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (value && query === value.displayName) return;
    const trimmed = query.trim();
    if (trimmed.length < 2) { setResults([]); return; }
    const controller = new AbortController();
    const handle = window.setTimeout(() => {
      setLoading(true);
      searchIdentities(trimmed, controller.signal)
        .then((page) => setResults(page.items))
        .catch((error) => { if (!(error instanceof DOMException && error.name === 'AbortError')) setResults([]); })
        .finally(() => setLoading(false));
    }, 250);
    return () => { window.clearTimeout(handle); controller.abort(); };
  }, [query, value]);

  return (
    <div className="access-picker">
      <label><span>{label}</span><input value={query} placeholder="Search governed identities…" onChange={(event) => { setQuery(event.target.value); if (value) onSelect(null); }} /></label>
      {loading ? <small className="access-picker-help">Searching…</small> : null}
      {results.length > 0 ? (
        <div className="access-picker-results" role="listbox" aria-label={`${label} results`}>
          {results.map((identity) => (
            <button key={identity.id} type="button" role="option" aria-selected={value?.id === identity.id} onClick={() => { onSelect(identity); setQuery(identity.displayName); setResults([]); }}>
              <strong>{identity.displayName}</strong><small>{identity.type} · {identity.lifecycleState}</small>
            </button>
          ))}
        </div>
      ) : null}
      {value ? <small className="access-picker-selected">Selected: {value.displayName} · {shortId(value.id)}</small> : null}
    </div>
  );
}

function TargetPicker({ kind, value, onSelect }: { kind: AccessTargetKind; value: CatalogAccessTarget | null; onSelect: (target: CatalogAccessTarget | null) => void }) {
  const [query, setQuery] = useState(value?.label ?? '');
  const [results, setResults] = useState<CatalogAccessTarget[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    setQuery(value?.label ?? '');
    setResults([]);
  }, [kind]);

  useEffect(() => {
    if (value && query === value.label) return;
    const trimmed = query.trim();
    if (trimmed.length < 2) { setResults([]); return; }
    const controller = new AbortController();
    const handle = window.setTimeout(() => {
      setLoading(true);
      searchAccessTargets(kind, trimmed, controller.signal)
        .then((page) => setResults(page.items))
        .catch((error) => { if (!(error instanceof DOMException && error.name === 'AbortError')) setResults([]); })
        .finally(() => setLoading(false));
    }, 250);
    return () => { window.clearTimeout(handle); controller.abort(); };
  }, [kind, query, value]);

  return (
    <div className="access-picker">
      <label><span>{kind === 'ROLE' ? 'Role' : 'Entitlement'}</span><input value={query} placeholder={`Search ${kind === 'ROLE' ? 'roles' : 'entitlements'}…`} onChange={(event) => { setQuery(event.target.value); if (value) onSelect(null); }} /></label>
      {loading ? <small className="access-picker-help">Searching…</small> : null}
      {results.length > 0 ? (
        <div className="access-picker-results" role="listbox" aria-label={`${kind} results`}>
          {results.map((target) => (
            <button key={target.id} type="button" role="option" aria-selected={value?.id === target.id} onClick={() => { onSelect(target); setQuery(target.label); setResults([]); }}>
              <strong>{target.label}</strong><small>{target.code}{target.secondaryLabel ? ` · ${target.secondaryLabel}` : ''}</small>
            </button>
          ))}
        </div>
      ) : null}
      {value ? <small className="access-picker-selected">Selected: {value.label} · {shortId(value.id)}</small> : null}
    </div>
  );
}

function GrantAccessDialog({ open, onClose, onCreated }: { open: boolean; onClose: () => void; onCreated: (id: string) => void }) {
  const [identity, setIdentity] = useState<IdentityResource | null>(null);
  const [targetKind, setTargetKind] = useState<AccessTargetKind>('ROLE');
  const [target, setTarget] = useState<CatalogAccessTarget | null>(null);
  const [constraint, setConstraint] = useState<PrincipalConstraintKind>('ANY');
  const [principals, setPrincipals] = useState<PrincipalResource[]>([]);
  const [principalId, setPrincipalId] = useState('');
  const [validFrom, setValidFrom] = useState('');
  const [validUntil, setValidUntil] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [key, setKey] = useState(createIdempotencyKey());

  useEffect(() => {
    if (!identity || constraint !== 'SPECIFIC') { setPrincipals([]); setPrincipalId(''); return; }
    const controller = new AbortController();
    listIdentityPrincipals(identity.id, controller.signal)
      .then((page) => setPrincipals(page.items.filter((item) => item.lifecycleState === 'ACTIVE')))
      .catch(() => setPrincipals([]));
    return () => controller.abort();
  }, [identity, constraint]);

  function reset() {
    setIdentity(null); setTargetKind('ROLE'); setTarget(null); setConstraint('ANY'); setPrincipals([]); setPrincipalId('');
    setValidFrom(''); setValidUntil(''); setError(null); setKey(createIdempotencyKey());
  }

  async function grant() {
    if (!identity || !target || (constraint === 'SPECIFIC' && !principalId)) return;
    setBusy(true); setError(null);
    try {
      const result = await createAssignment({
        identityId: identity.id,
        targetKind,
        roleId: targetKind === 'ROLE' ? target.id : null,
        entitlementId: targetKind === 'ENTITLEMENT' ? target.id : null,
        principalConstraintKind: constraint,
        specificPrincipalId: constraint === 'SPECIFIC' ? principalId : null,
        validFrom: validFrom ? new Date(validFrom).toISOString() : null,
        validUntil: validUntil ? new Date(validUntil).toISOString() : null,
      }, key);
      reset(); onClose(); onCreated(result.data.id);
    } catch (cause) { setError(cause); }
    finally { setBusy(false); }
  }

  const ready = Boolean(identity && target && (constraint === 'ANY' || principalId));
  return (
    <Modal open={open} title="Grant access" description="Record one manual Access-owned business authorization. Provider realization happens asynchronously." busy={busy} onClose={() => { if (!busy) { reset(); onClose(); } }}>
      <div className="access-grant-form">
        {error ? <InlineError error={error} /> : null}
        <IdentityPicker value={identity} onSelect={setIdentity} />
        <label className="access-field"><span>Target kind</span><select value={targetKind} onChange={(event) => { setTargetKind(event.target.value as AccessTargetKind); setTarget(null); }}><option value="ROLE">Role</option><option value="ENTITLEMENT">Entitlement</option></select></label>
        <TargetPicker kind={targetKind} value={target} onSelect={setTarget} />
        <label className="access-field"><span>Principal constraint</span><select value={constraint} onChange={(event) => setConstraint(event.target.value as PrincipalConstraintKind)}><option value="ANY">Any eligible principal</option><option value="SPECIFIC">Specific principal</option></select></label>
        {constraint === 'SPECIFIC' ? (
          <label className="access-field"><span>Specific Principal</span><select value={principalId} disabled={!identity} onChange={(event) => setPrincipalId(event.target.value)}><option value="">Select an active Principal…</option>{principals.map((principal) => <option key={principal.id} value={principal.id}>{principal.nativePrincipalKey}</option>)}</select><small>Principals are loaded only for the selected governed Identity.</small></label>
        ) : null}
        <div className="access-grant-times">
          <label className="access-field"><span>Valid from</span><input type="datetime-local" value={validFrom} onChange={(event) => setValidFrom(event.target.value)} /><small>Leave blank for immediate authority.</small></label>
          <label className="access-field"><span>Valid until</span><input type="datetime-local" value={validUntil} onChange={(event) => setValidUntil(event.target.value)} /><small>Leave blank for no scheduled end.</small></label>
        </div>
        <div className="access-origin-fixed"><span>Origin</span><strong>Manual grant</strong><small>Fixed by the server. Lifecycle state is not operator-selectable.</small></div>
        <StatePanel state="informational" title="Business authority, not provider fulfillment">Grant access records AccessAssignment intent. Effective access and downstream technical realization converge separately.</StatePanel>
        <div className="access-dialog-actions"><Button variant="text" disabled={busy} onClick={() => { reset(); onClose(); }}>Cancel</Button><Button variant="contained" disabled={busy || !ready} onClick={() => void grant()}>{busy ? 'Granting…' : 'Grant access'}</Button></div>
      </div>
    </Modal>
  );
}

function actionFor(value: AccessAssignmentView): AssignmentAction[] {
  if (value.lifecycleState === 'ACTIVE') return ['suspend', 'revoke'];
  if (value.lifecycleState === 'SUSPENDED') return ['resume', 'revoke'];
  if (value.lifecycleState === 'SCHEDULED' && value.validityState === 'FUTURE') return ['cancel'];
  return [];
}

function actionLabel(action: AssignmentAction): string {
  if (action === 'suspend') return 'Suspend access';
  if (action === 'resume') return 'Resume access';
  if (action === 'cancel') return 'Cancel scheduled access';
  return 'Revoke access';
}

function actionConfirmation(action: AssignmentAction): { title: string; copy: string } {
  switch (action) {
    case 'suspend': return { title: 'Suspend current access?', copy: 'This retains the assignment but removes its current business authority. Effective access will be recomputed. Downstream provider changes happen asynchronously, and provider failure will not reactivate this assignment.' };
    case 'resume': return { title: 'Resume suspended access?', copy: 'This restores this existing assignment to ACTIVE only if it remains temporally valid. Resuming increases current privilege; the server re-authorizes the operation and applies current security rules.' };
    case 'cancel': return { title: 'Cancel scheduled access?', copy: 'This permanently cancels the future assignment before activation. It cannot be resumed. No provider call occurs in this Access transaction.' };
    case 'revoke': return { title: 'Revoke access?', copy: 'This permanently removes this assignment’s business authority. Effective access will be recomputed, but an entitlement may remain effective while another assignment still supports it. Provider removal is asynchronous.' };
  }
}

function AssignmentDetail({ id, refreshToken, onChanged }: { id: string; refreshToken: number; onChanged: () => void }) {
  const [state, setState] = useState<LoadState>('loading');
  const [result, setResult] = useState<{ data: AccessAssignmentView; etag: string | null } | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [audit, setAudit] = useState<AuditRecord[]>([]);
  const [auditError, setAuditError] = useState<unknown>(null);
  const [confirm, setConfirm] = useState<AssignmentAction | null>(null);
  const [mutationBusy, setMutationBusy] = useState(false);
  const [mutationError, setMutationError] = useState<unknown>(null);
  const [mutationKey, setMutationKey] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    setState('loading'); setError(null); setMutationError(null);
    getAssignmentView(id, controller.signal)
      .then((value) => { setResult({ data: value.data, etag: value.etag }); setState('ready'); })
      .catch((cause) => { if (!(cause instanceof DOMException && cause.name === 'AbortError')) { setError(cause); setState('error'); } });
    return () => controller.abort();
  }, [id, refreshToken]);

  useEffect(() => {
    const controller = new AbortController();
    setAudit([]); setAuditError(null);
    listAssignmentAudit(id, controller.signal).then((page) => setAudit(page.items)).catch((cause) => { if (!(cause instanceof DOMException && cause.name === 'AbortError')) setAuditError(cause); });
    return () => controller.abort();
  }, [id, refreshToken]);

  async function execute(action: AssignmentAction) {
    if (!result?.etag) return;
    const key = mutationKey ?? createIdempotencyKey();
    setMutationKey(key); setMutationBusy(true); setMutationError(null);
    try {
      await mutateAssignment(id, action, result.etag, key);
      setConfirm(null); setMutationKey(null); onChanged();
    } catch (cause) {
      setMutationError(cause);
      if (cause instanceof ApiError && (cause.status === 409 || cause.status === 412)) setConfirm(null);
    } finally { setMutationBusy(false); }
  }

  if (state === 'loading') return <StatePanel state="loading" title="Loading authoritative assignment">Reading the latest Access-owned state…</StatePanel>;
  if (state === 'error' || !result) return <InlineError error={error} onRetry={onChanged} />;
  const value = result.data;
  const actions = actionFor(value);
  return (
    <aside className="access-detail" aria-labelledby="assignment-detail-title">
      <div className="access-detail-kicker">AUTHORITATIVE · ACCESS</div>
      <div className="access-detail-heading"><div><h2 id="assignment-detail-title">{value.identityLabel ?? `Identity ${shortId(value.identityId)}`} → {value.targetLabel ?? `${value.targetKind} ${shortId(value.targetId)}`}</h2><span className={stateClass(value.lifecycleState)}>{value.lifecycleState} · {lifecycleLabel(value.lifecycleState)}</span></div><button type="button" className="access-detail-close" aria-label="Close assignment detail" onClick={() => setAccessQuery((q) => q.delete('assignmentId'))}>×</button></div>
      {mutationError ? <InlineError error={mutationError} onRetry={() => { setMutationError(null); onChanged(); }} /> : null}

      <section className="access-detail-section"><h3>Authoritative intent</h3><dl className="access-detail-grid">
        <div><dt>Governed Identity</dt><dd><button className="access-inline-link" type="button" onClick={() => navigateConsole(identityDetailPath(value.identityId))}>{value.identityLabel ?? shortId(value.identityId)} ↗</button></dd></div>
        <div><dt>Target</dt><dd>{value.targetLabel ?? shortId(value.targetId)}<small>{value.targetKind}</small></dd></div>
        <div><dt>Principal constraint</dt><dd>{value.principalConstraintKind === 'ANY' ? 'Any eligible principal' : value.specificPrincipalLabel ?? shortId(value.specificPrincipalId)}</dd></div>
        <div><dt>Origin</dt><dd>{provenanceLabel(value.provenanceKind)}{value.provenanceRefId ? <small>{shortId(value.provenanceRefId)}</small> : null}</dd></div>
        <div><dt>Lifecycle</dt><dd><span className={stateClass(value.lifecycleState)}>{value.lifecycleState}</span><small>{lifecycleLabel(value.lifecycleState)}</small></dd></div>
        <div><dt>Revision</dt><dd>{value.revision}</dd></div>
      </dl></section>

      <section className="access-detail-section"><h3>Temporal validity</h3><div className="access-validity-card"><span className={stateClass(value.validityState)}>{value.validityState === 'CURRENT' ? 'VALID NOW' : value.validityState}</span><strong>{validityLabel(value)}</strong><dl><div><dt>Valid from</dt><dd>{value.validFrom ? fmt(value.validFrom) : 'Immediately'}</dd></div><div><dt>Valid until</dt><dd>{value.validUntil ? fmt(value.validUntil) : 'No end date'}</dd></div><div><dt>Evaluated</dt><dd>{fmt(value.evaluatedAt)}</dd></div></dl></div></section>

      <section className="access-detail-section"><h3>Lifecycle action</h3>{actions.length ? <div className="access-action-row">{actions.map((action) => <Button key={action} variant={action === 'revoke' || action === 'cancel' ? 'contained' : 'outlined'} color={action === 'revoke' || action === 'cancel' ? 'error' : undefined} disabled={mutationBusy} onClick={() => { setMutationKey(createIdempotencyKey()); setConfirm(action); }}>{actionLabel(action)}</Button>)}</div> : <StatePanel state="informational" title="No lifecycle action available">This assignment is terminal or no longer in a state with an Access lifecycle operation.</StatePanel>}</section>

      <StatePanel state="informational" title="Technical realization is separate">Assignment state is business authority. Provisioning tasks, provider attempts and provider observations are Integration-owned and do not rewrite this lifecycle.</StatePanel>

      <section className="access-detail-section"><div className="access-section-heading"><h3>Recent Access evidence</h3><button type="button" className="access-inline-link" onClick={() => navigateConsole(`/audit?resourceType=access-assignment&resourceId=${encodeURIComponent(value.id)}`)}>View in Audit ↗</button></div>{auditError ? <StatePanel state={auditError instanceof ApiError && auditError.status === 403 ? 'warning' : 'error'} title={auditError instanceof ApiError && auditError.status === 403 ? 'Evidence not authorized' : 'Evidence unavailable'}>Assignment authority remains available independently of Audit evidence.</StatePanel> : audit.length ? <div className="access-evidence-list">{audit.map((item) => <div key={item.id}><strong>{item.actionType}</strong><span className={stateClass(item.outcome)}>{item.outcome}</span><time dateTime={item.occurredAt}>{fmt(item.occurredAt)}</time></div>)}</div> : <StatePanel state="empty" title="No recent evidence">No AuditRecord is visible for this assignment in the bounded preview.</StatePanel>}</section>

      <details className="access-technical"><summary>Technical identifiers and revision metadata</summary><dl><div><dt>Assignment ID</dt><dd>{value.id}</dd></div><div><dt>Identity ID</dt><dd>{value.identityId}</dd></div><div><dt>Target ID</dt><dd>{value.targetId}</dd></div>{value.specificPrincipalId ? <div><dt>Principal ID</dt><dd>{value.specificPrincipalId}</dd></div> : null}{value.provenanceRefId ? <div><dt>Provenance reference</dt><dd>{value.provenanceRefId}</dd></div> : null}</dl></details>

      <ConfirmDialog open={confirm !== null} title={confirm ? actionConfirmation(confirm).title : ''} confirmLabel={confirm ? actionLabel(confirm) : 'Confirm'} busy={mutationBusy} destructive={confirm === 'revoke' || confirm === 'cancel'} onCancel={() => { if (!mutationBusy) { setConfirm(null); setMutationKey(null); } }} onConfirm={() => { if (confirm) void execute(confirm); }}>{confirm ? actionConfirmation(confirm).copy : ''}</ConfirmDialog>
    </aside>
  );
}

function AssignmentsView({ onGrant }: { onGrant: () => void }) {
  const { search } = useConsoleLocation();
  const query = readQuery(search);
  const selectedId = query.get('assignmentId');
  const filters = useMemo<AssignmentFilters>(() => ({
    identityId: query.get('identityId') ?? undefined,
    lifecycleState: (query.get('lifecycleState') as AssignmentLifecycle | null) ?? undefined,
    targetKind: (query.get('targetKind') as AccessTargetKind | null) ?? undefined,
    targetId: query.get('targetId') ?? undefined,
    provenance: (query.get('provenance') as AssignmentProvenance | null) ?? undefined,
    validity: (query.get('validity') as AssignmentValidity | null) ?? undefined,
  }), [search]);
  const [items, setItems] = useState<AccessAssignmentView[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [cursor, setCursor] = useState<string | null>(null);
  const [cursorHistory, setCursorHistory] = useState<Array<string | null>>([]);
  const [state, setState] = useState<LoadState>('loading');
  const [error, setError] = useState<unknown>(null);
  const [refresh, setRefresh] = useState(0);

  useEffect(() => { setCursor(null); setCursorHistory([]); }, [filters.identityId, filters.lifecycleState, filters.targetKind, filters.targetId, filters.provenance, filters.validity]);
  useEffect(() => {
    const controller = new AbortController(); setState('loading'); setError(null);
    listAssignmentViews(filters, cursor, controller.signal)
      .then((page) => { setItems(page.items); setNextCursor(page.nextCursor ?? null); setState('ready'); })
      .catch((cause) => { if (!(cause instanceof DOMException && cause.name === 'AbortError')) { setError(cause); setState('error'); } });
    return () => controller.abort();
  }, [filters, cursor, refresh]);

  function updateFilter(name: string, value: string) {
    setAccessQuery((params) => { if (value) params.set(name, value); else params.delete(name); params.delete('assignmentId'); });
  }
  const activeFilterCount = Object.values(filters).filter(Boolean).length;

  return (
    <div className={`access-assignment-layout${selectedId ? ' has-selection' : ''}`}>
      <section className="access-collection" aria-labelledby="assignment-collection-title">
        <div className="access-filter-toolbar">
          <div className="access-filter-context">{filters.identityId ? <span>Identity · {shortId(filters.identityId)} <button type="button" aria-label="Clear Identity filter" onClick={() => updateFilter('identityId', '')}>×</button></span> : <span className="access-filter-muted">All governed subjects</span>}</div>
          <label><span>Lifecycle</span><select value={filters.lifecycleState ?? ''} onChange={(e) => updateFilter('lifecycleState', e.target.value)}><option value="">All</option>{LIFECYCLES.map((v) => <option key={v}>{v}</option>)}</select></label>
          <label><span>Target kind</span><select value={filters.targetKind ?? ''} onChange={(e) => { updateFilter('targetKind', e.target.value); if (!e.target.value) updateFilter('targetId', ''); }}><option value="">All</option><option value="ROLE">Role</option><option value="ENTITLEMENT">Entitlement</option></select></label>
          <label><span>Validity</span><select value={filters.validity ?? ''} onChange={(e) => updateFilter('validity', e.target.value)}><option value="">All</option>{VALIDITIES.map((v) => <option key={v}>{v}</option>)}</select></label>
          <label><span>Origin</span><select value={filters.provenance ?? ''} onChange={(e) => updateFilter('provenance', e.target.value)}><option value="">All</option>{PROVENANCES.map((v) => <option key={v} value={v}>{provenanceLabel(v)}</option>)}</select></label>
          {activeFilterCount ? <button className="access-clear-filters" type="button" onClick={() => setAccessQuery((params) => ['identityId','lifecycleState','targetKind','targetId','provenance','validity','assignmentId'].forEach((name) => params.delete(name)))}>Clear filters ({activeFilterCount})</button> : null}
        </div>
        <div className="access-collection-heading"><div><span className="access-detail-kicker">AUTHORITATIVE · ACCESS</span><h2 id="assignment-collection-title">Authoritative assignments</h2><p>Business access intent. Lifecycle and temporal validity are separate; provider fulfillment is not assignment state.</p></div><Button variant="contained" onClick={onGrant}>＋ Grant access</Button></div>

        {state === 'loading' ? <div className="access-state-wrap"><StatePanel state="loading" title="Loading assignments">Reading a bounded server page…</StatePanel></div> : null}
        {state === 'error' ? <div className="access-state-wrap"><InlineError error={error} onRetry={() => setRefresh((v) => v + 1)} /></div> : null}
        {state === 'ready' && items.length === 0 ? <div className="access-state-wrap"><StatePanel state="empty" title="No assignments">No AccessAssignment matches the current server-side filters.</StatePanel></div> : null}
        {items.length ? (
          <>
            <div className="access-table-wrap"><table className="access-table"><caption className="sr-only">Authoritative AccessAssignments</caption><thead><tr><th>Governed subject</th><th>Target</th><th>Principal constraint</th><th>Lifecycle</th><th>Validity</th><th>Origin</th><th>Updated</th><th><span className="sr-only">Review</span></th></tr></thead><tbody>{items.map((item) => <tr key={item.id} className={selectedId === item.id ? 'is-selected' : ''}><td><strong>{item.identityLabel ?? `Identity ${shortId(item.identityId)}`}</strong><small>{shortId(item.identityId)}</small></td><td><strong>{item.targetLabel ?? shortId(item.targetId)}</strong><small>{item.targetKind}</small></td><td>{item.principalConstraintKind === 'ANY' ? 'Any principal' : item.specificPrincipalLabel ?? shortId(item.specificPrincipalId)}</td><td><span className={stateClass(item.lifecycleState)}>{item.lifecycleState}</span><small>{lifecycleLabel(item.lifecycleState)}</small></td><td><span className={stateClass(item.validityState)}>{item.validityState}</span><small>{validityLabel(item)}</small></td><td>{provenanceLabel(item.provenanceKind)}</td><td>{compact(item.updatedAt)}</td><td><button type="button" className="access-review-button" onClick={() => setAccessQuery((q) => q.set('assignmentId', item.id))}>{selectedId === item.id ? 'Selected' : 'Review'} ›</button></td></tr>)}</tbody></table></div>
            <div className="access-mobile-list">{items.map((item) => <button type="button" key={item.id} className={`access-mobile-card${selectedId === item.id ? ' is-selected' : ''}`} onClick={() => setAccessQuery((q) => q.set('assignmentId', item.id))}><strong>{item.identityLabel ?? `Identity ${shortId(item.identityId)}`}</strong><b>{item.targetLabel ?? shortId(item.targetId)}</b><span>{item.targetKind} · {item.principalConstraintKind === 'ANY' ? 'Any principal' : 'Specific principal'}</span><dl><div><dt>Lifecycle</dt><dd><span className={stateClass(item.lifecycleState)}>{item.lifecycleState}</span></dd></div><div><dt>Validity</dt><dd>{validityLabel(item)}</dd></div><div><dt>Origin</dt><dd>{provenanceLabel(item.provenanceKind)}</dd></div><div><dt>Updated</dt><dd>{compact(item.updatedAt)}</dd></div></dl><i aria-hidden="true">›</i></button>)}</div>
            <footer className="access-pagination"><Button variant="outlined" disabled={!cursorHistory.length || state === 'loading'} onClick={() => { const history = [...cursorHistory]; const previous = history.pop() ?? null; setCursorHistory(history); setCursor(previous); }}>Previous</Button><span>Bounded server page · {items.length} visible</span><Button variant="outlined" disabled={!nextCursor || state === 'loading'} onClick={() => { setCursorHistory((h) => [...h, cursor]); setCursor(nextCursor); }}>Next page</Button></footer>
          </>
        ) : null}
      </section>
      {selectedId ? <AssignmentDetail id={selectedId} refreshToken={refresh} onChanged={() => setRefresh((v) => v + 1)} /> : <aside className="access-detail-placeholder"><span className="access-detail-kicker">INVESTIGATE</span><h2>Select an assignment</h2><p>Review authoritative intent, temporal validity, provenance and the lifecycle action that is safe now.</p></aside>}
    </div>
  );
}

function EffectiveView() {
  const { search } = useConsoleLocation();
  const query = readQuery(search);
  const identityId = query.get('identityId') ?? undefined;
  const selectedId = query.get('effectiveAccessId');
  const [items, setItems] = useState<EffectiveAccessSummary[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [cursor, setCursor] = useState<string | null>(null);
  const [history, setHistory] = useState<Array<string | null>>([]);
  const [state, setState] = useState<LoadState>('loading');
  const [error, setError] = useState<unknown>(null);
  const [detail, setDetail] = useState<EffectiveAccessResource | null>(null);
  const [detailError, setDetailError] = useState<unknown>(null);

  useEffect(() => { setCursor(null); setHistory([]); }, [identityId]);
  useEffect(() => {
    const controller = new AbortController(); setState('loading'); setError(null);
    listEffectiveAccess(identityId, cursor, controller.signal)
      .then((page) => { setItems(page.items); setNextCursor(page.nextCursor ?? null); setState('ready'); })
      .catch((cause) => { if (!(cause instanceof DOMException && cause.name === 'AbortError')) { setError(cause); setState('error'); } });
    return () => controller.abort();
  }, [identityId, cursor]);
  useEffect(() => {
    if (!selectedId) { setDetail(null); setDetailError(null); return; }
    const controller = new AbortController(); setDetail(null); setDetailError(null);
    getEffectiveAccess(selectedId, controller.signal).then((value) => setDetail(value.data)).catch((cause) => { if (!(cause instanceof DOMException && cause.name === 'AbortError')) setDetailError(cause); });
    return () => controller.abort();
  }, [selectedId]);

  return (
    <div className={`access-assignment-layout${selectedId ? ' has-selection' : ''}`}>
      <section className="access-collection" aria-labelledby="effective-title">
        <div className="access-collection-heading"><div><span className="access-detail-kicker">DERIVED · READ ONLY</span><h2 id="effective-title">Effective access</h2><p>Entitlement-level projection supported by current authoritative assignments. Change supporting intent, never this projection.</p></div></div>
        <div className="access-effective-filter">{identityId ? <span>Governed Identity · {shortId(identityId)} <button type="button" onClick={() => setAccessQuery((q) => { q.delete('identityId'); q.delete('effectiveAccessId'); })}>×</button></span> : <StatePanel state="informational" title="Tenant-scoped projection view">Use Identity 360 or an Identity filter to narrow this potentially large projection.</StatePanel>}</div>
        {state === 'loading' ? <div className="access-state-wrap"><StatePanel state="loading" title="Loading EffectiveAccess">Reading current semantic projection rows…</StatePanel></div> : null}
        {state === 'error' ? <div className="access-state-wrap"><InlineError error={error} /></div> : null}
        {state === 'ready' && !items.length ? <div className="access-state-wrap"><StatePanel state="empty" title="No effective access">No current EffectiveAccess is visible for this context.</StatePanel></div> : null}
        {items.length ? <><div className="access-table-wrap"><table className="access-table"><caption className="sr-only">Effective access projection</caption><thead><tr><th>Governed Identity</th><th>Entitlement</th><th>Principal constraint</th><th>Supports</th><th>Computed</th><th></th></tr></thead><tbody>{items.map((item) => <tr key={item.id} className={selectedId === item.id ? 'is-selected' : ''}><td>{shortId(item.identityId)}</td><td><strong>{shortId(item.entitlementId)}</strong></td><td>{item.principalConstraintKey}</td><td>{item.supportCount}</td><td>{fmt(item.computedAt)}</td><td><button type="button" className="access-review-button" onClick={() => setAccessQuery((q) => q.set('effectiveAccessId', item.id))}>Explain ›</button></td></tr>)}</tbody></table></div><footer className="access-pagination"><Button variant="outlined" disabled={!history.length} onClick={() => { const copy = [...history]; setCursor(copy.pop() ?? null); setHistory(copy); }}>Previous</Button><span>Read-only projection · {items.length} visible</span><Button variant="outlined" disabled={!nextCursor} onClick={() => { setHistory((h) => [...h, cursor]); setCursor(nextCursor); }}>Next page</Button></footer></> : null}
      </section>
      {selectedId ? <aside className="access-detail" aria-labelledby="effective-detail-title"><div className="access-detail-kicker">DERIVED · ACCESS</div><div className="access-detail-heading"><div><h2 id="effective-detail-title">Why is this entitlement effective?</h2><span className="access-state access-state-current">READ ONLY</span></div><button type="button" className="access-detail-close" aria-label="Close EffectiveAccess detail" onClick={() => setAccessQuery((q) => q.delete('effectiveAccessId'))}>×</button></div>{detailError ? <InlineError error={detailError} /> : !detail ? <StatePanel state="loading" title="Loading explanation">Reading current support evidence…</StatePanel> : <><section className="access-detail-section"><h3>Projection context</h3><dl className="access-detail-grid"><div><dt>Identity</dt><dd><button className="access-inline-link" type="button" onClick={() => navigateConsole(identityDetailPath(detail.identityId))}>{shortId(detail.identityId)} ↗</button></dd></div><div><dt>Entitlement</dt><dd>{shortId(detail.entitlementId)}</dd></div><div><dt>Principal constraint</dt><dd>{detail.principalConstraintKey}</dd></div><div><dt>Current supports</dt><dd>{detail.supportCount}</dd></div><div><dt>Computed</dt><dd>{fmt(detail.computedAt)}</dd></div><div><dt>Projection generation</dt><dd>{detail.projectionGeneration}</dd></div></dl></section><section className="access-detail-section"><h3>Why this entitlement is effective</h3><p className="access-explain-copy">{detail.supports.length} current support{detail.supports.length === 1 ? '' : 's'} contribute to this projection. Removing one assignment does not necessarily remove effective entitlement access while another valid support remains.</p><div className="access-support-list">{detail.supports.map((support, index) => <article key={`${support.accessAssignmentId}-${support.pathHash}`}><div><span>SUPPORT {index + 1}</span><strong>{support.pathDepth === 0 ? 'Direct entitlement assignment' : 'Role-derived access'}</strong></div><p>{support.pathDepth === 0 ? 'Direct path · depth 0' : `Ordered RoleVersion path · depth ${support.pathDepth}`}</p>{support.roleVersionPath.length ? <ol>{support.roleVersionPath.map((roleVersionId) => <li key={roleVersionId}>{shortId(roleVersionId)}</li>)}</ol> : null}<Button variant="outlined" size="small" onClick={() => setAccessQuery((q) => { q.set('view', 'assignments'); q.set('assignmentId', support.accessAssignmentId); q.delete('effectiveAccessId'); })}>Open supporting assignment</Button><details><summary>Deterministic path reference</summary><code>{support.pathHash}</code></details></article>)}</div></section></>}</aside> : <aside className="access-detail-placeholder"><span className="access-detail-kicker">EXPLAIN</span><h2>Select EffectiveAccess</h2><p>Inspect current support count and the authoritative AccessAssignments that explain this derived entitlement access.</p></aside>}
    </div>
  );
}

function expectedValue(rule: LifecycleAccessPolicyRule): string {
  if (rule.predicateKind === 'ALWAYS') return 'Everyone';
  if (rule.expectedSet?.length) return rule.expectedSet.map((value) => String(value.stringValue ?? value.booleanValue ?? value.integerValue ?? value.decimalValue ?? value.dateValue ?? value.dateTimeValue ?? value.enumValue ?? '')).join(', ');
  return String(rule.expectedString ?? rule.expectedBoolean ?? rule.expectedInteger ?? rule.expectedDecimal ?? rule.expectedDate ?? rule.expectedDateTime ?? rule.expectedEnum ?? '');
}

function humanPredicate(rule: LifecycleAccessPolicyRule): string {
  if (rule.predicateKind === 'ALWAYS') return 'Always';
  const operation = rule.predicateKind.includes('CONTAINS_ANY') ? 'contains any of' : rule.predicateKind.includes('CONTAINS_ALL') ? 'contains all of' : rule.predicateKind.includes('CONTAINS') ? 'contains' : 'equals';
  return `${rule.canonicalKey ?? 'Canonical attribute'} ${operation} ${expectedValue(rule)}`;
}

function PolicyView() {
  const [state, setState] = useState<LoadState>('loading');
  const [policy, setPolicy] = useState<LifecycleAccessPolicy | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);
  const [activateError, setActivateError] = useState<unknown>(null);
  const [key, setKey] = useState(createIdempotencyKey());
  const [refresh, setRefresh] = useState(0);

  useEffect(() => {
    const controller = new AbortController(); setState('loading'); setError(null);
    getLifecycleAccessPolicy(controller.signal).then((value) => { setPolicy(value); setState('ready'); }).catch((cause) => { if (!(cause instanceof DOMException && cause.name === 'AbortError')) { setError(cause); setState('error'); } });
    return () => controller.abort();
  }, [refresh]);

  async function activateSameRules() {
    if (!policy) return;
    setBusy(true); setActivateError(null);
    try { await activateLifecycleAccessPolicy(policy.rules, key); setConfirm(false); setKey(createIdempotencyKey()); setRefresh((v) => v + 1); }
    catch (cause) { setActivateError(cause); }
    finally { setBusy(false); }
  }

  return (
    <section className="access-policy" aria-labelledby="policy-title"><div className="access-collection-heading"><div><span className="access-detail-kicker">AUTHORITATIVE POLICY · EXPERT</span><h2 id="policy-title">Lifecycle access policy</h2><p>Access-owned birthright/lifecycle policy. Versions are immutable and predicates remain closed and typed.</p></div>{policy ? <Button variant="outlined" onClick={() => { setKey(createIdempotencyKey()); setConfirm(true); }}>Activate successor version</Button> : null}</div>
      {state === 'loading' ? <StatePanel state="loading" title="Loading lifecycle policy">Reading the current active immutable version…</StatePanel> : null}
      {state === 'error' ? error instanceof ApiError && error.status === 404 ? <StatePanel state="empty" title="No active lifecycle policy">No active Access lifecycle policy is configured.</StatePanel> : <InlineError error={error} onRetry={() => setRefresh((v) => v + 1)} /> : null}
      {activateError ? <InlineError error={activateError} /> : null}
      {policy ? <><div className="access-policy-meta"><div><span>Current active version</span><strong>{policy.versionNumber}</strong></div><div><span>State</span><strong className={stateClass(policy.state)}>{policy.state}</strong></div><div><span>Activated</span><strong>{fmt(policy.activatedAt)}</strong></div><div><span>Rules</span><strong>{policy.rules.length} of 100</strong></div></div><StatePanel state="informational" title="Closed typed rule model">Rules support accepted exact typed equality and bounded MULTI membership only. There is no arbitrary JSON, expression text, generic AND/OR/NOT, unsupported relationship predicates, count/order semantics, or browser-side Governance decision.</StatePanel><div className="access-table-wrap"><table className="access-table access-policy-table"><caption className="sr-only">Lifecycle access policy rules</caption><thead><tr><th>Rule</th><th>When</th><th>Grant</th><th>Target kind</th><th>Principal</th></tr></thead><tbody>{policy.rules.map((rule) => <tr key={rule.ruleId}><td><strong>{shortId(rule.ruleId)}</strong></td><td>{humanPredicate(rule)}</td><td>{shortId(rule.targetId)}</td><td>{rule.targetKind}</td><td>Any principal</td></tr>)}</tbody></table></div><StatePanel state="warning" title="Successor activation semantics">Activation creates immutable version {policy.versionNumber + 1} and supersedes version {policy.versionNumber}. Policy-owned reductions happen before privilege increases. Privilege increases remain subject to current server-side Governance evaluation. Activation does not perform a tenant-wide retroactive sweep.</StatePanel></> : null}
      <ConfirmDialog open={confirm} title={policy ? `Activate lifecycle policy version ${policy.versionNumber + 1}?` : 'Activate successor policy?'} confirmLabel={policy ? `Activate version ${policy.versionNumber + 1}` : 'Activate'} busy={busy} onCancel={() => { if (!busy) setConfirm(false); }} onConfirm={() => void activateSameRules()}>This creates an immutable successor using the currently visible typed rules. The prior active version is superseded, not edited in place. This action does not perform a tenant-wide retroactive sweep.</ConfirmDialog>
    </section>
  );
}

export function AccessWorkspace() {
  const { search } = useConsoleLocation();
  const view = currentView(search);
  const [grantOpen, setGrantOpen] = useState(false);
  return (
    <section className="access-golden-page" aria-labelledby="access-page-title">
      <header className="access-golden-header"><div><span className="access-golden-eyebrow">ACCESS · AUTHORITY & PROJECTION</span><h1 id="access-page-title">Access</h1><p>Investigate authoritative access intent, derived effective entitlement access, and the lifecycle action that is safe now.</p></div><Button variant="contained" onClick={() => setGrantOpen(true)}>＋ Grant access</Button></header>
      <nav className="access-golden-tabs" role="tablist" aria-label="Access workspace views"><TabButton id="assignments" label="Assignments" hint="Authoritative" selected={view === 'assignments'} /><TabButton id="effective" label="Effective access" hint="Read only" selected={view === 'effective'} /><TabButton id="policy" label="Lifecycle policy" hint="Expert" selected={view === 'policy'} /></nav>
      <div role="tabpanel" className="access-golden-panel">{view === 'assignments' ? <AssignmentsView onGrant={() => setGrantOpen(true)} /> : view === 'effective' ? <EffectiveView /> : <PolicyView />}</div>
      <GrantAccessDialog open={grantOpen} onClose={() => setGrantOpen(false)} onCreated={(id) => setAccessQuery((query) => { query.set('view', 'assignments'); query.set('assignmentId', id); })} />
    </section>
  );
}
