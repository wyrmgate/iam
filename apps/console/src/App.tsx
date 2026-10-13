import { useEffect, useMemo, useState } from 'react';
import {
  ApiError,
  apiRequest,
  getCurrentAdministrativeAuthority,
  getIdentity,
  getSystemInfo,
  listIdentities,
  transitionIdentity,
  V1_ROOT,
  type CurrentAdministrativeAuthority,
  type IdentityLifecycleAction,
  type IdentityPage,
  type IdentityResource,
  type SystemInfo,
} from './api';
import {
  AccessPage,
  AdministrationPage,
  AuditPage,
  CatalogPage,
  CredentialsPage,
  IntegrationsPage,
  PoliciesPage,
  PrincipalsPage,
  RequestsPage,
  ReviewsPage,
} from './ControlPlanePages';
import { useConsolePathname } from './consoleLocation';
import {
  identityDetailPath,
  matchConsoleRoute,
  navigateConsole,
  type ConsoleRouteId,
} from './consoleNavigation';
import {
  Box,
  Button,
  ConfirmDialog,
  DataTable,
  Page,
  PageHeader,
  StatePanel,
  StatusChip,
  Surface,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextInput,
  Typography,
} from './ui';

type LoadState<T> =
  | { status: 'loading' }
  | { status: 'ready'; value: T }
  | { status: 'error'; error: unknown };

type IdentityConfirmation =
  | { kind: 'lifecycle'; action: IdentityLifecycleAction }
  | { kind: 'merge' }
  | { kind: 'split' }
  | null;

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
    <StatePanel
      severity="error"
      title={copy.title}
      action={onRetry ? <Button variant="outlined" size="small" onClick={onRetry}>Retry</Button> : undefined}
    >
      {copy.detail}
    </StatePanel>
  );
}

