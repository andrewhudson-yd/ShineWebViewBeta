#!/usr/bin/env node
// Applies app.config.json to the native iOS and Android projects.
// Safe to run repeatedly — `npm run sync` runs it before `cap sync`.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(import.meta.url);
const app = require('./load-config.js').loadAppConfig();
const deep = app.deepLinks || {};
const hosts = (deep.hosts || []).filter(Boolean);
const scheme = (deep.scheme || '').trim();
const perms = app.permissions || {};
const portrait = app.orientation === 'portrait';

const changed = [];
function write(file, content) {
  const full = path.join(root, file);
  const before = fs.existsSync(full) ? fs.readFileSync(full, 'utf8') : null;
  if (before === content) return;
  fs.mkdirSync(path.dirname(full), { recursive: true });
  fs.writeFileSync(full, content);
  changed.push(file);
}
const read = (file) => fs.readFileSync(path.join(root, file), 'utf8');
const exists = (file) => fs.existsSync(path.join(root, file));
const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

if (!/^[a-zA-Z][a-zA-Z0-9]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$/.test(app.appId)) {
  console.error(`✖ appId "${app.appId}" is not a valid bundle id (e.g. uk.co.company.app)`);
  process.exit(1);
}
try { new URL(app.startUrl); } catch { console.error(`✖ startUrl "${app.startUrl}" is not a valid URL`); process.exit(1); }

// ---------------------------------------------------------------- iOS

/** Minimal plist editing: replaces or inserts top-level keys of the root <dict>. */
function valueEnd(xml, start) {
  const open = /<(\w+)(\s*\/)?>/y;
  open.lastIndex = start;
  const m = open.exec(xml);
  if (!m) throw new Error('Unexpected plist structure near ' + xml.slice(start, start + 40));
  if (m[2]) return open.lastIndex;
  const tag = m[1];
  const re = new RegExp(`<(/?)${tag}(\\s*/)?>`, 'g');
  re.lastIndex = open.lastIndex;
  let depth = 1, t;
  while ((t = re.exec(xml))) {
    if (t[2]) continue;
    depth += t[1] ? -1 : 1;
    if (depth === 0) return re.lastIndex;
  }
  throw new Error('Unbalanced plist <' + tag + '>');
}
function plistRemove(xml, key) {
  const re = new RegExp(`\\s*<key>${key}</key>\\s*`, 'g');
  let m;
  while ((m = re.exec(xml))) {
    const end = valueEnd(xml, re.lastIndex);
    xml = xml.slice(0, m.index) + xml.slice(end);
    re.lastIndex = m.index;
  }
  return xml;
}
function plistValue(v, indent = '\t') {
  if (v === true) return '<true/>';
  if (v === false) return '<false/>';
  if (typeof v === 'number') return `<integer>${v}</integer>`;
  if (Array.isArray(v)) return `<array>\n${v.map((x) => `${indent}\t${plistValue(x, indent + '\t')}`).join('\n')}\n${indent}</array>`;
  if (v && typeof v === 'object') {
    const body = Object.entries(v).map(([k, x]) => `${indent}\t<key>${k}</key>\n${indent}\t${plistValue(x, indent + '\t')}`).join('\n');
    return `<dict>\n${body}\n${indent}</dict>`;
  }
  return `<string>${esc(v)}</string>`;
}
function plistSet(xml, key, value) {
  xml = plistRemove(xml, key);
  if (value === undefined || value === null || value === '') return xml;
  const close = xml.lastIndexOf('</dict>');
  return xml.slice(0, close) + `\t<key>${key}</key>\n\t${plistValue(value)}\n` + xml.slice(close);
}

