import { useEffect, useMemo, useState } from 'react';
import {
  ApiError,
  apiRequest,
  getIdentity,
  listIdentities,
  transitionIdentity,
  V1_ROOT,
  type IdentityLifecycleAction,
  type IdentityPage,
  type IdentityResource,
} from '../api';
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
} from '../ui';

type LoadState<T> =
  | { status: 'loading' }
  | { status: 'ready'; value: T }
  | { status: 'error'; error: unknown };

type IdentityConfirmation =
  | { kind: 'lifecycle'; action: IdentityLifecycleAction }
  | { kind: 'merge' }
  | { kind: 'split' }
  | null;

type CanonicalValue =
  | { type: 'STRING'; value: string }
  | { type: 'BOOLEAN'; value: boolean }
  | { type: 'INTEGER'; value: number }
  | { type: 'DECIMAL'; value: string }
  | { type: 'DATE'; value: string }
  | { type: 'DATETIME'; value: string }
  | { type: 'ENUM'; key: string };

type CanonicalAttributeView = {
  definitionId: string;
  key: string;
  classification: string;
  type: string;
  cardinality: string;
  resolutionStatus: string;
  valueRevision: number;
  visibility: 'VALUE' | 'REDACTED';
  hasTrustedValue: boolean;
  values?: CanonicalValue[];
};

function formatError(error: unknown): { title: string; detail: string } {
  if (error instanceof ApiError) {
    if (error.status === 401) return { title: 'Authentication required', detail: 'Your authenticated control-plane session is required.' };
    if (error.status === 403) return { title: 'Not authorized', detail: 'The server denied this operation for your current administrative authority.' };
    if (error.status === 404) return { title: 'Identity not found', detail: 'The requested governed Identity is no longer available at this location.' };
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
      action={onRetry ? <Button variant="outlined" size="small" onClick={onRetry}>Refresh</Button> : undefined}
    >
      {copy.detail}
    </StatePanel>
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
      title: 'Merge governed Identities?',
      body: 'The current Identity remains the survivor. The absorbed Identity is permanently decommissioned, and only current Identity-owned source links and Principals are moved. Access assignments and other foreign-capability authority are not merged or copied.',
      label: 'Merge Identity',
    };
  }
  if (confirmation.kind === 'split') {
    return {
      title: 'Split governed Identity?',
      body: 'A new same-type PENDING Identity is created. Only the explicitly listed current source records and Principals move; access and governance authority are not cloned. Verify every selected technical relationship before continuing.',
      label: 'Split Identity',
    };
  }
  if (confirmation.action === 'decommission') {
    return {
      title: 'Decommission Identity?',
      body: `Decommissioning ${identityName} is terminal. This changes authoritative governed-subject lifecycle state; downstream access reduction and provider realization remain separate processes.`,
      label: 'Decommission',
    };
  }
  return {
    title: `Deactivate ${identityName}?`,
    body: 'This changes authoritative governed-subject lifecycle state. Downstream access reduction and provider realization remain separate asynchronous concerns.',
    label: 'Deactivate',
  };
}

function formatCanonicalValue(value: CanonicalValue): string {
  if (value.type === 'ENUM') return value.key;
  if (value.type === 'BOOLEAN') return value.value ? 'true' : 'false';
  return String(value.value);
}

function canonicalValueCopy(attribute: CanonicalAttributeView): string {
  if (attribute.visibility === 'REDACTED') return 'Redacted by classification authority';
  if (!attribute.hasTrustedValue || !attribute.values?.length) return 'No readable trusted value';
  return attribute.values.map(formatCanonicalValue).join(', ');
}

