import { useEffect, useState } from 'react';
import { useConsoleLocation } from './consoleLocation';
import { navigateConsole } from './consoleNavigation';
import { RequestsApprovalsPage } from './RequestsApprovalsPage';
import { getAccessRequest, type RequestItem } from './requestsApprovalsApi';
import { Button } from './ui';
import './semantic-cross-links.css';

export function RequestsApprovalsWithAccessLinks() {
  const { search } = useConsoleLocation();
  const requestId = new URLSearchParams(search).get('requestId');
  const [appliedItems, setAppliedItems] = useState<RequestItem[]>([]);

  useEffect(() => {
    setAppliedItems([]);
    if (!requestId) return;
    const controller = new AbortController();
    getAccessRequest(requestId, controller.signal)
      .then(({ data }) => setAppliedItems(data.items.filter((item) => item.state === 'APPLIED' && Boolean(item.accessAssignmentId))))
      .catch(() => setAppliedItems([]));
    return () => controller.abort();
  }, [requestId]);

  return (
    <>
      {appliedItems.length ? (
        <nav className="semantic-cross-links" aria-label="Applied request Access links">
          <div>
            <strong>Resulting Access authority</strong>
            <span>APPLIED means Access accepted authoritative assignment intent. It does not mean provider fulfillment succeeded.</span>
          </div>
          <div>
            {appliedItems.slice(0, 4).map((item, index) => (
              <Button
                key={item.id}
                variant="outlined"
                size="small"
                onClick={() => navigateConsole(`/access?view=assignments&assignmentId=${encodeURIComponent(item.accessAssignmentId!)}`)}
              >
                View resulting access{appliedItems.length > 1 ? ` ${index + 1}` : ''}
              </Button>
            ))}
          </div>
        </nav>
      ) : null}
      <RequestsApprovalsPage />
    </>
  );
}
