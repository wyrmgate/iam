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
import {
  Box,
  Button,
  DataTable,
  DataTableActionCell,
  DataTableRow,
  Modal,
  Page,
  PageHeader,
  PageToolbar,
  SelectInput,
  StateIndicator,
  StatePanel,
  Surface,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextInput,
  Typography,
  type StateIndicatorTone,
} from '../ui';

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

function lifecycleTone(state: IdentityLifecycleState): StateIndicatorTone {
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
    <Page width="wide" aria-labelledby="identities-page-title">
      <PageHeader
        eyebrow="Identity · governed subjects"
        title={<span id="identities-page-title">Identities</span>}
        description="Find and investigate canonical governed subjects. Technical accounts are Principals and remain separate from Identity authority."
        actions={(
          <>
            <Button variant="outlined" disabled={status === 'loading'} onClick={() => setReloadKey((value) => value + 1)}>
              {status === 'loading' ? 'Refreshing…' : 'Refresh'}
            </Button>
            <Button variant="contained" onClick={() => setCreateOpen(true)}>Create Identity</Button>
          </>
        )}
      />

      <Surface>
        <PageToolbar
          leading={authority ? (
            <Box sx={{ display: 'grid', gap: 0.25 }}>
              <Typography variant="caption" color="text.secondary">Tenant</Typography>
              <Typography variant="body2" sx={{ fontWeight: 600 }}>{shortId(authority.tenantId)}</Typography>
              <Typography variant="caption" color="text.secondary">Authority evaluated {formatDateTime(authority.evaluatedAt)}</Typography>
            </Box>
          ) : undefined}
        >
          <Button variant="outlined" onClick={() => setLookupOpen(true)}>Open by Identity ID</Button>
        </PageToolbar>

        <Box
          component="section"
          aria-label="Identity discovery filters"
          sx={{
            display: 'grid',
            gridTemplateColumns: { xs: '1fr', md: 'minmax(16rem, 1.6fr) minmax(10rem, .7fr) minmax(11rem, .8fr) auto' },
            gap: 1.25,
            mt: 2,
            alignItems: 'start',
          }}
        >
          <TextInput
            label="Search"
            placeholder="Search display name"
            value={filters.q}
            onChange={(event) => setFilters((current) => ({ ...current, q: event.target.value }))}
          />
          <SelectInput
            label="Type"
            value={filters.type}
            emptyLabel="All types"
            options={['PERSON', 'SERVICE', 'WORKLOAD']}
            onValueChange={(value) => setFilters((current) => ({ ...current, type: value as Filters['type'] }))}
          />
          <SelectInput
            label="Lifecycle"
            value={filters.lifecycle}
            emptyLabel="All lifecycles"
            options={['PENDING', 'ACTIVE', 'SUSPENDED', 'INACTIVE', 'DECOMMISSIONED']}
            onValueChange={(value) => setFilters((current) => ({ ...current, lifecycle: value as Filters['lifecycle'] }))}
          />
          <Button
            variant="text"
            disabled={!filtersActive && !filters.q.trim()}
            onClick={clearFilters}
            sx={{ alignSelf: { xs: 'stretch', md: 'end' }, minHeight: { md: 36 } }}
          >
            Clear filters
          </Button>
        </Box>
      </Surface>

      {status === 'loading' && identities.length === 0 ? (
        <StatePanel state="loading" title="Loading identities">Reading authoritative governed subjects…</StatePanel>
      ) : null}
      {status === 'error' ? (
        <StatePanel state="error" title="Unable to load identities" action={<Button variant="outlined" onClick={() => setReloadKey((value) => value + 1)}>Retry</Button>}>
          {errorCopy(error)}
        </StatePanel>
      ) : null}
      {status === 'ready' && identities.length === 0 ? (
        <StatePanel state="empty" title={filtersActive ? 'No matching identities' : 'No identities'}>
          {filtersActive ? 'No governed subject matches the current filters.' : 'No governed Identity is visible to your current server-authorized context.'}
        </StatePanel>
      ) : null}

      {identities.length > 0 ? (
        <Surface
          title="Governed identities"
          description={`${identities.length} ${filtersActive ? 'matching ' : ''}identities currently loaded.`}
        >
          <DataTable ariaLabel="Governed identities" minWidth={760}>
            <TableHead>
              <TableRow>
                <TableCell>Governed subject</TableCell>
                <TableCell>Type</TableCell>
                <TableCell>Lifecycle</TableCell>
                <TableCell>Updated</TableCell>
                <TableCell align="right">Action</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {identities.map((identity) => (
                <DataTableRow key={identity.id} interactive>
                  <TableCell>
                    <Button variant="text" size="small" onClick={() => onSelect(identity.id)} sx={{ p: 0, minHeight: 0, justifyContent: 'flex-start', textAlign: 'left' }}>
                      <Box sx={{ display: 'grid', gap: 0.25 }}>
                        <Typography component="span" variant="body2" sx={{ color: 'text.primary', fontWeight: 600 }}>{identity.displayName}</Typography>
                        <Typography component="span" variant="caption" color="text.secondary" sx={{ fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace' }}>{identity.id}</Typography>
                      </Box>
                    </Button>
                  </TableCell>
                  <TableCell>{identity.type}</TableCell>
                  <TableCell><StateIndicator label={identity.lifecycleState} tone={lifecycleTone(identity.lifecycleState)} /></TableCell>
                  <TableCell><time dateTime={identity.updatedAt}>{formatDateTime(identity.updatedAt)}</time></TableCell>
                  <DataTableActionCell><Button variant="outlined" size="small" onClick={() => onSelect(identity.id)}>View</Button></DataTableActionCell>
                </DataTableRow>
              ))}
            </TableBody>
          </DataTable>

          <Box sx={{ display: 'flex', flexDirection: { xs: 'column', sm: 'row' }, alignItems: { xs: 'stretch', sm: 'center' }, justifyContent: 'space-between', gap: 1.25, mt: 2 }}>
            <Typography variant="caption" color="text.secondary">
              {nextCursor ? 'More identities are available.' : 'End of visible collection.'}
            </Typography>
            {nextCursor ? (
              <Button variant="outlined" disabled={status === 'loading'} onClick={() => void loadMore()}>
                {status === 'loading' ? 'Loading…' : 'Load more identities'}
              </Button>
            ) : null}
          </Box>
        </Surface>
      ) : null}

      <Modal open={lookupOpen} title="Open Identity by ID" description="Direct resource lookup remains server-authorized." onClose={() => setLookupOpen(false)}>
        <Box sx={{ display: 'grid', gap: 1.5 }}>
          <TextInput autoFocus label="Identity ID" value={identityLookup} onChange={(event) => setIdentityLookup(event.target.value)} onKeyDown={(event) => { if (event.key === 'Enter') openExactIdentity(); }} />
          <Box sx={{ display: 'flex', justifyContent: 'flex-end', gap: 1 }}>
            <Button variant="text" onClick={() => setLookupOpen(false)}>Cancel</Button>
            <Button variant="contained" disabled={!identityLookup.trim()} onClick={openExactIdentity}>Open Identity</Button>
          </Box>
        </Box>
      </Modal>

      <Modal open={createOpen} title="Create governed Identity" description="Create one typed governed subject. Technical accounts and access remain separate." busy={creating} onClose={() => { if (!creating) { setCreateOpen(false); setCreateError(null); } }}>
        <Box sx={{ display: 'grid', gap: 1.5 }}>
          {createError ? <StatePanel state="error" title="Identity was not created">{errorCopy(createError)}</StatePanel> : null}
          <SelectInput label="Identity type" value={create.type} options={['PERSON', 'SERVICE', 'WORKLOAD']} onValueChange={(value) => setCreate((current) => ({ ...current, type: (value || 'PERSON') as IdentityType }))} />
          <SelectInput label="Initial lifecycle" value={create.lifecycleState} options={['PENDING', 'ACTIVE', 'SUSPENDED', 'INACTIVE']} onValueChange={(value) => setCreate((current) => ({ ...current, lifecycleState: (value || 'PENDING') as Exclude<IdentityLifecycleState, 'DECOMMISSIONED'> }))} />
          <TextInput label="Display name" required value={create.displayName} onChange={(event) => setCreate((current) => ({ ...current, displayName: event.target.value }))} />
          <Box sx={{ display: 'flex', justifyContent: 'flex-end', gap: 1 }}>
            <Button variant="text" disabled={creating} onClick={() => setCreateOpen(false)}>Cancel</Button>
            <Button variant="contained" disabled={creating || !create.displayName.trim()} onClick={() => void createIdentity()}>{creating ? 'Creating…' : 'Create Identity'}</Button>
          </Box>
        </Box>
      </Modal>
    </Page>
  );
}
