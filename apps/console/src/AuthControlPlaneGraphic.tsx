import type { CSSProperties, SVGProps } from 'react';

export type AuthIconName =
  | 'audit'
  | 'building'
  | 'check'
  | 'chevron'
  | 'globe'
  | 'governance'
  | 'identity'
  | 'info'
  | 'integration'
  | 'key'
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
    case 'identity':
      return <svg {...common}><circle cx="9" cy="8" r="3" /><path d="M3.8 19c.6-3.2 2.4-5 5.2-5s4.6 1.8 5.2 5M15.4 5.5a3 3 0 0 1 0 5.8M17.2 14c2 .6 3.2 2.2 3.6 4.5" /></svg>;
    case 'key':
      return <svg {...common}><circle cx="8.2" cy="15.8" r="4.2" /><path d="m11.2 12.8 7.5-7.5 2 2-1.6 1.6 1.4 1.4-2.2 2.2-1.4-1.4-2.9 2.9" /></svg>;
    case 'shield':
      return <svg {...common}><path d="M12 3 19 6v5.2c0 4.4-2.7 7.5-7 9.8-4.3-2.3-7-5.4-7-9.8V6z" /><path d="m9.2 12 1.8 1.8 3.8-4" /></svg>;
    case 'governance':
      return <svg {...common}><path d="M6 3.5h9l3 3V21H6z" /><path d="M15 3.5V7h3M9 11h6M9 15h6M9 18h4" /></svg>;
    case 'integration':
      return <svg {...common}><circle cx="6" cy="12" r="2.5" /><circle cx="18" cy="6" r="2.5" /><circle cx="18" cy="18" r="2.5" /><path d="m8.4 10.8 7.2-3.6M8.4 13.2l7.2 3.6" /></svg>;
    case 'audit':
      return <svg {...common}><circle cx="11" cy="11" r="6" /><path d="m15.5 15.5 5 5M8.5 11h5M11 8.5v5" /></svg>;
    case 'user':
      return <svg {...common}><circle cx="12" cy="8" r="3.2" /><path d="M5.5 20c.7-4 2.8-6 6.5-6s5.8 2 6.5 6" /></svg>;
    case 'lock':
      return <svg {...common}><rect x="5" y="10" width="14" height="10" rx="2" /><path d="M8.5 10V7.5a3.5 3.5 0 0 1 7 0V10" /></svg>;
    case 'visibility':
      return <svg {...common}><path d="M2.5 12s3.3-5 9.5-5 9.5 5 9.5 5-3.3 5-9.5 5-9.5-5-9.5-5Z" /><circle cx="12" cy="12" r="2.4" /></svg>;
    case 'visibilityOff':
      return <svg {...common}><path d="M3 3 21 21M9.8 7.3A10.8 10.8 0 0 1 12 7c6.2 0 9.5 5 9.5 5a14 14 0 0 1-2.7 3.1M6.3 8.1A13.5 13.5 0 0 0 2.5 12s3.3 5 9.5 5c1.1 0 2.1-.2 3-.4M10.3 10.3a2.4 2.4 0 0 0 3.4 3.4" /></svg>;
    case 'globe':
      return <svg {...common}><circle cx="12" cy="12" r="9" /><path d="M3.5 12h17M12 3c2.2 2.5 3.3 5.5 3.3 9S14.2 18.5 12 21M12 3C9.8 5.5 8.7 8.5 8.7 12s1.1 6.5 3.3 9" /></svg>;
    case 'chevron':
      return <svg {...common}><path d="m7 9.5 5 5 5-5" /></svg>;
    case 'right':
      return <svg {...common}><path d="M5 12h13M14 7l5 5-5 5" /></svg>;
    case 'info':
      return <svg {...common}><circle cx="12" cy="12" r="9" /><path d="M12 10.5V17M12 7h.01" /></svg>;
    case 'check':
      return <svg {...common}><path d="m5 12.5 4.2 4.2L19 7" /></svg>;
    default:
      return <svg {...common}><path d="M12 3 19 6v5.2c0 4.4-2.7 7.5-7 9.8-4.3-2.3-7-5.4-7-9.8V6z" /></svg>;
  }
}

