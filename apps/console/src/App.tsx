import { useEffect, useMemo, useState } from 'react';
import {
  ApiError,
  getIdentity,
  getSystemInfo,
  listIdentities,
  transitionIdentity,
  type IdentityLifecycleAction,
  type IdentityPage,
  type IdentityResource,
  type SystemInfo,
} from './api';

type LoadState<T> =
  | { status: 'loading' }
  | { status: 'ready'; value: T }
  | { status: 'error'; error: unknown };

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
  return (
    <div className="state-card state-error" role="alert">
      <strong>{copy.title}</strong>
      <span>{copy.detail}</span>
      {onRetry && <button className="button secondary" onClick={onRetry}>Retry</button>}
    </div>
  );
}

function Overview({ system }: { system: LoadState<SystemInfo> }) {
  return (
    <section className="page-stack">
      <header className="page-header">
        <p className="eyebrow">Control plane</p>
        <h1>Overview</h1>
        <p>Operator console for authoritative Wyrmgate IAM workflows. Business decisions and authorization remain server-side.</p>
      </header>
      <div className="card-grid">
        <article className="card">
          <span className="label">Runtime</span>
          {system.status === 'loading' && <strong>Checking…</strong>}
          {system.status === 'ready' && <><strong>{system.value.name}</strong><span>{system.value.status}</span></>}
          {system.status === 'error' && <span>Unavailable</span>}
        </article>
        <article className="card">
          <span className="label">Trust boundary</span>
          <strong>Server-derived</strong>
          <span>Tenant, governed actor, and administrative authority are never inferred from browser claims.</span>
        </article>
        <article className="card">
          <span className="label">API routing</span>
          <strong>Same origin</strong>
          <span>The browser uses only <code>/api/*</code>; backend origin configuration stays at the edge.</span>
        </article>
      </div>
    </section>
  );
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
  const [pendingAction, setPendingAction] = useState<IdentityLifecycleAction | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    setState({ status: 'loading' });
    getIdentity(identityId, controller.signal)
      .then(({ data, etag }) => {
        if (!etag) throw new Error('Identity response did not include the required ETag');
        setState({ status: 'ready', value: { identity: data, etag } });
      })
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return;
        setState({ status: 'error', error });
      });
    return () => controller.abort();
  }, [identityId, reloadKey]);

  async function runAction(action: IdentityLifecycleAction) {
    if (state.status !== 'ready') return;
    const destructive = action === 'deactivate' || action === 'decommission';
    const prompt = action === 'decommission'
      ? 'Decommissioning is terminal. Confirm this authoritative Identity lifecycle change.'
      : `Confirm ${action} for ${state.value.identity.displayName}?`;
    if (destructive && !window.confirm(prompt)) return;

    setPendingAction(action);
    setMutationError(null);
    try {
      const result = await transitionIdentity(identityId, action, state.value.etag);
      if (!result.etag) throw new Error('Mutation response did not include the required ETag');
      setState({ status: 'ready', value: { identity: result.data, etag: result.etag } });
    } catch (error) {
      setMutationError(error);
    } finally {
      setPendingAction(null);
    }
  }

  return (
    <section className="page-stack">
      <button className="back-link" onClick={onBack}>← Identities</button>
      {state.status === 'loading' && <div className="state-card">Loading authoritative Identity…</div>}
      {state.status === 'error' && <ErrorPanel error={state.error} onRetry={() => setReloadKey((value) => value + 1)} />}
      {state.status === 'ready' && (
        <>
          <header className="page-header split-header">
            <div>
              <p className="eyebrow">Identity · authoritative state</p>
              <h1>{state.value.identity.displayName}</h1>
              <p className="mono">{state.value.identity.id}</p>
            </div>
            <span className={`pill state-${state.value.identity.lifecycleState.toLowerCase()}`}>{state.value.identity.lifecycleState}</span>
          </header>
          {mutationError && <ErrorPanel error={mutationError} onRetry={() => setReloadKey((value) => value + 1)} />}
          <dl className="detail-grid card">
            <div><dt>Type</dt><dd>{state.value.identity.type}</dd></div>
            <div><dt>Revision</dt><dd>{state.value.identity.revision}</dd></div>
            <div><dt>Created</dt><dd>{new Date(state.value.identity.createdAt).toLocaleString()}</dd></div>
            <div><dt>Updated</dt><dd>{new Date(state.value.identity.updatedAt).toLocaleString()}</dd></div>
          </dl>
          <section className="card action-card">
            <div>
              <span className="label">Lifecycle actions</span>
              <p>Each command uses the latest server ETag as <code>If-Match</code> and a fresh causal <code>Idempotency-Key</code>.</p>
            </div>
            <div className="action-row">
              {lifecycleActions(state.value.identity).map((action) => (
                <button
                  key={action}
                  className={action === 'decommission' ? 'button danger' : 'button secondary'}
                  disabled={pendingAction !== null}
                  onClick={() => void runAction(action)}
                >
                  {pendingAction === action ? 'Working…' : action[0].toUpperCase() + action.slice(1)}
                </button>
              ))}
              {lifecycleActions(state.value.identity).length === 0 && <span>No further lifecycle transitions are valid.</span>}
            </div>
          </section>
        </>
      )}
    </section>
  );
}

function Identities({ selectedId, onSelect }: { selectedId?: string; onSelect: (id?: string) => void }) {
  const [pages, setPages] = useState<IdentityPage[]>([]);
  const [state, setState] = useState<'loading' | 'ready' | 'error'>('loading');
  const [error, setError] = useState<unknown>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const nextCursor = pages.at(-1)?.nextCursor ?? null;
  const identities = useMemo(() => pages.flatMap((page) => page.items), [pages]);

  useEffect(() => {
    if (selectedId) return;
    const controller = new AbortController();
    setState('loading');
    setPages([]);
    listIdentities(undefined, controller.signal)
      .then((page) => { setPages([page]); setState('ready'); })
      .catch((cause: unknown) => {
        if (cause instanceof DOMException && cause.name === 'AbortError') return;
        setError(cause); setState('error');
      });
    return () => controller.abort();
  }, [reloadKey, selectedId]);

  async function loadMore() {
    if (!nextCursor) return;
    setState('loading');
    try {
      const page = await listIdentities(nextCursor);
      setPages((current) => [...current, page]);
      setState('ready');
    } catch (cause) {
      setError(cause); setState('error');
    }
  }

  if (selectedId) return <IdentityDetail identityId={selectedId} onBack={() => onSelect()} />;

  return (
    <section className="page-stack">
      <header className="page-header">
        <p className="eyebrow">Identity</p>
        <h1>Identities</h1>
        <p>Governed subjects. Provider accounts are Principals and are intentionally not presented as Identity authority here.</p>
      </header>
      {state === 'loading' && identities.length === 0 && <div className="state-card">Loading identities…</div>}
      {state === 'error' && <ErrorPanel error={error} onRetry={() => setReloadKey((value) => value + 1)} />}
      {state !== 'error' && identities.length === 0 && state === 'ready' && <div className="state-card">No governed identities found.</div>}
      {identities.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead><tr><th>Name</th><th>Type</th><th>Lifecycle</th><th>Revision</th><th><span className="sr-only">Open</span></th></tr></thead>
            <tbody>
              {identities.map((identity) => (
                <tr key={identity.id}>
                  <td><strong>{identity.displayName}</strong><span className="mono subline">{identity.id}</span></td>
                  <td>{identity.type}</td>
                  <td><span className={`pill state-${identity.lifecycleState.toLowerCase()}`}>{identity.lifecycleState}</span></td>
                  <td>{identity.revision}</td>
                  <td><button className="text-button" onClick={() => onSelect(identity.id)}>View</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {nextCursor && <button className="button secondary load-more" disabled={state === 'loading'} onClick={() => void loadMore()}>{state === 'loading' ? 'Loading…' : 'Load more'}</button>}
    </section>
  );
}

export function App() {
  const [pathname, setPathname] = usePathname();
  const [system, setSystem] = useState<LoadState<SystemInfo>>({ status: 'loading' });

  useEffect(() => {
    const controller = new AbortController();
    getSystemInfo(controller.signal)
      .then((value) => setSystem({ status: 'ready', value }))
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return;
        setSystem({ status: 'error', error });
      });
    return () => controller.abort();
  }, []);

  const identityMatch = pathname.match(/^\/identities\/([^/]+)$/);
  const route = pathname === '/' ? 'overview' : pathname === '/identities' || identityMatch ? 'identities' : 'not-found';

  function go(path: string) { navigate(path, setPathname); }

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <a className="brand" href="/" onClick={(event) => { event.preventDefault(); go('/'); }}>
          <span className="brand-mark">W</span><span><strong>Wyrmgate</strong><small>IAM Console</small></span>
        </a>
        <nav aria-label="Primary navigation">
          <a className={route === 'overview' ? 'active' : ''} href="/" onClick={(event) => { event.preventDefault(); go('/'); }}>Overview</a>
          <a className={route === 'identities' ? 'active' : ''} href="/identities" onClick={(event) => { event.preventDefault(); go('/identities'); }}>Identities</a>
        </nav>
        <div className="sidebar-footer">
          <span className={`status-dot ${system.status === 'ready' ? 'online' : ''}`} />
          <span>{system.status === 'ready' ? system.value.status : system.status === 'loading' ? 'Checking runtime' : 'Runtime unavailable'}</span>
        </div>
      </aside>
      <main className="content" id="main-content">
        {route === 'overview' && <Overview system={system} />}
        {route === 'identities' && <Identities selectedId={identityMatch ? decodeURIComponent(identityMatch[1]) : undefined} onSelect={(id) => go(id ? `/identities/${encodeURIComponent(id)}` : '/identities')} />}
        {route === 'not-found' && <section className="page-stack"><header className="page-header"><p className="eyebrow">404</p><h1>Page not found</h1></header><button className="button secondary" onClick={() => go('/')}>Return to overview</button></section>}
      </main>
    </div>
  );
}
