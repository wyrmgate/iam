import { useState } from 'react';
import { V1_ROOT } from '../api';
import { Workspace } from '../SemanticWorkspace';
import { Box, Button, Page, PageHeader, Surface, TextInput } from '../ui';
import { iso, list } from './workspaceSpecHelpers';

export function CredentialsWorkspace() {
  const [principal, setPrincipal] = useState('');
  const [active, setActive] = useState('');
  if (!active) {
    return (
      <Page>
        <PageHeader eyebrow="Credential" title="Credentials" description="Credential list is contractually scoped to one Principal." />
        <Surface title="Choose Principal">
          <Box sx={{ display: 'flex', flexDirection: { xs: 'column', sm: 'row' }, gap: 1 }}>
            <TextInput label="Principal ID" value={principal} onChange={(event) => setPrincipal(event.target.value)} sx={{ flex: 1 }} />
            <Button variant="contained" disabled={!principal} onClick={() => setActive(principal)}>Open</Button>
          </Box>
        </Surface>
      </Page>
    );
  }
  return <Workspace spec={{ eyebrow: 'Credential', title: 'Credentials', description: 'Metadata/control plane only; raw secret/private material must never enter this UI.', list: list(`${V1_ROOT}/credentials`, { principalId: active }), detail: (id) => `${V1_ROOT}/credentials/${encodeURIComponent(id)}`, columns: ['kind', 'lifecycleState', 'validFrom', 'validUntil'], create: { path: `${V1_ROOT}/credentials`, warning: 'Enter only an opaque external secret reference, never the secret/private material itself.', fields: [{ name: 'principalId', label: 'Principal ID', required: true }, { name: 'kind', label: 'Kind', type: 'select', options: ['PASSWORD', 'API_KEY', 'SSH_KEY', 'CERTIFICATE', 'OAUTH_CLIENT_SECRET'], required: true }, { name: 'providerType', label: 'Secret provider type', required: true }, { name: 'referenceKey', label: 'Opaque reference key', required: true }, { name: 'validFrom', label: 'Valid from', type: 'datetime-local' }, { name: 'validUntil', label: 'Valid until', type: 'datetime-local' }], body: (values) => ({ principalId: values.principalId, kind: values.kind, secretReference: { providerType: values.providerType, referenceKey: values.referenceKey }, validFrom: iso(values.validFrom), validUntil: iso(values.validUntil) }) }, actions: [{ label: 'Revoke', path: (id) => `${V1_ROOT}/credentials/${encodeURIComponent(id)}:revoke`, destructive: true }, { label: 'Compromise', path: (id) => `${V1_ROOT}/credentials/${encodeURIComponent(id)}:compromise`, destructive: true }, { label: 'Plan rotation', path: (id) => `${V1_ROOT}/credentials/${encodeURIComponent(id)}:rotate`, requireEtag: false }] }} />;
}
