# Webview Shell (iOS + Android)

A native app wrapper for any web app, built on **Capacitor 8**. Set a URL in one config file and you get a proper iOS and Android app:

- Your site runs full-screen in the native WebView (WKWebView / Android System WebView)
- **Haptics** tuned per platform (Taptic Engine on iPhone, haptic primitives on Android 11+)
- **Push notifications** via OneSignal, including deep-linking from a notification into a page
- **App icon badge counts** (e.g. "3 to-dos"), set from your site or by a push while the app is closed
- **Camera, microphone, location, photo upload, file capture**: permissions and prompts are set up on both platforms
- **Intro video splash**: a full-screen video that plays while your site loads, then fades into it
- Pull-to-refresh, and an offline screen with auto-retry
- External links open in an in-app browser; `tel:`, `mailto:`, maps and WhatsApp links open the right app
- Downloads (normal links, `<a download>`, `blob:` and `data:` URLs)
- Deep links (`myapp://…`) and universal / app links (`https://yourdomain/…`)
- An optional native bottom tab bar
- A `window.NativeApp` JavaScript API so your site can use all of the above

```
app.config.json          ← the only file you normally edit
capacitor.config.js      ← generated from app.config.json
scripts/configure.mjs    ← applies app.config.json to the iOS/Android projects
www/                     ← bundled pages: offline screen + the injected bridge (native-shell.js)
plugins/native-shell/    ← the native code (Swift + Java): haptics, push, links, tabs, permissions…
web-sdk/native-app.d.ts  ← TypeScript types for your web apps
examples/demo.html       ← upload to your site to test every feature on a phone
well-known/              ← generated files to upload to your site for universal/app links
ios/  android/           ← native projects (open in Xcode / Android Studio)
```

---

## 1. Requirements

| | |
|---|---|
| Node 20+ | `node -v` |
| **iOS** | A Mac with Xcode 16+, plus an Apple Developer account for devices and the App Store |
| **Android** | Android Studio (latest), which bundles the **JDK 21** that Capacitor 8 requires |

## 2. Configure your app

Edit **`app.config.json`**:

