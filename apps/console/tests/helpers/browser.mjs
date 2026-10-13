function snapshotGlobal(name) {
  return {
    exists: Object.prototype.hasOwnProperty.call(globalThis, name),
    value: globalThis[name],
  };
}

function restoreGlobal(name, snapshot) {
  if (snapshot.exists) {
    globalThis[name] = snapshot.value;
  } else {
    delete globalThis[name];
  }
}

export function installBrowserHistory(t, initialUrl = 'https://console.example/') {
  const snapshots = {
    window: snapshotGlobal('window'),
    location: snapshotGlobal('location'),
    history: snapshotGlobal('history'),
  };

  let currentUrl = new URL(initialUrl);
  const windowTarget = new EventTarget();
  const location = {};

  const navigate = (href) => {
    currentUrl = new URL(String(href), currentUrl);
  };

  Object.defineProperties(location, {
    href: {
      enumerable: true,
      get: () => currentUrl.href,
      set: navigate,
    },
    origin: { enumerable: true, get: () => currentUrl.origin },
    pathname: { enumerable: true, get: () => currentUrl.pathname },
    search: { enumerable: true, get: () => currentUrl.search },
    hash: { enumerable: true, get: () => currentUrl.hash },
  });

  const history = {
    state: null,
    pushState(state, _unused, href) {
      this.state = state;
      if (href != null) navigate(href);
    },
    replaceState(state, _unused, href) {
      this.state = state;
      if (href != null) navigate(href);
    },
  };

  Object.defineProperties(windowTarget, {
    location: { enumerable: true, get: () => location },
    history: { enumerable: true, get: () => history },
  });

  globalThis.window = windowTarget;
  globalThis.location = location;
  globalThis.history = history;

  t.after(() => {
    restoreGlobal('window', snapshots.window);
    restoreGlobal('location', snapshots.location);
    restoreGlobal('history', snapshots.history);
  });

  return {
    location,
    history,
    dispatchPopState() {
      windowTarget.dispatchEvent(new Event('popstate'));
    },
  };
}
