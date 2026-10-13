import { type ReactNode, useEffect, useState } from 'react';
import { ApiError, V1_ROOT, apiRequest, withCursor } from '../api';
import { type Spec, Workspace, WorkspaceTabs } from '../SemanticWorkspace';
import {
  Box,
  Button,
  Page,
  PageHeader,
  SelectInput,
  StatePanel,
  StatusChip,
  Surface,
  TextInput,
  Typography,
} from '../ui';
import { list, nullable } from './workspaceSpecHelpers';

type CatalogParent = {
  id: string;
  code?: string;
  name?: string;
  lifecycleState?: string;
  roleType?: string;
};

type CatalogPage<T> = {
  items: T[];
  nextCursor?: string | null;
};

type ScopeKind = 'application' | 'role';
type ScopedKind = 'targets' | 'entitlements' | 'versions';

const applicationSpec: Spec = {
  eyebrow: 'Catalog',
  title: 'Applications',
  description: 'Governed Application authority. Select an Application in Targets or Entitlements to work inside its technical hierarchy; provider observations never become Catalog authority implicitly.',
  list: list(`${V1_ROOT}/applications`),
  detail: (id) => `${V1_ROOT}/applications/${encodeURIComponent(id)}`,
  columns: ['code', 'name', 'lifecycleState', 'revision'],
  create: {
    path: `${V1_ROOT}/applications`,
    fields: [
      { name: 'code', label: 'Code', required: true, help: 'Stable tenant-unique Application code.' },
      { name: 'name', label: 'Name', required: true },
    ],
    body: (values) => ({ code: values.code, name: values.name }),
  },
  actions: [{ label: 'Retire application', path: (id) => `${V1_ROOT}/applications/${encodeURIComponent(id)}/retire`, destructive: true }],
};

const roleSpec: Spec = {
  eyebrow: 'Catalog',
  title: 'Roles',
  description: 'BUSINESS Roles are organizational access packages that may span applications. APPLICATION Roles are bound to one Application. Composition is a separate, immutable RoleVersion snapshot.',
  list: list(`${V1_ROOT}/roles`),
  detail: (id) => `${V1_ROOT}/roles/${encodeURIComponent(id)}`,
  columns: ['code', 'name', 'roleType', 'applicationId', 'lifecycleState'],
  create: {
    path: `${V1_ROOT}/roles`,
    fields: [
      {
        name: 'roleType',
        label: 'Role type',
        type: 'select',
        options: ['BUSINESS', 'APPLICATION'],
        required: true,
        help: 'BUSINESS may span applications; APPLICATION belongs to exactly one Application.',
      },
      {
        name: 'applicationId',
        label: 'Application ID',
        help: 'Required by the server for APPLICATION Roles; BUSINESS Roles are not Application-bound.',
      },
      { name: 'code', label: 'Code', required: true, help: 'Role type, Application binding, and code are immutable after creation.' },
      { name: 'name', label: 'Name', required: true },
    ],
    body: (values) => ({
      roleType: values.roleType,
      applicationId: nullable(values.applicationId),
      code: values.code,
      name: values.name,
    }),
  },
  actions: [{ label: 'Retire role', path: (id) => `${V1_ROOT}/roles/${encodeURIComponent(id)}/retire`, destructive: true }],
};

function CatalogErrorState({ error, subject }: { error: unknown; subject: string }) {
  const detail = error instanceof Error ? error.message : `Unable to load ${subject}.`;
  if (error instanceof ApiError) {
    if (error.status === 401) return <StatePanel state="error" title="Authentication required">A trusted control-plane session is required.</StatePanel>;
    if (error.status === 403) return <StatePanel state="forbidden" title={`Not authorized to list ${subject}`}>Server authorization is final. You can still enter a known parent ID below if your scoped authority permits the child operation.</StatePanel>;
    if (error.status === 409) return <StatePanel state="conflict" title="Catalog conflict">{detail}</StatePanel>;
    if (error.status === 412) return <StatePanel state="stale" title="Stale Catalog revision">Refresh and retry against current authoritative state.</StatePanel>;
    if (error.status === 503) return <StatePanel state="error" title="Required evaluator unavailable">{detail}</StatePanel>;
  }
  return <StatePanel state="error" title={`Unable to load ${subject}`}>{detail}</StatePanel>;
}

