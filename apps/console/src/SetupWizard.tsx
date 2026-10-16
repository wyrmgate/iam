import { useMemo, useState, type FormEvent } from 'react';
import { Alert, Button, CircularProgress, TextInput } from './ui';
import { ApiError } from './api';
import {
  establishSetupSession,
  getInstallationStatus,
  initializeInstallation,
  type InstallationPhase,
  type InstallationStatus,
} from './installationApi';
import './setup.css';

type WizardStep = 'access' | 'installation' | 'administrator' | 'review' | 'running' | 'complete';

const PHASES: Array<{ phase: InstallationPhase; label: string }> = [
  { phase: 'PLANNED', label: 'Installation plan secured' },
  { phase: 'TENANT_CREATED', label: 'Tenant created' },
  { phase: 'IDENTITY_CREATED', label: 'Administrator identity created' },
  { phase: 'CONSOLE_REGISTERED', label: 'Wyrmgate Console registered' },
  { phase: 'PRINCIPAL_CREATED', label: 'Administrator principal created' },
  { phase: 'CREDENTIAL_ENROLLED', label: 'Password credential enrolled' },
  { phase: 'ADMIN_BOOTSTRAPPED', label: 'Platform Owner and Tenant Owner authority established' },
  { phase: 'VERIFIED', label: 'Installation and owner authority verified' },
  { phase: 'SEALED', label: 'Bootstrap permanently sealed' },
];

function phaseReached(current: InstallationPhase | null, target: InstallationPhase): boolean {
  if (!current) return false;
  return PHASES.findIndex((item) => item.phase === current) >= PHASES.findIndex((item) => item.phase === target);
}

