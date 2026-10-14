import { useEffect, useMemo, useState } from 'react';
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
  Box,
  Button,
  ConfirmDialog,
  Modal,
  StatePanel,
  StatusChip,
  Tab,
  TabPanel,
  Tabs,
  TextInput,
  getTabA11yProps,
} from '../ui';
import '../identity-detail-golden.css';

type DetailView = 'overview' | 'profile' | 'principals' | 'access' | 'governance' | 'evidence';
type LoadState<T> = { status: 'idle' | 'loading' } | { status: 'ready'; value: T } | { status: 'error'; error: unknown };
type Page<T> = { items: T[]; nextCursor?: string | null };
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
type Confirmation = { kind: 'lifecycle'; action: IdentityLifecycleAction } | { kind: 'merge' } | { kind: 'split' } | null;

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
function tone(value: string): 'success' | 'warning' | 'default' | 'info' | 'error' {
  if (['ACTIVE', 'APPROVED', 'COMPLETED', 'RESOLVED', 'SUCCESS'].includes(value)) return 'success';
  if (['PENDING', 'DRAFT', 'GENERATING', 'IN_PROGRESS', 'OVERRIDDEN', 'INFO'].includes(value)) return 'info';
  if (['SUSPENDED', 'UNRESOLVED', 'PENDING_APPROVAL'].includes(value)) return 'warning';
  if (['DECOMMISSIONED', 'CONFLICT', 'FAILURE', 'DENIED', 'REJECTED'].includes(value)) return 'error';
  return 'default';
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
  if (confirmation.kind === 'merge') return {
    title: 'Merge governed Identities?',
    body: 'The current Identity remains the survivor. Only current Identity-owned source links and Principals move. AccessAssignments, Governance authority and other foreign-capability state do not move implicitly.',
    label: 'Merge Identity',
  };
  if (confirmation.kind === 'split') return {
    title: 'Split governed Identity?',
    body: 'A new same-type PENDING Identity is created. Only explicitly selected current source links and Principals move; access and governance authority are never copied.',
    label: 'Split Identity',
  };
  if (confirmation.action === 'activate') return { title: `Activate ${name}?`, body: 'Activation makes this Identity access-eligible. It does not resurrect terminated AccessAssignments.', label: 'Activate Identity' };
  if (confirmation.action === 'suspend') return { title: `Suspend ${name} and reduce access?`, body: 'Suspension makes this Identity access-ineligible immediately. EffectiveAccess contributes no authority and Access begins durable assignment reduction; provider realization remains asynchronous.', label: 'Suspend and reduce access' };
  if (confirmation.action === 'decommission') return { title: `Decommission ${name}?`, body: 'DECOMMISSIONED is terminal. Historical evidence and stable technical references remain; this does not synchronously delete provider accounts or credentials.', label: 'Decommission' };
  return { title: `Deactivate ${name} and reduce access?`, body: 'INACTIVE is access-ineligible. Access reduction proceeds asynchronously and provider observation remains separate.', label: 'Deactivate Identity' };
}

