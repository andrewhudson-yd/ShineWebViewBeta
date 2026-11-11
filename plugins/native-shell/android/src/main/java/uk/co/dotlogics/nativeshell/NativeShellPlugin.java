package uk.co.dotlogics.nativeshell;

import android.Manifest;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.browser.customtabs.CustomTabColorSchemeParams;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Logger;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.WebViewListener;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

@CapacitorPlugin(
    name = "NativeShell",
    permissions = {
        @Permission(alias = "camera", strings = { Manifest.permission.CAMERA }),
        @Permission(alias = "microphone", strings = { Manifest.permission.RECORD_AUDIO }),
        @Permission(alias = "location", strings = { Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION }),
        @Permission(alias = "notifications", strings = { Manifest.permission.POST_NOTIFICATIONS })
    }
)
public class NativeShellPlugin extends Plugin implements ShellWebViewClient.UrlListener, ShellPush.Listener {

    private static final String[] RUNTIME_ALIASES = { "camera", "microphone", "location" };

    private ShellSettings settings;
    private ShellHaptics haptics;
    private ShellPush push;
    private ShellSpeech speech;
    private ShellBadge badge;
    private SwipeRefreshLayout refreshLayout;
    private ShellTabBar tabBar;
    private final Handler main = new Handler(Looper.getMainLooper());

    private boolean refreshEnabled = true;
    private Boolean pageRefreshOverride = null;
    private volatile boolean refreshBlocked = false;
    private boolean ready = false;
    private Uri pendingUrl;
    private Uri lastUrl;
    private String bootScript;
    private ShellVideoSplash videoSplash;
    private volatile boolean splashDone = false;
    private volatile boolean online = true;
    private boolean pageShown = false;
    private volatile boolean onBundledPage = false;

    // region Lifecycle

    @Override
    public void load() {
        settings = new ShellSettings(getConfig());
        haptics = new ShellHaptics(getContext(), settings.hapticIntensity);
        push = new ShellPush(getContext());
        speech = new ShellSpeech(getContext(), new ShellSpeech.Listener() {
            @Override public void onSpeechResult(String text, boolean isFinal) {
                JSObject data = new JSObject();
                data.put("type", "result");
                data.put("text", text);
                data.put("final", isFinal);
                notifyListeners("speech", data);
            }
            @Override public void onSpeechLevel(float level) {
                JSObject data = new JSObject();
                data.put("type", "level");
                data.put("level", level);
                notifyListeners("speech", data);
            }
            @Override public void onSpeechStopped() {
                JSObject data = new JSObject();
                data.put("type", "stopped");
                notifyListeners("speech", data);
            }
            @Override public void onSpeechError(String message) {
                JSObject data = new JSObject();
                data.put("type", "error");
                data.put("message", message);
                notifyListeners("speech", data);
            }
        });
        badge = new ShellBadge(getContext());
        badge.setDefaultText(settings.badgeText);
        refreshEnabled = settings.pullToRefresh;

        WebView webView = bridge.getWebView();
        bridge.setWebViewClient(new ShellWebViewClient(bridge, this));
        webView.setDownloadListener(this::onDownload);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);

        injectBridgeScript(webView);
        // After every plugin has registered, so the bridge script lists them all.
        main.post(() -> injectCapacitorIntoBundledPages(webView));
        wrapWebView(webView);
        applyThemeBarIcons();
        showVideoSplash();
        watchConnectivity();

        bridge.addWebViewListener(
            new WebViewListener() {
                @Override
                public void onPageStarted(WebView view) {
                    markReady();
                }

                @Override
                public void onPageLoaded(WebView view) {
                    pageShown = true;
                    if (refreshLayout != null) refreshLayout.setRefreshing(false);
                    if (bootScript != null) view.evaluateJavascript(bootScript, null);
                    if (videoSplash != null) videoSplash.pageReady();
                }

                @Override
                public void onReceivedError(WebView view) {
                    if (refreshLayout != null) refreshLayout.setRefreshing(false);
                    if (videoSplash != null) videoSplash.pageReady();
                }
            }
        );

        push.start(settings.oneSignalAppId, this);
        handleIntent(getActivity().getIntent());