export function SetupWizard({ initialStatus, onComplete }: {
  initialStatus: InstallationStatus;
  onComplete: () => void;
}) {
  const [status, setStatus] = useState(initialStatus);
  const [step, setStep] = useState<WizardStep>(() => {
    if (initialStatus.status === 'SEALED') return 'complete';
    if (!initialStatus.setupSessionAuthenticated) return 'access';
    return initialStatus.status === 'IN_PROGRESS' ? 'administrator' : 'installation';
  });
  const [setupSecret, setSetupSecret] = useState('');
  const [tenantDisplayName, setTenantDisplayName] = useState(initialStatus.tenantDisplayName ?? 'Wyrmgate');
  const [identityDisplayName, setIdentityDisplayName] = useState(initialStatus.identityDisplayName ?? 'Wyrmgate Administrator');
  const [username, setUsername] = useState(initialStatus.username ?? 'admin');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const frozenPlan = status.status === 'IN_PROGRESS';
  const passwordsMatch = password.length > 0 && password === confirmPassword;
  const canContinueInstallation = tenantDisplayName.trim().length > 0;
  const canContinueAdministrator = identityDisplayName.trim().length > 0
    && username.trim().length > 0
    && passwordsMatch;

  const currentPhaseLabel = useMemo(
    () => PHASES.find((item) => item.phase === status.phase)?.label ?? 'Ready to initialize',
    [status.phase],
  );

  async function unlock(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const next = await establishSetupSession(setupSecret);
      setStatus(next);
      setSetupSecret('');
      setTenantDisplayName(next.tenantDisplayName ?? tenantDisplayName);
      setIdentityDisplayName(next.identityDisplayName ?? identityDisplayName);
      setUsername(next.username ?? username);
      setStep(next.status === 'IN_PROGRESS' ? 'administrator' : 'installation');
    } catch (cause) {
      setSetupSecret('');
      setError(cause instanceof ApiError && cause.status === 401
        ? 'The setup access code was not accepted.'
        : 'Secure setup could not be unlocked.');
    } finally {
      setBusy(false);
    }
  }

  async function initialize() {
    setStep('running');
    setBusy(true);
    setError(null);
    try {
      const next = await initializeInstallation({
        tenantDisplayName: tenantDisplayName.trim(),
        identityDisplayName: identityDisplayName.trim(),
        username: username.trim(),
        password,
      });
      setStatus(next);
      setPassword('');
      setConfirmPassword('');
      setStep(next.status === 'SEALED' ? 'complete' : 'running');
    } catch (cause) {
      setPassword('');
      setConfirmPassword('');
      try {
        const refreshed = await getInstallationStatus();
        setStatus(refreshed);
        if (refreshed.tenantDisplayName) setTenantDisplayName(refreshed.tenantDisplayName);
        if (refreshed.identityDisplayName) setIdentityDisplayName(refreshed.identityDisplayName);
        if (refreshed.username) setUsername(refreshed.username);
      } catch {
        // Keep the last safe projection if status refresh also fails.
      }
      setError(cause instanceof ApiError && cause.code === 'INSTALLATION_RESUME_FAILED'
        ? 'Setup made durable progress but could not finish. Re-enter the original administrator password to resume safely.'
        : 'Installation could not complete. Durable progress has been preserved; retry to resume.');
      setStep('administrator');
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="setup-shell">
      <section className="setup-panel" aria-labelledby="setup-title">
        <header className="setup-header">
          <div className="setup-mark" aria-hidden="true">W</div>
          <div>
            <p className="setup-eyebrow">First-run setup</p>
            <h1 id="setup-title">Initialize Wyrmgate IAM</h1>
            <p>Establish the first tenant, its Tenant Owner, and the deployment Platform Owner; verify the Console, then permanently seal bootstrap.</p>
          </div>
        </header>

        <ol className="setup-stepper" aria-label="Setup progress">
          {['Secure setup', 'Installation', 'Administrator', 'Review', 'Initialize'].map((label, index) => (
            <li key={label} className={index <= ['access', 'installation', 'administrator', 'review', 'running', 'complete'].indexOf(step) ? 'active' : ''}>
              <span>{index + 1}</span>{label}
            </li>
          ))}
        </ol>

        {error && <Alert severity="error" role="alert">{error}</Alert>}

        {step === 'access' && (
          <form className="setup-form" onSubmit={(event) => void unlock(event)}>
            <h2>Secure setup access</h2>
            <p>Enter the one-time setup access code supplied by the deployment operator. It is separate from the administrator password.</p>
            <TextInput
              label="Setup access code"
              type="password"
              autoComplete="off"
              autoFocus
              required
              value={setupSecret}
              onChange={(event) => setSetupSecret(event.target.value)}
            />
            <Button type="submit" variant="contained" disabled={busy || setupSecret.length === 0}>
              {busy ? 'Verifying…' : 'Continue securely'}
            </Button>
          </form>
        )}

        {step === 'installation' && (
          <div className="setup-form">
            <h2>Installation</h2>
            <p>Choose the first tenant display name. Network and Console routing remain controlled by trusted deployment configuration.</p>
            <TextInput
              label="Tenant display name"
              required
              disabled={frozenPlan}
              value={tenantDisplayName}
              onChange={(event) => setTenantDisplayName(event.target.value)}
            />
            <TextInput
              label="Console redirect URI"
              value={status.consoleRedirectUri}
              readOnly
              helperText="Read-only: resolved from trusted server configuration."
            />
            <Button variant="contained" disabled={!canContinueInstallation} onClick={() => setStep('administrator')}>Continue</Button>
          </div>
        )}

        {step === 'administrator' && (
          <div className="setup-form">
            <h2>{frozenPlan ? 'Resume administrator setup' : 'Initial administrator'}</h2>
            <p>{frozenPlan
              ? `Durable setup is at “${currentPhaseLabel}”. The original non-secret values are locked; re-enter the original password to resume.`
              : 'Create the governed person identity, Console principal and password credential. This identity becomes the deployment Platform Owner and the first Tenant Owner.'}</p>
            <TextInput label="Administrator display name" required disabled={frozenPlan} value={identityDisplayName} onChange={(event) => setIdentityDisplayName(event.target.value)} />
            <TextInput label="Username" autoComplete="username" required disabled={frozenPlan} value={username} onChange={(event) => setUsername(event.target.value)} />
            <TextInput label="Password" type="password" autoComplete="new-password" required value={password} onChange={(event) => setPassword(event.target.value)} />
            <TextInput
              label="Confirm password"
              type="password"
              autoComplete="new-password"
              required
              value={confirmPassword}
              error={confirmPassword.length > 0 && password !== confirmPassword}
              helperText={confirmPassword.length > 0 && password !== confirmPassword ? 'Passwords do not match.' : 'The password is sent only for Credential enrollment and verification.'}
              onChange={(event) => setConfirmPassword(event.target.value)}
            />
            <div className="setup-actions">
              {!frozenPlan && <Button variant="outlined" onClick={() => setStep('installation')}>Back</Button>}
              <Button variant="contained" disabled={!canContinueAdministrator} onClick={() => setStep('review')}>Review</Button>
            </div>
          </div>
        )}

        {step === 'review' && (
          <div className="setup-form">
            <h2>Review</h2>
            <dl className="setup-review">
              <div><dt>Tenant</dt><dd>{tenantDisplayName}</dd></div>
              <div><dt>Administrator</dt><dd>{identityDisplayName}</dd></div>
              <div><dt>Username</dt><dd>{username}</dd></div>
              <div><dt>Deployment authority</dt><dd>Platform Owner</dd></div>
              <div><dt>Tenant authority</dt><dd>Tenant Owner</dd></div>
              <div><dt>Console redirect</dt><dd>{status.consoleRedirectUri}</dd></div>
              <div><dt>Password</dt><dd>••••••••</dd></div>
            </dl>
            <Alert severity="info">Initialization creates both owner authorities atomically with the first administrative grant. Once all authority and authentication checks pass, bootstrap is permanently sealed and cannot be reopened.</Alert>
            <div className="setup-actions">
              <Button variant="outlined" onClick={() => setStep('administrator')}>Back</Button>
              <Button variant="contained" onClick={() => void initialize()}>Initialize Wyrmgate</Button>
            </div>
          </div>
        )}

        {step === 'running' && (
          <div className="setup-form" aria-live="polite">
            <div className="setup-running-title"><CircularProgress size={22} thickness={5} /><h2>Initializing Wyrmgate</h2></div>
            <p>Each completed phase is durable. Refreshing or retrying resumes from the authoritative checkpoint rather than starting over.</p>
            <ol className="setup-phases">
              {PHASES.map(({ phase, label }) => <li key={phase} className={phaseReached(status.phase, phase) ? 'done' : ''}>{label}</li>)}
            </ol>
          </div>
        )}

        {step === 'complete' && (
          <div className="setup-form setup-complete">
            <div className="setup-complete-mark" aria-hidden="true">✓</div>
            <h2>Wyrmgate is ready</h2>
            <p>The installation, Platform Owner and first Tenant Owner were verified and bootstrap is permanently sealed. Continue through the normal first-party sign-in flow.</p>
            <Button variant="contained" onClick={onComplete}>Sign in to Wyrmgate</Button>
          </div>
        )}
      </section>
    </main>
  );
}
