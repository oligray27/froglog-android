package uk.co.froglog.app.widgets;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

/**
 * Registers this install's Firebase token with the backend while any widget is on the home
 * screen, so it can send silent refreshes (backend lib/social/widgetPush.js). Separate from
 * notifications, which only register once they're turned on. Runs on WidgetSupport.WORKER.
 */
final class WidgetDevices {
    private static final String REGISTERED = "registered_token";

    private WidgetDevices() {}

    static boolean anyPlaced(Context context) {
        return WidgetSupport.ids(context, QuickSessionWidget.class, QuickSessionFlexWidget.class).length > 0
            || WidgetSupport.ids(context, ThisWeekWidget.class, ThisWeekFlexWidget.class).length > 0
            || WidgetSupport.ids(context, FriendsWidget.class, FriendsFlexWidget.class).length > 0
            // Remote Start's play buttons follow your own session, which the same silent pushes report.
            || RemoteStartWidget.ids(context).length > 0;
    }

    // Cheap when already registered, so every widget refresh calls it.
    static void register(Context context) {
        if (!WidgetStore.isSignedIn(context) || !anyPlaced(context)) return;
        try {
            String token = Tasks.await(FirebaseMessaging.getInstance().getToken(), 10, TimeUnit.SECONDS);
            if (token == null || token.equals(WidgetStore.cache(context, REGISTERED))) return;
            JSONObject body = new JSONObject().put("token", token);
            if (FroglogApi.post(context, "/api/widgets/device", body).ok()) WidgetStore.putCache(context, REGISTERED, token);
        } catch (Exception unavailable) {
            // No Firebase (a dev build without its config) or no connection: widgets still refresh every 30 minutes.
        }
    }

    // When the last widget goes, and before logging out clears the login.
    static void unregister(Context context) {
        String token = WidgetStore.cache(context, REGISTERED);
        if (token == null || !WidgetStore.isSignedIn(context)) return;
        try {
            FroglogApi.delete(context, "/api/widgets/device", new JSONObject().put("token", token));
        } catch (Exception ignored) {
            // A token the backend keeps gets dropped the first time Firebase reports it gone.
        }
        WidgetStore.putCache(context, REGISTERED, null);
    }
}