if (exists('ios/App/App/Info.plist')) {
  let plist = read('ios/App/App/Info.plist');
  plist = plistSet(plist, 'CFBundleDisplayName', app.appName);
  plist = plistSet(plist, 'NSCameraUsageDescription', perms.camera);
  plist = plistSet(plist, 'NSMicrophoneUsageDescription', perms.microphone);
  plist = plistSet(plist, 'NSPhotoLibraryUsageDescription', perms.photoLibrary);
  plist = plistSet(plist, 'NSPhotoLibraryAddUsageDescription', perms.photoLibraryAdd);
  plist = plistSet(plist, 'NSLocationWhenInUseUsageDescription', perms.location);
  plist = plistSet(plist, 'NSMotionUsageDescription', perms.motion);
  plist = plistSet(plist, 'UIBackgroundModes', ['remote-notification']);
  plist = plistSet(plist, 'OneSignal_suppress_launch_urls', true);
  // Keep the icon badge your app sets (OneSignal would otherwise clear it whenever the app opens).
  plist = plistSet(plist, 'OneSignal_disable_badge_clearing', true);
  plist = plistSet(plist, 'ITSAppUsesNonExemptEncryption', false);
  // theme.statusBarStyle: "light" = white icons (dark sites), "dark" = dark icons (light sites).
  plist = plistSet(plist, 'UIStatusBarStyle', app.theme.statusBarStyle === 'light' ? 'UIStatusBarStyleLightContent' : 'UIStatusBarStyleDarkContent');
  plist = plistSet(plist, 'CFBundleURLTypes', scheme ? [{ CFBundleURLName: app.appId, CFBundleURLSchemes: [scheme] }] : null);
  plist = plistSet(plist, 'UISupportedInterfaceOrientations', portrait
    ? ['UIInterfaceOrientationPortrait']
    : ['UIInterfaceOrientationPortrait', 'UIInterfaceOrientationLandscapeLeft', 'UIInterfaceOrientationLandscapeRight']);
  // The window is created in SceneDelegate.swift; a storyboard would create a second web view.
  plist = plistRemove(plist, 'UISceneStoryboardFile');
  plist = plistRemove(plist, 'UIMainStoryboardFile');
  plist = plistRemove(plist, 'UIRequiredDeviceCapabilities');
  write('ios/App/App/Info.plist', plist);

  const entitlements = { 'aps-environment': 'development' };
  if (hosts.length) entitlements['com.apple.developer.associated-domains'] = hosts.map((h) => `applinks:${h}`);
  write('ios/App/App/App.entitlements',
    '<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">\n' +
    `<plist version="1.0">\n${plistValue(entitlements, '')}\n</plist>\n`);

  let pbx = read('ios/App/App.xcodeproj/project.pbxproj');
  pbx = pbx.replace(/buildSettings = \{([^}]*INFOPLIST_FILE = App\/Info\.plist;[^}]*)\}/g, (all, body) => {
    const set = (s, k, v) => (new RegExp(`\\n(\\s*)${k} = [^;]*;`).test(s)
      ? s.replace(new RegExp(`\\n(\\s*)${k} = [^;]*;`), `\n$1${k} = ${v};`)
      : s.replace(/\n(\s*)INFOPLIST_FILE/, `\n$1${k} = ${v};\n$1INFOPLIST_FILE`));
    body = set(body, 'PRODUCT_BUNDLE_IDENTIFIER', app.appId);
    body = set(body, 'MARKETING_VERSION', app.version || '1.0.0');
    body = set(body, 'CURRENT_PROJECT_VERSION', String(app.build || 1));
    body = set(body, 'CODE_SIGN_ENTITLEMENTS', 'App/App.entitlements');
    body = set(body, 'TARGETED_DEVICE_FAMILY', '"1,2"');
    if (deep.appleTeamId) body = set(body, 'DEVELOPMENT_TEAM', deep.appleTeamId);
    return `buildSettings = {${body}}`;
  });
  write('ios/App/App.xcodeproj/project.pbxproj', pbx);
}

// ---------------------------------------------------------------- Android