function parentLabel(parent: CatalogParent, kind: ScopeKind) {
  const primary = parent.name || parent.code || parent.id;
  const code = parent.code && parent.code !== primary ? ` · ${parent.code}` : '';
  const type = kind === 'role' && parent.roleType ? ` · ${parent.roleType}` : '';
  const lifecycle = parent.lifecycleState ? ` · ${parent.lifecycleState}` : '';
  return `${primary}${code}${type}${lifecycle}`;
}

function ParentScope({ kind, children }: { kind: ScopeKind; children: (parentId: string, parent?: CatalogParent) => ReactNode }) {
  const config = kind === 'application'
    ? { label: 'Application', plural: 'Applications', path: `${V1_ROOT}/applications` }
    : { label: 'Role', plural: 'Roles', path: `${V1_ROOT}/roles` };
  const [parents, setParents] = useState<CatalogParent[]>([]);
  const [selectedId, setSelectedId] = useState('');
  const [manualId, setManualId] = useState('');
  const [activeId, setActiveId] = useState('');
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    setParents([]);
    setNextCursor(null);
    apiRequest<CatalogPage<CatalogParent>>(withCursor(config.path), { signal: controller.signal })
      .then(({ data }) => {
        setParents(data.items);
        setNextCursor(data.nextCursor ?? null);
      })
      .catch((cause) => {
        if (!(cause instanceof DOMException && cause.name === 'AbortError')) setError(cause);
      })
      .finally(() => setLoading(false));
    return () => controller.abort();
  }, [config.path, reload]);

  async function loadMore() {
    if (!nextCursor) return;
    setLoadingMore(true);
    setError(null);
    try {
      const { data } = await apiRequest<CatalogPage<CatalogParent>>(withCursor(config.path, nextCursor));
      setParents((current) => [...current, ...data.items]);
      setNextCursor(data.nextCursor ?? null);
    } catch (cause) {
      setError(cause);
    } finally {
      setLoadingMore(false);
    }
  }

  const candidateId = selectedId || manualId.trim();
  const activeParent = parents.find((parent) => parent.id === activeId);

  if (activeId) {
    return (
      <>
        <Page>
          <Surface
            title={`Current ${config.label} scope`}
            description={`Child resources remain explicitly scoped to this governed ${config.label}; changing scope does not mutate Catalog state.`}
            actions={<Button variant="outlined" onClick={() => setActiveId('')}>Change {config.label}</Button>}
          >
            <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1, alignItems: 'center' }}>
              <Typography variant="body2" sx={{ fontWeight: 700, overflowWrap: 'anywhere' }}>
                {activeParent ? parentLabel(activeParent, kind) : activeId}
              </Typography>
              {activeParent?.roleType ? <StatusChip label={activeParent.roleType} /> : null}
              {activeParent?.lifecycleState ? <StatusChip label={activeParent.lifecycleState} /> : null}
            </Box>
          </Surface>
        </Page>
        {children(activeId, activeParent)}
      </>
    );
  }

  return (
    <Page>
      <PageHeader
        eyebrow="Catalog · governed scope"
        title={`Choose ${config.label}`}
        description={`Select from server-returned Catalog authority or enter a known ${config.label} ID. The browser does not infer authority from this selection.`}
      />
      <Surface title={`Select governed ${config.label}`} description={`Use the existing ${config.plural} collection; no client-side filters or synthetic business data are introduced.`}>
        <Box sx={{ display: 'grid', gap: 1.5 }}>
          {loading ? <StatePanel state="loading" title={`Loading ${config.plural}`}>Reading current Catalog authority.</StatePanel> : null}
          {!loading && parents.length === 0 && error === null ? <StatePanel state="empty" title={`No ${config.plural} returned`}>Enter a known {config.label} ID if you have scoped authority, or create the parent through its governed Catalog workflow.</StatePanel> : null}
          {error !== null ? <CatalogErrorState error={error} subject={config.plural} /> : null}
          {parents.length > 0 ? (
            <SelectInput
              label={config.label}
              value={selectedId}
              options={parents.map((parent) => ({ value: parent.id, label: parentLabel(parent, kind) }))}
              helperText={`Choose a ${config.label} returned by the authoritative Catalog collection.`}
              onChange={(event) => {
                setSelectedId(event.target.value);
                setManualId('');
              }}
            />
          ) : null}
          {nextCursor ? (
            <Button variant="text" disabled={loadingMore} onClick={() => void loadMore()} sx={{ justifySelf: 'start' }}>
              {loadingMore ? 'Loading more…' : `Load more ${config.plural}`}
            </Button>
          ) : null}
          <TextInput
            label={`Known ${config.label} ID`}
            value={manualId}
            helperText="Fallback for scoped operators who know the governed parent ID; server authorization still decides access."
            onChange={(event) => {
              setManualId(event.target.value);
              setSelectedId('');
            }}
          />
          <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1 }}>
            <Button variant="contained" disabled={!candidateId} onClick={() => setActiveId(candidateId)}>Open scoped workspace</Button>
            <Button variant="outlined" disabled={loading} onClick={() => setReload((value) => value + 1)}>Refresh parents</Button>
          </Box>
        </Box>
      </Surface>
    </Page>
  );
}