| Key | What it does |
|---|---|
| `appId` | Bundle ID / package name, e.g. `uk.co.dotlogics.myclient`. **Set this before your first store upload; it can't change afterwards.** |
| `appName` | Name under the icon |
| `version`, `build` | Store version (`1.2.0`) and build number (bump it for every upload) |
| `startUrl` | The URL the app opens |
| `internalHosts` | Domains that stay **inside** the app (wildcards like `*.example.com` work). Everything else counts as external. |
| `trustedOrigins` | Extra origins (beyond `startUrl`'s) that get the `NativeApp` API, e.g. `https://app.example.com` |
| `externalLinks` | `inApp` (Safari View / Chrome Custom Tab), `browser` (system browser) or `webview` (load in the app) |
| `userAgentSuffix` | Added to the user agent, so your server can detect the app (`DLWebView`) |
| `theme.backgroundColor` | Background while loading, and the tab bar background |
| `theme.accentColor` | Pull-to-refresh spinner, active tab, in-app browser buttons |
| `theme.statusBarStyle` | `dark` = dark status-bar icons (for light sites), `light` = light icons |
| `features.pullToRefresh` | Pull down to reload |
| `features.autoHaptics` | Haptics on `data-haptic` elements, tab taps and pull-to-refresh |
| `features.zoom` | Allow pinch-zoom |
| `features.debug` | Enables Safari / Chrome remote inspection of the WebView |
| `notifications.oneSignalAppId` | Your OneSignal App ID (leave empty to disable push) |
| `badge.androidText` | Text of the quiet Android notification that carries the icon count (`{count}` is replaced) |
| `notifications.promptOnLaunch` | Ask for notification permission at launch. It's better to leave this `false` and ask from your site at a good moment. |
| `orientation` | `portrait` or `all` |
| `deepLinks.scheme` | Custom scheme, so `myapp://orders/12` opens `startUrl` origin + `/orders/12` |
| `deepLinks.hosts` | Domains whose links open the app (universal links / app links) |
| `deepLinks.appleTeamId`, `androidSha256` | Used to generate the `well-known/` files (see section 7) |
| `tabs` | Optional native tab bar; see below |
| `permissions.*` | The text iOS shows when asking for camera / mic / photos / location. **Apple rejects vague wording**, so describe what *your* app does. |

### Tab bar (optional)

```json
"tabs": [
  { "title": "Home",    "icon": "home",     "url": "/" },
  { "title": "Orders",  "icon": "list",     "url": "/orders" },
  { "title": "Alerts",  "icon": "bell",     "url": "/notifications" },
  { "title": "Account", "icon": "user",     "url": "/account" }
]
```

Icons: `home search bell user account settings cart bag heart star calendar chat messages menu grid info list map camera plus bookmark play`. On iOS you can also use any SF Symbol name. The active tab follows the current URL automatically. Leave `"tabs": []` for no tab bar.

### Intro video splash

```json
"splash": {
  "video": "resources/splash.mp4",
  "backgroundColor": "#0b0f17",
  "statusBarStyle": "auto",
  "play": "everyLaunch",
  "skippable": true,
  "minDuration": 1.5,
  "maxWait": 8
}
```

A full-screen, muted video plays the moment the app opens while your site loads behind it. When the video has finished **and** the page is ready, it fades away. If the site is slow it holds the last frame, up to `maxWait` seconds. `play: "firstLaunch"` shows it only the first time the app is opened. Tap to skip if `skippable`. It never interrupts the user's music, and it's skipped when the phone's "reduce motion / remove animations" setting is on. Set `"video": ""` for a normal static splash. While it plays, the status and navigation bar icons switch to suit the splash colour (`statusBarStyle`: `auto`, `light` = white icons, or `dark`). They switch back when the page appears.

- **The video file:** 2–4 seconds, portrait 1080×1920, H.264 `.mp4`, ideally under 5 MB. It's cropped to fill any screen, so keep logos away from the edges. Make the last frame a clean resting frame (e.g. just the logo), because it's held while the page finishes loading.
- **iOS:** make `resources/splash.png` the video's **first frame** and run `npm run assets`, so the system launch image hands off to the video seamlessly.
- **Android 12+** always shows a brief system splash with the app icon first. Its background is set to `splash.backgroundColor` so it blends in.
- **Animating your site in afterwards:** `<html>` gets the class `native-splash-done`, and `NativeApp.on('splash:done', …)` fires:
  ```css
  html.native-app .hero { opacity: 0; transform: translateY(12px); transition: all .6s ease; }
  html.native-splash-done .hero { opacity: 1; transform: none; }
  ```

### Haptics strength

`"haptics": { "intensity": "subtle" | "normal" | "strong" }`. Phones with good haptic motors (iPhone, Pixel, Galaxy S) use their crisp built-in effects. Budget Android phones whose motors don't support those effects (e.g. Galaxy A-series) get hand-tuned pulses instead, and this setting scales how strong those feel.

### App icon and splash screen

Put a 1024×1024 `resources/icon.png` and a 2732×2732 `resources/splash.png` in this folder, then run:

```bash
npm run assets
```

## 3. Build and run

After **any** change to `app.config.json` or `www/`:

```bash
npm run sync
```

**iOS (on the Mac):**

```bash
npm install
npm run ios
```

In Xcode: select the **App** target → **Signing & Capabilities** → pick your Team. Check that **Push Notifications**, **Background Modes → Remote notifications** and **Associated Domains** are listed (they come from `App/App.entitlements`). Then press ▶ on a device or simulator. Push only works on a real device.

**Android:**

```bash
npm run android
```

Android Studio opens the project. Press ▶ to run it on a device or emulator.

For store builds use Xcode's **Product → Archive** and Android Studio's **Build → Generate Signed App Bundle**.

## 4. OneSignal setup

1. Create an app at [onesignal.com](https://onesignal.com) → **Settings → Push & In-App**.
2. **Apple (APNs):** upload a `.p8` auth key (Apple Developer → Keys → **+** → Apple Push Notifications service) with your Key ID, Team ID and bundle ID.
3. **Google (FCM):** create a Firebase project, then in Firebase → Project settings → Service accounts → **Generate new private key**, and upload that JSON to OneSignal. You do **not** need `google-services.json` in the app; OneSignal handles registration.
4. Copy the **OneSignal App ID** into `notifications.oneSignalAppId` and run `npm run sync`.

**Notification → page:** in OneSignal, add **Additional Data** `url` = `/orders/123` (or a full URL). Tapping the notification opens that page inside the app. A **Launch URL** works too: the app opens it in the WebView instead of a browser.

**Targeting users:** after login on your site, call `NativeApp.notifications.login(user.id)`. You can then send to that user from your server with the OneSignal REST API, using `include_aliases: { external_id: ["123"] }`.

**Rich notifications on iOS (optional):** images and confirmed-delivery stats need a *Notification Service Extension*. In Xcode: **File → New → Target → Notification Service Extension**, then follow OneSignal's iOS SDK setup guide (add `OneSignalExtension` to that target and an App Group). Plain notifications work without it.

## 5. Using native features from your website

The app injects `window.NativeApp` into pages on `startUrl`'s origin (plus `trustedOrigins`). In a normal browser it is `undefined`, so guard calls:

```js
NativeApp?.haptic('success');

if (window.NativeApp) {
  NativeApp.ready(() => {
    NativeApp.notifications.login(currentUser.id);
    NativeApp.notifications.addTags({ plan: currentUser.plan });
  });
}
```

You can also detect the app with CSS: `<html>` gets the classes `native-app native-ios` / `native-android`. Server side, check for `DLWebView` in the user agent.

### Haptics

```js
NativeApp.haptic('light' | 'medium' | 'heavy' | 'soft' | 'rigid' | 'selection' | 'success' | 'warning' | 'error');
```

No JS needed: `<button data-haptic="medium">Save</button>`

### Push notifications

```js
await NativeApp.notifications.requestPermission();   // { granted }
await NativeApp.notifications.getState();            // { permission, optedIn, subscriptionId, externalId, … }
NativeApp.notifications.login('user-123');           // link device ↔ your user
NativeApp.notifications.logout();
NativeApp.notifications.addTags({ plan: 'pro' });
NativeApp.notifications.removeTags(['plan']);
NativeApp.notifications.setEmail('a@b.com');
NativeApp.notifications.optOut() / optIn();
NativeApp.notifications.onClick(n => …);    // the app has already navigated to n.url
NativeApp.notifications.onReceived(n => …); // arrived while the app was open
```

### Icon badge (e.g. "3 to-dos")

Show a number on the app icon. Pick whichever fits your app; all three do the same thing:

```js
// 1. JavaScript, whenever the count changes
NativeApp.badge.set(todos.filter(t => !t.done).length);
NativeApp.badge.clear();
await NativeApp.badge.get();

// 2. The standard web Badging API also works, so existing PWA code needs no changes
navigator.setAppBadge(3);
navigator.clearAppBadge();
```

```php
<!-- 3. No JavaScript: print the count in the page <head> (re-read on every page and when the tag changes) -->
<meta name="app-badge" content="<?= (int) $openTodoCount ?>">
```

`badge.set()` resolves `{ count, applied }`. If `applied` is `false`, the user hasn't allowed notifications yet: iOS and Android 13+ only show badges for apps with notification permission. Call `NativeApp.notifications.requestPermission()`, then set it again.

**Updating it while the app is closed:** send a push from your server whenever the count changes. Add the iOS badge fields plus a `badge` value in the data:

```bash
curl -X POST https://api.onesignal.com/notifications \
  -H "Authorization: Key YOUR_REST_API_KEY" -H "Content-Type: application/json" \
  -d '{
    "app_id": "YOUR_APP_ID",
    "include_aliases": { "external_id": ["123"] },
    "target_channel": "push",
    "headings": { "en": "New to-do" },
    "contents": { "en": "Approve the March invoice" },
    "ios_badgeType": "SetTo",
    "ios_badgeCount": 3,
    "data": { "badge": 3, "url": "/todos" }
  }'
```

- **iOS:** an actual number on the icon. It stays until your app changes it; opening the app does not clear it.
- **Android:** there's no system API for icon numbers, so the app posts one quiet, silent notification ("You have 3 things to do", set by `badge.androidText` in `app.config.json`, or `badge.set(3, { text: '…' })`). Samsung, Xiaomi, OnePlus, Huawei and Nova launchers show its number on the icon; Pixel and stock launchers show a dot. Setting the badge to 0 removes that notification. A push with `"data": { "badge": n }` updates it even when the app is closed.
- For a custom small icon on that Android notification, add `android/app/src/main/res/drawable/ic_stat_badge.png` (white on transparent).

### Permissions (camera, microphone, location…)

Your site's normal web APIs just work: `getUserMedia()`, `<input type="file" capture>`, `navigator.geolocation`. The OS prompt appears the first time. To ask up front, or to check:

```js
await NativeApp.permissions.request(['camera', 'microphone']); // { camera: 'granted', microphone: 'denied' }
await NativeApp.permissions.check();
NativeApp.permissions.openSettings(); // if the user said no earlier
```

### Everything else

```js
NativeApp.share({ title, text, url });
NativeApp.openUrl('https://…', { mode: 'inApp' | 'browser' });
NativeApp.download(url, 'report.pdf');   // also automatic for <a download> and blob: links
NativeApp.copy('text'); await NativeApp.paste();
NativeApp.device.info();                 // model, OS, app version, device id…
NativeApp.network.status();
NativeApp.statusBar.setStyle('light' | 'dark');
NativeApp.keepAwake(true);
NativeApp.pullToRefresh(false);          // or <meta name="native-pull-to-refresh" content="off">
NativeApp.tabs.select(2); NativeApp.tabs.setBadge(2, '5'); NativeApp.tabs.show(false);
NativeApp.reload();
NativeApp.vibrate(500);

NativeApp.on('resume', …); NativeApp.on('pause', …); NativeApp.on('network', s => …); NativeApp.on('tab', t => …);
```

Full types are in `web-sdk/native-app.d.ts`.

### Bundled pages that work with no signal

Some screens need to work with no signal at all, like a shopping list in a supermarket basement. Put them in the app itself, under `www/` (e.g. `www/app/shopping/index.html`). They're served from inside the app, and the bridge and every plugin work there just like on your site.

- **Link to one from your site:** `<a href="/shopping" data-native-href="/app/shopping/index.html">`. Inside the app it opens the bundled page; in a browser it's a normal link.
- **Share data between the site and bundled pages** with `NativeApp.storage.get/set/getJSON/setJSON/remove`. It's stored on the phone and, unlike `localStorage`, shared across both. A typical use: your signed-in site stores an API token there, and the bundled page uses it to sync.
- **Offer them on the offline screen:** `"offline": { "links": [{ "title": "Open shopping list", "path": "/app/shopping/index.html" }] }`.
- **Remember to use full paths** to `index.html` (Android doesn't map folders to `index.html` for bundled pages).

Kitchen HQ's offline shopping list is the worked example: `KitchenHQ-App/www/app/shopping/` and `KitchenHQ/docs/OFFLINE-SHOPPING.md`.

### Tips for your web app

- Use `<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">` and pad fixed headers and footers with `env(safe-area-inset-top)` / `env(safe-area-inset-bottom)`.
- Add `-webkit-tap-highlight-color: transparent` and `overscroll-behavior-y: none` (if you use your own scroll containers) so it feels native.
- Upload `examples/demo.html` to your site and set `startUrl` to it to try every feature on a phone.

## 6. How links behave

| Link | Result |
|---|---|
| Same site / `internalHosts` | Loads in the app |
| Other `https://` sites | In-app browser (`externalLinks`) |
| `target="_blank"` / `window.open` | Internal: same view. External: in-app browser. `target="_system"` uses the system browser |
| iframes (YouTube, Stripe, maps…) | Load normally |
| `tel:` `mailto:` `sms:` `maps:` `whatsapp:` `intent:` | Open the matching app |
| `myapp://path` | Opens `path` in the app |

## 7. Universal links / App Links

`npm run sync` writes two files to `well-known/`. Upload both to **`https://<each deepLinks host>/.well-known/`**, served as `application/json` with no redirects:

- `apple-app-site-association`: set `deepLinks.appleTeamId` first (Apple Developer → Membership).
- `assetlinks.json`: set `deepLinks.androidSha256` to your signing certificate's SHA-256. Find it in Play Console → **App integrity → App signing**, or run `keytool -list -v -keystore your.keystore`.

## 8. Store review notes

- **Apple guideline 4.2** (minimum functionality) rejects apps that are just a website. Push, haptics, native tabs, offline handling and share support help a lot. Make sure the site itself is mobile-first and app-like, and describe the native features in the review notes.
- Give Apple a demo login if your app needs an account.
- Account deletion must be possible from inside the app if users can sign up in it.
- Android 13+ asks for notification permission at runtime; the app handles this.

## 9. Updating

```bash
npm install @capacitor/core@latest @capacitor/cli@latest @capacitor/ios@latest @capacitor/android@latest
npm run sync
```

Your site's content updates whenever you deploy it; you only need a new store build when you change `app.config.json`, icons or native code.