if (exists('android/app/src/main/AndroidManifest.xml')) {
  let strings = read('android/app/src/main/res/values/strings.xml');
  const setString = (name, value) => {
    strings = strings.replace(new RegExp(`<string name="${name}">[^<]*</string>`), `<string name="${name}">${esc(value)}</string>`);
  };
  setString('app_name', app.appName);
  setString('title_activity_main', app.appName);
  setString('package_name', app.appId);
  setString('custom_url_scheme', scheme || app.appId);
  write('android/app/src/main/res/values/strings.xml', strings);

  let manifest = read('android/app/src/main/AndroidManifest.xml');
  const START = '<!-- native-shell:deeplinks -->';
  const END = '<!-- /native-shell:deeplinks -->';
  manifest = manifest.replace(new RegExp(`\\s*${START}[\\s\\S]*?${END}`), '');
  let filters = '';
  if (scheme) {
    filters += `
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="${esc(scheme)}" />
            </intent-filter>`;
  }
  if (hosts.length) {
    filters += `
            <intent-filter android:autoVerify="true">
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="https" />
${hosts.map((h) => `                <data android:host="${esc(h)}" />`).join('\n')}
            </intent-filter>`;
  }
  manifest = manifest.replace(
    /(<category android:name="android\.intent\.category\.LAUNCHER" \/>\s*<\/intent-filter>)/,
    `$1\n\n            ${START}${filters}\n            ${END}`
  );
  manifest = manifest.replace(/\s*android:screenOrientation="[^"]*"/, '');
  if (portrait) manifest = manifest.replace(/android:name="\.MainActivity"/, 'android:name=".MainActivity"\n            android:screenOrientation="portrait"');
  // Plain http:// start URLs (local testing) need cleartext traffic allowed.
  manifest = manifest.replace(/\s*android:usesCleartextTraffic="[^"]*"/, '');
  if (new URL(app.startUrl).protocol === 'http:') {
    manifest = manifest.replace(/<application\b/, '<application\n        android:usesCleartextTraffic="true"');
  }
  write('android/app/src/main/AndroidManifest.xml', manifest);

  let gradle = read('android/app/build.gradle');
  gradle = gradle
    .replace(/applicationId "[^"]*"/, `applicationId "${app.appId}"`)
    .replace(/versionCode \d+/, `versionCode ${parseInt(app.build, 10) || 1}`)
    .replace(/versionName "[^"]*"/, `versionName "${app.version || '1.0.0'}"`);
  write('android/app/build.gradle', gradle);
}

// ---------------------------------------------------------------- Intro video splash

{
  const splash = app.splash || {};
  const dir = path.join(root, 'www', 'splash');
  const wanted = splash.video ? path.basename(splash.video) : null;
  if (fs.existsSync(dir)) {
    for (const f of fs.readdirSync(dir)) if (f !== wanted) { fs.rmSync(path.join(dir, f)); changed.push('www/splash/' + f + ' (removed)'); }
  }
  if (wanted) {
    const src = path.join(root, splash.video);
    if (!fs.existsSync(src)) {
      console.warn(`⚠ splash.video "${splash.video}" not found — the app will start without the intro video`);
    } else {
      const dest = path.join(dir, wanted);
      const same = fs.existsSync(dest) && fs.statSync(dest).size === fs.statSync(src).size && fs.statSync(dest).mtimeMs >= fs.statSync(src).mtimeMs;
      if (!same) {
        fs.mkdirSync(dir, { recursive: true });
        fs.copyFileSync(src, dest);
        changed.push('www/splash/' + wanted);
      }
      const mb = fs.statSync(src).size / 1048576;
      if (mb > 6) console.warn(`⚠ splash video is ${mb.toFixed(1)} MB — aim for under 5 MB (2–4 s, H.264 MP4)`);
    }
  }

  // Android 12+ always shows a system splash (app icon) first — match its background to the video.
  const stylesFile = 'android/app/src/main/res/values/styles.xml';
  if (exists(stylesFile)) {
    const bg = splash.backgroundColor || (app.theme && app.theme.backgroundColor) || '#ffffff';
    write('android/app/src/main/res/values/ns_splash.xml',
      `<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ns_splash_background">${bg}</color>\n</resources>\n`);
    let styles = read(stylesFile);
    if (!styles.includes('windowSplashScreenBackground')) {
      styles = styles.replace(
        /(<style name="AppTheme\.NoActionBarLaunch" parent="Theme\.SplashScreen">)/,
        '$1\n        <item name="windowSplashScreenBackground">@color/ns_splash_background</item>'
      );
      write(stylesFile, styles);
    }
  }
}

// ---------------------------------------------------------------- Files for your web server

if (hosts.length) {
  const teamId = deep.appleTeamId || 'YOUR_TEAM_ID';
  write('well-known/apple-app-site-association', JSON.stringify({
    applinks: { details: [{ appIDs: [`${teamId}.${app.appId}`], components: [{ '/': '*' }] }] },
    webcredentials: { apps: [`${teamId}.${app.appId}`] }
  }, null, 2) + '\n');
  const prints = (deep.androidSha256 || []).length ? deep.androidSha256 : ['YOUR:SHA256:SIGNING:CERT:FINGERPRINT'];
  write('well-known/assetlinks.json', JSON.stringify([{
    relation: ['delegate_permission/common.handle_all_urls', 'delegate_permission/common.get_login_creds'],
    target: { namespace: 'android_app', package_name: app.appId, sha256_cert_fingerprints: prints }
  }], null, 2) + '\n');
}

console.log(changed.length ? `✔ configured: ${changed.join(', ')}` : '✔ native projects already match app.config.json');
