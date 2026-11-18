package uk.co.froglog.app.widgets;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONObject;

import uk.co.froglog.app.R;

/**
 * The notification for a Quick Session started on this phone (in the app, from the Current Activity widget, or by
 * Play on Phone): the game and its icon, Pause/Resume, and Stop, which opens the app at the stop menu.
 *
 * Only a session this phone started is shown. Its server id (or, for one started offline, its start time) is kept,
 * and every refresh compares the server's running session with it: anything else, or nothing, removes the
 * notification. While one is shown, the phone registers for the widgets' silent pushes, so a session stopped
 * elsewhere clears it.
 */
public class SessionNotification extends BroadcastReceiver {
    static final String CHANNEL = "running_session";
    private static final int ID = 4101;
    private static final String TAG = "FrogLogSession";

    private static final String ACTION_PAUSE = "uk.co.froglog.app.widgets.SESSION_PAUSE";
    private static final String ACTION_RESUME = "uk.co.froglog.app.widgets.SESSION_RESUME";
    private static final String ACTION_DISMISSED = "uk.co.froglog.app.widgets.SESSION_DISMISSED";

    // Its own file, not WidgetStore's, so the on/off choice outlives logging out.
    private static final String PREFS = "froglog_session_notification";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_ID = "owned_id";
    private static final String KEY_STARTED = "owned_started";
    private static final String KEY_DISMISSED = "dismissed";
    private static final String KEY_SESSION = "session";
    private static final String KEY_COVER = "cover_url";
    // The game's Android app, when it's linked for Play on Phone, for its icon.
    private static final String KEY_PACKAGE = "package";

