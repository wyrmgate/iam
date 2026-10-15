import { useConsoleLocation } from '../consoleLocation';
import { navigateConsole } from '../consoleNavigation';
import { Button } from '../ui';
import { AuditWorkspace } from './AuditWorkspace';
import '../semantic-cross-links.css';

export function AuditWorkspaceWithAccessLink() {
  const { search } = useConsoleLocation();
  const query = new URLSearchParams(search);
  const resourceType = query.get('resourceType');
  const resourceId = query.get('resourceId');
  const assignmentScoped = resourceType === 'access-assignment' && Boolean(resourceId);

  return (
    <>
      {assignmentScoped ? (
        <nav className="semantic-cross-links" aria-label="Audit evidence Access link">
          <div>
            <strong>AccessAssignment evidence context</strong>
            <span>Audit is immutable evidence, not current Access authority. Open the referenced assignment to inspect current business intent.</span>
          </div>
          <div>
            <Button
              variant="outlined"
              size="small"
              onClick={() => navigateConsole(`/access?view=assignments&assignmentId=${encodeURIComponent(resourceId!)}`)}
            >
              View AccessAssignment
            </Button>
          </div>
        </nav>
      ) : null}
      <AuditWorkspace />
    </>
  );
}
