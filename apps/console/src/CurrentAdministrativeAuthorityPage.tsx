import { useEffect, useState } from 'react';
import {
  ApiError,
  getCurrentAdministrativeAuthority,
  type CurrentAdministrativeAuthority,
  type EffectiveAdministrativeAuthority,
} from './api';

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
  return <div className="state-card state-error" role="alert"><strong>Authority unavailable</strong><span>{text}</span><button className="button secondary" onClick={retry}>Retry</button></div>;
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

  return <section className="page-stack">
    <header className="page-header">
      <p className="eyebrow">Administration · derived projection</p>
      <h1>My Administrative Authority</h1>
      <p>Backend-derived point-in-time authority for console optimization. Navigation visibility is not proof of authorization; each operation is re-authorized by the server.</p>
    </header>
    {state.status === 'loading' && <div className="state-card">Loading current Administration authority…</div>}
    {state.status === 'error' && <ErrorState error={state.error} retry={() => setReload((value) => value + 1)} />}
    {state.status === 'ready' && <>
      <dl className="detail-grid card">
        <div><dt>Tenant</dt><dd className="mono">{state.value.tenantId}</dd></div>
        <div><dt>Governed actor</dt><dd className="mono">{state.value.actorIdentityId}</dd></div>
        <div><dt>Administrative eligibility</dt><dd>{state.value.administrativelyEligible ? 'Eligible' : 'Not eligible'}</dd></div>
        <div><dt>Evaluated</dt><dd>{new Date(state.value.evaluatedAt).toLocaleString()}</dd></div>
      </dl>
      {!state.value.administrativelyEligible && <div className="state-card"><strong>No current administrative authority</strong><span>The governed actor is not currently administratively eligible. Stale grants or token claims do not restore authority.</span></div>}
      {state.value.administrativelyEligible && state.value.authorities.length === 0 && <div className="state-card"><strong>No effective supported authority</strong><span>No current GLOBAL, SPECIFIC_RESOURCE or classification-scoped Administration authority is effective.</span></div>}
      {state.value.authorities.length > 0 && <div className="table-wrap"><table><thead><tr><th>Permission</th><th>Scope</th><th>Source</th><th>Valid until</th></tr></thead><tbody>{state.value.authorities.map((authority) => <tr key={`${authority.source}:${authority.sourceId}:${authority.permission.key}:${scopeLabel(authority)}`}><td><strong>{authority.permission.key}</strong></td><td>{scopeLabel(authority)}</td><td>{authority.source.replaceAll('_', ' ')}</td><td>{authority.validUntil ? new Date(authority.validUntil).toLocaleString() : 'No explicit expiry'}</td></tr>)}</tbody></table></div>}
      <div className="action-row"><button className="button secondary" onClick={() => setReload((value) => value + 1)}>Refresh authority</button></div>
    </>}
  </section>;
}
