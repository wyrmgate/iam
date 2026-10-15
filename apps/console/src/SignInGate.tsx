import { useEffect, useState, type FormEvent } from 'react';
import { App } from './App';
import { AuthIcon } from './AuthIcon';
import { ShellChrome } from './ShellChrome';
import { SetupWizard } from './SetupWizard';
import { Alert, Button, CircularProgress, TextInput } from './ui';
import { ApiError, getAuthSession, getLoginRoute, signIn, signOut, type LoginRoute } from './api';
import { getInstallationStatus, type InstallationStatus } from './installationApi';

type GateState = 'checking' | 'setup' | 'first-party-login' | 'authenticated' | 'external-mode';

export function SignInGate() {
  const [state, setState] = useState<GateState>('checking');
  const [setupStatus, setSetupStatus] = useState<InstallationStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [loginRoute, setLoginRoute] = useState<LoginRoute | null>(null);
  const [principalKey, setPrincipalKey] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);

  async function resolveAuthentication(signal?: AbortSignal) {
    setError(null);
    try {
      const session = await getAuthSession(signal);
      if (session.authenticated) {
        setState('authenticated');
        return;
      }
      const route = await getLoginRoute(signal);
      setLoginRoute(route);
      setState('first-party-login');
    } catch (cause: unknown) {
      if (cause instanceof DOMException && cause.name === 'AbortError') return;
      if (cause instanceof ApiError && (cause.status === 403 || cause.status === 404)) {
        setState('external-mode');
        return;
      }
      setError('Sign-in is unavailable until Wyrmgate installation and Console routing are ready.');
      setState('first-party-login');
    }
  }

  useEffect(() => {
    const controller = new AbortController();
    getInstallationStatus(controller.signal)
      .then(async (installation) => {
        if (installation.status !== 'SEALED') {
          setSetupStatus(installation);
          setState('setup');
          return;
        }
        await resolveAuthentication(controller.signal);
      })
      .catch(async (cause: unknown) => {
        if (cause instanceof DOMException && cause.name === 'AbortError') return;
        // A 404 means the deployment did not enable the optional web setup adapter.
        if (cause instanceof ApiError && cause.status === 404) {
          await resolveAuthentication(controller.signal);
          return;
        }
        await resolveAuthentication(controller.signal);
      });
    return () => controller.abort();
  }, []);

  async function setupComplete() {
    setSetupStatus(null);
    setState('checking');
    await resolveAuthentication();
  }

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

  if (state === 'setup' && setupStatus) {
    return <SetupWizard initialStatus={setupStatus} onComplete={() => void setupComplete()} />;
  }

  if (state === 'first-party-login') {
    return (
      <main className="auth-shell">
        <section className="auth-login-surface" aria-labelledby="auth-title">
          <header className="auth-product-header">
            <span className="auth-product-mark" aria-hidden="true">W</span>
            <span className="auth-product-copy">
              <strong>Wyrmgate IAM</strong>
              <span>Enterprise identity control plane</span>
            </span>
          </header>

          <div className="auth-login-layout">
            <div className="auth-context-panel">
              <p className="auth-eyebrow">Secure administration</p>
              <h1 id="auth-title">Sign in to Wyrmgate</h1>
              <p className="auth-intro">
                Continue to the IAM operator workbench using your governed Wyrmgate credentials.
              </p>

              <dl className="auth-context-list">
                <div>
                  <dt><AuthIcon name="building" /> Tenant</dt>
                  <dd>Resolved by the server from this Console route</dd>
                </div>
                <div>
                  <dt><AuthIcon name="shield" /> Session</dt>
                  <dd>Secure, server-managed browser session</dd>
                </div>
              </dl>
            </div>

            <div className="auth-form-panel">
              <header className="auth-form-header">
                <h2>Continue to Wyrmgate</h2>
                <p>Use your organisation credentials.</p>
              </header>

              {error && <Alert severity="error" role="alert" className="auth-error">{error}</Alert>}

              <form className="auth-form" onSubmit={(event) => void submit(event)}>
                <TextInput
                  className="auth-input"
                  label="Username"
                  autoComplete="username"
                  autoFocus
                  required
                  value={principalKey}
                  onChange={(event) => setPrincipalKey(event.target.value)}
                  slotProps={{
                    input: {
                      startAdornment: <span className="auth-input-icon"><AuthIcon name="user" /></span>,
                    },
                  }}
                />

                <TextInput
                  className="auth-input"
                  label="Password"
                  type={showPassword ? 'text' : 'password'}
                  autoComplete="current-password"
                  required
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  slotProps={{
                    input: {
                      startAdornment: <span className="auth-input-icon"><AuthIcon name="lock" /></span>,
                      endAdornment: (
                        <button
                          type="button"
                          className="auth-password-toggle"
                          aria-label={showPassword ? 'Hide password' : 'Show password'}
                          onClick={() => setShowPassword((value) => !value)}
                        >
                          <AuthIcon name={showPassword ? 'visibilityOff' : 'visibility'} />
                        </button>
                      ),
                    },
                  }}
                />

                <p className="auth-session-note"><AuthIcon name="shield" /> Tenant routing and session authority remain server-resolved.</p>

                <Button type="submit" variant="contained" className="auth-submit" disabled={busy || !loginRoute}>
                  <span>{busy ? 'Signing in…' : 'Sign in'}</span>
                  {!busy && <AuthIcon name="right" />}
                </Button>
              </form>
            </div>
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
