import { type ReactNode, useCallback, useEffect, useMemo, useState } from 'react';
import { ApiError, getCurrentAdministrativeAuthority, type ApiResult, type CurrentAdministrativeAuthority } from '../api';
import { CONSOLE_NAVIGATION_EVENT, identityDetailPath, navigateConsole } from '../consoleNavigation';
import {
  activateLifecyclePolicy,
  createAssignment,
  getAssignment,
  getEffectiveSummary,
  getLifecyclePolicy,
  identityOptions,
  listAssignmentAudit,
  listAssignments,
  listEffective,
  listEffectiveSupports,
  mutateAssignment,
  newCausalKey,
  principalOptions,
  targetOptions,
} from '../access/accessApi';
import type {
  AccessView,
  AssignmentFilters,
  AssignmentLifecycle,
  AssignmentPage,
  AssignmentProvenance,
  AssignmentResource,
  AssignmentValidity,
  AuditRecord,
  EffectiveResource,
  EffectiveSupport,
  LifecycleExpectedValue,
  LifecyclePolicyResource,
  LifecyclePolicyRule,
  OptionResource,
  PrincipalConstraintKind,
  TargetKind,
} from '../access/accessTypes';
import {
  Box,
  Button,
  ConfirmDialog,
  DataTable,
  Page,
  PageHeader,
  PageToolbar,
  SelectInput,
  SidePanel,
  StatePanel,
  StatusChip,
  Surface,
  Tab,
  TabPanel,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Tabs,
  TextInput,
  Typography,
  getTabA11yProps,
} from '../ui';
import '../access-golden.css';

const GROUP = 'access-golden';
const PREDICATES = [
  'ALWAYS',
  'CANONICAL_STRING_EQUALS', 'CANONICAL_BOOLEAN_EQUALS', 'CANONICAL_INTEGER_EQUALS',
  'CANONICAL_DECIMAL_EQUALS', 'CANONICAL_DATE_EQUALS', 'CANONICAL_DATETIME_EQUALS', 'CANONICAL_ENUM_EQUALS',
  'CANONICAL_STRING_CONTAINS', 'CANONICAL_BOOLEAN_CONTAINS', 'CANONICAL_INTEGER_CONTAINS',
  'CANONICAL_DECIMAL_CONTAINS', 'CANONICAL_DATE_CONTAINS', 'CANONICAL_DATETIME_CONTAINS', 'CANONICAL_ENUM_CONTAINS',
  'CANONICAL_STRING_CONTAINS_ANY', 'CANONICAL_BOOLEAN_CONTAINS_ANY', 'CANONICAL_INTEGER_CONTAINS_ANY',
  'CANONICAL_DECIMAL_CONTAINS_ANY', 'CANONICAL_DATE_CONTAINS_ANY', 'CANONICAL_DATETIME_CONTAINS_ANY', 'CANONICAL_ENUM_CONTAINS_ANY',
  'CANONICAL_STRING_CONTAINS_ALL', 'CANONICAL_BOOLEAN_CONTAINS_ALL', 'CANONICAL_INTEGER_CONTAINS_ALL',
  'CANONICAL_DECIMAL_CONTAINS_ALL', 'CANONICAL_DATE_CONTAINS_ALL', 'CANONICAL_DATETIME_CONTAINS_ALL', 'CANONICAL_ENUM_CONTAINS_ALL',
] as const;

function shortId(value?: string | null) {
  return value ? `${value.slice(0, 8)}…` : '—';
}
function humanEnum(value?: string | null) {
  if (!value) return '—';
  return value.toLowerCase().replaceAll('_', ' ').replace(/^./, (letter) => letter.toUpperCase());
}
function dateTime(value?: string | null) {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat(undefined, {
    year: 'numeric', month: 'short', day: '2-digit', hour: '2-digit', minute: '2-digit',
  }).format(date);
}
function dateOnly(value?: string | null) {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat(undefined, {
    year: 'numeric', month: 'short', day: '2-digit',
  }).format(date);
}
function lifecycleColor(state: AssignmentLifecycle): 'default' | 'success' | 'warning' | 'error' | 'info' {
  if (state === 'ACTIVE') return 'success';
  if (state === 'SCHEDULED') return 'info';
  if (state === 'SUSPENDED') return 'warning';
  if (state === 'REVOKED' || state === 'CANCELLED') return 'error';
  return 'default';
}
function lifecycleLabel(state: AssignmentLifecycle) {
  return ({
    SCHEDULED: 'Scheduled intent', ACTIVE: 'Active intent', SUSPENDED: 'Suspended intent',
    REVOKED: 'Revoked', EXPIRED: 'Expired', CANCELLED: 'Cancelled',
  } as const)[state];
}
function provenanceLabel(value: AssignmentProvenance) {
  if (value === 'MANUAL') return 'Manual grant';
  if (value === 'REQUEST_ITEM') return 'Request item';
  return 'Lifecycle policy rule';
}
function validityLabel(item: Pick<AssignmentResource, 'validity' | 'validFrom' | 'validUntil'>) {
  if (item.validity === 'FUTURE') return `Starts ${dateOnly(item.validFrom)}`;
  if (item.validity === 'ENDED') return `Ended ${dateOnly(item.validUntil)}`;
  return item.validUntil ? `Valid now · ends ${dateOnly(item.validUntil)}` : 'Valid now · no end date';
}
function primaryLabel(label: string | null | undefined, id: string) {
  return label?.trim() || `ID ${shortId(id)}`;
}

function errorPresentation(error: unknown): { state: 'error' | 'forbidden' | 'not-found' | 'conflict' | 'stale'; title: string; detail: string } {
  if (error instanceof ApiError) {
    if (error.status === 403) return { state: 'forbidden', title: 'Not authorized', detail: 'Administrative Authorization denied this surface or operation. Server authorization is final.' };
    if (error.status === 404) return { state: 'not-found', title: 'Not found', detail: 'The requested Access resource is no longer available in this tenant.' };
    if (error.status === 412) return { state: 'stale', title: 'Resource changed', detail: 'Refresh and review the latest authoritative state before taking another action.' };
    if (error.status === 409) {
      const semantic: Record<string, string> = {
        access_assignment_not_active: 'This assignment is no longer active.',
        access_assignment_not_suspended: 'This assignment can no longer be resumed.',
        access_assignment_expired: 'Temporal validity has already ended.',
        access_assignment_not_cancellable: 'This scheduled assignment can no longer be cancelled.',
        access_assignment_not_revocable: 'This assignment can no longer be revoked.',
      };
      return { state: 'conflict', title: 'Authoritative state changed', detail: (error.code && semantic[error.code]) || error.message };
    }
    return { state: 'error', title: error.status === 503 ? 'Temporarily unavailable' : 'Request failed', detail: error.message };
  }
  return { state: 'error', title: 'Request failed', detail: error instanceof Error ? error.message : 'Unable to complete the request.' };
}
function ErrorState({ error, action }: { error: unknown; action?: ReactNode }) {
  const view = errorPresentation(error);
  return <StatePanel state={view.state} title={view.title} action={action}>{view.detail}</StatePanel>;
}

