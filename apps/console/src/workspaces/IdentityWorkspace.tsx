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
import { navigateConsole } from '../consoleNavigation';
import {
  Box,
  Button,
  ConfirmDialog,
  Modal,
  Page,
  SelectInput,
  StatePanel,
  StatusChip,
  Tab,
  TabPanel,
  Tabs,
  TextInput,
  Typography,
  getTabA11yProps,
} from '../ui';
import '../identity-workspace.css';

type LoadState<T> =
  | { status: 'loading' }
  | { status: 'ready'; value: T }
  | { status: 'error'; error: unknown };

type IdentityConfirmation =
  | { kind: 'lifecycle'; action: IdentityLifecycleAction }
  | { kind: 'merge' }
  | { kind: 'split' }
  | null;

type IdentityDetailView = 'overview' | 'profile' | 'principals' | 'access' | 'governance' | 'evidence';
type CorrectionMode = 'merge' | 'split';

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

function resolutionChipColor(state: string): 'success' | 'warning' | 'default' | 'info' | 'error' {
  if (state === 'RESOLVED') return 'success';
  if (state === 'OVERRIDDEN') return 'info';
  if (state === 'CONFLICT') return 'error';
  if (state === 'UNRESOLVED') return 'warning';
  return 'default';
}

function confirmationCopy(confirmation: IdentityConfirmation, identityName: string) {
  if (!confirmation) return { title: '', body: '', label: 'Confirm' };
  if (confirmation.kind === 'merge') {
    return {
      title: 'Merge governed Identities?',
      body: 'The current Identity remains the survivor. The absorbed Identity is permanently decommissioned, and only current Identity-owned source links and Principals move. AccessAssignments, Governance authority and other foreign-capability state do not move implicitly.',
      label: 'Merge Identity',
    };
  }
  if (confirmation.kind === 'split') {
    return {
      title: 'Split governed Identity?',
      body: 'A new same-type PENDING Identity is created. Only explicitly selected current source records and Principals move. A split never copies access or governance authority.',
      label: 'Split Identity',
    };
  }
  if (confirmation.action === 'decommission') {
    return {
      title: 'Decommission Identity?',
      body: `Decommissioning ${identityName} is terminal. It ends governed-subject eligibility but does not synchronously delete Principals, Credentials, provider accounts or historical evidence.`,
      label: 'Decommission',
    };
  }
  if (confirmation.action === 'suspend') {
    return {
      title: `Suspend ${identityName} and reduce access?`,
      body: 'Suspension makes this Identity access-ineligible immediately. Semantic EffectiveAccess becomes absent and Access begins durable reduction of non-terminal AccessAssignments. Reactivation does not restore assignments already terminated, and provider removal remains asynchronous.',
      label: 'Suspend and reduce access',
    };
  }
  if (confirmation.action === 'activate') {
    return {
      title: `Activate ${identityName}?`,
      body: 'Activation makes this governed Identity access-eligible. It does not resurrect terminated AccessAssignments; any new lifecycle-driven access still requires current governed evaluation.',
      label: 'Activate Identity',
    };
  }
  return {
    title: `Deactivate ${identityName} and reduce access?`,
    body: 'Deactivation changes the governed Identity to INACTIVE. If currently ACTIVE, semantic access eligibility ends immediately and Access reduction proceeds asynchronously. Provider realization remains separate.',
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

function shortId(value: string): string {
  if (value.length <= 16) return value;
  return `${value.slice(0, 8)}…${value.slice(-6)}`;
}

function UnsupportedSubjectView({
  title,
  detail,
  actionLabel,
  path,
}: {
  title: string;
  detail: string;
  actionLabel: string;
  path: string;
}) {
  return (
    <div className="identity-section-state">
      <strong>{title}</strong>
      <span>{detail}</span>
      <div className="identity-section-state-actions">
        <Button variant="outlined" onClick={() => navigateConsole(path)}>{actionLabel}</Button>
      </div>
    </div>
  );
}

function IdentityDetail({ identityId, onBack }: { identityId: string; onBack: () => void }) {
  const [reloadKey, setReloadKey] = useState(0);
  const [view, setView] = useState<IdentityDetailView>('overview');
  const [state, setState] = useState<LoadState<{ identity: IdentityResource; etag: string }>>({ status: 'loading' });
  const [canonicalState, setCanonicalState] = useState<LoadState<CanonicalAttributeView[]>>({ status: 'loading' });
  const [mutationError, setMutationError] = useState<unknown>(null);
  const [pendingAction, setPendingAction] = useState<string | null>(null);
  const [confirmation, setConfirmation] = useState<IdentityConfirmation>(null);
  const [editOpen, setEditOpen] = useState(false);
  const [correctionOpen, setCorrectionOpen] = useState(false);
  const [correctionMode, setCorrectionMode] = useState<CorrectionMode>('merge');
  const [displayName, setDisplayName] = useState('');
  const [mergeValues, setMergeValues] = useState({ absorbedIdentityId: '', reason: '' });
  const [mergeTarget, setMergeTarget] = useState<LoadState<IdentityResource> | null>(null);
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
      setEditOpen(false);
    } catch (error) {
      setMutationError(error);
    } finally {
      setPendingAction(null);
    }
  }

  async function loadMergeTarget() {
    const targetId = mergeValues.absorbedIdentityId.trim();
    if (!targetId) return;
    setMergeTarget({ status: 'loading' });
    try {
      const result = await getIdentity(targetId);
      setMergeTarget({ status: 'ready', value: result.data });
    } catch (error) {
      setMergeTarget({ status: 'error', error });
    }
  }

  async function executeMerge() {
    if (state.status !== 'ready' || mergeTarget?.status !== 'ready') return;
    setPendingAction('merge');
    setMutationError(null);
    try {
      await apiRequest(`${V1_ROOT}/identities/${encodeURIComponent(identityId)}:merge`, {
        method: 'POST',
        etag: state.value.etag,
        idempotent: true,
        body: {
          absorbedIdentityId: mergeTarget.value.id,
          absorbedRevision: mergeTarget.value.revision,
          reason: mergeValues.reason,
        },
      });
      setCorrectionOpen(false);
      setMergeTarget(null);
      setMergeValues({ absorbedIdentityId: '', reason: '' });
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
      setCorrectionOpen(false);
      setSplitValues({ newDisplayName: '', sourceRecordIds: '', principalIds: '', reason: '' });
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
  const mergeTargetMatches = mergeTarget?.status === 'ready' && mergeTarget.value.id === mergeValues.absorbedIdentityId.trim();

  if (state.status === 'loading') {
    return <Page width="wide"><div className="identity-workbench"><StatePanel state="loading" title="Loading governed Identity">Reading the latest authoritative Identity revision…</StatePanel></div></Page>;
  }
  if (state.status === 'error') {
    return <Page width="wide"><div className="identity-workbench"><button className="identity-detail-back" type="button" onClick={onBack}>← Identities</button><ErrorPanel error={state.error} onRetry={() => setReloadKey((value) => value + 1)} /></div></Page>;
  }

  const identity = state.value.identity;
  const eligible = identity.lifecycleState === 'ACTIVE';
  const actions = lifecycleActions(identity);

  return (
    <Page width="wide">
      <div className="identity-workbench">
        <button className="identity-detail-back" type="button" onClick={onBack}>← Identities</button>

        <section className="identity-detail-header" aria-labelledby="identity-detail-title">
          <div className="identity-detail-primary">
            <div className="identity-detail-title">
              <span className="identity-detail-eyebrow">Identity · governed subject</span>
              <h1 id="identity-detail-title">{identity.displayName}</h1>
              <div className="identity-detail-id"><span className="mono">{identity.id}</span> · Updated {formatDateTime(identity.updatedAt)}</div>
            </div>
            <div className="identity-detail-actions">
              <Button variant="outlined" onClick={() => setEditOpen(true)}>Edit metadata</Button>
              {actions.map((action) => (
                <Button
                  key={action}
                  variant={action === 'activate' ? 'contained' : 'outlined'}
                  color={action === 'decommission' ? 'error' : 'primary'}
                  disabled={pendingAction !== null}
                  onClick={() => requestLifecycleAction(action)}
                >
                  {pendingAction === action ? 'Working…' : action[0].toUpperCase() + action.slice(1)}
                </Button>
              ))}
              <Button variant="text" color="error" disabled={pendingAction !== null} onClick={() => setCorrectionOpen(true)}>Advanced correction</Button>
            </div>
          </div>

          <div className="identity-detail-badges">
            <StatusChip label={identity.type} />
            <StatusChip label={identity.lifecycleState} color={lifecycleChipColor(identity.lifecycleState)} />
            <Typography variant="caption" color="text.secondary">Revision {identity.revision}</Typography>
          </div>

          <div className={`identity-eligibility${eligible ? '' : ' is-ineligible'}`}>
            <span className="identity-eligibility-icon" aria-hidden="true">{eligible ? '✓' : '!'}</span>
            <div>
              <strong>{eligible ? 'Currently access-eligible' : 'Currently access-ineligible'}</strong>
              <span>{eligible
                ? 'ACTIVE is the only access-eligible Identity lifecycle state. This does not by itself prove an AccessAssignment, EffectiveAccess, Principal, Credential or provider grant.'
                : 'Only ACTIVE is access-eligible. Semantic EffectiveAccess contributes no authority while this Identity is ineligible; Access reduction and provider-side removal may continue asynchronously.'}</span>
            </div>
          </div>
        </section>

        {mutationError ? <ErrorPanel error={mutationError} onRetry={() => setReloadKey((value) => value + 1)} /> : null}

        <section className="identity-tabs-panel" aria-label="Identity 360 workspace">
          <div className="identity-tabs-bar">
            <Tabs
              value={view}
              onChange={(_event, next) => setView(next as IdentityDetailView)}
              aria-label="Identity 360 views"
              sx={{ borderBottom: 0 }}
            >
              <Tab value="overview" label="Overview" {...getTabA11yProps('identity-360', 'overview')} />
              <Tab value="profile" label="Governed profile" {...getTabA11yProps('identity-360', 'profile')} />
              <Tab value="principals" label="Principals" {...getTabA11yProps('identity-360', 'principals')} />
              <Tab value="access" label="Access" {...getTabA11yProps('identity-360', 'access')} />
              <Tab value="governance" label="Governance" {...getTabA11yProps('identity-360', 'governance')} />
              <Tab value="evidence" label="Evidence" {...getTabA11yProps('identity-360', 'evidence')} />
            </Tabs>
          </div>

          <div className="identity-tab-content">
            <TabPanel groupId="identity-360" tabId="overview" active={view === 'overview'}>
              <div className="identity-overview-grid">
                <div className="identity-overview-card">
                  <div className="identity-overview-card-header">
                    <h3>Identity summary</h3>
                    <p>Current authoritative governed-subject state.</p>
                  </div>
                  <dl className="identity-summary-list">
                    <div className="identity-summary-row"><dt>Identity type</dt><dd>{identity.type}</dd></div>
                    <div className="identity-summary-row"><dt>Lifecycle</dt><dd>{identity.lifecycleState}</dd></div>
                    <div className="identity-summary-row"><dt>Access eligibility</dt><dd>{eligible ? 'Eligible' : 'Ineligible'}</dd></div>
                    <div className="identity-summary-row"><dt>Created</dt><dd>{formatDateTime(identity.createdAt)}</dd></div>
                    <div className="identity-summary-row"><dt>Updated</dt><dd>{formatDateTime(identity.updatedAt)}</dd></div>
                    <div className="identity-summary-row"><dt>Identity ID</dt><dd className="mono">{identity.id}</dd></div>
                    <div className="identity-summary-row"><dt>Revision</dt><dd>{identity.revision}</dd></div>
                  </dl>
                </div>

                <div className="identity-overview-card">
                  <div className="identity-overview-card-header">
                    <h3>Investigation</h3>
                    <p>Move from the governed subject into the evidence or projection you need.</p>
                  </div>
                  <div className="identity-investigation-list">
                    {([
                      ['profile', 'Governed profile', 'Canonical resolution, classifications and authorized effective values'],
                      ['principals', 'Principals', 'Technical account representations owned by Identity'],
                      ['access', 'Access', 'Authoritative access intent and derived effective access'],
                      ['governance', 'Governance', 'Review and exception context where current contracts allow it'],
                      ['evidence', 'Evidence', 'Identity-scoped audit and decision evidence'],
                    ] as const).map(([target, label, detail]) => (
                      <button className="identity-investigation-button" type="button" key={target} onClick={() => setView(target)}>
                        <span><strong>{label}</strong><small>{detail}</small></span><em aria-hidden="true">›</em>
                      </button>
                    ))}
                  </div>
                </div>
              </div>
            </TabPanel>

            <TabPanel groupId="identity-360" tabId="profile" active={view === 'profile'}>
              <div className="identity-profile-header">
                <div>
                  <h3>Governed profile</h3>
                  <p>Canonical effective values and resolution state only. Provider/source payloads are not authoritative profile data.</p>
                </div>
                <span className="identity-profile-note">Value disclosure remains independently authorized for the exact canonical classification.</span>
              </div>
              {canonicalState.status === 'loading' ? <StatePanel state="loading" title="Loading canonical attributes">Reading governed resolution metadata and authorized effective values…</StatePanel> : null}
              {canonicalState.status === 'error' ? <ErrorPanel error={canonicalState.error} onRetry={() => setReloadKey((value) => value + 1)} /> : null}
              {canonicalState.status === 'ready' && canonicalState.value.length === 0 ? <StatePanel state="empty" title="No canonical attributes">No governed canonical attribute state is currently available for this Identity.</StatePanel> : null}
              {canonicalState.status === 'ready' && canonicalState.value.length > 0 ? (
                <div className="identity-table-wrap">
                  <table className="identity-table">
                    <caption className="sr-only">Governed canonical effective values</caption>
                    <thead><tr><th>Attribute</th><th>Effective value</th><th>Resolution</th><th>Classification</th><th>Shape</th><th>Value revision</th></tr></thead>
                    <tbody>
                      {canonicalState.value.map((attribute) => {
                        const retained = (attribute.resolutionStatus === 'CONFLICT' || attribute.resolutionStatus === 'UNRESOLVED')
                          && attribute.visibility === 'VALUE' && attribute.hasTrustedValue && Boolean(attribute.values?.length);
                        return (
                          <tr key={attribute.definitionId}>
                            <td><strong>{attribute.key}</strong></td>
                            <td className="identity-effective-value">
                              {canonicalValueCopy(attribute)}
                              {retained ? <span className="identity-retained-value">Retained trusted value · current resolution is {attribute.resolutionStatus}</span> : null}
                            </td>
                            <td><StatusChip label={attribute.visibility === 'REDACTED' ? 'REDACTED' : attribute.resolutionStatus} color={attribute.visibility === 'REDACTED' ? 'default' : resolutionChipColor(attribute.resolutionStatus)} /></td>
                            <td>{attribute.classification}</td>
                            <td>{attribute.type} · {attribute.cardinality}</td>
                            <td>{attribute.valueRevision}</td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              ) : null}
            </TabPanel>

            <TabPanel groupId="identity-360" tabId="principals" active={view === 'principals'}>
              <UnsupportedSubjectView
                title="Subject-scoped Principal query not yet exposed"
                detail="Principal remains Identity-owned technical account state, but the current public collection contract does not provide a safe exact identityId filter. Wyrmgate does not scan or filter an incomplete client-side collection to fake this view."
                actionLabel="Open Principals workspace"
                path="/principals"
              />
            </TabPanel>

            <TabPanel groupId="identity-360" tabId="access" active={view === 'access'}>
              <UnsupportedSubjectView
                title="Full Identity access workbench needs the bounded subject read contract"
                detail="EffectiveAccess already supports subject filtering, but authoritative AccessAssignment discovery does not yet expose the matching bounded identityId collection query. This view stays explicit rather than showing a misleading partial access picture."
                actionLabel="Open Access workspace"
                path="/access"
              />
            </TabPanel>

            <TabPanel groupId="identity-360" tabId="governance" active={view === 'governance'}>
              <UnsupportedSubjectView
                title="Identity-scoped Governance discovery is intentionally incomplete"
                detail="Current Governance request visibility is participant-scoped and the public review/exception contracts do not yet expose every bounded subject query required for an operator-safe Identity 360 view."
                actionLabel="Open Governance workspace"
                path="/policies"
              />
            </TabPanel>

            <TabPanel groupId="identity-360" tabId="evidence" active={view === 'evidence'}>
              <UnsupportedSubjectView
                title="Use Audit for current direct evidence"
                detail="Audit can query direct Identity resource evidence, but that is not an exhaustive cross-capability history. Until the Identity 360 evidence read model is implemented, use the dedicated Audit workbench and keep that distinction visible."
                actionLabel="Open Audit workspace"
                path="/audit"
              />
            </TabPanel>
          </div>
        </section>

        <Modal
          open={editOpen}
          title="Edit Identity metadata"
          description="Display name is the only mutable non-lifecycle Identity metadata field in the current public contract."
          busy={pendingAction === 'rename'}
          onClose={() => {
            if (pendingAction !== 'rename') {
              setDisplayName(identity.displayName);
              setEditOpen(false);
            }
          }}
        >
          <Box sx={{ display: 'grid', gap: 1.5 }}>
            <TextInput label="Display name" required autoFocus value={displayName} onChange={(event) => setDisplayName(event.target.value)} />
            <div className="identity-dialog-actions">
              <Button variant="text" disabled={pendingAction === 'rename'} onClick={() => { setDisplayName(identity.displayName); setEditOpen(false); }}>Cancel</Button>
              <Button variant="contained" disabled={pendingAction === 'rename' || !displayName.trim() || displayName === identity.displayName} onClick={() => void updateName()}>
                {pendingAction === 'rename' ? 'Saving…' : 'Save metadata'}
              </Button>
            </div>
          </Box>
        </Modal>

        <Modal
          open={correctionOpen}
          title="Advanced identity correction"
          description="Merge and split are high-impact governed-subject corrections, not normal account maintenance."
          busy={pendingAction === 'merge' || pendingAction === 'split'}
          onClose={() => {
            if (pendingAction === null) setCorrectionOpen(false);
          }}
        >
          <Box sx={{ display: 'grid', gap: 1.5 }}>
            <div className="identity-dialog-mode" aria-label="Correction type">
              <button type="button" className={correctionMode === 'merge' ? 'active' : ''} onClick={() => setCorrectionMode('merge')}>Merge</button>
              <button type="button" className={correctionMode === 'split' ? 'active' : ''} onClick={() => setCorrectionMode('split')}>Split</button>
            </div>

            {correctionMode === 'merge' ? (
              <>
                <div className="identity-dialog-warning"><strong>Permanent governed-subject correction.</strong> The current Identity remains the survivor. The absorbed Identity becomes DECOMMISSIONED. AccessAssignments and Governance authority do not transfer.</div>
                <div className="identity-form-grid">
                  <TextInput
                    label="Absorbed Identity ID"
                    value={mergeValues.absorbedIdentityId}
                    onChange={(event) => {
                      setMergeValues((value) => ({ ...value, absorbedIdentityId: event.target.value }));
                      setMergeTarget(null);
                    }}
                  />
                  <Box sx={{ alignSelf: 'end' }}><Button variant="outlined" disabled={!mergeValues.absorbedIdentityId.trim() || mergeTarget?.status === 'loading'} onClick={() => void loadMergeTarget()}>{mergeTarget?.status === 'loading' ? 'Checking…' : 'Review target'}</Button></Box>
                  {mergeTarget?.status === 'error' ? <Box className="identity-form-span"><ErrorPanel error={mergeTarget.error} /></Box> : null}
                  {mergeTarget?.status === 'ready' ? (
                    <div className="identity-merge-target identity-form-span">
                      <span><strong>{mergeTarget.value.displayName}</strong><small>{mergeTarget.value.type} · {mergeTarget.value.lifecycleState} · revision {mergeTarget.value.revision}</small></span>
                      <StatusChip label="Absorbed target" color="warning" />
                    </div>
                  ) : null}
                  <TextInput className="identity-form-span" label="Correction reason" multiline minRows={3} value={mergeValues.reason} onChange={(event) => setMergeValues((value) => ({ ...value, reason: event.target.value }))} />
                </div>
                <div className="identity-dialog-actions">
                  <Button variant="text" onClick={() => setCorrectionOpen(false)}>Cancel</Button>
                  <Button color="error" variant="contained" disabled={!mergeTargetMatches || !mergeValues.reason.trim()} onClick={() => setConfirmation({ kind: 'merge' })}>Review merge consequence</Button>
                </div>
              </>
            ) : (
              <>
                <div className="identity-dialog-warning"><strong>Explicit relationship selection required.</strong> A split creates a same-type PENDING Identity. A split never copies access or governance authority. The current API still requires explicit SourceRecord/Principal IDs until the bounded split-candidates query is added.</div>
                <div className="identity-form-grid">
                  <TextInput label="New Identity display name" value={splitValues.newDisplayName} onChange={(event) => setSplitValues((value) => ({ ...value, newDisplayName: event.target.value }))} />
                  <TextInput label="Correction reason" value={splitValues.reason} onChange={(event) => setSplitValues((value) => ({ ...value, reason: event.target.value }))} />
                  <TextInput label="Source record IDs" multiline minRows={3} placeholder="one UUID per line" value={splitValues.sourceRecordIds} onChange={(event) => setSplitValues((value) => ({ ...value, sourceRecordIds: event.target.value }))} />
                  <TextInput label="Principal IDs (technical accounts)" multiline minRows={3} placeholder="one UUID per line" value={splitValues.principalIds} onChange={(event) => setSplitValues((value) => ({ ...value, principalIds: event.target.value }))} />
                </div>
                <div className="identity-dialog-actions">
                  <Button variant="text" onClick={() => setCorrectionOpen(false)}>Cancel</Button>
                  <Button color="error" variant="contained" disabled={!splitValues.newDisplayName.trim() || !splitValues.reason.trim() || !splitSelectionPresent} onClick={() => setConfirmation({ kind: 'split' })}>Review split consequence</Button>
                </div>
              </>
            )}
          </Box>
        </Modal>

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
      </div>
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
      <div className="identity-workbench">
        <header className="identity-header">
          <div className="identity-header-copy">
            <h1>Identities</h1>
            <p>Find and investigate canonical governed subjects. Identity is authoritative governed-subject state; Principals are separate technical representations/accounts and provider observations are not Identity authority.</p>
          </div>
          <div className="identity-header-actions">
            <Button variant="outlined" disabled={state === 'loading'} onClick={() => setReloadKey((value) => value + 1)}>{state === 'loading' ? 'Refreshing…' : 'Refresh'}</Button>
            <Button variant="contained" onClick={() => setCreateOpen(true)}>+ Create Identity</Button>
          </div>
        </header>

        <section className="identity-panel" aria-labelledby="identity-collection-title">
          <div className="identity-panel-header">
            <div>
              <h2 id="identity-collection-title">Governed identities</h2>
              <p>Authoritative Identity collection. Search and type/lifecycle filtering remain server-contract work; this page does not fake incomplete client filtering.</p>
            </div>
            <span className="identity-panel-meta">{identities.length ? `${identities.length} loaded` : 'Authoritative collection'}</span>
          </div>

          <div className="identity-toolbar">
            <div className="identity-toolbar-copy"><strong>Discovery</strong> · Browse the server-ordered collection or open one exact Identity ID as a support path.</div>
            <div className="identity-toolbar-actions">
              <Button variant="outlined" onClick={() => setLookupOpen((value) => !value)}>Open by Identity ID</Button>
            </div>
          </div>

          {lookupOpen ? (
            <Box sx={{ p: 1.5, borderBottom: 1, borderColor: 'divider', display: 'flex', flexDirection: { xs: 'column', sm: 'row' }, gap: 1, alignItems: { sm: 'center' } }}>
              <TextInput
                autoFocus
                label="Exact Identity ID"
                value={identityLookup}
                size="small"
                onChange={(event) => setIdentityLookup(event.target.value)}
                onKeyDown={(event) => { if (event.key === 'Enter') openExactIdentity(); }}
                sx={{ flex: '1 1 28rem', maxWidth: 520 }}
              />
              <Button variant="contained" disabled={!identityLookup.trim()} onClick={openExactIdentity}>Open Identity</Button>
              <Typography variant="caption" color="text.secondary">Direct lookup remains server-authorized and does not enumerate the collection.</Typography>
            </Box>
          ) : null}

          {state === 'loading' && identities.length === 0 ? <Box sx={{ p: 2 }}><StatePanel state="loading" title="Loading governed identities">Reading authoritative Identity resources…</StatePanel></Box> : null}
          {state === 'error' ? <Box sx={{ p: 2 }}><ErrorPanel error={error} onRetry={() => setReloadKey((value) => value + 1)} /></Box> : null}
          {state === 'ready' && identities.length === 0 ? <Box sx={{ p: 2 }}><StatePanel state="empty" title="No governed identities">No Identity resources are currently visible to your server-authorized administrative context.</StatePanel></Box> : null}

          {identities.length > 0 ? (
            <>
              <div className="identity-table-wrap">
                <table className="identity-table">
                  <caption className="sr-only">Governed identities</caption>
                  <thead><tr><th>Governed subject</th><th>Type</th><th>Lifecycle</th><th>Updated</th><th className="identity-row-action"><span className="sr-only">Open</span></th></tr></thead>
                  <tbody>
                    {identities.map((identity) => (
                      <tr key={identity.id}>
                        <td className="identity-subject">
                          <button className="identity-name-button" type="button" onClick={() => onSelect(identity.id)}>
                            <strong>{identity.displayName}</strong><small className="mono">{shortId(identity.id)}</small>
                          </button>
                        </td>
                        <td><StatusChip label={identity.type} /></td>
                        <td><StatusChip label={identity.lifecycleState} color={lifecycleChipColor(identity.lifecycleState)} /></td>
                        <td>{formatDateTime(identity.updatedAt)}</td>
                        <td className="identity-row-action"><Button size="small" variant="text" onClick={() => onSelect(identity.id)} aria-label={`Open Identity ${identity.displayName}`}>Open ›</Button></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              <div className="identity-mobile-list" aria-label="Governed identities mobile list">
                {identities.map((identity) => (
                  <div className="identity-mobile-item" key={identity.id}>
                    <div>
                      <strong>{identity.displayName}</strong><small className="mono">{shortId(identity.id)}</small>
                      <div className="identity-mobile-badges"><StatusChip label={identity.type} /><StatusChip label={identity.lifecycleState} color={lifecycleChipColor(identity.lifecycleState)} /></div>
                    </div>
                    <Button size="small" variant="text" onClick={() => onSelect(identity.id)} aria-label={`Open Identity ${identity.displayName}`}>Open ›</Button>
                  </div>
                ))}
              </div>

              <div className="identity-collection-footer">
                <span>Showing {identities.length} governed identities</span>
                {nextCursor ? <Button variant="outlined" disabled={state === 'loading'} onClick={() => void loadMore()}>{state === 'loading' ? 'Loading…' : 'Load more'}</Button> : <span>End of visible collection</span>}
              </div>
            </>
          ) : null}
        </section>

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
            <div className="identity-form-grid">
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
              <TextInput className="identity-form-span" label="Display name" required autoFocus value={create.displayName} onChange={(event) => setCreate((current) => ({ ...current, displayName: event.target.value }))} />
            </div>
            <div className="identity-dialog-warning">Identity is the governed subject. Creating an Identity does not register a Principal, grant access, create a Credential or establish provider state.</div>
            <div className="identity-dialog-actions">
              <Button variant="text" disabled={creating} onClick={() => { setCreateOpen(false); setCreateError(null); }}>Cancel</Button>
              <Button variant="contained" disabled={creating || !create.displayName.trim()} onClick={() => void createIdentity()}>{creating ? 'Creating…' : 'Create Identity'}</Button>
            </div>
          </Box>
        </Modal>
      </div>
    </Page>
  );
}
