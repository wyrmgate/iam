import { type FormEvent, useEffect, useMemo, useState } from 'react';
import { ApiError, apiDownload, apiRequest, V1_ROOT, withCursor, type ApiResult } from './api';

type JsonObject = Record<string, unknown>;
type Page = { items: JsonObject[]; nextCursor?: string | null };
type FieldValue = string | boolean;
type Values = Record<string, FieldValue>;

type Field = {
  name: string;
  label: string;
  type?: 'text' | 'number' | 'datetime-local' | 'textarea' | 'checkbox' | 'select';
  options?: string[];
  required?: boolean;
  placeholder?: string;
  help?: string;
};

type Action = {
  label: string;
  path: (id: string) => string;
  destructive?: boolean;
  body?: unknown;
  fields?: Field[];
  buildBody?: (values: Values) => unknown;
  idempotent?: boolean;
  requireEtag?: boolean;
};

type CollectionSpec = {
  eyebrow: string;
  title: string;
  description: string;
  listPath: string | ((cursor?: string | null) => string);
  detailPath: (id: string) => string;
  columns: Array<{ key: string; label: string }>;
  createPath?: string;
  createFields?: Field[];
  buildCreateBody?: (values: Values) => unknown;
  actions?: Action[];
  emptyText?: string;
};

const sensitiveKey = /(secret|password|token|private|credentialmaterial|raw|configuration)/i;
const secretReferenceKey = /^(secretReference|referenceKey)$/i;

function human(value: unknown): string {
  if (value === null || value === undefined || value === '') return '—';
  if (typeof value === 'boolean') return value ? 'Yes' : 'No';
  if (typeof value === 'string') {
    const parsed = Date.parse(value);
    if (/^\d{4}-\d{2}-\d{2}T/.test(value) && !Number.isNaN(parsed)) return new Date(parsed).toLocaleString();
    return value;
  }
  if (typeof value === 'number') return String(value);
  if (Array.isArray(value)) return value.map(human).join(', ');
  return '[structured value]';
}

function displayValue(key: string, value: unknown): string {
  if (secretReferenceKey.test(key)) return 'Hidden by console';
  if (sensitiveKey.test(key)) return value ? 'Configured / redacted' : '—';
  return human(value);
}

function formatError(error: unknown): { title: string; detail: string; correlationId?: string | null } {
  if (error instanceof ApiError) {
    if (error.status === 401) return { title: 'Authentication required', detail: 'A trusted control-plane session is required.', correlationId: error.correlationId };
    if (error.status === 403) return { title: 'Not authorized', detail: 'Administrative Authorization denied this operation. The browser does not infer or override authority.', correlationId: error.correlationId };
    if (error.status === 404) return { title: 'Not found', detail: 'The resource was not found inside the current tenant/isolation boundary.', correlationId: error.correlationId };
    if (error.status === 409) return { title: 'Conflict', detail: error.message, correlationId: error.correlationId };
    if (error.status === 412) return { title: 'Stale revision', detail: 'The resource changed. Refresh it and retry using the latest ETag.', correlationId: error.correlationId };
    if (error.status === 503) return { title: 'Required evaluator unavailable', detail: error.message, correlationId: error.correlationId };
    return { title: `Request failed (${error.status})`, detail: error.message, correlationId: error.correlationId };
  }
  return { title: 'Request failed', detail: error instanceof Error ? error.message : 'The request could not be completed.' };
}

function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const copy = formatError(error);
  return <div className="state-card state-error" role="alert"><strong>{copy.title}</strong><span>{copy.detail}</span>{copy.correlationId && <span className="mono subline">Correlation: {copy.correlationId}</span>}{onRetry && <button className="button secondary" onClick={onRetry}>Retry</button>}</div>;
}

function FieldControl({ field, value, onChange }: { field: Field; value: FieldValue | undefined; onChange: (value: FieldValue) => void }) {
  const id = `field-${field.name}`;
  if (field.type === 'checkbox') return <label className="check-field"><input id={id} type="checkbox" checked={Boolean(value)} onChange={(e) => onChange(e.target.checked)} /><span>{field.label}</span>{field.help && <small>{field.help}</small>}</label>;
  if (field.type === 'select') return <label><span>{field.label}</span><select id={id} required={field.required} value={String(value ?? '')} onChange={(e) => onChange(e.target.value)}><option value="">Select…</option>{field.options?.map((option) => <option key={option} value={option}>{option}</option>)}</select>{field.help && <small>{field.help}</small>}</label>;
  if (field.type === 'textarea') return <label><span>{field.label}</span><textarea id={id} required={field.required} value={String(value ?? '')} placeholder={field.placeholder} onChange={(e) => onChange(e.target.value)} />{field.help && <small>{field.help}</small>}</label>;
  return <label><span>{field.label}</span><input id={id} type={field.type ?? 'text'} required={field.required} value={String(value ?? '')} placeholder={field.placeholder} onChange={(e) => onChange(e.target.value)} />{field.help && <small>{field.help}</small>}</label>;
}

function SemanticForm({ fields, submitLabel, onSubmit, warning }: { fields: Field[]; submitLabel: string; onSubmit: (values: Values) => Promise<void>; warning?: string }) {
  const initial = useMemo(() => Object.fromEntries(fields.map((field) => [field.name, field.type === 'checkbox' ? false : ''])) as Values, [fields]);
  const [values, setValues] = useState<Values>(initial);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [success, setSuccess] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (warning && !window.confirm(warning)) return;
    setPending(true); setError(null); setSuccess(false);
    try {
      await onSubmit(values);
      setValues(initial); setSuccess(true);
    } catch (cause) { setError(cause); }
    finally { setPending(false); }
  }

  return <form className="semantic-form" onSubmit={(event) => void submit(event)}>
    <div className="form-grid">{fields.map((field) => <FieldControl key={field.name} field={field} value={values[field.name]} onChange={(value) => setValues((current) => ({ ...current, [field.name]: value }))} />)}</div>
    {warning && <p className="warning-copy">{warning}</p>}
    {error && <ErrorState error={error} />}
    {success && <div className="success-note" role="status">Operation accepted.</div>}
    <button className="button" disabled={pending}>{pending ? 'Working…' : submitLabel}</button>
  </form>;
}

function ResourceDetails({ resource }: { resource: JsonObject }) {
  const entries = Object.entries(resource).filter(([key]) => !key.startsWith('_'));
  return <dl className="resource-details">{entries.map(([key, value]) => <div key={key}><dt>{key}</dt><dd>{displayValue(key, value)}</dd></div>)}</dl>;
}

