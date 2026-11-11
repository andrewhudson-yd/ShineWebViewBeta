/*
 * NativeApp bridge — injected by the app into every page on a trusted origin.
 * Gives your website one small API for native features:
 *
 *   if (window.NativeApp) NativeApp.haptic('success');
 *
 * Pages can wait for it with:  document.addEventListener('nativeapp:ready', fn)
 * or check `window.NativeApp?.isNative` before use. See README.md for the full API.
 */
(function () {
  'use strict';
  if (window.NativeApp && window.NativeApp.isNative) return;

  var cfg = window.__NATIVE_SHELL__ || {};
  var trusted = cfg.trustedOrigins || [];
  // Only expose native features to your own sites (plus the app's bundled pages).
  if (trusted.indexOf(location.origin) === -1 && location.origin !== cfg.localOrigin) return;

  // ---------- plumbing ----------

  var readyCallbacks = [];
  var plugins = null;

  function whenCapacitor(fn, tries) {
    var cap = window.Capacitor;
    if (cap && cap.Plugins && cap.Plugins.NativeShell) return fn(cap.Plugins);
    if ((tries || 0) > 200) return console.warn('[NativeApp] Capacitor bridge not found');
    setTimeout(function () { whenCapacitor(fn, (tries || 0) + 1); }, 25);
  }

  function call(plugin, method, args) {
    return new Promise(function (resolve, reject) {
      whenCapacitor(function (P) {
        var p = P[plugin];
        if (!p || typeof p[method] !== 'function') return reject(new Error(plugin + '.' + method + ' is not available'));
        p[method](args || {}).then(resolve, reject);
      });
    });
  }

  function emit(name, detail) {
    try { window.dispatchEvent(new CustomEvent('nativeapp:' + name, { detail: detail })); } catch (e) { /* old webview */ }
  }

  function on(name, cb) {
    var handler = function (e) { cb(e.detail); };
    window.addEventListener('nativeapp:' + name, handler);
    return function off() { window.removeEventListener('nativeapp:' + name, handler); };
  }

  function absolute(url) {
    try { return new URL(url, location.href).href; } catch (e) { return url; }
  }

  function isInternal(url) {
    try {
      var u = new URL(url, location.href);
      if (u.protocol !== 'http:' && u.protocol !== 'https:') return false;
      var hosts = cfg.internalHosts || [];
      for (var i = 0; i < hosts.length; i++) {
        var h = hosts[i];
        if (h === u.hostname) return true;
        if (h.indexOf('*.') === 0 && (u.hostname === h.slice(2) || u.hostname.slice(-(h.length - 1)) === h.slice(1))) return true;
      }
      return u.origin === location.origin;
    } catch (e) { return true; }
  }

  // ---------- public API ----------

  var HAPTICS = ['light', 'medium', 'heavy', 'soft', 'rigid', 'success', 'warning', 'error', 'selection'];

  var NativeApp = {
    isNative: true,
    platform: cfg.platform || 'unknown',
    version: cfg.appVersion || '',
    config: cfg,

    /** Run fn once the native bridge is ready. */
    ready: function (fn) {
      if (plugins) { if (fn) fn(NativeApp); return Promise.resolve(NativeApp); }
      return new Promise(function (resolve) { readyCallbacks.push(function () { if (fn) fn(NativeApp); resolve(NativeApp); }); });
    },

    /** Subscribe to events: ready, splash:done, resume, pause, push:click, push:received, push:permission, network, tab. Returns an unsubscribe function. */
    on: on,

    /** light | medium | heavy | soft | rigid | success | warning | error | selection */
    haptic: function (type) {
      type = HAPTICS.indexOf(type) === -1 ? 'light' : type;
      return call('NativeShell', 'haptic', { type: type }).catch(function () {});
    },

    /**
     * Native dictation. start() resolves once listening; results arrive as events:
     *   NativeApp.on('speech', function (e) { e.type: result | level | stopped | error; e.text, e.final, e.level, e.message })
     * Calling start() while listening stops it. Android only for now.
     */
    speech: {
      start: function (opts) {
        opts = opts || {};
        return call('NativeShell', 'speechStart', { locale: opts.locale || 'en-GB', pauseFor: opts.pauseFor || 4000 });
      },
      stop: function () { return call('NativeShell', 'speechStop'); }
    },

    /** Raw vibration in ms (Android); iOS falls back to a heavy haptic. */
    vibrate: function (ms) { return call('NativeShell', 'vibrate', { duration: ms || 300 }).catch(function () {}); },

    share: function (opts) {
      opts = opts || {};
      if (opts.url) opts.url = absolute(opts.url);
      return call('Share', 'share', { title: opts.title, text: opts.text, url: opts.url, dialogTitle: opts.dialogTitle || opts.title });
    },

    /** mode: 'inApp' (default) | 'browser' */
    openUrl: function (url, opts) {
      return call('NativeShell', 'openUrl', { url: absolute(url), mode: (opts && opts.mode) || 'inApp' });
    },

    /** Download a file (handles same-origin URLs, blob: and data: URLs). */
    download: function (url, filename) { return downloadFile(url, filename); },

    copy: function (text) { return call('Clipboard', 'write', { string: String(text) }); },
    paste: function () { return call('Clipboard', 'read').then(function (r) { return r.value; }); },

    reload: function () { return call('NativeShell', 'reload'); },

    /**
     * Small key/value storage on the phone, shared by your website and the app's bundled pages
     * (unlike localStorage, which is per-origin). Values are strings; use JSON for objects.
     */
    storage: {
      get: function (key) { return call('Preferences', 'get', { key: key }).then(function (r) { return r.value; }); },
      set: function (key, value) { return call('Preferences', 'set', { key: key, value: String(value) }); },
      remove: function (key) { return call('Preferences', 'remove', { key: key }); },
      getJSON: function (key) {
        return NativeApp.storage.get(key).then(function (v) { try { return v ? JSON.parse(v) : null; } catch (e) { return null; } });
      },
      setJSON: function (key, value) { return NativeApp.storage.set(key, JSON.stringify(value)); }
    },

    /** The app's bundled pages live here (e.g. localOrigin + '/app/shopping/index.html'). */
    localOrigin: cfg.localOrigin || '',
    keepAwake: function (on) { return call('NativeShell', 'keepAwake', { enabled: on !== false }); },
    pullToRefresh: function (enabled) { return call('NativeShell', 'setPullToRefresh', { enabled: enabled !== false }); },

    device: {
      info: function () {
        return Promise.all([call('Device', 'getInfo'), call('App', 'getInfo').catch(function () { return {}; }), call('Device', 'getId')])
          .then(function (r) { var o = r[0]; o.appVersion = r[1].version; o.appBuild = r[1].build; o.deviceId = r[2].identifier; return o; });
      },
      battery: function () { return call('Device', 'getBatteryInfo'); },
      language: function () { return call('Device', 'getLanguageCode').then(function (r) { return r.value; }); }
    },

    network: {
      status: function () { return call('Network', 'getStatus'); }
    },

    statusBar: {
      /** 'dark' = dark icons (for light backgrounds), 'light' = light icons */
      setStyle: function (style) { return call('StatusBar', 'setStyle', { style: style === 'light' ? 'DARK' : 'LIGHT' }); },
      hide: function () { return call('StatusBar', 'hide'); },
      show: function () { return call('StatusBar', 'show'); }
    },

    /** names: camera | microphone | location | notifications | photos */
    permissions: {
      check: function () { return call('NativeShell', 'checkPermissions'); },
      request: function (names) {
        names = Array.isArray(names) ? names : [names];
        return call('NativeShell', 'requestPermissions', { permissions: names });
      },
      openSettings: function () { return call('NativeShell', 'openSettings'); }
    },

    notifications: {
      /** Shows the OS prompt (once). Resolves { granted: boolean }. */
      requestPermission: function () { return call('NativeShell', 'pushRequestPermission'); },
      /** { enabled, permission, optedIn, subscriptionId, externalId, provider } */
      getState: function () { return call('NativeShell', 'pushGetState'); },
      /** Link this device to your user id so you can target them from your server. */
      login: function (externalId) { return call('NativeShell', 'pushLogin', { externalId: String(externalId) }); },
      logout: function () { return call('NativeShell', 'pushLogout'); },
      addTags: function (tags) { return call('NativeShell', 'pushAddTags', { tags: tags || {} }); },
      removeTags: function (keys) { return call('NativeShell', 'pushRemoveTags', { keys: [].concat(keys || []) }); },
      setEmail: function (email) { return call('NativeShell', 'pushSetEmail', { email: email }); },
      optIn: function () { return call('NativeShell', 'pushOptIn'); },
      optOut: function () { return call('NativeShell', 'pushOptOut'); },
      clearAll: function () { return call('NativeShell', 'pushClearAll'); },
      onClick: function (cb) { return on('push:click', cb); },
      onReceived: function (cb) { return on('push:received', cb); }
    },

    /**
     * Number on the app icon, e.g. outstanding to-dos.
     * Resolves { count, applied }. applied=false means notifications aren't allowed yet —
     * call NativeApp.notifications.requestPermission() first.
     * opts.text (Android only): the quiet notification text, e.g. 'You have {count} to-dos'.
     */
    badge: {
      set: function (count, opts) {
        var n = Math.max(0, parseInt(count, 10) || 0);
        return call('NativeShell', 'setBadge', { count: n, text: opts && opts.text });
      },
      clear: function () { return call('NativeShell', 'setBadge', { count: 0 }); },
      get: function () { return call('NativeShell', 'getBadge').then(function (r) { return r.count; }); }
    },

    tabs: {
      select: function (index) { return call('NativeShell', 'selectTab', { index: index }); },
      /** text: '3', '•', or '' to clear */
      setBadge: function (index, text) { return call('NativeShell', 'setTabBadge', { index: index, value: text == null ? '' : String(text) }); },
      show: function (visible) { return call('NativeShell', 'setTabsVisible', { visible: visible !== false }); }
    }
  };

  window.NativeApp = NativeApp;

  // ---------- icon badge: web Badging API + <meta name="app-badge" content="3"> ----------

  // Sites using the standard API (navigator.setAppBadge) get the native badge for free.
  try {
    navigator.setAppBadge = function (count) { return NativeApp.badge.set(count == null ? 0 : count).then(function () {}); };
    navigator.clearAppBadge = function () { return NativeApp.badge.clear().then(function () {}); };
  } catch (e) { /* read-only navigator in some webviews */ }

  // Server-rendered pages can just print the count: <meta name="app-badge" content="3">
  var lastMetaBadge = null;
  function syncMetaBadge() {
    var meta = document.querySelector('meta[name="app-badge"]');
    if (!meta) return;
    var value = meta.getAttribute('content');
    if (value === lastMetaBadge) return;
    lastMetaBadge = value;
    NativeApp.badge.set(value).catch(function () {});
  }

  // ---------- downloads ----------

  function blobToBase64(blob) {
    return new Promise(function (resolve, reject) {
      var r = new FileReader();
      r.onload = function () { resolve(String(r.result).split(',')[1] || ''); };
      r.onerror = reject;
      r.readAsDataURL(blob);
    });
  }

  function filenameFrom(url, header) {
    var m = header && /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(header);
    if (m) return decodeURIComponent(m[1]);
    try { return decodeURIComponent(new URL(url, location.href).pathname.split('/').pop()) || 'download'; } catch (e) { return 'download'; }
  }

  function downloadFile(url, filename) {
    var disposition = null;
    return fetch(url, { credentials: 'include' })
      .then(function (res) {
        if (!res.ok) throw new Error('Download failed (' + res.status + ')');
        disposition = res.headers.get('Content-Disposition');
        return res.blob();
      })
      .then(function (blob) {
        return blobToBase64(blob).then(function (data) {
          return call('NativeShell', 'saveFile', {
            data: data,
            filename: filename || filenameFrom(url, disposition),
            mimeType: blob.type || 'application/octet-stream'
          });
        });
      });
  }

  // ---------- link handling ----------

  // target=_blank / window.open: keep internal pages in the app, send external ones to openUrl.
  document.addEventListener('click', function (e) {
    if (e.defaultPrevented || e.button !== 0) return;
    var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
    if (!a) return;
    var href = a.getAttribute('href');
    if (!href || href.charAt(0) === '#' || /^javascript:/i.test(href)) return;

    if (a.hasAttribute('download') || /^(blob|data):/i.test(href)) {
      e.preventDefault();
      downloadFile(a.href, a.getAttribute('download') || undefined).catch(function (err) { console.error('[NativeApp]', err); });
      return;
    }
    if (a.target === '_blank' || a.target === '_system') {
      e.preventDefault();
      if (isInternal(a.href)) location.href = a.href;
      else NativeApp.openUrl(a.href, { mode: a.target === '_system' ? 'browser' : 'inApp' });
    }
  }, false);

  var nativeOpen = window.open;
  window.open = function (url, target, features) {
    if (!url) return nativeOpen.apply(window, arguments);
    if (target === '_self' || isInternal(url)) { location.href = absolute(url); return null; }
    NativeApp.openUrl(url, { mode: target === '_system' ? 'browser' : 'inApp' });
    return null;
  };

  // ---------- haptics on tap: <button data-haptic="light"> ----------

  document.addEventListener('click', function (e) {
    if (!cfg.autoHaptics) return;
    var el = e.target && e.target.closest ? e.target.closest('[data-haptic]') : null;
    if (el) NativeApp.haptic(el.getAttribute('data-haptic') || 'light');
  }, true);

  // ---------- pull-to-refresh guard for inner scrolling areas (Android) ----------

  var refreshBlocked = false;
  if (cfg.platform === 'android') {
    document.addEventListener('touchstart', function (e) {
      var el = e.target, blocked = false;
      while (el && el !== document.body && el !== document.documentElement) {
        if (el.scrollTop > 0) { blocked = true; break; }
        el = el.parentElement;
      }
      if (blocked !== refreshBlocked) {
        refreshBlocked = blocked;
        call('NativeShell', 'setRefreshBlocked', { blocked: blocked }).catch(function () {});
      }
    }, { passive: true, capture: true });
  }

  // ---------- links to the app's bundled pages ----------
  //
  // <a href="/shopping" data-native-href="/app/shopping/index.html"> opens the bundled (offline-capable) page
  // inside the app, and the normal page everywhere else.

  function linkBundledPages() {
    if (!cfg.localOrigin) return;
    var links = document.querySelectorAll('a[data-native-href]');
    for (var i = 0; i < links.length; i++) {
      var path = links[i].getAttribute('data-native-href');
      if (path && path.charAt(0) === '/') links[i].href = cfg.localOrigin + path;
    }
  }

  // ---------- offline: never lock the user out ----------
  //
  // offline.mode "banner" (default): the current page stays usable, a small pill says you're offline,
  // links tapped while offline open automatically once you're back. "screen": the old behaviour
  // (navigations fail to the bundled offline page). Pages can hide the pill with
  // <meta name="native-offline-banner" content="off"> and use NativeApp.on('offline'|'online') instead.

  var offline = { active: false, queued: null, pill: null, hideTimer: null };
  var bannerMode = cfg.offlineMode !== 'screen';
  var TEXT = {
    offline: cfg.offlineMessage || "You're offline. We'll reconnect automatically.",
    queued: cfg.offlineQueuedMessage || "You're offline. That will open when you're back.",
    online: cfg.onlineMessage || 'Back online'
  };

  function pillAllowed() {
    var meta = document.querySelector('meta[name="native-offline-banner"]');
    return !(meta && meta.getAttribute('content') === 'off');
  }

  function showPill(text, tone) {
    if (!bannerMode || !document.body || !pillAllowed()) return;
    if (!offline.pill) {
      var style = document.createElement('style');
      style.textContent =
        '.native-offline-pill{position:fixed;left:50%;top:calc(env(safe-area-inset-top,0px) + 10px);z-index:2147483647;' +
        'transform:translate(-50%,-140%);transition:transform .35s cubic-bezier(.2,.9,.3,1.2),background-color .3s;' +
        'display:flex;align-items:center;gap:8px;max-width:calc(100vw - 32px);padding:9px 14px;border-radius:999px;' +
        'background:rgba(17,24,39,.94);color:#fff;font:500 13px/1.3 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;' +
        'box-shadow:0 6px 24px rgba(0,0,0,.22);pointer-events:none;-webkit-font-smoothing:antialiased}' +
        '.native-offline-pill.show{transform:translate(-50%,0)}' +
        '.native-offline-pill.online{background:rgba(22,101,52,.95)}' +
        '.native-offline-pill i{flex:none;width:8px;height:8px;border-radius:50%;background:#f59e0b}' +
        '.native-offline-pill.online i{background:#4ade80}' +
        '.native-offline-pill.nudge{animation:native-pill-nudge .4s}' +
        '@keyframes native-pill-nudge{0%,100%{margin-left:0}25%{margin-left:-6px}75%{margin-left:6px}}';
      document.head.appendChild(style);
      offline.pill = document.createElement('div');
      offline.pill.className = 'native-offline-pill';
      offline.pill.setAttribute('role', 'status');
      offline.pill.setAttribute('aria-live', 'polite');
      offline.pill.innerHTML = '<i></i><span></span>';
      document.body.appendChild(offline.pill);
    }
    clearTimeout(offline.hideTimer);
    offline.pill.querySelector('span').textContent = text;
    offline.pill.classList.toggle('online', tone === 'online');
    void offline.pill.offsetWidth; // restart transitions
    offline.pill.classList.add('show');
  }

  function hidePill(delay) {
    if (!offline.pill) return;
    clearTimeout(offline.hideTimer);
    offline.hideTimer = setTimeout(function () { offline.pill.classList.remove('show'); }, delay || 0);
  }

  function nudgePill(text) {
    showPill(text, 'offline');
    if (!offline.pill) return;
    offline.pill.classList.remove('nudge');
    void offline.pill.offsetWidth;
    offline.pill.classList.add('nudge');
    NativeApp.haptic('warning');
  }

  function setOnline(isOnline) {
    var wasOffline = offline.active;
    offline.active = !isOnline;
    document.documentElement.classList.toggle('native-offline', offline.active);
    if (offline.active && !wasOffline) {
      showPill(TEXT.offline, 'offline');
      emit('offline');
    } else if (!offline.active && wasOffline) {
      emit('online');
      showPill(TEXT.online, 'online');
      hidePill(1800);
      if (offline.queued) {
        var url = offline.queued;
        offline.queued = null;
        setTimeout(function () { location.href = url; }, 350);
      }
    }
  }

  function queueWhileOffline(url) {
    offline.queued = url || null;
    nudgePill(url ? TEXT.queued : TEXT.offline);
  }

  NativeApp.isOffline = function () { return offline.active; };

  // Links tapped while offline: stay on this page, open it when the connection returns.
  // Capture phase on window, so this runs before Livewire's wire:navigate and other handlers.
  window.addEventListener('click', function (e) {
    if (!bannerMode || !offline.active || e.defaultPrevented || e.button !== 0) return;
    var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
    if (!a || a.hasAttribute('download')) return;
    var href = a.getAttribute('href');
    if (!href || href.charAt(0) === '#' || /^(javascript|mailto|tel|sms|blob|data):/i.test(href)) return;
    if (!isInternal(a.href)) return; // external links can still try the in-app browser
    e.preventDefault();
    e.stopImmediatePropagation();
    queueWhileOffline(a.href);
  }, true);

  // Classic (non-Livewire) form posts while offline: keep what was typed instead of losing the page.
  window.addEventListener('submit', function (e) {
    if (!bannerMode || !offline.active) return;
    var form = e.target;
    if (!form || form.hasAttribute('wire:submit') || form.hasAttribute('wire:submit.prevent')) return;
    e.preventDefault();
    e.stopImmediatePropagation();
    nudgePill(TEXT.offline);
  }, true);

  // ---------- boot ----------

  // The intro video has faded out (or there wasn't one): pages can animate in from here.
  //   html.native-splash-done .hero { animation: rise .6s ease both }
  var splashIsDone = false;
  function splashFinished() {
    if (splashIsDone) return;
    splashIsDone = true;
    document.documentElement.classList.add('native-splash-done');
    emit('splash:done');
  }

  function onDomReady(fn) {
    if (document.readyState !== 'loading') fn();
    else document.addEventListener('DOMContentLoaded', fn);
  }

  whenCapacitor(function (P) {
    plugins = P;

    P.NativeShell.addListener('pushClick', function (d) { emit('push:click', d); });
    P.NativeShell.addListener('pushReceived', function (d) { emit('push:received', d); });
    P.NativeShell.addListener('pushPermission', function (d) { emit('push:permission', d); });
    P.NativeShell.addListener('tabSelected', function (d) { emit('tab', d); });
    P.NativeShell.addListener('splashDone', function () { splashFinished(); });
    P.NativeShell.addListener('speech', function (d) { emit('speech', d); });
    if (P.App) {
      P.App.addListener('pause', function () { emit('pause'); });
      P.App.addListener('resume', function () { emit('resume'); });
    }
    if (P.Network) {
      P.Network.addListener('networkStatusChange', function (s) { emit('network', s); onDomReady(function () { setOnline(s.connected); }); });
      P.Network.getStatus().then(function (s) { onDomReady(function () { setOnline(s.connected); }); }).catch(function () {});
    }
    // The app blocked a navigation or reload because there's no connection.
    P.NativeShell.addListener('offlineBlocked', function (d) { onDomReady(function () { setOnline(false); queueWhileOffline(d && d.url); }); });

    onDomReady(function () {
      if (P.SplashScreen) P.SplashScreen.hide({ fadeOutDuration: 250 }).catch(function () {});

      // Pages can opt out: <meta name="native-pull-to-refresh" content="off">
      var ptr = document.querySelector('meta[name="native-pull-to-refresh"]');
      P.NativeShell.pageReady({
        url: location.href,
        title: document.title,
        pullToRefresh: ptr ? ptr.getAttribute('content') !== 'off' : null
      }).then(function (r) { if (r && r.splashDone) splashFinished(); }).catch(function () {});

      syncMetaBadge();
      linkBundledPages();
      // SPAs that update the meta tag (or swap it) keep the badge in sync.
      if (window.MutationObserver && document.head) {
        new MutationObserver(syncMetaBadge).observe(document.head, { childList: true, subtree: true, attributes: true, attributeFilter: ['content'] });
      }

      document.documentElement.classList.add('native-app', 'native-' + NativeApp.platform);
      var cbs = readyCallbacks; readyCallbacks = [];
      cbs.forEach(function (cb) { try { cb(); } catch (e) { console.error(e); } });
      emit('ready', NativeApp);
    });
  });
})();