function useLocationVersion() {
  const [version, setVersion] = useState(0);
  useEffect(() => {
    const update = () => setVersion((value) => value + 1);
    window.addEventListener('popstate', update);
    window.addEventListener(CONSOLE_NAVIGATION_EVENT, update);
    return () => {
      window.removeEventListener('popstate', update);
      window.removeEventListener(CONSOLE_NAVIGATION_EVENT, update);
    };
  }, []);
  return version;
}
function accessUrl(update: Record<string, string | null | undefined>, replace = false) {
  const url = new URL(window.location.href);
  for (const [key, value] of Object.entries(update)) {
    if (!value) url.searchParams.delete(key); else url.searchParams.set(key, value);
  }
  const next = `${url.pathname}${url.searchParams.size ? `?${url.searchParams.toString()}` : ''}`;
  if (replace) window.history.replaceState({}, '', next); else window.history.pushState({}, '', next);
  window.dispatchEvent(new Event(CONSOLE_NAVIGATION_EVENT));
  window.scrollTo({ top: 0, behavior: 'auto' });
}
function authorityHas(authority: CurrentAdministrativeAuthority | null, resourceType: string, action: string) {
  if (!authority) return false;
  return authority.authorities.some((item) => item.permission.resourceType === resourceType && item.permission.action === action);
}
function filterFromUrl(): AssignmentFilters {
  const params = new URLSearchParams(window.location.search);
  return {
    identityId: params.get('identityId') || undefined,
    lifecycleState: (params.get('lifecycleState') as AssignmentLifecycle | null) || undefined,
    targetKind: (params.get('targetKind') as TargetKind | null) || undefined,
    targetId: params.get('targetId') || undefined,
    provenance: (params.get('provenance') as AssignmentProvenance | null) || undefined,
    validity: (params.get('validity') as AssignmentValidity | null) || undefined,
  };
}
function Field({ label, children }: { label: ReactNode; children: ReactNode }) {
  return <div className="access-detail-field"><dt>{label}</dt><dd>{children}</dd></div>;
}

function OptionPicker({ label, selected, onSelect, load, placeholder, disabled = false }: {
  label: string;
  selected: OptionResource | null;
  onSelect: (option: OptionResource | null) => void;
  load: (query: string, signal?: AbortSignal) => Promise<ApiResult<{ items: OptionResource[] }>>;
  placeholder: string;
  disabled?: boolean;
}) {
  const [query, setQuery] = useState('');
  const [items, setItems] = useState<OptionResource[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<unknown>(null);
  useEffect(() => {
    if (disabled || selected) return;
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      setLoading(true); setError(null);
      load(query, controller.signal)
        .then(({ data }) => setItems(data.items))
        .catch((cause) => { if (!(cause instanceof DOMException && cause.name === 'AbortError')) setError(cause); })
        .finally(() => setLoading(false));
    }, 220);
    return () => { window.clearTimeout(timer); controller.abort(); };
  }, [disabled, load, query, selected]);
  return <Box sx={{ minWidth: 0 }}>
    <TextInput label={label} value={selected ? selected.label : query} disabled={disabled} placeholder={placeholder} onChange={(event) => { onSelect(null); setQuery(event.target.value); }} />
    {!selected && !disabled ? <div className="access-option-list" role="listbox" aria-label={`${label} results`}>
      {loading ? <Typography variant="caption" color="text.secondary">Searching…</Typography> : null}
      {error instanceof ApiError && error.status === 403 ? <Typography variant="caption" color="text.secondary">Not authorized to search.</Typography> : null}
      {!loading && !error && items.map((item) => <button key={item.id} type="button" className="access-option" role="option" aria-selected="false" onClick={() => onSelect(item)}><strong>{item.label}</strong><small>{[item.secondary, item.state].filter(Boolean).join(' · ')}</small></button>)}
    </div> : null}
    {selected ? <Button size="small" variant="text" onClick={() => { onSelect(null); setQuery(''); }}>Change</Button> : null}
  </Box>;
}