        if (settings.promptOnLaunch && push.isEnabled()) {
            main.postDelayed(() -> push.requestPermission(granted -> {}), 1500);
        }
    }

    /** theme.statusBarStyle: "light" = white status/nav bar icons (dark sites), "dark" = dark icons. */
    private void applyThemeBarIcons() {
        boolean light = "light".equals(settings.statusBarStyle);
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getActivity().getWindow(), getActivity().getWindow().getDecorView());
        bars.setAppearanceLightStatusBars(!light);
        bars.setAppearanceLightNavigationBars(!light);

        // No grey "contrast" strip behind 3-button navigation; the bar shows the page's own colour.
        android.view.Window window = getActivity().getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setNavigationBarContrastEnforced(false);
            window.setStatusBarContrastEnforced(false);
        }
        if (settings.backgroundColor != null && Build.VERSION.SDK_INT < 35) {
            window.setNavigationBarColor(settings.backgroundColor); // pre-Android 15 (edge-to-edge is automatic after)
        }
    }

    /** Intro video over the web view (app.config.json → splash.video). */
    private void showVideoSplash() {
        if (settings.splashVideo.isEmpty()) {
            splashDone = true;
            return;
        }
        videoSplash = new ShellVideoSplash(
            getActivity(),
            settings.splashVideo,
            settings.splashBackground,
            settings.splashStatusBar,
            settings.splashSkippable,
            settings.splashMinDuration,
            settings.splashMaxWait,
            () -> {
                splashDone = true;
                videoSplash = null;
                bridge.getWebView().evaluateJavascript("document.documentElement.classList.add('native-splash-done')", null);
                notifyListeners("splashDone", new JSObject());
            }
        );
        videoSplash.setAppLightIcons("light".equals(settings.statusBarStyle));
        if (!videoSplash.show(settings.splashPlay)) {
            videoSplash = null;
            splashDone = true;
        }
    }

    // region Offline

    /**
     * Tracks connectivity. While offline the web view prefers its cache (so pages you've visited
     * still open) and, in "banner" mode, navigations are held instead of ending on the error page.
     */
    private void watchConnectivity() {
        ConnectivityManager cm = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        online = hasInternet(cm, cm.getActiveNetwork());
        applyCacheMode();
        cm.registerDefaultNetworkCallback(
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    setOnline(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
                }

                @Override
                public void onLost(Network network) {
                    setOnline(false);
                }
            }
        );
    }

    private static boolean hasInternet(ConnectivityManager cm, Network network) {
        if (network == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void setOnline(boolean value) {
        if (online == value) return;
        online = value;
        main.post(this::applyCacheMode);
    }

    private void applyCacheMode() {
        bridge.getWebView().getSettings().setCacheMode(online ? WebSettings.LOAD_DEFAULT : WebSettings.LOAD_CACHE_ELSE_NETWORK);
    }

    /** In banner mode, returns true (and tells the page) when a load must wait for a connection. */
    private boolean blockWhileOffline(String url) {
        if (online || !pageShown || "screen".equals(settings.offlineMode)) return false;
        JSObject data = new JSObject();
        data.put("url", url);
        notifyListeners("offlineBlocked", data);
        main.post(() -> haptics.play("warning"));
        return true;
    }

    // endregion

    @Override
    protected void handleOnPause() {
        super.handleOnPause();
        if (speech != null && speech.isListening()) speech.stop();
    }

    @Override
    protected void handleOnDestroy() {
        if (speech != null) speech.destroy();
        super.handleOnDestroy();
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        handleIntent(intent);
    }

    /** Deep links: myapp://path and https://yourdomain/path (App Links). */
    private void handleIntent(Intent intent) {
        if (!settings.nativeDeepLinks || intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null) return;
        Uri target = settings.resolve(intent.getData().toString());
        if (settings.isInternal(target)) navigate(target);
    }

    /**
     * Where the app's bundled pages are served (e.g. https://localhost). Not bridge.getLocalUrl():
     * with a remote server.url that returns the website's origin instead.
     */
    private String localOrigin() {
        return bridge.getScheme() + "://" + bridge.getHost();
    }

    /**
     * Capacitor only injects its bridge (window.Capacitor.Plugins) into the website's origin when the
     * app loads a remote site, so bundled pages like an offline screen would have no plugins. Its
     * script builder is private, hence the reflection; if a Capacitor update changes that, bundled
     * pages simply run without plugins rather than crashing.
     */
    private void injectCapacitorIntoBundledPages(WebView webView) {
        if (bridge.getServerUrl() == null || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return;
        try {
            java.lang.reflect.Method build = com.getcapacitor.Bridge.class.getDeclaredMethod("getJSInjector");
            build.setAccessible(true);
            Object injector = build.invoke(bridge);
            if (injector == null) return;
            java.lang.reflect.Method script = injector.getClass().getDeclaredMethod("getScriptString");
            script.setAccessible(true);
            String js = (String) script.invoke(injector);
            WebViewCompat.addDocumentStartJavaScript(webView, js, java.util.Collections.singleton(localOrigin()));
        } catch (Exception e) {
            Logger.warn("NativeShell", "Bundled pages won't have Capacitor plugins: " + e.getMessage());
        }
    }

    /** Adds native-shell.js to every page on a trusted origin, before the page's own scripts. */
    private void injectBridgeScript(WebView webView) {
        String source;
        try (InputStream in = getContext().getAssets().open("public/native-shell.js")) {
            byte[] bytes = new byte[in.available()];
            int read = 0;
            while (read < bytes.length) {
                int n = in.read(bytes, read, bytes.length - read);
                if (n < 0) break;
                read += n;
            }
            source = new String(bytes, 0, read, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Logger.error("NativeShell", "public/native-shell.js not found — run `npx cap sync`", e);
            return;
        }

        String localOrigin = localOrigin();
        JSObject boot = new JSObject();
        boot.put("platform", "android");
        boot.put("appVersion", appVersion());
        boot.put("trustedOrigins", new JSArray(settings.trustedOrigins));
        List<String> hosts = new ArrayList<>(settings.internalHosts);
        if (settings.startUrl.getHost() != null) hosts.add(settings.startUrl.getHost());
        boot.put("internalHosts", new JSArray(hosts));
        boot.put("localOrigin", localOrigin);
        boot.put("autoHaptics", settings.autoHaptics);
        boot.put("offlineMode", settings.offlineMode);
        boot.put("offlineMessage", settings.offlineMessage);
        boot.put("offlineQueuedMessage", settings.offlineQueuedMessage);
        boot.put("onlineMessage", settings.onlineMessage);
        boot.put("offlineLinks", settings.offlineLinks);
        String script = "window.__NATIVE_SHELL__=" + boot + ";\n" + source + optionalAsset("public/native-extras.js");

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            Set<String> origins = new HashSet<>(settings.trustedOrigins);
            origins.add(localOrigin);
            try {
                WebViewCompat.addDocumentStartJavaScript(webView, script, origins);
                return;
            } catch (IllegalArgumentException e) {
                Logger.warn("NativeShell", "Invalid trusted origin, falling back to late injection: " + e.getMessage());
            }
        }
        bootScript = script; // older WebViews: inject once the page has loaded
    }

    /** An app-specific script that runs straight after native-shell.js (www/native-extras.js), or "". */
    private String optionalAsset(String path) {
        try (InputStream in = getContext().getAssets().open(path)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return "\n;" + out.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** Puts the web view inside pull-to-refresh, with the tab bar underneath. */
    private void wrapWebView(WebView webView) {
        ViewGroup parent = (ViewGroup) webView.getParent();
        if (parent == null) return;
        int index = parent.indexOfChild(webView);
        ViewGroup.LayoutParams params = webView.getLayoutParams();
        parent.removeView(webView);

        Context ctx = getContext();
        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);

        refreshLayout = new SwipeRefreshLayout(ctx);
        refreshLayout.addView(webView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (settings.accentColor != null) refreshLayout.setColorSchemeColors(settings.accentColor);
        refreshLayout.setOnChildScrollUpCallback((p, child) -> refreshBlocked || webView.getScrollY() > 0);
        refreshLayout.setOnRefreshListener(() -> {
            if (settings.autoHaptics) haptics.play("light");
            reloadCurrent();
            main.postDelayed(() -> refreshLayout.setRefreshing(false), 10000);
        });
        applyPullToRefresh();
        column.addView(refreshLayout, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        tabBar = new ShellTabBar(ctx);
        tabBar.setTabs(settings.tabs, settings.accentColor, settings.backgroundColor, this::onTabSelected);
        column.addView(tabBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (settings.safeAreaApp) {
            // Keep the page clear of the status bar, camera cutout, navigation bar and keyboard,
            // whatever the site's CSS does. The bar areas show the theme background.
            if (settings.backgroundColor != null) column.setBackgroundColor(settings.backgroundColor);
            ViewCompat.setOnApplyWindowInsetsListener(column, (v, insets) -> {
                Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
                Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
                return WindowInsetsCompat.CONSUMED;
            });
        }

        parent.addView(column, index, params);
    }

    private void applyPullToRefresh() {
        if (refreshLayout == null) return;
        boolean enabled = refreshEnabled && (pageRefreshOverride == null || pageRefreshOverride);
        refreshLayout.setEnabled(enabled);
        if (!enabled) refreshLayout.setRefreshing(false);
    }

    private String appVersion() {
        try {
            return getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    // endregion

    // region Navigation

    private void markReady() {
        if (ready) return;
        ready = true;
        if (pendingUrl != null) {
            Uri url = pendingUrl;
            pendingUrl = null;
            main.post(() -> navigate(url));
        }
    }

    private void navigate(Uri url) {
        if (url == null) return;
        if (!ready) {
            pendingUrl = url;
            return;
        }
        main.post(() -> bridge.getWebView().loadUrl(url.toString()));
    }

    private void reloadCurrent() {
        // Bundled pages live on the phone, so they can always reload.
        if (!onBundledPage && blockWhileOffline(null)) {
            if (refreshLayout != null) main.post(() -> refreshLayout.setRefreshing(false));
            return;
        }
        main.post(() -> {
            WebView webView = bridge.getWebView();
            String current = webView.getUrl();
            if (current != null && (settings.isInternal(Uri.parse(current)) || current.startsWith(localOrigin()))) {
                webView.reload();
            } else {
                webView.loadUrl((lastUrl != null ? lastUrl : settings.startUrl).toString());
            }
        });
    }

    @Override
    public void onUrlChanged(String url) {
        if (url == null) return;
        onBundledPage = url.startsWith(localOrigin());
        Uri uri = Uri.parse(url);
        if (settings.isInternal(uri)) lastUrl = uri;
        syncSelectedTab(uri);
    }

    private void syncSelectedTab(Uri url) {
        if (tabBar == null || settings.tabs.isEmpty() || url.getHost() == null) return;
        int best = -1;
        int bestLength = -1;
        String path = url.getPath() == null ? "/" : url.getPath();
        for (int i = 0; i < settings.tabs.size(); i++) {
            Uri tab = settings.tabs.get(i).url;
            String tabPath = tab.getPath() == null || tab.getPath().isEmpty() ? "/" : tab.getPath();
            if (url.getHost().equalsIgnoreCase(tab.getHost()) && path.startsWith(tabPath) && tabPath.length() > bestLength) {
                best = i;
                bestLength = tabPath.length();
            }
        }
        if (best >= 0) {
            final int index = best;
            main.post(() -> tabBar.select(index));
        }
    }

    private void onTabSelected(int index) {
        if (index < 0 || index >= settings.tabs.size()) return;
        if (settings.autoHaptics) haptics.play("selection");
        navigate(settings.tabs.get(index).url);
        JSObject data = new JSObject();
        data.put("index", index);
        data.put("url", settings.tabs.get(index).url.toString());
        notifyListeners("tabSelected", data);
    }

    private void open(Uri url, String mode) {
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
        boolean web = scheme.equals("http") || scheme.equals("https");
        if (web && "inApp".equals(mode)) {
            try {
                CustomTabColorSchemeParams colors = new CustomTabColorSchemeParams.Builder()
                    .setToolbarColor(settings.backgroundColor != null ? settings.backgroundColor : 0xFFFFFFFF)
                    .build();
                new CustomTabsIntent.Builder().setDefaultColorSchemeParams(colors).setShowTitle(true).build().launchUrl(getActivity(), url);
                return;
            } catch (ActivityNotFoundException ignored) {
                // no Custom Tabs browser — fall through to a normal intent
            }
        }
        try {
            Intent intent;
            if (scheme.equals("intent")) {
                intent = Intent.parseUri(url.toString(), Intent.URI_INTENT_SCHEME);
                try {
                    getActivity().startActivity(intent);
                } catch (ActivityNotFoundException e) {
                    String fallback = intent.getStringExtra("browser_fallback_url");
                    if (fallback != null) open(Uri.parse(fallback), mode);
                }
                return;
            }
            intent = new Intent(Intent.ACTION_VIEW, url);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getActivity().startActivity(intent);
        } catch (Exception e) {
            Logger.warn("NativeShell", "No app can open " + url);
        }
    }

    /**
     * Decides where links go: internal pages stay in the web view, external sites open in a
     * Custom Tab (or the browser), and tel:/mailto:/maps/intent: etc. go to the system.
     */
    @Override
    public Boolean shouldOverrideLoad(Uri url) {
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
        switch (scheme) {
            case "about":
            case "blob":
            case "data":
            case "file":
            case "javascript":
                return null;
            case "http":
            case "https":
                if (settings.isInternal(url)) return blockWhileOffline(url.toString());
                // The app's own bundled pages (e.g. an offline screen) open inside the app.
                if (url.getHost() != null && url.getHost().equals(bridge.getHost()) && !settings.isInternal(url)) return false;
                if ("webview".equals(settings.externalLinks)) return false;
                open(url, settings.externalLinks);
                return true;
            default:
                if (settings.isDeepLink(url)) {
                    navigate(settings.resolve(url.toString()));
                } else {
                    open(url, "browser");
                }
                return true;
        }
    }

    // endregion

    // region Downloads

    private void onDownload(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
        if (url.startsWith("blob:") || url.startsWith("data:")) return; // handled by native-shell.js
        String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setMimeType(mimeType);
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) req.addRequestHeader("Cookie", cookies);
            req.addRequestHeader("User-Agent", userAgent);
            req.setTitle(name);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            DownloadManager dm = (DownloadManager) getContext().getSystemService(Context.DOWNLOAD_SERVICE);
            dm.enqueue(req);
            toast("Downloading " + name + "…");
        } catch (Exception e) {
            Logger.warn("NativeShell", "DownloadManager failed, opening in browser: " + e.getMessage());
            open(Uri.parse(url), "browser");
        }
    }

    @PluginMethod
    public void saveFile(PluginCall call) {
        String data = call.getString("data");
        if (data == null) {
            call.reject("No file data");
            return;
        }
        String name = call.getString("filename", "download").replace('/', '_');
        String mime = call.getString("mimeType", "application/octet-stream");
        byte[] bytes;
        try {
            bytes = Base64.decode(data, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            call.reject("Invalid file data");
            return;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentResolver resolver = getContext().getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                values.put(MediaStore.Downloads.MIME_TYPE, mime);
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IllegalStateException("Could not create download");
                try (OutputStream out = resolver.openOutputStream(uri)) {
                    if (out == null) throw new IllegalStateException("Could not open download");
                    out.write(bytes);
                }
                main.post(() -> toast("Saved to Downloads: " + name));
                JSObject result = new JSObject();
                result.put("saved", true);
                result.put("path", uri.toString());
                call.resolve(result);
            } else {
                File file = new File(getContext().getCacheDir(), name);
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(bytes);
                }
                Uri uri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", file);
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType(mime);
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                getActivity().startActivity(Intent.createChooser(send, name));
                JSObject result = new JSObject();
                result.put("saved", true);
                result.put("path", uri.toString());
                call.resolve(result);
            }
        } catch (Exception e) {
            call.reject("Could not save file", e);
        }
    }

    private void toast(String message) {
        Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
    }

    // endregion

    // region JS methods: general

    @PluginMethod
    public void getConfig(PluginCall call) {
        call.resolve(settings.toJSObject());
    }

    @PluginMethod
    public void pageReady(PluginCall call) {
        pageRefreshOverride = call.getBoolean("pullToRefresh", null);
        main.post(() -> {
            markReady();
            applyPullToRefresh();
            String url = bridge.getWebView().getUrl();
            if (url != null) syncSelectedTab(Uri.parse(url));
            if (videoSplash != null) videoSplash.pageReady();
        });
        JSObject result = new JSObject();
        result.put("splashDone", splashDone);
        call.resolve(result);
    }

    @PluginMethod
    public void haptic(PluginCall call) {
        haptics.play(call.getString("type", "light"));
        call.resolve();
    }

    @PluginMethod
    public void vibrate(PluginCall call) {
        haptics.vibrate(call.getInt("duration", 300));
        call.resolve();
    }

    @PluginMethod
    public void openUrl(PluginCall call) {
        Uri url = settings.resolve(call.getString("url"));
        if (url == null) {
            call.reject("A valid url is required");
            return;
        }
        String mode = call.getString("mode", "inApp");
        main.post(() -> open(url, mode));
        call.resolve();
    }

    @PluginMethod
    public void openSettings(PluginCall call) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getContext().getPackageName(), null));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getActivity().startActivity(intent);
        call.resolve();
    }

    @PluginMethod
    public void reload(PluginCall call) {
        reloadCurrent();
        call.resolve();
    }

    @PluginMethod
    public void retry(PluginCall call) {
        Uri target = lastUrl != null ? lastUrl : settings.startUrl;
        main.post(() -> bridge.getWebView().loadUrl(target.toString()));
        call.resolve();
    }

    @PluginMethod
    public void keepAwake(PluginCall call) {
        boolean enabled = call.getBoolean("enabled", true);
        main.post(() -> {
            if (enabled) getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
        call.resolve();
    }

    @PluginMethod
    public void setPullToRefresh(PluginCall call) {
        refreshEnabled = call.getBoolean("enabled", true);
        main.post(this::applyPullToRefresh);
        call.resolve();
    }

    @PluginMethod
    public void setRefreshBlocked(PluginCall call) {
        refreshBlocked = call.getBoolean("blocked", false);
        call.resolve();
    }

    @PluginMethod
    public void setBadge(PluginCall call) {
        int count = Math.max(0, call.getInt("count", 0));
        boolean applied = badge.set(count, call.getString("text"));
        JSObject result = new JSObject();
        result.put("count", count);
        result.put("applied", applied);
        if (!applied) result.put("reason", "permission");
        call.resolve(result);
    }

    @PluginMethod
    public void getBadge(PluginCall call) {
        JSObject result = new JSObject();
        result.put("count", badge.get());
        call.resolve(result);
    }

    // endregion

    // region JS methods: tabs

    @PluginMethod
    public void selectTab(PluginCall call) {
        int index = call.getInt("index", 0);
        main.post(() -> {
            if (tabBar != null) tabBar.select(index);
            if (index >= 0 && index < settings.tabs.size()) navigate(settings.tabs.get(index).url);
        });
        call.resolve();
    }

    @PluginMethod
    public void setTabBadge(PluginCall call) {
        int index = call.getInt("index", 0);
        String value = call.getString("value", "");
        main.post(() -> {
            if (tabBar != null) tabBar.setBadge(index, value);
        });
        call.resolve();
    }

    @PluginMethod
    public void setTabsVisible(PluginCall call) {
        boolean visible = call.getBoolean("visible", true);
        main.post(() -> {
            if (tabBar != null && tabBar.hasTabs()) tabBar.setVisibility(visible ? ViewGroup.VISIBLE : ViewGroup.GONE);
        });
        call.resolve();
    }

    // endregion

    // region JS methods: speech

    @PluginMethod
    public void speechStart(PluginCall call) {
        if (getPermissionState("microphone") != PermissionState.GRANTED) {
            requestPermissionForAlias("microphone", call, "afterSpeechPermission");
            return;
        }
        startSpeech(call);
    }

    @PermissionCallback
    private void afterSpeechPermission(PluginCall call) {
        if (getPermissionState("microphone") == PermissionState.GRANTED) {
            startSpeech(call);
        } else {
            call.reject("Microphone permission is blocked. Open Android Settings, choose Apps, then this app, Permissions, and allow Microphone.", "permission");
        }
    }

    private void startSpeech(PluginCall call) {
        if (!speech.available()) {
            call.reject("Speech recognition isn't available on this phone.", "unavailable");
            return;
        }
        speech.start(call.getString("locale", "en-GB"), call.getInt("pauseFor", 4000));
        call.resolve();
    }

    @PluginMethod
    public void speechStop(PluginCall call) {
        speech.stop();
        call.resolve();
    }

    // endregion

    // region JS methods: permissions

    @Override
    @PluginMethod
    public void checkPermissions(PluginCall call) {
        call.resolve(permissionStates());
    }

    @Override
    @PluginMethod
    public void requestPermissions(PluginCall call) {
        List<String> aliases = new ArrayList<>();
        for (String name : requestedNames(call)) {
            for (String alias : RUNTIME_ALIASES) {
                if (alias.equals(name) && getPermissionState(alias) != PermissionState.GRANTED) aliases.add(alias);
            }
        }
        if (aliases.isEmpty()) {
            afterRuntimePermissions(call);
        } else {
            requestPermissionForAliases(aliases.toArray(new String[0]), call, "afterRuntimePermissions");
        }
    }

    @PermissionCallback
    private void afterRuntimePermissions(PluginCall call) {
        if (requestedNames(call).contains("notifications")) {
            requestNotificationPermission(call, "afterNotificationPermission");
        } else {
            call.resolve(permissionStates());
        }
    }

    @PermissionCallback
    private void afterNotificationPermission(PluginCall call) {
        call.resolve(permissionStates());
    }

    private List<String> requestedNames(PluginCall call) {
        List<String> names = new ArrayList<>();
        JSArray arr = call.getArray("permissions");
        if (arr == null) {
            names.add("camera");
            names.add("microphone");
            return names;
        }
        try {
            names.addAll(arr.<String>toList());
        } catch (JSONException ignored) {
            // malformed list — treat as empty
        }
        return names;
    }

    /** OneSignal when configured (so the device registers), otherwise the system prompt. */
    private void requestNotificationPermission(PluginCall call, String callbackName) {
        if (push.isEnabled()) {
            main.post(() -> push.requestPermission(granted -> {
                    if (callbackName.equals("afterPushPermission")) afterPushPermission(call);
                    else afterNotificationPermission(call);
                })
            );
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !push.hasPermission()) {
            requestPermissionForAlias("notifications", call, callbackName);
        } else if (callbackName.equals("afterPushPermission")) {
            afterPushPermission(call);
        } else {
            afterNotificationPermission(call);
        }
    }

    private String notificationState() {
        if (push.hasPermission()) return "granted";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return getPermissionState("notifications").toString();
        return "denied";
    }

    private JSObject permissionStates() {
        JSObject result = new JSObject();
        for (String alias : RUNTIME_ALIASES) result.put(alias, getPermissionState(alias).toString());
        result.put("notifications", notificationState());
        result.put("photos", "granted"); // the Android photo picker needs no permission
        return result;
    }

    // endregion

    // region JS methods: push

    @PluginMethod
    public void pushRequestPermission(PluginCall call) {
        requestNotificationPermission(call, "afterPushPermission");
    }

    @PermissionCallback
    private void afterPushPermission(PluginCall call) {
        JSObject result = new JSObject();
        result.put("granted", push.hasPermission());
        call.resolve(result);
    }

    @PluginMethod
    public void pushGetState(PluginCall call) {
        call.resolve(push.state(notificationState()));
    }

    @PluginMethod
    public void pushLogin(PluginCall call) {
        String id = call.getString("externalId");
        if (id == null || id.isEmpty()) {
            call.reject("externalId is required");
            return;
        }
        push.login(id);
        call.resolve();
    }

    @PluginMethod
    public void pushLogout(PluginCall call) {
        push.logout();
        call.resolve();
    }

    @PluginMethod
    public void pushAddTags(PluginCall call) {
        JSObject tags = call.getObject("tags", new JSObject());
        Map<String, String> map = new HashMap<>();
        Iterator<String> keys = tags.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            map.put(key, String.valueOf(tags.opt(key)));
        }
        push.addTags(map);
        call.resolve();
    }

    @PluginMethod
    public void pushRemoveTags(PluginCall call) {
        List<String> keys = new ArrayList<>();
        JSArray arr = call.getArray("keys");
        if (arr != null) {
            try {
                keys.addAll(arr.<String>toList());
            } catch (JSONException ignored) {
                // malformed list — nothing to remove
            }
        }
        push.removeTags(keys);
        call.resolve();
    }

    @PluginMethod
    public void pushSetEmail(PluginCall call) {
        String email = call.getString("email");
        if (email == null || email.isEmpty()) {
            call.reject("email is required");
            return;
        }
        push.setEmail(email);
        call.resolve();
    }

    @PluginMethod
    public void pushOptIn(PluginCall call) {
        push.optIn();
        call.resolve();
    }

    @PluginMethod
    public void pushOptOut(PluginCall call) {
        push.optOut();
        call.resolve();
    }

    @PluginMethod
    public void pushClearAll(PluginCall call) {
        push.clearAll();
        badge.set(badge.get(), null); // keep the icon count
        call.resolve();
    }

    // ShellPush.Listener

    @Override
    public void onPushClick(JSObject data, String url) {
        Uri target = settings.resolve(url);
        if (target != null) {
            if (settings.isInternal(target)) navigate(target);
            else open(target, "inApp");
        }
        notifyListeners("pushClick", data, true);
    }

    @Override
    public void onPushReceived(JSObject data) {
        notifyListeners("pushReceived", data);
    }

    @Override
    public void onPushPermission(boolean granted) {
        JSObject data = new JSObject();
        data.put("granted", granted);
        notifyListeners("pushPermission", data);
    }

    // endregion
}
