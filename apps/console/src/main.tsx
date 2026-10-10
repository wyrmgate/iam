import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { SignInGate } from './SignInGate';
import './styles.css';

const root = document.getElementById('root');

if (!root) {
  throw new Error('Root element not found');
}

createRoot(root).render(
  <StrictMode>
    <SignInGate />
  </StrictMode>,
);
