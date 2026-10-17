import { useEffect, useMemo, useState, type ReactNode } from 'react';
import type { CurrentAdministrativeAuthority } from './api';
import {
  getIamOperationsOverview,
  type IamOperationsOverview,
  type OverviewBreakdown,
  type OverviewMetric,
  type OverviewSourceSystem,
  type OverviewStatus,
} from './iamOperationsApi';
import { navigateConsole } from './consoleNavigation';
import { getTenantContext, type TenantContext } from './tenantContextApi';
import {
  Box,
  Button,
  DataTable,
  DataTableRow,
  Page,
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

type LoadState<T> =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'ready'; value: T }
  | { status: 'error'; error: unknown };

type IconName =
  | 'overview'
  | 'identities'
  | 'applications'
  | 'access'
  | 'organizations'
  | 'governance'
  | 'sources'
  | 'administrators'
  | 'audit'
  | 'settings'
  | 'connectors'
  | 'check'
  | 'warning'
  | 'edit';

type TenantTab = {
  label: string;
  icon: IconName;
  path?: string;
  disabledReason?: string;
};

type BreakdownLabel = {
  key: string;
  label: string;
};

const tenantTabs: TenantTab[] = [
  { label: 'Overview', icon: 'overview', path: '/tenant' },
  { label: 'Identities', icon: 'identities', path: '/identities' },
  { label: 'Applications', icon: 'applications', path: '/catalog' },
  { label: 'Access', icon: 'access', path: '/access' },
  { label: 'Organizations', icon: 'organizations', disabledReason: 'Organization workspace is not exposed by the current Console contract.' },
  { label: 'Governance', icon: 'governance', path: '/requests' },
  { label: 'Sources & Integrations', icon: 'sources', path: '/integrations' },
  { label: 'Administrators', icon: 'administrators', path: '/administration' },
  { label: 'Audit', icon: 'audit', path: '/audit' },
  { label: 'Settings', icon: 'settings', disabledReason: 'Tenant settings are not exposed by the current Console contract.' },
];

const identityTypeLabels: BreakdownLabel[] = [
  { key: 'PERSON', label: 'People' },
  { key: 'SERVICE', label: 'Service identities' },
  { key: 'WORKLOAD', label: 'Workload identities' },
];

const applicationOriginLabels: BreakdownLabel[] = [
  { key: 'TENANT_LOCAL', label: 'Tenant local' },
  { key: 'PLATFORM_SHARED', label: 'Platform shared' },
  { key: 'PLATFORM_INTERNAL', label: 'Platform internal' },
];

function LineIcon({ name, size = 20 }: { name: IconName; size?: number }) {
  let content: ReactNode;
  switch (name) {
    case 'overview':
      content = <><path d="M3 10.5 12 3l9 7.5" /><path d="M5.5 9.5V21h13V9.5" /><path d="M9.5 21v-6h5v6" /></>;
      break;
    case 'identities':
      content = <><circle cx="9" cy="8" r="3" /><path d="M3.5 20c.4-3.6 2.4-5.5 5.5-5.5s5.1 1.9 5.5 5.5" /><path d="M15.5 5.5a3 3 0 0 1 0 5.5" /><path d="M16.5 14.7c2.5.4 3.9 2.1 4 5.3" /></>;
      break;
    case 'applications':
      content = <><path d="m12 2.8 8 4.5v9.4l-8 4.5-8-4.5V7.3Z" /><path d="m4.4 7.5 7.6 4.3 7.6-4.3" /><path d="M12 11.8v9" /></>;
      break;
    case 'access':
      content = <><circle cx="8" cy="15" r="4" /><path d="m10.9 12.1 8-8" /><path d="m16 7 2 2" /><path d="m14 9 2 2" /></>;
      break;
    case 'organizations':
      content = <><path d="M4 21V6h10v15" /><path d="M14 10h6v11" /><path d="M7 9h1M10 9h1M7 13h1M10 13h1M7 17h1M10 17h1M17 13h1M17 17h1" /></>;
      break;
    case 'governance':
      content = <><path d="M12 3 5 6v5c0 4.6 2.8 8.1 7 10 4.2-1.9 7-5.4 7-10V6Z" /><path d="m9 12 2 2 4-4" /></>;
      break;
    case 'sources':
      content = <><ellipse cx="12" cy="5.5" rx="7" ry="3" /><path d="M5 5.5v6c0 1.7 3.1 3 7 3s7-1.3 7-3v-6" /><path d="M5 11.5v6c0 1.7 3.1 3 7 3s7-1.3 7-3v-6" /></>;
      break;
    case 'administrators':
      content = <><circle cx="10" cy="8" r="3" /><path d="M4 20c.5-3.8 2.6-5.7 6-5.7 2 0 3.6.7 4.7 2" /><path d="m16.5 18 1.7 1.7 3.3-3.7" /></>;
      break;
    case 'audit':
      content = <><rect x="5" y="4" width="14" height="17" rx="2" /><path d="M9 4V2.8h6V4" /><path d="M8.5 9h7M8.5 13h7M8.5 17h4" /></>;
      break;
    case 'settings':
      content = <><circle cx="12" cy="12" r="3" /><path d="M19 13.5v-3l-2-.7a7 7 0 0 0-.7-1.7l.9-1.9-2.1-2.1-1.9.9a7 7 0 0 0-1.7-.7L10.5 2h-3l-.7 2.3a7 7 0 0 0-1.7.7l-1.9-.9-2.1 2.1.9 1.9a7 7 0 0 0-.7 1.7l-2 .7v3l2 .7a7 7 0 0 0 .7 1.7l-.9 1.9 2.1 2.1 1.9-.9a7 7 0 0 0 1.7.7l.7 2.3h3l.7-2.3a7 7 0 0 0 1.7-.7l1.9.9 2.1-2.1-.9-1.9a7 7 0 0 0 .7-1.7Z" transform="translate(1.5 0) scale(.88)" /></>;
      break;
    case 'connectors':
      content = <><circle cx="12" cy="5" r="2.5" /><circle cx="5" cy="18" r="2.5" /><circle cx="19" cy="18" r="2.5" /><path d="M12 7.5v4M7.1 16.3l3.5-4.8M16.9 16.3l-3.5-4.8" /></>;
      break;
    case 'check':
      content = <><circle cx="12" cy="12" r="9" /><path d="m8 12 2.6 2.6L16.5 9" /></>;
      break;
    case 'warning':
      content = <><path d="M12 3 2.8 20h18.4Z" /><path d="M12 9v5M12 17.2v.1" /></>;
      break;
    case 'edit':
      content = <><path d="m4 20 4.2-1 10.9-10.9-3.2-3.2L5 15.8Z" /><path d="m14.8 6 3.2 3.2" /></>;
      break;
  }
  return (
    <svg
      aria-hidden="true"
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      style={{ display: 'block', flex: '0 0 auto' }}
    >
      {content}
    </svg>
  );
}