function AssignmentDetail({ selected, authority, refreshToken, onChanged }: {
  selected: string | null;
  authority: CurrentAdministrativeAuthority | null;
  refreshToken: number;
  onChanged: (message?: string) => void;
}) {
  const [detail, setDetail] = useState<AssignmentResource | null>(null);
  const [etag, setEtag] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [reload, setReload] = useState(0);
  const [audit, setAudit] = useState<AuditRecord[]>([]);
  const [auditState, setAuditState] = useState<'loading' | 'ready' | 'forbidden' | 'unavailable'>('loading');
  const [confirm, setConfirm] = useState<null | { action: 'suspend' | 'resume' | 'cancel' | 'revoke'; title: string; copy: string; label: string; destructive: boolean; key: string }>(null);
  const [mutationError, setMutationError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!selected) { setDetail(null); setEtag(null); return; }
    const controller = new AbortController(); setLoading(true); setError(null);
    getAssignment(selected, controller.signal)
      .then((result) => { setDetail(result.data); setEtag(result.etag); })
      .catch((cause) => { if (!(cause instanceof DOMException && cause.name === 'AbortError')) { setError(cause); setDetail(null); } })
      .finally(() => setLoading(false));
    return () => controller.abort();
  }, [selected, refreshToken, reload]);
  useEffect(() => {
    if (!selected) return;
    const controller = new AbortController(); setAuditState('loading');
    listAssignmentAudit(selected, controller.signal)
      .then(({ data }) => { setAudit(data.items); setAuditState('ready'); })
      .catch((cause) => setAuditState(cause instanceof ApiError && cause.status === 403 ? 'forbidden' : 'unavailable'));
    return () => controller.abort();
  }, [selected, refreshToken]);

  if (!selected) return <Surface className="access-detail" title="Assignment detail" description="Select an authoritative assignment to investigate its meaning and safe next action."><StatePanel state="empty">No assignment selected.</StatePanel></Surface>;
  if (loading) return <Surface className="access-detail" title="Assignment detail"><StatePanel state="loading">Loading authoritative assignment…</StatePanel></Surface>;
  if (error) return <Surface className="access-detail" title="Assignment detail"><ErrorState error={error} action={<Button size="small" onClick={() => setReload((value) => value + 1)}>Refresh</Button>} /></Surface>;
  if (!detail) return null;

  function ask(action: 'suspend' | 'resume' | 'cancel' | 'revoke') {
    const config = ({
      suspend: { title: 'Suspend current access?', label: 'Suspend access', destructive: false, copy: 'This retains the assignment but removes its current business authority. Effective access will be recomputed. Provider changes happen asynchronously and provider failure will not reactivate this assignment.' },
      resume: { title: 'Resume suspended access?', label: 'Resume access', destructive: false, copy: 'This restores this assignment to ACTIVE only if temporal validity still permits it. Resuming increases current privilege; the server re-authorizes the operation and downstream realization remains asynchronous.' },
      cancel: { title: 'Cancel scheduled access?', label: 'Cancel scheduled access', destructive: true, copy: 'This permanently cancels the future assignment before activation. It cannot be resumed. No provider call occurs inside this Access transaction.' },
      revoke: { title: 'Revoke access?', label: 'Revoke access', destructive: true, copy: 'This permanently removes this assignment’s business authority. Effective access is recomputed, but another assignment may keep an entitlement effective. Provider removal is asynchronous.' },
    } as const)[action];
    setMutationError(null); setConfirm({ action, ...config, key: newCausalKey() });
  }
  async function execute() {
    if (!confirm || !etag || !selected) return;
    setBusy(true); setMutationError(null);
    try {
      await mutateAssignment(selected, confirm.action, etag, confirm.key);
      setConfirm(null);
      onChanged('Authoritative Access state changed. Downstream technical realization remains asynchronous.');
    } catch (cause) {
      setMutationError(cause);
      if (cause instanceof ApiError && cause.status === 412) setConfirm(null);
    } finally { setBusy(false); }
  }
  const can = (action: string) => authorityHas(authority, 'access-assignment', action);
  return <Surface className="access-detail" title={primaryLabel(detail.targetLabel, detail.targetId)} description={`${primaryLabel(detail.identityLabel, detail.identityId)} · ${humanEnum(detail.targetKind)}`} actions={<Button size="small" variant="text" onClick={() => accessUrl({ assignmentId: null })}>Back to assignments</Button>}>
    <Typography className="access-section-label">Authoritative · Access</Typography>
    <Box sx={{ display: 'grid', gap: 2, mt: 1.5 }}>
      <Box><Typography component="h3" variant="subtitle1" sx={{ fontWeight: 800 }}>Authoritative intent</Typography><dl className="access-detail-dl">
        <Field label="Governed Identity"><Button variant="text" size="small" onClick={() => navigateConsole(identityDetailPath(detail.identityId))}>{primaryLabel(detail.identityLabel, detail.identityId)}</Button></Field>
        <Field label="Target">{primaryLabel(detail.targetLabel, detail.targetId)} · {humanEnum(detail.targetKind)}</Field>
        <Field label="Principal constraint">{detail.principalConstraintKind === 'ANY' ? 'Any eligible principal' : `Specific · ${detail.principalLabel || shortId(detail.specificPrincipalId)}`}</Field>
        <Field label="Lifecycle"><StatusChip label={`${detail.lifecycleState} — ${lifecycleLabel(detail.lifecycleState)}`} color={lifecycleColor(detail.lifecycleState)} /></Field>
        <Field label="Origin">{provenanceLabel(detail.provenanceKind)}</Field><Field label="Updated">{dateTime(detail.updatedAt)}</Field>
      </dl></Box>
      <Box><Typography component="h3" variant="subtitle1" sx={{ fontWeight: 800 }}>Temporal validity</Typography><dl className="access-detail-dl">
        <Field label="Validity"><strong>{detail.validity}</strong> — {validityLabel(detail)}</Field><Field label="Evaluated">{dateTime(detail.validityEvaluatedAt)}</Field>
        <Field label="Starts">{detail.validFrom ? dateTime(detail.validFrom) : 'No lower bound'}</Field><Field label="Ends">{detail.validUntil ? dateTime(detail.validUntil) : 'No end date'}</Field>
      </dl></Box>
      <Box><Typography component="h3" variant="subtitle1" sx={{ fontWeight: 800, mb: 1 }}>Lifecycle action</Typography><div className="access-actions">
        {detail.lifecycleState === 'ACTIVE' && can('suspend') ? <Button variant="outlined" onClick={() => ask('suspend')}>Suspend access</Button> : null}
        {detail.lifecycleState === 'SUSPENDED' && detail.validity === 'CURRENT' && can('resume') ? <Button variant="contained" onClick={() => ask('resume')}>Resume access</Button> : null}
        {detail.lifecycleState === 'SCHEDULED' && detail.validity === 'FUTURE' && can('cancel') ? <Button color="error" variant="outlined" onClick={() => ask('cancel')}>Cancel scheduled access</Button> : null}
        {(detail.lifecycleState === 'ACTIVE' || detail.lifecycleState === 'SUSPENDED') && can('revoke') ? <Button color="error" variant="contained" onClick={() => ask('revoke')}>Revoke access</Button> : null}
        {['REVOKED', 'CANCELLED', 'EXPIRED'].includes(detail.lifecycleState) ? <Typography variant="body2" color="text.secondary">No lifecycle action is available for this terminal assignment.</Typography> : null}
      </div><Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>Provider fulfillment is separate from Access authority and is not represented by this lifecycle state.</Typography>{mutationError ? <Box sx={{ mt: 1 }}><ErrorState error={mutationError} action={<Button size="small" onClick={() => setReload((value) => value + 1)}>Refresh</Button>} /></Box> : null}</Box>
      <Box><Typography component="h3" variant="subtitle1" sx={{ fontWeight: 800, mb: 1 }}>Recent Access evidence</Typography>
        {auditState === 'loading' ? <StatePanel state="loading">Loading Audit-owned evidence…</StatePanel> : null}
        {auditState === 'forbidden' ? <StatePanel state="forbidden">Not authorized to read Audit evidence.</StatePanel> : null}
        {auditState === 'unavailable' ? <StatePanel state="warning">Audit evidence is unavailable. The authoritative assignment above remains valid.</StatePanel> : null}
        {auditState === 'ready' && audit.length === 0 ? <StatePanel state="empty">No matching recent AuditRecords.</StatePanel> : null}
        {auditState === 'ready' && audit.length > 0 ? <Box sx={{ display: 'grid', gap: .75 }}>{audit.map((record) => <Box key={record.id} sx={{ display: 'flex', justifyContent: 'space-between', gap: 1, borderBottom: 1, borderColor: 'divider', pb: .65 }}><Typography variant="body2">{humanEnum(record.actionType.replace('access-assignment:', ''))}</Typography><Typography variant="caption" color="text.secondary">{record.outcome} · {dateTime(record.occurredAt)}</Typography></Box>)}</Box> : null}
        <Button size="small" variant="text" onClick={() => navigateConsole(`/audit?resourceType=access-assignment&resourceId=${encodeURIComponent(detail.id)}`)}>View in Audit</Button>
      </Box>
      <Box component="details"><Typography component="summary" variant="body2" sx={{ fontWeight: 700, cursor: 'pointer' }}>Technical identifiers and revision metadata</Typography><dl className="access-detail-dl"><Field label="Assignment ID"><span className="access-mono">{detail.id}</span></Field><Field label="Identity ID"><span className="access-mono">{detail.identityId}</span></Field><Field label="Target ID"><span className="access-mono">{detail.targetId}</span></Field><Field label="Provenance reference"><span className="access-mono">{detail.provenanceRefId || '—'}</span></Field><Field label="Revision">{detail.revision}</Field><Field label="Created">{dateTime(detail.createdAt)}</Field></dl></Box>
    </Box>
    <ConfirmDialog open={Boolean(confirm)} title={confirm?.title ?? ''} confirmLabel={confirm?.label} destructive={confirm?.destructive} busy={busy} onCancel={() => { if (!busy) setConfirm(null); }} onConfirm={() => void execute()}>{confirm?.copy ?? ''}</ConfirmDialog>
  </Surface>;
}

