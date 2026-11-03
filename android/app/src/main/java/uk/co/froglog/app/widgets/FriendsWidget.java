package uk.co.froglog.app.widgets;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import uk.co.froglog.app.R;

/**
 * Home screen widget for everyone you follow, from /api/widgets/friends (the Activity page's privacy rules),
 * as a scrolling list (FriendsService): a green dot, the game and the time for people online, last seen for the rest.
 * The times are text ("1m", "1h 27m", "5m ago") redrawn by a minute alarm.
 *
 * The backend sends a silent refresh when someone you follow starts, stops, switches game or
 * pauses (lib/social/widgetPush.js), so this stays current with the app closed. It also refreshes
 * every 30 minutes, which catches LilyPad going quiet without saying so.
 */
public class FriendsWidget extends AppWidgetProvider {
    private static final String ACTION_REFRESH = "uk.co.froglog.app.widgets.FRIENDS_REFRESH";
    private static final String ACTION_TICK = "uk.co.froglog.app.widgets.FRIENDS_TICK";
    static final String CACHE_FRIENDS = "friends";
    // While this is set (only the dev build's DebugSessionReceiver sets it) the widget shows its cached list and asks the server nothing.
    private static final String CACHE_DEBUG_HOLD = "friends_debug_hold";

    static void showDebugState(Context context, String json) {
        WidgetStore.putCache(context, CACHE_FRIENDS, json);
        WidgetStore.putCache(context, CACHE_DEBUG_HOLD, "1");
        render(context, WidgetSupport.parse(json), true);
    }

    static void clearDebugState(Context context) {
        WidgetStore.putCache(context, CACHE_DEBUG_HOLD, null);
        refresh(context);
    }

