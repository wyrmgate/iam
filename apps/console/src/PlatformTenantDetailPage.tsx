import { useEffect, useState, type ReactNode } from 'react';
import { ApiError } from './api';
import { getPlatformTenant, type PlatformTenantResource } from './platformTenantsApi';
import { Box, Button, Page, StateIndicator, StatePanel, Surface, Typography } from './ui';

type Props = {
  tenantId: string;
  onBack: () => void;
};

type LoadState =
  | { status: 'loading' }
  | { status: 'ready'; tenant: PlatformTenantResource }
  | { status: 'error'; error: unknown };

function formatDateTime(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString([], {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function errorCopy(error: unknown): { title: string; body: string } {
  if (error instanceof ApiError && error.status === 403) {
    return {
      title: 'Platform Tenant detail is not authorized',
      body: 'A current viable Platform Owner is required. Tenant-scoped administration does not grant deployment-wide Tenant inspection.',
    };
  }
  if (error instanceof ApiError && error.status === 404) {
    return {
      title: 'Tenant not found',
      body: 'No canonical Platform Tenant exists for this identifier, or it is no longer available.',
    };
  }
  return {
    title: 'Platform Tenant detail is unavailable',
    body: error instanceof Error ? error.message : 'Wyrmgate could not read this Tenant.',
  };
}

function DefinitionRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Box
      sx={{
        display: 'grid',
        gridTemplateColumns: { xs: '1fr', sm: '10rem minmax(0, 1fr)' },
        gap: { xs: 0.35, sm: 2 },
        py: 1.15,
        borderBottom: 1,
        borderColor: 'divider',
        '&:last-of-type': { borderBottom: 0 },
      }}
    >
      <Typography component="dt" variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>
        {label}
      </Typography>
      <Typography component="dd" variant="body2" sx={{ m: 0, overflowWrap: 'anywhere' }}>
        {children}
      </Typography>
    </Box>
  );
}

export function PlatformTenantDetailPage({ tenantId, onBack }: Props) {
  const [state, setState] = useState<LoadState>({ status: 'loading' });
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setState({ status: 'loading' });
    getPlatformTenant(tenantId, controller.signal)
      .then((tenant) => setState({ status: 'ready', tenant }))
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return;
        setState({ status: 'error', error });
      });
    return () => controller.abort();
  }, [tenantId, reloadKey]);

  const tenantName = state.status === 'ready' ? state.tenant.displayName : 'Tenant inspection';
  const initial = tenantName.trim().slice(0, 1).toUpperCase() || 'T';

  return (
    <Page width="standard" aria-labelledby="platform-tenant-title" sx={{ gap: 0 }}>
      <Box component="nav" aria-label="Breadcrumb" sx={{ display: { xs: 'none', sm: 'flex' }, alignItems: 'center', gap: 0.8, color: 'text.secondary', mb: 1.25 }}>
        <Typography variant="caption">Platform</Typography><Typography variant="caption">›</Typography><Typography variant="caption">Tenants</Typography><Typography variant="caption">›</Typography><Typography variant="caption" color="text.primary">{tenantName}</Typography>
      </Box>

      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '48px minmax(0, 1fr)', sm: '64px minmax(0, 1fr) auto' }, gap: { xs: 1.5, sm: 2 }, alignItems: 'center', mb: 2 }}>
        <Box sx={{ width: { xs: 44, sm: 56 }, height: { xs: 44, sm: 56 }, borderRadius: '50%', bgcolor: 'primary.main', color: 'primary.contrastText', display: 'grid', placeItems: 'center', fontSize: { xs: 20, sm: 24 }, fontWeight: 600 }}>{initial}</Box>
        <Box sx={{ minWidth: 0 }}>
          <Typography component="h1" id="platform-tenant-title" sx={{ fontSize: { xs: 22, sm: 26 }, lineHeight: 1.15, fontWeight: 700, overflowWrap: 'anywhere' }}>{tenantName}</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mt: 0.45, overflowWrap: 'anywhere' }}>Tenant ID: {state.status === 'ready' ? state.tenant.id : tenantId}</Typography>
          {state.status === 'ready' ? <Typography variant="caption" color="text.secondary" sx={{ display: { xs: 'none', sm: 'block' }, mt: 0.35 }}>Revision {state.tenant.revision} · Created {formatDateTime(state.tenant.createdAt)}</Typography> : null}
        </Box>
        <Box sx={{ display: { xs: 'none', sm: 'flex' }, alignItems: 'center', gap: 1, justifySelf: 'end' }}>
          <StateIndicator label="Platform inspection" tone="info" />
          <Button variant="outlined" onClick={onBack}>Back to Tenants</Button>
        </Box>
      </Box>

      <Box sx={{ display: { xs: 'flex', sm: 'none' }, mb: 1.5 }}>
        <Button variant="outlined" onClick={onBack} fullWidth>Back to Tenant inventory</Button>
      </Box>

      <Surface
        title="Inspection boundary"
        description="This is the Tenant Management presentation in deployment inspection mode. The selected Tenant identifier is a requested Platform resource, not authenticated current-Tenant context."
        sx={{ mb: 1.5, p: 2 }}
      >
        <Typography variant="body2" color="text.secondary">
          Inspecting this resource does not change your current Tenant context or grant tenant-scoped administrative authority. Tenant-owned Identity, Principal, Access, Governance, Credential, Integration and Audit workspaces are intentionally not exposed from this route.
        </Typography>
      </Surface>

      {state.status === 'loading' ? (
        <StatePanel state="loading" title="Loading Tenant detail">
          Reading canonical Platform-owned Tenant metadata.
        </StatePanel>
      ) : null}

      {state.status === 'error' ? (() => {
        const copy = errorCopy(state.error);
        return (
          <StatePanel
            state="error"
            title={copy.title}
            action={<Button variant="outlined" onClick={() => setReloadKey((value) => value + 1)}>Retry</Button>}
          >
            {copy.body}
          </StatePanel>
        );
      })() : null}

      {state.status === 'ready' ? (
        <Surface
          title="Tenant information"
          description="Canonical Tenant metadata only. This view intentionally excludes Tenant lifecycle/status, code, description, subscription, domains, tenant-owned IAM data and mutation controls."
          sx={{ p: 2 }}
        >
          <Box component="dl" sx={{ m: 0 }}>
            <DefinitionRow label="Display name">{state.tenant.displayName}</DefinitionRow>
            <DefinitionRow label="Tenant ID">
              <Box component="span" sx={{ fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace' }}>
                {state.tenant.id}
              </Box>
            </DefinitionRow>
            <DefinitionRow label="Revision">{state.tenant.revision}</DefinitionRow>
            <DefinitionRow label="Created"><time dateTime={state.tenant.createdAt}>{formatDateTime(state.tenant.createdAt)}</time></DefinitionRow>
            <DefinitionRow label="Updated"><time dateTime={state.tenant.updatedAt}>{formatDateTime(state.tenant.updatedAt)}</time></DefinitionRow>
          </Box>
        </Surface>
      ) : null}
    </Page>
  );
}