function parseRoleVersionMembers(raw: string) {
  const lines = raw.split(/\n/).map((line) => line.trim()).filter(Boolean);
  if (lines.length === 0) throw new Error('Add at least one typed RoleVersion member.');
  return lines.map((line, index) => {
    const separator = line.indexOf(':');
    const kind = separator >= 0 ? line.slice(0, separator).trim() : '';
    const memberId = separator >= 0 ? line.slice(separator + 1).trim() : '';
    if (!memberId || (kind !== 'ENTITLEMENT' && kind !== 'APPLICATION_ROLE')) {
      throw new Error(`Member ${index + 1} must use ENTITLEMENT:<id> or APPLICATION_ROLE:<id>.`);
    }
    return kind === 'ENTITLEMENT'
      ? { kind: 'ENTITLEMENT', entitlementId: memberId }
      : { kind: 'APPLICATION_ROLE', roleId: memberId };
  });
}

function ScopedCatalog({ kind }: { kind: ScopedKind }) {
  const scopeKind: ScopeKind = kind === 'versions' ? 'role' : 'application';

  return (
    <ParentScope kind={scopeKind}>
      {(active) => {
        if (kind === 'targets') {
          const spec: Spec = {
            eyebrow: 'Catalog · Application hierarchy',
            title: 'Application Targets',
            description: 'Technical targets belonging to exactly one governed Application. Retirement is terminal in this slice and does not cascade business mutation to child Entitlements.',
            list: list(`${V1_ROOT}/applications/${encodeURIComponent(active)}/targets`),
            detail: (id) => `${V1_ROOT}/application-targets/${encodeURIComponent(id)}`,
            columns: ['code', 'lifecycleState', 'revision'],
            create: {
              path: `${V1_ROOT}/applications/${encodeURIComponent(active)}/targets`,
              fields: [{ name: 'code', label: 'Target code', required: true, help: 'Stable within the selected Application.' }],
              body: (values) => ({ code: values.code }),
            },
            actions: [{ label: 'Retire target', path: (id) => `${V1_ROOT}/application-targets/${encodeURIComponent(id)}/retire`, destructive: true }],
          };
          return <Workspace spec={spec} />;
        }

        if (kind === 'entitlements') {
          const spec: Spec = {
            eyebrow: 'Catalog · Application hierarchy',
            title: 'Entitlements',
            description: 'Governed technical access for the selected Application. An Entitlement may optionally be scoped to a compatible ApplicationTarget; provider discovery remains observation, not Catalog maintenance.',
            list: list(`${V1_ROOT}/applications/${encodeURIComponent(active)}/entitlements`),
            detail: (id) => `${V1_ROOT}/entitlements/${encodeURIComponent(id)}`,
            columns: ['code', 'applicationTargetId', 'entitlementType', 'lifecycleState'],
            create: {
              path: `${V1_ROOT}/applications/${encodeURIComponent(active)}/entitlements`,
              fields: [
                { name: 'applicationTargetId', label: 'Application Target ID', help: 'Optional. When present, the server requires the Target to belong to this Application.' },
                { name: 'code', label: 'Entitlement code', required: true, help: 'Stable within the selected Application.' },
                { name: 'nativeKey', label: 'Native key', help: 'Creation-time Catalog metadata; provider observations remain Integration-owned.' },
                { name: 'entitlementType', label: 'Entitlement type', required: true },
              ],
              body: (values) => ({
                applicationTargetId: nullable(values.applicationTargetId),
                code: values.code,
                nativeKey: nullable(values.nativeKey),
                entitlementType: values.entitlementType,
              }),
            },
            actions: [{ label: 'Retire entitlement', path: (id) => `${V1_ROOT}/entitlements/${encodeURIComponent(id)}/retire`, destructive: true }],
          };
          return <Workspace spec={spec} />;
        }

        const spec: Spec = {
          eyebrow: 'Catalog · typed composition',
          title: 'Role Versions',
          description: 'Each version is a complete typed composition snapshot. Validate re-checks current references and moves DRAFT to READY; Activate re-checks READY, makes it ACTIVE, and supersedes any prior ACTIVE version. ACTIVE and SUPERSEDED content is immutable.',
          list: list(`${V1_ROOT}/roles/${encodeURIComponent(active)}/versions`),
          detail: (id) => `${V1_ROOT}/roles/${encodeURIComponent(active)}/versions/${encodeURIComponent(id)}`,
          columns: ['versionNumber', 'state', 'revision'],
          create: {
            path: `${V1_ROOT}/roles/${encodeURIComponent(active)}/versions`,
            fields: [{
              name: 'members',
              label: 'Typed members',
              type: 'textarea',
              required: true,
              placeholder: 'ENTITLEMENT:<id>\nAPPLICATION_ROLE:<id>',
              help: 'One typed member per line. This is the complete snapshot; arbitrary JSON and in-place member patching are not supported.',
            }],
            body: (values) => ({ members: parseRoleVersionMembers(String(values.members)) }),
          },
          actions: [
            { label: 'Validate draft', path: (id) => `${V1_ROOT}/roles/${encodeURIComponent(active)}/versions/${encodeURIComponent(id)}/validate` },
            { label: 'Activate version', path: (id) => `${V1_ROOT}/roles/${encodeURIComponent(active)}/versions/${encodeURIComponent(id)}/activate` },
          ],
        };
        return <Workspace spec={spec} />;
      }}
    </ParentScope>
  );
}

export function CatalogWorkspace() {
  return <WorkspaceTabs tabs={[
    { id: 'apps', label: 'Applications', content: <Workspace spec={applicationSpec} /> },
    { id: 'targets', label: 'Targets', content: <ScopedCatalog kind="targets" /> },
    { id: 'entitlements', label: 'Entitlements', content: <ScopedCatalog kind="entitlements" /> },
    { id: 'roles', label: 'Roles', content: <Workspace spec={roleSpec} /> },
    { id: 'versions', label: 'Role versions', content: <ScopedCatalog kind="versions" /> },
  ]} />;
}