function shortId(value?: string | null): string {
  if (!value) return '—';
  return value.length <= 28 ? value : `${value.slice(0, 12)}…${value.slice(-8)}`;
}

function humanize(value: string): string {
  return value
    .toLowerCase()
    .replaceAll('_', ' ')
    .replace(/(^|\s)\S/g, (letter) => letter.toUpperCase());
}

function formatDateTime(value?: string | null): string {
  if (!value) return '—';
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

function formatTime(value?: string | null): string {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '—';
  return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

function statusTone(status: OverviewStatus): StateIndicatorTone {
  switch (status) {
    case 'AVAILABLE': return 'success';
    case 'NOT_AUTHORIZED': return 'warning';
    case 'UNAVAILABLE': return 'danger';
    default: return 'neutral';
  }
}

function statusLabel(status: OverviewStatus): string {
  if (status === 'NOT_AUTHORIZED') return 'Not authorized';
  if (status === 'UNAVAILABLE') return 'Unavailable';
  if (status === 'UNSUPPORTED') return 'Not supported';
  return 'Available';
}

function metricText(metric: OverviewMetric): string | null {
  return metric.status === 'AVAILABLE' && metric.value !== null ? metric.value.toLocaleString() : null;
}

function metricFromCount(status: OverviewStatus, value: number | null): OverviewMetric {
  return { status, value: status === 'AVAILABLE' ? value : null };
}

function sumMetrics(...metrics: OverviewMetric[]): OverviewMetric {
  const firstUnavailable = metrics.find((metric) => metric.status !== 'AVAILABLE' || metric.value === null);
  if (firstUnavailable) return { status: firstUnavailable.status, value: null };
  return { status: 'AVAILABLE', value: metrics.reduce((sum, metric) => sum + (metric.value ?? 0), 0) };
}

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}

function toneColor(tone: StateIndicatorTone): string {
  if (tone === 'success') return 'success.main';
  if (tone === 'warning') return 'warning.main';
  if (tone === 'danger') return 'error.main';
  if (tone === 'info') return 'info.main';
  return 'text.secondary';
}

function Definition({ term, children }: { term: string; children: ReactNode }) {
  return (
    <Box component="div" sx={{ display: 'grid', gridTemplateColumns: '104px minmax(0, 1fr)', gap: 1.5, py: 0.65, alignItems: 'center' }}>
      <Typography component="dt" variant="caption" color="text.secondary">{term}</Typography>
      <Typography component="dd" variant="body2" sx={{ m: 0, fontWeight: 500, overflowWrap: 'anywhere' }}>{children}</Typography>
    </Box>
  );
}

function SummaryTile({ label, icon, metric, detail, path, tone = 'info' }: {
  label: string;
  icon: IconName;
  metric?: OverviewMetric;
  detail: string;
  path?: string;
  tone?: StateIndicatorTone;
}) {
  const value = metric ? metricText(metric) : null;
  return (
    <Box
      component={path ? 'button' : 'div'}
      type={path ? 'button' : undefined}
      onClick={path ? () => navigateConsole(path) : undefined}
      sx={{
        appearance: 'none',
        textAlign: 'left',
        width: '100%',
        minWidth: 0,
        minHeight: 104,
        p: 2,
        border: '1px solid',
        borderColor: 'divider',
        borderRadius: 1,
        bgcolor: 'background.paper',
        color: 'text.primary',
        cursor: path ? 'pointer' : 'default',
        transition: 'border-color 120ms ease, background-color 120ms ease',
        '&:hover': path ? { borderColor: 'primary.main', bgcolor: 'action.hover' } : undefined,
        '&:focus-visible': path ? { outline: '2px solid', outlineColor: 'primary.main', outlineOffset: 2 } : undefined,
      }}
    >
      <Box sx={{ display: 'grid', gridTemplateColumns: '28px minmax(0, 1fr)', gap: 1.25, alignItems: 'start' }}>
        <Box sx={{ color: toneColor(tone), pt: 0.15 }}><LineIcon name={icon} size={24} /></Box>
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block', lineHeight: 1.25 }}>{label}</Typography>
          <Box sx={{ minHeight: 34, display: 'flex', alignItems: 'center', mt: 0.25 }}>
            {metric ? (
              value !== null
                ? <Typography sx={{ fontSize: 22, lineHeight: 1.2, fontWeight: 700 }}>{value}</Typography>
                : <StateIndicator label={statusLabel(metric.status)} tone={statusTone(metric.status)} />
            ) : <Typography sx={{ fontSize: 22, lineHeight: 1.2, fontWeight: 700 }}>—</Typography>}
          </Box>
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5, lineHeight: 1.35 }}>{detail}</Typography>
        </Box>
      </Box>
    </Box>
  );
}

