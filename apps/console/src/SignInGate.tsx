import { useEffect, useState, type FormEvent } from 'react';
import { App } from './App';
import { ShellChrome } from './ShellChrome';
import { ApiError, getAuthSession, getLoginRoute, signIn, signOut, type LoginRoute } from './api';

type GateState = 'checking' | 'first-party-login' | 'authenticated' | 'external-mode';

export function SignInGate() {
  const [state, setState] = useState<GateState>('checking');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [loginRoute, setLoginRoute] = useState<LoginRoute | null>(null);
  const [principalKey, setPrincipalKey] = useState('');
  const [password, setPassword] = useState('');

  useEffect(() => {
    const controller = new AbortController();
    getAuthSession(controller.signal)
      .then(async (session) => {
        if (session.authenticated) {
          setState('authenticated');
          return;
        }
        const route = await getLoginRoute(controller.signal);
        setLoginRoute(route);
        setState('first-party-login');
      })
      .catch((cause: unknown) => {
        if (cause instanceof DOMException && cause.name === 'AbortError') return;
        if (cause instanceof ApiError && (cause.status === 403 || cause.status === 404)) {
          setState('external-mode');
          return;
        }
        setError('Sign-in is unavailable until Wyrmgate installation and Console routing are ready.');
        setState('first-party-login');
      });
    return () => controller.abort();
  }, []);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!loginRoute) {
      setError('Sign-in routing is unavailable.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const session = await signIn({ ...loginRoute, principalKey, password });
      setPassword('');
      if (!session.authenticated) throw new Error('Authentication did not establish a session');
      setState('authenticated');
    } catch (cause) {
      setPassword('');
      setError(cause instanceof ApiError && cause.status === 401
        ? 'Sign-in failed. Check your username and password.'
        : 'Sign-in is temporarily unavailable.');
    } finally {
      setBusy(false);
    }
  }

  async function logout() {
    setBusy(true);
    try {
      await signOut();
      const route = await getLoginRoute();
      setLoginRoute(route);
      setState('first-party-login');
    } finally {
      setBusy(false);
    }
  }

  if (state === 'checking') {
    return <div className="state-card">Checking authentication…</div>;
  }

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
            <label><span>Username</span><input autoComplete="username" required value={principalKey} onChange={(event) => setPrincipalKey(event.target.value)} /></label>
            <label><span>Password</span><input type="password" autoComplete="current-password" required value={password} onChange={(event) => setPassword(event.target.value)} /></label>
            <button className="button" disabled={busy || !loginRoute}>{busy ? 'Signing in…' : 'Sign in'}</button>
          </form>
        </section>
      </main>
    );
  }

  if (state === 'external-mode') {
    return <ShellChrome><App /></ShellChrome>;
  }

  return (
    <ShellChrome onSignOut={logout} signOutBusy={busy}>
      <App />
    </ShellChrome>
  );
}
