import { useEffect, useMemo, useState } from 'react';
import {
  ApiError,
  apiRequest,
  getIdentity,
  getSystemInfo,
  listIdentities,
  transitionIdentity,
  V1_ROOT,
  type IdentityLifecycleAction,
  type IdentityPage,
  type IdentityResource,
  type SystemInfo,
} from './api';
import { CatalogPage, PrincipalsPage, RequestsPage, ReviewsPage } from './ControlPlanePages';
import {
  AccessPage,
  AdministrationPage,
  AuditPage,
  CredentialsPage,
  IntegrationsPage,
  PoliciesPage,
} from './ContractCompletenessPages';

type LoadState<T> =
  | { status: 'loading' }
  | { status: 'ready'; value: T }
  | { status: 'error'; error: unknown };

type RouteKey = 'overview' | 'identities' | 'principals' | 'catalog' | 'access' | 'requests' | 'reviews' | 'policies' | 'credentials' | 'integrations' | 'administration' | 'audit' | 'not-found';

const navigation: Array<{ route: RouteKey; path: string; label: string }> = [
  { route: 'overview', path: '/', label: 'Overview' },
  { route: 'identities', path: '/identities', label: 'Identities' },
  { route: 'principals', path: '/principals', label: 'Principals' },
  { route: 'catalog', path: '/catalog', label: 'Catalog' },
  { route: 'access', path: '/access', label: 'Access' },
  { route: 'requests', path: '/requests', label: 'Requests & Approvals' },
  { route: 'reviews', path: '/reviews', label: 'Reviews' },
  { route: 'policies', path: '/policies', label: 'Policies / Exceptions' },
  { route: 'credentials', path: '/credentials', label: 'Credentials' },
  { route: 'integrations', path: '/integrations', label: 'Integrations' },
  { route: 'administration', path: '/administration', label: 'Administration' },
  { route: 'audit', path: '/audit', label: 'Audit' },
];

function usePathname() {
  const [pathname, setPathname] = useState(window.location.pathname);
  useEffect(() => {
    const onPopState = () => setPathname(window.location.pathname);
    window.addEventListener('popstate', onPopState);
    return () => window.removeEventListener('popstate', onPopState);
  }, []);
  return [pathname, setPathname] as const;
}

function navigate(path: string, setPathname: (path: string) => void) {
  if (window.location.pathname === path) return;
  window.history.pushState({}, '', path);
  setPathname(path);
  window.scrollTo({ top: 0 });
}

function formatError(error: unknown): { title: string; detail: string } {
  if (error instanceof ApiError) {
    if (error.status === 401) return { title: 'Authentication required', detail: 'Your authenticated control-plane session is required.' };
    if (error.status === 403) return { title: 'Not authorized', detail: 'The server denied this operation for your current administrative authority.' };
    if (error.status === 409) return { title: 'Conflict', detail: 'The requested change conflicts with current authoritative state. Refresh and review before retrying.' };
    if (error.status === 412) return { title: 'Resource changed', detail: 'This resource has a newer revision. Refresh it before making another change.' };
    return { title: `Request failed (${error.status})`, detail: error.message };
  }
  return { title: 'Unable to reach Wyrmgate', detail: 'The request could not be completed. Retry after checking connectivity.' };
}

