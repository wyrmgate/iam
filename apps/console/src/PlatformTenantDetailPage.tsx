import { useEffect, useState, type ReactNode } from 'react';
import { ApiError } from './api';
import { getPlatformTenant, type PlatformTenantResource } from './platformTenantsApi';
import { Box, Button, Page, PageHeader, StatePanel, Surface, Typography } from './ui';

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
        gridTemplateColumns: { xs: '1fr', sm: '12rem minmax(0, 1fr)' },
        gap: { xs: 0.5, sm: 2 },
        py: 1.25,
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

  return (
    <Page width="standard">
      <PageHeader
        eyebrow="Platform · Tenant inspection"
        title={state.status === 'ready' ? state.tenant.displayName : 'Tenant detail'}
        description="Read-only deployment metadata for the selected Tenant. Inspecting this resource does not change your current Tenant context or grant tenant-scoped administrative authority."
        actions={<Button variant="outlined" onClick={onBack}>Back to Tenants</Button>}
      />

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
          title="Canonical Tenant metadata"
          description="This view intentionally excludes Tenant lifecycle/status, code, description, subscription, domains, tenant-owned IAM data and mutation controls."
        >
          <Box component="dl" sx={{ m: 0 }}>
            <DefinitionRow label="Display name">{state.tenant.displayName}</DefinitionRow>
            <DefinitionRow label="Tenant ID">
              <Box component="span" sx={{ fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace' }}>
                {state.tenant.id}
              </Box>
            </DefinitionRow>
            <DefinitionRow label="Revision">{state.tenant.revision}</DefinitionRow>
            <DefinitionRow label="Created">
              <time dateTime={state.tenant.createdAt}>{formatDateTime(state.tenant.createdAt)}</time>
            </DefinitionRow>
            <DefinitionRow label="Updated">
              <time dateTime={state.tenant.updatedAt}>{formatDateTime(state.tenant.updatedAt)}</time>
            </DefinitionRow>
          </Box>
        </Surface>
      ) : null}
    </Page>
  );
}