function HealthSignalRow({ label, state, tone, path }: {
  label: string;
  state: string;
  tone: StateIndicatorTone;
  path?: string;
}) {
  const icon = tone === 'success' ? 'check' : 'warning';
  return (
    <Box sx={{ display: 'grid', gridTemplateColumns: '24px minmax(0, 1fr) auto', gap: 1, alignItems: 'center', minHeight: 39, py: 0.7, borderBottom: '1px solid', borderColor: 'divider', '&:last-child': { borderBottom: 0 } }}>
      <Box sx={{ color: toneColor(tone) }}><LineIcon name={icon} size={19} /></Box>
      <Typography variant="body2" sx={{ minWidth: 0, fontWeight: 500 }}>{label}</Typography>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}>
        <Typography variant="caption" color="text.secondary" sx={{ whiteSpace: 'nowrap' }}>{state}</Typography>
        {path ? <Button variant="text" size="small" onClick={() => navigateConsole(path)} sx={{ minWidth: 0, px: 0.5 }}>View</Button> : null}
      </Box>
    </Box>
  );
}

function breakdownRows(breakdown: OverviewBreakdown, labels: BreakdownLabel[]): Array<{ key: string; label: string; value: number }> {
  const values = new Map(breakdown.items.map((item) => [item.key, item.value]));
  const known = labels.map((item) => ({ ...item, value: values.get(item.key) ?? 0 }));
  const knownKeys = new Set(labels.map((item) => item.key));
  const additional = breakdown.items
    .filter((item) => !knownKeys.has(item.key))
    .map((item) => ({ key: item.key, label: humanize(item.key), value: item.value }));
  return [...known, ...additional];
}

