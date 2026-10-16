import { useEffect, useMemo, useState } from 'react';
import type { CurrentAdministrativeAuthority } from './api';
import {
  getIamOperationsOverview,
  type IamOperationsOverview,
  type OverviewMetric,
  type OverviewStatus,
} from './iamOperationsApi';
import { navigateConsole } from './consoleNavigation';
import {
  Box,
  Button,
  DataTable,
  DataTableActionCell,
  DataTableRow,
  Page,
  PageHeader,
  StateIndicator,
  StatePanel,
  Surface,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
  type StateIndicatorTone,
} from './ui';

type Props = {
  authority: CurrentAdministrativeAuthority | null;
};

type LoadState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'ready'; value: IamOperationsOverview }
  | { status: 'error'; error: unknown };

type InventoryRow = {
  label: string;
  owner: string;
  metric: OverviewMetric;
  meaning: string;
  path: string;
  action: string;
};

type ManagementRow = {
  label: string;
  owner: string;
  description: string;
  path: string;
  action: string;
};

function shortId(value: string): string {
  return value.length <= 32 ? value : `${value.slice(0, 12)}…${value.slice(-8)}`;
}

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

function statusTone(status: OverviewStatus): StateIndicatorTone {
  switch (status) {
    case 'AVAILABLE': return 'success';
    case 'NOT_AUTHORIZED': return 'warning';
    case 'UNAVAILABLE': return 'danger';
    default: return 'neutral';
  }
}

function metricValue(metric: OverviewMetric): string | null {
  if (metric.status !== 'AVAILABLE' || metric.value === null) return null;
  return metric.value.toLocaleString();
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : 'Wyrmgate could not load the current Tenant projection.';
}

function Definition({ term, children }: { term: string; children: React.ReactNode }) {
  return (
    <Box component="div" sx={{ display: 'grid', gap: 0.5 }}>
      <Typography component="dt" variant="caption" color="text.secondary">{term}</Typography>
      <Typography component="dd" variant="body2" sx={{ m: 0, fontWeight: 600, overflowWrap: 'anywhere' }}>{children}</Typography>
    </Box>
  );
}