    // How far apart the start times of a session started offline and the one the server later has can be.
    private static final long START_MATCH_MS = 5_000;

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, true);
    }

    static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
        if (!enabled) forget(context);
    }

    static boolean owned(Context context) {
        SharedPreferences p = prefs(context);
        return p.contains(KEY_ID) || p.contains(KEY_STARTED);
    }

    static boolean canShow(Context context) {
        return NotificationManagerCompat.from(context).areNotificationsEnabled();
    }

    // A session this phone just started. Shown straight away from what the caller has, then checked against the server.
    static void own(Context context, JSONObject active) {
        own(context, active, null);
    }

    static void own(Context context, JSONObject active, String packageName) {
        Context app = context.getApplicationContext();
        if (active == null || !enabled(app)) return;
        SharedPreferences.Editor edit = prefs(app).edit().clear().putBoolean(KEY_ENABLED, true);
        if (packageName != null && !packageName.isEmpty()) edit.putString(KEY_PACKAGE, packageName);
        if (!active.isNull("id") && active.optInt("id", 0) > 0) edit.putInt(KEY_ID, active.optInt("id"));
        else edit.putLong(KEY_STARTED, QuickSessionWidget.parseIso(active.optString("started_at", null)));
        edit.putString(KEY_SESSION, active.toString()).apply();
        show(app, active, null);
        WidgetSupport.WORKER.execute(() -> {
            WidgetDevices.register(app);
            refreshNow(app);
        });
    }

    // Redraws from the server, when something may have changed. Free when this phone has no session.
    static void refresh(Context context) {
        Context app = context.getApplicationContext();
        if (!owned(app)) return;
        WidgetSupport.WORKER.execute(() -> refreshNow(app));
    }

    // Shows it again from what was last drawn, when notifications have just been allowed.
    static void redraw(Context context) {
        Context app = context.getApplicationContext();
        JSONObject session = WidgetSupport.parse(prefs(app).getString(KEY_SESSION, null));
        if (session != null && owned(app)) show(app, session, prefs(app).getString(KEY_COVER, null));
    }

    // Removes it and stops following the session. On logout too.
    static void forget(Context context) {
        Context app = context.getApplicationContext();
        boolean enabled = enabled(app);
        prefs(app).edit().clear().putBoolean(KEY_ENABLED, enabled).apply();
        NotificationManagerCompat.from(app).cancel(ID);
        WidgetSupport.WORKER.execute(() -> {
            if (!WidgetDevices.anyPlaced(app)) WidgetDevices.unregister(app);
        });
    }

    // On WidgetSupport.WORKER.
    private static void refreshNow(Context context) {
        if (!owned(context)) return;
        if (!WidgetStore.isSignedIn(context)) {
            forget(context);
            return;
        }
        try {
            FroglogApi.Response response = FroglogApi.get(context, "/api/live-session/active");
            if (response.status == 401) {
                forget(context);
                return;
            }
            if (!response.ok()) throw new IllegalStateException("HTTP " + response.status);
            sync(context, response.body.optJSONObject("active"), response.body.optString("cover_url", null));
        } catch (Exception offline) {
            // Keeps what's shown: the timer runs on its own.
            Log.w(TAG, "fetch failed, keeping the notification: " + offline);
        }
    }

    private static void sync(Context context, JSONObject active, String cover) {
        if (!matches(context, active)) {
            Log.i(TAG, "the running session isn't this phone's, removing the notification");
            forget(context);
            return;
        }
        boolean wasPaused = wasPaused(context);
        SharedPreferences.Editor edit = prefs(context).edit().remove(KEY_STARTED).putInt(KEY_ID, active.optInt("id")).putString(KEY_SESSION, active.toString());
        String coverUrl = cover == null || cover.isEmpty() || "null".equals(cover) ? null : cover;
        edit.putString(KEY_COVER, coverUrl);
        // Swiped away: it stays away for this session, unless it is paused or resumed.
        boolean pausedNow = !active.isNull("paused_at");
        if (pausedNow != wasPaused) edit.remove(KEY_DISMISSED);
        edit.apply();
        if (prefs(context).getBoolean(KEY_DISMISSED, false)) return;
        show(context, active, coverUrl);
        if (coverUrl != null && CoverArt.cached(context, coverUrl) == null) {
            WidgetSupport.IMAGES.execute(() -> {
                CoverArt.ensure(context, coverUrl);
                if (CoverArt.cached(context, coverUrl) != null && owned(context) && !prefs(context).getBoolean(KEY_DISMISSED, false)) {
                    JSONObject latest = WidgetSupport.parse(prefs(context).getString(KEY_SESSION, null));
                    if (latest != null) show(context, latest, coverUrl);
                }
            });
        }
    }

    private static boolean wasPaused(Context context) {
        JSONObject last = WidgetSupport.parse(prefs(context).getString(KEY_SESSION, null));
        return last != null && !last.isNull("paused_at");
    }

    // A Quick Session, and the one this phone started: the same id, or for one started offline, the same start time.
    private static boolean matches(Context context, JSONObject active) {
        if (active == null || !"manual".equals(active.optString("source"))) return false;
        SharedPreferences p = prefs(context);
        if (p.contains(KEY_ID)) return p.getInt(KEY_ID, -1) == active.optInt("id", -2);
        long started = QuickSessionWidget.parseIso(active.optString("started_at", null));
        return started >= 0 && Math.abs(started - p.getLong(KEY_STARTED, Long.MIN_VALUE / 2)) <= START_MATCH_MS;
    }

    private static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL, context.getString(R.string.session_notification_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(context.getString(R.string.session_notification_channel_description));
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private static void show(Context context, JSONObject active, String coverUrl) {
        if (!canShow(context)) return;
        ensureChannel(context);
        String title = active.optString("title", "");
        if (title.isEmpty() || "null".equals(title)) title = context.getString(R.string.session_notification_untitled);
        boolean paused = !active.isNull("paused_at");

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(WidgetSupport.openApp(context, QuickSessionWidget.gameUrl(active)))
            .setDeleteIntent(broadcast(context, ACTION_DISMISSED));
        // No timer (the user's call): just whether it's running or paused.
        builder.setShowWhen(false);
        if (paused) {
            builder.setContentText(context.getString(R.string.session_notification_paused))
                .addAction(R.drawable.ic_widget_play, context.getString(R.string.session_notification_resume), broadcast(context, ACTION_RESUME));
        } else {
            builder.setContentText(context.getString(R.string.session_notification_running))
                .addAction(R.drawable.ic_widget_pause, context.getString(R.string.session_notification_pause), broadcast(context, ACTION_PAUSE));
        }
        builder.addAction(R.drawable.ic_widget_stop, context.getString(R.string.session_notification_stop), WidgetSupport.openApp(context, QuickSessionWidget.URL_STOP));

        // The game's own app icon when it's linked for Play on Phone, else its cover.
        Bitmap icon = appIcon(context, prefs(context).getString(KEY_PACKAGE, null));
        if (icon == null && coverUrl != null) icon = CoverArt.cached(context, coverUrl);
        if (icon != null) builder.setLargeIcon(icon);

        // Android 16's Live Updates: asks for the timer to show as a status bar chip. Older versions ignore the extra.
        Bundle extras = new Bundle();
        extras.putBoolean("android.requestPromotedOngoing", true);
        builder.addExtras(extras);

        try {
            NotificationManagerCompat.from(context).notify(ID, builder.build());
        } catch (SecurityException notAllowed) {
            Log.w(TAG, "notifications not allowed: " + notAllowed);
        }
    }

    private static Bitmap appIcon(Context context, String packageName) {
        if (packageName == null) return null;
        try {
            Drawable drawable = context.getPackageManager().getApplicationIcon(packageName);
            int px = Math.round(64 * context.getResources().getDisplayMetrics().density);
            Bitmap bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, px, px);
            drawable.draw(canvas);
            return bitmap;
        } catch (Exception uninstalled) {
            return null;
        }
    }

    private static PendingIntent broadcast(Context context, String action) {
        Intent intent = new Intent(context, SessionNotification.class).setAction(action);
        return PendingIntent.getBroadcast(context, action.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Context app = context.getApplicationContext();
        if (ACTION_DISMISSED.equals(action)) {
            if (owned(app)) prefs(app).edit().putBoolean(KEY_DISMISSED, true).apply();
            return;
        }
        if (!ACTION_PAUSE.equals(action) && !ACTION_RESUME.equals(action)) return;
        PendingResult pending = goAsync();
        WidgetSupport.WORKER.execute(() -> {
            try {
                if (!WidgetStore.isSignedIn(app)) {
                    forget(app);
                    return;
                }
                FroglogApi.Response response = FroglogApi.post(app, ACTION_PAUSE.equals(action) ? "/api/live-session/pause" : "/api/live-session/resume", null);
                Log.i(TAG, action + ": HTTP " + response.status);
                if (response.status == 404 || response.status == 401) forget(app);
                else if (response.ok()) sync(app, response.body.optJSONObject("active"), prefs(app).getString(KEY_COVER, null));
                // The widget shows the same session.
                QuickSessionWidget.refresh(app);
            } catch (Exception offline) {
                Log.w(TAG, action + " failed: " + offline);
            } finally {
                pending.finish();
            }
        });
    }
}
