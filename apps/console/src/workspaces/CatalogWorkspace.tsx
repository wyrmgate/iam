import { useState } from 'react';
import { V1_ROOT } from '../api';
import { type Spec, Workspace, WorkspaceTabs } from '../SemanticWorkspace';
import { Box, Button, Page, PageHeader, Surface, TextInput } from '../ui';
import { list, nullable } from './workspaceSpecHelpers';

const applicationSpec: Spec = {
  eyebrow: 'Catalog', title: 'Applications', description: 'Governed Application authority.',
  list: list(`${V1_ROOT}/applications`), detail: (id) => `${V1_ROOT}/applications/${encodeURIComponent(id)}`,
  columns: ['code', 'name', 'lifecycleState', 'revision'],
  create: { path: `${V1_ROOT}/applications`, fields: [{ name: 'code', label: 'Code', required: true }, { name: 'name', label: 'Name', required: true }], body: (values) => ({ code: values.code, name: values.name }) },
  actions: [{ label: 'Retire', path: (id) => `${V1_ROOT}/applications/${encodeURIComponent(id)}/retire`, destructive: true }],
};

const roleSpec: Spec = {
  eyebrow: 'Catalog', title: 'Roles', description: 'Business/application Role authority; composition is versioned separately.',
  list: list(`${V1_ROOT}/roles`), detail: (id) => `${V1_ROOT}/roles/${encodeURIComponent(id)}`,
  columns: ['code', 'name', 'roleType', 'lifecycleState'],
  create: { path: `${V1_ROOT}/roles`, fields: [{ name: 'roleType', label: 'Role type', type: 'select', options: ['BUSINESS', 'APPLICATION'], required: true }, { name: 'applicationId', label: 'Application ID' }, { name: 'code', label: 'Code', required: true }, { name: 'name', label: 'Name', required: true }], body: (values) => ({ roleType: values.roleType, applicationId: nullable(values.applicationId), code: values.code, name: values.name }) },
  actions: [{ label: 'Retire', path: (id) => `${V1_ROOT}/roles/${encodeURIComponent(id)}/retire`, destructive: true }],
};

function ScopedCatalog({ kind }: { kind: 'targets' | 'entitlements' | 'versions' }) {
  const [parent, setParent] = useState('');
  const [active, setActive] = useState('');
  const parentType = kind === 'versions' ? 'Role ID' : 'Application ID';
  const title = kind === 'targets' ? 'Application Targets' : kind === 'entitlements' ? 'Entitlements' : 'Role Versions';

  if (!active) {
    return (
      <Page>
        <PageHeader eyebrow="Catalog · scoped" title={title} description={`Open ${title.toLowerCase()} for one governed parent resource.`} />
        <Surface title={`Choose ${parentType}`}>
          <Box sx={{ display: 'flex', flexDirection: { xs: 'column', sm: 'row' }, gap: 1 }}>
            <TextInput label={parentType} value={parent} onChange={(event) => setParent(event.target.value)} sx={{ flex: 1 }} />
            <Button variant="contained" disabled={!parent} onClick={() => setActive(parent)}>Open</Button>
          </Box>
        </Surface>
      </Page>
    );
  }

  if (kind === 'targets') return <Workspace spec={{ eyebrow: 'Catalog', title: 'Application Targets', description: 'Technical targets belonging to one governed Application.', list: list(`${V1_ROOT}/applications/${encodeURIComponent(active)}/targets`), detail: (id) => `${V1_ROOT}/application-targets/${encodeURIComponent(id)}`, columns: ['code', 'lifecycleState', 'revision'], create: { path: `${V1_ROOT}/applications/${encodeURIComponent(active)}/targets`, fields: [{ name: 'code', label: 'Code', required: true }], body: (values) => ({ code: values.code }) }, actions: [{ label: 'Retire', path: (id) => `${V1_ROOT}/application-targets/${encodeURIComponent(id)}/retire`, destructive: true }] }} />;
  if (kind === 'entitlements') return <Workspace spec={{ eyebrow: 'Catalog', title: 'Entitlements', description: 'Governed Entitlement authority for one Application.', list: list(`${V1_ROOT}/applications/${encodeURIComponent(active)}/entitlements`), detail: (id) => `${V1_ROOT}/entitlements/${encodeURIComponent(id)}`, columns: ['code', 'entitlementType', 'lifecycleState'], create: { path: `${V1_ROOT}/applications/${encodeURIComponent(active)}/entitlements`, fields: [{ name: 'applicationTargetId', label: 'Application Target ID' }, { name: 'code', label: 'Code', required: true }, { name: 'nativeKey', label: 'Native key' }, { name: 'entitlementType', label: 'Entitlement type', required: true }], body: (values) => ({ applicationTargetId: nullable(values.applicationTargetId), code: values.code, nativeKey: nullable(values.nativeKey), entitlementType: values.entitlementType }) }, actions: [{ label: 'Retire', path: (id) => `${V1_ROOT}/entitlements/${encodeURIComponent(id)}/retire`, destructive: true }] }} />;
  return <Workspace spec={{ eyebrow: 'Catalog · composition', title: 'Role Versions', description: 'Typed immutable Role/Entitlement composition.', list: list(`${V1_ROOT}/roles/${encodeURIComponent(active)}/versions`), detail: (id) => `${V1_ROOT}/roles/${encodeURIComponent(active)}/versions/${encodeURIComponent(id)}`, columns: ['versionNumber', 'state', 'revision'], create: { path: `${V1_ROOT}/roles/${encodeURIComponent(active)}/versions`, fields: [{ name: 'members', label: 'Members', type: 'textarea', required: true, placeholder: 'ENTITLEMENT:<uuid>\nAPPLICATION_ROLE:<uuid>' }], body: (values) => ({ members: String(values.members).split(/\n/).map((line) => line.trim()).filter(Boolean).map((line) => { const [kindName, memberId] = line.split(':', 2); return kindName === 'ENTITLEMENT' ? { kind: 'ENTITLEMENT', entitlementId: memberId } : { kind: 'APPLICATION_ROLE', roleId: memberId }; }) }) }, actions: [{ label: 'Validate', path: (id) => `${V1_ROOT}/roles/${encodeURIComponent(active)}/versions/${encodeURIComponent(id)}/validate` }, { label: 'Activate', path: (id) => `${V1_ROOT}/roles/${encodeURIComponent(active)}/versions/${encodeURIComponent(id)}/activate` }] }} />;
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