    static void refresh(Context context) {
        context.sendBroadcast(new Intent(context, FriendsWidget.class).setAction(ACTION_REFRESH));
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(action) && !ACTION_REFRESH.equals(action) && !ACTION_TICK.equals(action)) {
            super.onReceive(context, intent);
            return;
        }
        Context app = context.getApplicationContext();
        PendingResult pending = goAsync();
        WidgetSupport.WORKER.execute(() -> {
            try {
                if (ACTION_TICK.equals(action)) {
                    // Only the clocks moved, so redraw from the cached list.
                    render(app, WidgetSupport.parse(WidgetStore.cache(app, CACHE_FRIENDS)), WidgetStore.isSignedIn(app));
                    return;
                }
                fetchAndRender(app);
            } finally {
                pending.finish();
            }
        });
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        WidgetTheme.forget(context, appWidgetIds);
    }

    @Override
    public void onDisabled(Context context) {
        ((AlarmManager) context.getSystemService(Context.ALARM_SERVICE)).cancel(tickIntent(context));
        WidgetSupport.lastOneRemoved(context);
    }

    private static void fetchAndRender(Context context) {
        if (WidgetStore.cache(context, CACHE_DEBUG_HOLD) != null) {
            render(context, WidgetSupport.parse(WidgetStore.cache(context, CACHE_FRIENDS)), true);
            return;
        }
        if (!WidgetStore.isSignedIn(context)) {
            render(context, null, false);
            return;
        }
        WidgetDevices.register(context);
        try {
            FroglogApi.Response friends = FroglogApi.get(context, "/api/widgets/friends");
            if (friends.status == 401) {
                WidgetStore.clearLogin(context);
                render(context, null, false);
                return;
            }
            if (friends.ok()) WidgetStore.putCache(context, CACHE_FRIENDS, friends.body.toString());
        } catch (Exception offline) {
            // Falls through to the cached list.
        }
        render(context, WidgetSupport.parse(WidgetStore.cache(context, CACHE_FRIENDS)), true);
    }

    private static void render(Context context, JSONObject data, boolean signedIn) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, FriendsWidget.class));
        if (ids.length == 0) return;
        for (int id : ids) manager.updateAppWidget(id, build(context, data, signedIn, id));
        // The list reads the cache itself, so tell it to look again (this is also how the times move on).
        manager.notifyAppWidgetViewDataChanged(ids, R.id.friends_list);
        scheduleTick(context, signedIn ? data : null);
    }

    private static PendingIntent tickIntent(Context context) {
        Intent intent = new Intent(context, FriendsWidget.class).setAction(ACTION_TICK);
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // Timers and last-seen times are text ("1m", "1h 27m", "5m ago"), so redraw when the soonest one reaches its next minute.
    private static void scheduleTick(Context context, JSONObject data) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent tick = tickIntent(context);
        JSONArray friends = data == null ? null : data.optJSONArray("friends");
        long soonest = Long.MAX_VALUE;
        for (int i = 0; friends != null && i < friends.length(); i++) {
            JSONObject friend = friends.optJSONObject(i);
            boolean online = friend.optBoolean("online", true);
            long age = online ? elapsedMs(friend) : sinceSeen(friend);
            if (age >= 0 && (!online || friend.isNull("paused_at"))) soonest = Math.min(soonest, 60_000 - age % 60_000);
        }
        if (soonest == Long.MAX_VALUE) {
            alarms.cancel(tick);
            return;
        }
        alarms.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + soonest + 500, tick);
    }

    // How long ago they were last seen; -1 if never.
    static long sinceSeen(JSONObject friend) {
        long seen = QuickSessionWidget.parseIso(friend.isNull("last_seen_at") ? null : friend.optString("last_seen_at", null));
        return seen < 0 ? -1 : Math.max(0, System.currentTimeMillis() - seen);
    }

    // Time on the clock now, or when it was paused; -1 without a start time.
    static long elapsedMs(JSONObject friend) {
        long started = QuickSessionWidget.parseIso(friend.isNull("started_at") ? null : friend.optString("started_at", null));
        if (started < 0) return -1;
        long pausedAt = QuickSessionWidget.parseIso(friend.isNull("paused_at") ? null : friend.optString("paused_at", null));
        return Math.max(0, (pausedAt >= 0 ? pausedAt : System.currentTimeMillis()) - started);
    }

    private static RemoteViews build(Context context, JSONObject data, boolean signedIn, int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_friends);
        WidgetTheme theme = WidgetTheme.forWidget(context, appWidgetId);
        theme.card(views, R.id.friends_root);
        theme.icon(views, R.id.friends_logo, theme.foreground);
        theme.text(views, theme.foreground, R.id.friends_title);
        theme.chip(views, R.id.friends_count);
        theme.text(views, theme.secondaryForeground, R.id.friends_count);
        theme.text(views, theme.muted, R.id.friends_empty);
        views.setOnClickPendingIntent(R.id.friends_root, WidgetSupport.openApp(context, "/activity"));
        // The scrolling list, and the message that shows when it has nothing.
        // The list is built for this widget's theme, so its intent names the widget (and is unique to it).
        Intent adapter = new Intent(context, FriendsService.class).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        adapter.setData(Uri.parse(adapter.toUri(Intent.URI_INTENT_SCHEME)));
        views.setRemoteAdapter(R.id.friends_list, adapter);
        views.setEmptyView(R.id.friends_list, R.id.friends_empty);
        views.setPendingIntentTemplate(R.id.friends_list, WidgetSupport.openAppTemplate(context));

        JSONArray friends = data == null ? null : data.optJSONArray("friends");
        boolean any = signedIn && friends != null && friends.length() > 0;
        views.setViewVisibility(R.id.friends_count, any ? View.VISIBLE : View.GONE);
        if (any) {
            int online = 0;
            for (int i = 0; i < friends.length(); i++) if (friends.optJSONObject(i).optBoolean("online", true)) online++;
            views.setTextViewText(R.id.friends_count, context.getString(R.string.widget_friends_count, online));
        } else {
            views.setTextViewText(R.id.friends_empty, context.getString(!signedIn ? R.string.widget_signed_out_hint
                : data == null ? R.string.widget_friends_loading : R.string.widget_friends_empty));
        }
        return views;
    }
}
