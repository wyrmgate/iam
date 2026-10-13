import { useEffect, useState } from 'react';
import {
  ApiError,
  getCurrentAdministrativeAuthority,
  type CurrentAdministrativeAuthority,
  type EffectiveAdministrativeAuthority,
} from './api';
import {
  Button,
  DataTable,
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

type State =
  | { status: 'loading' }
  | { status: 'ready'; value: CurrentAdministrativeAuthority }
  | { status: 'error'; error: unknown };

function scopeLabel(authority: EffectiveAdministrativeAuthority): string {
  const scope = authority.scope;
  if (scope.type === 'GLOBAL') return 'Global';
  if (scope.type === 'CANONICAL_ATTRIBUTE_CLASSIFICATION') return `Classification: ${scope.scopeKey ?? '—'}`;
  return `${scope.resourceType ?? 'resource'} · ${scope.resourceId ?? '—'}`;
}

function ErrorState({ error, retry }: { error: unknown; retry: () => void }) {
  const text = error instanceof ApiError
    ? `Request failed (${error.status}). Server authorization remains final.`
    : 'Unable to load current administrative authority.';
  return (
    <StatePanel
      severity="error"
      title="Authority unavailable"
      action={<Button variant="outlined" size="small" onClick={retry}>Retry</Button>}
    >
      {text}
    </StatePanel>
  );
}

export function CurrentAdministrativeAuthorityPage() {
  const [reload, setReload] = useState(0);
  const [state, setState] = useState<State>({ status: 'loading' });

  useEffect(() => {
    const controller = new AbortController();
    setState({ status: 'loading' });
    getCurrentAdministrativeAuthority(controller.signal)
      .then((value) => setState({ status: 'ready', value }))
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return;
        setState({ status: 'error', error });
      });
    return () => controller.abort();
  }, [reload]);

  return (
    <Page>
      <PageHeader
        eyebrow="Administration · derived projection"
        title="My Administrative Authority"
        description="Backend-derived point-in-time authority for console optimization. Navigation visibility is not proof of authorization; each operation is re-authorized by the server."
        actions={state.status === 'ready' ? (
          <Button variant="outlined" onClick={() => setReload((value) => value + 1)}>Refresh authority</Button>
        ) : undefined}
      />

      {state.status === 'loading' && <StatePanel>Loading current Administration authority…</StatePanel>}
      {state.status === 'error' && <ErrorState error={state.error} retry={() => setReload((value) => value + 1)} />}

      {state.status === 'ready' && (
        <>
          <Surface title="Current authority context" description="Server-derived actor and tenant context for this request moment.">
            <dl className="detail-grid">
              <div><dt>Tenant</dt><dd className="mono">{state.value.tenantId}</dd></div>
              <div><dt>Governed actor</dt><dd className="mono">{state.value.actorIdentityId}</dd></div>
              <div><dt>Administrative eligibility</dt><dd>{state.value.administrativelyEligible ? 'Eligible' : 'Not eligible'}</dd></div>
              <div><dt>Evaluated</dt><dd>{new Date(state.value.evaluatedAt).toLocaleString()}</dd></div>
            </dl>
          </Surface>

          {!state.value.administrativelyEligible && (
            <StatePanel title="No current administrative authority">
              The governed actor is not currently administratively eligible. Stale grants or token claims do not restore authority.
            </StatePanel>
          )}

          {state.value.administrativelyEligible && state.value.authorities.length === 0 && (
            <StatePanel title="No effective supported authority">
              No current GLOBAL, SPECIFIC_RESOURCE or classification-scoped Administration authority is effective.
            </StatePanel>
          )}

          {state.value.authorities.length > 0 && (
            <Surface
              title="Effective authority sources"
              description="This projection is informational for the Console. Operation-time server authorization remains final."
            >
              <DataTable minWidth={860}>
                <TableHead>
                  <TableRow>
                    <TableCell>Permission</TableCell>
                    <TableCell>Scope</TableCell>
                    <TableCell>Source</TableCell>
                    <TableCell>Valid until</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {state.value.authorities.map((authority) => (
                    <TableRow key={`${authority.source}:${authority.sourceId}:${authority.permission.key}:${scopeLabel(authority)}`} hover>
                      <TableCell><Typography variant="body2" sx={{ fontWeight: 700 }}>{authority.permission.key}</Typography></TableCell>
                      <TableCell>{scopeLabel(authority)}</TableCell>
                      <TableCell>{authority.source.replaceAll('_', ' ')}</TableCell>
                      <TableCell>{authority.validUntil ? new Date(authority.validUntil).toLocaleString() : 'No explicit expiry'}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </DataTable>
            </Surface>
          )}
        </>
      )}
    </Page>
  );
}
