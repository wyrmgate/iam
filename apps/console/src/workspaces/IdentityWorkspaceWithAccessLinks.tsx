import type { CurrentAdministrativeAuthority } from '../api';
import { navigateConsole } from '../consoleNavigation';
import { Button } from '../ui';
import { IdentityWorkspace } from './IdentityWorkspace';
import '../identity-access-links.css';

export function IdentityWorkspaceWithAccessLinks({
  selectedId,
  onSelect,
  authority = null,
}: {
  selectedId?: string;
  onSelect: (id?: string) => void;
  authority?: CurrentAdministrativeAuthority | null;
}) {
  const identityId = selectedId ?? '';
  return (
    <>
      {identityId ? (
        <nav className="identity-access-links" aria-label="Identity Access investigation links">
          <div>
            <strong>Access context</strong>
            <span>Deep links preserve this governed Identity as server-side Access filter context. Navigation does not imply permission.</span>
          </div>
          <div>
            <Button variant="outlined" size="small" onClick={() => navigateConsole(`/access?view=assignments&identityId=${encodeURIComponent(identityId)}`)}>
              View authoritative access
            </Button>
            <Button variant="outlined" size="small" onClick={() => navigateConsole(`/access?view=effective&identityId=${encodeURIComponent(identityId)}`)}>
              View effective access
            </Button>
          </div>
        </nav>
      ) : null}
      <IdentityWorkspace selectedId={selectedId} onSelect={onSelect} authority={authority} />
    </>
  );
}