function BreakdownSummary({ breakdown, total, totalLabel, labels, action, path }: {
  breakdown: OverviewBreakdown;
  total: OverviewMetric;
  totalLabel: string;
  labels: BreakdownLabel[];
  action: string;
  path: string;
}) {
  if (breakdown.status !== 'AVAILABLE') {
    return (
      <Box sx={{ minHeight: 176, display: 'grid', alignContent: 'center', gap: 1.5 }}>
        <StateIndicator label={statusLabel(breakdown.status)} tone={statusTone(breakdown.status)} />
        <Typography variant="caption" color="text.secondary">This governed breakdown is not available to the current actor.</Typography>
        <Button variant="text" size="small" onClick={() => navigateConsole(path)} sx={{ justifySelf: 'start', px: 0 }}>{action}</Button>
      </Box>
    );
  }

  const rows = breakdownRows(breakdown, labels);
  const fallbackTotal = rows.reduce((sum, row) => sum + row.value, 0);
  const totalValue = total.status === 'AVAILABLE' && total.value !== null ? total.value : fallbackTotal;

  return (
    <Box sx={{ minHeight: 176, display: 'grid', gridTemplateColumns: { xs: '1fr', sm: '112px minmax(0, 1fr)' }, gap: 2, alignItems: 'center' }}>
      <Box sx={{ alignSelf: 'stretch', display: 'grid', alignContent: 'center', p: 1.5, border: '1px solid', borderColor: 'divider', borderRadius: 1, bgcolor: 'action.hover' }}>
        <Typography sx={{ fontSize: 24, lineHeight: 1.2, fontWeight: 700 }}>{totalValue.toLocaleString()}</Typography>
        <Typography variant="caption" color="text.secondary">{totalLabel}</Typography>
      </Box>
      <Box sx={{ minWidth: 0, display: 'grid', gap: 1.15 }}>
        {rows.map((row) => {
          const share = totalValue > 0 ? Math.min(100, (row.value / totalValue) * 100) : 0;
          return (
            <Box key={row.key} sx={{ minWidth: 0 }}>
              <Box sx={{ display: 'flex', justifyContent: 'space-between', gap: 1, mb: 0.45 }}>
                <Typography variant="caption" sx={{ fontWeight: 600 }}>{row.label}</Typography>
                <Typography variant="caption" color="text.secondary">{row.value.toLocaleString()}</Typography>
              </Box>
              <Box sx={{ height: 6, borderRadius: 3, bgcolor: 'action.hover', overflow: 'hidden' }}>
                <Box sx={{ width: `${share}%`, minWidth: row.value > 0 ? 4 : 0, height: '100%', bgcolor: 'primary.main' }} />
              </Box>
            </Box>
          );
        })}
        <Button variant="text" size="small" onClick={() => navigateConsole(path)} sx={{ justifySelf: 'start', px: 0 }}>{action}</Button>
      </Box>
    </Box>
  );
}

function AccessBreakdownUnavailable({ total }: { total: OverviewMetric }) {
  const value = metricText(total);
  return (
    <Box sx={{ minHeight: 176, display: 'grid', alignContent: 'center', gap: 1.5 }}>
      <Box>
        {value !== null
          ? <Typography sx={{ fontSize: 24, fontWeight: 700 }}>{value}</Typography>
          : <StateIndicator label={statusLabel(total.status)} tone={statusTone(total.status)} />}
        <Typography variant="caption" color="text.secondary">Authoritative active AccessAssignments</Typography>
      </Box>
      <StateIndicator label="Per-application aggregation not exposed" tone="neutral" />
      <Typography variant="caption" color="text.secondary">
        AccessAssignment remains authoritative business intent and EffectiveAccess remains a projection. The Console does not approximate application totals from a truncated entitlement set.
      </Typography>
      <Button variant="text" size="small" onClick={() => navigateConsole('/access')} sx={{ justifySelf: 'start', px: 0 }}>View access</Button>
    </Box>
  );
}

function ownerContinuity(overview: IamOperationsOverview): { state: string; tone: StateIndicatorTone } {
  const owners = overview.tenantDashboard.tenantOwners;
  if (owners.status !== 'AVAILABLE' || owners.effectiveOwnerCount === null || owners.viableOwnerCount === null) {
    return { state: statusLabel(owners.status), tone: statusTone(owners.status) };
  }
  if (owners.viableOwnerCount === 0) {
    return {
      state: owners.effectiveOwnerCount === 0 ? 'No effective owner' : `0 / ${owners.effectiveOwnerCount} viable`,
      tone: 'danger',
    };
  }
  if (owners.viableOwnerCount < owners.effectiveOwnerCount) {
    return { state: `${owners.viableOwnerCount} / ${owners.effectiveOwnerCount} viable`, tone: 'warning' };
  }
  return { state: `${owners.viableOwnerCount} viable`, tone: 'success' };
}

function TenantOwnerDetails({ overview }: { overview: IamOperationsOverview }) {
  const owners = overview.tenantDashboard.tenantOwners;
  if (owners.status !== 'AVAILABLE' || owners.owners.length === 0) return null;
  return (
    <Box sx={{ pt: 1, mt: 0.5, borderTop: '1px solid', borderColor: 'divider', display: 'grid', gap: 0.6 }}>
      <Typography variant="caption" color="text.secondary">Effective Tenant Owners</Typography>
      {owners.owners.slice(0, 3).map((owner) => (
        <Box key={owner.identityId} sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 1 }}>
          <Typography variant="caption" sx={{ minWidth: 0, overflowWrap: 'anywhere' }}>{owner.displayName ?? shortId(owner.identityId)}</Typography>
          <StateIndicator label={owner.administrativelyEligible ? 'Eligible' : 'Not eligible'} tone={owner.administrativelyEligible ? 'success' : 'danger'} />
        </Box>
      ))}
      {owners.owners.length > 3 ? <Typography variant="caption" color="text.secondary">+{owners.owners.length - 3} more in Administration</Typography> : null}
    </Box>
  );
}

