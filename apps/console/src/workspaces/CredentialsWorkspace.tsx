import { useMemo, useState } from 'react';
import { apiRequest, V1_ROOT } from '../api';
import { type Spec, Workspace, WorkspaceTabs } from '../SemanticWorkspace';
import { Box, Button, Page, PageHeader, StatePanel, Surface, TextInput, Typography } from '../ui';
import { iso, list } from './workspaceSpecHelpers';

type CredentialRotationResource = {
  id: string;
  oldCredentialId: string;
  replacementCredentialId?: string | null;
  processState: string;
};

const credentialKinds = ['PASSWORD', 'API_KEY', 'SSH_KEY', 'CERTIFICATE', 'OAUTH_CLIENT_SECRET'];

function CredentialMetadataWorkspace({ principalId }: { principalId: string }) {
  const spec = useMemo<Spec>(() => ({
    eyebrow: 'Credential · Principal-scoped metadata',
    title: 'Credential metadata',
    description: `Credentials for Principal ${principalId}. This control plane governs authentication-instrument metadata only; raw secret/private material never enters this UI.`,
    list: list(`${V1_ROOT}/credentials`, { principalId }),
    detail: (id) => `${V1_ROOT}/credentials/${encodeURIComponent(id)}`,
    columns: ['kind', 'lifecycleState', 'validFrom', 'validUntil', 'revision'],
    create: {
      path: `${V1_ROOT}/credentials`,
      warning: 'Create only an opaque external SecretReference. Never paste a password, API-key value, private key, certificate private key, OAuth client secret, token, or other secret/private material into this form.',
      fields: [
        {
          name: 'kind',
          label: 'Credential kind',
          type: 'select',
          options: credentialKinds,
          required: true,
          help: 'PASSWORD, API_KEY, SSH_KEY, CERTIFICATE, and OAUTH_CLIENT_SECRET are metadata classifications only; they do not mean Wyrmgate stores the corresponding secret/private material.',
        },
        {
          name: 'providerType',
          label: 'Secret provider type',
          required: true,
          help: 'Opaque metadata naming the external secret-provider family. This is not provider configuration or a secret value.',
        },
        {
          name: 'referenceKey',
          label: 'Opaque reference key',
          required: true,
          help: 'Locator understood by an external secret provider. Enter the reference only, never the referenced secret/private material.',
        },
        {
          name: 'validFrom',
          label: 'Valid from',
          type: 'datetime-local',
          help: 'A future credential remains semantically ineffective until this instant even if scheduled materialization is delayed.',
        },
        {
          name: 'validUntil',
          label: 'Valid until',
          type: 'datetime-local',
          help: 'The credential becomes semantically ineffective at this instant; scheduler delay cannot extend validity.',
        },
      ],
      body: (values) => ({
        principalId,
        kind: values.kind,
        secretReference: {
          providerType: values.providerType,
          referenceKey: values.referenceKey,
        },
        validFrom: iso(values.validFrom),
        validUntil: iso(values.validUntil),
      }),
    },
    actions: [
      {
        label: 'Revoke credential',
        path: (id) => `${V1_ROOT}/credentials/${encodeURIComponent(id)}:revoke`,
        destructive: true,
      },
      {
        label: 'Mark compromised',
        path: (id) => `${V1_ROOT}/credentials/${encodeURIComponent(id)}:compromise`,
        destructive: true,
      },
    ],
  }), [principalId]);

  return (
    <Box sx={{ display: 'grid', gap: 2 }}>
      <StatePanel state="informational" title="Metadata only — not a secret vault">
        Credential kinds describe governed authentication-instrument metadata. SecretReference is opaque external metadata and is never dereferenced by this Console. Raw passwords, API-key values, private keys, certificate private keys, OAuth client secrets, and tokens do not belong in ordinary IAM UI/API/log/error surfaces.
      </StatePanel>
      <StatePanel state="warning" title="Lifecycle operations have immediate security meaning">
        Revoke is an authoritative Credential decision; provider-side residue remains separate observation or fulfillment state. Mark compromised makes an ACTIVE or SCHEDULED credential immediately unsafe and may cause service interruption. Both operations use the current authoritative revision; server authorization, revision checks, and idempotency remain final.
      </StatePanel>
      <Surface title="Lifecycle and validity" description="Lifecycle, time validity, and durable rotation are separate dimensions.">
        <Box component="ul" sx={{ m: 0, pl: 2.5, display: 'grid', gap: 1 }}>
          <Typography component="li" variant="body2"><strong>SCHEDULED</strong> is not effective before validFrom.</Typography>
          <Typography component="li" variant="body2"><strong>ACTIVE</strong> is effective only while validFrom/validUntil also permit it.</Typography>
          <Typography component="li" variant="body2"><strong>EXPIRED</strong> reflects the validity boundary; delayed scheduled work cannot extend access to the credential.</Typography>
          <Typography component="li" variant="body2"><strong>REVOKED</strong> is authoritative even if external provider cleanup is still pending elsewhere.</Typography>
          <Typography component="li" variant="body2"><strong>COMPROMISED</strong> is immediately unsafe and is intentionally separate from routine rotation.</Typography>
        </Box>
      </Surface>
      <Workspace spec={spec} />
    </Box>
  );
}

