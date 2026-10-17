import { useEffect, useState, type ReactNode } from 'react';
import {
  ApiError,
  apiRequest,
  getIdentity,
  transitionIdentity,
  V1_ROOT,
  type CurrentAdministrativeAuthority,
  type IdentityLifecycleAction,
  type IdentityResource,
} from '../api';
import { navigateConsole } from '../consoleNavigation';
import {
  Alert,
  Box,
  Button,
  CheckboxInput,
  ConfirmDialog,
  DataTable,
  Modal,
  Page,
  PageHeader,
  StateIndicator,
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
  type StateIndicatorTone,
} from '../ui';

type DetailView = 'overview' | 'profile' | 'principals' | 'access' | 'governance' | 'evidence';
type LoadState<T> = { status: 'idle' } | { status: 'loading' } | { status: 'ready'; value: T } | { status: 'error'; error: unknown };
type PageResult<T> = { items: T[]; nextCursor?: string | null };
type CanonicalValue =
  | { type: 'STRING'; value: string }
  | { type: 'BOOLEAN'; value: boolean }
  | { type: 'INTEGER'; value: number }
  | { type: 'DECIMAL'; value: string }
  | { type: 'DATE'; value: string }
  | { type: 'DATETIME'; value: string }
  | { type: 'ENUM'; key: string };
type CanonicalAttribute = {
  definitionId: string; key: string; classification: string; type: string; cardinality: string;
  resolutionStatus: string; valueRevision: number; visibility: 'VALUE' | 'REDACTED';
  hasTrustedValue: boolean; values?: CanonicalValue[];
};
type PrincipalResource = {
  id: string; identityId?: string | null; applicationTargetId: string; kind: string;
  nativePrincipalKey: string; lifecycleState: string; revision: number; createdAt: string; updatedAt: string;
};
type AssignmentResource = {
  id: string; identityId: string; targetKind: 'ROLE' | 'ENTITLEMENT'; roleId?: string | null; entitlementId?: string | null;
  principalConstraintKind: string; specificPrincipalId?: string | null; provenanceKind: string; provenanceRefId?: string | null;
  lifecycleState: string; validFrom?: string | null; validUntil?: string | null; revision: number; createdAt: string; updatedAt: string;
};
type EffectiveAccess = {
  id: string; identityId: string; entitlementId: string; principalConstraintKey: string;
  supportCount: number; computedAt: string; projectionGeneration: number;
};
type ReviewCampaign = {
  id: string; kind: string; subjectIdentityId: string; reviewerIdentityId: string; snapshotAt: string;
  state: string; generatedItemCount: number; decidedItemCount: number; revision: number; createdAt: string;
  updatedAt: string; activatedAt?: string | null; completedAt?: string | null; failureCode?: string | null;
};
type GovernanceException = {
  id: string; scopeKind: string; subjectIdentityId: string; sodRuleId: string; requesterIdentityId: string;
  businessReason: string; validFrom: string; validUntil: string; lifecycleState: string; approvalCaseId: string;
  revision: number; createdAt: string; updatedAt: string;
};
type AuditRecord = {
  id: string; occurredAt: string; recordedAt: string; actorId?: string | null; actionType: string;
  resourceType: string; resourceId?: string | null; outcome: string; correlationId?: string | null; causationId?: string | null;
};
type SplitCandidates = {
  sourceRecords: { sourceRecordId: string; linkedAt: string }[];
  principals: { principalId: string; applicationTargetId: string; lifecycleState: string; revision: number }[];
  sourceRecordsTruncated: boolean;
  principalsTruncated: boolean;
};
type Confirmation = { kind: 'lifecycle'; action: IdentityLifecycleAction } | { kind: 'merge' } | { kind: 'split' } | null;