function auditOutcomeTone(outcome: string): StateIndicatorTone {
  if (outcome === 'SUCCESS') return 'success';
  if (outcome === 'FAILURE') return 'danger';
  return 'warning';
}

function RecentActivity({ overview }: { overview: IamOperationsOverview }) {
  const activity = overview.tenantDashboard.recentActivity;
  if (activity.status !== 'AVAILABLE') {
    return <StateIndicator label={statusLabel(activity.status)} tone={statusTone(activity.status)} />;
  }
  if (activity.items.length === 0) {
    return <StatePanel state="empty" title="No recent Audit activity">No Audit records were returned in the last 24 hours.</StatePanel>;
  }
  return (
    <DataTable ariaLabel="Recent Tenant Audit activity" minWidth={560}>
      <TableHead><TableRow><TableCell>Time</TableCell><TableCell>Actor</TableCell><TableCell>Event</TableCell><TableCell>Resource</TableCell></TableRow></TableHead>
      <TableBody>
        {activity.items.slice(0, 5).map((item) => (
          <DataTableRow key={item.id}>
            <TableCell>{formatTime(item.occurredAt)}</TableCell>
            <TableCell>{item.actorDisplayLabel ?? shortId(item.actorId) ?? '—'}</TableCell>
            <TableCell>
              <Typography variant="body2" sx={{ fontWeight: 500 }}>{humanize(item.actionType)}</Typography>
              <StateIndicator label={humanize(item.outcome)} tone={auditOutcomeTone(item.outcome)} />
            </TableCell>
            <TableCell>
              <Typography variant="body2" sx={{ fontWeight: 500 }}>{item.resourceDisplayLabel ?? humanize(item.resourceType)}</Typography>
              {item.resourceDisplayLabel ? <Typography variant="caption" color="text.secondary">{humanize(item.resourceType)}</Typography> : null}
            </TableCell>
          </DataTableRow>
        ))}
      </TableBody>
    </DataTable>
  );
}

function WorkItems({ overview }: { overview: IamOperationsOverview }) {
  if (overview.myWork.status !== 'AVAILABLE') {
    return <StateIndicator label={statusLabel(overview.myWork.status)} tone={statusTone(overview.myWork.status)} />;
  }
  if (overview.myWork.items.length === 0) {
    return <StatePanel state="empty" title="No assigned governance items">There are no pending approvals or reviews assigned to the current actor.</StatePanel>;
  }
  return (
    <DataTable ariaLabel="Open governance items" minWidth={680}>
      <TableHead><TableRow><TableCell>Type</TableCell><TableCell>Subject</TableCell><TableCell>Status</TableCell><TableCell>Created At</TableCell><TableCell align="right">Action</TableCell></TableRow></TableHead>
      <TableBody>
        {overview.myWork.items.slice(0, 5).map((item) => (
          <DataTableRow key={`${item.kind}-${item.id}`}>
            <TableCell>{item.kind === 'APPROVAL' ? 'Approval' : 'Review'}</TableCell>
            <TableCell><Typography variant="body2" sx={{ fontWeight: 500 }}>{humanize(item.subjectKind)}</Typography><Typography variant="caption" color="text.secondary">{shortId(item.subjectId)}</Typography></TableCell>
            <TableCell><StateIndicator label={item.kind === 'APPROVAL' ? `Stage ${item.stageOrdinal + 1}` : 'Pending'} tone="warning" /></TableCell>
            <TableCell>{formatDateTime(item.createdAt)}</TableCell>
            <TableCell align="right"><Button variant="text" size="small" onClick={() => navigateConsole(item.kind === 'APPROVAL' ? `/requests?view=approvals&approvalCaseId=${encodeURIComponent(item.id)}` : `/reviews?reviewItemId=${encodeURIComponent(item.id)}`)}>Open</Button></TableCell>
          </DataTableRow>
        ))}
      </TableBody>
    </DataTable>
  );
}

function sourceConnectorState(source: OverviewSourceSystem): { label: string; tone: StateIndicatorTone } {
  const operational = source.operational;
  if (operational.status !== 'AVAILABLE') return { label: statusLabel(operational.status), tone: statusTone(operational.status) };
  if ((operational.activeConnectorCount ?? 0) > 0) {
    return { label: `${operational.activeConnectorCount} active`, tone: 'success' };
  }
  if ((operational.activeBindingCount ?? 0) > 0) return { label: 'No active connector', tone: 'warning' };
  return { label: 'No active binding', tone: 'neutral' };
}