function RotationWorkspace() {
  const [credentialId, setCredentialId] = useState('');
  const [activeCredentialId, setActiveCredentialId] = useState('');
  const [planning, setPlanning] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const [result, setResult] = useState<CredentialRotationResource | null>(null);
  const [error, setError] = useState<unknown>(null);

  const rotationSpec = useMemo<Spec | null>(() => activeCredentialId ? ({
    eyebrow: 'Credential · durable rotation',
    title: 'Rotation history',
    description: `Durable rotation process state for old Credential ${activeCredentialId}. Rotation progress is not Credential lifecycle and does not imply synchronous provider execution.`,
    list: list(`${V1_ROOT}/credentials/${encodeURIComponent(activeCredentialId)}/rotations`),
    detail: (id) => `${V1_ROOT}/credential-rotations/${encodeURIComponent(id)}`,
    columns: ['processState', 'replacementCredentialId', 'failureCode', 'createdAt', 'completedAt'],
  }) : null, [activeCredentialId]);

  function openCredential() {
    const next = credentialId.trim();
    if (!next) return;
    setActiveCredentialId(next);
    setResult(null);
    setError(null);
  }

  async function planRotation() {
    if (!activeCredentialId) return;
    setPlanning(true);
    setError(null);
    try {
      const response = await apiRequest<CredentialRotationResource>(
        `${V1_ROOT}/credentials/${encodeURIComponent(activeCredentialId)}:rotate`,
        { method: 'POST', idempotent: true },
      );
      setResult(response.data);
      setRefresh((value) => value + 1);
    } catch (cause) {
      setError(cause);
    } finally {
      setPlanning(false);
    }
  }

  return (
    <Page>
      <PageHeader
        eyebrow="Credential · rotation safety"
        title="Credential rotation"
        description="Plan and read the accepted durable CredentialRotation process. The Console does not expose internal provider-execution steps or generic process-state mutation."
      />

      <StatePanel state="informational" title="Routine rotation is a durable process, not a status edit">
        Planning creates a separate PLANNED CredentialRotation for an effective ACTIVE credential. Replacement creation, distribution, verification, cutover, and old-credential revocation remain durable server/provider execution stages; this UI cannot advance them manually.
      </StatePanel>

      <StatePanel state="warning" title="Compromise and routine rotation are different operations">
        Do not use routine rotation as a substitute for compromise handling. A known-compromised credential must be made unsafe through the explicit compromise operation even when that can interrupt service.
      </StatePanel>

      <Surface
        title="Choose old Credential"
        description="Use a Credential ID from the active Principal context. Rotation history is scoped to this old Credential."
      >
        <Box sx={{ display: 'flex', flexDirection: { xs: 'column', sm: 'row' }, gap: 1, alignItems: { sm: 'flex-start' } }}>
          <TextInput
            label="Credential ID"
            value={credentialId}
            helperText="Public Credential stable ID; no secret/private material."
            onChange={(event) => setCredentialId(event.target.value)}
            sx={{ flex: 1 }}
          />
          <Button variant="contained" disabled={!credentialId.trim()} onClick={openCredential}>Open rotation history</Button>
        </Box>
      </Surface>

      {activeCredentialId ? (
        <Surface
          title="Rotation planning"
          description="Creates durable PLANNED process state only. Starting routine rotation does not use the Credential If-Match revision; causal idempotency still applies."
          actions={(
            <Button variant="outlined" disabled={planning} onClick={() => void planRotation()}>
              {planning ? 'Planning…' : 'Plan routine rotation'}
            </Button>
          )}
        >
          <Typography variant="body2" className="mono" sx={{ overflowWrap: 'anywhere' }}>
            Old Credential: {activeCredentialId}
          </Typography>
        </Surface>
      ) : null}

      {planning ? <StatePanel state="loading" title="Planning rotation">Creating durable rotation state…</StatePanel> : null}
      {error !== null ? (
        <StatePanel state="error" title="Rotation planning failed">
          {error instanceof Error ? error.message : 'Unable to plan Credential rotation.'}
        </StatePanel>
      ) : null}
      {result ? (
        <StatePanel state="informational" title="Rotation planned">
          Rotation {result.id} is {result.processState}. This confirms durable process state only; it does not claim that replacement creation, distribution, verification, cutover, or provider revocation has completed.
        </StatePanel>
      ) : null}

      {rotationSpec ? <Workspace key={`${activeCredentialId}:${refresh}`} spec={rotationSpec} /> : (
        <StatePanel state="empty" title="No Credential selected">
          Choose an old Credential to load its rotation history and enable routine rotation planning.
        </StatePanel>
      )}
    </Page>
  );
}