function AssignmentFiltersBar({ filters, onChange }: { filters: AssignmentFilters; onChange: (next: AssignmentFilters) => void }) {
  const [identity, setIdentity] = useState<OptionResource | null>(null);
  const [target, setTarget] = useState<OptionResource | null>(null);
  const identityLoader = useCallback((query: string, signal?: AbortSignal) => identityOptions(query, signal), []);
  const targetLoader = useCallback((query: string, signal?: AbortSignal) => targetOptions(filters.targetKind ?? 'ROLE', query, signal), [filters.targetKind]);
  return <Box className="access-filter-grid">
    <OptionPicker label="Governed Identity" selected={identity} onSelect={(option) => { setIdentity(option); onChange({ ...filters, identityId: option?.id }); }} load={identityLoader} placeholder={filters.identityId ? `Identity ${shortId(filters.identityId)}` : 'Search identities…'} />
    <SelectInput label="Lifecycle" options={['', 'SCHEDULED', 'ACTIVE', 'SUSPENDED', 'REVOKED', 'EXPIRED', 'CANCELLED']} value={filters.lifecycleState ?? ''} onChange={(event) => onChange({ ...filters, lifecycleState: (event.target.value || undefined) as AssignmentLifecycle | undefined })} />
    <SelectInput label="Target kind" options={['', 'ROLE', 'ENTITLEMENT']} value={filters.targetKind ?? ''} onChange={(event) => { setTarget(null); onChange({ ...filters, targetKind: (event.target.value || undefined) as TargetKind | undefined, targetId: undefined }); }} />
    <OptionPicker label="Target" selected={target} onSelect={(option) => { setTarget(option); onChange({ ...filters, targetId: option?.id }); }} load={targetLoader} placeholder={filters.targetId ? `Target ${shortId(filters.targetId)}` : 'Search targets…'} disabled={!filters.targetKind} />
    <SelectInput label="Validity" options={['', 'CURRENT', 'FUTURE', 'ENDED']} value={filters.validity ?? ''} onChange={(event) => onChange({ ...filters, validity: (event.target.value || undefined) as AssignmentValidity | undefined })} />
    <SelectInput label="Origin" options={['', 'MANUAL', 'REQUEST_ITEM', 'LIFECYCLE_POLICY_RULE']} value={filters.provenance ?? ''} onChange={(event) => onChange({ ...filters, provenance: (event.target.value || undefined) as AssignmentProvenance | undefined })} />
    <Button variant="text" onClick={() => { setIdentity(null); setTarget(null); onChange({}); }}>Clear filters</Button>
  </Box>;
}

function AssignmentsView({ authority, refreshToken, onChanged }: { authority: CurrentAdministrativeAuthority | null; refreshToken: number; onChanged: (message?: string) => void }) {
  const locationVersion = useLocationVersion();
  const filters = useMemo(filterFromUrl, [locationVersion]);
  const selectedId = new URLSearchParams(window.location.search).get('assignmentId');
  const [page, setPage] = useState<AssignmentPage | null>(null);
  const [cursor, setCursor] = useState<string | null>(null);
  const [history, setHistory] = useState<(string | null)[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);
  useEffect(() => { setCursor(null); setHistory([]); }, [filters.identityId, filters.lifecycleState, filters.targetKind, filters.targetId, filters.provenance, filters.validity]);
  useEffect(() => {
    const controller = new AbortController(); setLoading(true); setError(null);
    listAssignments(filters, cursor, controller.signal).then(({ data }) => setPage(data)).catch((cause) => { if (!(cause instanceof DOMException && cause.name === 'AbortError')) setError(cause); }).finally(() => setLoading(false));
    return () => controller.abort();
  }, [cursor, filters.identityId, filters.lifecycleState, filters.targetKind, filters.targetId, filters.provenance, filters.validity, refreshToken]);
  function changeFilters(next: AssignmentFilters) {
    accessUrl({ identityId: next.identityId ?? null, lifecycleState: next.lifecycleState ?? null, targetKind: next.targetKind ?? null, targetId: next.targetId ?? null, provenance: next.provenance ?? null, validity: next.validity ?? null, assignmentId: null });
  }
  return <>
    <Surface title="Filters" description="All filters execute on the server before cursor pagination. Cursor context is bound to the selected filters."><AssignmentFiltersBar filters={filters} onChange={changeFilters} /></Surface>
    <Box className="access-workbench-grid">
      <Surface title="Authoritative assignments" description="Access-owned business intent. Lifecycle and temporal validity are separate; provider fulfillment is not assignment state." actions={<StatusChip label={loading ? 'Loading' : page?.nextCursor ? 'More available' : 'Bounded page'} />}>
        {error ? <ErrorState error={error} /> : null}{loading && !page ? <StatePanel state="loading">Loading authoritative assignments…</StatePanel> : null}{!loading && !error && page?.items.length === 0 ? <StatePanel state="empty" title="No assignments in this server result">Change filters or investigate another subject. Empty does not mean not authorized.</StatePanel> : null}
        {page?.items.length ? <><div className="access-desktop-table"><DataTable minWidth={900} ariaLabel="Authoritative AccessAssignments"><TableHead><TableRow><TableCell component="th" scope="col">Governed subject</TableCell><TableCell component="th" scope="col">Target</TableCell><TableCell component="th" scope="col">Principal constraint</TableCell><TableCell component="th" scope="col">Lifecycle</TableCell><TableCell component="th" scope="col">Validity</TableCell><TableCell component="th" scope="col">Origin</TableCell><TableCell component="th" scope="col">Updated</TableCell><TableCell component="th" scope="col" align="right">Details</TableCell></TableRow></TableHead><TableBody>{page.items.map((item) => <TableRow key={item.id} hover selected={item.id === selectedId}><TableCell><strong>{primaryLabel(item.identityLabel, item.identityId)}</strong></TableCell><TableCell><strong>{primaryLabel(item.targetLabel, item.targetId)}</strong><Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>{humanEnum(item.targetKind)}</Typography></TableCell><TableCell>{item.principalConstraintKind === 'ANY' ? 'Any principal' : `Specific · ${item.principalLabel || shortId(item.specificPrincipalId)}`}</TableCell><TableCell><StatusChip label={item.lifecycleState} color={lifecycleColor(item.lifecycleState)} /></TableCell><TableCell>{validityLabel(item)}</TableCell><TableCell>{provenanceLabel(item.provenanceKind)}</TableCell><TableCell>{dateOnly(item.updatedAt)}</TableCell><TableCell align="right"><Button size="small" variant={item.id === selectedId ? 'outlined' : 'text'} onClick={() => accessUrl({ view: 'assignments', assignmentId: item.id })}>{item.id === selectedId ? 'Selected' : 'Review'}</Button></TableCell></TableRow>)}</TableBody></DataTable></div>
        <div className="access-mobile-list">{page.items.map((item) => <button key={item.id} type="button" className="access-mobile-card" data-selected={item.id === selectedId} onClick={() => accessUrl({ view: 'assignments', assignmentId: item.id })}><div className="access-mobile-card__title"><span><strong>{primaryLabel(item.identityLabel, item.identityId)}</strong><br/><span>{primaryLabel(item.targetLabel, item.targetId)}</span></span><StatusChip label={item.lifecycleState} color={lifecycleColor(item.lifecycleState)} /></div><div className="access-mobile-card__meta"><span>Kind</span><span>{humanEnum(item.targetKind)}</span><span>Validity</span><span>{validityLabel(item)}</span><span>Principal</span><span>{item.principalConstraintKind === 'ANY' ? 'Any principal' : `Specific · ${item.principalLabel || shortId(item.specificPrincipalId)}`}</span><span>Origin</span><span>{provenanceLabel(item.provenanceKind)}</span><span>Updated</span><span>{dateOnly(item.updatedAt)}</span></div></button>)}</div>
        <PageToolbar sx={{ mt: 2 }} leading={<Typography variant="caption" color="text.secondary">Server-evaluated at {dateTime(page.evaluatedAt)}</Typography>}><Button variant="outlined" disabled={history.length === 0 || loading} onClick={() => { const previous = history.at(-1) ?? null; setHistory((current) => current.slice(0, -1)); setCursor(previous); }}>Previous</Button><Button variant="outlined" disabled={!page.nextCursor || loading} onClick={() => { if (page.nextCursor) { setHistory((current) => [...current, cursor]); setCursor(page.nextCursor); } }}>Next page</Button></PageToolbar></> : null}
      </Surface>
      <AssignmentDetail selected={selectedId} authority={authority} refreshToken={refreshToken} onChanged={onChanged} />
    </Box>
  </>;
}

