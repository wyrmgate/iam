import { useEffect, useState, type ReactNode } from 'react';
import { ApiError } from './api';
import { getPlatformTenant, type PlatformTenantResource } from './platformTenantsApi';
import { Box, Button, Page, PageHeader, StateIndicator, StatePanel, Surface } from './ui';

type Props = {
  tenantId: string;
  onBack: () => void;
};

type LoadState =
  | { status: 'loading' }
  | { status: 'ready'; tenant: PlatformTenantResource }
  | { status: 'error'; error: unknown };

type DefinitionItem = {
  label: string;
  value: ReactNode;
  mono?: boolean;
};

const monoSx = {
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace',
  overflowWrap: 'anywhere',
} as const;

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

function DefinitionList({ items }: { items: DefinitionItem[] }) {
  return (
    <Box
      component="dl"
      sx={{
        m: 0,
        display: 'grid',
        gridTemplateColumns: { xs: '1fr', sm: 'minmax(9rem, 12rem) minmax(0, 1fr)' },
        columnGap: 2,
        '& > dt, & > dd': { py: 1, m: 0, borderBottom: 1, borderColor: 'divider' },
        '& > dt': { color: 'text.secondary', fontSize: '0.75rem' },
        '& > dd': { fontSize: '0.8125rem', fontWeight: 600, overflowWrap: 'anywhere' },
      }}
    >
      {items.flatMap((item) => [
        <Box component="dt" key={`${item.label}-label`}>{item.label}</Box>,
        <Box component="dd" key={`${item.label}-value`} sx={item.mono ? monoSx : undefined}>{item.value}</Box>,
      ])}
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
  const resolvedTenantId = state.status === 'ready' ? state.tenant.id : tenantId;

  return (
    <Page width="standard" aria-labelledby="platform-tenant-title">
      <PageHeader
        eyebrow="Platform · Tenant inspection"
        title={<Box component="span" id="platform-tenant-title">{tenantName}</Box>}
        description={`Deployment-scoped read-only Tenant resource · Tenant ID ${resolvedTenantId}`}
        actions={(
          <>
            <StateIndicator label="Platform inspection · read-only" tone="info" />
            <Button variant="outlined" onClick={onBack}>Back to Tenants</Button>
          </>
        )}
      />

      <StatePanel state="info" title="Deployment inspection boundary">
        The selected Tenant identifier is a requested Platform resource, not authenticated current-Tenant context. Inspecting it does not change your current Tenant context or grant tenant-scoped administrative authority. Tenant-owned capability workspaces are intentionally not linked from this route.
      </StatePanel>

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
          description="Canonical Platform-owned metadata only. Tenant lifecycle/status, code, description, subscription, domains, tenant-owned IAM data and mutation controls are outside this inspection contract."
        >
          <DefinitionList items={[
            { label: 'Display name', value: state.tenant.displayName },
            { label: 'Tenant ID', value: state.tenant.id, mono: true },
            { label: 'Revision', value: state.tenant.revision },
            { label: 'Created', value: <time dateTime={state.tenant.createdAt}>{formatDateTime(state.tenant.createdAt)}</time> },
            { label: 'Updated', value: <time dateTime={state.tenant.updatedAt}>{formatDateTime(state.tenant.updatedAt)}</time> },
          ]} />
        </Surface>
      ) : null}
    </Page>
  );
}
