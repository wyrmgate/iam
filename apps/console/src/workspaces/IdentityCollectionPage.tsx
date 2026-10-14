import { useEffect, useMemo, useState } from 'react';
import {
  ApiError,
  apiRequest,
  V1_ROOT,
  type CurrentAdministrativeAuthority,
  type IdentityLifecycleState,
  type IdentityPage,
  type IdentityResource,
  type IdentityType,
} from '../api';
import { Box, Button, Modal, SelectInput, StatePanel, TextInput } from '../ui';
import '../identity-golden.css';

type Props = {
  authority: CurrentAdministrativeAuthority | null;
  onSelect: (id: string) => void;
};

type Filters = {
  q: string;
  type: '' | IdentityType;
  lifecycle: '' | IdentityLifecycleState;
};

const INITIAL_FILTERS: Filters = { q: '', type: '', lifecycle: '' };

function shortId(value: string): string {
  return value.length <= 32 ? value : `${value.slice(0, 12)}…${value.slice(-8)}`;
}

function formatDateTime(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString([], { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

function compactDateTime(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString([], { month: 'short', day: 'numeric', year: 'numeric', hour: '2-digit', minute: '2-digit' });
}

function lifecycleTone(state: IdentityLifecycleState): string {
  switch (state) {
    case 'ACTIVE': return 'success';
    case 'SUSPENDED': return 'warning';
    case 'PENDING': return 'info';
    case 'DECOMMISSIONED': return 'danger';
    default: return 'neutral';
  }
}

function errorCopy(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 403) return 'Your current administrative authority does not allow this Identity collection read.';
    if (error.status === 401) return 'Your control-plane session is no longer authenticated.';
    return error.message;
  }
  return 'Wyrmgate could not load the Identity collection.';
}

function buildListPath(cursor: string | null, filters: Filters): string {
  const query = new URLSearchParams({ limit: '10' });
  if (cursor) query.set('cursor', cursor);
  const q = filters.q.trim();
  if (q) query.set('q', q);
  if (filters.type) query.set('type', filters.type);
  if (filters.lifecycle) query.set('lifecycle', filters.lifecycle);
  return `${V1_ROOT}/identities?${query.toString()}`;
}

export function IdentityCollectionPage({ authority, onSelect }: Props) {
  const [filters, setFilters] = useState<Filters>(INITIAL_FILTERS);
  const [appliedFilters, setAppliedFilters] = useState<Filters>(INITIAL_FILTERS);
  const [pages, setPages] = useState<IdentityPage[]>([]);
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading');
  const [error, setError] = useState<unknown>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [lookupOpen, setLookupOpen] = useState(false);
  const [identityLookup, setIdentityLookup] = useState('');
  const [createOpen, setCreateOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<unknown>(null);
  const [create, setCreate] = useState<{ type: IdentityType; lifecycleState: Exclude<IdentityLifecycleState, 'DECOMMISSIONED'>; displayName: string }>({
    type: 'PERSON', lifecycleState: 'PENDING', displayName: '',
  });

  const identities = useMemo(() => pages.flatMap((page) => page.items), [pages]);
  const nextCursor = pages.at(-1)?.nextCursor ?? null;
  const filtersActive = Boolean(appliedFilters.q.trim() || appliedFilters.type || appliedFilters.lifecycle);

  useEffect(() => {
    const handle = window.setTimeout(() => setAppliedFilters(filters), 280);
    return () => window.clearTimeout(handle);
  }, [filters]);

  useEffect(() => {
    const controller = new AbortController();
    setStatus('loading');
    setError(null);
    setPages([]);
    apiRequest<IdentityPage>(buildListPath(null, appliedFilters), { signal: controller.signal })
      .then(({ data }) => {
        setPages([data]);
        setStatus('ready');
      })
      .catch((cause: unknown) => {
        if (cause instanceof DOMException && cause.name === 'AbortError') return;
        setError(cause);
        setStatus('error');
      });
    return () => controller.abort();
  }, [appliedFilters, reloadKey]);

  async function loadMore() {
    if (!nextCursor || status === 'loading') return;
    setStatus('loading');
    try {
      const { data } = await apiRequest<IdentityPage>(buildListPath(nextCursor, appliedFilters));
      setPages((current) => [...current, data]);
      setStatus('ready');
    } catch (cause) {
      setError(cause);
      setStatus('error');
    }
  }

  async function createIdentity() {
    if (!create.displayName.trim()) return;
    setCreating(true);
    setCreateError(null);
    try {
      const { data } = await apiRequest<IdentityResource>(`${V1_ROOT}/identities`, {
        method: 'POST',
        idempotent: true,
        body: {
          type: create.type,
          profile: { kind: create.type },
          lifecycleState: create.lifecycleState,
          displayName: create.displayName.trim(),
        },
      });
      setCreate({ type: 'PERSON', lifecycleState: 'PENDING', displayName: '' });
      setCreateOpen(false);
      setReloadKey((value) => value + 1);
      onSelect(data.id);
    } catch (cause) {
      setCreateError(cause);
    } finally {
      setCreating(false);
    }
  }

  function openExactIdentity() {
    const id = identityLookup.trim();
    if (!id) return;
    setLookupOpen(false);
    onSelect(id);
  }

  function clearFilters() {
    setFilters(INITIAL_FILTERS);
    setAppliedFilters(INITIAL_FILTERS);
  }

  return (
    <section className="identity-golden-page" aria-labelledby="identities-page-title">
      <header className="identity-golden-header">
        <div className="identity-golden-heading">
          <span className="identity-golden-eyebrow">Identity · governed subjects</span>
          <h1 id="identities-page-title">Identities</h1>
          <p>Find and investigate canonical governed subjects. Technical accounts are Principals and remain separate from Identity authority.</p>
        </div>
        <div className="identity-golden-header-side">
          {authority ? (
            <div className="identity-golden-context">
              <strong>Tenant: {shortId(authority.tenantId)}</strong>
              <span>Authority evaluated {formatDateTime(authority.evaluatedAt)}</span>
            </div>
          ) : null}
          <div className="identity-golden-header-actions">
            <Button variant="outlined" disabled={status === 'loading'} onClick={() => setReloadKey((value) => value + 1)}>
              {status === 'loading' ? 'Refreshing…' : 'Refresh'}
            </Button>
            <Button variant="contained" onClick={() => setCreateOpen(true)}>＋ Create Identity</Button>
          </div>
        </div>
      </header>

      <section className="identity-discovery" aria-label="Identity discovery filters">
        <div className="identity-search-field">
          <span aria-hidden="true">⌕</span>
          <input
            aria-label="Search display name"
            placeholder="Search display name…"
            value={filters.q}
            onChange={(event) => setFilters((current) => ({ ...current, q: event.target.value }))}
          />
        </div>
        <label className="identity-filter-select">
          <span>Type</span>
          <select value={filters.type} onChange={(event) => setFilters((current) => ({ ...current, type: event.target.value as Filters['type'] }))}>
            <option value="">All types</option>
            <option value="PERSON">PERSON</option>
            <option value="SERVICE">SERVICE</option>
            <option value="WORKLOAD">WORKLOAD</option>
          </select>
        </label>
        <label className="identity-filter-select">
          <span>Lifecycle</span>
          <select value={filters.lifecycle} onChange={(event) => setFilters((current) => ({ ...current, lifecycle: event.target.value as Filters['lifecycle'] }))}>
            <option value="">All lifecycles</option>
            <option value="PENDING">PENDING</option>
            <option value="ACTIVE">ACTIVE</option>
            <option value="SUSPENDED">SUSPENDED</option>
            <option value="INACTIVE">INACTIVE</option>
            <option value="DECOMMISSIONED">DECOMMISSIONED</option>
          </select>
        </label>
        <Button variant="outlined" className="identity-id-button" onClick={() => setLookupOpen(true)}>▣ Open by Identity ID</Button>
        <Button variant="outlined" className="identity-clear-button" disabled={!filtersActive && !filters.q.trim()} onClick={clearFilters}>Clear filters</Button>
      </section>

      <section className="identity-results" aria-label="Governed identities">
        {status === 'loading' && identities.length === 0 ? (
          <div className="identity-result-state"><StatePanel state="loading" title="Loading identities">Reading authoritative governed subjects…</StatePanel></div>
        ) : null}
        {status === 'error' ? (
          <div className="identity-result-state"><StatePanel state="error" title="Unable to load identities" action={<Button variant="outlined" onClick={() => setReloadKey((value) => value + 1)}>Retry</Button>}>{errorCopy(error)}</StatePanel></div>
        ) : null}
        {status === 'ready' && identities.length === 0 ? (
          <div className="identity-result-state"><StatePanel state="empty" title={filtersActive ? 'No matching identities' : 'No identities'}>{filtersActive ? 'No governed subject matches the current filters.' : 'No governed Identity is visible to your current server-authorized context.'}</StatePanel></div>
        ) : null}

        {identities.length > 0 ? (
          <>
            <div className="identity-golden-table-wrap">
              <table className="identity-golden-table">
                <caption className="sr-only">Governed identities</caption>
                <thead><tr><th>Governed subject</th><th>Type</th><th>Lifecycle</th><th>Updated</th><th>Actions</th></tr></thead>
                <tbody>
                  {identities.map((identity) => (
                    <tr key={identity.id}>
                      <td><button type="button" className="identity-subject-link" onClick={() => onSelect(identity.id)}><strong>{identity.displayName}</strong><small>{identity.id}</small></button></td>
                      <td><span className="identity-type-text">{identity.type}</span></td>
                      <td><span className={`identity-life identity-life-${lifecycleTone(identity.lifecycleState)}`}><i aria-hidden="true" />{identity.lifecycleState}</span></td>
                      <td><time dateTime={identity.updatedAt}>{compactDateTime(identity.updatedAt)}</time></td>
                      <td><div className="identity-row-actions"><Button variant="outlined" size="small" onClick={() => onSelect(identity.id)}>View</Button><span className="identity-kebab" aria-hidden="true">⋮</span></div></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <div className="identity-golden-mobile-list">
              {identities.map((identity) => (
                <button className="identity-golden-mobile-card" type="button" key={identity.id} onClick={() => onSelect(identity.id)}>
                  <div className="identity-mobile-title"><strong>{identity.displayName}</strong><span>{identity.type}</span></div>
                  <div className="identity-mobile-id">{identity.id}</div>
                  <div className="identity-mobile-meta"><span className={`identity-life identity-life-${lifecycleTone(identity.lifecycleState)}`}><i aria-hidden="true" />{identity.lifecycleState}</span><time dateTime={identity.updatedAt}>{compactDateTime(identity.updatedAt)}</time></div>
                  <span className="identity-mobile-chevron" aria-hidden="true">›</span>
                </button>
              ))}
            </div>

            <footer className="identity-results-footer">
              <span>Showing {identities.length} {filtersActive ? 'matching ' : ''}identities</span>
              {nextCursor ? <Button variant="outlined" disabled={status === 'loading'} onClick={() => void loadMore()}>{status === 'loading' ? 'Loading…' : 'Load more identities⌄'}</Button> : <span className="identity-end-copy">End of visible collection</span>}
            </footer>
          </>
        ) : null}
      </section>

      <Modal open={lookupOpen} title="Open Identity by ID" description="Direct resource lookup remains server-authorized." onClose={() => setLookupOpen(false)}>
        <Box sx={{ display: 'grid', gap: 1.5 }}>
          <TextInput autoFocus label="Identity ID" value={identityLookup} onChange={(event) => setIdentityLookup(event.target.value)} onKeyDown={(event) => { if (event.key === 'Enter') openExactIdentity(); }} />
          <Box sx={{ display: 'flex', justifyContent: 'flex-end', gap: 1 }}><Button variant="text" onClick={() => setLookupOpen(false)}>Cancel</Button><Button variant="contained" disabled={!identityLookup.trim()} onClick={openExactIdentity}>Open Identity</Button></Box>
        </Box>
      </Modal>

      <Modal open={createOpen} title="Create governed Identity" description="Create one typed governed subject. Technical accounts and access remain separate." busy={creating} onClose={() => { if (!creating) { setCreateOpen(false); setCreateError(null); } }}>
        <Box sx={{ display: 'grid', gap: 1.5 }}>
          {createError ? <StatePanel state="error" title="Identity was not created">{errorCopy(createError)}</StatePanel> : null}
          <SelectInput label="Identity type" value={create.type} options={['PERSON', 'SERVICE', 'WORKLOAD']} onValueChange={(value) => setCreate((current) => ({ ...current, type: (value || 'PERSON') as IdentityType }))} />
          <SelectInput label="Initial lifecycle" value={create.lifecycleState} options={['PENDING', 'ACTIVE', 'SUSPENDED', 'INACTIVE']} onValueChange={(value) => setCreate((current) => ({ ...current, lifecycleState: (value || 'PENDING') as Exclude<IdentityLifecycleState, 'DECOMMISSIONED'> }))} />
          <TextInput label="Display name" required value={create.displayName} onChange={(event) => setCreate((current) => ({ ...current, displayName: event.target.value }))} />
          <Box sx={{ display: 'flex', justifyContent: 'flex-end', gap: 1 }}><Button variant="text" disabled={creating} onClick={() => setCreateOpen(false)}>Cancel</Button><Button variant="contained" disabled={creating || !create.displayName.trim()} onClick={() => void createIdentity()}>{creating ? 'Creating…' : 'Create Identity'}</Button></Box>
        </Box>
      </Modal>
    </section>
  );
}