function CollectionWorkspace({ spec }: { spec: CollectionSpec }) {
  const [pages, setPages] = useState<Page[]>([]);
  const [state, setState] = useState<'loading' | 'ready' | 'error'>('loading');
  const [error, setError] = useState<unknown>(null);
  const [reload, setReload] = useState(0);
  const [selected, setSelected] = useState<string | null>(null);
  const [detail, setDetail] = useState<ApiResult<JsonObject> | null>(null);
  const [detailError, setDetailError] = useState<unknown>(null);
  const [pendingAction, setPendingAction] = useState<string | null>(null);
  const [actionValues, setActionValues] = useState<Values>({});
  const items = useMemo(() => pages.flatMap((page) => page.items), [pages]);
  const nextCursor = pages.at(-1)?.nextCursor ?? null;

  function pathFor(cursor?: string | null) { return typeof spec.listPath === 'function' ? spec.listPath(cursor) : withCursor(spec.listPath, cursor); }

  useEffect(() => {
    const controller = new AbortController();
    setState('loading'); setPages([]); setError(null);
    apiRequest<Page>(pathFor(), { signal: controller.signal }).then(({ data }) => { setPages([data]); setState('ready'); }).catch((cause: unknown) => {
      if (cause instanceof DOMException && cause.name === 'AbortError') return;
      setError(cause); setState('error');
    });
    return () => controller.abort();
    // spec is a static module constant; reload is the intentional refresh trigger.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reload]);

  useEffect(() => {
    if (!selected) { setDetail(null); return; }
    const controller = new AbortController(); setDetail(null); setDetailError(null);
    apiRequest<JsonObject>(spec.detailPath(selected), { signal: controller.signal }).then(setDetail).catch((cause: unknown) => {
      if (cause instanceof DOMException && cause.name === 'AbortError') return; setDetailError(cause);
    });
    return () => controller.abort();
  }, [selected, spec]);

  async function loadMore() {
    if (!nextCursor) return;
    setState('loading');
    try { const { data } = await apiRequest<Page>(pathFor(nextCursor)); setPages((current) => [...current, data]); setState('ready'); }
    catch (cause) { setError(cause); setState('error'); }
  }

  async function create(values: Values) {
    if (!spec.createPath || !spec.buildCreateBody) return;
    await apiRequest<JsonObject>(spec.createPath, { method: 'POST', idempotent: true, body: spec.buildCreateBody(values) });
    setReload((value) => value + 1);
  }

  async function execute(action: Action) {
    if (!detail || !selected) return;
    if (action.destructive && !window.confirm(`${action.label} changes authoritative state. Confirm this operation.`)) return;
    setPendingAction(action.label); setDetailError(null);
    try {
      const result = await apiRequest<JsonObject>(action.path(selected), {
        method: 'POST',
        body: action.buildBody ? action.buildBody(actionValues) : action.body,
        etag: action.requireEtag === false ? undefined : detail.etag ?? undefined,
        idempotent: action.idempotent !== false,
      });
      setDetail(result); setActionValues({});
      setReload((value) => value + 1);
    } catch (cause) { setDetailError(cause); }
    finally { setPendingAction(null); }
  }

  return <section className="page-stack">
    <header className="page-header"><p className="eyebrow">{spec.eyebrow}</p><h1>{spec.title}</h1><p>{spec.description}</p></header>
    {spec.createFields && spec.buildCreateBody && <details className="card expandable"><summary>Create</summary><SemanticForm fields={spec.createFields} submitLabel={`Create ${spec.title.replace(/s$/, '')}`} onSubmit={create} /></details>}
    {state === 'error' && <ErrorState error={error} onRetry={() => setReload((value) => value + 1)} />}
    {state === 'loading' && items.length === 0 && <div className="state-card">Loading…</div>}
    {state === 'ready' && items.length === 0 && <div className="state-card">{spec.emptyText ?? 'No resources found.'}</div>}
    {items.length > 0 && <div className="table-wrap"><table><thead><tr>{spec.columns.map((column) => <th key={column.key}>{column.label}</th>)}<th><span className="sr-only">Open</span></th></tr></thead><tbody>{items.map((item, index) => <tr key={String(item.id ?? index)}>{spec.columns.map((column) => <td key={column.key}>{displayValue(column.key, item[column.key])}</td>)}<td>{item.id && <button className="text-button" onClick={() => setSelected(String(item.id))}>View</button>}</td></tr>)}</tbody></table></div>}
    {nextCursor && <button className="button secondary load-more" disabled={state === 'loading'} onClick={() => void loadMore()}>{state === 'loading' ? 'Loading…' : 'Load more'}</button>}
    {selected && <section className="card detail-panel"><div className="split-header"><div><span className="label">Detail</span><strong className="mono">{selected}</strong></div><button className="text-button" onClick={() => setSelected(null)}>Close</button></div>{detailError && <ErrorState error={detailError} onRetry={() => setSelected((id) => id)} />}{!detail && !detailError && <div className="state-card">Loading detail…</div>}{detail && <><ResourceDetails resource={detail.data} />{spec.actions && spec.actions.length > 0 && <div className="semantic-actions">{spec.actions.map((action) => <div className="action-block" key={action.label}>{action.fields && action.fields.length > 0 && <div className="form-grid compact">{action.fields.map((field) => <FieldControl key={field.name} field={field} value={actionValues[field.name]} onChange={(value) => setActionValues((current) => ({ ...current, [field.name]: value }))} />)}</div>}<button className={action.destructive ? 'button danger' : 'button secondary'} disabled={pendingAction !== null} onClick={() => void execute(action)}>{pendingAction === action.label ? 'Working…' : action.label}</button></div>)}</div>}</>}</section>}
  </section>;
}

function Tabs({ tabs }: { tabs: Array<{ id: string; label: string; content: JSX.Element }> }) {
  const [active, setActive] = useState(tabs[0]?.id ?? '');
  const current = tabs.find((tab) => tab.id === active) ?? tabs[0];
  return <><div className="tabs" role="tablist">{tabs.map((tab) => <button key={tab.id} role="tab" aria-selected={active === tab.id} className={active === tab.id ? 'active' : ''} onClick={() => setActive(tab.id)}>{tab.label}</button>)}</div>{current?.content}</>;
}

function nullIfEmpty(value: FieldValue | undefined): string | null { const text = String(value ?? '').trim(); return text ? text : null; }
function csv(value: FieldValue | undefined): string[] { return String(value ?? '').split(/[\n,]/).map((part) => part.trim()).filter(Boolean); }
function numberValue(value: FieldValue | undefined): number { return Number(String(value ?? '0')); }
function isoOrNull(value: FieldValue | undefined): string | null { const text = String(value ?? '').trim(); return text ? new Date(text).toISOString() : null; }
function scope(values: Values): JsonObject {
  const type = String(values.scopeType ?? 'GLOBAL');
  return { type, resourceType: nullIfEmpty(values.scopeResourceType), resourceId: nullIfEmpty(values.scopeResourceId), scopeKey: nullIfEmpty(values.scopeKey) };
}

const principals: CollectionSpec = {
  eyebrow: 'Identity', title: 'Principals', description: 'Technical account authority owned by Identity. Provider observations are not Principal authority.',
  listPath: `${V1_ROOT}/principals`, detailPath: (id) => `${V1_ROOT}/principals/${encodeURIComponent(id)}`,
  columns: [{ key: 'nativePrincipalKey', label: 'Native key' }, { key: 'applicationTargetId', label: 'Target' }, { key: 'identityId', label: 'Identity' }, { key: 'lifecycleState', label: 'Lifecycle' }],
  createPath: `${V1_ROOT}/principals`, createFields: [{ name: 'applicationTargetId', label: 'Application Target ID', required: true }, { name: 'nativePrincipalKey', label: 'Native principal key', required: true }],
  buildCreateBody: (v) => ({ applicationTargetId: v.applicationTargetId, nativePrincipalKey: v.nativePrincipalKey }),
  actions: [{ label: 'Correlate to Identity', path: (id) => `${V1_ROOT}/principals/${encodeURIComponent(id)}:correlate`, fields: [{ name: 'identityId', label: 'Identity ID', required: true }], buildBody: (v) => ({ identityId: v.identityId }) }],
};

const applications: CollectionSpec = {
  eyebrow: 'Catalog', title: 'Applications', description: 'Authoritative governed Applications. Provider discovery cannot create or mutate these resources.',
  listPath: `${V1_ROOT}/applications`, detailPath: (id) => `${V1_ROOT}/applications/${encodeURIComponent(id)}`,
  columns: [{ key: 'code', label: 'Code' }, { key: 'name', label: 'Name' }, { key: 'lifecycleState', label: 'Lifecycle' }, { key: 'revision', label: 'Revision' }],
  createPath: `${V1_ROOT}/applications`, createFields: [{ name: 'code', label: 'Code', required: true }, { name: 'name', label: 'Name', required: true }], buildCreateBody: (v) => ({ code: v.code, name: v.name }),
  actions: [{ label: 'Retire', path: (id) => `${V1_ROOT}/applications/${encodeURIComponent(id)}/retire`, destructive: true }],
};

const roles: CollectionSpec = {
  eyebrow: 'Catalog', title: 'Roles', description: 'Governed business/application Roles. Composition is versioned separately from Role identity.',
  listPath: `${V1_ROOT}/roles`, detailPath: (id) => `${V1_ROOT}/roles/${encodeURIComponent(id)}`,
  columns: [{ key: 'code', label: 'Code' }, { key: 'name', label: 'Name' }, { key: 'roleType', label: 'Type' }, { key: 'lifecycleState', label: 'Lifecycle' }],
  createPath: `${V1_ROOT}/roles`, createFields: [{ name: 'roleType', label: 'Role type', type: 'select', options: ['BUSINESS', 'APPLICATION'], required: true }, { name: 'applicationId', label: 'Application ID', help: 'Required for APPLICATION; blank for BUSINESS.' }, { name: 'code', label: 'Code', required: true }, { name: 'name', label: 'Name', required: true }],
  buildCreateBody: (v) => ({ roleType: v.roleType, applicationId: nullIfEmpty(v.applicationId), code: v.code, name: v.name }),
  actions: [{ label: 'Retire', path: (id) => `${V1_ROOT}/roles/${encodeURIComponent(id)}/retire`, destructive: true }],
};

const assignments: CollectionSpec = {
  eyebrow: 'Access', title: 'Access Assignments', description: 'Authoritative business access intent. Provider desired/observed state is deliberately separate.',
  listPath: `${V1_ROOT}/access-assignments`, detailPath: (id) => `${V1_ROOT}/access-assignments/${encodeURIComponent(id)}`,
  columns: [{ key: 'identityId', label: 'Identity' }, { key: 'targetKind', label: 'Target kind' }, { key: 'lifecycleState', label: 'Lifecycle' }, { key: 'principalConstraintKind', label: 'Principal constraint' }],
  createPath: `${V1_ROOT}/access-assignments`, createFields: [{ name: 'identityId', label: 'Identity ID', required: true }, { name: 'targetKind', label: 'Target kind', type: 'select', options: ['ROLE', 'ENTITLEMENT'], required: true }, { name: 'roleId', label: 'Role ID', help: 'Only for ROLE.' }, { name: 'entitlementId', label: 'Entitlement ID', help: 'Only for ENTITLEMENT.' }, { name: 'principalConstraintKind', label: 'Principal constraint', type: 'select', options: ['ANY', 'SPECIFIC'], required: true }, { name: 'specificPrincipalId', label: 'Specific Principal ID', help: 'Only for SPECIFIC.' }, { name: 'validFrom', label: 'Valid from', type: 'datetime-local' }, { name: 'validUntil', label: 'Valid until', type: 'datetime-local' }],
  buildCreateBody: (v) => ({ identityId: v.identityId, targetKind: v.targetKind, roleId: nullIfEmpty(v.roleId), entitlementId: nullIfEmpty(v.entitlementId), principalConstraintKind: v.principalConstraintKind, specificPrincipalId: nullIfEmpty(v.specificPrincipalId), validFrom: isoOrNull(v.validFrom), validUntil: isoOrNull(v.validUntil) }),
  actions: [
    { label: 'Suspend', path: (id) => `${V1_ROOT}/access-assignments/${encodeURIComponent(id)}/suspend` },
    { label: 'Resume', path: (id) => `${V1_ROOT}/access-assignments/${encodeURIComponent(id)}/resume` },
    { label: 'Cancel', path: (id) => `${V1_ROOT}/access-assignments/${encodeURIComponent(id)}/cancel`, destructive: true },
    { label: 'Revoke', path: (id) => `${V1_ROOT}/access-assignments/${encodeURIComponent(id)}/revoke`, destructive: true },
  ],
};

const effectiveAccess: CollectionSpec = {
  eyebrow: 'Access · projection', title: 'Effective Access', description: 'Read-only entitlement-level projection derived from authoritative assignments. It is not editable business intent.',
  listPath: `${V1_ROOT}/effective-access`, detailPath: (id) => `${V1_ROOT}/effective-access/${encodeURIComponent(id)}`,
  columns: [{ key: 'identityId', label: 'Identity' }, { key: 'entitlementId', label: 'Entitlement' }, { key: 'principalConstraintKey', label: 'Principal constraint' }, { key: 'supportCount', label: 'Supports' }],
};

const approvals: CollectionSpec = {
  eyebrow: 'Governance', title: 'Approval Inbox', description: 'Current reusable approval work assigned to the authenticated governed Identity.',
  listPath: `${V1_ROOT}/governance/approval-inbox`, detailPath: (id) => `${V1_ROOT}/governance/approvals/${encodeURIComponent(id)}`,
  columns: [{ key: 'subjectKind', label: 'Subject' }, { key: 'subjectId', label: 'Subject ID' }, { key: 'state', label: 'State' }, { key: 'currentStageOrdinal', label: 'Stage' }],
  actions: [
    { label: 'Approve', path: (id) => `${V1_ROOT}/governance/approvals/${encodeURIComponent(id)}/approve`, fields: [{ name: 'reason', label: 'Reason', type: 'textarea' }], buildBody: (v) => ({ reason: nullIfEmpty(v.reason) }) },
    { label: 'Reject', path: (id) => `${V1_ROOT}/governance/approvals/${encodeURIComponent(id)}/reject`, destructive: true, fields: [{ name: 'reason', label: 'Reason', type: 'textarea' }], buildBody: (v) => ({ reason: nullIfEmpty(v.reason) }) },
  ],
};

const reviewCampaigns: CollectionSpec = {
  eyebrow: 'Governance', title: 'Review Campaigns', description: 'Identity access certification campaigns. Campaign completion and remediation are distinct states.',
  listPath: `${V1_ROOT}/governance/review-campaigns`, detailPath: (id) => `${V1_ROOT}/governance/review-campaigns/${encodeURIComponent(id)}`,
  columns: [{ key: 'subjectIdentityId', label: 'Subject Identity' }, { key: 'reviewerIdentityId', label: 'Reviewer' }, { key: 'state', label: 'State' }, { key: 'generatedItemCount', label: 'Items' }],
  createPath: `${V1_ROOT}/governance/review-campaigns`, createFields: [{ name: 'subjectIdentityId', label: 'Subject Identity ID', required: true }, { name: 'reviewerIdentityId', label: 'Reviewer Identity ID', required: true }, { name: 'snapshotAt', label: 'Snapshot at', type: 'datetime-local', required: true }], buildCreateBody: (v) => ({ subjectIdentityId: v.subjectIdentityId, reviewerIdentityId: v.reviewerIdentityId, snapshotAt: new Date(String(v.snapshotAt)).toISOString() }),
  actions: [{ label: 'Start campaign', path: (id) => `${V1_ROOT}/governance/review-campaigns/${encodeURIComponent(id)}/start` }],
};

const reviewInbox: CollectionSpec = {
  eyebrow: 'Governance', title: 'Review Inbox', description: 'Pending ReviewItems assigned to the authenticated governed Identity.',
  listPath: `${V1_ROOT}/governance/review-inbox`, detailPath: (id) => `${V1_ROOT}/governance/review-items/${encodeURIComponent(id)}`,
  columns: [{ key: 'identityId', label: 'Identity' }, { key: 'targetKind', label: 'Target kind' }, { key: 'decision', label: 'Decision' }, { key: 'state', label: 'State' }],
  actions: [
    { label: 'Keep', path: (id) => `${V1_ROOT}/governance/review-items/${encodeURIComponent(id)}/keep`, fields: [{ name: 'reason', label: 'Reason', type: 'textarea' }], buildBody: (v) => ({ reason: nullIfEmpty(v.reason) }) },
    { label: 'Revoke access', path: (id) => `${V1_ROOT}/governance/review-items/${encodeURIComponent(id)}/revoke`, destructive: true, fields: [{ name: 'reason', label: 'Reason', type: 'textarea' }], buildBody: (v) => ({ reason: nullIfEmpty(v.reason) }) },
  ],
};

const adminRoles: CollectionSpec = {
  eyebrow: 'Administration', title: 'Administrative Roles', description: 'Control-plane permissions only. These are not Catalog Roles and never derive from OAuth/OIDC claims.',
  listPath: `${V1_ROOT}/administrative-roles`, detailPath: (id) => `${V1_ROOT}/administrative-roles/${encodeURIComponent(id)}`,
  columns: [{ key: 'code', label: 'Code' }, { key: 'name', label: 'Name' }, { key: 'revision', label: 'Revision' }],
  createPath: `${V1_ROOT}/administrative-roles`, createFields: [{ name: 'code', label: 'Code', required: true }, { name: 'name', label: 'Name', required: true }, { name: 'permissions', label: 'Permissions', type: 'textarea', required: true, placeholder: 'identity:read\napplication:read', help: 'One semantic resource:action permission per line.' }], buildCreateBody: (v) => ({ code: v.code, name: v.name, permissions: csv(v.permissions) }),
  actions: [
    { label: 'Add permission', path: (id) => `${V1_ROOT}/administrative-roles/${encodeURIComponent(id)}:add-permission`, fields: [{ name: 'permission', label: 'Permission', required: true, placeholder: 'identity:read' }], buildBody: (v) => ({ permission: v.permission }) },
    { label: 'Remove permission', path: (id) => `${V1_ROOT}/administrative-roles/${encodeURIComponent(id)}:remove-permission`, fields: [{ name: 'permission', label: 'Permission', required: true, placeholder: 'identity:read' }], buildBody: (v) => ({ permission: v.permission }), destructive: true },
  ],
};

const scopeFields: Field[] = [
  { name: 'scopeType', label: 'Scope type', type: 'select', options: ['GLOBAL', 'ORGANIZATION', 'APPLICATION', 'APPLICATION_TARGET', 'SOURCE_SYSTEM', 'CONNECTOR_INSTANCE', 'IDENTITY_POPULATION', 'CANONICAL_ATTRIBUTE_CLASSIFICATION', 'SPECIFIC_RESOURCE'], required: true },
  { name: 'scopeResourceType', label: 'Scope resource type', help: 'Required only when the selected typed scope uses a concrete resource type.' },
  { name: 'scopeResourceId', label: 'Scope resource ID' },
  { name: 'scopeKey', label: 'Scope key', help: 'Used by classification/key-based scopes.' },
];

const adminGrants: CollectionSpec = {
  eyebrow: 'Administration', title: 'Administrative Grants', description: 'Direct scoped administrative authority with explicit grantability/delegability ceilings.',
  listPath: `${V1_ROOT}/administrative-grants`, detailPath: (id) => `${V1_ROOT}/administrative-grants/${encodeURIComponent(id)}`,
  columns: [{ key: 'beneficiaryIdentityId', label: 'Beneficiary' }, { key: 'roleId', label: 'Admin Role' }, { key: 'state', label: 'State' }, { key: 'validUntil', label: 'Valid until' }],
  createPath: `${V1_ROOT}/administrative-grants`, createFields: [{ name: 'beneficiaryIdentityId', label: 'Beneficiary Identity ID', required: true }, { name: 'roleId', label: 'Administrative Role ID', required: true }, ...scopeFields, { name: 'validFrom', label: 'Valid from', type: 'datetime-local' }, { name: 'validUntil', label: 'Valid until', type: 'datetime-local' }, { name: 'grantable', label: 'Grantable', type: 'checkbox' }, { name: 'delegable', label: 'Delegable', type: 'checkbox' }, { name: 'authorityBasisGrantId', label: 'Authority basis Grant ID', required: true }],
  buildCreateBody: (v) => ({ beneficiaryIdentityId: v.beneficiaryIdentityId, roleId: v.roleId, scope: scope(v), validFrom: isoOrNull(v.validFrom), validUntil: isoOrNull(v.validUntil), grantable: Boolean(v.grantable), delegable: Boolean(v.delegable), authorityBasisGrantId: v.authorityBasisGrantId }),
  actions: [{ label: 'Revoke grant', path: (id) => `${V1_ROOT}/administrative-grants/${encodeURIComponent(id)}:revoke`, destructive: true }],
};

const adminDelegations: CollectionSpec = {
  eyebrow: 'Administration', title: 'Administrative Delegations', description: 'Single-hop delegated authority. Re-delegation is not supported.',
  listPath: `${V1_ROOT}/administrative-delegations`, detailPath: (id) => `${V1_ROOT}/administrative-delegations/${encodeURIComponent(id)}`,
  columns: [{ key: 'delegateIdentityId', label: 'Delegate' }, { key: 'sourceGrantId', label: 'Source grant' }, { key: 'state', label: 'State' }, { key: 'validUntil', label: 'Valid until' }],
  createPath: `${V1_ROOT}/administrative-delegations`, createFields: [{ name: 'delegateIdentityId', label: 'Delegate Identity ID', required: true }, { name: 'sourceGrantId', label: 'Source direct Grant ID', required: true }, ...scopeFields, { name: 'validFrom', label: 'Valid from', type: 'datetime-local' }, { name: 'validUntil', label: 'Valid until', type: 'datetime-local', required: true }],
  buildCreateBody: (v) => ({ delegateIdentityId: v.delegateIdentityId, sourceGrantId: v.sourceGrantId, scope: scope(v), validFrom: isoOrNull(v.validFrom), validUntil: new Date(String(v.validUntil)).toISOString() }),
  actions: [{ label: 'Revoke delegation', path: (id) => `${V1_ROOT}/administrative-delegations/${encodeURIComponent(id)}:revoke`, destructive: true }],
};

const adminElevations: CollectionSpec = {
  eyebrow: 'Administration', title: 'Administrative Elevations', description: 'Finite JIT administrative authority. Governance approval is evidence; Administration remains authority owner.',
  listPath: `${V1_ROOT}/administrative-elevations`, detailPath: (id) => `${V1_ROOT}/administrative-elevations/${encodeURIComponent(id)}`,
  columns: [{ key: 'beneficiaryIdentityId', label: 'Beneficiary' }, { key: 'roleId', label: 'Admin Role' }, { key: 'state', label: 'State' }, { key: 'validUntil', label: 'Valid until' }],
  createPath: `${V1_ROOT}/administrative-elevations`, createFields: [{ name: 'beneficiaryIdentityId', label: 'Beneficiary Identity ID', required: true }, { name: 'roleId', label: 'Administrative Role ID', required: true }, ...scopeFields, { name: 'validFrom', label: 'Valid from', type: 'datetime-local' }, { name: 'validUntil', label: 'Valid until', type: 'datetime-local', required: true }, { name: 'authorityBasisGrantId', label: 'Authority basis Grant ID', required: true }],
  buildCreateBody: (v) => ({ beneficiaryIdentityId: v.beneficiaryIdentityId, roleId: v.roleId, scope: scope(v), validFrom: isoOrNull(v.validFrom), validUntil: new Date(String(v.validUntil)).toISOString(), authorityBasisGrantId: v.authorityBasisGrantId }),
  actions: [
    { label: 'Request approval', path: (id) => `${V1_ROOT}/administrative-elevations/${encodeURIComponent(id)}:request-approval` },
    { label: 'Apply approval result', path: (id) => `${V1_ROOT}/administrative-elevations/${encodeURIComponent(id)}:apply` },
    { label: 'Cancel elevation', path: (id) => `${V1_ROOT}/administrative-elevations/${encodeURIComponent(id)}:cancel`, destructive: true },
    { label: 'Revoke elevation', path: (id) => `${V1_ROOT}/administrative-elevations/${encodeURIComponent(id)}:revoke`, destructive: true },
  ],
};

const breakGlass: CollectionSpec = {
  eyebrow: 'Administration · emergency', title: 'Break-glass Operations', description: 'Emergency authority is separate from grants/elevation, time-bounded, assurance-aware and subject to durable notification/post-use review.',
  listPath: `${V1_ROOT}/administrative-break-glass-operations`, detailPath: (id) => `${V1_ROOT}/administrative-break-glass-operations/${encodeURIComponent(id)}`,
  columns: [{ key: 'beneficiaryIdentityId', label: 'Actor / beneficiary' }, { key: 'roleId', label: 'Role' }, { key: 'state', label: 'State' }, { key: 'validUntil', label: 'Valid until' }],
  createPath: `${V1_ROOT}/administrative-break-glass-operations`, createFields: [{ name: 'roleId', label: 'Emergency Administrative Role ID', required: true }, ...scopeFields, { name: 'validUntil', label: 'Valid until', type: 'datetime-local', required: true }, { name: 'reason', label: 'Emergency reason', type: 'textarea', required: true }, { name: 'incidentReference', label: 'Incident/reference', required: true }],
  buildCreateBody: (v) => ({ roleId: v.roleId, scope: scope(v), validUntil: new Date(String(v.validUntil)).toISOString(), reason: v.reason, incidentReference: v.incidentReference }),
  actions: [
    { label: 'Revoke break-glass', path: (id) => `${V1_ROOT}/administrative-break-glass-operations/${encodeURIComponent(id)}:revoke`, destructive: true },
    { label: 'Complete post-use review', path: (id) => `${V1_ROOT}/administrative-break-glass-operations/${encodeURIComponent(id)}:complete-review`, fields: [{ name: 'outcome', label: 'Review outcome', type: 'select', options: ['APPROVED_USE', 'POLICY_CONCERN', 'INCIDENT_FOLLOW_UP_REQUIRED'], required: true }, { name: 'summary', label: 'Review summary', type: 'textarea', required: true }], buildBody: (v) => ({ outcome: v.outcome, summary: v.summary }) },
  ],
};

const auditRecords: CollectionSpec = {
  eyebrow: 'Audit', title: 'Audit Records', description: 'Append-only semantic security/business evidence. This is not generic application logging.',
  listPath: `${V1_ROOT}/audit-records`, detailPath: (id) => `${V1_ROOT}/audit-records/${encodeURIComponent(id)}`,
  columns: [{ key: 'occurredAt', label: 'Occurred' }, { key: 'actionType', label: 'Action' }, { key: 'resourceType', label: 'Resource' }, { key: 'outcome', label: 'Outcome' }, { key: 'actorId', label: 'Actor' }],
};

function ScopedCollection({ title, description, parentLabel, parentPlaceholder, path, detailPath, columns, createFields, buildCreateBody }: { title: string; description: string; parentLabel: string; parentPlaceholder: string; path: (parentId: string) => string; detailPath: (id: string) => string; columns: CollectionSpec['columns']; createFields?: Field[]; buildCreateBody?: (v: Values) => unknown }) {
  const [parentId, setParentId] = useState('');
  if (!parentId) return <section className="page-stack"><header className="page-header"><p className="eyebrow">Scoped resource</p><h1>{title}</h1><p>{description}</p></header><div className="card lookup-card"><label><span>{parentLabel}</span><input value={parentId} onChange={(e) => setParentId(e.target.value)} placeholder={parentPlaceholder} /></label><button className="button" disabled={!parentId}>Open</button><small>Enter the governing resource ID to bind list pagination and create operations to the correct semantic parent.</small></div></section>;
  return <CollectionWorkspace spec={{ eyebrow: 'Scoped resource', title, description, listPath: path(parentId), detailPath, columns, createPath: path(parentId), createFields, buildCreateBody }} />;
}

function LookupWorkspace({ eyebrow, title, description, label, getPath, actions, create }: { eyebrow: string; title: string; description: string; label: string; getPath: (id: string) => string; actions?: Action[]; create?: { path: string; fields: Field[]; buildBody: (v: Values) => unknown; warning?: string } }) {
  const [id, setId] = useState(''); const [resource, setResource] = useState<ApiResult<JsonObject> | null>(null); const [error, setError] = useState<unknown>(null); const [pending, setPending] = useState(false); const [actionValues, setActionValues] = useState<Values>({});
  async function lookup() { setPending(true); setError(null); try { setResource(await apiRequest<JsonObject>(getPath(id))); } catch (cause) { setError(cause); setResource(null); } finally { setPending(false); } }
  async function act(action: Action) { if (!resource) return; if (action.destructive && !window.confirm(`${action.label} changes authoritative state. Confirm.`)) return; setPending(true); setError(null); try { setResource(await apiRequest<JsonObject>(action.path(id), { method: 'POST', etag: action.requireEtag === false ? undefined : resource.etag ?? undefined, idempotent: action.idempotent !== false, body: action.buildBody ? action.buildBody(actionValues) : action.body })); setActionValues({}); } catch (cause) { setError(cause); } finally { setPending(false); } }
  return <section className="page-stack"><header className="page-header"><p className="eyebrow">{eyebrow}</p><h1>{title}</h1><p>{description}</p></header>{create && <details className="card expandable"><summary>Create</summary><SemanticForm fields={create.fields} submitLabel="Create" warning={create.warning} onSubmit={async (v) => { const result = await apiRequest<JsonObject>(create.path, { method: 'POST', idempotent: true, body: create.buildBody(v) }); setResource(result); const rid = result.data.id; if (typeof rid === 'string') setId(rid); }} /></details>}<div className="card lookup-card"><label><span>{label}</span><input value={id} onChange={(e) => setId(e.target.value)} /></label><button className="button secondary" disabled={!id || pending} onClick={() => void lookup()}>{pending ? 'Loading…' : 'Lookup'}</button></div>{error && <ErrorState error={error} />}{resource && <section className="card detail-panel"><ResourceDetails resource={resource.data} />{actions && <div className="semantic-actions">{actions.map((action) => <div className="action-block" key={action.label}>{action.fields?.map((field) => <FieldControl key={field.name} field={field} value={actionValues[field.name]} onChange={(value) => setActionValues((current) => ({ ...current, [field.name]: value }))} />)}<button className={action.destructive ? 'button danger' : 'button secondary'} disabled={pending} onClick={() => void act(action)}>{action.label}</button></div>)}</div>}</section>}</section>;
}

function RequestWorkspace() {
  return <LookupWorkspace eyebrow="Governance" title="Access Requests" description="Create a typed self-service request, inspect item state, then submit the draft with optimistic revision protection." label="Access Request ID" getPath={(id) => `${V1_ROOT}/governance/access-requests/${encodeURIComponent(id)}`} create={{ path: `${V1_ROOT}/governance/access-requests`, fields: [{ name: 'beneficiaryIdentityId', label: 'Beneficiary Identity ID', required: true }, { name: 'targetKind', label: 'Target kind', type: 'select', options: ['ROLE', 'ENTITLEMENT'], required: true }, { name: 'targetId', label: 'Role / Entitlement ID', required: true }, { name: 'principalConstraintKind', label: 'Principal constraint', type: 'select', options: ['ANY', 'SPECIFIC'], required: true }, { name: 'specificPrincipalId', label: 'Specific Principal ID' }, { name: 'validFrom', label: 'Valid from', type: 'datetime-local' }, { name: 'validUntil', label: 'Valid until', type: 'datetime-local' }], buildBody: (v) => ({ beneficiaryIdentityId: v.beneficiaryIdentityId, items: [{ targetKind: v.targetKind, targetId: v.targetId, principalConstraintKind: v.principalConstraintKind, specificPrincipalId: nullIfEmpty(v.specificPrincipalId), validFrom: isoOrNull(v.validFrom), validUntil: isoOrNull(v.validUntil) }] }) }} actions={[{ label: 'Submit request', path: (id) => `${V1_ROOT}/governance/access-requests/${encodeURIComponent(id)}/submit` }]} />;
}

function LifecyclePolicyWorkspace() {
  const [resource, setResource] = useState<JsonObject | null>(null); const [error, setError] = useState<unknown>(null); const [loading, setLoading] = useState(false);
  async function load() { setLoading(true); setError(null); try { setResource((await apiRequest<JsonObject>(`${V1_ROOT}/lifecycle-access-policy`)).data); } catch (cause) { setError(cause); } finally { setLoading(false); } }
  useEffect(() => { void load(); }, []);
  const fields: Field[] = [{ name: 'predicateKind', label: 'Predicate', type: 'select', options: ['ALWAYS', 'CANONICAL_STRING_EQUALS', 'CANONICAL_BOOLEAN_EQUALS', 'CANONICAL_INTEGER_EQUALS', 'CANONICAL_DECIMAL_EQUALS', 'CANONICAL_DATE_EQUALS', 'CANONICAL_DATETIME_EQUALS', 'CANONICAL_ENUM_EQUALS', 'CANONICAL_STRING_CONTAINS'], required: true }, { name: 'canonicalKey', label: 'Canonical key', help: 'Blank for ALWAYS.' }, { name: 'expected', label: 'Expected value', help: 'Typed according to predicate.' }, { name: 'targetKind', label: 'Target kind', type: 'select', options: ['ROLE', 'ENTITLEMENT'], required: true }, { name: 'targetId', label: 'Target ID', required: true }];
  function rule(v: Values) { const kind = String(v.predicateKind); const body: JsonObject = { ruleId: crypto.randomUUID(), predicateKind: kind, targetKind: v.targetKind, targetId: v.targetId, canonicalKey: kind === 'ALWAYS' ? null : nullIfEmpty(v.canonicalKey) }; const expected = nullIfEmpty(v.expected); if (kind.includes('STRING')) body.expectedString = expected; else if (kind.includes('BOOLEAN')) body.expectedBoolean = expected === 'true'; else if (kind.includes('INTEGER')) body.expectedInteger = expected ? Number(expected) : null; else if (kind.includes('DECIMAL')) body.expectedDecimal = expected; else if (kind.includes('DATE') && !kind.includes('DATETIME')) body.expectedDate = expected; else if (kind.includes('DATETIME')) body.expectedDateTime = expected ? new Date(expected).toISOString() : null; else if (kind.includes('ENUM')) body.expectedEnum = expected; return body; }
  return <section className="page-stack"><header className="page-header"><p className="eyebrow">Access · policy</p><h1>Lifecycle Access Policy</h1><p>Typed Access-owned birthright/JML policy. This UI activates one explicit rule at a time; it does not expose a generic expression engine.</p></header>{error && <ErrorState error={error} onRetry={() => void load()} />}{loading && !resource && <div className="state-card">Loading active policy…</div>}{resource && <div className="card"><ResourceDetails resource={resource} /></div>}<details className="card expandable"><summary>Activate successor with one typed rule</summary><SemanticForm fields={fields} submitLabel="Activate policy" warning="Policy activation can increase authoritative access intent. The server performs the final governance checks." onSubmit={async (v) => { await apiRequest<JsonObject>(`${V1_ROOT}/lifecycle-access-policy:activate`, { method: 'POST', idempotent: true, body: { rules: [rule(v)] } }); await load(); }} /></details></section>;
}

function SourcePolicyWorkspace() {
  const [sourceId, setSourceId] = useState('');
  if (!sourceId) return <section className="page-stack"><header className="page-header"><p className="eyebrow">Identity · source policy</p><h1>Source Policies</h1><p>Correlation, lifecycle and trusted-absence policy are Identity-owned and versioned. SourceSystem administration itself is not exposed by the current public contract.</p></header><div className="card lookup-card"><label><span>SourceSystem ID</span><input value={sourceId} onChange={(e) => setSourceId(e.target.value)} /></label></div></section>;
  const base = `${V1_ROOT}/source-systems/${encodeURIComponent(sourceId)}`;
  return <Tabs tabs={[
    { id: 'correlation', label: 'Correlation', content: <LookupWorkspace eyebrow="Identity policy" title="Correlation Policy" description="Current immutable source correlation policy." label="SourceSystem ID" getPath={() => `${base}/correlation-policy`} create={{ path: `${base}/correlation-policy:activate`, fields: [{ name: 'canonicalKey', label: 'Canonical key', required: true }, { name: 'createIdentityOnNoMatch', label: 'Create Identity on no match', type: 'checkbox' }, { name: 'createdIdentityType', label: 'Created Identity type', type: 'select', options: ['PERSON', 'SERVICE', 'WORKLOAD'] }, { name: 'displayNameSourcePath', label: 'Display-name source path', placeholder: '$.displayName' }], buildBody: (v) => ({ canonicalKey: v.canonicalKey, createIdentityOnNoMatch: Boolean(v.createIdentityOnNoMatch), createdIdentityType: Boolean(v.createIdentityOnNoMatch) ? nullIfEmpty(v.createdIdentityType) : null, displayNameSourcePath: Boolean(v.createIdentityOnNoMatch) ? nullIfEmpty(v.displayNameSourcePath) : null }) }} /> },
    { id: 'lifecycle', label: 'Lifecycle', content: <LookupWorkspace eyebrow="Identity policy" title="Lifecycle Policy" description="Explicit source-value to Identity lifecycle mapping." label="SourceSystem ID" getPath={() => `${base}/lifecycle-policy`} create={{ path: `${base}/lifecycle-policy:activate`, fields: [{ name: 'sourcePath', label: 'Source path', required: true, placeholder: '$.status' }, { name: 'sourceValue', label: 'Source value', required: true }, { name: 'targetState', label: 'Target lifecycle', type: 'select', options: ['ACTIVE', 'SUSPENDED', 'INACTIVE', 'DECOMMISSIONED'], required: true }], buildBody: (v) => ({ sourcePath: v.sourcePath, rules: [{ sourceValue: v.sourceValue, targetState: v.targetState }] }) }} /> },
    { id: 'absence', label: 'Absence', content: <LookupWorkspace eyebrow="Identity policy" title="Trusted Absence Policy" description="Positive mass-Leaver safety ceiling used only with trusted COMPLETE absence inference." label="SourceSystem ID" getPath={() => `${base}/absence-policy`} create={{ path: `${base}/absence-policy:activate`, fields: [{ name: 'maxInferredTransitions', label: 'Maximum inferred transitions', type: 'number', required: true }], buildBody: (v) => ({ maxInferredTransitions: numberValue(v.maxInferredTransitions) }), warning: 'This safety ceiling affects trusted COMPLETE absence inference. Confirm the reviewed value.' }} /> },
  ]} />;
}

function CredentialsWorkspace() {
  const [principalId, setPrincipalId] = useState('');
  const spec: CollectionSpec = { eyebrow: 'Credential', title: 'Credentials', description: 'Authentication-instrument metadata only. Raw secret/private material is never requested or rendered.', listPath: (cursor) => withCursor(`${V1_ROOT}/credentials`, cursor, { principalId }), detailPath: (id) => `${V1_ROOT}/credentials/${encodeURIComponent(id)}`, columns: [{ key: 'kind', label: 'Kind' }, { key: 'lifecycleState', label: 'Lifecycle' }, { key: 'validFrom', label: 'Valid from' }, { key: 'validUntil', label: 'Valid until' }], createPath: `${V1_ROOT}/credentials`, createFields: [{ name: 'principalId', label: 'Principal ID', required: true }, { name: 'kind', label: 'Credential kind', type: 'select', options: ['PASSWORD', 'API_KEY', 'SSH_KEY', 'CERTIFICATE', 'OAUTH_CLIENT_SECRET'], required: true }, { name: 'providerType', label: 'External secret provider type', required: true }, { name: 'referenceKey', label: 'Opaque secret reference key', required: true, help: 'Reference metadata only. Never paste a password/private key/secret value here.' }, { name: 'validFrom', label: 'Valid from', type: 'datetime-local' }, { name: 'validUntil', label: 'Valid until', type: 'datetime-local' }], buildCreateBody: (v) => ({ principalId: v.principalId, kind: v.kind, secretReference: { providerType: v.providerType, referenceKey: v.referenceKey }, validFrom: isoOrNull(v.validFrom), validUntil: isoOrNull(v.validUntil) }), actions: [{ label: 'Revoke', path: (id) => `${V1_ROOT}/credentials/${encodeURIComponent(id)}:revoke`, destructive: true }, { label: 'Mark compromised', path: (id) => `${V1_ROOT}/credentials/${encodeURIComponent(id)}:compromise`, destructive: true }, { label: 'Plan rotation', path: (id) => `${V1_ROOT}/credentials/${encodeURIComponent(id)}:rotate`, requireEtag: false }] };
  if (!principalId) return <section className="page-stack"><header className="page-header"><p className="eyebrow">Credential</p><h1>Credentials</h1><p>Credential list pagination is contractually bound to one Principal.</p></header><div className="card lookup-card"><label><span>Principal ID</span><input value={principalId} onChange={(e) => setPrincipalId(e.target.value)} /></label></div></section>;
  return <CollectionWorkspace spec={spec} />;
}

function IntegrationWorkspace() {
  const connectorCreate = { path: `${V1_ROOT}/connectors`, fields: [{ name: 'connectorType', label: 'Connector type', required: true }, { name: 'runtimeId', label: 'Runtime ID', required: true }, { name: 'runtimeVersion', label: 'Runtime version', required: true }, { name: 'configurationVersion', label: 'Configuration version', type: 'number', required: true }, { name: 'configuration', label: 'Provider configuration JSON', type: 'textarea', required: true, help: 'Provider-specific Integration configuration only; never enter secret material.' }, { name: 'secretReference', label: 'Opaque secret reference', help: 'Optional write-only external secret reference; never enter the secret itself.' }], buildBody: (v: Values) => ({ connectorType: v.connectorType, runtimeId: v.runtimeId, runtimeVersion: v.runtimeVersion, configurationVersion: numberValue(v.configurationVersion), configuration: JSON.parse(String(v.configuration || '{}')) as unknown, secretReference: nullIfEmpty(v.secretReference) }) };
  return <Tabs tabs={[
    { id: 'connectors', label: 'Connectors', content: <LookupWorkspace eyebrow="Integration" title="Connector Instances" description="Integration-owned connector lifecycle/configuration. Secret references are write-only metadata and provider observations remain non-authoritative." label="Connector Instance ID" getPath={(id) => `${V1_ROOT}/connectors/${encodeURIComponent(id)}`} create={connectorCreate} actions={[{ label: 'Disable connector', path: (id) => `${V1_ROOT}/connectors/${encodeURIComponent(id)}:disable`, destructive: true }]} /> },
    { id: 'bindings', label: 'Bindings', content: <LookupWorkspace eyebrow="Integration" title="Connector Bindings" description="Binds connector runtime capability to an ApplicationTarget or SourceSystem without promoting provider data into IAM authority." label="Connector Binding ID" getPath={(id) => `${V1_ROOT}/connector-bindings/${encodeURIComponent(id)}`} actions={[{ label: 'Disable binding', path: (id) => `${V1_ROOT}/connector-bindings/${encodeURIComponent(id)}:disable`, destructive: true }]} /> },
    { id: 'workers', label: 'Workers', content: <LookupWorkspace eyebrow="Integration" title="Connector Workers" description="Server-owned worker registrations and compatibility scope. Browser users never call the internal worker execution protocol." label="Connector Worker ID" getPath={(id) => `${V1_ROOT}/connector-workers/${encodeURIComponent(id)}`} actions={[{ label: 'Disable worker', path: (id) => `${V1_ROOT}/connector-workers/${encodeURIComponent(id)}:disable`, destructive: true }]} /> },
    { id: 'mapping', label: 'Entitlement mapping', content: <LookupWorkspace eyebrow="Integration · observation mapping" title="Entitlement Observation Mapping" description="Explicit mapping from provider observation to governed Catalog Entitlement. Mapping does not create Catalog or Access authority." label="Mapping ID" getPath={(id) => `${V1_ROOT}/entitlement-observation-mappings/${encodeURIComponent(id)}`} actions={[{ label: 'Unmap observation', path: (id) => `${V1_ROOT}/entitlement-observation-mappings/${encodeURIComponent(id)}:unmap`, destructive: true }]} /> },
    { id: 'operations', label: 'Operations', content: <section className="page-stack"><header className="page-header"><p className="eyebrow">Integration · operations</p><h1>Provisioning & Reconciliation</h1><p>Public operator read contracts for ProvisioningJob/Task/Attempt and ReconciliationRun are not currently checked in. The internal connector-worker protocol is deliberately not used by the browser.</p></header><div className="state-card"><strong>Public API gap tracked as #266</strong><span>This panel remains unavailable rather than bypassing the Integration boundary.</span></div></section> },
  ]} />;
}

function AdministrationWorkspace() {
  return <Tabs tabs={[
    { id: 'roles', label: 'Roles', content: <CollectionWorkspace spec={adminRoles} /> },
    { id: 'grants', label: 'Grants', content: <CollectionWorkspace spec={adminGrants} /> },
    { id: 'delegations', label: 'Delegations', content: <CollectionWorkspace spec={adminDelegations} /> },
    { id: 'elevations', label: 'Elevations', content: <CollectionWorkspace spec={adminElevations} /> },
    { id: 'break-glass', label: 'Break-glass', content: <CollectionWorkspace spec={breakGlass} /> },
    { id: 'authority', label: 'My authority', content: <section className="page-stack"><header className="page-header"><p className="eyebrow">Administration · current actor</p><h1>Effective Administrative Authority</h1><p>The browser must not derive IAM permissions from token claims, groups or scopes.</p></header><div className="state-card"><strong>Backend-derived projection pending (#265)</strong><span>Operation-time server authorization remains final. Navigation is intentionally not presented as proof of permission.</span></div></section> },
  ]} />;
}

function AuditWorkspace() {
  const [downloadError, setDownloadError] = useState<unknown>(null);
  return <Tabs tabs={[
    { id: 'records', label: 'Records', content: <CollectionWorkspace spec={auditRecords} /> },
    { id: 'exports', label: 'Exports', content: <LookupWorkspace eyebrow="Audit" title="Audit Exports" description="Durable snapshot-frozen NDJSON export. Completed artifacts are re-authorized at download time." label="Audit Export ID" getPath={(id) => `${V1_ROOT}/audit-exports/${encodeURIComponent(id)}`} create={{ path: `${V1_ROOT}/audit-exports`, fields: [{ name: 'occurredFrom', label: 'Occurred from', type: 'datetime-local', required: true }, { name: 'occurredUntil', label: 'Occurred until', type: 'datetime-local', required: true }, { name: 'outcome', label: 'Outcome', type: 'select', options: ['SUCCESS', 'DENIED', 'FAILURE'] }, { name: 'actionType', label: 'Action type' }, { name: 'resourceType', label: 'Resource type' }, { name: 'resourceId', label: 'Resource ID' }, { name: 'actorId', label: 'Actor ID' }, { name: 'correlationId', label: 'Correlation ID' }], buildBody: (v) => ({ occurredFrom: new Date(String(v.occurredFrom)).toISOString(), occurredUntil: new Date(String(v.occurredUntil)).toISOString(), outcome: nullIfEmpty(v.outcome), actionType: nullIfEmpty(v.actionType), resourceType: nullIfEmpty(v.resourceType), resourceId: nullIfEmpty(v.resourceId), actorId: nullIfEmpty(v.actorId), correlationId: nullIfEmpty(v.correlationId) }) }} actions={[{ label: 'Download completed NDJSON', path: () => '', requireEtag: false, idempotent: false }]} /> },
    { id: 'evidence', label: 'Evidence', content: <CollectionWorkspace spec={{ eyebrow: 'Audit · immutable evidence', title: 'Evidence Snapshots', description: 'Immutable typed evidence snapshots.', listPath: `${V1_ROOT}/evidence-snapshots`, detailPath: (id) => `${V1_ROOT}/evidence-snapshots/${encodeURIComponent(id)}`, columns: [{ key: 'snapshotType', label: 'Type' }, { key: 'subjectResourceType', label: 'Subject type' }, { key: 'subjectResourceId', label: 'Subject ID' }, { key: 'createdAt', label: 'Created' }] }} /> },
    { id: 'holds', label: 'Legal hold', content: <LookupWorkspace eyebrow="Audit · evidence lifecycle" title="Legal Holds" description="Governed evidence-retention hold. Release is security-significant and revision protected." label="Legal Hold ID" getPath={(id) => `${V1_ROOT}/audit-evidence-lifecycle/legal-holds/${encodeURIComponent(id)}`} actions={[{ label: 'Release legal hold', path: (id) => `${V1_ROOT}/audit-evidence-lifecycle/legal-holds/${encodeURIComponent(id)}:release`, destructive: true, idempotent: false }]} /> },
    { id: 'purges', label: 'Purge', content: <LookupWorkspace eyebrow="Audit · destructive evidence" title="Purge Operations" description="Dual-control destructive evidence operation. Requester and approver must be distinct; server-side safety fences remain authoritative." label="Purge operation ID" getPath={(id) => `${V1_ROOT}/audit-evidence-lifecycle/purges/${encodeURIComponent(id)}`} actions={[{ label: 'Approve purge', path: (id) => `${V1_ROOT}/audit-evidence-lifecycle/purges/${encodeURIComponent(id)}:approve`, destructive: true, idempotent: false }]} /> },
  ]} />;
}

function CatalogWorkspace() {
  return <Tabs tabs={[
    { id: 'applications', label: 'Applications', content: <CollectionWorkspace spec={applications} /> },
    { id: 'targets', label: 'Targets', content: <ScopedCollection title="Application Targets" description="Technical targets belonging to one governed Application." parentLabel="Application ID" parentPlaceholder="Application UUID" path={(app) => `${V1_ROOT}/applications/${encodeURIComponent(app)}/targets`} detailPath={(id) => `${V1_ROOT}/application-targets/${encodeURIComponent(id)}`} columns={[{ key: 'code', label: 'Code' }, { key: 'lifecycleState', label: 'Lifecycle' }, { key: 'revision', label: 'Revision' }]} createFields={[{ name: 'code', label: 'Target code', required: true }]} buildCreateBody={(v) => ({ code: v.code })} /> },
    { id: 'entitlements', label: 'Entitlements', content: <ScopedCollection title="Entitlements" description="Governed Catalog entitlement authority for one Application." parentLabel="Application ID" parentPlaceholder="Application UUID" path={(app) => `${V1_ROOT}/applications/${encodeURIComponent(app)}/entitlements`} detailPath={(id) => `${V1_ROOT}/entitlements/${encodeURIComponent(id)}`} columns={[{ key: 'code', label: 'Code' }, { key: 'entitlementType', label: 'Type' }, { key: 'lifecycleState', label: 'Lifecycle' }]} createFields={[{ name: 'applicationTargetId', label: 'Application Target ID' }, { name: 'code', label: 'Code', required: true }, { name: 'nativeKey', label: 'Native key' }, { name: 'entitlementType', label: 'Entitlement type', required: true }]} buildCreateBody={(v) => ({ applicationTargetId: nullIfEmpty(v.applicationTargetId), code: v.code, nativeKey: nullIfEmpty(v.nativeKey), entitlementType: v.entitlementType })} /> },
    { id: 'roles', label: 'Roles', content: <CollectionWorkspace spec={roles} /> },
    { id: 'versions', label: 'Role versions', content: <RoleVersionWorkspace /> },
  ]} />;
}

function RoleVersionWorkspace() {
  const [roleId, setRoleId] = useState('');
  if (!roleId) return <section className="page-stack"><header className="page-header"><p className="eyebrow">Catalog · composition</p><h1>Role Versions</h1><p>Role composition remains explicit and typed. Enter a Role ID to manage immutable composition versions.</p></header><div className="card lookup-card"><label><span>Role ID</span><input value={roleId} onChange={(e) => setRoleId(e.target.value)} /></label></div></section>;
  return <CollectionWorkspace spec={{ eyebrow: 'Catalog · composition', title: 'Role Versions', description: 'Typed Role/Entitlement composition; membership changes create a new version rather than mutating an active one.', listPath: `${V1_ROOT}/roles/${encodeURIComponent(roleId)}/versions`, detailPath: (id) => `${V1_ROOT}/roles/${encodeURIComponent(roleId)}/versions/${encodeURIComponent(id)}`, columns: [{ key: 'versionNumber', label: 'Version' }, { key: 'state', label: 'State' }, { key: 'revision', label: 'Revision' }, { key: 'contentHash', label: 'Content hash' }], createPath: `${V1_ROOT}/roles/${encodeURIComponent(roleId)}/versions`, createFields: [{ name: 'members', label: 'Members', type: 'textarea', required: true, placeholder: 'ENTITLEMENT:<uuid>\nAPPLICATION_ROLE:<uuid>', help: 'One typed composition member per line.' }], buildCreateBody: (v) => ({ members: String(v.members).split(/\n/).map((line) => line.trim()).filter(Boolean).map((line) => { const [kind, id] = line.split(':', 2); if (kind === 'ENTITLEMENT') return { kind, entitlementId: id }; if (kind === 'APPLICATION_ROLE') return { kind, roleId: id }; throw new Error(`Unsupported member kind: ${kind}`); }) }), actions: [{ label: 'Validate version', path: (id) => `${V1_ROOT}/roles/${encodeURIComponent(roleId)}/versions/${encodeURIComponent(id)}/validate` }, { label: 'Activate version', path: (id) => `${V1_ROOT}/roles/${encodeURIComponent(roleId)}/versions/${encodeURIComponent(id)}/activate` }] }} />;
}

export function PrincipalsPage() { return <CollectionWorkspace spec={principals} />; }
export function CatalogPage() { return <CatalogWorkspace />; }
export function AccessPage() { return <Tabs tabs={[{ id: 'assignments', label: 'Assignments', content: <CollectionWorkspace spec={assignments} /> }, { id: 'effective', label: 'Effective access', content: <CollectionWorkspace spec={effectiveAccess} /> }, { id: 'policy', label: 'Lifecycle policy', content: <LifecyclePolicyWorkspace /> }]} />; }
export function RequestsPage() { return <Tabs tabs={[{ id: 'requests', label: 'Access request', content: <RequestWorkspace /> }, { id: 'approvals', label: 'Approvals', content: <CollectionWorkspace spec={approvals} /> }]} />; }
export function ReviewsPage() { return <Tabs tabs={[{ id: 'campaigns', label: 'Campaigns', content: <CollectionWorkspace spec={reviewCampaigns} /> }, { id: 'inbox', label: 'Review inbox', content: <CollectionWorkspace spec={reviewInbox} /> }]} />; }
export function PoliciesPage() { return <Tabs tabs={[{ id: 'source', label: 'Source policies', content: <SourcePolicyWorkspace /> }, { id: 'access', label: 'Lifecycle access', content: <LifecyclePolicyWorkspace /> }, { id: 'governance', label: 'Access-request policy', content: <LookupWorkspace eyebrow="Governance · policy" title="ACCESS_REQUEST Policy" description="Read a specific policy version. State transitions remain explicit semantic commands." label="Policy Version ID" getPath={(id) => `${V1_ROOT}/governance/policies/access-request/versions/${encodeURIComponent(id)}`} actions={[{ label: 'Mark READY', path: (id) => `${V1_ROOT}/governance/policies/access-request/versions/${encodeURIComponent(id)}:ready` }, { label: 'Activate', path: (id) => `${V1_ROOT}/governance/policies/access-request/versions/${encodeURIComponent(id)}:activate` }, { label: 'Cancel draft', path: (id) => `${V1_ROOT}/governance/policies/access-request/versions/${encodeURIComponent(id)}:cancel`, destructive: true }]} /> }, { id: 'exceptions', label: 'Exceptions', content: <LookupWorkspace eyebrow="Governance · exception" title="Governance Exceptions" description="Scoped, time-bound Governance exception evidence. Revocation reduces exception authority but does not mutate Access directly." label="Governance Exception ID" getPath={(id) => `${V1_ROOT}/governance/exceptions/${encodeURIComponent(id)}`} actions={[{ label: 'Revoke exception', path: (id) => `${V1_ROOT}/governance/exceptions/${encodeURIComponent(id)}:revoke`, destructive: true }]} /> }]} />; }
export function CredentialsPage() { return <CredentialsWorkspace />; }
export function IntegrationsPage() { return <IntegrationWorkspace />; }
export function AdministrationPage() { return <AdministrationWorkspace />; }
export function AuditPage() { return <AuditWorkspace />; }