function usePagedResource<T>(path: string | null, trigger: number) {
  const [state, setState] = useState<LoadState<Page<T>>>({ status: 'idle' });
  useEffect(() => {
    if (!path) return;
    const controller = new AbortController();
    setState({ status: 'loading' });
    apiRequest<Page<T>>(path, { signal: controller.signal })
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
    const { data } = await apiRequest<Page<T>>(`${basePath}${separator}cursor=${encodeURIComponent(current.nextCursor!)}`);
    setState({ status: 'ready', value: { items: [...current.items, ...data.items], nextCursor: data.nextCursor } });
  }
  return { state, setState, loadMore };
}

function PagedTableState({ state, empty, retry }: { state: LoadState<Page<unknown>>; empty: string; retry: () => void }) {
  if (state.status === 'loading' || state.status === 'idle') return <StatePanel state="loading" title="Loading">Reading the latest server-authorized view…</StatePanel>;
  if (state.status === 'error') return <ErrorPanel error={state.error} retry={retry} />;
  if (state.value.items.length === 0) return <StatePanel state="empty" title={empty}>No records are visible for this governed Identity.</StatePanel>;
  return null;
}

export function IdentityWorkspace({
  selectedId,
  onSelect,
  authority = null,
}: {
  selectedId?: string;
  onSelect: (id?: string) => void;
  authority?: CurrentAdministrativeAuthority | null;
}) {
  const identityId = selectedId ?? '';
  const initialView = (() => {
    const value = new URLSearchParams(window.location.search).get('view');
    return (['overview', 'profile', 'principals', 'access', 'governance', 'evidence'] as DetailView[]).includes(value as DetailView)
      ? value as DetailView : 'overview';
  })();
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
  const [splitValues, setSplitValues] = useState({ newDisplayName: '', sourceRecordIds: '', principalIds: '', reason: '' });

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
    getIdentity(identityId, controller.signal)
      .then(({ data, etag }) => {
        if (!etag) throw new Error('Identity response did not include the required ETag');
        setDisplayName(data.displayName);
        setIdentityState({ status: 'ready', value: { identity: data, etag } });
      })
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return;
        setIdentityState({ status: 'error', error });
      });
    return () => controller.abort();
  }, [identityId, reloadKey]);

  function changeView(next: DetailView) {
    setView(next);
    const url = new URL(window.location.href);
    if (next === 'overview') url.searchParams.delete('view'); else url.searchParams.set('view', next);
    window.history.replaceState({}, '', `${url.pathname}${url.search}`);
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
      const result = await apiRequest<IdentityResource>(`${V1_ROOT}/identities/${encodeURIComponent(identityId)}`, {
        method: 'PATCH', etag: identityState.value.etag, idempotent: true, body: { displayName: displayName.trim() },
      });
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
      await apiRequest(`${V1_ROOT}/identities/${encodeURIComponent(identityId)}:merge`, {
        method: 'POST', etag: identityState.value.etag, idempotent: true,
        body: { absorbedIdentityId: mergeTarget.value.id, absorbedRevision: mergeTarget.value.revision, reason: mergeValues.reason.trim() },
      });
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
        body: {
          newDisplayName: splitValues.newDisplayName.trim(),
          sourceRecordIds: splitValues.sourceRecordIds.split(/[\n,]/).map((v) => v.trim()).filter(Boolean),
          principalIds: splitValues.principalIds.split(/[\n,]/).map((v) => v.trim()).filter(Boolean),
          reason: splitValues.reason.trim(),
        },
      });
      setMoreOpen(false); setSplitValues({ newDisplayName: '', sourceRecordIds: '', principalIds: '', reason: '' }); setReloadKey((value) => value + 1);
    } catch (error) { setMutationError(error); }
    finally { setPendingAction(null); setConfirmation(null); }
  }

  if (identityState.status !== 'ready') {
    if (identityState.status === 'error') {
      return <div className="identity360-page"><button className="identity360-back" onClick={() => onSelect()}>← Identities</button><ErrorPanel error={identityState.error} retry={() => setReloadKey((v) => v + 1)} /></div>;
    }
    return <div className="identity360-page"><StatePanel state="loading" title="Loading governed Identity">Reading the latest authoritative revision…</StatePanel></div>;
  }

  const identity = identityState.value.identity;
  const eligible = identity.lifecycleState === 'ACTIVE';
  const actions = lifecycleActions(identity);
  const confirm = confirmationCopy(confirmation, identity.displayName);
  const splitHasSelection = splitValues.sourceRecordIds.split(/[\n,]/).some((v) => v.trim()) || splitValues.principalIds.split(/[\n,]/).some((v) => v.trim());
  const principalSummary = principals.state.status === 'ready' ? principals.state.value.items : [];
  const principalActive = principalSummary.filter((p) => p.lifecycleState === 'ACTIVE').length;
  const principalDisabled = principalSummary.filter((p) => p.lifecycleState === 'DISABLED').length;

  return (
    <main className="identity360-page" aria-labelledby="identity360-title">
      <button className="identity360-back" type="button" onClick={() => onSelect()}>← Identities</button>

      <header className="identity360-header">
        <div className="identity360-heading">
          <span className="identity360-eyebrow">Identity · governed subject</span>
          <div className="identity360-title-row">
            <h1 id="identity360-title">{identity.displayName}</h1>
            <span className="identity360-type">{identity.type}</span>
            <StatusChip label={identity.lifecycleState} color={tone(identity.lifecycleState)} />
          </div>
          <div className="identity360-meta"><span>Identity ID: <span className="mono">{identity.id}</span></span><i /> <span>Updated {formatDateTime(identity.updatedAt)}</span></div>
        </div>
        <div className="identity360-context-actions">
          {authority ? <div className="identity360-context"><strong>Tenant: {shortId(authority.tenantId)}</strong><span>Authority evaluated {formatDateTime(authority.evaluatedAt)}</span></div> : null}
          <div className="identity360-actions">
            <Button variant="outlined" onClick={() => setReloadKey((v) => v + 1)}>Refresh</Button>
            <Button variant="outlined" onClick={() => setEditOpen(true)}>✎ Edit metadata</Button>
            <div className="identity360-menu">
              <details><summary>⚙ Lifecycle actions⌄</summary><div className="identity360-menu-popover">{actions.length ? actions.map((action) => <button key={action} type="button" className={action === 'decommission' ? 'danger' : ''} onClick={() => setConfirmation({ kind: 'lifecycle', action })}>{action[0].toUpperCase() + action.slice(1)}</button>) : <span>No lifecycle actions</span>}</div></details>
            </div>
            <Button variant="outlined" onClick={() => setMoreOpen(true)}>⋮ More</Button>
          </div>
        </div>
      </header>

      <section className={`identity360-eligibility ${eligible ? 'eligible' : 'ineligible'}`}>
        <span className="identity360-eligibility-icon" aria-hidden="true">{eligible ? '✓' : '!'}</span>
        <div><strong>{eligible ? 'Currently access-eligible' : 'Currently access-ineligible'}</strong><p>{eligible
          ? 'ACTIVE is the only access-eligible Identity lifecycle state. This does not itself confirm assignments, Principals, Credentials, EffectiveAccess, or provider grants.'
          : 'Only ACTIVE is access-eligible. EffectiveAccess contributes no semantic authority while this Identity is ineligible; assignment reduction and provider convergence may continue asynchronously.'}</p></div>
      </section>

      {mutationError ? <ErrorPanel error={mutationError} retry={() => setReloadKey((v) => v + 1)} /> : null}

      <section className="identity360-workspace" aria-label="Identity 360 workspace">
        <Tabs value={view} onChange={(_e, next) => changeView(next as DetailView)} aria-label="Identity 360 views">
          <Tab value="overview" label="Overview" {...getTabA11yProps('identity360', 'overview')} />
          <Tab value="profile" label="Governed profile" {...getTabA11yProps('identity360', 'profile')} />
          <Tab value="principals" label="Principals" {...getTabA11yProps('identity360', 'principals')} />
          <Tab value="access" label="Access" {...getTabA11yProps('identity360', 'access')} />
          <Tab value="governance" label="Governance" {...getTabA11yProps('identity360', 'governance')} />
          <Tab value="evidence" label="Evidence" {...getTabA11yProps('identity360', 'evidence')} />
        </Tabs>

        <div className="identity360-tab-body">
          <TabPanel groupId="identity360" tabId="overview" active={view === 'overview'}>
            <div className="identity360-overview-grid">
              <article className="identity360-card"><header><h2>Identity summary</h2><p>Current authoritative governed-subject state.</p></header><dl className="identity360-summary">
                <div><dt>Identity type</dt><dd>{identity.type}</dd></div><div><dt>Lifecycle</dt><dd><StatusChip label={identity.lifecycleState} color={tone(identity.lifecycleState)} /></dd></div>
                <div><dt>Access eligibility</dt><dd>{eligible ? 'Eligible' : 'Ineligible'}</dd></div><div><dt>Created</dt><dd>{formatDateTime(identity.createdAt)}</dd></div>
                <div><dt>Updated</dt><dd>{formatDateTime(identity.updatedAt)}</dd></div><div><dt>Identity ID</dt><dd className="mono">{identity.id}</dd></div><div><dt>Revision</dt><dd>{identity.revision}</dd></div>
              </dl></article>
              <article className="identity360-card"><header><h2>Investigation</h2><p>Explore this Identity without collapsing authority, projections, evidence, or technical representations.</p></header><div className="identity360-investigation">
                {([
                  ['profile', 'Governed profile', 'Canonical values, resolution, classification and visibility'],
                  ['principals', 'Principals', 'Identity-owned technical account representations'],
                  ['access', 'Access', 'Authoritative assignments and derived EffectiveAccess'],
                  ['governance', 'Governance', 'Subject reviews and GovernanceExceptions'],
                  ['evidence', 'Evidence', 'Direct immutable Identity AuditRecord evidence'],
                ] as const).map(([target, label, detail]) => <button key={target} type="button" onClick={() => changeView(target)}><span><strong>{label}</strong><small>{detail}</small></span><em>›</em></button>)}
              </div></article>
            </div>
            <div className="identity360-info"><strong>Identity is the governed subject.</strong><span>Principals are separate technical representations/accounts. Provider observations are not Identity authority.</span></div>
          </TabPanel>

          <TabPanel groupId="identity360" tabId="profile" active={view === 'profile'}>
            <section className="identity360-section"><header className="identity360-section-header"><div><h2>Governed profile</h2><p>Canonical effective values, resolution state, classification, and authorized visibility.</p></div><span className="identity360-note-chip">ⓘ Authorized values only</span></header>
              <PagedTableState state={profile.state as LoadState<Page<unknown>>} empty="No canonical attributes" retry={() => setReloadKey((v) => v + 1)} />
              {profile.state.status === 'ready' && profile.state.value.items.length ? <><div className="identity360-table-wrap"><table className="identity360-table"><thead><tr><th>Attribute</th><th>Effective value</th><th>Resolution</th><th>Classification</th><th>Shape</th><th>Value revision</th></tr></thead><tbody>{profile.state.value.items.map((attribute) => {
                const retained = ['CONFLICT', 'UNRESOLVED'].includes(attribute.resolutionStatus) && attribute.visibility === 'VALUE' && attribute.hasTrustedValue && Boolean(attribute.values?.length);
                return <tr key={attribute.definitionId}><td><strong>{attribute.key}</strong></td><td className="identity360-value">{canonicalValue(attribute)}{retained ? <small>⚠ Retained trusted value · current resolution is {attribute.resolutionStatus}</small> : null}</td><td><StatusChip label={attribute.visibility === 'REDACTED' ? 'REDACTED' : attribute.resolutionStatus} color={attribute.visibility === 'REDACTED' ? 'default' : tone(attribute.resolutionStatus)} /></td><td><span className="identity360-classification">{attribute.classification}</span></td><td>{attribute.type} · {attribute.cardinality}</td><td>{attribute.valueRevision}</td></tr>;
              })}</tbody></table></div>{profile.state.value.nextCursor ? <div className="identity360-load-more"><Button variant="outlined" onClick={() => void profile.loadMore(`${V1_ROOT}/identities/${encodeURIComponent(identityId)}/canonical-attributes?limit=50`)}>Load more attributes</Button></div> : null}</> : null}
              <div className="identity360-info"><span>Provider/source payloads are not shown here. Canonical resolution and value disclosure remain separately governed.</span></div>
            </section>
          </TabPanel>

          <TabPanel groupId="identity360" tabId="principals" active={view === 'principals'}>
            <section className="identity360-section"><header className="identity360-section-header"><div><h2>Principals</h2><p>Technical representations/accounts currently owned by this governed Identity.</p></div><Button variant="outlined" size="small" onClick={() => navigateConsole('/principals')}>Open Principals workspace</Button></header>
              {principals.state.status === 'ready' ? <div className="identity360-metrics"><div><strong>{principalSummary.length}</strong><span>Loaded Principals</span></div><div><strong>{principalActive}</strong><span>Active</span></div><div><strong>{principalDisabled}</strong><span>Disabled</span></div></div> : null}
              <PagedTableState state={principals.state as LoadState<Page<unknown>>} empty="No Principals" retry={() => setReloadKey((v) => v + 1)} />
              {principals.state.status === 'ready' && principalSummary.length ? <><div className="identity360-table-wrap"><table className="identity360-table"><thead><tr><th>Native principal key</th><th>Application target</th><th>Principal lifecycle</th><th>Principal ID</th><th>Updated</th></tr></thead><tbody>{principalSummary.map((principal) => <tr key={principal.id}><td><strong>{principal.nativePrincipalKey}</strong></td><td className="mono">{shortId(principal.applicationTargetId)}</td><td><StatusChip label={principal.lifecycleState} color={tone(principal.lifecycleState)} /></td><td className="mono">{shortId(principal.id)}</td><td>{formatDateTime(principal.updatedAt)}</td></tr>)}</tbody></table></div>{principals.state.value.nextCursor ? <div className="identity360-load-more"><Button variant="outlined" onClick={() => void principals.loadMore(`${V1_ROOT}/principals?identityId=${encodeURIComponent(identityId)}&limit=50`)}>Load more Principals</Button></div> : null}</> : null}
              <div className="identity360-info"><strong>Identity, Principal, Credential.</strong><span>Identity is the governed subject. Principal is the technical representation/account. Credentials belong to Principals.</span></div>
            </section>
          </TabPanel>

          <TabPanel groupId="identity360" tabId="access" active={view === 'access'}>
            <section className="identity360-section"><div className="identity360-info prominent"><strong>Assignments are authoritative business intent. Effective access is a derived projection.</strong><span>Technical fulfillment and provider observation remain separate from both.</span></div>
              <article className="identity360-subpanel"><header className="identity360-section-header"><div><h2>Access assignments <span className="identity360-badge authoritative">Authoritative</span></h2><p>Direct and indirect assignment intent for this governed Identity.</p></div><Button variant="outlined" size="small" onClick={() => navigateConsole('/access')}>Open Access workspace</Button></header>
                <PagedTableState state={assignments.state as LoadState<Page<unknown>>} empty="No AccessAssignments" retry={() => setReloadKey((v) => v + 1)} />
                {assignments.state.status === 'ready' && assignments.state.value.items.length ? <><div className="identity360-table-wrap"><table className="identity360-table"><thead><tr><th>Target</th><th>Kind</th><th>Assignment lifecycle</th><th>Valid from</th><th>Valid until</th><th>Principal constraint</th><th>Assignment ID</th></tr></thead><tbody>{assignments.state.value.items.map((assignment) => <tr key={assignment.id}><td className="mono">{shortId(assignment.roleId ?? assignment.entitlementId)}</td><td>{assignment.targetKind}</td><td><StatusChip label={assignment.lifecycleState} color={tone(assignment.lifecycleState)} /></td><td>{formatDateTime(assignment.validFrom)}</td><td>{formatDateTime(assignment.validUntil)}</td><td>{assignment.principalConstraintKind}{assignment.specificPrincipalId ? <small className="identity360-cell-note mono">{shortId(assignment.specificPrincipalId)}</small> : null}</td><td className="mono">{shortId(assignment.id)}</td></tr>)}</tbody></table></div>{assignments.state.value.nextCursor ? <div className="identity360-load-more"><Button variant="outlined" onClick={() => void assignments.loadMore(`${V1_ROOT}/identities/${encodeURIComponent(identityId)}/access-assignments?limit=50`)}>Load more assignments</Button></div> : null}</> : null}
              </article>
              <article className="identity360-subpanel"><header className="identity360-section-header"><div><h2>Effective access <span className="identity360-badge projection">Projection</span></h2><p>Entitlement-level access currently derived for this Identity. An ineligible Identity contributes no semantic EffectiveAccess authority.</p></div></header>
                <PagedTableState state={effective.state as LoadState<Page<unknown>>} empty="No EffectiveAccess" retry={() => setReloadKey((v) => v + 1)} />
                {effective.state.status === 'ready' && effective.state.value.items.length ? <><div className="identity360-table-wrap"><table className="identity360-table"><thead><tr><th>Entitlement</th><th>Principal constraint</th><th>Support count</th><th>Computed</th><th>Projection generation</th><th>EffectiveAccess ID</th></tr></thead><tbody>{effective.state.value.items.map((item) => <tr key={item.id}><td className="mono">{shortId(item.entitlementId)}</td><td>{item.principalConstraintKey}</td><td>{item.supportCount}</td><td>{formatDateTime(item.computedAt)}</td><td>{item.projectionGeneration}</td><td className="mono">{shortId(item.id)}</td></tr>)}</tbody></table></div>{effective.state.value.nextCursor ? <div className="identity360-load-more"><Button variant="outlined" onClick={() => void effective.loadMore(`${V1_ROOT}/effective-access?identityId=${encodeURIComponent(identityId)}&limit=50`)}>Load more effective access</Button></div> : null}</> : null}
              </article>
            </section>
          </TabPanel>

          <TabPanel groupId="identity360" tabId="governance" active={view === 'governance'}>
            <section className="identity360-section"><article className="identity360-subpanel"><header className="identity360-section-header"><div><h2>Access reviews</h2><p>Governance-owned review campaigns for this governed subject.</p></div><Button variant="outlined" size="small" onClick={() => navigateConsole('/reviews')}>View all</Button></header>
              <PagedTableState state={campaigns.state as LoadState<Page<unknown>>} empty="No subject review campaigns" retry={() => setReloadKey((v) => v + 1)} />
              {campaigns.state.status === 'ready' && campaigns.state.value.items.length ? <><div className="identity360-table-wrap"><table className="identity360-table"><thead><tr><th>Campaign ID</th><th>State</th><th>Reviewer Identity</th><th>Snapshot time</th><th>Progress</th><th>Updated</th></tr></thead><tbody>{campaigns.state.value.items.map((campaign) => <tr key={campaign.id}><td className="mono">{shortId(campaign.id)}</td><td><StatusChip label={campaign.state} color={tone(campaign.state)} /></td><td className="mono">{shortId(campaign.reviewerIdentityId)}</td><td>{formatDateTime(campaign.snapshotAt)}</td><td>{campaign.decidedItemCount} / {campaign.generatedItemCount}</td><td>{formatDateTime(campaign.updatedAt)}</td></tr>)}</tbody></table></div>{campaigns.state.value.nextCursor ? <div className="identity360-load-more"><Button variant="outlined" onClick={() => void campaigns.loadMore(`${V1_ROOT}/governance/subjects/${encodeURIComponent(identityId)}/review-campaigns?limit=50`)}>Load more reviews</Button></div> : null}</> : null}
              </article>
              <article className="identity360-subpanel"><header className="identity360-section-header"><div><h2>Governance exceptions</h2><p>Current and historical subject-scoped SoD exceptions. Approval evidence does not itself create Access authority.</p></div><Button variant="outlined" size="small" onClick={() => navigateConsole('/policies')}>Open Governance</Button></header>
              <PagedTableState state={exceptions.state as LoadState<Page<unknown>>} empty="No subject GovernanceExceptions" retry={() => setReloadKey((v) => v + 1)} />
              {exceptions.state.status === 'ready' && exceptions.state.value.items.length ? <><div className="identity360-table-wrap"><table className="identity360-table"><thead><tr><th>Exception ID</th><th>SoD rule</th><th>State</th><th>Valid from</th><th>Valid until</th><th>Revision</th></tr></thead><tbody>{exceptions.state.value.items.map((exception) => <tr key={exception.id}><td className="mono">{shortId(exception.id)}</td><td className="mono">{shortId(exception.sodRuleId)}</td><td><StatusChip label={exception.lifecycleState} color={tone(exception.lifecycleState)} /></td><td>{formatDateTime(exception.validFrom)}</td><td>{formatDateTime(exception.validUntil)}</td><td>{exception.revision}</td></tr>)}</tbody></table></div>{exceptions.state.value.nextCursor ? <div className="identity360-load-more"><Button variant="outlined" onClick={() => void exceptions.loadMore(`${V1_ROOT}/governance/subjects/${encodeURIComponent(identityId)}/exceptions?limit=50`)}>Load more exceptions</Button></div> : null}</> : null}
              </article>
              <div className="identity360-info"><strong>Access request history is intentionally not shown here.</strong><span>Request visibility remains participant-scoped unless a separately accepted administrative subject-read contract exists.</span></div>
            </section>
          </TabPanel>

          <TabPanel groupId="identity360" tabId="evidence" active={view === 'evidence'}>
            <section className="identity360-section"><header className="identity360-section-header"><div><h2>Recent Identity evidence</h2><p>Direct immutable AuditRecord evidence whose resource is this Identity. This is not an exhaustive cross-capability activity history.</p></div><Button variant="outlined" size="small" onClick={() => navigateConsole('/audit')}>Open Audit workspace</Button></header>
              <PagedTableState state={evidence.state as LoadState<Page<unknown>>} empty="No direct Identity AuditRecords" retry={() => setReloadKey((v) => v + 1)} />
              {evidence.state.status === 'ready' && evidence.state.value.items.length ? <><div className="identity360-table-wrap"><table className="identity360-table"><thead><tr><th>Time</th><th>Outcome</th><th>Action</th><th>Actor Identity</th><th>Correlation ID</th><th>AuditRecord ID</th></tr></thead><tbody>{evidence.state.value.items.map((record) => <tr key={record.id}><td>{formatDateTime(record.occurredAt)}</td><td><StatusChip label={record.outcome} color={tone(record.outcome)} /></td><td><strong>{record.actionType}</strong></td><td className="mono">{shortId(record.actorId)}</td><td className="mono">{shortId(record.correlationId)}</td><td className="mono">{shortId(record.id)}</td></tr>)}</tbody></table></div><div className="identity360-metrics evidence"><div><strong>{evidence.state.value.items.length}</strong><span>Loaded direct records</span></div><div><strong>{formatDateTime(evidence.state.value.items[0]?.occurredAt)}</strong><span>Newest loaded evidence</span></div><div><strong>Identity</strong><span>Audited resource type</span></div></div>{evidence.state.value.nextCursor ? <div className="identity360-load-more"><Button variant="outlined" onClick={() => void evidence.loadMore(`${V1_ROOT}/audit-records?resourceType=identity&resourceId=${encodeURIComponent(identityId)}&limit=50`)}>Load more evidence</Button></div> : null}</> : null}
              <div className="identity360-info"><strong>Broader investigations continue in Audit.</strong><span>Related Access, Governance, Integration, or system evidence is not silently joined into this direct Identity view.</span></div>
            </section>
          </TabPanel>
        </div>
      </section>

      <Modal open={editOpen} title="Edit Identity metadata" description="Display name is the mutable non-lifecycle Identity metadata field in the current contract." busy={pendingAction === 'rename'} onClose={() => { if (pendingAction !== 'rename') { setDisplayName(identity.displayName); setEditOpen(false); } }}>
        <Box sx={{ display: 'grid', gap: 1.5 }}><TextInput autoFocus label="Display name" required value={displayName} onChange={(e) => setDisplayName(e.target.value)} /><Box sx={{ display: 'flex', justifyContent: 'flex-end', gap: 1 }}><Button variant="text" onClick={() => setEditOpen(false)}>Cancel</Button><Button variant="contained" disabled={!displayName.trim() || displayName.trim() === identity.displayName || pendingAction === 'rename'} onClick={() => void updateName()}>{pendingAction === 'rename' ? 'Saving…' : 'Save metadata'}</Button></Box></Box>
      </Modal>

      <Modal open={moreOpen} title="Advanced Identity correction" description="Merge and split are governed-subject corrections, not ordinary account maintenance." busy={pendingAction === 'merge' || pendingAction === 'split'} onClose={() => { if (!pendingAction) setMoreOpen(false); }}>
        <Box sx={{ display: 'grid', gap: 1.5 }}><div className="identity360-correction-tabs"><button className={correctionMode === 'merge' ? 'active' : ''} onClick={() => setCorrectionMode('merge')}>Merge</button><button className={correctionMode === 'split' ? 'active' : ''} onClick={() => setCorrectionMode('split')}>Split</button></div>
          {correctionMode === 'merge' ? <><div className="identity360-warning"><strong>Permanent correction.</strong> The current Identity survives. The absorbed Identity becomes DECOMMISSIONED. Foreign-capability authority never transfers implicitly.</div><TextInput label="Absorbed Identity ID" value={mergeValues.absorbedIdentityId} onChange={(e) => { setMergeValues((v) => ({ ...v, absorbedIdentityId: e.target.value })); setMergeTarget(null); }} /><Button variant="outlined" disabled={!mergeValues.absorbedIdentityId.trim() || mergeTarget?.status === 'loading'} onClick={() => void loadMergeTarget()}>{mergeTarget?.status === 'loading' ? 'Checking…' : 'Review target'}</Button>{mergeTarget?.status === 'error' ? <ErrorPanel error={mergeTarget.error} /> : null}{mergeTarget?.status === 'ready' ? <div className="identity360-target"><strong>{mergeTarget.value.displayName}</strong><span>{mergeTarget.value.type} · {mergeTarget.value.lifecycleState} · revision {mergeTarget.value.revision}</span></div> : null}<TextInput label="Correction reason" multiline minRows={3} value={mergeValues.reason} onChange={(e) => setMergeValues((v) => ({ ...v, reason: e.target.value }))} /><Button color="error" variant="contained" disabled={mergeTarget?.status !== 'ready' || !mergeValues.reason.trim()} onClick={() => setConfirmation({ kind: 'merge' })}>Review merge consequence</Button></> : <><div className="identity360-warning"><strong>Explicit relationship selection required.</strong> The new same-type Identity starts PENDING. No AccessAssignment or Governance authority is copied.</div><TextInput label="New Identity display name" value={splitValues.newDisplayName} onChange={(e) => setSplitValues((v) => ({ ...v, newDisplayName: e.target.value }))} /><TextInput label="Source record IDs" multiline minRows={3} value={splitValues.sourceRecordIds} onChange={(e) => setSplitValues((v) => ({ ...v, sourceRecordIds: e.target.value }))} /><TextInput label="Principal IDs" multiline minRows={3} value={splitValues.principalIds} onChange={(e) => setSplitValues((v) => ({ ...v, principalIds: e.target.value }))} /><TextInput label="Correction reason" multiline minRows={2} value={splitValues.reason} onChange={(e) => setSplitValues((v) => ({ ...v, reason: e.target.value }))} /><Button color="error" variant="contained" disabled={!splitValues.newDisplayName.trim() || !splitValues.reason.trim() || !splitHasSelection} onClick={() => setConfirmation({ kind: 'split' })}>Review split consequence</Button></>}
        </Box>
      </Modal>

      <ConfirmDialog open={confirmation !== null} title={confirm.title} confirmLabel={confirm.label} destructive={confirmation?.kind !== 'lifecycle' || confirmation?.action !== 'activate'} busy={pendingAction !== null} onCancel={() => setConfirmation(null)} onConfirm={() => void (confirmation?.kind === 'lifecycle' ? executeLifecycle(confirmation.action) : confirmation?.kind === 'merge' ? executeMerge() : executeSplit())}>{confirm.body}</ConfirmDialog>
    </main>
  );
}
