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
  Modal,
  Page,
  PageHeader,
  PageToolbar,
  SelectInput,
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
      state="error"
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

function lifecycleChipColor(state: IdentityResource['lifecycleState']): 'success' | 'warning' | 'default' | 'info' | 'error' {
  if (state === 'ACTIVE') return 'success';
  if (state === 'SUSPENDED') return 'warning';
  if (state === 'PENDING') return 'info';
  if (state === 'DECOMMISSIONED') return 'error';
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
  if (confirmation.action === 'suspend') {
    return {
      title: `Suspend ${identityName} and reduce access?`,
      body: 'Suspension makes this Identity access-ineligible immediately. Semantic EffectiveAccess becomes absent and Access begins durable reduction of non-terminal AccessAssignments. Reactivation does not restore assignments that were already terminated, and provider-side removal remains asynchronous.',
      label: 'Suspend Identity',
    };
  }
  if (confirmation.action === 'activate') {
    return {
      title: `Activate ${identityName}?`,
      body: 'Activation makes this governed Identity access-eligible. It does not resurrect AccessAssignments that were already terminated, and any downstream lifecycle-access reconciliation remains separately governed and asynchronous.',
      label: 'Activate Identity',
    };
  }
  return {
    title: `Deactivate ${identityName} and reduce access?`,
    body: 'Deactivation changes the governed Identity to INACTIVE. If the Identity is currently ACTIVE, semantic access eligibility ends immediately and Access reduction proceeds asynchronously; provider realization remains separate.',
    label: 'Deactivate Identity',
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

function formatDateTime(value: string): string {
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString([], {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
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
    setConfirmation({ kind: 'lifecycle', action });
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
    <Page width="wide">
      <Button variant="text" onClick={onBack} sx={{ justifySelf: 'start' }}>← Identities</Button>
      {state.status === 'loading' && <StatePanel state="loading" title="Loading governed Identity">Reading the latest authoritative Identity revision…</StatePanel>}
      {state.status === 'error' && <ErrorPanel error={state.error} onRetry={() => setReloadKey((value) => value + 1)} />}
      {state.status === 'ready' && (
        <>
          <PageHeader
            eyebrow="Identity · governed subject"
            title={state.value.identity.displayName}
            description={<><span className="mono">{state.value.identity.id}</span> · Updated {formatDateTime(state.value.identity.updatedAt)}</>}
            actions={(
              <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap', alignItems: 'center' }}>
                <StatusChip label={state.value.identity.type} />
                <StatusChip label={state.value.identity.lifecycleState} color={lifecycleChipColor(state.value.identity.lifecycleState)} />
              </Box>
            )}
          />

          <StatePanel state="informational" title="Governed subject, not a technical account">
            This Identity is authoritative governed-subject state. Principals are separate technical representations/accounts, and provider observations are not Identity authority.
          </StatePanel>

          {mutationError && <ErrorPanel error={mutationError} onRetry={() => setReloadKey((value) => value + 1)} />}

          <Surface title="Authoritative Identity" description="Current governed-subject state. Revision remains the concurrency guard for mutations.">
            <Box component="dl" sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, minmax(0, 1fr))', lg: 'repeat(5, minmax(0, 1fr))' }, gap: 1.25, m: 0 }}>
              {[
                ['Identity type', state.value.identity.type],
                ['Lifecycle', state.value.identity.lifecycleState],
                ['Revision', String(state.value.identity.revision)],
                ['Created', formatDateTime(state.value.identity.createdAt)],
                ['Updated', formatDateTime(state.value.identity.updatedAt)],
              ].map(([label, value]) => (
                <Box key={label} sx={{ p: 1.5, border: 1, borderColor: 'divider', borderRadius: 1, bgcolor: 'background.default' }}>
                  <Typography component="dt" variant="caption" color="text.secondary" sx={{ textTransform: 'uppercase', fontWeight: 800, letterSpacing: '.05em' }}>{label}</Typography>
                  <Typography component="dd" variant="body2" sx={{ m: 0, mt: 0.5, fontWeight: 700 }}>{value}</Typography>
                </Box>
              ))}
            </Box>
          </Surface>

          <Surface
            title="Display name"
            description="Metadata maintenance uses the current ETag/If-Match revision guard and causal idempotency."
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

          <Surface title="Lifecycle operations" description="Explicit semantic Identity commands. Provider fulfillment and downstream access convergence remain separate.">
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
                <StatePanel state="informational" title="Terminal lifecycle state">DECOMMISSIONED is terminal; no lifecycle commands are available.</StatePanel>
              )}
            </Box>
          </Surface>

          <Surface
            title="Canonical effective values"
            description="Governed canonical resolution state only. Value disclosure remains independently authorized for the exact classification."
          >
            {canonicalState.status === 'loading' && <StatePanel state="loading" title="Loading canonical attributes">Reading governed resolution metadata and authorized effective values…</StatePanel>}
            {canonicalState.status === 'error' && <ErrorPanel error={canonicalState.error} onRetry={() => setReloadKey((value) => value + 1)} />}
            {canonicalState.status === 'ready' && canonicalState.value.length === 0 && <StatePanel state="empty" title="No canonical attributes">No governed canonical attribute state is currently available for this Identity.</StatePanel>}
            {canonicalState.status === 'ready' && canonicalState.value.length > 0 && (
              <DataTable minWidth={920} ariaLabel="Canonical effective values">
                <TableHead>
                  <TableRow>
                    <TableCell component="th" scope="col">Attribute</TableCell>
                    <TableCell component="th" scope="col">Resolution</TableCell>
                    <TableCell component="th" scope="col">Classification</TableCell>
                    <TableCell component="th" scope="col">Shape</TableCell>
                    <TableCell component="th" scope="col">Effective value</TableCell>
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
            <StatePanel state="warning" title="Permanent governed-subject correction">
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
            <StatePanel state="warning" title="Explicit relationship selection required">
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
            destructive={confirmation?.kind !== 'lifecycle' || confirmation?.action !== 'activate'}
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
  const [createOpen, setCreateOpen] = useState(false);
  const [lookupOpen, setLookupOpen] = useState(false);
  const [identityLookup, setIdentityLookup] = useState('');
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
      setCreateOpen(false);
      setReloadKey((value) => value + 1);
    } catch (cause) {
      setCreateError(cause);
    } finally {
      setCreating(false);
    }
  }

  function openExactIdentity() {
    const identityId = identityLookup.trim();
    if (!identityId) return;
    setLookupOpen(false);
    onSelect(identityId);
  }

  if (selectedId) return <IdentityDetail identityId={selectedId} onBack={() => onSelect()} />;

  return (
    <Page width="wide">
      <PageHeader
        eyebrow="Identity · governed subjects"
        title="Identities"
        description="Find and investigate canonical governed subjects. Technical accounts are Principals and remain separate from Identity authority."
        actions={(
          <Button variant="contained" onClick={() => setCreateOpen(true)}>
            + Create Identity
          </Button>
        )}
      />

      <Surface sx={{ p: { xs: 1.5, md: 2 } }}>
        <PageToolbar
          leading={(
            <TextInput
              label="Search display name"
              placeholder="Server-backed search coming in the bounded query contract"
              disabled
              size="small"
              sx={{ width: { xs: '100%', sm: 320 } }}
            />
          )}
          sx={{ alignItems: { sm: 'flex-start' } }}
        >
          <SelectInput
            label="Type"
            value=""
            emptyLabel="All types"
            options={['PERSON', 'SERVICE', 'WORKLOAD']}
            disabled
            size="small"
            sx={{ minWidth: { xs: '100%', sm: 150 } }}
          />
          <SelectInput
            label="Lifecycle"
            value=""
            emptyLabel="All lifecycles"
            options={['PENDING', 'ACTIVE', 'SUSPENDED', 'INACTIVE', 'DECOMMISSIONED']}
            disabled
            size="small"
            sx={{ minWidth: { xs: '100%', sm: 180 } }}
          />
          <Button variant="outlined" onClick={() => setLookupOpen((value) => !value)}>
            Open by Identity ID
          </Button>
          <Button variant="text" disabled>Clear filters</Button>
        </PageToolbar>

        {lookupOpen ? (
          <Box sx={{ mt: 1.5, pt: 1.5, borderTop: 1, borderColor: 'divider', display: 'flex', flexDirection: { xs: 'column', sm: 'row' }, gap: 1, alignItems: { sm: 'center' } }}>
            <TextInput
              autoFocus
              label="Exact Identity ID"
              value={identityLookup}
              size="small"
              onChange={(event) => setIdentityLookup(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter') openExactIdentity();
              }}
              sx={{ flex: '1 1 28rem', maxWidth: 520 }}
            />
            <Button variant="contained" disabled={!identityLookup.trim()} onClick={openExactIdentity}>Open Identity</Button>
            <Typography variant="caption" color="text.secondary">
              Direct resource lookup remains server-authorized and does not enumerate the collection.
            </Typography>
          </Box>
        ) : null}
      </Surface>

      <Typography variant="caption" color="text.secondary" sx={{ mt: -1.5 }}>
        Display-name search and collection filters are shown in their final layout but stay disabled until the accepted server-side filter contract is implemented.
      </Typography>

      {state === 'loading' && identities.length === 0 ? (
        <StatePanel state="loading" title="Loading governed identities">Reading authoritative Identity resources…</StatePanel>
      ) : null}
      {state === 'error' ? <ErrorPanel error={error} onRetry={() => setReloadKey((value) => value + 1)} /> : null}
      {state === 'ready' && identities.length === 0 ? (
        <StatePanel state="empty" title="No governed identities">No Identity resources are currently visible to your server-authorized administrative context.</StatePanel>
      ) : null}

      {identities.length > 0 ? (
        <Surface
          title="Governed identities"
          description="Authoritative Identity resources. Lifecycle is governed-subject state; technical Principal accounts remain separate."
          actions={<Typography variant="caption" color="text.secondary">Showing {identities.length} identities</Typography>}
          sx={{ p: 0, '& > div:first-of-type': { px: { xs: 2, md: 2.5 }, pt: { xs: 2, md: 2.5 }, mb: 0 } }}
        >
          <DataTable minWidth={780} ariaLabel="Governed identities">
            <TableHead>
              <TableRow>
                <TableCell component="th" scope="col">Governed subject</TableCell>
                <TableCell component="th" scope="col">Type</TableCell>
                <TableCell component="th" scope="col">Lifecycle</TableCell>
                <TableCell component="th" scope="col">Updated</TableCell>
                <TableCell component="th" scope="col" align="right">Open</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {identities.map((identity) => (
                <TableRow key={identity.id} hover>
                  <TableCell>
                    <Typography variant="body2" sx={{ fontWeight: 800, color: 'text.primary' }}>{identity.displayName}</Typography>
                    <Typography variant="caption" color="text.secondary" className="mono">{identity.id}</Typography>
                  </TableCell>
                  <TableCell>
                    <StatusChip label={identity.type} />
                  </TableCell>
                  <TableCell>
                    <StatusChip label={identity.lifecycleState} color={lifecycleChipColor(identity.lifecycleState)} />
                  </TableCell>
                  <TableCell>
                    <Typography variant="body2" color="text.secondary">{formatDateTime(identity.updatedAt)}</Typography>
                  </TableCell>
                  <TableCell align="right">
                    <Button size="small" variant="outlined" onClick={() => onSelect(identity.id)} aria-label={`Open Identity ${identity.displayName}`}>
                      View
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </DataTable>

          <Box sx={{ px: { xs: 2, md: 2.5 }, py: 1.75, borderTop: 1, borderColor: 'divider', display: 'flex', justifyContent: 'space-between', gap: 1, alignItems: 'center', flexWrap: 'wrap' }}>
            <Typography variant="caption" color="text.secondary">Showing {identities.length} identities</Typography>
            {nextCursor ? (
              <Button variant="outlined" disabled={state === 'loading'} onClick={() => void loadMore()}>
                {state === 'loading' ? 'Loading…' : 'Load more identities'}
              </Button>
            ) : (
              <Typography variant="caption" color="text.secondary">End of visible collection</Typography>
            )}
          </Box>
        </Surface>
      ) : null}

      {state === 'loading' && identities.length > 0 ? (
        <StatePanel state="loading" title="Loading next page">Existing governed identities remain visible while the next signed cursor page loads.</StatePanel>
      ) : null}

      <Modal
        open={createOpen}
        title="Create governed Identity"
        description="Create one typed governed subject. Tenant, actor and administrative authority remain server-derived."
        busy={creating}
        onClose={() => {
          if (!creating) {
            setCreateOpen(false);
            setCreateError(null);
          }
        }}
      >
        <Box sx={{ display: 'grid', gap: 1.5, pt: 0.5 }}>
          {createError ? <ErrorPanel error={createError} /> : null}
          <SelectInput
            label="Identity type"
            value={create.type}
            options={['PERSON', 'SERVICE', 'WORKLOAD']}
            onValueChange={(value) => setCreate((current) => ({ ...current, type: String(value || 'PERSON') }))}
          />
          <SelectInput
            label="Initial lifecycle"
            value={create.lifecycleState}
            options={['PENDING', 'ACTIVE', 'SUSPENDED', 'INACTIVE']}
            onValueChange={(value) => setCreate((current) => ({ ...current, lifecycleState: String(value || 'PENDING') }))}
          />
          <TextInput
            label="Display name"
            required
            autoFocus
            value={create.displayName}
            onChange={(event) => setCreate((current) => ({ ...current, displayName: event.target.value }))}
          />
          <StatePanel state="informational" title="Identity is the governed subject">
            Creating an Identity does not register a Principal, create provider state, grant access or establish a Credential.
          </StatePanel>
          <Box sx={{ display: 'flex', justifyContent: 'flex-end', gap: 1, flexWrap: 'wrap', pt: 1 }}>
            <Button variant="text" disabled={creating} onClick={() => { setCreateOpen(false); setCreateError(null); }}>Cancel</Button>
            <Button variant="contained" disabled={creating || !create.displayName.trim()} onClick={() => void createIdentity()}>
              {creating ? 'Creating…' : 'Create Identity'}
            </Button>
          </Box>
        </Box>
      </Modal>
    </Page>
  );
}