export function TenantOverviewPage({ authority }: Props) {
  const [overview, setOverview] = useState<LoadState>({ status: 'idle' });
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    if (!authority) {
      setOverview({ status: 'idle' });
      return;
    }
    const controller = new AbortController();
    setOverview({ status: 'loading' });
    getIamOperationsOverview('24h', 'ALL', 'OLDEST', controller.signal)
      .then((value) => setOverview({ status: 'ready', value }))
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return;
        setOverview({ status: 'error', error });
      });
    return () => controller.abort();
  }, [authority?.tenantId, reloadKey]);

  const inventoryRows = useMemo<InventoryRow[]>(() => {
    if (overview.status !== 'ready') return [];
    const { environment } = overview.value;
    return [
      {
        label: 'Active identities',
        owner: 'Identity',
        metric: environment.activeIdentities,
        meaning: 'Governed subjects owned by this Tenant. Identity is the subject; Principal is the technical account representation.',
        path: '/identities',
        action: 'Open identities',
      },
      {
        label: 'Applications',
        owner: 'Catalog',
        metric: environment.applications,
        meaning: 'Tenant-local Applications, including tenant-local bindings created for admitted platform offerings.',
        path: '/catalog',
        action: 'Open Catalog',
      },
      {
        label: 'Active access assignments',
        owner: 'Access',
        metric: environment.activeAccessAssignments,
        meaning: 'Authoritative business access intent. Application access is expressed through Role or Entitlement targets, not a synthetic user-application membership.',
        path: '/access',
        action: 'Open access',
      },
      {
        label: 'Active connectors',
        owner: 'Integration',
        metric: environment.activeConnectors,
        meaning: 'Tenant-scoped connector administration and operational integration state.',
        path: '/integrations',
        action: 'Open integrations',
      },
      {
        label: 'Uncorrelated principals',
        owner: 'Identity',
        metric: environment.uncorrelatedPrincipals,
        meaning: 'Technical Principal records that are not currently correlated to a governed Identity.',
        path: '/principals',
        action: 'Open principals',
      },
    ];
  }, [overview]);

  const managementRows: ManagementRow[] = [
    {
      label: 'People and governed subjects',
      owner: 'Identity',
      description: 'Manage tenant-owned Identities and their lifecycle. Organization is business structure inside the Tenant, not a second isolation boundary.',
      path: '/identities',
      action: 'Manage identities',
    },
    {
      label: 'Application availability',
      owner: 'Catalog',
      description: 'Manage tenant-local Applications, targets, entitlements and roles. A platform-shared offering becomes tenant-usable only through an explicit tenant binding.',
      path: '/catalog',
      action: 'Manage applications',
    },
    {
      label: 'Application access',
      owner: 'Access',
      description: 'Manage AccessAssignment business intent and inspect EffectiveAccess separately. Assignments target Roles or Entitlements, never an arbitrary hasApplication flag.',
      path: '/access',
      action: 'Manage access',
    },
    {
      label: 'Requests, approvals and policy',
      owner: 'Governance',
      description: 'Govern privilege increases, approvals, reviews, policy and exceptions without rewriting Access authority.',
      path: '/requests',
      action: 'Open governance work',
    },
    {
      label: 'Sources and integrations',
      owner: 'Integration',
      description: 'Operate connector administration, provisioning and reconciliation while keeping provider observation distinct from authoritative IAM state.',
      path: '/integrations',
      action: 'Manage integrations',
    },
    {
      label: 'Tenant administrators',
      owner: 'Administration',
      description: 'Manage tenant-scoped administrative authority. Tenant Owner authority is distinct from business/application access and from deployment Platform Owner authority.',
      path: '/administration',
      action: 'Manage administrators',
    },
    {
      label: 'Audit and evidence',
      owner: 'Audit',
      description: 'Investigate append-only tenant evidence and durable evidence workflows without treating audit records as mutable business state.',
      path: '/audit',
      action: 'Open audit',
    },
  ];

  return (
    <Page width="wide" aria-labelledby="tenant-page-title">
      <PageHeader
        eyebrow="Administration · Tenant context"
        title={<span id="tenant-page-title">Current Tenant</span>}
        description="Operate the server-resolved Tenant isolation boundary without creating a parallel Tenant domain model in the browser. Each linked workspace keeps authority with its owning IAM capability."
        actions={(
          <Button
            variant="outlined"
            disabled={!authority || overview.status === 'loading'}
            onClick={() => setReloadKey((value) => value + 1)}
          >
            {overview.status === 'loading' ? 'Refreshing…' : 'Refresh'}
          </Button>
        )}
      />

      {!authority ? (
        <StatePanel state="loading" title="Resolving Tenant context">
          Reading the current governed actor and server-authoritative Tenant context.
        </StatePanel>
      ) : (
        <Surface
          title="Tenant context"
          description="This context comes from the authenticated control plane. The browser does not choose or override authoritative Tenant routing."
        >
          <Box
            component="dl"
            sx={{
              m: 0,
              display: 'grid',
              gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, minmax(0, 1fr))', lg: 'repeat(4, minmax(0, 1fr))' },
              gap: 2,
            }}
          >
            <Definition term="Tenant ID">{shortId(authority.tenantId)}</Definition>
            <Definition term="Current actor">{shortId(authority.actorIdentityId)}</Definition>
            <Definition term="Administrative eligibility">{authority.administrativelyEligible ? 'Eligible' : 'Not eligible'}</Definition>
            <Definition term="Authority evaluated"><time dateTime={authority.evaluatedAt}>{formatDateTime(authority.evaluatedAt)}</time></Definition>
          </Box>
        </Surface>
      )}

      {overview.status === 'loading' ? (
        <StatePanel state="loading" title="Loading tenant-bound inventory">
          Reading existing tenant-scoped Console projections. No synthetic counts are generated in the browser.
        </StatePanel>
      ) : null}

      {overview.status === 'error' ? (
        <StatePanel
          state="error"
          title="Tenant inventory is unavailable"
          action={<Button variant="outlined" onClick={() => setReloadKey((value) => value + 1)}>Retry</Button>}
        >
          {errorMessage(overview.error)} The management workspaces below remain available; operation-time server authorization is final.
        </StatePanel>
      ) : null}

      {overview.status === 'ready' ? (
        <Surface
          title="Tenant-bound inventory"
          description={`As of ${formatDateTime(overview.value.environment.asOf)}. Counts reuse the existing Console operations projection and remain semantically owned by their capabilities.`}
        >
          <DataTable ariaLabel="Current Tenant inventory" minWidth={880}>
            <TableHead>
              <TableRow>
                <TableCell>Tenant view</TableCell>
                <TableCell>Owning capability</TableCell>
                <TableCell>Current value</TableCell>
                <TableCell>Meaning</TableCell>
                <TableCell align="right">Action</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {inventoryRows.map((row) => {
                const value = metricValue(row.metric);
                return (
                  <DataTableRow key={row.label}>
                    <TableCell><Typography variant="body2" sx={{ fontWeight: 600 }}>{row.label}</Typography></TableCell>
                    <TableCell>{row.owner}</TableCell>
                    <TableCell>
                      {value !== null
                        ? <Typography variant="body2" sx={{ fontWeight: 700 }}>{value}</Typography>
                        : <StateIndicator label={row.metric.status.replaceAll('_', ' ')} tone={statusTone(row.metric.status)} />}
                    </TableCell>
                    <TableCell><Typography variant="body2" color="text.secondary">{row.meaning}</Typography></TableCell>
                    <DataTableActionCell>
                      <Button variant="outlined" size="small" onClick={() => navigateConsole(row.path)}>{row.action}</Button>
                    </DataTableActionCell>
                  </DataTableRow>
                );
              })}
            </TableBody>
          </DataTable>
        </Surface>
      ) : null}

      <Surface
        title="Manage this Tenant"
        description="Use the owning capability for each kind of tenant-bound state. This page coordinates navigation; it does not become a cross-capability aggregate or mutation authority."
      >
        <DataTable ariaLabel="Tenant management workspaces" minWidth={820}>
          <TableHead>
            <TableRow>
              <TableCell>Area</TableCell>
              <TableCell>Owning capability</TableCell>
              <TableCell>Responsibility</TableCell>
              <TableCell align="right">Action</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {managementRows.map((row) => (
              <DataTableRow key={row.label}>
                <TableCell><Typography variant="body2" sx={{ fontWeight: 600 }}>{row.label}</Typography></TableCell>
                <TableCell>{row.owner}</TableCell>
                <TableCell><Typography variant="body2" color="text.secondary">{row.description}</Typography></TableCell>
                <DataTableActionCell>
                  <Button variant="outlined" size="small" onClick={() => navigateConsole(row.path)}>{row.action}</Button>
                </DataTableActionCell>
              </DataTableRow>
            ))}
          </TableBody>
        </DataTable>
      </Surface>

      <Surface
        title="Tenant boundary semantics"
        description="These distinctions are architectural invariants, not browser conventions."
      >
        <Box component="ul" sx={{ m: 0, pl: 2.5, display: 'grid', gap: 1 }}>
          <Typography component="li" variant="body2">Tenant is the isolation boundary; Organization is tenant-local business structure.</Typography>
          <Typography component="li" variant="body2">A governed Identity is tenant-owned state. Wyrmgate does not invent a generic cross-tenant UserTenantMembership authority model.</Typography>
          <Typography component="li" variant="body2">Application availability belongs to Catalog. Individual application access belongs to Access through AccessAssignment targeting a Role or Entitlement; EffectiveAccess remains a derived projection.</Typography>
          <Typography component="li" variant="body2">Platform-shared and platform-internal offerings remain platform-owned definitions. Tenant-facing use occurs through an explicit binding to a tenant-local Application; governance, access and SSO state remain tenant-isolated.</Typography>
          <Typography component="li" variant="body2">AdministrativeRole, Grant, Delegation, Elevation and Tenant Owner authority are Administration concerns and remain distinct from IAM business access.</Typography>
        </Box>
      </Surface>
    </Page>
  );
}
