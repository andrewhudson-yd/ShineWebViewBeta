package uk.co.dotlogics.nativeshell;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/**
 * App icon badge count on Android.
 *
 * Android has no API to put a number on the icon; launchers (Samsung, Xiaomi, Huawei, Nova…)
 * take it from the app's notifications. So a badge is shown with one quiet notification
 * ("You have 3 to-dos") whose number is the count. Pixel launchers show a dot rather than a number.
 */
class ShellBadge {

    static final String CHANNEL_ID = "native_shell_badge";
    private static final int NOTIFICATION_ID = 0x0BAD6E;
    private static final String PREFS = "NativeShell.badge";

    private final Context context;

    ShellBadge(Context context) {
        this.context = context.getApplicationContext();
    }

    int get() {
        return prefs().getInt("count", 0);
    }

    /** Returns true if the badge could be shown (needs notification permission when count > 0). */
    boolean set(int count, String text) {
        count = Math.max(0, count);
        prefs().edit().putInt("count", count).apply();

        NotificationManagerCompat nm = NotificationManagerCompat.from(context);
        if (count == 0) {
            nm.cancel(NOTIFICATION_ID);
            return true;
        }
        if (!nm.areNotificationsEnabled()) return false;

        ensureChannel();
        String label = text != null && !text.isEmpty() ? text : defaultText();
        label = label.replace("{count}", String.valueOf(count));

        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIcon())
            .setContentTitle(appName())
            .setContentText(label)
            .setNumber(count)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS);

        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            b.setContentIntent(PendingIntent.getActivity(context, NOTIFICATION_ID, launch, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        }

        try {
            nm.notify(NOTIFICATION_ID, b.build());
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    /** Text template from app.config.json → badge.androidText, saved by the plugin on load. */
    void setDefaultText(String text) {
        if (text != null && !text.isEmpty()) prefs().edit().putString("text", text).apply();
    }

    private String defaultText() {
        return prefs().getString("text", "You have {count} new items");
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Icon badge", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shows the number of items waiting for you on the app icon");
        channel.setShowBadge(true);
        channel.enableVibration(false);
        channel.setSound(null, null);
        nm.createNotificationChannel(channel);
    }

    private int smallIcon() {
        int id = context.getResources().getIdentifier("ic_stat_badge", "drawable", context.getPackageName());
        if (id == 0) id = context.getResources().getIdentifier("ic_stat_onesignal_default", "drawable", context.getPackageName());
        return id != 0 ? id : context.getApplicationInfo().icon;
    }

    private String appName() {
        return context.getPackageManager().getApplicationLabel(context.getApplicationInfo()).toString();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
