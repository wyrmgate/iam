import { useEffect, useState } from 'react';
import { CONSOLE_NAVIGATION_EVENT } from './consoleNavigation';

export type ConsoleLocation = {
  pathname: string;
  search: string;
};

function currentLocation(): ConsoleLocation {
  return { pathname: window.location.pathname, search: window.location.search };
}

export function useConsoleLocation(): ConsoleLocation {
  const [location, setLocation] = useState<ConsoleLocation>(currentLocation);

  useEffect(() => {
    const onLocationChange = () => setLocation(currentLocation());
    window.addEventListener('popstate', onLocationChange);
    window.addEventListener(CONSOLE_NAVIGATION_EVENT, onLocationChange);
    return () => {
      window.removeEventListener('popstate', onLocationChange);
      window.removeEventListener(CONSOLE_NAVIGATION_EVENT, onLocationChange);
    };
  }, []);

  return location;
}

export function useConsolePathname(): string {
  return useConsoleLocation().pathname;
}
