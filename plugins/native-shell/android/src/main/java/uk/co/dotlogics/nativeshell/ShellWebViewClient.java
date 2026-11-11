package uk.co.dotlogics.nativeshell;

import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeWebViewClient;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Capacitor's client with a few web-app friendly tweaks. */
class ShellWebViewClient extends BridgeWebViewClient {

    interface UrlListener {
        void onUrlChanged(String url);
    }

    private static final Map<String, String> TYPES = new HashMap<>();

    static {
        TYPES.put("html", "text/html");
        TYPES.put("js", "text/javascript");
        TYPES.put("mjs", "text/javascript");
        TYPES.put("css", "text/css");
        TYPES.put("json", "application/json");
        TYPES.put("svg", "image/svg+xml");
        TYPES.put("png", "image/png");
        TYPES.put("jpg", "image/jpeg");
        TYPES.put("jpeg", "image/jpeg");
        TYPES.put("webp", "image/webp");
        TYPES.put("gif", "image/gif");
        TYPES.put("ico", "image/x-icon");
        TYPES.put("woff2", "font/woff2");
        TYPES.put("woff", "font/woff");
        TYPES.put("mp4", "video/mp4");
        TYPES.put("txt", "text/plain");
    }

    private final Bridge bridge;
    private final UrlListener listener;

    ShellWebViewClient(Bridge bridge, UrlListener listener) {
        super(bridge);
        this.bridge = bridge;
        this.listener = listener;
    }

    /**
     * Bundled pages (www/ → https://localhost/…) are served straight from the app. Capacitor only does
     * this for its error page when the app loads a remote site, and otherwise sends localhost requests
     * out to the network — which fails, and is exactly when a bundled page is needed.
     */
    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        Uri url = request.getUrl();
        if (bridge.getServerUrl() != null && "GET".equals(request.getMethod()) && isBundled(url)) {
            WebResourceResponse local = asset(view, url.getPath());
            if (local != null) return local;
        }
        return super.shouldInterceptRequest(view, request);
    }

    private boolean isBundled(Uri url) {
        return bridge.getScheme().equalsIgnoreCase(url.getScheme()) && bridge.getHost().equalsIgnoreCase(url.getHost());
    }

    private WebResourceResponse asset(WebView view, String path) {
        if (path == null || path.isEmpty() || path.contains("..")) return null;
        if (path.endsWith("/")) path += "index.html";
        try {
            InputStream in = view.getContext().getAssets().open("public" + path);
            String ext = path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            String type = TYPES.containsKey(ext) ? TYPES.get(ext) : "application/octet-stream";
            WebResourceResponse response = new WebResourceResponse(type, type.startsWith("text/") || ext.equals("json") || ext.equals("svg") ? "utf-8" : null, in);
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "no-cache");
            response.setResponseHeaders(headers);
            return response;
        } catch (IOException e) {
            return null; // not bundled — let Capacitor decide
        }
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        // Let embedded content (iframes: video players, maps, payment forms) navigate freely.
        String scheme = request.getUrl().getScheme();
        if (!request.isForMainFrame() && ("http".equals(scheme) || "https".equals(scheme))) return false;
        return super.shouldOverrideUrlLoading(view, request);
    }

    @Override
    public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
        // Keep the site's own 404 / 500 pages instead of replacing them with offline.html.
        // Network failures still show the offline page (onReceivedError).
    }

    @Override
    public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
        super.doUpdateVisitedHistory(view, url, isReload);
        listener.onUrlChanged(url);
    }
}
