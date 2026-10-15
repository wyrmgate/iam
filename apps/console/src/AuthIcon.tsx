import type { SVGProps } from 'react';

export type AuthIconName =
  | 'building'
  | 'lock'
  | 'right'
  | 'shield'
  | 'user'
  | 'visibility'
  | 'visibilityOff';

type AuthIconProps = SVGProps<SVGSVGElement> & {
  name: AuthIconName;
};

export function AuthIcon({ name, ...props }: AuthIconProps) {
  const common = {
    viewBox: '0 0 24 24',
    fill: 'none',
    stroke: 'currentColor',
    strokeWidth: 1.8,
    strokeLinecap: 'round' as const,
    strokeLinejoin: 'round' as const,
    'aria-hidden': true,
    ...props,
  };

  switch (name) {
    case 'building':
      return <svg {...common}><path d="M4 21V5.8L12 3v18M4 21h16M8 8h1M8 12h1M8 16h1M15 8h2v3h-2zM15 14h2v3h-2z" /></svg>;
    case 'user':
      return <svg {...common}><circle cx="12" cy="8" r="3.2" /><path d="M5.5 20c.7-4 2.8-6 6.5-6s5.8 2 6.5 6" /></svg>;
    case 'lock':
      return <svg {...common}><rect x="5" y="10" width="14" height="10" rx="2" /><path d="M8.5 10V7.5a3.5 3.5 0 0 1 7 0V10" /></svg>;
    case 'visibility':
      return <svg {...common}><path d="M2.5 12s3.3-5 9.5-5 9.5 5 9.5 5-3.3 5-9.5 5-9.5-5-9.5-5Z" /><circle cx="12" cy="12" r="2.4" /></svg>;
    case 'visibilityOff':
      return <svg {...common}><path d="M3 3 21 21M9.8 7.3A10.8 10.8 0 0 1 12 7c6.2 0 9.5 5 9.5 5a14 14 0 0 1-2.7 3.1M6.3 8.1A13.5 13.5 0 0 0 2.5 12s3.3 5 9.5 5c1.1 0 2.1-.2 3-.4M10.3 10.3a2.4 2.4 0 0 0 3.4 3.4" /></svg>;
    case 'right':
      return <svg {...common}><path d="M5 12h13M14 7l5 5-5 5" /></svg>;
    default:
      return <svg {...common}><path d="M12 3 19 6v5.2c0 4.4-2.7 7.5-7 9.8-4.3-2.3-7-5.4-7-9.8V6z" /><path d="m9.2 12 1.8 1.8 3.8-4" /></svg>;
  }
}
