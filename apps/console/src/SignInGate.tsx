import { useEffect, useState, type FormEvent } from 'react';
import { App } from './App';
import { ShellChrome } from './ShellChrome';
import { Alert, Button, CircularProgress, TextInput } from './ui';
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
    return (
      <main className="auth-loading" aria-live="polite">
        <div className="auth-loading-card">
          <CircularProgress size={20} thickness={5} aria-hidden="true" />
          <span>Establishing secure session context…</span>
        </div>
      </main>
    );
  }

  if (state === 'first-party-login') {
    return (
      <main className="auth-shell">
        <section className="auth-brand-panel" aria-label="Wyrmgate IAM">
          <div className="auth-wordmark">
            <span className="auth-mark" aria-hidden="true">W</span>
            <span className="auth-wordmark-copy">
              <strong>Wyrmgate IAM</strong>
              <span>Identity governance and access management</span>
            </span>
          </div>

          <div className="auth-brand-copy">
            <p className="eyebrow">Enterprise identity control plane</p>
            <h1>Govern identity, access, and administration from one trusted plane.</h1>
            <p>Wyrmgate brings governed identities, first-party authentication, access intent, lifecycle policy, integrations, and audit evidence into a single security boundary.</p>
          </div>

          <div className="auth-trust-row" aria-label="Security characteristics">
            <span>Server-side tenant routing</span>
            <span>Governed administrative authority</span>
            <span>No browser bearer-token storage</span>
          </div>
        </section>

        <section className="auth-form-panel">
          <div className="auth-form-wrap">
            <header>
              <p className="eyebrow">Secure access</p>
              <h2>Sign in to Wyrmgate</h2>
              <p>Use your governed Wyrmgate principal credentials to continue.</p>
            </header>

            {error && <Alert severity="error" role="alert" className="auth-error">{error}</Alert>}

            <form className="auth-form" onSubmit={(event) => void submit(event)}>
              <TextInput
                label="Username"
                autoComplete="username"
                autoFocus
                required
                value={principalKey}
                onChange={(event) => setPrincipalKey(event.target.value)}
              />
              <TextInput
                label="Password"
                type="password"
                autoComplete="current-password"
                required
                value={password}
                onChange={(event) => setPassword(event.target.value)}
              />
              <Button type="submit" variant="contained" disabled={busy || !loginRoute}>
                {busy ? 'Signing in…' : 'Sign in'}
              </Button>
            </form>

            <p className="auth-security-note">Tenant routing and administrative authority are resolved by the server. Authentication secrets are not written to browser storage.</p>
          </div>
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
