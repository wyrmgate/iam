import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { SignInGate } from './SignInGate';
import { installEnterpriseCssVariables } from './ui/cssVariables';
import { WyrmgateUiProvider } from './ui/WyrmgateUiProvider';
import './styles.css';
import './auth-login.css';
import './shell.css';
import './shell-theme.css';
import './shell-foundation.css';
import './shell-session-golden.css';
import './enterprise-console.css';

installEnterpriseCssVariables();

const root = document.getElementById('root');

if (!root) {
  throw new Error('Root element not found');
}

createRoot(root).render(
  <StrictMode>
    <WyrmgateUiProvider>
      <SignInGate />
    </WyrmgateUiProvider>
  </StrictMode>,
);