function sourceReconciliationState(source: OverviewSourceSystem): { label: string; tone: StateIndicatorTone } {
  const operational = source.operational;
  if (operational.status !== 'AVAILABLE') return { label: statusLabel(operational.status), tone: statusTone(operational.status) };
  const failures = operational.failedReconciliationsInWindow ?? 0;
  return failures > 0
    ? { label: `${failures} failed · 24h`, tone: 'danger' }
    : { label: 'No failures · 24h', tone: 'success' };
}

function IdentitySourcesSummary({ overview }: { overview: IamOperationsOverview }) {
  const sources = overview.tenantDashboard.sources;
  if (sources.status !== 'AVAILABLE') {
    return <StateIndicator label={statusLabel(sources.status)} tone={statusTone(sources.status)} />;
  }
  if (sources.items.length === 0) {
    return <StatePanel state="empty" title="No Identity sources">No SourceSystem records are configured for this Tenant.</StatePanel>;
  }
  return (
    <>
      <DataTable ariaLabel="Identity source operational summary" minWidth={720}>
        <TableHead><TableRow><TableCell>Source</TableCell><TableCell align="right">Linked identities</TableCell><TableCell>Connector posture</TableCell><TableCell>Reconciliation</TableCell><TableCell>Last complete</TableCell></TableRow></TableHead>
        <TableBody>
          {sources.items.map((source) => {
            const connector = sourceConnectorState(source);
            const reconciliation = sourceReconciliationState(source);
            return (
              <DataTableRow key={source.id}>
                <TableCell><Typography variant="body2" sx={{ fontWeight: 500 }}>{source.name}</Typography><Typography variant="caption" color="text.secondary">{source.code}</Typography></TableCell>
                <TableCell align="right">{source.linkedIdentityCount.toLocaleString()}</TableCell>
                <TableCell><StateIndicator label={connector.label} tone={connector.tone} /></TableCell>
                <TableCell><StateIndicator label={reconciliation.label} tone={reconciliation.tone} /></TableCell>
                <TableCell>{source.operational.status === 'AVAILABLE' ? formatDateTime(source.operational.lastCompleteAt) : '—'}</TableCell>
              </DataTableRow>
            );
          })}
        </TableBody>
      </DataTable>
      {sources.totalCount !== null && sources.totalCount > sources.items.length
        ? <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>Showing {sources.items.length} of {sources.totalCount.toLocaleString()} configured sources.</Typography>
        : null}
    </>
  );
}

