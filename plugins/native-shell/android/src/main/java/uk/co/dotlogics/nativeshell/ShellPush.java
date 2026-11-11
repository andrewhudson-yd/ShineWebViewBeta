package uk.co.dotlogics.nativeshell;

import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import androidx.core.app.NotificationManagerCompat;
import com.getcapacitor.JSObject;
import com.onesignal.Continue;
import com.onesignal.OneSignal;
import com.onesignal.notifications.INotification;
import com.onesignal.notifications.INotificationClickEvent;
import com.onesignal.notifications.INotificationClickListener;
import com.onesignal.notifications.INotificationLifecycleListener;
import com.onesignal.notifications.INotificationWillDisplayEvent;
import com.onesignal.notifications.IPermissionObserver;
import com.onesignal.user.subscriptions.IPushSubscription;
import java.util.Collection;
import java.util.Map;
import org.json.JSONObject;

/**
 * Push notifications via OneSignal. If no OneSignal App ID is configured, the JS API still
 * works for permission handling but nothing is registered with a push service.
 */
class ShellPush implements INotificationClickListener, INotificationLifecycleListener, IPermissionObserver {

    interface Listener {
        void onPushClick(JSObject data, String url);

        void onPushReceived(JSObject data);

        void onPushPermission(boolean granted);
    }

    interface PermissionCallback {
        void done(boolean granted);
    }

    private static final String PREFS = "NativeShell.push";
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private boolean enabled = false;

    ShellPush(Context context) {
        this.context = context.getApplicationContext();
    }

    boolean isEnabled() {
        return enabled;
    }

    void start(String appId, Listener listener) {
        this.listener = listener;
        if (appId == null || appId.isEmpty()) return;
        enabled = true;
        OneSignal.initWithContext(context, appId);
        OneSignal.getNotifications().addClickListener(this);
        OneSignal.getNotifications().addForegroundLifecycleListener(this);
        OneSignal.getNotifications().addPermissionObserver(this);
    }

    /** OneSignal path; without OneSignal the plugin asks via Capacitor's permission flow. */
    void requestPermission(PermissionCallback callback) {
        OneSignal.getNotifications()
            .requestPermission(
                true,
                Continue.with(r -> {
                    boolean granted = r.isSuccess() && Boolean.TRUE.equals(r.getData());
                    main.post(() -> callback.done(granted));
                })
            );
    }

    boolean hasPermission() {
        return NotificationManagerCompat.from(context).areNotificationsEnabled();
    }

    JSObject state(String permission) {
        JSObject o = new JSObject();
        o.put("provider", enabled ? "onesignal" : "none");
        o.put("enabled", enabled);
        o.put("permission", permission);
        o.put("externalId", prefs().getString("externalId", null));
        if (enabled) {
            IPushSubscription sub = OneSignal.getUser().getPushSubscription();
            o.put("optedIn", sub.getOptedIn());
            o.put("subscriptionId", sub.getId());
            o.put("token", sub.getToken());
        }
        return o;
    }

    void login(String externalId) {
        prefs().edit().putString("externalId", externalId).apply();
        if (enabled) OneSignal.login(externalId);
    }

    void logout() {
        prefs().edit().remove("externalId").apply();
        if (enabled) OneSignal.logout();
    }

    void addTags(Map<String, String> tags) {
        if (enabled) OneSignal.getUser().addTags(tags);
    }

    void removeTags(Collection<String> keys) {
        if (enabled) OneSignal.getUser().removeTags(keys);
    }

    void setEmail(String email) {
        if (enabled) OneSignal.getUser().addEmail(email);
    }

    void optIn() {
        if (enabled) OneSignal.getUser().getPushSubscription().optIn();
    }

    void optOut() {
        if (enabled) OneSignal.getUser().getPushSubscription().optOut();
    }

    void clearAll() {
        if (enabled) {
            OneSignal.getNotifications().clearAllNotifications();
        } else {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancelAll();
        }
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // OneSignal callbacks

    @Override
    public void onClick(INotificationClickEvent event) {
        INotification n = event.getNotification();
        JSObject data = payload(n);
        data.put("actionId", event.getResult().getActionId());
        JSONObject extra = n.getAdditionalData();
        String url = extra != null ? extra.optString("url", "") : "";
        if (url.isEmpty() && event.getResult().getUrl() != null) url = event.getResult().getUrl();
        if (url.isEmpty() && n.getLaunchURL() != null) url = n.getLaunchURL();
        data.put("url", url.isEmpty() ? null : url);
        final String target = url;
        main.post(() -> {
            if (listener != null) listener.onPushClick(data, target);
        });
    }

    @Override
    public void onWillDisplay(INotificationWillDisplayEvent event) {
        // Leave display to OneSignal; just tell the page.
        JSObject data = payload(event.getNotification());
        main.post(() -> {
            if (listener != null) listener.onPushReceived(data);
        });
    }

    @Override
    public void onNotificationPermissionChange(boolean permission) {
        main.post(() -> {
            if (listener != null) listener.onPushPermission(permission);
        });
    }

    private static JSObject payload(INotification n) {
        JSObject o = new JSObject();
        o.put("id", n.getNotificationId());
        o.put("title", n.getTitle());
        o.put("body", n.getBody());
        JSONObject extra = n.getAdditionalData();
        o.put("data", extra != null ? extra : new JSONObject());
        o.put("launchUrl", n.getLaunchURL());
        return o;
    }
}