export function CredentialsWorkspace() {
  const [principal, setPrincipal] = useState('');
  const [activePrincipal, setActivePrincipal] = useState('');

  if (!activePrincipal) {
    return (
      <Page>
        <PageHeader
          eyebrow="Credential"
          title="Credentials"
          description="Credential metadata and lifecycle controls are contractually scoped to one Identity-owned Principal. Choose that Principal before reading or creating Credential metadata."
        />
        <StatePanel state="informational" title="Principal owns the technical account context">
          Credential belongs to exactly one Principal. Selecting a Principal scopes the Credential collection; it does not move Principal authority into the Credential capability or into the browser.
        </StatePanel>
        <Surface title="Choose Principal" description="Use the stable Principal ID from the accepted Principal control-plane surface.">
          <Box sx={{ display: 'flex', flexDirection: { xs: 'column', sm: 'row' }, gap: 1, alignItems: { sm: 'flex-start' } }}>
            <TextInput
              label="Principal ID"
              value={principal}
              helperText="Identity-owned Principal stable ID; server authorization and tenant validation remain final."
              onChange={(event) => setPrincipal(event.target.value)}
              sx={{ flex: 1 }}
            />
            <Button variant="contained" disabled={!principal.trim()} onClick={() => setActivePrincipal(principal.trim())}>Open credentials</Button>
          </Box>
        </Surface>
      </Page>
    );
  }

  return (
    <Box sx={{ display: 'grid', gap: 2 }}>
      <Page>
        <PageHeader
          eyebrow="Credential"
          title="Principal credential workspace"
          description="Manage metadata lifecycle and inspect durable rotation state without treating secret/private material as ordinary form data."
          actions={<Button variant="outlined" onClick={() => { setActivePrincipal(''); setPrincipal(''); }}>Change Principal</Button>}
        />
        <Surface title="Active Principal context" description="Credential list/create operations below stay scoped to this Principal.">
          <Typography variant="body2" className="mono" sx={{ overflowWrap: 'anywhere' }}>{activePrincipal}</Typography>
        </Surface>
      </Page>

      <WorkspaceTabs
        tabs={[
          { id: 'metadata', label: 'Credential metadata', content: <CredentialMetadataWorkspace principalId={activePrincipal} /> },
          { id: 'rotation', label: 'Rotation process', content: <RotationWorkspace /> },
        ]}
      />
    </Box>
  );
}
