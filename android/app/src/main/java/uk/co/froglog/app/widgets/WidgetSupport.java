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
    // Image downloads wait on their own thread. On WORKER a slow one held up every widget's broadcast past its 10 seconds, and Android killed the app (an ANR).
    static final ExecutorService IMAGES = Executors.newSingleThreadExecutor();

    private WidgetSupport() {}

    // Redraws every widget. Used when the site's login or session changes, and for silent pushes.
    static void refreshAll(Context context) {
        QuickSessionWidget.refresh(context);
        ThisWeekWidget.refresh(context);
        FriendsWidget.refresh(context);
    }

    // The ids of a widget on the home screen and its cover screen twin, which are drawn together.
    static int[] ids(Context context, Class<?> home, Class<?> cover) {
        android.appwidget.AppWidgetManager manager = android.appwidget.AppWidgetManager.getInstance(context);
        int[] a = manager.getAppWidgetIds(new android.content.ComponentName(context, home));
        int[] b = manager.getAppWidgetIds(new android.content.ComponentName(context, cover));
        int[] all = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        return all;
    }

    // Extra room under the content on the cover screen, which isn't square.
    static final float COVER_EXTRA_BOTTOM_DP = 14f;

    // A list row's tap on the cover screen goes to this instead of the app: a broadcast nothing listens for.
    static PendingIntent noTapTemplate(Context context, Class<?> receiver) {
        Intent intent = new Intent(context, receiver).setAction("uk.co.froglog.app.widgets.NO_TAP");
        int mutable = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0;
        return PendingIntent.getBroadcast(context, 7002, intent, PendingIntent.FLAG_UPDATE_CURRENT | mutable);
    }

    static boolean isCover(Context context, int appWidgetId) {
        android.appwidget.AppWidgetProviderInfo info = android.appwidget.AppWidgetManager.getInstance(context).getAppWidgetInfo(appWidgetId);
        return info != null && info.provider.getClassName().endsWith("FlexWidget");
    }

    // The cover screen supplies its own corners, so a Flex widget's card is drawn square.
    static android.widget.RemoteViews squareOnCover(Context context, int appWidgetId, android.widget.RemoteViews views, int rootId) {
        if (isCover(context, appWidgetId)) {
            views.setInt(rootId, "setBackgroundResource", uk.co.froglog.app.R.drawable.widget_card_square);
            // Tapping the cover screen widget does nothing: opening the app there is not wanted.
            views.setOnClickPendingIntent(rootId, null);
        }
        return views;
    }

    static boolean contains(int[] ids, int id) {
        for (int candidate : ids) if (candidate == id) return true;
        return false;
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
