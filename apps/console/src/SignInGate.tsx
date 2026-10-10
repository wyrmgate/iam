import { useEffect, useState, type FormEvent } from 'react';
import { App } from './App';
import { ApiError, getAuthSession, signIn, signOut } from './api';

type GateState = 'checking' | 'first-party-login' | 'authenticated' | 'external-mode';

export function SignInGate() {
  const [state, setState] = useState<GateState>('checking');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [clientId, setClientId] = useState('');
  const [applicationTargetId, setApplicationTargetId] = useState('');
  const [principalKey, setPrincipalKey] = useState('');
  const [password, setPassword] = useState('');

  useEffect(() => {
    const controller = new AbortController();
    getAuthSession(controller.signal)
      .then((session) => setState(session.authenticated ? 'authenticated' : 'first-party-login'))
      .catch((cause: unknown) => {
        if (cause instanceof DOMException && cause.name === 'AbortError') return;
        if (cause instanceof ApiError && (cause.status === 403 || cause.status === 404)) {
          setState('external-mode');
          return;
        }
        setError('Unable to determine authentication state.');
        setState('first-party-login');
      });
    return () => controller.abort();
  }, []);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const session = await signIn({ clientId, applicationTargetId, principalKey, password });
      setPassword('');
      if (!session.authenticated) throw new Error('Authentication did not establish a session');
      setState('authenticated');
    } catch (cause) {
      setPassword('');
      setError(cause instanceof ApiError && cause.status === 401
        ? 'Sign-in failed. Check the supplied sign-in route and credentials.'
        : 'Sign-in is temporarily unavailable.');
    } finally {
      setBusy(false);
    }
  }

  async function logout() {
    setBusy(true);
    try {
      await signOut();
      setState('first-party-login');
    } finally {
      setBusy(false);
    }
  }

  if (state === 'checking') {
    return <div className="state-card">Checking authentication…</div>;
  }

  if (state === 'external-mode') return <App />;

  if (state === 'first-party-login') {
    return (
      <main className="content" style={{ maxWidth: 720, margin: '10vh auto' }}>
        <section className="page-stack">
          <header className="page-header">
            <p className="eyebrow">Wyrmgate IAM</p>
            <h1>Sign in</h1>
            <p>Tenant and administrative authority are resolved server-side. The browser stores no bearer or refresh token.</p>
          </header>
          {error && <div className="state-card state-error" role="alert">{error}</div>}
          <form className="card form-grid" onSubmit={(event) => void submit(event)}>
            <label><span>SSO client ID</span><input autoComplete="off" required value={clientId} onChange={(event) => setClientId(event.target.value)} /></label>
            <label><span>Authentication target ID</span><input autoComplete="off" required value={applicationTargetId} onChange={(event) => setApplicationTargetId(event.target.value)} /></label>
            <label><span>Principal key</span><input autoComplete="username" required value={principalKey} onChange={(event) => setPrincipalKey(event.target.value)} /></label>
            <label><span>Password</span><input type="password" autoComplete="current-password" required value={password} onChange={(event) => setPassword(event.target.value)} /></label>
            <button className="button" disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button>
          </form>
        </section>
      </main>
    );
  }

  return (
    <>
      <button
        className="button secondary"
        style={{ position: 'fixed', right: 20, top: 16, zIndex: 20 }}
        disabled={busy}
        onClick={() => void logout()}
      >Sign out</button>
      <App />
    </>
  );
}
