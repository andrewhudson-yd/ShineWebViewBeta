package uk.co.dotlogics.nativeshell;

import android.graphics.Color;
import android.net.Uri;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Values from `plugins.NativeShell` in capacitor.config (generated from app.config.json). */
class ShellSettings {

    static class Tab {

        final String title;
        final String icon;
        final Uri url;

        Tab(String title, String icon, Uri url) {
            this.title = title;
            this.icon = icon;
            this.url = url;
        }
    }

    Uri startUrl = Uri.parse("about:blank");
    List<String> internalHosts = new ArrayList<>();
    List<String> trustedOrigins = new ArrayList<>();
    String externalLinks = "inApp";
    String accentHex = "";
    String backgroundHex = "";
    Integer accentColor;
    Integer backgroundColor;
    boolean pullToRefresh = true;
    boolean autoHaptics = true;
    String oneSignalAppId = "";
    boolean promptOnLaunch = false;
    String deepLinkScheme = "";
    String badgeText = "";
    String hapticIntensity = "normal";
    boolean safeAreaApp = true;
    boolean nativeDeepLinks = true;
    String offlineMode = "banner";
    String offlineMessage = "";
    String offlineQueuedMessage = "";
    String onlineMessage = "";
    JSONArray offlineLinks = new JSONArray();
    String splashVideo = "";
    String splashPlay = "everyLaunch";
    boolean splashSkippable = true;
    double splashMinDuration = 0;
    double splashMaxWait = 8;
    int splashBackground = Color.BLACK;
    String splashStatusBar = "auto";
    String statusBarStyle = "dark";
    List<Tab> tabs = new ArrayList<>();

    ShellSettings(PluginConfig config) {
        startUrl = Uri.parse(config.getString("startUrl", "about:blank"));
        internalHosts = new ArrayList<>(Arrays.asList(config.getArray("internalHosts", new String[0])));
        trustedOrigins = new ArrayList<>(Arrays.asList(config.getArray("trustedOrigins", new String[0])));
        externalLinks = config.getString("externalLinks", "inApp");
        accentHex = config.getString("accentColor", "");
        accentColor = parseColor(accentHex);
        backgroundHex = config.getString("backgroundColor", "");
        backgroundColor = parseColor(backgroundHex);
        pullToRefresh = config.getBoolean("pullToRefresh", true);
        autoHaptics = config.getBoolean("autoHaptics", true);
        oneSignalAppId = config.getString("oneSignalAppId", "");
        promptOnLaunch = config.getBoolean("promptOnLaunch", false);
        deepLinkScheme = config.getString("deepLinkScheme", "");
        badgeText = config.getString("badgeText", "");
        hapticIntensity = config.getString("hapticIntensity", "normal");
        safeAreaApp = !"web".equals(config.getString("safeArea", "app"));
        nativeDeepLinks = config.getBoolean("nativeDeepLinks", true);
        statusBarStyle = config.getString("statusBarStyle", "dark");
        JSONObject off = config.getConfigJSON().optJSONObject("offline");
        if (off != null) {
            offlineMode = off.optString("mode", "banner");
            offlineMessage = off.optString("message", "");
            offlineQueuedMessage = off.optString("queuedMessage", "");
            onlineMessage = off.optString("onlineMessage", "");
            JSONArray links = off.optJSONArray("links");
            if (links != null) offlineLinks = links;
        }

        JSONObject splash = config.getConfigJSON().optJSONObject("splash");
        if (splash != null) {
            splashVideo = splash.optString("video", "");
            splashPlay = splash.optString("play", "everyLaunch");
            splashSkippable = splash.optBoolean("skippable", true);
            splashMinDuration = splash.optDouble("minDuration", 0);
            splashMaxWait = splash.optDouble("maxWait", 8);
            splashStatusBar = splash.optString("statusBarStyle", "auto");
            Integer bg = parseColor(splash.optString("backgroundColor", ""));
            splashBackground = bg != null ? bg : (backgroundColor != null ? backgroundColor : Color.BLACK);
        }

        JSONArray rawTabs = config.getConfigJSON().optJSONArray("tabs");
        if (rawTabs != null) {
            for (int i = 0; i < rawTabs.length(); i++) {
                JSONObject t = rawTabs.optJSONObject(i);
                if (t == null) continue;
                Uri url = resolve(t.optString("url", ""));
                if (url != null) tabs.add(new Tab(t.optString("title", ""), t.optString("icon", ""), url));
            }
        }
    }

    private static Integer parseColor(String hex) {
        if (hex == null || hex.isEmpty()) return null;
        try {
            return Color.parseColor(hex);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Hosts that load inside the app. Supports "*.example.com" wildcards. */
    boolean isInternal(Uri url) {
        if (url == null) return false;
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return false;
        String host = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);
        if (host.equalsIgnoreCase(startUrl.getHost())) return true;
        for (String pattern : internalHosts) {
            String p = pattern.toLowerCase(Locale.ROOT);
            if (p.startsWith("*.")) {
                String root = p.substring(2);
                if (host.equals(root) || host.endsWith("." + root)) return true;
            } else if (host.equals(p)) {
                return true;
            }
        }
        return false;
    }

    boolean isDeepLink(Uri url) {
        return url != null && !deepLinkScheme.isEmpty() && deepLinkScheme.equalsIgnoreCase(url.getScheme());
    }

    /** Turns `myapp://orders/12`, `/orders/12` or a full URL into a URL the web view can load. */
    Uri resolve(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        Uri url = Uri.parse(raw);
        if (url.getScheme() == null) {
            String path = raw.startsWith("/") ? raw : "/" + raw;
            return Uri.parse(origin(startUrl) + path);
        }
        if (isDeepLink(url)) {
            String path = (url.getHost() == null ? "" : url.getHost()) + (url.getPath() == null ? "" : url.getPath());
            if (!path.startsWith("/")) path = "/" + path;
            Uri.Builder b = startUrl.buildUpon().encodedPath(path).encodedQuery(url.getEncodedQuery()).encodedFragment(url.getEncodedFragment());
            return b.build();
        }
        return url;
    }

    static String origin(Uri url) {
        if (url == null || url.getScheme() == null || url.getHost() == null) return "";
        String o = url.getScheme() + "://" + url.getHost();
        return url.getPort() == -1 ? o : o + ":" + url.getPort();
    }

    JSObject toJSObject() {
        JSObject o = new JSObject();
        o.put("startUrl", startUrl.toString());
        o.put("internalHosts", new JSArray(internalHosts));
        o.put("externalLinks", externalLinks);
        o.put("accentColor", accentHex);
        o.put("backgroundColor", backgroundHex);
        o.put("pullToRefresh", pullToRefresh);
        o.put("autoHaptics", autoHaptics);
        o.put("pushEnabled", !oneSignalAppId.isEmpty());
        JSArray t = new JSArray();
        for (Tab tab : tabs) {
            JSObject item = new JSObject();
            item.put("title", tab.title);
            item.put("icon", tab.icon);
            item.put("url", tab.url.toString());
            t.put(item);
        }
        o.put("tabs", t);
        return o;
    }
}
