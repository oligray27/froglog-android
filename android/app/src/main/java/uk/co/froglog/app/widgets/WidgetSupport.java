package uk.co.froglog.app.widgets;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import uk.co.froglog.app.MainActivity;

/** What the widgets share: their worker thread, opening the app at a page, and refreshing them all. */
final class WidgetSupport {
    // One thread for every widget's API calls and drawing, so they never run on the main thread.
    static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    private WidgetSupport() {}

    // Redraws every widget. Used when the site's login or session changes, and for silent pushes.
    static void refreshAll(Context context) {
        QuickSessionWidget.refresh(context);
        ThisWeekWidget.refresh(context);
        FriendsWidget.refresh(context);
    }

    // For each widget's onDisabled: once none are left, the backend stops sending refreshes.
    static void lastOneRemoved(Context context) {
        Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            if (!WidgetDevices.anyPlaced(app)) WidgetDevices.unregister(app);
        });
    }

    // Opens the app at a page. Each url gets its own request code, or Android would reuse one intent's extras for all.
    static PendingIntent openApp(Context context, String url) {
        Intent intent = new Intent(context, MainActivity.class)
            .setAction(FrogLogWidgetsPlugin.ACTION_OPEN)
            .putExtra(FrogLogWidgetsPlugin.EXTRA_URL, url)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, url.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // For a list: the intent a row's fill-in (its own url) is added to. It has to be mutable for that.
    static PendingIntent openAppTemplate(Context context) {
        Intent intent = new Intent(context, MainActivity.class)
            .setAction(FrogLogWidgetsPlugin.ACTION_OPEN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int mutable = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0;
        return PendingIntent.getActivity(context, 7001, intent, PendingIntent.FLAG_UPDATE_CURRENT | mutable);
    }

    static JSONObject parse(String json) {
        if (json == null) return null;
        try {
            return new JSONObject(json);
        } catch (Exception e) {
            return null;
        }
    }
}