function ErrorPanel({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const copy = formatError(error);
  return <div className="state-card state-error" role="alert"><strong>{copy.title}</strong><span>{copy.detail}</span>{onRetry ? <button className="button secondary" onClick={onRetry}>Retry</button> : null}</div>;
}

function Overview({ system }: { system: LoadState<SystemInfo> }) {
  return <section className="page-stack"><header className="page-header"><p className="eyebrow">Control plane</p><h1>Overview</h1><p>Operator console for authoritative Wyrmgate IAM workflows. Business decisions and authorization remain server-side.</p></header><div className="card-grid"><article className="card"><span className="label">Runtime</span>{system.status === 'loading' ? <strong>Checking…</strong> : null}{system.status === 'ready' ? <><strong>{system.value.name}</strong><span>{system.value.status}</span></> : null}{system.status === 'error' ? <span>Unavailable</span> : null}</article><article className="card"><span className="label">Trust boundary</span><strong>Server-derived</strong><span>Tenant, governed actor and administrative authority are never inferred from browser claims.</span></article><article className="card"><span className="label">API routing</span><strong>Same origin</strong><span>The browser uses only <code>/api/*</code>; backend origin configuration stays at the edge.</span></article><article className="card"><span className="label">Known API gaps</span><strong>#265 · #266 · #268</strong><span>Missing public authority projection, Integration operations reads and complete Binding/Worker mutation schemas remain unavailable rather than bypassed.</span></article></div></section>;
}

function lifecycleActions(identity: IdentityResource): IdentityLifecycleAction[] {
  switch (identity.lifecycleState) {
    case 'PENDING': return ['activate', 'deactivate', 'decommission'];
    case 'ACTIVE': return ['suspend', 'deactivate', 'decommission'];
    case 'SUSPENDED': return ['activate', 'deactivate', 'decommission'];
    case 'INACTIVE': return ['activate', 'decommission'];
    case 'DECOMMISSIONED': return [];
  }
}

function IdentityDetail({ identityId, onBack }: { identityId: string; onBack: () => void }) {
  const [reloadKey, setReloadKey] = useState(0);
  const [state, setState] = useState<LoadState<{ identity: IdentityResource; etag: string }>>({ status: 'loading' });
  const [mutationError, setMutationError] = useState<unknown>(null);
  const [pendingAction, setPendingAction] = useState<string | null>(null);
  const [displayName, setDisplayName] = useState('');
  const [mergeValues, setMergeValues] = useState({ absorbedIdentityId: '', absorbedRevision: '', reason: '' });
  const [splitValues, setSplitValues] = useState({ newDisplayName: '', sourceRecordIds: '', principalIds: '', reason: '' });
  const [canonical, setCanonical] = useState<Record<string, unknown>[] | null>(null);

  useEffect(() => {
    const controller = new AbortController(); setState({ status: 'loading' });
    getIdentity(identityId, controller.signal).then(({ data, etag }) => { if (!etag) throw new Error('Identity response did not include the required ETag'); setDisplayName(data.displayName); setState({ status: 'ready', value: { identity: data, etag } }); }).catch((error: unknown) => { if (error instanceof DOMException && error.name === 'AbortError') return; setState({ status: 'error', error }); });
    return () => controller.abort();
  }, [identityId, reloadKey]);

  useEffect(() => {
    const controller = new AbortController();
    apiRequest<{ items: Record<string, unknown>[] }>(`${V1_ROOT}/identities/${encodeURIComponent(identityId)}/canonical-attributes?limit=50`, { signal: controller.signal }).then(({ data }) => setCanonical(data.items)).catch(() => setCanonical(null));
    return () => controller.abort();
  }, [identityId, reloadKey]);

  async function runAction(action: IdentityLifecycleAction) {
    if (state.status !== 'ready') return;
    if ((action === 'deactivate' || action === 'decommission') && !window.confirm(action === 'decommission' ? 'Decommissioning is terminal. Confirm this authoritative Identity lifecycle change.' : `Confirm ${action} for ${state.value.identity.displayName}?`)) return;
    setPendingAction(action); setMutationError(null);
    try { const result = await transitionIdentity(identityId, action, state.value.etag); if (!result.etag) throw new Error('Mutation response did not include the required ETag'); setState({ status: 'ready', value: { identity: result.data, etag: result.etag } }); }
    catch (error) { setMutationError(error); }
    finally { setPendingAction(null); }
  }

  async function updateName() {
    if (state.status !== 'ready') return; setPendingAction('rename'); setMutationError(null);
    try { const result = await apiRequest<IdentityResource>(`${V1_ROOT}/identities/${encodeURIComponent(identityId)}`, { method: 'PATCH', etag: state.value.etag, idempotent: true, body: { displayName } }); if (!result.etag) throw new Error('Identity update did not return ETag'); setState({ status: 'ready', value: { identity: result.data, etag: result.etag } }); }
    catch (error) { setMutationError(error); }
    finally { setPendingAction(null); }
  }

  async function merge() {
    if (state.status !== 'ready' || !window.confirm('Merge permanently decommissions the absorbed Identity and moves only Identity-owned relationships. Continue?')) return;
    setPendingAction('merge'); setMutationError(null);
    try { await apiRequest(`${V1_ROOT}/identities/${encodeURIComponent(identityId)}:merge`, { method: 'POST', etag: state.value.etag, idempotent: true, body: { absorbedIdentityId: mergeValues.absorbedIdentityId, absorbedRevision: Number(mergeValues.absorbedRevision), reason: mergeValues.reason } }); setReloadKey((v) => v + 1); }
    catch (error) { setMutationError(error); }
    finally { setPendingAction(null); }
  }

  async function split() {
    if (state.status !== 'ready' || !window.confirm('Split creates a new PENDING Identity and moves only explicitly selected Identity-owned relationships. Continue?')) return;
    setPendingAction('split'); setMutationError(null);
    try { await apiRequest(`${V1_ROOT}/identities/${encodeURIComponent(identityId)}:split`, { method: 'POST', etag: state.value.etag, idempotent: true, body: { newDisplayName: splitValues.newDisplayName, sourceRecordIds: splitValues.sourceRecordIds.split(/[\n,]/).map((v) => v.trim()).filter(Boolean), principalIds: splitValues.principalIds.split(/[\n,]/).map((v) => v.trim()).filter(Boolean), reason: splitValues.reason } }); setReloadKey((v) => v + 1); }
    catch (error) { setMutationError(error); }
    finally { setPendingAction(null); }
  }

  return <section className="page-stack"><button className="back-link" onClick={onBack}>← Identities</button>{state.status === 'loading' ? <div className="state-card">Loading authoritative Identity…</div> : null}{state.status === 'error' ? <ErrorPanel error={state.error} onRetry={() => setReloadKey((value) => value + 1)} /> : null}{state.status === 'ready' ? <><header className="page-header split-header"><div><p className="eyebrow">Identity · authoritative state</p><h1>{state.value.identity.displayName}</h1><p className="mono">{state.value.identity.id}</p></div><span className={`pill state-${state.value.identity.lifecycleState.toLowerCase()}`}>{state.value.identity.lifecycleState}</span></header>{mutationError ? <ErrorPanel error={mutationError} onRetry={() => setReloadKey((value) => value + 1)} /> : null}<dl className="detail-grid card"><div><dt>Type</dt><dd>{state.value.identity.type}</dd></div><div><dt>Revision</dt><dd>{state.value.identity.revision}</dd></div><div><dt>Created</dt><dd>{new Date(state.value.identity.createdAt).toLocaleString()}</dd></div><div><dt>Updated</dt><dd>{new Date(state.value.identity.updatedAt).toLocaleString()}</dd></div></dl><section className="card action-card"><div><span className="label">Display name</span><p>Non-lifecycle metadata uses PATCH with ETag/If-Match.</p></div><div className="inline-form"><input value={displayName} onChange={(e) => setDisplayName(e.target.value)} /><button className="button secondary" disabled={pendingAction !== null || !displayName.trim()} onClick={() => void updateName()}>{pendingAction === 'rename' ? 'Saving…' : 'Save name'}</button></div></section><section className="card action-card"><div><span className="label">Lifecycle actions</span><p>Explicit semantic commands; provider realization remains separate.</p></div><div className="action-row">{lifecycleActions(state.value.identity).map((action) => <button key={action} className={action === 'decommission' ? 'button danger' : 'button secondary'} disabled={pendingAction !== null} onClick={() => void runAction(action)}>{pendingAction === action ? 'Working…' : action[0].toUpperCase() + action.slice(1)}</button>)}</div></section><section className="card"><span className="label">Canonical attributes</span><p>Governed effective values only; classification authorization and redaction are enforced by the server.</p>{canonical === null ? <span>Unavailable or not authorized.</span> : null}{canonical?.length === 0 ? <span>No canonical attributes.</span> : null}{canonical && canonical.length > 0 ? <div className="table-wrap compact-table"><table><thead><tr><th>Key</th><th>Status</th><th>Classification</th><th>Value</th></tr></thead><tbody>{canonical.map((attribute, index) => <tr key={String(attribute.definitionId ?? index)}><td>{String(attribute.key ?? '—')}</td><td>{String(attribute.resolutionStatus ?? '—')}</td><td>{String(attribute.classification ?? '—')}</td><td>{attribute.valueRedacted === true ? 'Redacted' : attribute.value ? '[authorized value]' : '—'}</td></tr>)}</tbody></table></div> : null}</section><details className="card expandable"><summary>Merge administrative correction</summary><div className="form-grid"><label><span>Absorbed Identity ID</span><input value={mergeValues.absorbedIdentityId} onChange={(e) => setMergeValues((v) => ({ ...v, absorbedIdentityId: e.target.value }))} /></label><label><span>Absorbed revision</span><input type="number" value={mergeValues.absorbedRevision} onChange={(e) => setMergeValues((v) => ({ ...v, absorbedRevision: e.target.value }))} /></label><label><span>Reason</span><textarea value={mergeValues.reason} onChange={(e) => setMergeValues((v) => ({ ...v, reason: e.target.value }))} /></label></div><button className="button danger" disabled={pendingAction !== null} onClick={() => void merge()}>Merge Identity</button></details><details className="card expandable"><summary>Split administrative correction</summary><div className="form-grid"><label><span>New display name</span><input value={splitValues.newDisplayName} onChange={(e) => setSplitValues((v) => ({ ...v, newDisplayName: e.target.value }))} /></label><label><span>Source record IDs</span><textarea value={splitValues.sourceRecordIds} onChange={(e) => setSplitValues((v) => ({ ...v, sourceRecordIds: e.target.value }))} /></label><label><span>Principal IDs</span><textarea value={splitValues.principalIds} onChange={(e) => setSplitValues((v) => ({ ...v, principalIds: e.target.value }))} /></label><label><span>Reason</span><textarea value={splitValues.reason} onChange={(e) => setSplitValues((v) => ({ ...v, reason: e.target.value }))} /></label></div><button className="button danger" disabled={pendingAction !== null} onClick={() => void split()}>Split Identity</button></details></> : null}</section>;
}

function Identities({ selectedId, onSelect }: { selectedId?: string; onSelect: (id?: string) => void }) {
  const [pages, setPages] = useState<IdentityPage[]>([]); const [state, setState] = useState<'loading' | 'ready' | 'error'>('loading'); const [error, setError] = useState<unknown>(null); const [reloadKey, setReloadKey] = useState(0); const [create, setCreate] = useState({ type: 'PERSON', lifecycleState: 'PENDING', displayName: '' });
  const nextCursor = pages.at(-1)?.nextCursor ?? null; const identities = useMemo(() => pages.flatMap((page) => page.items), [pages]);
  useEffect(() => { if (selectedId) return; const controller = new AbortController(); setState('loading'); setPages([]); listIdentities(undefined, controller.signal).then((page) => { setPages([page]); setState('ready'); }).catch((cause: unknown) => { if (cause instanceof DOMException && cause.name === 'AbortError') return; setError(cause); setState('error'); }); return () => controller.abort(); }, [reloadKey, selectedId]);
  async function loadMore() { if (!nextCursor) return; setState('loading'); try { const page = await listIdentities(nextCursor); setPages((current) => [...current, page]); setState('ready'); } catch (cause) { setError(cause); setState('error'); } }
  async function createIdentity() { try { await apiRequest(`${V1_ROOT}/identities`, { method: 'POST', idempotent: true, body: { type: create.type, profile: { kind: create.type }, lifecycleState: create.lifecycleState, displayName: create.displayName } }); setCreate({ type: 'PERSON', lifecycleState: 'PENDING', displayName: '' }); setReloadKey((v) => v + 1); } catch (cause) { setError(cause); setState('error'); } }
  if (selectedId) return <IdentityDetail identityId={selectedId} onBack={() => onSelect()} />;
  return <section className="page-stack"><header className="page-header"><p className="eyebrow">Identity</p><h1>Identities</h1><p>Governed subjects. Principal technical accounts and provider observations remain distinct concepts.</p></header><details className="card expandable"><summary>Create Identity</summary><div className="form-grid"><label><span>Type</span><select value={create.type} onChange={(e) => setCreate((v) => ({ ...v, type: e.target.value }))}><option>PERSON</option><option>SERVICE</option><option>WORKLOAD</option></select></label><label><span>Initial lifecycle</span><select value={create.lifecycleState} onChange={(e) => setCreate((v) => ({ ...v, lifecycleState: e.target.value }))}><option>PENDING</option><option>ACTIVE</option><option>SUSPENDED</option><option>INACTIVE</option></select></label><label><span>Display name</span><input value={create.displayName} onChange={(e) => setCreate((v) => ({ ...v, displayName: e.target.value }))} /></label></div><button className="button" disabled={!create.displayName.trim()} onClick={() => void createIdentity()}>Create Identity</button></details>{state === 'loading' && identities.length === 0 ? <div className="state-card">Loading identities…</div> : null}{state === 'error' ? <ErrorPanel error={error} onRetry={() => setReloadKey((value) => value + 1)} /> : null}{state === 'ready' && identities.length === 0 ? <div className="state-card">No governed identities found.</div> : null}{identities.length > 0 ? <div className="table-wrap"><table><thead><tr><th>Name</th><th>Type</th><th>Lifecycle</th><th>Revision</th><th>Open</th></tr></thead><tbody>{identities.map((identity) => <tr key={identity.id}><td><strong>{identity.displayName}</strong><span className="mono subline">{identity.id}</span></td><td>{identity.type}</td><td><span className={`pill state-${identity.lifecycleState.toLowerCase()}`}>{identity.lifecycleState}</span></td><td>{identity.revision}</td><td><button className="text-button" onClick={() => onSelect(identity.id)}>View</button></td></tr>)}</tbody></table></div> : null}{nextCursor ? <button className="button secondary load-more" disabled={state === 'loading'} onClick={() => void loadMore()}>{state === 'loading' ? 'Loading…' : 'Load more'}</button> : null}</section>;
}

export function App() {
  const [pathname, setPathname] = usePathname(); const [system, setSystem] = useState<LoadState<SystemInfo>>({ status: 'loading' });
  useEffect(() => { const controller = new AbortController(); getSystemInfo(controller.signal).then((value) => setSystem({ status: 'ready', value })).catch((error: unknown) => { if (error instanceof DOMException && error.name === 'AbortError') return; setSystem({ status: 'error', error }); }); return () => controller.abort(); }, []);
  const identityMatch = pathname.match(/^\/identities\/([^/]+)$/); const exact = navigation.find((item) => item.path === pathname); const route: RouteKey = identityMatch ? 'identities' : exact?.route ?? 'not-found'; const go = (path: string) => navigate(path, setPathname);
  return <div className="app-shell"><aside className="sidebar"><a className="brand" href="/" onClick={(event) => { event.preventDefault(); go('/'); }}><span className="brand-mark">W</span><span><strong>Wyrmgate</strong><small>IAM Console</small></span></a><nav aria-label="Primary navigation">{navigation.map((item) => <a key={item.path} className={route === item.route ? 'active' : ''} href={item.path} onClick={(event) => { event.preventDefault(); go(item.path); }}>{item.label}</a>)}</nav><div className="sidebar-footer"><span className={`status-dot ${system.status === 'ready' ? 'online' : ''}`} /><span>{system.status === 'ready' ? system.value.status : system.status === 'loading' ? 'Checking runtime' : 'Runtime unavailable'}</span></div></aside><main className="content" id="main-content">{route === 'overview' ? <Overview system={system} /> : null}{route === 'identities' ? <Identities selectedId={identityMatch ? decodeURIComponent(identityMatch[1]) : undefined} onSelect={(id) => go(id ? `/identities/${encodeURIComponent(id)}` : '/identities')} /> : null}{route === 'principals' ? <PrincipalsPage /> : null}{route === 'catalog' ? <CatalogPage /> : null}{route === 'access' ? <AccessPage /> : null}{route === 'requests' ? <RequestsPage /> : null}{route === 'reviews' ? <ReviewsPage /> : null}{route === 'policies' ? <PoliciesPage /> : null}{route === 'credentials' ? <CredentialsPage /> : null}{route === 'integrations' ? <IntegrationsPage /> : null}{route === 'administration' ? <AdministrationPage /> : null}{route === 'audit' ? <AuditPage /> : null}{route === 'not-found' ? <section className="page-stack"><header className="page-header"><p className="eyebrow">404</p><h1>Page not found</h1></header><button className="button secondary" onClick={() => go('/')}>Return to overview</button></section> : null}</main></div>;
}
