import { useEffect, useState } from 'react';
import { ApiError } from './api';
import { listPlatformTenants, type PlatformTenantResource } from './platformTenantsApi';
import {
  Button,
  DataTable,
  DataTableActionCell,
  DataTableRow,
  Page,
  PageHeader,
  StatePanel,
  Surface,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from './ui';

type Props = {
  onSelect: (tenantId: string) => void;
};

type LoadState =
  | { status: 'loading' }
  | { status: 'ready'; items: PlatformTenantResource[]; nextCursor: string | null }
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
      title: 'Platform Tenant inventory is not authorized',
      body: 'A current viable Platform Owner is required. Tenant-scoped administration does not grant deployment-wide Tenant visibility.',
    };
  }
  return {
    title: 'Platform Tenant inventory is unavailable',
    body: error instanceof Error ? error.message : 'Wyrmgate could not read the deployment Tenant inventory.',
  };
}

export function PlatformTenantsPage({ onSelect }: Props) {
  const [state, setState] = useState<LoadState>({ status: 'loading' });
  const [loadingMore, setLoadingMore] = useState(false);
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setState({ status: 'loading' });
    listPlatformTenants(null, controller.signal)
      .then((page) => setState({ status: 'ready', items: page.items, nextCursor: page.nextCursor ?? null }))
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return;
        setState({ status: 'error', error });
      });
    return () => controller.abort();
  }, [reloadKey]);

  async function loadMore() {
    if (state.status !== 'ready' || !state.nextCursor || loadingMore) return;
    setLoadingMore(true);
    try {
      const page = await listPlatformTenants(state.nextCursor);
      setState({
        status: 'ready',
        items: [...state.items, ...page.items],
        nextCursor: page.nextCursor ?? null,
      });
    } catch (error: unknown) {
      setState({ status: 'error', error });
    } finally {
      setLoadingMore(false);
    }
  }

  return (
    <Page width="wide">
      <PageHeader
        eyebrow="Platform"
        title="Tenants"
        description="Deployment-wide Tenant inventory. This surface is read-only and requires current viable Platform Owner authority."
      />

      {state.status === 'loading' ? (
        <StatePanel state="loading" title="Loading Tenant inventory">
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

      {state.status === 'ready' && state.items.length === 0 ? (
        <StatePanel state="empty" title="No Tenants found">
          The deployment did not return any Tenant records.
        </StatePanel>
      ) : null}

      {state.status === 'ready' && state.items.length > 0 ? (
        <Surface title="Tenant inventory" aria-label="Platform Tenant inventory" sx={{ overflow: 'hidden' }}>
          <DataTable ariaLabel="Platform Tenant inventory" minWidth={940}>
            <TableHead>
              <TableRow>
                <TableCell>Tenant</TableCell>
                <TableCell>Tenant ID</TableCell>
                <TableCell align="right">Revision</TableCell>
                <TableCell>Created</TableCell>
                <TableCell>Updated</TableCell>
                <TableCell align="right">Action</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {state.items.map((tenant) => (
                <DataTableRow key={tenant.id} interactive>
                  <TableCell>
                    <Button
                      variant="text"
                      size="small"
                      onClick={() => onSelect(tenant.id)}
                      sx={{ p: 0, minHeight: 0, justifyContent: 'flex-start', textAlign: 'left' }}
                    >
                      <Typography component="span" variant="body2" sx={{ color: 'text.primary', fontWeight: 600 }}>
                        {tenant.displayName}
                      </Typography>
                    </Button>
                  </TableCell>
                  <TableCell>
                    <Typography variant="caption" sx={{ fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', overflowWrap: 'anywhere' }}>
                      {tenant.id}
                    </Typography>
                  </TableCell>
                  <TableCell align="right">{tenant.revision}</TableCell>
                  <TableCell><time dateTime={tenant.createdAt}>{formatDateTime(tenant.createdAt)}</time></TableCell>
                  <TableCell><time dateTime={tenant.updatedAt}>{formatDateTime(tenant.updatedAt)}</time></TableCell>
                  <DataTableActionCell>
                    <Button variant="outlined" size="small" onClick={() => onSelect(tenant.id)}>View</Button>
                  </DataTableActionCell>
                </DataTableRow>
              ))}
            </TableBody>
          </DataTable>
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block', pt: 1.25 }}>
            Showing {state.items.length.toLocaleString()} Tenant{state.items.length === 1 ? '' : 's'}. Tenant lifecycle/status, code and description are not part of the current canonical Tenant contract.
          </Typography>
          {state.nextCursor ? (
            <Button
              variant="text"
              size="small"
              disabled={loadingMore}
              onClick={() => void loadMore()}
              sx={{ mt: 1 }}
            >
              {loadingMore ? 'Loading…' : 'Load more'}
            </Button>
          ) : null}
        </Surface>
      ) : null}
    </Page>
  );
}
