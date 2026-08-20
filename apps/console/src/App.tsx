import { useEffect, useState } from 'react';

type SystemInfo = {
  name: string;
  status: string;
};

export function App() {
  const [systemInfo, setSystemInfo] = useState<SystemInfo | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();

    fetch('/api/system/info', { signal: controller.signal })
      .then(async (response) => {
        if (!response.ok) {
          throw new Error(`Server returned ${response.status}`);
        }
        return response.json() as Promise<SystemInfo>;
      })
      .then(setSystemInfo)
      .catch((cause: unknown) => {
        if (cause instanceof DOMException && cause.name === 'AbortError') {
          return;
        }
        setError(cause instanceof Error ? cause.message : 'Unable to reach IAM server');
      });

    return () => controller.abort();
  }, []);

  return (
    <main className="shell">
      <section className="panel">
        <p className="eyebrow">Wyrmgate</p>
        <h1>Identity &amp; Access Management</h1>
        <p className="description">
          Engineering baseline for the IAM v2 modular monolith and management console.
        </p>

        <div className="status" aria-live="polite">
          {systemInfo && (
            <>
              <strong>{systemInfo.name}</strong>
              <span>Server status: {systemInfo.status}</span>
            </>
          )}
          {!systemInfo && !error && <span>Connecting to IAM server…</span>}
          {error && <span>Server unavailable: {error}</span>}
        </div>
      </section>
    </main>
  );
}
