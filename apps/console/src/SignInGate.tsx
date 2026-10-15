import { useEffect, useState, type FormEvent } from 'react';
import { App } from './App';
import { AuthControlPlaneGraphic, AuthIcon } from './AuthControlPlaneGraphic';
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
      <main className="auth-shell auth-shell-enterprise">
        <section className="auth-brand-panel auth-brand-panel-enterprise" aria-label="Wyrmgate IAM">
          <div className="auth-brand-ambient auth-brand-ambient-one" aria-hidden="true" />
          <div className="auth-brand-ambient auth-brand-ambient-two" aria-hidden="true" />

          <div className="auth-wordmark auth-wordmark-enterprise">
            <span className="auth-mark auth-mark-enterprise" aria-hidden="true">W</span>
            <span className="auth-wordmark-copy">
              <strong>Wyrmgate IAM</strong>
              <span>Identity Governance and Access Management</span>
            </span>
          </div>

          <div className="auth-enterprise-copy">
            <p className="auth-enterprise-eyebrow">One identity control plane</p>
            <h1>People. Access. Governance. <span>Connected.</span></h1>
            <p>Wyrmgate unifies identities, credentials, access, governance and integrations across your enterprise.</p>
          </div>

          <AuthControlPlaneGraphic />
        </section>

        <section className="auth-form-panel auth-form-panel-enterprise">
          <div className="auth-form-wrap auth-form-wrap-enterprise">
            <header className="auth-form-header-enterprise">
              <p className="auth-form-eyebrow">Sign in</p>
              <h2>Continue to Wyrmgate</h2>
              <p>Use your organisation credentials to continue.</p>
            </header>

            {error && <Alert severity="error" role="alert" className="auth-error">{error}</Alert>}

            <form className="auth-form auth-form-enterprise" onSubmit={(event) => void submit(event)}>
              <div className="auth-route-field" aria-label="Tenant routing">
                <span className="auth-route-field-label">Tenant</span>
                <span className="auth-route-field-value"><AuthIcon name="building" /> Server resolved</span>
                <span className="auth-route-field-note">Resolved from this Console route.</span>
              </div>

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

              <div className="auth-session-context">
                <span><AuthIcon name="shield" /> Secure server-managed session</span>
                <span>Tenant routing is resolved by Wyrmgate</span>
              </div>

              <Button type="submit" variant="contained" className="auth-submit" disabled={busy || !loginRoute}>
                <span>{busy ? 'Signing in…' : 'Sign in'}</span>
                {!busy && <AuthIcon name="right" />}
              </Button>
            </form>
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
