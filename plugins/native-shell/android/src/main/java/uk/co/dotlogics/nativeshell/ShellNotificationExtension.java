package uk.co.dotlogics.nativeshell;

import androidx.annotation.Keep;
import com.onesignal.notifications.INotificationReceivedEvent;
import com.onesignal.notifications.INotificationServiceExtension;
import org.json.JSONObject;

/**
 * Runs for every OneSignal push, even when the app is closed. If the push's Additional Data
 * contains "badge" (e.g. {"badge": 3}), the icon badge is updated to that number.
 * Registered in the plugin's AndroidManifest.xml.
 */
@Keep
public class ShellNotificationExtension implements INotificationServiceExtension {

    @Override
    public void onNotificationReceived(INotificationReceivedEvent event) {
        JSONObject data = event.getNotification().getAdditionalData();
        if (data == null || !data.has("badge")) return;
        int count = data.optInt("badge", -1);
        if (count >= 0) new ShellBadge(event.getContext()).set(count, data.optString("badgeText", null));
    }
}