function IdentityDetail({ identityId, onBack }: { identityId: string; onBack: () => void }) {
  const [reloadKey, setReloadKey] = useState(0);
  const [state, setState] = useState<LoadState<{ identity: IdentityResource; etag: string }>>({ status: 'loading' });
  const [canonicalState, setCanonicalState] = useState<LoadState<CanonicalAttributeView[]>>({ status: 'loading' });
  const [mutationError, setMutationError] = useState<unknown>(null);
  const [pendingAction, setPendingAction] = useState<string | null>(null);
  const [confirmation, setConfirmation] = useState<IdentityConfirmation>(null);
  const [displayName, setDisplayName] = useState('');
  const [mergeValues, setMergeValues] = useState({ absorbedIdentityId: '', absorbedRevision: '', reason: '' });
  const [splitValues, setSplitValues] = useState({ newDisplayName: '', sourceRecordIds: '', principalIds: '', reason: '' });

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
    setCanonicalState({ status: 'loading' });
    apiRequest<{ items: CanonicalAttributeView[] }>(`${V1_ROOT}/identities/${encodeURIComponent(identityId)}/canonical-attributes?limit=50`, { signal: controller.signal })
      .then(({ data }) => setCanonicalState({ status: 'ready', value: data.items }))
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return;
        setCanonicalState({ status: 'error', error });
      });
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
  const splitSelectionPresent = splitValues.sourceRecordIds.split(/[\n,]/).some((value) => value.trim())
    || splitValues.principalIds.split(/[\n,]/).some((value) => value.trim());

  return (
    <Page>
      <Button variant="text" onClick={onBack} sx={{ justifySelf: 'start' }}>← Governed identities</Button>
      {state.status === 'loading' && <StatePanel title="Loading governed Identity">Reading the latest authoritative Identity revision…</StatePanel>}
      {state.status === 'error' && <ErrorPanel error={state.error} onRetry={() => setReloadKey((value) => value + 1)} />}
      {state.status === 'ready' && (
        <>
          <PageHeader
            eyebrow="Identity · governed subject"
            title={state.value.identity.displayName}
            description={<span className="mono">{state.value.identity.id}</span>}
            actions={<StatusChip label={state.value.identity.lifecycleState} color={lifecycleChipColor(state.value.identity.lifecycleState)} />}
          />

          <StatePanel severity="info" title="Governed subject, not a technical account">
            This Identity is the authoritative subject for governance. Principals are technical representations/accounts owned by Identity and are managed separately; provider observations are not Identity authority.
          </StatePanel>

          {mutationError && <ErrorPanel error={mutationError} onRetry={() => setReloadKey((value) => value + 1)} />}

          <Surface title="Authoritative Identity" description="Current governed-subject state. Revision is the concurrency guard used by mutation operations.">
            <dl className="detail-grid">
              <div><dt>Identity type</dt><dd>{state.value.identity.type}</dd></div>
              <div><dt>Lifecycle</dt><dd><StatusChip label={state.value.identity.lifecycleState} color={lifecycleChipColor(state.value.identity.lifecycleState)} /></dd></div>
              <div><dt>Revision</dt><dd>{state.value.identity.revision}</dd></div>
              <div><dt>Created</dt><dd>{new Date(state.value.identity.createdAt).toLocaleString()}</dd></div>
              <div><dt>Updated</dt><dd>{new Date(state.value.identity.updatedAt).toLocaleString()}</dd></div>
            </dl>
          </Surface>

          <Surface
            title="Display name"
            description="Metadata maintenance is revision-safe. Saving uses the current ETag/If-Match guard and a causal idempotency key."
            actions={(
              <Button
                variant="outlined"
                disabled={pendingAction !== null || !displayName.trim() || displayName === state.value.identity.displayName}
                onClick={() => void updateName()}
              >
                {pendingAction === 'rename' ? 'Saving…' : 'Save name'}
              </Button>
            )}
          >
            <TextInput label="Display name" value={displayName} onChange={(event) => setDisplayName(event.target.value)} />
          </Surface>

          <Surface title="Lifecycle operations" description="These are explicit semantic Identity commands, never generic status edits. Provider realization and downstream access reduction remain separate concerns.">
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
                <StatePanel title="Terminal lifecycle state">DECOMMISSIONED is terminal; no lifecycle commands are available.</StatePanel>
              )}
            </Box>
          </Surface>

          <Surface
            title="Canonical effective values"
            description="Governed canonical resolution state only. Value disclosure is independently authorized by the server for the exact classification; redaction never broadens browser authority."
          >
            {canonicalState.status === 'loading' && <StatePanel title="Loading canonical attributes">Reading governed resolution metadata and authorized effective values…</StatePanel>}
            {canonicalState.status === 'error' && <ErrorPanel error={canonicalState.error} onRetry={() => setReloadKey((value) => value + 1)} />}
            {canonicalState.status === 'ready' && canonicalState.value.length === 0 && <StatePanel title="No canonical attributes">No governed canonical attribute state is currently available for this Identity.</StatePanel>}
            {canonicalState.status === 'ready' && canonicalState.value.length > 0 && (
              <DataTable minWidth={920}>
                <TableHead>
                  <TableRow>
                    <TableCell>Attribute</TableCell>
                    <TableCell>Resolution</TableCell>
                    <TableCell>Classification</TableCell>
                    <TableCell>Shape</TableCell>
                    <TableCell>Effective value</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {canonicalState.value.map((attribute) => (
                    <TableRow key={attribute.definitionId} hover>
                      <TableCell>
                        <Typography variant="body2" sx={{ fontWeight: 700 }}>{attribute.key}</Typography>
                        <Typography variant="caption" color="text.secondary">value revision {attribute.valueRevision}</Typography>
                      </TableCell>
                      <TableCell>{attribute.resolutionStatus}</TableCell>
                      <TableCell>{attribute.classification}</TableCell>
                      <TableCell>{attribute.type} · {attribute.cardinality}</TableCell>
                      <TableCell>{canonicalValueCopy(attribute)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </DataTable>
            )}
          </Surface>

          <Surface
            title="Merge administrative correction"
            description="Use only when two governed Identities must be corrected into one survivor. This is not generic account merging."
          >
            <StatePanel severity="warning" title="Permanent governed-subject correction">
              The current Identity is the survivor. The absorbed Identity is decommissioned. Only Identity-owned current source links and Principals move; AccessAssignments, Governance authority and other foreign-capability state do not move implicitly.
            </StatePanel>
            <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' }, gap: 1.5, mt: 2 }}>
              <TextInput label="Absorbed Identity ID" value={mergeValues.absorbedIdentityId} onChange={(event) => setMergeValues((value) => ({ ...value, absorbedIdentityId: event.target.value }))} />
              <TextInput label="Absorbed authoritative revision" type="number" value={mergeValues.absorbedRevision} onChange={(event) => setMergeValues((value) => ({ ...value, absorbedRevision: event.target.value }))} />
              <TextInput label="Correction reason" multiline minRows={3} value={mergeValues.reason} onChange={(event) => setMergeValues((value) => ({ ...value, reason: event.target.value }))} sx={{ gridColumn: { md: '1 / -1' } }} />
            </Box>
            <Button
              color="error"
              variant="outlined"
              sx={{ mt: 2 }}
              disabled={pendingAction !== null || !mergeValues.absorbedIdentityId.trim() || Number(mergeValues.absorbedRevision) < 1 || !mergeValues.reason.trim()}
              onClick={() => setConfirmation({ kind: 'merge' })}
            >
              Review merge consequence
            </Button>
          </Surface>

          <Surface
            title="Split administrative correction"
            description="Create one same-type PENDING Identity and explicitly re-home selected current Identity-owned relationships."
          >
            <StatePanel severity="warning" title="Explicit relationship selection required">
              A split never copies access or governance authority. Select at least one current source record or Principal to move, and verify technical Principal IDs separately from the governed Identity itself.
            </StatePanel>
            <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' }, gap: 1.5, mt: 2 }}>
              <TextInput label="New Identity display name" value={splitValues.newDisplayName} onChange={(event) => setSplitValues((value) => ({ ...value, newDisplayName: event.target.value }))} />
              <TextInput label="Correction reason" value={splitValues.reason} onChange={(event) => setSplitValues((value) => ({ ...value, reason: event.target.value }))} />
              <TextInput label="Source record IDs" multiline minRows={3} placeholder="one UUID per line" value={splitValues.sourceRecordIds} onChange={(event) => setSplitValues((value) => ({ ...value, sourceRecordIds: event.target.value }))} />
              <TextInput label="Principal IDs (technical accounts)" multiline minRows={3} placeholder="one UUID per line" value={splitValues.principalIds} onChange={(event) => setSplitValues((value) => ({ ...value, principalIds: event.target.value }))} />
            </Box>
            <Button
              color="error"
              variant="outlined"
              sx={{ mt: 2 }}
              disabled={pendingAction !== null || !splitValues.newDisplayName.trim() || !splitValues.reason.trim() || !splitSelectionPresent}
              onClick={() => setConfirmation({ kind: 'split' })}
            >
              Review split consequence
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

export function IdentityWorkspace({ selectedId, onSelect }: { selectedId?: string; onSelect: (id?: string) => void }) {
  const [pages, setPages] = useState<IdentityPage[]>([]);
  const [state, setState] = useState<'loading' | 'ready' | 'error'>('loading');
  const [error, setError] = useState<unknown>(null);
  const [createError, setCreateError] = useState<unknown>(null);
  const [creating, setCreating] = useState(false);
  const [reloadKey, setReloadKey] = useState(0);
  const [create, setCreate] = useState({ type: 'PERSON', lifecycleState: 'PENDING', displayName: '' });
  const nextCursor = pages.at(-1)?.nextCursor ?? null;
  const identities = useMemo(() => pages.flatMap((page) => page.items), [pages]);

  useEffect(() => {
    if (selectedId) return;
    const controller = new AbortController();
    setState('loading');
    setError(null);
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
    setError(null);
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
    setCreating(true);
    setCreateError(null);
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
      setCreateError(cause);
    } finally {
      setCreating(false);
    }
  }

  if (selectedId) return <IdentityDetail identityId={selectedId} onBack={() => onSelect()} />;

  return (
    <Page>
      <PageHeader
        eyebrow="Identity · governed subjects"
        title="Identities"
        description="Authoritative governed subjects for IAM decisions. Principals are separate technical representations/accounts, and provider observations are not Identity authority."
      />

      <StatePanel severity="info" title="Identity and Principal are deliberately different">
        Manage the governed subject here. Technical accounts live in the Principal workspace and may be correlated to an Identity; do not treat a Principal or provider account as the governed subject itself.
      </StatePanel>

      <Surface title="Create governed Identity" description="Create a typed governed subject. Tenant, actor and administrative authority remain server-derived.">
        {createError && <ErrorPanel error={createError} />}
        <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: 'repeat(3, minmax(0, 1fr))' }, gap: 1.5, mt: createError ? 2 : 0 }}>
          <label>
            <Typography component="span" variant="body2" sx={{ display: 'block', mb: 0.5, fontWeight: 700 }}>Identity type</Typography>
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
        <Button variant="contained" sx={{ mt: 2 }} disabled={creating || !create.displayName.trim()} onClick={() => void createIdentity()}>
          {creating ? 'Creating…' : 'Create Identity'}
        </Button>
      </Surface>

      {state === 'loading' && identities.length === 0 && <StatePanel title="Loading governed identities">Reading authoritative Identity resources…</StatePanel>}
      {state === 'error' && <ErrorPanel error={error} onRetry={() => setReloadKey((value) => value + 1)} />}
      {state === 'ready' && identities.length === 0 && <StatePanel title="No governed identities">No Identity resources are currently visible to your server-authorized administrative context.</StatePanel>}

      {identities.length > 0 && (
        <Surface title="Governed identities" description="Authoritative Identity resources. Lifecycle is business/governance state; technical Principal accounts remain separate.">
          <DataTable minWidth={820}>
            <TableHead>
              <TableRow><TableCell>Governed subject</TableCell><TableCell>Type</TableCell><TableCell>Lifecycle</TableCell><TableCell>Revision</TableCell><TableCell align="right">Action</TableCell></TableRow>
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
                  <TableCell align="right"><Button size="small" variant="text" onClick={() => onSelect(identity.id)}>Open Identity</Button></TableCell>
                </TableRow>
              ))}
            </TableBody>
          </DataTable>
          {state === 'loading' && <StatePanel title="Loading next page">Existing governed identities remain visible while the next cursor page loads.</StatePanel>}
        </Surface>
      )}

      {nextCursor && (
        <Button variant="outlined" disabled={state === 'loading'} onClick={() => void loadMore()} sx={{ justifySelf: 'start' }}>
          {state === 'loading' ? 'Loading…' : 'Load more identities'}
        </Button>
      )}
    </Page>
  );
}