const monoSx = { fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace', overflowWrap: 'anywhere' } as const;

function formatDateTime(value?: string | null): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString([], { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}
function shortId(value?: string | null): string {
  if (!value) return '—';
  return value.length <= 20 ? value : `${value.slice(0, 10)}…${value.slice(-6)}`;
}
function canonicalValue(attribute: CanonicalAttribute): string {
  if (attribute.visibility === 'REDACTED') return 'Redacted by classification authority';
  if (!attribute.hasTrustedValue || !attribute.values?.length) return 'No readable trusted value';
  return attribute.values.map((value) => value.type === 'ENUM' ? value.key : value.type === 'BOOLEAN' ? (value.value ? 'true' : 'false') : String(value.value)).join(', ');
}
function stateTone(value: string): StateIndicatorTone {
  if (['ACTIVE', 'APPROVED', 'COMPLETED', 'RESOLVED', 'SUCCESS'].includes(value)) return 'success';
  if (['PENDING', 'DRAFT', 'GENERATING', 'IN_PROGRESS', 'OVERRIDDEN', 'INFO'].includes(value)) return 'info';
  if (['SUSPENDED', 'UNRESOLVED', 'PENDING_APPROVAL'].includes(value)) return 'warning';
  if (['DECOMMISSIONED', 'CONFLICT', 'FAILURE', 'DENIED', 'REJECTED'].includes(value)) return 'danger';
  return 'neutral';
}
function errorCopy(error: unknown): { title: string; detail: string } {
  if (error instanceof ApiError) {
    if (error.status === 401) return { title: 'Authentication required', detail: 'Your authenticated control-plane session is required.' };
    if (error.status === 403) return { title: 'Not authorized', detail: 'Your current administrative authority does not permit this read or operation.' };
    if (error.status === 404) return { title: 'Not found', detail: 'The requested governed resource is not available.' };
    if (error.status === 412) return { title: 'Resource changed', detail: 'A newer authoritative revision exists. Refresh before retrying.' };
    return { title: `Request failed (${error.status})`, detail: error.message };
  }
  return { title: 'Unable to reach Wyrmgate', detail: 'The request could not be completed.' };
}
function ErrorPanel({ error, retry }: { error: unknown; retry?: () => void }) {
  const copy = errorCopy(error);
  return <StatePanel state="error" title={copy.title} action={retry ? <Button variant="outlined" size="small" onClick={retry}>Retry</Button> : undefined}>{copy.detail}</StatePanel>;
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
function confirmationCopy(confirmation: Confirmation, name: string) {
  if (!confirmation) return { title: '', body: '', label: 'Confirm' };
  if (confirmation.kind === 'merge') return { title: 'Merge governed Identities?', body: 'The current Identity remains the survivor. Only current Identity-owned source links and Principals move. AccessAssignments, Governance authority and other foreign-capability state do not move implicitly.', label: 'Merge Identity' };
  if (confirmation.kind === 'split') return { title: 'Split governed Identity?', body: 'A new same-type PENDING Identity is created. Only explicitly selected current source links and Principals move; access and governance authority are never copied.', label: 'Split Identity' };
  if (confirmation.action === 'activate') return { title: `Activate ${name}?`, body: 'Activation makes this Identity access-eligible. It does not resurrect terminated AccessAssignments.', label: 'Activate Identity' };
  if (confirmation.action === 'suspend') return { title: `Suspend ${name} and reduce access?`, body: 'Suspension makes this Identity access-ineligible immediately. EffectiveAccess contributes no authority and Access begins durable assignment reduction; provider realization remains asynchronous.', label: 'Suspend and reduce access' };
  if (confirmation.action === 'decommission') return { title: `Decommission ${name}?`, body: 'DECOMMISSIONED is terminal. Historical evidence and stable technical references remain; this does not synchronously delete provider accounts or credentials.', label: 'Decommission' };
  return { title: `Deactivate ${name} and reduce access?`, body: 'INACTIVE is access-ineligible. Access reduction proceeds asynchronously and provider observation remains separate.', label: 'Deactivate Identity' };
}

function usePagedResource<T>(path: string | null, trigger: number) {
  const [state, setState] = useState<LoadState<PageResult<T>>>({ status: 'idle' });
  useEffect(() => {
    if (!path) return;
    const controller = new AbortController();
    setState({ status: 'loading' });
    apiRequest<PageResult<T>>(path, { signal: controller.signal })
      .then(({ data }) => setState({ status: 'ready', value: data }))
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return;
        setState({ status: 'error', error });
      });
    return () => controller.abort();
  }, [path, trigger]);
  async function loadMore(basePath: string) {
    if (state.status !== 'ready' || !state.value.nextCursor) return;
    const current = state.value;
    const separator = basePath.includes('?') ? '&' : '?';
    const { data } = await apiRequest<PageResult<T>>(`${basePath}${separator}cursor=${encodeURIComponent(current.nextCursor!)}`);
    setState({ status: 'ready', value: { items: [...current.items, ...data.items], nextCursor: data.nextCursor } });
  }
  return { state, loadMore };
}
function PagedTableState({ state, empty, retry }: { state: LoadState<PageResult<unknown>>; empty: string; retry: () => void }) {
  if (state.status === 'loading' || state.status === 'idle') return <StatePanel state="loading" title="Loading">Reading the latest server-authorized view…</StatePanel>;
  if (state.status === 'error') return <ErrorPanel error={state.error} retry={retry} />;
  if (state.value.items.length === 0) return <StatePanel state="empty" title={empty}>No records are visible for this governed Identity.</StatePanel>;
  return null;
}
function DefinitionList({ items }: { items: { label: string; value: ReactNode; mono?: boolean }[] }) {
  return <Box component="dl" sx={{ m: 0, display: 'grid', gridTemplateColumns: { xs: '1fr', sm: 'minmax(9rem, 12rem) minmax(0, 1fr)' }, columnGap: 2, '& > dt, & > dd': { py: 1, m: 0, borderBottom: 1, borderColor: 'divider' }, '& > dt': { color: 'text.secondary', fontSize: '0.75rem' }, '& > dd': { fontSize: '0.8125rem', fontWeight: 600 } }}>
    {items.flatMap((item) => [<Box component="dt" key={`${item.label}-label`}>{item.label}</Box>, <Box component="dd" key={`${item.label}-value`} sx={item.mono ? monoSx : undefined}>{item.value}</Box>])}
  </Box>;
}
function LoadMore({ show, children }: { show: boolean; children: ReactNode }) {
  if (!show) return null;
  return <Box sx={{ display: 'flex', justifyContent: 'flex-end', pt: 1.5 }}>{children}</Box>;
}
function SemanticNote({ title, children }: { title?: string; children: ReactNode }) {
  return <Alert severity="info" icon={false}><Box sx={{ display: 'grid', gap: 0.25 }}>{title ? <Typography variant="subtitle2">{title}</Typography> : null}<Typography variant="body2">{children}</Typography></Box></Alert>;
}

export function IdentityWorkspace({ selectedId, onSelect, authority = null }: { selectedId?: string; onSelect: (id?: string) => void; authority?: CurrentAdministrativeAuthority | null }) {
  const identityId = selectedId ?? '';
  const queryView = new URLSearchParams(window.location.search).get('view');
  const initialView = (['overview', 'profile', 'principals', 'access', 'governance', 'evidence'] as DetailView[]).includes(queryView as DetailView) ? queryView as DetailView : 'overview';
  const [view, setView] = useState<DetailView>(initialView);
  const [reloadKey, setReloadKey] = useState(0);
  const [identityState, setIdentityState] = useState<LoadState<{ identity: IdentityResource; etag: string }>>({ status: 'loading' });
  const [mutationError, setMutationError] = useState<unknown>(null);
  const [pendingAction, setPendingAction] = useState<string | null>(null);
  const [confirmation, setConfirmation] = useState<Confirmation>(null);
  const [editOpen, setEditOpen] = useState(false);
  const [displayName, setDisplayName] = useState('');
  const [moreOpen, setMoreOpen] = useState(false);
  const [correctionMode, setCorrectionMode] = useState<'merge' | 'split'>('merge');
  const [mergeValues, setMergeValues] = useState({ absorbedIdentityId: '', reason: '' });
  const [mergeTarget, setMergeTarget] = useState<LoadState<IdentityResource> | null>(null);
  const [splitValues, setSplitValues] = useState({ newDisplayName: '', reason: '' });
  const [splitCandidates, setSplitCandidates] = useState<LoadState<SplitCandidates>>({ status: 'idle' });
  const [splitSelection, setSplitSelection] = useState({ sourceRecordIds: [] as string[], principalIds: [] as string[] });

  const profile = usePagedResource<CanonicalAttribute>(view === 'profile' ? `${V1_ROOT}/identities/${encodeURIComponent(identityId)}/canonical-attributes?limit=50` : null, reloadKey);
  const principals = usePagedResource<PrincipalResource>(view === 'principals' ? `${V1_ROOT}/principals?identityId=${encodeURIComponent(identityId)}&limit=50` : null, reloadKey);
  const assignments = usePagedResource<AssignmentResource>(view === 'access' ? `${V1_ROOT}/identities/${encodeURIComponent(identityId)}/access-assignments?limit=50` : null, reloadKey);
  const effective = usePagedResource<EffectiveAccess>(view === 'access' ? `${V1_ROOT}/effective-access?identityId=${encodeURIComponent(identityId)}&limit=50` : null, reloadKey);
  const campaigns = usePagedResource<ReviewCampaign>(view === 'governance' ? `${V1_ROOT}/governance/subjects/${encodeURIComponent(identityId)}/review-campaigns?limit=50` : null, reloadKey);
  const exceptions = usePagedResource<GovernanceException>(view === 'governance' ? `${V1_ROOT}/governance/subjects/${encodeURIComponent(identityId)}/exceptions?limit=50` : null, reloadKey);
  const evidence = usePagedResource<AuditRecord>(view === 'evidence' ? `${V1_ROOT}/audit-records?resourceType=identity&resourceId=${encodeURIComponent(identityId)}&limit=50` : null, reloadKey);

  useEffect(() => {
    const controller = new AbortController();
    setIdentityState({ status: 'loading' });
    getIdentity(identityId, controller.signal).then(({ data, etag }) => {
      if (!etag) throw new Error('Identity response did not include the required ETag');
      setDisplayName(data.displayName);
      setIdentityState({ status: 'ready', value: { identity: data, etag } });
    }).catch((error: unknown) => {
      if (error instanceof DOMException && error.name === 'AbortError') return;
      setIdentityState({ status: 'error', error });
    });
    return () => controller.abort();
  }, [identityId, reloadKey]);

  useEffect(() => {
    if (!moreOpen || correctionMode !== 'split' || splitCandidates.status !== 'idle') return;
    const controller = new AbortController();
    setSplitCandidates({ status: 'loading' });
    apiRequest<SplitCandidates>(`${V1_ROOT}/identities/${encodeURIComponent(identityId)}/split-candidates`, { signal: controller.signal })
      .then(({ data }) => setSplitCandidates({ status: 'ready', value: data }))
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return;
        setSplitCandidates({ status: 'error', error });
      });
    return () => controller.abort();
  }, [moreOpen, correctionMode, identityId, splitCandidates.status]);

  function changeView(next: DetailView) {
    setView(next);
    const url = new URL(window.location.href);
    if (next === 'overview') url.searchParams.delete('view'); else url.searchParams.set('view', next);
    window.history.replaceState({}, '', `${url.pathname}${url.search}`);
  }
  function toggleSplitSelection(kind: 'sourceRecordIds' | 'principalIds', id: string) {
    setSplitSelection((current) => ({ ...current, [kind]: current[kind].includes(id) ? current[kind].filter((value) => value !== id) : [...current[kind], id] }));
  }
  async function executeLifecycle(action: IdentityLifecycleAction) {
    if (identityState.status !== 'ready') return;
    setPendingAction(action); setMutationError(null);
    try {
      const result = await transitionIdentity(identityId, action, identityState.value.etag);
      if (!result.etag) throw new Error('Identity mutation did not return ETag');
      setIdentityState({ status: 'ready', value: { identity: result.data, etag: result.etag } });
    } catch (error) { setMutationError(error); }
    finally { setPendingAction(null); setConfirmation(null); }
  }
  async function updateName() {
    if (identityState.status !== 'ready') return;
    setPendingAction('rename'); setMutationError(null);
    try {
      const result = await apiRequest<IdentityResource>(`${V1_ROOT}/identities/${encodeURIComponent(identityId)}`, { method: 'PATCH', etag: identityState.value.etag, idempotent: true, body: { displayName: displayName.trim() } });
      if (!result.etag) throw new Error('Identity update did not return ETag');
      setIdentityState({ status: 'ready', value: { identity: result.data, etag: result.etag } });
      setEditOpen(false);
    } catch (error) { setMutationError(error); }
    finally { setPendingAction(null); }
  }
  async function loadMergeTarget() {
    const targetId = mergeValues.absorbedIdentityId.trim();
    if (!targetId) return;
    setMergeTarget({ status: 'loading' });
    try { setMergeTarget({ status: 'ready', value: (await getIdentity(targetId)).data }); }
    catch (error) { setMergeTarget({ status: 'error', error }); }
  }
  async function executeMerge() {
    if (identityState.status !== 'ready' || mergeTarget?.status !== 'ready') return;
    setPendingAction('merge'); setMutationError(null);
    try {
      await apiRequest(`${V1_ROOT}/identities/${encodeURIComponent(identityId)}:merge`, { method: 'POST', etag: identityState.value.etag, idempotent: true, body: { absorbedIdentityId: mergeTarget.value.id, absorbedRevision: mergeTarget.value.revision, reason: mergeValues.reason.trim() } });
      setMoreOpen(false); setMergeTarget(null); setMergeValues({ absorbedIdentityId: '', reason: '' }); setReloadKey((value) => value + 1);
    } catch (error) { setMutationError(error); }
    finally { setPendingAction(null); setConfirmation(null); }
  }
  async function executeSplit() {
    if (identityState.status !== 'ready') return;
    setPendingAction('split'); setMutationError(null);
    try {
      await apiRequest(`${V1_ROOT}/identities/${encodeURIComponent(identityId)}:split`, {
        method: 'POST', etag: identityState.value.etag, idempotent: true,
        body: { newDisplayName: splitValues.newDisplayName.trim(), sourceRecordIds: splitSelection.sourceRecordIds, principalIds: splitSelection.principalIds, reason: splitValues.reason.trim() },
      });
      setMoreOpen(false); setSplitValues({ newDisplayName: '', reason: '' }); setSplitSelection({ sourceRecordIds: [], principalIds: [] }); setSplitCandidates({ status: 'idle' }); setReloadKey((value) => value + 1);
    } catch (error) { setMutationError(error); }
    finally { setPendingAction(null); setConfirmation(null); }
  }

  if (identityState.status !== 'ready') {
    return <Page width="wide"><Button variant="text" sx={{ justifySelf: 'start' }} onClick={() => onSelect()}>Back to identities</Button>{identityState.status === 'error' ? <ErrorPanel error={identityState.error} retry={() => setReloadKey((value) => value + 1)} /> : <StatePanel state="loading" title="Loading governed Identity">Reading the latest authoritative revision…</StatePanel>}</Page>;
  }

  const identity = identityState.value.identity;
  const eligible = identity.lifecycleState === 'ACTIVE';
  const actions = lifecycleActions(identity);
  const confirm = confirmationCopy(confirmation, identity.displayName);
  const splitHasSelection = splitSelection.sourceRecordIds.length > 0 || splitSelection.principalIds.length > 0;
  const principalSummary = principals.state.status === 'ready' ? principals.state.value.items : [];
  const principalActive = principalSummary.filter((principal) => principal.lifecycleState === 'ACTIVE').length;
  const principalDisabled = principalSummary.filter((principal) => principal.lifecycleState === 'DISABLED').length;

  return <Page width="wide">
    <Button variant="text" sx={{ justifySelf: 'start' }} onClick={() => onSelect()}>Back to identities</Button>
    <PageHeader
      eyebrow="Identity · governed subject"
      title={<Box sx={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 1.25 }}><span>{identity.displayName}</span><StatusChip label={identity.type} /><StateIndicator label={identity.lifecycleState} tone={stateTone(identity.lifecycleState)} /></Box>}
      description={<Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1.5, color: 'text.secondary' }}><Box component="span">Identity ID: <Box component="span" sx={monoSx}>{identity.id}</Box></Box><Box component="span">Updated {formatDateTime(identity.updatedAt)}</Box></Box>}
      actions={<><Button variant="outlined" onClick={() => setReloadKey((value) => value + 1)}>Refresh</Button><Button variant="outlined" onClick={() => setEditOpen(true)}>Edit metadata</Button><Button variant="outlined" onClick={() => setMoreOpen(true)}>Advanced correction</Button></>}
    />

    {mutationError ? <ErrorPanel error={mutationError} retry={() => setReloadKey((value) => value + 1)} /> : null}

    <Box component="section" aria-label="Identity 360 workspace" sx={{ minWidth: 0 }}>
      <Box sx={{ borderBottom: 1, borderColor: 'divider', mb: 2 }}><Tabs value={view} onChange={(_event, next) => changeView(next as DetailView)} aria-label="Identity 360 views"><Tab value="overview" label="Overview" {...getTabA11yProps('identity360', 'overview')} /><Tab value="profile" label="Governed profile" {...getTabA11yProps('identity360', 'profile')} /><Tab value="principals" label="Principals" {...getTabA11yProps('identity360', 'principals')} /><Tab value="access" label="Access" {...getTabA11yProps('identity360', 'access')} /><Tab value="governance" label="Governance" {...getTabA11yProps('identity360', 'governance')} /><Tab value="evidence" label="Evidence" {...getTabA11yProps('identity360', 'evidence')} /></Tabs></Box>

      <TabPanel groupId="identity360" tabId="overview" active={view === 'overview'}>
        <Box sx={{ display: 'grid', gap: 2 }}>
          <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', lg: 'minmax(0, 1fr) minmax(20rem, .9fr)' }, gap: 2, alignItems: 'start' }}>
            <Surface title="Identity summary" description="Current authoritative governed-subject state."><DefinitionList items={[{ label: 'Identity type', value: identity.type }, { label: 'Lifecycle', value: <StateIndicator label={identity.lifecycleState} tone={stateTone(identity.lifecycleState)} /> }, { label: 'Access eligibility', value: eligible ? 'Eligible' : 'Ineligible' }, { label: 'Created', value: formatDateTime(identity.createdAt) }, { label: 'Updated', value: formatDateTime(identity.updatedAt) }, { label: 'Identity ID', value: identity.id, mono: true }, { label: 'Revision', value: identity.revision }]} /></Surface>
            <Surface title="Lifecycle and access eligibility" description="Lifecycle is authoritative Identity state. Access eligibility follows the current lifecycle; provider convergence remains separate.">
              <Box sx={{ display: 'grid', gap: 2 }}>
                <Alert severity={eligible ? 'success' : 'warning'} icon={false}>
                  <Box sx={{ display: 'grid', gap: 0.75 }}><StateIndicator label={eligible ? 'Currently access-eligible' : 'Currently access-ineligible'} tone={eligible ? 'success' : 'warning'} /><Typography variant="body2">{eligible ? 'ACTIVE is the only access-eligible Identity lifecycle state. This does not itself confirm assignments, Principals, Credentials, EffectiveAccess, or provider grants.' : 'Only ACTIVE is access-eligible. EffectiveAccess contributes no semantic authority while this Identity is ineligible; assignment reduction and provider convergence may continue asynchronously.'}</Typography></Box>
                </Alert>
                <Box sx={{ display: 'grid', gap: 0.5 }}><Typography variant="overline" color="text.secondary">Control-plane context</Typography>{authority ? <><Typography variant="body2" sx={{ fontWeight: 600 }}>Tenant {shortId(authority.tenantId)}</Typography><Typography variant="caption" color="text.secondary">Authority evaluated {formatDateTime(authority.evaluatedAt)}</Typography></> : <Typography variant="body2" color="text.secondary">Current authority context unavailable.</Typography>}</Box>
                <Box sx={{ display: 'grid', gap: 1 }}><Typography variant="overline" color="text.secondary">Lifecycle actions</Typography><Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1 }}>{actions.length ? actions.map((action) => <Button key={action} variant="outlined" size="small" color={action === 'decommission' ? 'error' : 'primary'} onClick={() => setConfirmation({ kind: 'lifecycle', action })}>{action[0].toUpperCase() + action.slice(1)}</Button>) : <Typography variant="body2" color="text.secondary">No lifecycle actions available.</Typography>}</Box></Box>
              </Box>
            </Surface>
          </Box>
          <SemanticNote title="Identity is the governed subject.">Principals are separate technical representations/accounts. Provider observations are not Identity authority.</SemanticNote>
        </Box>
      </TabPanel>

      <TabPanel groupId="identity360" tabId="profile" active={view === 'profile'}>
        <Surface title="Governed profile" description="Canonical effective values, resolution state, classification, and authorized visibility." actions={<StatusChip label="Authorized values only" />}>
          <Box sx={{ display: 'grid', gap: 1.5 }}><PagedTableState state={profile.state as LoadState<PageResult<unknown>>} empty="No canonical attributes" retry={() => setReloadKey((value) => value + 1)} />{profile.state.status === 'ready' && profile.state.value.items.length ? <><DataTable ariaLabel="Governed canonical attributes" minWidth={860}><TableHead><TableRow><TableCell>Attribute</TableCell><TableCell>Effective value</TableCell><TableCell>Resolution</TableCell><TableCell>Classification</TableCell><TableCell>Shape</TableCell><TableCell>Value revision</TableCell></TableRow></TableHead><TableBody>{profile.state.value.items.map((attribute) => { const retained = ['CONFLICT','UNRESOLVED'].includes(attribute.resolutionStatus) && attribute.visibility === 'VALUE' && attribute.hasTrustedValue && Boolean(attribute.values?.length); return <TableRow key={attribute.definitionId}><TableCell><strong>{attribute.key}</strong></TableCell><TableCell sx={{ minWidth: 220 }}>{canonicalValue(attribute)}{retained ? <Typography component="span" variant="caption" color="warning.main" sx={{ display: 'block', mt: 0.25 }}>Retained trusted value; current resolution is {attribute.resolutionStatus}.</Typography> : null}</TableCell><TableCell><StateIndicator label={attribute.visibility === 'REDACTED' ? 'REDACTED' : attribute.resolutionStatus} tone={attribute.visibility === 'REDACTED' ? 'neutral' : stateTone(attribute.resolutionStatus)} /></TableCell><TableCell><StatusChip label={attribute.classification} /></TableCell><TableCell>{attribute.type} · {attribute.cardinality}</TableCell><TableCell>{attribute.valueRevision}</TableCell></TableRow>; })}</TableBody></DataTable><LoadMore show={Boolean(profile.state.value.nextCursor)}><Button variant="outlined" onClick={() => void profile.loadMore(`${V1_ROOT}/identities/${encodeURIComponent(identityId)}/canonical-attributes?limit=50`)}>Load more attributes</Button></LoadMore></> : null}<SemanticNote>Provider/source payloads are not shown here. Canonical resolution and value disclosure remain separately governed.</SemanticNote></Box>
        </Surface>
      </TabPanel>

      <TabPanel groupId="identity360" tabId="principals" active={view === 'principals'}>
        <Surface title="Principals" description="Technical representations/accounts currently owned by this governed Identity." actions={<Button variant="outlined" size="small" onClick={() => navigateConsole('/principals')}>Open Principals workspace</Button>}>
          <Box sx={{ display: 'grid', gap: 1.5 }}>{principals.state.status === 'ready' ? <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 2 }}><Typography variant="body2"><strong>{principalSummary.length}</strong> loaded</Typography><Typography variant="body2"><strong>{principalActive}</strong> active</Typography><Typography variant="body2"><strong>{principalDisabled}</strong> disabled</Typography></Box> : null}<PagedTableState state={principals.state as LoadState<PageResult<unknown>>} empty="No Principals" retry={() => setReloadKey((value) => value + 1)} />{principals.state.status === 'ready' && principalSummary.length ? <><DataTable ariaLabel="Identity Principals" minWidth={760}><TableHead><TableRow><TableCell>Native principal key</TableCell><TableCell>Application target</TableCell><TableCell>Principal lifecycle</TableCell><TableCell>Principal ID</TableCell><TableCell>Updated</TableCell></TableRow></TableHead><TableBody>{principalSummary.map((principal) => <TableRow key={principal.id}><TableCell><strong>{principal.nativePrincipalKey}</strong></TableCell><TableCell sx={monoSx}>{shortId(principal.applicationTargetId)}</TableCell><TableCell><StateIndicator label={principal.lifecycleState} tone={stateTone(principal.lifecycleState)} /></TableCell><TableCell sx={monoSx}>{shortId(principal.id)}</TableCell><TableCell>{formatDateTime(principal.updatedAt)}</TableCell></TableRow>)}</TableBody></DataTable><LoadMore show={Boolean(principals.state.value.nextCursor)}><Button variant="outlined" onClick={() => void principals.loadMore(`${V1_ROOT}/principals?identityId=${encodeURIComponent(identityId)}&limit=50`)}>Load more Principals</Button></LoadMore></> : null}<SemanticNote title="Identity, Principal, Credential.">Identity is the governed subject. Principal is the technical representation/account. Credentials belong to Principals.</SemanticNote></Box>
        </Surface>
      </TabPanel>

      <TabPanel groupId="identity360" tabId="access" active={view === 'access'}>
        <Box sx={{ display: 'grid', gap: 2 }}><SemanticNote title="Assignments are authoritative business intent. Effective access is a derived projection.">Technical fulfillment and provider observation remain separate from both.</SemanticNote>
          <Surface title="Access assignments" description="Direct and indirect assignment intent for this governed Identity." actions={<Box sx={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 1 }}><StatusChip label="Authoritative" /><Button variant="outlined" size="small" onClick={() => navigateConsole('/access')}>Open Access workspace</Button></Box>}><Box sx={{ display: 'grid', gap: 1.5 }}><PagedTableState state={assignments.state as LoadState<PageResult<unknown>>} empty="No AccessAssignments" retry={() => setReloadKey((value) => value + 1)} />{assignments.state.status === 'ready' && assignments.state.value.items.length ? <><DataTable ariaLabel="Identity access assignments" minWidth={980}><TableHead><TableRow><TableCell>Target</TableCell><TableCell>Kind</TableCell><TableCell>Assignment lifecycle</TableCell><TableCell>Valid from</TableCell><TableCell>Valid until</TableCell><TableCell>Principal constraint</TableCell><TableCell>Assignment ID</TableCell></TableRow></TableHead><TableBody>{assignments.state.value.items.map((assignment) => <TableRow key={assignment.id}><TableCell sx={monoSx}>{shortId(assignment.roleId ?? assignment.entitlementId)}</TableCell><TableCell>{assignment.targetKind}</TableCell><TableCell><StateIndicator label={assignment.lifecycleState} tone={stateTone(assignment.lifecycleState)} /></TableCell><TableCell>{formatDateTime(assignment.validFrom)}</TableCell><TableCell>{formatDateTime(assignment.validUntil)}</TableCell><TableCell>{assignment.principalConstraintKind}{assignment.specificPrincipalId ? <Typography component="span" variant="caption" sx={{ ...monoSx, display: 'block' }}>{shortId(assignment.specificPrincipalId)}</Typography> : null}</TableCell><TableCell sx={monoSx}>{shortId(assignment.id)}</TableCell></TableRow>)}</TableBody></DataTable><LoadMore show={Boolean(assignments.state.value.nextCursor)}><Button variant="outlined" onClick={() => void assignments.loadMore(`${V1_ROOT}/identities/${encodeURIComponent(identityId)}/access-assignments?limit=50`)}>Load more assignments</Button></LoadMore></> : null}</Box></Surface>
          <Surface title="Effective access" description="Entitlement-level access currently derived for this Identity. An ineligible Identity contributes no semantic EffectiveAccess authority." actions={<StatusChip label="Projection" />}><Box sx={{ display: 'grid', gap: 1.5 }}><PagedTableState state={effective.state as LoadState<PageResult<unknown>>} empty="No EffectiveAccess" retry={() => setReloadKey((value) => value + 1)} />{effective.state.status === 'ready' && effective.state.value.items.length ? <><DataTable ariaLabel="Identity effective access" minWidth={820}><TableHead><TableRow><TableCell>Entitlement</TableCell><TableCell>Principal constraint</TableCell><TableCell>Support count</TableCell><TableCell>Computed</TableCell><TableCell>Projection generation</TableCell><TableCell>EffectiveAccess ID</TableCell></TableRow></TableHead><TableBody>{effective.state.value.items.map((item) => <TableRow key={item.id}><TableCell sx={monoSx}>{shortId(item.entitlementId)}</TableCell><TableCell>{item.principalConstraintKey}</TableCell><TableCell>{item.supportCount}</TableCell><TableCell>{formatDateTime(item.computedAt)}</TableCell><TableCell>{item.projectionGeneration}</TableCell><TableCell sx={monoSx}>{shortId(item.id)}</TableCell></TableRow>)}</TableBody></DataTable><LoadMore show={Boolean(effective.state.value.nextCursor)}><Button variant="outlined" onClick={() => void effective.loadMore(`${V1_ROOT}/effective-access?identityId=${encodeURIComponent(identityId)}&limit=50`)}>Load more effective access</Button></LoadMore></> : null}</Box></Surface>
        </Box>
      </TabPanel>

      <TabPanel groupId="identity360" tabId="governance" active={view === 'governance'}>
        <Box sx={{ display: 'grid', gap: 2 }}><Surface title="Access reviews" description="Governance-owned review campaigns for this governed subject." actions={<Button variant="outlined" size="small" onClick={() => navigateConsole('/reviews')}>View all</Button>}><Box sx={{ display: 'grid', gap: 1.5 }}><PagedTableState state={campaigns.state as LoadState<PageResult<unknown>>} empty="No subject review campaigns" retry={() => setReloadKey((value) => value + 1)} />{campaigns.state.status === 'ready' && campaigns.state.value.items.length ? <><DataTable ariaLabel="Identity access review campaigns" minWidth={820}><TableHead><TableRow><TableCell>Campaign ID</TableCell><TableCell>State</TableCell><TableCell>Reviewer Identity</TableCell><TableCell>Snapshot time</TableCell><TableCell>Progress</TableCell><TableCell>Updated</TableCell></TableRow></TableHead><TableBody>{campaigns.state.value.items.map((campaign) => <TableRow key={campaign.id}><TableCell sx={monoSx}>{shortId(campaign.id)}</TableCell><TableCell><StateIndicator label={campaign.state} tone={stateTone(campaign.state)} /></TableCell><TableCell sx={monoSx}>{shortId(campaign.reviewerIdentityId)}</TableCell><TableCell>{formatDateTime(campaign.snapshotAt)}</TableCell><TableCell>{campaign.decidedItemCount} / {campaign.generatedItemCount}</TableCell><TableCell>{formatDateTime(campaign.updatedAt)}</TableCell></TableRow>)}</TableBody></DataTable><LoadMore show={Boolean(campaigns.state.value.nextCursor)}><Button variant="outlined" onClick={() => void campaigns.loadMore(`${V1_ROOT}/governance/subjects/${encodeURIComponent(identityId)}/review-campaigns?limit=50`)}>Load more reviews</Button></LoadMore></> : null}</Box></Surface>
          <Surface title="Governance exceptions" description="Current and historical subject-scoped SoD exceptions. Approval evidence does not itself create Access authority." actions={<Button variant="outlined" size="small" onClick={() => navigateConsole('/policies')}>Open Governance</Button>}><Box sx={{ display: 'grid', gap: 1.5 }}><PagedTableState state={exceptions.state as LoadState<PageResult<unknown>>} empty="No subject GovernanceExceptions" retry={() => setReloadKey((value) => value + 1)} />{exceptions.state.status === 'ready' && exceptions.state.value.items.length ? <><DataTable ariaLabel="Identity Governance exceptions" minWidth={820}><TableHead><TableRow><TableCell>Exception ID</TableCell><TableCell>SoD rule</TableCell><TableCell>State</TableCell><TableCell>Valid from</TableCell><TableCell>Valid until</TableCell><TableCell>Revision</TableCell></TableRow></TableHead><TableBody>{exceptions.state.value.items.map((exception) => <TableRow key={exception.id}><TableCell sx={monoSx}>{shortId(exception.id)}</TableCell><TableCell sx={monoSx}>{shortId(exception.sodRuleId)}</TableCell><TableCell><StateIndicator label={exception.lifecycleState} tone={stateTone(exception.lifecycleState)} /></TableCell><TableCell>{formatDateTime(exception.validFrom)}</TableCell><TableCell>{formatDateTime(exception.validUntil)}</TableCell><TableCell>{exception.revision}</TableCell></TableRow>)}</TableBody></DataTable><LoadMore show={Boolean(exceptions.state.value.nextCursor)}><Button variant="outlined" onClick={() => void exceptions.loadMore(`${V1_ROOT}/governance/subjects/${encodeURIComponent(identityId)}/exceptions?limit=50`)}>Load more exceptions</Button></LoadMore></> : null}</Box></Surface>
          <SemanticNote title="Access request history is intentionally not shown here.">Request visibility remains participant-scoped unless a separately accepted administrative subject-read contract exists.</SemanticNote>
        </Box>
      </TabPanel>

      <TabPanel groupId="identity360" tabId="evidence" active={view === 'evidence'}>
        <Surface title="Recent Identity evidence" description="Direct immutable AuditRecord evidence whose resource is this Identity. This is not an exhaustive cross-capability activity history." actions={<Button variant="outlined" size="small" onClick={() => navigateConsole('/audit')}>Open Audit workspace</Button>}>
          <Box sx={{ display: 'grid', gap: 1.5 }}><PagedTableState state={evidence.state as LoadState<PageResult<unknown>>} empty="No direct Identity AuditRecords" retry={() => setReloadKey((value) => value + 1)} />{evidence.state.status === 'ready' && evidence.state.value.items.length ? <><DataTable ariaLabel="Direct Identity AuditRecord evidence" minWidth={840}><TableHead><TableRow><TableCell>Time</TableCell><TableCell>Outcome</TableCell><TableCell>Action</TableCell><TableCell>Actor Identity</TableCell><TableCell>Correlation ID</TableCell><TableCell>AuditRecord ID</TableCell></TableRow></TableHead><TableBody>{evidence.state.value.items.map((record) => <TableRow key={record.id}><TableCell>{formatDateTime(record.occurredAt)}</TableCell><TableCell><StateIndicator label={record.outcome} tone={stateTone(record.outcome)} /></TableCell><TableCell><strong>{record.actionType}</strong></TableCell><TableCell sx={monoSx}>{shortId(record.actorId)}</TableCell><TableCell sx={monoSx}>{shortId(record.correlationId)}</TableCell><TableCell sx={monoSx}>{shortId(record.id)}</TableCell></TableRow>)}</TableBody></DataTable><Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 2 }}><Typography variant="body2"><strong>{evidence.state.value.items.length}</strong> loaded direct records</Typography><Typography variant="body2">Newest loaded: <strong>{formatDateTime(evidence.state.value.items[0]?.occurredAt)}</strong></Typography><Typography variant="body2">Resource type: <strong>Identity</strong></Typography></Box><LoadMore show={Boolean(evidence.state.value.nextCursor)}><Button variant="outlined" onClick={() => void evidence.loadMore(`${V1_ROOT}/audit-records?resourceType=identity&resourceId=${encodeURIComponent(identityId)}&limit=50`)}>Load more evidence</Button></LoadMore></> : null}<SemanticNote title="Broader investigations continue in Audit.">Related Access, Governance, Integration, or system evidence is not silently joined into this direct Identity view.</SemanticNote></Box>
        </Surface>
      </TabPanel>
    </Box>

    <Modal open={editOpen} title="Edit Identity metadata" description="Display name is the mutable non-lifecycle Identity metadata field in the current contract." busy={pendingAction === 'rename'} onClose={() => { if (pendingAction !== 'rename') { setDisplayName(identity.displayName); setEditOpen(false); } }}><Box sx={{ display: 'grid', gap: 1.5 }}><TextInput autoFocus label="Display name" required value={displayName} onChange={(event) => setDisplayName(event.target.value)} /><Box sx={{ display: 'flex', justifyContent: 'flex-end', gap: 1 }}><Button variant="text" onClick={() => setEditOpen(false)}>Cancel</Button><Button variant="contained" disabled={!displayName.trim() || displayName.trim() === identity.displayName || pendingAction === 'rename'} onClick={() => void updateName()}>{pendingAction === 'rename' ? 'Saving…' : 'Save metadata'}</Button></Box></Box></Modal>

    <Modal open={moreOpen} title="Advanced Identity correction" description="Merge and split are governed-subject corrections, not ordinary account maintenance." busy={pendingAction === 'merge' || pendingAction === 'split'} onClose={() => { if (!pendingAction) setMoreOpen(false); }}><Box sx={{ display: 'grid', gap: 2 }}><Box sx={{ display: 'flex', gap: 1 }}><Button variant={correctionMode === 'merge' ? 'contained' : 'outlined'} onClick={() => setCorrectionMode('merge')}>Merge</Button><Button variant={correctionMode === 'split' ? 'contained' : 'outlined'} disabled={identity.lifecycleState === 'DECOMMISSIONED'} onClick={() => setCorrectionMode('split')}>Split</Button></Box>
      {correctionMode === 'merge' ? <><Alert severity="warning"><strong>Permanent correction.</strong> The current Identity survives. The absorbed Identity becomes DECOMMISSIONED. Foreign-capability authority never transfers implicitly.</Alert><TextInput label="Absorbed Identity ID" value={mergeValues.absorbedIdentityId} onChange={(event) => { setMergeValues((value) => ({ ...value, absorbedIdentityId: event.target.value })); setMergeTarget(null); }} /><Button variant="outlined" disabled={!mergeValues.absorbedIdentityId.trim() || mergeTarget?.status === 'loading'} onClick={() => void loadMergeTarget()}>{mergeTarget?.status === 'loading' ? 'Checking…' : 'Review target'}</Button>{mergeTarget?.status === 'error' ? <ErrorPanel error={mergeTarget.error} /> : null}{mergeTarget?.status === 'ready' ? <Surface><Typography variant="subtitle2">{mergeTarget.value.displayName}</Typography><Typography variant="body2" color="text.secondary">{mergeTarget.value.type} · {mergeTarget.value.lifecycleState} · revision {mergeTarget.value.revision}</Typography></Surface> : null}<TextInput label="Correction reason" multiline minRows={3} value={mergeValues.reason} onChange={(event) => setMergeValues((value) => ({ ...value, reason: event.target.value }))} /><Button color="error" variant="contained" disabled={mergeTarget?.status !== 'ready' || !mergeValues.reason.trim()} onClick={() => setConfirmation({ kind: 'merge' })}>Review merge consequence</Button></> : <><Alert severity="warning"><strong>Explicit relationship selection required.</strong> The new same-type Identity starts PENDING. No AccessAssignment or Governance authority is copied.</Alert><TextInput label="New Identity display name" value={splitValues.newDisplayName} onChange={(event) => setSplitValues((value) => ({ ...value, newDisplayName: event.target.value }))} />
        {splitCandidates.status === 'idle' || splitCandidates.status === 'loading' ? <StatePanel state="loading" title="Loading split candidates">Reading current Identity-owned relationships…</StatePanel> : null}
        {splitCandidates.status === 'error' ? <ErrorPanel error={splitCandidates.error} retry={() => setSplitCandidates({ status: 'idle' })} /> : null}
        {splitCandidates.status === 'ready' ? <Box sx={{ display: 'grid', gap: 2 }}>
          {(splitCandidates.value.sourceRecordsTruncated || splitCandidates.value.principalsTruncated) ? <Alert severity="warning"><strong>Candidate set exceeds the first-slice correction bound.</strong> Only the first 200 current candidates of each relationship kind are selectable here; use a narrower recovery workflow for larger corrections.</Alert> : null}
          <Surface title="Current SourceRecord links">{splitCandidates.value.sourceRecords.length ? <Box sx={{ display: 'grid', gap: 0.5 }}>{splitCandidates.value.sourceRecords.map((candidate) => <CheckboxInput key={candidate.sourceRecordId} checked={splitSelection.sourceRecordIds.includes(candidate.sourceRecordId)} onChange={() => toggleSplitSelection('sourceRecordIds', candidate.sourceRecordId)} label={<Box sx={{ display: 'grid' }}><Typography variant="body2" sx={{ fontWeight: 600 }}>SourceRecord {shortId(candidate.sourceRecordId)}</Typography><Typography variant="caption" color="text.secondary">Accepted link · linked {formatDateTime(candidate.linkedAt)}</Typography></Box>} />)}</Box> : <Typography variant="body2" color="text.secondary">No current ACCEPTED SourceRecord links.</Typography>}</Surface>
          <Surface title="Current Principals">{splitCandidates.value.principals.length ? <Box sx={{ display: 'grid', gap: 0.5 }}>{splitCandidates.value.principals.map((candidate) => <CheckboxInput key={candidate.principalId} checked={splitSelection.principalIds.includes(candidate.principalId)} onChange={() => toggleSplitSelection('principalIds', candidate.principalId)} label={<Box sx={{ display: 'grid' }}><Typography variant="body2" sx={{ fontWeight: 600 }}>Principal {shortId(candidate.principalId)}</Typography><Typography variant="caption" color="text.secondary">Application target {shortId(candidate.applicationTargetId)} · {candidate.lifecycleState} · revision {candidate.revision}</Typography></Box>} />)}</Box> : <Typography variant="body2" color="text.secondary">No current Principals.</Typography>}</Surface>
        </Box> : null}
        <TextInput label="Correction reason" multiline minRows={2} value={splitValues.reason} onChange={(event) => setSplitValues((value) => ({ ...value, reason: event.target.value }))} /><Button color="error" variant="contained" disabled={splitCandidates.status !== 'ready' || !splitValues.newDisplayName.trim() || !splitValues.reason.trim() || !splitHasSelection} onClick={() => setConfirmation({ kind: 'split' })}>Review split consequence</Button></>}
    </Box></Modal>

    <ConfirmDialog open={confirmation !== null} title={confirm.title} confirmLabel={confirm.label} destructive={confirmation?.kind !== 'lifecycle' || confirmation?.action !== 'activate'} busy={pendingAction !== null} onCancel={() => setConfirmation(null)} onConfirm={() => void (confirmation?.kind === 'lifecycle' ? executeLifecycle(confirmation.action) : confirmation?.kind === 'merge' ? executeMerge() : executeSplit())}>{confirm.body}</ConfirmDialog>
  </Page>;
}
