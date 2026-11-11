// Generated from app.config.json (+ app.config.local.json) — edit those, not this file.
const { loadAppConfig } = require('./scripts/load-config');
const app = loadAppConfig();

const startUrl = new URL(app.startUrl);
const splash = app.splash || {};
// The intro video is copied into the app by scripts/configure.mjs.
const path = require('path');
const splashVideo = splash.video && require('fs').existsSync(path.join(__dirname, splash.video))
  ? 'splash/' + path.basename(splash.video)
  : '';
// "app" (default): the app keeps pages clear of the status bar / notch / home bar.
// "web": edge-to-edge; your CSS handles env(safe-area-inset-*) itself.
const safeAreaApp = (app.theme.safeArea || 'app') !== 'web';
const debug = !!app.features.debug || process.env.APP_DEBUG === '1';

/** @type {import('@capacitor/cli').CapacitorConfig} */
module.exports = {
  appId: app.appId,
  appName: app.appName,
  webDir: 'www',
  backgroundColor: app.theme.backgroundColor,
  appendUserAgent: app.userAgentSuffix,
  zoomEnabled: !!app.features.zoom,
  loggingBehavior: debug ? 'debug' : 'production',

  server: {
    url: app.startUrl,
    cleartext: startUrl.protocol === 'http:',
    allowNavigation: app.internalHosts,
    errorPath: 'offline.html',
  },

  android: {
    // Only for local testing against a plain-http server; production start URLs are https.
    allowMixedContent: startUrl.protocol === 'http:',
    captureInput: true,
    webContentsDebuggingEnabled: debug,
  },

  ios: {
    contentInset: safeAreaApp ? 'never' : 'automatic',
    allowsLinkPreview: !!app.features.linkPreview,
    webContentsDebuggingEnabled: debug,
    preferredContentMode: 'mobile',
  },

  plugins: {
    SystemBars: {
      insetsHandling: safeAreaApp ? 'disable' : 'css',
      style: app.theme.statusBarStyle === 'light' ? 'DARK' : 'LIGHT',
    },
    SplashScreen: {
      // With an intro video the native video takes over straight away; otherwise native-shell.js
      // hides this as soon as the page is ready (8 s is only the fallback).
      launchAutoHide: true,
      launchShowDuration: splashVideo ? 0 : 8000,
      backgroundColor: splash.backgroundColor || app.theme.backgroundColor,
      showSpinner: false,
      androidScaleType: 'CENTER_CROP',
    },
    CapacitorCookies: { enabled: true },
    // Settings for any extra Capacitor plugins this app installs (app.config.json → capacitorPlugins).
    ...(app.capacitorPlugins || {}),
    NativeShell: {
      startUrl: app.startUrl,
      internalHosts: app.internalHosts,
      trustedOrigins: [startUrl.origin, ...(app.trustedOrigins || [])],
      externalLinks: app.externalLinks,
      accentColor: app.theme.accentColor,
      backgroundColor: app.theme.backgroundColor,
      pullToRefresh: !!app.features.pullToRefresh,
      autoHaptics: !!app.features.autoHaptics,
      oneSignalAppId: app.notifications.oneSignalAppId || '',
      promptOnLaunch: !!app.notifications.promptOnLaunch,
      deepLinkScheme: app.deepLinks.scheme,
      badgeText: (app.badge && app.badge.androidText) || '',
      safeArea: safeAreaApp ? 'app' : 'web',
      statusBarStyle: app.theme.statusBarStyle === 'light' ? 'light' : 'dark',
      offline: {
        mode: (app.offline && app.offline.mode) || 'banner',
        message: (app.offline && app.offline.message) || '',
        queuedMessage: (app.offline && app.offline.queuedMessage) || '',
        onlineMessage: (app.offline && app.offline.onlineMessage) || '',
        // Buttons on the offline screen for bundled pages that work without signal: [{ title, path }]
        links: (app.offline && app.offline.links) || [],
      },
      // false when the website handles deep links itself (App.addListener('appUrlOpen')).
      nativeDeepLinks: app.deepLinks.handledBy !== 'web',
      hapticIntensity: (app.haptics && app.haptics.intensity) || 'normal',
      tabs: app.tabs || [],
      splash: {
        video: splashVideo,
        backgroundColor: splash.backgroundColor || app.theme.backgroundColor,
        statusBarStyle: splash.statusBarStyle || 'auto',
        play: splash.play || 'everyLaunch',
        skippable: splash.skippable !== false,
        minDuration: splash.minDuration || 0,
        maxWait: splash.maxWait || 8,
      },
    },
  },
};
