import { type KeyboardEvent } from 'react';
import { AccessWorkspace as AccessGoldenWorkspace } from './AccessGoldenWorkspace';

function handleAccessTabsKeyDown(event: KeyboardEvent<HTMLDivElement>) {
  const target = event.target;
  if (!(target instanceof HTMLElement) || target.getAttribute('role') !== 'tab') return;

  const tablist = target.closest('[role="tablist"]');
  if (!(tablist instanceof HTMLElement) || !tablist.classList.contains('access-golden-tabs')) return;

  const tabs = Array.from(tablist.querySelectorAll<HTMLElement>('[role="tab"]:not([disabled])'));
  const current = tabs.indexOf(target);
  if (current < 0 || tabs.length === 0) return;

  let next = current;
  if (event.key === 'ArrowRight') next = (current + 1) % tabs.length;
  else if (event.key === 'ArrowLeft') next = (current - 1 + tabs.length) % tabs.length;
  else if (event.key === 'Home') next = 0;
  else if (event.key === 'End') next = tabs.length - 1;
  else return;

  event.preventDefault();
  tabs[next].focus();
  tabs[next].click();
}

export function AccessWorkspace() {
  return (
    <div className="access-enterprise-boundary" onKeyDown={handleAccessTabsKeyDown}>
      <AccessGoldenWorkspace />
    </div>
  );
}