function EffectiveView() {
  const locationVersion = useLocationVersion();
  const params = new URLSearchParams(window.location.search);
  const identityId = params.get('identityId') || undefined;
  const selectedId = params.get('effectiveAccessId');
  const [identity, setIdentity] = useState<OptionResource | null>(null);
  const [page, setPage] = useState<EffectiveResource[] | null>(null);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [cursor, setCursor] = useState<string | null>(null);
  const [history, setHistory] = useState<(string | null)[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const [summary, setSummary] = useState<EffectiveResource | null>(null);
  const [supports, setSupports] = useState<EffectiveSupport[]>([]);
  const [supportCursor, setSupportCursor] = useState<string | null>(null);
  const [supportLoading, setSupportLoading] = useState(false);
  const [detailError, setDetailError] = useState<unknown>(null);
  const identityLoader = useCallback((query: string, signal?: AbortSignal) => identityOptions(query, signal), []);
  useEffect(() => { setCursor(null); setHistory([]); }, [identityId]);
  useEffect(() => {
    const controller = new AbortController(); setLoading(true); setError(null);
    listEffective(identityId, cursor, controller.signal).then(({ data }) => { setPage(data.items); setNextCursor(data.nextCursor ?? null); }).catch((cause) => { if (!(cause instanceof DOMException && cause.name === 'AbortError')) setError(cause); }).finally(() => setLoading(false));
    return () => controller.abort();
  }, [identityId, cursor, locationVersion]);
  useEffect(() => {
    if (!selectedId) { setSummary(null); setSupports([]); setSupportCursor(null); return; }
    const controller = new AbortController(); setSupportLoading(true); setDetailError(null);
    Promise.all([getEffectiveSummary(selectedId, controller.signal), listEffectiveSupports(selectedId, null, controller.signal)])
      .then(([summaryResult, supportResult]) => { setSummary(summaryResult.data); setSupports(supportResult.data.items); setSupportCursor(supportResult.data.nextCursor ?? null); })
      .catch((cause) => { if (!(cause instanceof DOMException && cause.name === 'AbortError')) setDetailError(cause); })
      .finally(() => setSupportLoading(false));
    return () => controller.abort();
  }, [selectedId]);
  async function loadMoreSupports() {
    if (!selectedId || !supportCursor) return;
    setSupportLoading(true); setDetailError(null);
    try { const { data } = await listEffectiveSupports(selectedId, supportCursor); setSupports((current) => [...current, ...data.items]); setSupportCursor(data.nextCursor ?? null); }
    catch (cause) { setDetailError(cause); }
    finally { setSupportLoading(false); }
  }
  return <>
    <Surface title="Effective access" description="Derived entitlement-level Access projection. Read only: change business intent through a supporting AccessAssignment." actions={<StatusChip label="Derived · Read only" color="info" />}>
      <Box sx={{ maxWidth: 420, mb: 2 }}><OptionPicker label="Governed Identity" selected={identity} onSelect={(option) => { setIdentity(option); accessUrl({ view: 'effective', identityId: option?.id ?? null, effectiveAccessId: null }); }} load={identityLoader} placeholder={identityId ? `Identity ${shortId(identityId)}` : 'Search identities…'} /></Box>
      {error ? <ErrorState error={error} /> : null}{loading && !page ? <StatePanel state="loading">Loading EffectiveAccess…</StatePanel> : null}{!loading && !error && page?.length === 0 ? <StatePanel state="empty">No current semantic EffectiveAccess in this server result.</StatePanel> : null}
      {page?.length ? <><div className="access-desktop-table"><DataTable minWidth={760} ariaLabel="Read-only EffectiveAccess projection"><TableHead><TableRow><TableCell component="th" scope="col">Governed subject</TableCell><TableCell component="th" scope="col">Entitlement</TableCell><TableCell component="th" scope="col">Principal constraint</TableCell><TableCell component="th" scope="col">Supports</TableCell><TableCell component="th" scope="col">Computed</TableCell><TableCell component="th" scope="col" align="right">Explanation</TableCell></TableRow></TableHead><TableBody>{page.map((item) => <TableRow key={item.id} selected={item.id === selectedId} hover><TableCell>{primaryLabel(item.identityLabel, item.identityId)}</TableCell><TableCell><strong>{primaryLabel(item.entitlementLabel, item.entitlementId)}</strong></TableCell><TableCell>{item.principalConstraintKey}</TableCell><TableCell>{item.supportCount}</TableCell><TableCell>{dateTime(item.computedAt)}</TableCell><TableCell align="right"><Button size="small" variant="text" onClick={() => accessUrl({ view: 'effective', effectiveAccessId: item.id })}>Explain</Button></TableCell></TableRow>)}</TableBody></DataTable></div><div className="access-mobile-list">{page.map((item) => <button key={item.id} type="button" className="access-mobile-card" data-selected={item.id === selectedId} onClick={() => accessUrl({ view: 'effective', effectiveAccessId: item.id })}><div className="access-mobile-card__title"><span><strong>{primaryLabel(item.identityLabel, item.identityId)}</strong><br/>{primaryLabel(item.entitlementLabel, item.entitlementId)}</span><StatusChip label={`${item.supportCount} support${item.supportCount === 1 ? '' : 's'}`} /></div><div className="access-mobile-card__meta"><span>Principal</span><span>{item.principalConstraintKey}</span><span>Computed</span><span>{dateTime(item.computedAt)}</span></div></button>)}</div><PageToolbar sx={{ mt: 2 }}><Button variant="outlined" disabled={history.length === 0 || loading} onClick={() => { const previous = history.at(-1) ?? null; setHistory((current) => current.slice(0, -1)); setCursor(previous); }}>Previous</Button><Button variant="outlined" disabled={!nextCursor || loading} onClick={() => { if (nextCursor) { setHistory((current) => [...current, cursor]); setCursor(nextCursor); } }}>Next page</Button></PageToolbar></> : null}
    </Surface>
    {selectedId ? <Surface title="Why this entitlement is effective" description="Bounded explanation evidence from the read-only Access projection." actions={<Button variant="text" size="small" onClick={() => accessUrl({ effectiveAccessId: null })}>Close explanation</Button>}>
      {supportLoading && !summary ? <StatePanel state="loading">Loading support evidence…</StatePanel> : null}{detailError ? <ErrorState error={detailError} /> : null}
      {summary ? <Box sx={{ display: 'grid', gap: 2 }}><dl className="access-effective-dl"><Field label="Governed Identity"><Button variant="text" size="small" onClick={() => navigateConsole(identityDetailPath(summary.identityId))}>{primaryLabel(summary.identityLabel, summary.identityId)}</Button></Field><Field label="Entitlement">{primaryLabel(summary.entitlementLabel, summary.entitlementId)}</Field><Field label="Principal constraint">{summary.principalConstraintKey}</Field><Field label="Current supports">{summary.supportCount}</Field><Field label="Computed">{dateTime(summary.computedAt)}</Field><Field label="Projection generation">{summary.projectionGeneration}</Field></dl><Typography variant="body2" color="text.secondary">{summary.supportCount > 1 ? `${summary.supportCount} current assignment supports contribute to this entitlement. Removing one does not necessarily remove effective access while another support remains.` : 'One current authoritative assignment supports this entitlement.'}</Typography><Box>{supports.map((support, index) => <div className="access-support" key={`${support.accessAssignmentId}-${support.pathHash}`}><Typography className="access-section-label">Support {index + 1}</Typography><Typography variant="subtitle2" sx={{ fontWeight: 800 }}>{support.pathDepth === 0 ? 'Direct entitlement assignment' : 'Role-derived access'}</Typography><Typography variant="body2">Path depth: {support.pathDepth}</Typography>{support.roleVersionPath.length ? <div className="access-path">{support.roleVersionPath.map((id) => `Role version ${shortId(id)}`).join(' → ')} → {primaryLabel(summary.entitlementLabel, summary.entitlementId)}</div> : <div className="access-path">Direct assignment → {primaryLabel(summary.entitlementLabel, summary.entitlementId)}</div>}<Button size="small" variant="outlined" onClick={() => accessUrl({ view: 'assignments', assignmentId: support.accessAssignmentId, effectiveAccessId: null })}>Open supporting assignment</Button><details><summary className="access-mono">Deterministic path evidence</summary><div className="access-mono">{support.pathHash}</div></details></div>)}</Box>{supportCursor ? <Button variant="outlined" disabled={supportLoading} onClick={() => void loadMoreSupports()}>{supportLoading ? 'Loading…' : 'Load more supports'}</Button> : null}</Box> : null}
    </Surface> : null}
  </>;
}

function predicateType(kind: string): LifecycleExpectedValue['type'] | null {
  for (const type of ['STRING', 'BOOLEAN', 'INTEGER', 'DECIMAL', 'DATE', 'DATETIME', 'ENUM'] as const) if (kind.includes(`_${type}_`)) return type;
  return null;
}
function expectedText(rule: LifecyclePolicyRule) {
  if (rule.expectedSet?.length) return rule.expectedSet.map((item) => item.stringValue ?? item.enumValue ?? item.decimalValue ?? item.dateValue ?? item.dateTimeValue ?? item.integerValue ?? item.booleanValue).join(', ');
  return String(rule.expectedString ?? rule.expectedEnum ?? rule.expectedDecimal ?? rule.expectedDate ?? rule.expectedDateTime ?? rule.expectedInteger ?? rule.expectedBoolean ?? '');
}
function expectedValue(type: LifecycleExpectedValue['type'], raw: string): LifecycleExpectedValue {
  if (type === 'STRING') return { type, stringValue: raw }; if (type === 'ENUM') return { type, enumValue: raw }; if (type === 'DECIMAL') return { type, decimalValue: raw }; if (type === 'DATE') return { type, dateValue: raw }; if (type === 'DATETIME') return { type, dateTimeValue: raw }; if (type === 'INTEGER') return { type, integerValue: Number(raw) }; return { type, booleanValue: raw.toLowerCase() === 'true' };
}
function withExpected(rule: LifecyclePolicyRule, raw: string): LifecyclePolicyRule {
  const base: LifecyclePolicyRule = { ...rule, expectedString: null, expectedEnum: null, expectedDecimal: null, expectedDate: null, expectedDateTime: null, expectedInteger: null, expectedBoolean: null, expectedSet: [] };
  const type = predicateType(rule.predicateKind); if (!type || rule.predicateKind === 'ALWAYS') return base;
  if (rule.predicateKind.includes('CONTAINS_ANY') || rule.predicateKind.includes('CONTAINS_ALL')) return { ...base, expectedSet: raw.split(',').map((value) => value.trim()).filter(Boolean).map((value) => expectedValue(type, value)) };
  if (type === 'STRING') return { ...base, expectedString: raw }; if (type === 'ENUM') return { ...base, expectedEnum: raw }; if (type === 'DECIMAL') return { ...base, expectedDecimal: raw }; if (type === 'DATE') return { ...base, expectedDate: raw }; if (type === 'DATETIME') return { ...base, expectedDateTime: raw }; if (type === 'INTEGER') return { ...base, expectedInteger: raw === '' ? null : Number(raw) }; return { ...base, expectedBoolean: raw.toLowerCase() === 'true' };
}
function predicateLabel(kind: string) { return kind === 'ALWAYS' ? 'Everyone' : kind.replace('CANONICAL_', '').replaceAll('_', ' ').toLowerCase().replace(/^./, (letter) => letter.toUpperCase()); }

function PolicyEditor({ policy, onDone }: { policy: LifecyclePolicyResource | null; onDone: () => void }) {
  const [rules, setRules] = useState<LifecyclePolicyRule[]>(() => policy?.rules.map((rule) => ({ ...rule, expectedSet: rule.expectedSet ? [...rule.expectedSet] : [] })) ?? []);
  const [busy, setBusy] = useState(false); const [error, setError] = useState<unknown>(null); const [confirm, setConfirm] = useState(false); const [key, setKey] = useState(() => newCausalKey());
  function updateRule(index: number, next: LifecyclePolicyRule) { setRules((current) => current.map((rule, i) => i === index ? next : rule)); setKey(newCausalKey()); }
  function addRule() { setRules((current) => [...current, { ruleId: crypto.randomUUID(), predicateKind: 'ALWAYS', targetKind: 'ROLE', targetId: '' }]); setKey(newCausalKey()); }
  async function activate() { setBusy(true); setError(null); try { await activateLifecyclePolicy(rules, key); setConfirm(false); onDone(); } catch (cause) { setError(cause); } finally { setBusy(false); } }
  return <Box sx={{ display: 'grid', gap: 1.5, p: 3 }}><StatePanel state="informational" title="Closed typed policy language">Each rule has one accepted typed predicate and one Catalog target. There is no arbitrary JSON, expression text, generic AND/OR/NOT tree, relationship predicate, or browser-side governance decision.</StatePanel>{rules.map((rule, index) => <div className="access-rule-card" key={rule.ruleId}><PageToolbar leading={<Typography variant="subtitle2" sx={{ fontWeight: 800 }}>Rule {index + 1} · <span className="access-mono">{shortId(rule.ruleId)}</span></Typography>}><Button color="error" size="small" variant="text" disabled={rules.length <= 1} onClick={() => setRules((current) => current.filter((_, i) => i !== index))}>Remove</Button></PageToolbar><div className="access-rule-grid"><SelectInput label="Predicate" options={[...PREDICATES]} value={rule.predicateKind} onChange={(event) => updateRule(index, withExpected({ ...rule, predicateKind: event.target.value, canonicalKey: event.target.value === 'ALWAYS' ? null : rule.canonicalKey }, ''))} />{rule.predicateKind !== 'ALWAYS' ? <TextInput label="Governed canonical key" value={rule.canonicalKey ?? ''} onChange={(event) => updateRule(index, { ...rule, canonicalKey: event.target.value })} helperText="Server activation validates type, cardinality and policy-addressability against current Identity schema." /> : <Box />}{rule.predicateKind !== 'ALWAYS' ? <TextInput label={rule.predicateKind.includes('CONTAINS_ANY') || rule.predicateKind.includes('CONTAINS_ALL') ? 'Expected values (comma separated, 2–20)' : 'Expected value'} value={expectedText(rule)} onChange={(event) => updateRule(index, withExpected(rule, event.target.value))} /> : null}<SelectInput label="Target kind" options={['ROLE', 'ENTITLEMENT']} value={rule.targetKind} onChange={(event) => updateRule(index, { ...rule, targetKind: event.target.value as TargetKind, targetId: '' })} /><TextInput label="Target ID" value={rule.targetId} onChange={(event) => updateRule(index, { ...rule, targetId: event.target.value })} helperText="Use the bounded Catalog target search in Assignments/Grant to resolve a stable ID." /></div></div>)}<PageToolbar leading={<Button variant="outlined" onClick={addRule} disabled={rules.length >= 100}>Add rule</Button>}><Button variant="contained" onClick={() => setConfirm(true)} disabled={rules.length === 0 || rules.some((rule) => !rule.targetId)}>Review successor</Button></PageToolbar>{error ? <ErrorState error={error} /> : null}<ConfirmDialog open={confirm} title={`Activate lifecycle policy version ${(policy?.versionNumber ?? 0) + 1}?`} confirmLabel={`Activate version ${(policy?.versionNumber ?? 0) + 1}`} busy={busy} onCancel={() => setConfirm(false)} onConfirm={() => void activate()}>This creates an immutable successor and supersedes the current active version. Policy-owned reductions are evaluated before increases; privilege increases remain subject to current server-side Governance evaluation/approval. Activation does not perform an immediate tenant-wide retroactive sweep.</ConfirmDialog></Box>;
}

function PolicyView({ authority }: { authority: CurrentAdministrativeAuthority | null }) {
  const [policy, setPolicy] = useState<LifecyclePolicyResource | null>(null); const [loading, setLoading] = useState(true); const [error, setError] = useState<unknown>(null); const [editor, setEditor] = useState(false); const [refresh, setRefresh] = useState(0);
  useEffect(() => { const controller = new AbortController(); setLoading(true); setError(null); getLifecyclePolicy(controller.signal).then(({ data }) => setPolicy(data)).catch((cause) => { if (cause instanceof ApiError && cause.status === 404) setPolicy(null); else if (!(cause instanceof DOMException && cause.name === 'AbortError')) setError(cause); }).finally(() => setLoading(false)); return () => controller.abort(); }, [refresh]);
  return <Surface title="Lifecycle access policy" description="Access-owned birthright/lifecycle policy. Active versions are immutable; activation creates a successor." actions={<><StatusChip label="Expert configuration" />{authorityHas(authority, 'lifecycle-access-policy', 'activate') ? <Button variant="contained" onClick={() => setEditor(true)}>Activate successor version</Button> : null}</>}>
    {loading ? <StatePanel state="loading">Loading current lifecycle policy…</StatePanel> : null}{error ? <ErrorState error={error} /> : null}{!loading && !error && !policy ? <StatePanel state="empty">No active lifecycle-access policy exists.</StatePanel> : null}{policy ? <Box sx={{ display: 'grid', gap: 2 }}><dl className="access-detail-dl"><Field label="Current active version">{policy.versionNumber}</Field><Field label="State"><StatusChip label={policy.state} color="success" /></Field><Field label="Activated">{dateTime(policy.activatedAt)}</Field><Field label="Rules">{policy.rules.length} of 100</Field></dl><Typography component="h3" variant="subtitle1" sx={{ fontWeight: 800 }}>Current immutable rules</Typography><div className="access-desktop-table"><DataTable minWidth={760} ariaLabel="Lifecycle access policy rules"><TableHead><TableRow><TableCell component="th" scope="col">Rule</TableCell><TableCell component="th" scope="col">When</TableCell><TableCell component="th" scope="col">Grant</TableCell><TableCell component="th" scope="col">Target kind</TableCell><TableCell component="th" scope="col">Principal</TableCell></TableRow></TableHead><TableBody>{policy.rules.map((rule) => <TableRow key={rule.ruleId}><TableCell><span className="access-mono">{shortId(rule.ruleId)}</span></TableCell><TableCell>{rule.predicateKind === 'ALWAYS' ? 'Everyone' : `${rule.canonicalKey ?? 'canonical attribute'} · ${predicateLabel(rule.predicateKind)} · ${expectedText(rule)}`}</TableCell><TableCell><span className="access-mono">{shortId(rule.targetId)}</span></TableCell><TableCell>{humanEnum(rule.targetKind)}</TableCell><TableCell>Any principal</TableCell></TableRow>)}</TableBody></DataTable></div><StatePanel state="informational">Activation does not imply an immediate tenant-wide retroactive sweep. Lifecycle/current-state triggers drive reconciliation, and Governance evidence never directly creates AccessAssignment authority.</StatePanel></Box> : null}<SidePanel open={editor} title="Activate successor lifecycle policy" description="Clone the current immutable policy, edit only accepted typed rule forms, then activate one successor version." onClose={() => setEditor(false)}>{editor ? <PolicyEditor policy={policy} onDone={() => { setEditor(false); setRefresh((value) => value + 1); }} /> : null}</SidePanel>
  </Surface>;
}

function GrantPanel({ open, onClose, onCreated }: { open: boolean; onClose: () => void; onCreated: (assignmentId: string) => void }) {
  const [identity, setIdentity] = useState<OptionResource | null>(null); const [targetKind, setTargetKind] = useState<TargetKind>('ROLE'); const [target, setTarget] = useState<OptionResource | null>(null); const [constraint, setConstraint] = useState<PrincipalConstraintKind>('ANY'); const [principal, setPrincipal] = useState<OptionResource | null>(null); const [validFrom, setValidFrom] = useState(''); const [validUntil, setValidUntil] = useState(''); const [busy, setBusy] = useState(false); const [error, setError] = useState<unknown>(null); const [review, setReview] = useState(false); const [key, setKey] = useState(() => newCausalKey());
  const identityLoader = useCallback((query: string, signal?: AbortSignal) => identityOptions(query, signal), []); const targetLoader = useCallback((query: string, signal?: AbortSignal) => targetOptions(targetKind, query, signal), [targetKind]); const principalLoader = useCallback((_query: string, signal?: AbortSignal) => identity ? principalOptions(identity.id, signal) : Promise.resolve({ data: { items: [] }, etag: null, correlationId: null }), [identity]);
  function dirty() { setKey(newCausalKey()); setError(null); } function iso(value: string) { return value ? new Date(value).toISOString() : null; }
  async function submit() { if (!identity || !target || (constraint === 'SPECIFIC' && !principal)) return; setBusy(true); setError(null); try { const result = await createAssignment({ identityId: identity.id, targetKind, roleId: targetKind === 'ROLE' ? target.id : null, entitlementId: targetKind === 'ENTITLEMENT' ? target.id : null, principalConstraintKind: constraint, specificPrincipalId: constraint === 'SPECIFIC' ? principal!.id : null, validFrom: iso(validFrom), validUntil: iso(validUntil) }, key); setReview(false); onCreated(result.data.id); } catch (cause) { setError(cause); } finally { setBusy(false); } }
  const ready = Boolean(identity && target && (constraint === 'ANY' || principal));
  return <SidePanel open={open} title="Grant access" description="Record one manual AccessAssignment. Provider realization is downstream and asynchronous." busy={busy} onClose={onClose}><Box sx={{ display: 'grid', gap: 2, p: 3 }}><OptionPicker label="Governed Identity" selected={identity} onSelect={(option) => { setIdentity(option); setPrincipal(null); dirty(); }} load={identityLoader} placeholder="Search identities…" /><SelectInput label="Target kind" options={['ROLE', 'ENTITLEMENT']} value={targetKind} onChange={(event) => { setTargetKind(event.target.value as TargetKind); setTarget(null); dirty(); }} /><OptionPicker label={targetKind === 'ROLE' ? 'Role' : 'Entitlement'} selected={target} onSelect={(option) => { setTarget(option); dirty(); }} load={targetLoader} placeholder={`Search ${targetKind === 'ROLE' ? 'roles' : 'entitlements'}…`} /><SelectInput label="Principal constraint" options={['ANY', 'SPECIFIC']} value={constraint} onChange={(event) => { setConstraint(event.target.value as PrincipalConstraintKind); setPrincipal(null); dirty(); }} />{constraint === 'SPECIFIC' ? <OptionPicker label="Specific Principal" selected={principal} onSelect={(option) => { setPrincipal(option); dirty(); }} load={principalLoader} placeholder={identity ? 'Select an Identity principal…' : 'Select an Identity first'} disabled={!identity} /> : null}<div className="access-rule-grid"><TextInput label="Valid from" type="datetime-local" value={validFrom} onChange={(event) => { setValidFrom(event.target.value); dirty(); }} helperText="Leave empty for immediate lower bound." /><TextInput label="Valid until" type="datetime-local" value={validUntil} onChange={(event) => { setValidUntil(event.target.value); dirty(); }} helperText="Leave empty for no end date." /></div><StatePanel state="informational" title="Origin: Manual grant">Provenance and lifecycle state are not operator-selectable. The server records MANUAL provenance and derives initial lifecycle from accepted temporal semantics.</StatePanel>{error ? <ErrorState error={error} /> : null}<PageToolbar><Button variant="text" disabled={busy} onClick={onClose}>Cancel</Button><Button variant="contained" disabled={!ready || busy} onClick={() => setReview(true)}>Review grant</Button></PageToolbar></Box><ConfirmDialog open={review} title="Grant this access?" confirmLabel="Grant access" busy={busy} onCancel={() => setReview(false)} onConfirm={() => void submit()}>This records Access-owned business authority for {identity?.label ?? 'the selected Identity'} to {target?.label ?? 'the selected target'}. Provider provisioning is not part of this transaction and must not be inferred from a successful grant.</ConfirmDialog></SidePanel>;
}

export function AccessWorkspace() {
  const locationVersion = useLocationVersion();
  const params = new URLSearchParams(window.location.search);
  const rawView = params.get('view');
  const view: AccessView = rawView === 'effective' || rawView === 'policy' ? rawView : 'assignments';
  const [authority, setAuthority] = useState<CurrentAdministrativeAuthority | null>(null);
  const [grantOpen, setGrantOpen] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const [announcement, setAnnouncement] = useState('');
  useEffect(() => { const controller = new AbortController(); getCurrentAdministrativeAuthority(controller.signal).then(setAuthority).catch(() => setAuthority(null)); return () => controller.abort(); }, []);
  useEffect(() => { if (rawView && !['assignments', 'effective', 'policy'].includes(rawView)) accessUrl({ view: 'assignments' }, true); }, [rawView, locationVersion]);
  function setView(next: AccessView) { accessUrl({ view: next, assignmentId: next === 'assignments' ? params.get('assignmentId') : null, effectiveAccessId: next === 'effective' ? params.get('effectiveAccessId') : null }); }
  function changed(message = 'Authoritative Access state refreshed.') { setRefresh((value) => value + 1); setAnnouncement(message); }
  return <Page width="wide" className="access-page"><PageHeader eyebrow="ACCESS" title="Access" description="Investigate authoritative access intent, derived effective entitlement access, and the lifecycle action that is safe now." actions={authorityHas(authority, 'access-assignment', 'create') ? <Button variant="contained" onClick={() => setGrantOpen(true)}>+ Grant access</Button> : undefined} /><Box aria-live="polite" sx={{ position: 'absolute', width: 1, height: 1, overflow: 'hidden', clip: 'rect(0 0 0 0)' }}>{announcement}</Box><Tabs className="access-tabs" value={view} onChange={(_event, next) => setView(next as AccessView)} aria-label="Access semantic views"><Tab value="assignments" label="Assignments · Authoritative" {...getTabA11yProps(GROUP, 'assignments')} /><Tab value="effective" label="Effective access · Read only" {...getTabA11yProps(GROUP, 'effective')} /><Tab value="policy" label="Lifecycle policy · Expert" {...getTabA11yProps(GROUP, 'policy')} /></Tabs><TabPanel groupId={GROUP} tabId="assignments" active={view === 'assignments'}><AssignmentsView authority={authority} refreshToken={refresh} onChanged={changed} /></TabPanel><TabPanel groupId={GROUP} tabId="effective" active={view === 'effective'}><EffectiveView /></TabPanel><TabPanel groupId={GROUP} tabId="policy" active={view === 'policy'}><PolicyView authority={authority} /></TabPanel><GrantPanel open={grantOpen} onClose={() => setGrantOpen(false)} onCreated={(assignmentId) => { setGrantOpen(false); accessUrl({ view: 'assignments', assignmentId }); changed('Access authorization recorded. Effective access and downstream technical realization may update asynchronously.'); }} /></Page>;
}
