import { useEffect, useState } from 'react';
import { CONSOLE_NAVIGATION_EVENT } from './consoleNavigation';

export function useConsolePathname(): string {
  const [pathname, setPathname] = useState(() => window.location.pathname);

  useEffect(() => {
    const onLocationChange = () => setPathname(window.location.pathname);
    window.addEventListener('popstate', onLocationChange);
    window.addEventListener(CONSOLE_NAVIGATION_EVENT, onLocationChange);
    return () => {
      window.removeEventListener('popstate', onLocationChange);
      window.removeEventListener(CONSOLE_NAVIGATION_EVENT, onLocationChange);
    };
  }, []);

  return pathname;
}