function Overview({ system, authority }: { system: LoadState<SystemInfo>; authority: LoadState<CurrentAdministrativeAuthority> }) {
  const authoritySummary = authority.status === 'ready'
    ? authority.value.administrativelyEligible
      ? `${authority.value.authorities.length} effective source${authority.value.authorities.length === 1 ? '' : 's'}`
      : 'Not administratively eligible'
    : authority.status === 'loading' ? 'Checking…' : 'Unavailable';

  return (
    <Page>
      <PageHeader
        eyebrow="Control plane"
        title="Overview"
        description="Operator console for authoritative Wyrmgate IAM workflows. Business decisions and authorization remain server-side."
      />
      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: 'repeat(2, minmax(0, 1fr))', xl: 'repeat(4, minmax(0, 1fr))' }, gap: 2 }}>
        <Surface title="Runtime" sx={{ minHeight: 160 }}>
          <Typography variant="h6" sx={{ mb: 0.75 }}>
            {system.status === 'loading' ? 'Checking…' : system.status === 'ready' ? system.value.name : 'Unavailable'}
          </Typography>
          <Typography color="text.secondary" variant="body2">
            {system.status === 'ready' ? system.value.status : system.status === 'loading' ? 'Resolving current runtime status.' : 'Runtime status could not be loaded.'}
          </Typography>
        </Surface>
        <Surface title="Trust boundary" sx={{ minHeight: 160 }}>
          <Typography variant="h6" sx={{ mb: 0.75 }}>Server-derived</Typography>
          <Typography color="text.secondary" variant="body2">Tenant, governed actor, and administrative authority are never inferred from browser claims.</Typography>
        </Surface>
        <Surface title="API routing" sx={{ minHeight: 160 }}>
          <Typography variant="h6" sx={{ mb: 0.75 }}>Same origin</Typography>
          <Typography color="text.secondary" variant="body2">The browser uses only <code>/api/*</code>; backend origin configuration stays at the edge.</Typography>
        </Surface>
        <Surface title="Authority visibility" sx={{ minHeight: 160 }}>
          <Typography variant="h6" sx={{ mb: 0.75 }}>{authoritySummary}</Typography>
          <Typography color="text.secondary" variant="body2">
            {authority.status === 'error'
              ? 'Navigation remains available; operation-time server authorization is still final.'
              : 'Navigation is optimized from the current no-store Administration projection. A server 403 remains final.'}
          </Typography>
        </Surface>
      </Box>
    </Page>
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

function lifecycleChipColor(state: IdentityResource['lifecycleState']): 'success' | 'warning' | 'default' | 'info' {
  if (state === 'ACTIVE') return 'success';
  if (state === 'SUSPENDED') return 'warning';
  if (state === 'PENDING') return 'info';
  return 'default';
}

function confirmationCopy(confirmation: IdentityConfirmation, identityName: string) {
  if (!confirmation) return { title: '', body: '', label: 'Confirm' };
  if (confirmation.kind === 'merge') {
    return {
      title: 'Merge Identity?',
      body: 'Merge permanently decommissions the absorbed Identity and moves only Identity-owned relationships. This administrative correction cannot be treated as a generic account merge.',
      label: 'Merge Identity',
    };
  }
  if (confirmation.kind === 'split') {
    return {
      title: 'Split Identity?',
      body: 'Split creates a new PENDING Identity and moves only explicitly selected Identity-owned relationships. Review the selected source records and Principals before continuing.',
      label: 'Split Identity',
    };
  }
  if (confirmation.action === 'decommission') {
    return {
      title: 'Decommission Identity?',
      body: `Decommissioning ${identityName} is terminal. Confirm this authoritative Identity lifecycle change.`,
      label: 'Decommission',
    };
  }
  return {
    title: `Deactivate ${identityName}?`,
    body: 'This changes authoritative Identity lifecycle state. Downstream provider realization remains a separate asynchronous concern.',
    label: 'Deactivate',
  };
}

function IdentityDetail({ identityId, onBack }: { identityId: string; onBack: () => void }) {
  const [reloadKey, setReloadKey] = useState(0);
  const [state, setState] = useState<LoadState<{ identity: IdentityResource; etag: string }>>({ status: 'loading' });
  const [mutationError, setMutationError] = useState<unknown>(null);
  const [pendingAction, setPendingAction] = useState<string | null>(null);
  const [confirmation, setConfirmation] = useState<IdentityConfirmation>(null);
  const [displayName, setDisplayName] = useState('');
  const [mergeValues, setMergeValues] = useState({ absorbedIdentityId: '', absorbedRevision: '', reason: '' });
  const [splitValues, setSplitValues] = useState({ newDisplayName: '', sourceRecordIds: '', principalIds: '', reason: '' });
  const [canonical, setCanonical] = useState<Record<string, unknown>[] | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    setState({ status: 'loading' });
    getIdentity(identityId, controller.signal)
      .then(({ data, etag }) => {
        if (!etag) throw new Error('Identity response did not include the required ETag');
        setDisplayName(data.displayName);
        setState({ status: 'ready', value: { identity: data, etag } });
      })
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return;
        setState({ status: 'error', error });
      });
    return () => controller.abort();
  }, [identityId, reloadKey]);

  useEffect(() => {
    const controller = new AbortController();
    apiRequest<{ items: Record<string, unknown>[] }>(`${V1_ROOT}/identities/${encodeURIComponent(identityId)}/canonical-attributes?limit=50`, { signal: controller.signal })
      .then(({ data }) => setCanonical(data.items))
      .catch(() => setCanonical(null));
    return () => controller.abort();
  }, [identityId, reloadKey]);

  async function executeLifecycleAction(action: IdentityLifecycleAction) {
    if (state.status !== 'ready') return;
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
      setConfirmation(null);
    }
  }

  function requestLifecycleAction(action: IdentityLifecycleAction) {
    if (action === 'deactivate' || action === 'decommission') {
      setConfirmation({ kind: 'lifecycle', action });
      return;
    }
    void executeLifecycleAction(action);
  }

  async function updateName() {
    if (state.status !== 'ready') return;
    setPendingAction('rename');
    setMutationError(null);
    try {
      const result = await apiRequest<IdentityResource>(`${V1_ROOT}/identities/${encodeURIComponent(identityId)}`, {
        method: 'PATCH',
        etag: state.value.etag,
        idempotent: true,
        body: { displayName },
      });
      if (!result.etag) throw new Error('Identity update did not return ETag');
      setState({ status: 'ready', value: { identity: result.data, etag: result.etag } });
    } catch (error) {
      setMutationError(error);
    } finally {
      setPendingAction(null);
    }
  }

  async function executeMerge() {
    if (state.status !== 'ready') return;
    setPendingAction('merge');
    setMutationError(null);
    try {
      await apiRequest(`${V1_ROOT}/identities/${encodeURIComponent(identityId)}:merge`, {
        method: 'POST',
        etag: state.value.etag,
        idempotent: true,
        body: {
          absorbedIdentityId: mergeValues.absorbedIdentityId,
          absorbedRevision: Number(mergeValues.absorbedRevision),
          reason: mergeValues.reason,
        },
      });
      setReloadKey((value) => value + 1);
    } catch (error) {
      setMutationError(error);
    } finally {
      setPendingAction(null);
      setConfirmation(null);
    }
  }

  async function executeSplit() {
    if (state.status !== 'ready') return;
    setPendingAction('split');
    setMutationError(null);
    try {
      await apiRequest(`${V1_ROOT}/identities/${encodeURIComponent(identityId)}:split`, {
        method: 'POST',
        etag: state.value.etag,
        idempotent: true,
        body: {
          newDisplayName: splitValues.newDisplayName,
          sourceRecordIds: splitValues.sourceRecordIds.split(/[\n,]/).map((value) => value.trim()).filter(Boolean),
          principalIds: splitValues.principalIds.split(/[\n,]/).map((value) => value.trim()).filter(Boolean),
          reason: splitValues.reason,
        },
      });
      setReloadKey((value) => value + 1);
    } catch (error) {
      setMutationError(error);
    } finally {
      setPendingAction(null);
      setConfirmation(null);
    }
  }

  async function confirmAction() {
    if (!confirmation) return;
    if (confirmation.kind === 'lifecycle') {
      await executeLifecycleAction(confirmation.action);
      return;
    }
    if (confirmation.kind === 'merge') {
      await executeMerge();
      return;
    }
    await executeSplit();
  }

  const currentIdentity = state.status === 'ready' ? state.value.identity : null;
  const confirmCopy = confirmationCopy(confirmation, currentIdentity?.displayName ?? 'this Identity');

  return (
    <Page>
      <Button variant="text" onClick={onBack} sx={{ justifySelf: 'start' }}>← Identities</Button>
      {state.status === 'loading' && <StatePanel>Loading authoritative Identity…</StatePanel>}
      {state.status === 'error' && <ErrorPanel error={state.error} onRetry={() => setReloadKey((value) => value + 1)} />}
      {state.status === 'ready' && (
        <>
          <PageHeader
            eyebrow="Identity · authoritative state"
            title={state.value.identity.displayName}
            description={<span className="mono">{state.value.identity.id}</span>}
            actions={<StatusChip label={state.value.identity.lifecycleState} color={lifecycleChipColor(state.value.identity.lifecycleState)} />}
          />

          {mutationError && <ErrorPanel error={mutationError} onRetry={() => setReloadKey((value) => value + 1)} />}

          <Surface title="Identity details">
            <dl className="detail-grid">
              <div><dt>Type</dt><dd>{state.value.identity.type}</dd></div>
              <div><dt>Revision</dt><dd>{state.value.identity.revision}</dd></div>
              <div><dt>Created</dt><dd>{new Date(state.value.identity.createdAt).toLocaleString()}</dd></div>
              <div><dt>Updated</dt><dd>{new Date(state.value.identity.updatedAt).toLocaleString()}</dd></div>
            </dl>
          </Surface>

          <Surface
            title="Display name"
            description="Non-lifecycle metadata uses PATCH with ETag/If-Match."
            actions={(
              <Button
                variant="outlined"
                disabled={pendingAction !== null || !displayName.trim()}
                onClick={() => void updateName()}
              >
                {pendingAction === 'rename' ? 'Saving…' : 'Save name'}
              </Button>
            )}
          >
            <TextInput label="Display name" value={displayName} onChange={(event) => setDisplayName(event.target.value)} />
          </Surface>

          <Surface title="Lifecycle actions" description="Explicit semantic commands; provider-side realization remains asynchronous and separate.">
            <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1 }}>
              {lifecycleActions(state.value.identity).map((action) => (
                <Button
                  key={action}
                  variant="outlined"
                  color={action === 'decommission' ? 'error' : 'primary'}
                  disabled={pendingAction !== null}
                  onClick={() => requestLifecycleAction(action)}
                >
                  {pendingAction === action ? 'Working…' : action[0].toUpperCase() + action.slice(1)}
                </Button>
              ))}
              {lifecycleActions(state.value.identity).length === 0 && (
                <Typography color="text.secondary" variant="body2">No lifecycle commands are available from DECOMMISSIONED.</Typography>
              )}
            </Box>
          </Surface>

          <Surface
            title="Canonical attributes"
            description="Governed effective values only; classification authorization and redaction are enforced by the server."
          >
            {canonical === null && <StatePanel severity="warning">Canonical attributes are unavailable or not authorized.</StatePanel>}
            {canonical?.length === 0 && <StatePanel>No canonical attributes.</StatePanel>}
            {canonical && canonical.length > 0 && (
              <DataTable minWidth={720}>
                <TableHead>
                  <TableRow><TableCell>Key</TableCell><TableCell>Status</TableCell><TableCell>Classification</TableCell><TableCell>Value</TableCell></TableRow>
                </TableHead>
                <TableBody>
                  {canonical.map((attribute, index) => (
                    <TableRow key={String(attribute.definitionId ?? index)} hover>
                      <TableCell>{String(attribute.key ?? '—')}</TableCell>
                      <TableCell>{String(attribute.resolutionStatus ?? '—')}</TableCell>
                      <TableCell>{String(attribute.classification ?? '—')}</TableCell>
                      <TableCell>{attribute.valueRedacted === true ? 'Redacted' : attribute.value ? '[authorized value]' : '—'}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </DataTable>
            )}
          </Surface>

          <Surface title="Merge administrative correction" description="Moves only Identity-owned relationships and permanently decommissions the absorbed Identity.">
            <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' }, gap: 1.5 }}>
              <TextInput label="Absorbed Identity ID" value={mergeValues.absorbedIdentityId} onChange={(event) => setMergeValues((value) => ({ ...value, absorbedIdentityId: event.target.value }))} />
              <TextInput label="Absorbed revision" type="number" value={mergeValues.absorbedRevision} onChange={(event) => setMergeValues((value) => ({ ...value, absorbedRevision: event.target.value }))} />
              <TextInput label="Reason" multiline minRows={3} value={mergeValues.reason} onChange={(event) => setMergeValues((value) => ({ ...value, reason: event.target.value }))} sx={{ gridColumn: { md: '1 / -1' } }} />
            </Box>
            <Button
              color="error"
              variant="outlined"
              sx={{ mt: 2 }}
              disabled={pendingAction !== null || !mergeValues.absorbedIdentityId || !mergeValues.absorbedRevision || !mergeValues.reason.trim()}
              onClick={() => setConfirmation({ kind: 'merge' })}
            >
              Merge Identity
            </Button>
          </Surface>

          <Surface title="Split administrative correction" description="Creates a new PENDING Identity and moves only explicitly selected Identity-owned relationships.">
            <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' }, gap: 1.5 }}>
              <TextInput label="New display name" value={splitValues.newDisplayName} onChange={(event) => setSplitValues((value) => ({ ...value, newDisplayName: event.target.value }))} />
              <TextInput label="Reason" value={splitValues.reason} onChange={(event) => setSplitValues((value) => ({ ...value, reason: event.target.value }))} />
              <TextInput label="Source record IDs" multiline minRows={3} placeholder="one UUID per line" value={splitValues.sourceRecordIds} onChange={(event) => setSplitValues((value) => ({ ...value, sourceRecordIds: event.target.value }))} />
              <TextInput label="Principal IDs" multiline minRows={3} placeholder="one UUID per line" value={splitValues.principalIds} onChange={(event) => setSplitValues((value) => ({ ...value, principalIds: event.target.value }))} />
            </Box>
            <Button
              color="error"
              variant="outlined"
              sx={{ mt: 2 }}
              disabled={pendingAction !== null || !splitValues.newDisplayName.trim() || !splitValues.reason.trim()}
              onClick={() => setConfirmation({ kind: 'split' })}
            >
              Split Identity
            </Button>
          </Surface>

          <ConfirmDialog
            open={confirmation !== null}
            title={confirmCopy.title}
            confirmLabel={confirmCopy.label}
            destructive
            busy={pendingAction !== null}
            onCancel={() => setConfirmation(null)}
            onConfirm={() => void confirmAction()}
          >
            {confirmCopy.body}
          </ConfirmDialog>
        </>
      )}
    </Page>
  );
}

function Identities({ selectedId, onSelect }: { selectedId?: string; onSelect: (id?: string) => void }) {
  const [pages, setPages] = useState<IdentityPage[]>([]);
  const [state, setState] = useState<'loading' | 'ready' | 'error'>('loading');
  const [error, setError] = useState<unknown>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [create, setCreate] = useState({ type: 'PERSON', lifecycleState: 'PENDING', displayName: '' });
  const nextCursor = pages.at(-1)?.nextCursor ?? null;
  const identities = useMemo(() => pages.flatMap((page) => page.items), [pages]);

  useEffect(() => {
    if (selectedId) return;
    const controller = new AbortController();
    setState('loading');
    setPages([]);
    listIdentities(undefined, controller.signal)
      .then((page) => {
        setPages([page]);
        setState('ready');
      })
      .catch((cause: unknown) => {
        if (cause instanceof DOMException && cause.name === 'AbortError') return;
        setError(cause);
        setState('error');
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
      setError(cause);
      setState('error');
    }
  }

  async function createIdentity() {
    try {
      await apiRequest(`${V1_ROOT}/identities`, {
        method: 'POST',
        idempotent: true,
        body: {
          type: create.type,
          profile: { kind: create.type },
          lifecycleState: create.lifecycleState,
          displayName: create.displayName,
        },
      });
      setCreate({ type: 'PERSON', lifecycleState: 'PENDING', displayName: '' });
      setReloadKey((value) => value + 1);
    } catch (cause) {
      setError(cause);
      setState('error');
    }
  }

  if (selectedId) return <IdentityDetail identityId={selectedId} onBack={() => onSelect()} />;

  return (
    <Page>
      <PageHeader
        eyebrow="Identity"
        title="Identities"
        description="Governed subjects. Principal technical accounts and provider observations remain distinct concepts."
      />

      <Surface title="Create Identity" description="Create a governed subject with explicit type and initial lifecycle state.">
        <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: 'repeat(3, minmax(0, 1fr))' }, gap: 1.5 }}>
          <label>
            <Typography component="span" variant="body2" sx={{ display: 'block', mb: 0.5, fontWeight: 700 }}>Type</Typography>
            <select value={create.type} onChange={(event) => setCreate((value) => ({ ...value, type: event.target.value }))}>
              <option>PERSON</option><option>SERVICE</option><option>WORKLOAD</option>
            </select>
          </label>
          <label>
            <Typography component="span" variant="body2" sx={{ display: 'block', mb: 0.5, fontWeight: 700 }}>Initial lifecycle</Typography>
            <select value={create.lifecycleState} onChange={(event) => setCreate((value) => ({ ...value, lifecycleState: event.target.value }))}>
              <option>PENDING</option><option>ACTIVE</option><option>SUSPENDED</option><option>INACTIVE</option>
            </select>
          </label>
          <TextInput label="Display name" value={create.displayName} onChange={(event) => setCreate((value) => ({ ...value, displayName: event.target.value }))} />
        </Box>
        <Button variant="contained" sx={{ mt: 2 }} disabled={!create.displayName.trim()} onClick={() => void createIdentity()}>Create Identity</Button>
      </Surface>

      {state === 'loading' && identities.length === 0 && <StatePanel>Loading identities…</StatePanel>}
      {state === 'error' && <ErrorPanel error={error} onRetry={() => setReloadKey((value) => value + 1)} />}
      {state !== 'error' && identities.length === 0 && state === 'ready' && <StatePanel>No governed identities found.</StatePanel>}

      {identities.length > 0 && (
        <Surface title="Governed identities" description="Authoritative Identity resources; technical Principals remain separate.">
          <DataTable minWidth={820}>
            <TableHead>
              <TableRow><TableCell>Name</TableCell><TableCell>Type</TableCell><TableCell>Lifecycle</TableCell><TableCell>Revision</TableCell><TableCell align="right">Open</TableCell></TableRow>
            </TableHead>
            <TableBody>
              {identities.map((identity) => (
                <TableRow key={identity.id} hover>
                  <TableCell>
                    <Typography variant="body2" sx={{ fontWeight: 700 }}>{identity.displayName}</Typography>
                    <Typography variant="caption" color="text.secondary" className="mono">{identity.id}</Typography>
                  </TableCell>
                  <TableCell>{identity.type}</TableCell>
                  <TableCell><StatusChip label={identity.lifecycleState} color={lifecycleChipColor(identity.lifecycleState)} /></TableCell>
                  <TableCell>{identity.revision}</TableCell>
                  <TableCell align="right"><Button size="small" variant="text" onClick={() => onSelect(identity.id)}>View</Button></TableCell>
                </TableRow>
              ))}
            </TableBody>
          </DataTable>
        </Surface>
      )}

      {nextCursor && (
        <Button variant="outlined" disabled={state === 'loading'} onClick={() => void loadMore()} sx={{ justifySelf: 'start' }}>
          {state === 'loading' ? 'Loading…' : 'Load more'}
        </Button>
      )}
    </Page>
  );
}

type RouteKey = ConsoleRouteId | 'not-found';

export function App() {
  const pathname = useConsolePathname();
  const [system, setSystem] = useState<LoadState<SystemInfo>>({ status: 'loading' });
  const [authority, setAuthority] = useState<LoadState<CurrentAdministrativeAuthority>>({ status: 'loading' });

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

  useEffect(() => {
    const controller = new AbortController();
    getCurrentAdministrativeAuthority(controller.signal)
      .then((value) => setAuthority({ status: 'ready', value }))
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return;
        setAuthority({ status: 'error', error });
      });
    return () => controller.abort();
  }, [pathname]);

  const routeMatch = matchConsoleRoute(pathname);
  const route: RouteKey = routeMatch?.route.id ?? 'not-found';
  const identityId = routeMatch?.params.identityId;
  function go(path: string) { navigateConsole(path); }

  return (
    <>
      {route === 'overview' && <Overview system={system} authority={authority} />}
      {route === 'identities' && <Identities selectedId={identityId} onSelect={(id) => go(id ? identityDetailPath(id) : '/identities')} />}
      {route === 'principals' && <PrincipalsPage />}
      {route === 'catalog' && <CatalogPage />}
      {route === 'access' && <AccessPage />}
      {route === 'requests' && <RequestsPage />}
      {route === 'reviews' && <ReviewsPage />}
      {route === 'policies' && <PoliciesPage />}
      {route === 'credentials' && <CredentialsPage />}
      {route === 'integrations' && <IntegrationsPage />}
      {route === 'administration' && <AdministrationPage />}
      {route === 'audit' && <AuditPage />}
      {route === 'not-found' && (
        <Page>
          <PageHeader eyebrow="404" title="Page not found" description="The requested Console route is not available." />
          <Button variant="outlined" onClick={() => go('/')} sx={{ justifySelf: 'start' }}>Return to overview</Button>
        </Page>
      )}
    </>
  );
}