export function TenantOverviewPage({ authority }: Props) {
  const [tenant, setTenant] = useState<LoadState<TenantContext>>({ status: 'idle' });
  const [overview, setOverview] = useState<LoadState<IamOperationsOverview>>({ status: 'idle' });
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    if (!authority) {
      setTenant({ status: 'idle' });
      setOverview({ status: 'idle' });
      return;
    }
    const controller = new AbortController();
    setTenant({ status: 'loading' });
    setOverview({ status: 'loading' });
    getTenantContext(controller.signal)
      .then((value) => setTenant({ status: 'ready', value }))
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return;
        setTenant({ status: 'error', error });
      });
    getIamOperationsOverview('24h', 'ALL', 'OLDEST', controller.signal)
      .then((value) => setOverview({ status: 'ready', value }))
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return;
        setOverview({ status: 'error', error });
      });
    return () => controller.abort();
  }, [authority?.tenantId, reloadKey]);

  const governanceIssues = useMemo(() => overview.status === 'ready'
    ? sumMetrics(overview.value.attention.pendingApprovals, overview.value.attention.pendingReviews)
    : undefined, [overview]);

  const sourceCount = useMemo(() => overview.status === 'ready'
    ? metricFromCount(overview.value.tenantDashboard.sources.status, overview.value.tenantDashboard.sources.totalCount)
    : undefined, [overview]);

  const tenantName = tenant.status === 'ready' ? tenant.value.displayName : 'Current Tenant';
  const initial = tenantName.trim().slice(0, 1).toUpperCase() || 'T';
  const loading = tenant.status === 'loading' || overview.status === 'loading';

  return (
    <Page width="wide" aria-labelledby="tenant-page-title" sx={{ gap: 0 }}>
      <Box component="nav" aria-label="Breadcrumb" sx={{ display: 'flex', alignItems: 'center', gap: 0.8, color: 'text.secondary', mb: 1.25 }}>
        <Typography variant="caption">Tenants</Typography><Typography variant="caption">›</Typography><Typography variant="caption">{tenantName}</Typography><Typography variant="caption">›</Typography><Typography variant="caption" color="text.primary">Overview</Typography>
      </Box>

      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: '64px minmax(0, 1fr) auto' }, gap: 2, alignItems: 'center', mb: 2.25 }}>
        <Box sx={{ width: 56, height: 56, borderRadius: '50%', bgcolor: 'primary.main', color: 'primary.contrastText', display: 'grid', placeItems: 'center', fontSize: 24, fontWeight: 500 }}>{initial}</Box>
        <Box sx={{ minWidth: 0 }}>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
            <Typography component="h1" id="tenant-page-title" sx={{ fontSize: 26, lineHeight: 1.15, fontWeight: 700 }}>{tenantName}</Typography>
          </Box>
          <Typography variant="body2" color="text.secondary" sx={{ mt: 0.45 }}>{tenant.status === 'ready' ? `Tenant ID: ${tenant.value.id}` : authority ? `Tenant ID: ${authority.tenantId}` : 'Resolving Tenant context'}</Typography>
          {tenant.status === 'ready' ? <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.35 }}>Revision {tenant.value.revision} · Created {formatDateTime(tenant.value.createdAt)}</Typography> : null}
        </Box>
        <Button
          variant="outlined"
          disabled
          title="No Tenant mutation actions are exposed by the current Console contract."
          endIcon={<Typography component="span" aria-hidden="true" sx={{ fontSize: 14 }}>⌄</Typography>}
          sx={{ justifySelf: { xs: 'start', sm: 'end' } }}
        >Tenant Actions</Button>
      </Box>

      <Box component="nav" aria-label="Tenant sections" sx={{ display: 'flex', gap: 0.2, overflowX: 'auto', borderBottom: '1px solid', borderColor: 'divider', mb: 1.75 }}>
        {tenantTabs.map((tab) => (
          <Button
            key={tab.label}
            variant="text"
            size="small"
            disabled={!tab.path}
            title={tab.disabledReason}
            startIcon={<LineIcon name={tab.icon} size={16} />}
            onClick={tab.path ? () => navigateConsole(tab.path) : undefined}
            sx={{
              flex: '0 0 auto',
              minHeight: 42,
              px: 1.15,
              borderRadius: 0,
              borderBottom: '2px solid',
              borderBottomColor: tab.path === '/tenant' ? 'primary.main' : 'transparent',
              color: tab.path === '/tenant' ? 'primary.main' : 'text.secondary',
              fontWeight: tab.path === '/tenant' ? 700 : 500,
              fontSize: 12,
            }}
          >{tab.label}</Button>
        ))}
      </Box>

      {!authority || loading ? <StatePanel state="loading" title="Loading Tenant overview">Reading canonical Tenant metadata and tenant-scoped IAM projections.</StatePanel> : null}
      {tenant.status === 'error' ? <StatePanel state="error" title="Tenant information is unavailable" action={<Button variant="outlined" onClick={() => setReloadKey((value) => value + 1)}>Retry</Button>}>{errorMessage(tenant.error, 'Wyrmgate could not read the current Tenant metadata.')}</StatePanel> : null}
      {overview.status === 'error' ? <StatePanel state="error" title="Tenant operations are unavailable" action={<Button variant="outlined" onClick={() => setReloadKey((value) => value + 1)}>Retry</Button>}>{errorMessage(overview.error, 'Wyrmgate could not read the Tenant operations projection.')}</StatePanel> : null}

      {overview.status === 'ready' ? (() => {
        const continuity = ownerContinuity(overview.value);
        return (
          <Box sx={{ display: 'grid', gap: 1.5 }}>
            <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, minmax(0, 1fr))', lg: 'repeat(6, minmax(0, 1fr))' }, gap: 1.5 }}>
              <SummaryTile label="Identities" icon="identities" metric={overview.value.environment.activeIdentities} detail="Active governed subjects" path="/identities" tone="info" />
              <SummaryTile label="Applications" icon="applications" metric={overview.value.environment.applications} detail="Active tenant-bound Catalog applications" path="/catalog" tone="info" />
              <SummaryTile label="Active Access" icon="access" metric={overview.value.environment.activeAccessAssignments} detail="Authoritative AccessAssignments" path="/access" tone="warning" />
              <SummaryTile label="Governance Issues" icon="warning" metric={governanceIssues} detail="Pending approvals + reviews" path="/requests" tone="danger" />
              <SummaryTile label="Sources" icon="sources" metric={sourceCount} detail="Configured Identity SourceSystems" path="/integrations" tone="info" />
              <SummaryTile label="Connectors" icon="connectors" metric={overview.value.environment.activeConnectors} detail="Active connector instances" path="/integrations" tone="info" />
            </Box>

            <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', lg: '1.15fr 1fr 1fr' }, gap: 1.5 }}>
              <Surface
                title="Tenant Information"
                actions={<Button variant="outlined" size="small" disabled startIcon={<LineIcon name="edit" size={15} />} title="Tenant mutation is not exposed by the current Console contract.">Edit</Button>}
                sx={{ height: '100%', p: 2 }}
              >
                {tenant.status === 'ready' ? <Box component="dl" sx={{ m: 0 }}>
                  <Definition term="Name">{tenant.value.displayName}</Definition>
                  <Definition term="Tenant ID">{tenant.value.id}</Definition>
                  <Definition term="Created At">{formatDateTime(tenant.value.createdAt)}</Definition>
                  <Definition term="Updated At">{formatDateTime(tenant.value.updatedAt)}</Definition>
                  <Definition term="Revision">{tenant.value.revision}</Definition>
                </Box> : <StateIndicator label="Unavailable" tone="danger" />}
              </Surface>

              <Surface
                title="Tenant Health"
                aria-label="Tenant Health — explicit operational and owner-continuity signals, not a synthetic health score"
                actions={<Button variant="text" size="small" onClick={() => navigateConsole('/administration')}>View Details</Button>}
                sx={{ height: '100%', p: 2 }}
              >
                <HealthSignalRow label="Tenant Owner continuity" state={continuity.state} tone={continuity.tone} path="/administration" />
                <HealthSignalRow label="Administrative actor eligible" state={authority?.administrativelyEligible ? 'Eligible' : 'Not eligible'} tone={authority?.administrativelyEligible ? 'success' : 'danger'} path="/administration" />
                <HealthSignalRow label="Pending approvals" state={metricText(overview.value.attention.pendingApprovals) ?? statusLabel(overview.value.attention.pendingApprovals.status)} tone={overview.value.attention.pendingApprovals.status === 'AVAILABLE' && (overview.value.attention.pendingApprovals.value ?? 0) === 0 ? 'success' : 'warning'} path="/requests?view=approvals" />
                <HealthSignalRow label="Pending reviews" state={metricText(overview.value.attention.pendingReviews) ?? statusLabel(overview.value.attention.pendingReviews.status)} tone={overview.value.attention.pendingReviews.status === 'AVAILABLE' && (overview.value.attention.pendingReviews.value ?? 0) === 0 ? 'success' : 'warning'} path="/reviews" />
                <HealthSignalRow label="Provisioning failures" state={metricText(overview.value.attention.finalProvisioningFailures) ?? statusLabel(overview.value.attention.finalProvisioningFailures.status)} tone={overview.value.attention.finalProvisioningFailures.status === 'AVAILABLE' && (overview.value.attention.finalProvisioningFailures.value ?? 0) === 0 ? 'success' : 'warning'} path="/integrations" />
                <HealthSignalRow label="Failed reconciliations" state={metricText(overview.value.attention.failedReconciliations) ?? statusLabel(overview.value.attention.failedReconciliations.status)} tone={overview.value.attention.failedReconciliations.status === 'AVAILABLE' && (overview.value.attention.failedReconciliations.value ?? 0) === 0 ? 'success' : 'warning'} path="/integrations" />
                <TenantOwnerDetails overview={overview.value} />
              </Surface>

              <Surface
                title="Recent Activity"
                aria-label="Recent Audit activity"
                actions={<Button variant="text" size="small" onClick={() => navigateConsole('/audit')}>View All</Button>}
                sx={{ height: '100%', p: 2 }}
              >
                <RecentActivity overview={overview.value} />
              </Surface>
            </Box>

            <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', lg: 'repeat(3, minmax(0, 1fr))' }, gap: 1.5 }}>
              <Surface title="Identities by Type" sx={{ height: '100%', p: 2 }}>
                <BreakdownSummary breakdown={overview.value.tenantDashboard.identityTypes} total={overview.value.environment.activeIdentities} totalLabel="Active identities" labels={identityTypeLabels} action="View identities" path="/identities" />
              </Surface>
              <Surface title="Applications by Origin" sx={{ height: '100%', p: 2 }}>
                <BreakdownSummary breakdown={overview.value.tenantDashboard.applicationOrigins} total={overview.value.environment.applications} totalLabel="Active applications" labels={applicationOriginLabels} action="View applications" path="/catalog" />
              </Surface>
              <Surface title="Active Access by Application" actions={<Button variant="text" size="small" onClick={() => navigateConsole('/access')}>View All</Button>} sx={{ height: '100%', p: 2 }}>
                <AccessBreakdownUnavailable total={overview.value.environment.activeAccessAssignments} />
              </Surface>
            </Box>

            <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', lg: '1.35fr 1fr' }, gap: 1.5 }}>
              <Surface title="Open Governance Items" actions={<Button variant="text" size="small" onClick={() => navigateConsole('/requests')}>View All</Button>} sx={{ height: '100%', p: 2 }}>
                <WorkItems overview={overview.value} />
              </Surface>
              <Surface title="Identity Sources" actions={<Button variant="text" size="small" onClick={() => navigateConsole('/integrations')}>View All</Button>} sx={{ height: '100%', p: 2 }}>
                <IdentitySourcesSummary overview={overview.value} />
              </Surface>
            </Box>
          </Box>
        );
      })() : null}
    </Page>
  );
}
