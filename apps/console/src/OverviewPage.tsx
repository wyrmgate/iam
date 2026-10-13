import type { CurrentAdministrativeAuthority, SystemInfo } from './api';
import {
  Box,
  Page,
  PageHeader,
  StatePanel,
  StatusChip,
  Surface,
  Typography,
} from './ui';

type LoadState<T> =
  | { status: 'loading' }
  | { status: 'ready'; value: T }
  | { status: 'error'; error: unknown };

type OverviewPageProps = {
  system: LoadState<SystemInfo>;
  authority: LoadState<CurrentAdministrativeAuthority>;
};

function evaluatedAtLabel(value: string) {
  const timestamp = new Date(value);
  return Number.isNaN(timestamp.getTime()) ? value : timestamp.toLocaleString();
}

function sourceLabel(source: CurrentAdministrativeAuthority['authorities'][number]['source']) {
  return source.toLowerCase().split('_').map((part) => part[0].toUpperCase() + part.slice(1)).join(' ');
}

function ContextValue({ label, value, supporting }: { label: string; value: string; supporting?: string }) {
  return (
    <Box sx={{ minWidth: 0 }}>
      <Typography variant="overline" color="text.secondary" sx={{ fontWeight: 800, letterSpacing: '.08em' }}>
        {label}
      </Typography>
      <Typography variant="h6" sx={{ mt: 0.25, overflowWrap: 'anywhere' }}>
        {value}
      </Typography>
      {supporting ? (
        <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5, lineHeight: 1.55 }}>
          {supporting}
        </Typography>
      ) : null}
    </Box>
  );
}

export function OverviewPage({ system, authority }: OverviewPageProps) {
  const authorityValue = authority.status === 'ready' ? authority.value : null;
  const sourceTypes = authorityValue
    ? [...new Set(authorityValue.authorities.map((entry) => entry.source))]
    : [];

  const runtimeName = system.status === 'ready'
    ? system.value.name
    : system.status === 'loading' ? 'Loading…' : 'Unavailable';
  const tenantId = authorityValue?.tenantId ?? (authority.status === 'loading' ? 'Loading…' : 'Unavailable');
  const actorIdentityId = authorityValue?.actorIdentityId ?? (authority.status === 'loading' ? 'Loading…' : 'Unavailable');

  return (
    <Page width="wide">
      <PageHeader
        eyebrow="Control plane"
        title="Overview"
        description="Current server-derived operating context for the Wyrmgate control plane. Business decisions and operation-time authorization remain server-side."
      />

      {(system.status === 'loading' || authority.status === 'loading') ? (
        <StatePanel title="Loading current control-plane context">
          Resolving runtime and current administrative authority from Wyrmgate.
        </StatePanel>
      ) : null}

      {system.status === 'error' ? (
        <StatePanel severity="error" title="Runtime status unavailable">
          Wyrmgate runtime information could not be loaded. No runtime health is inferred from browser state.
        </StatePanel>
      ) : null}

      {authority.status === 'error' ? (
        <StatePanel severity="warning" title="Administrative authority unavailable">
          The current no-store Administration projection could not be loaded. Navigation remains available; operation-time server authorization remains final.
        </StatePanel>
      ) : null}

      <Surface
        title="Current control-plane context"
        description="Tenant, governed actor and administrative authority are resolved by Wyrmgate rather than inferred from browser claims."
        actions={system.status === 'ready' ? <StatusChip label={system.value.status} /> : undefined}
      >
        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: { xs: '1fr', md: 'repeat(3, minmax(0, 1fr))' },
            gap: { xs: 2, md: 3 },
          }}
        >
          <ContextValue
            label="Runtime"
            value={runtimeName}
            supporting={system.status === 'ready' ? 'Status is reported by the current Wyrmgate runtime.' : 'Runtime identity is not available.'}
          />
          <ContextValue
            label="Tenant"
            value={tenantId}
            supporting="Server-derived tenant isolation context."
          />
          <ContextValue
            label="Governed actor"
            value={actorIdentityId}
            supporting="Current governed Identity resolved for this control-plane session."
          />
        </Box>
      </Surface>

      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', lg: 'minmax(0, 1.3fr) minmax(20rem, .7fr)' }, gap: 2 }}>
        <Surface
          title="Administrative authority"
          description="Current no-store point-in-time Administration projection. It improves visibility and navigation but never replaces operation-time authorization."
          actions={authorityValue ? (
            <StatusChip
              label={authorityValue.administrativelyEligible ? 'Administratively eligible' : 'Not administratively eligible'}
              color={authorityValue.administrativelyEligible ? 'success' : 'default'}
            />
          ) : undefined}
        >
          {authority.status === 'loading' ? (
            <StatePanel>Loading current administrative authority…</StatePanel>
          ) : null}

          {authority.status === 'error' ? (
            <StatePanel severity="warning" title="Authority projection unavailable">
              Wyrmgate will still authorize each requested operation on the server; the browser does not guess current authority.
            </StatePanel>
          ) : null}

          {authorityValue ? (
            <Box sx={{ display: 'grid', gap: 2 }}>
              <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, minmax(0, 1fr))' }, gap: 2 }}>
                <ContextValue
                  label="Effective sources"
                  value={String(authorityValue.authorities.length)}
                  supporting="Effective authority entries returned by the current projection."
                />
                <ContextValue
                  label="Evaluated"
                  value={evaluatedAtLabel(authorityValue.evaluatedAt)}
                  supporting="Point-in-time evaluation; not durable session authority."
                />
              </Box>

              {authorityValue.authorities.length === 0 ? (
                <StatePanel title="No effective administrative authority sources">
                  No administrative authority is currently projected for this actor. A later operation is still decided by the server from current authoritative state.
                </StatePanel>
              ) : (
                <Box>
                  <Typography variant="subtitle2" sx={{ mb: 1 }}>Authority source types</Typography>
                  <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1 }}>
                    {sourceTypes.map((source) => <StatusChip key={source} label={sourceLabel(source)} />)}
                  </Box>
                </Box>
              )}
            </Box>
          ) : null}
        </Surface>

        <Surface
          title="Control-plane boundaries"
          description="Operator cues for interpreting this workspace without turning presentation state into IAM authority."
        >
          <Box sx={{ display: 'grid', gap: 2 }}>
            <ContextValue
              label="Trust context"
              value="Server-derived"
              supporting="Tenant, governed actor and administrative authority are not inferred from OAuth/OIDC claims in the browser."
            />
            <ContextValue
              label="API routing"
              value="Same origin"
              supporting="The browser calls only /api/*; backend origin configuration remains at the edge."
            />
            <ContextValue
              label="Authorization"
              value="Server final"
              supporting="A server 403 remains final even when navigation or the authority projection suggests visibility."
            />
          </Box>
        </Surface>
      </Box>
    </Page>
  );
}