type VisualNode = {
  icon: AuthIconName;
  title: string;
  description: string;
  left: string;
  top: string;
  delay: string;
};

const nodes: VisualNode[] = [
  { icon: 'identity', title: 'Identities', description: 'Users, service accounts and external identities', left: '50%', top: '8%', delay: '-1.2s' },
  { icon: 'building', title: 'Tenants', description: 'Isolation and administration', left: '22%', top: '26%', delay: '-3.4s' },
  { icon: 'key', title: 'Credentials', description: 'Passwords, keys, certificates and secrets', left: '78%', top: '26%', delay: '-2.2s' },
  { icon: 'shield', title: 'Access', description: 'Roles, entitlements and assignments', left: '9%', top: '56%', delay: '-4.1s' },
  { icon: 'governance', title: 'Governance', description: 'Policies, approvals and reviews', left: '91%', top: '56%', delay: '-.7s' },
  { icon: 'integration', title: 'Integrations', description: 'Directories, cloud and business systems', left: '28%', top: '82%', delay: '-2.8s' },
  { icon: 'audit', title: 'Audit', description: 'Visibility and evidence across your estate', left: '72%', top: '82%', delay: '-1.8s' },
];

export function AuthControlPlaneGraphic() {
  return (
    <div className="auth-network" aria-label="Wyrmgate identity control plane connections">
      <svg className="auth-network-lines" viewBox="0 0 760 460" role="presentation" aria-hidden="true">
        <defs>
          <radialGradient id="auth-network-halo" cx="50%" cy="50%" r="50%">
            <stop offset="0%" stopColor="#25c8ff" stopOpacity=".18" />
            <stop offset="55%" stopColor="#1d67ff" stopOpacity=".04" />
            <stop offset="100%" stopColor="#1d67ff" stopOpacity="0" />
          </radialGradient>
          <linearGradient id="auth-network-line" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stopColor="#30dbff" stopOpacity=".9" />
            <stop offset="100%" stopColor="#3e8cff" stopOpacity=".62" />
          </linearGradient>
        </defs>
        <ellipse cx="380" cy="235" rx="350" ry="205" fill="url(#auth-network-halo)" />
        <ellipse className="auth-orbit auth-orbit-one" cx="380" cy="235" rx="332" ry="198" />
        <ellipse className="auth-orbit auth-orbit-two" cx="380" cy="235" rx="255" ry="151" />
        <ellipse className="auth-orbit auth-orbit-three" cx="380" cy="235" rx="176" ry="102" />
        <path className="auth-connection" d="M380 235 380 57" />
        <path className="auth-connection" d="M380 235 167 126" />
        <path className="auth-connection" d="M380 235 593 126" />
        <path className="auth-connection" d="M380 235 69 253" />
        <path className="auth-connection" d="M380 235 691 253" />
        <path className="auth-connection" d="M380 235 214 377" />
        <path className="auth-connection" d="M380 235 546 377" />
        <g className="auth-pulse-dots">
          <circle cx="380" cy="142" r="3" />
          <circle cx="276" cy="182" r="3" />
          <circle cx="484" cy="182" r="3" />
          <circle cx="226" cy="244" r="3" />
          <circle cx="534" cy="244" r="3" />
          <circle cx="301" cy="303" r="3" />
          <circle cx="459" cy="303" r="3" />
        </g>
      </svg>

      <div className="auth-core" aria-hidden="true">
        <div className="auth-core-layer auth-core-layer-three" />
        <div className="auth-core-layer auth-core-layer-two" />
        <div className="auth-core-face">
          <span className="auth-core-mark">W</span>
          <strong>Wyrmgate IAM</strong>
          <small>Single control plane</small>
        </div>
      </div>

      {nodes.map((node) => (
        <div
          className="auth-network-node"
          key={node.title}
          style={{ left: node.left, top: node.top, animationDelay: node.delay } as CSSProperties}
        >
          <span className="auth-network-node-icon"><AuthIcon name={node.icon} /></span>
          <strong>{node.title}</strong>
          <small>{node.description}</small>
        </div>
      ))}
    </div>
  );
}
