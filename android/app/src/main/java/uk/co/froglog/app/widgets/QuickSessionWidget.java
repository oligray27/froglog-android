package uk.co.froglog.app.widgets;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.util.ArrayMap;
import android.util.SizeF;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import uk.co.froglog.app.R;

/**
 * Home screen widget for the running session: the game, its elapsed time in minutes (redrawn each minute), and
 * Pause/Resume and Stop for a Quick Session. With nothing running it offers the last played game.
 *
 * It fetches /api/live-session/active when added, every 30 minutes, after its own buttons, and
 * whenever the site tells it something changed (FrogLogWidgetsPlugin.update). Stop opens the app,
 * because stopping needs the Auto Save / Manual / Discard choice.
 */
public class QuickSessionWidget extends AppWidgetProvider {
    static final String ACTION_REFRESH = "uk.co.froglog.app.widgets.QUICK_SESSION_REFRESH";
    private static final String ACTION_PAUSE = "uk.co.froglog.app.widgets.QUICK_SESSION_PAUSE";
    private static final String ACTION_RESUME = "uk.co.froglog.app.widgets.QUICK_SESSION_RESUME";
    private static final String ACTION_START = "uk.co.froglog.app.widgets.QUICK_SESSION_START";
    // Pause and Resume for a session LilyPad is tracking, relayed to the PC through the server.
    private static final String ACTION_TRACKER_PAUSE = "uk.co.froglog.app.widgets.QUICK_SESSION_TRACKER_PAUSE";
    private static final String ACTION_TRACKER_RESUME = "uk.co.froglog.app.widgets.QUICK_SESSION_TRACKER_RESUME";

    private static final String ACTION_TICK = "uk.co.froglog.app.widgets.QUICK_SESSION_TICK";

    // Asks the app to open the Quick Session stop menu rather than a page.
    static final String URL_STOP = "quick-session:stop";

    static final String CACHE_ACTIVE = "quick_session_active";
    static final String CACHE_LAST_PLAYED = "quick_session_last_played";
    // While this is set (only the dev build's DebugSessionReceiver sets it) the widget shows its cached state and asks the server nothing.
    static final String CACHE_DEBUG_HOLD = "quick_session_debug_hold";

    static void refresh(Context context) {
        context.sendBroadcast(new Intent(context, QuickSessionWidget.class).setAction(ACTION_REFRESH));
    }

    static void showDebugState(Context context, String activeJson, String lastPlayedJson) {
        WidgetStore.putCache(context, CACHE_ACTIVE, activeJson);
        WidgetStore.putCache(context, CACHE_LAST_PLAYED, lastPlayedJson);
        WidgetStore.putCache(context, CACHE_DEBUG_HOLD, "1");
        render(context, parse(activeJson), parse(lastPlayedJson), true);
    }

    static void clearDebugState(Context context) {
        WidgetStore.putCache(context, CACHE_DEBUG_HOLD, null);
        refresh(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        boolean ours = AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(action) || ACTION_REFRESH.equals(action)
            || ACTION_PAUSE.equals(action) || ACTION_RESUME.equals(action) || ACTION_START.equals(action)
            || ACTION_TRACKER_PAUSE.equals(action) || ACTION_TRACKER_RESUME.equals(action) || ACTION_TICK.equals(action);
        if (!ours) {
            super.onReceive(context, intent);
            return;
        }
        Context app = context.getApplicationContext();
        PendingResult pending = goAsync();
        WidgetSupport.WORKER.execute(() -> {
            try {
                if (ACTION_TICK.equals(action)) {
                    // Only the clock moved, so redraw from what's cached.
                    render(app, parse(WidgetStore.cache(app, CACHE_ACTIVE)), parse(WidgetStore.cache(app, CACHE_LAST_PLAYED)), WidgetStore.isSignedIn(app));
                    return;
                }
                act(app, action);
                fetchAndRender(app);
            } finally {
                pending.finish();
            }
        });
    }

    private static void act(Context context, String action) {
        if (!WidgetStore.isSignedIn(context) || WidgetStore.cache(context, CACHE_DEBUG_HOLD) != null) return;
        try {
            if (ACTION_PAUSE.equals(action)) FroglogApi.post(context, "/api/live-session/pause", null);
            else if (ACTION_RESUME.equals(action)) FroglogApi.post(context, "/api/live-session/resume", null);
            else if (ACTION_TRACKER_PAUSE.equals(action) || ACTION_TRACKER_RESUME.equals(action)) {
                JSONObject cached = parse(WidgetStore.cache(context, CACHE_ACTIVE));
                JSONObject tracker = cached == null ? null : cached.optJSONObject("tracker");
                JSONObject device = tracker == null ? null : tracker.optJSONObject("device");
                if (device == null || device.isNull("id") || tracker.isNull("ledger_id")) return;
                JSONObject body = new JSONObject();
                body.put("type", ACTION_TRACKER_PAUSE.equals(action) ? "pause" : "resume");
                body.put("ledger_id", tracker.optString("ledger_id"));
                FroglogApi.post(context, "/api/lilypad/devices/" + device.optString("id") + "/commands", body);
            } else if (ACTION_START.equals(action)) {
                JSONObject game = parse(WidgetStore.cache(context, CACHE_LAST_PLAYED));
                if (game == null) return;
                JSONObject body = new JSONObject();
                body.put("game_type", game.optString("game_type"));
                body.put("game_id", game.optInt("game_id"));
                FroglogApi.post(context, "/api/live-session/start", body);
            }
        } catch (Exception ignored) {
            // The fetch that follows shows whatever state the session is really in.
        }
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        WidgetTheme.forget(context, appWidgetIds);
    }

    @Override
    public void onDisabled(Context context) {
        ((AlarmManager) context.getSystemService(Context.ALARM_SERVICE)).cancel(action(context, ACTION_TICK));
        WidgetSupport.lastOneRemoved(context);
    }

    private static void fetchAndRender(Context context) {
        if (WidgetStore.cache(context, CACHE_DEBUG_HOLD) != null) {
            render(context, parse(WidgetStore.cache(context, CACHE_ACTIVE)), parse(WidgetStore.cache(context, CACHE_LAST_PLAYED)), true);
            return;
        }
        if (!WidgetStore.isSignedIn(context)) {
            render(context, null, null, false);
            return;
        }
        WidgetDevices.register(context);
        try {
            FroglogApi.Response active = FroglogApi.get(context, "/api/live-session/active");
            if (active.status == 401) {
                WidgetStore.clearLogin(context);
                render(context, null, null, false);
                return;
            }
            if (!active.ok()) throw new IllegalStateException("HTTP " + active.status);
            WidgetStore.putCache(context, CACHE_ACTIVE, active.body.toString());
            if (active.body.isNull("active") && active.body.isNull("tracker")) {
                FroglogApi.Response last = FroglogApi.get(context, "/api/live-session/last-played");
                if (last.ok()) {
                    JSONObject game = last.body.optJSONObject("game");
                    WidgetStore.putCache(context, CACHE_LAST_PLAYED, game == null ? null : game.toString());
                }
            }
        } catch (Exception offline) {
            // Falls through to the cached answer, so the timer keeps going without a connection.
        }
        render(context, parse(WidgetStore.cache(context, CACHE_ACTIVE)), parse(WidgetStore.cache(context, CACHE_LAST_PLAYED)), true);
    }

    private static void render(Context context, JSONObject state, JSONObject lastPlayed, boolean signedIn) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, QuickSessionWidget.class));
        if (ids.length == 0) return;
        // Each widget has its own theme, so each is built for it.
        for (int id : ids) {
            WidgetTheme theme = WidgetTheme.forWidget(context, id);
            RemoteViews views;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // The launcher shows the largest entry that fits: the header needs height, and the width decides how much sits beside the timer.
                Map<SizeF, RemoteViews> sizes = new ArrayMap<>();
                float[] widths = { 100f, NARROW_WIDTH_DP, WIDE_WIDTH_DP };
                for (int w = 0; w < widths.length; w++) {
                    boolean tiny = w == 0;
                    boolean narrow = w <= 1;
                    sizes.put(new SizeF(widths[w], 40f), build(context, state, lastPlayed, signedIn, true, narrow, tiny, theme));
                    sizes.put(new SizeF(widths[w], FULL_HEIGHT_DP), build(context, state, lastPlayed, signedIn, false, narrow, tiny, theme));
                }
                views = new RemoteViews(sizes);
            } else {
                views = build(context, state, lastPlayed, signedIn, false, false, false, theme);
            }
            manager.updateAppWidget(id, views);
        }
        scheduleTick(context, signedIn ? state : null);
    }

    // The timer is text ("1m", "1h 27m") rather than a Chronometer, so it needs a redraw each minute while a session runs.
    private static void scheduleTick(Context context, JSONObject state) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent tick = action(context, ACTION_TICK);
        JSONObject active = state == null ? null : state.optJSONObject("active");
        JSONObject session = active != null ? active : state == null ? null : state.optJSONObject("tracker");
        if (session == null || !session.isNull("paused_at")) {
            alarms.cancel(tick);
            return;
        }
        long intoMinute = elapsedMs(session) % 60_000;
        alarms.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + (60_000 - intoMinute) + 500, tick);
    }

    // "1m" for the first minute, then "42m", then "1h 27m".
    static String formatElapsed(long ms) {
        long minutes = Math.max(1, ms / 60_000);
        if (minutes < 60) return minutes + "m";
        long hours = minutes / 60;
        long rest = minutes % 60;
        return rest == 0 ? hours + "h" : hours + "h " + rest + "m";
    }

    // A two-row widget. Samsung reports about 127dp a row, because it lays widgets out larger and scales them down.
    private static final float FULL_HEIGHT_DP = 190f;

    // Wider than three columns, and wider than two. Samsung reports about 149dp a column (298 for two, 446 for three, 595 for four), for the same scaling reason.
    private static final float WIDE_WIDTH_DP = 520f;
    private static final float NARROW_WIDTH_DP = 370f;

    // Narrow is three columns or fewer, and tiny two or fewer. Narrow: the text drops its prefixes ("In Game:", "Paused:", "Last Session:", "Duration:") and keeps the game's name and the time.
    private static RemoteViews build(Context context, JSONObject state, JSONObject lastPlayed, boolean signedIn, boolean compact, boolean narrow, boolean tiny, WidgetTheme theme) {
        RemoteViews views = buildContent(context, state, lastPlayed, signedIn, compact, narrow, theme);
        // Two columns or fewer: no room for the buttons or the platform, so only the logo and the text stay.
        if (tiny) {
            views.setViewVisibility(R.id.widget_actions, View.GONE);
            views.setViewVisibility(R.id.widget_app_icon, View.GONE);
            views.setViewVisibility(R.id.widget_divider, View.GONE);
        }
        return views;
    }

    // Compact is for a one-row widget: no header, less padding, and smaller text so the lines fit beside the buttons.
    private static RemoteViews buildContent(Context context, JSONObject state, JSONObject lastPlayed, boolean signedIn, boolean compact, boolean narrow, WidgetTheme theme) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_quick_session);
        // The widget's colours, set every time like everything else here.
        theme.card(views, R.id.widget_root);
        theme.icon(views, R.id.widget_header_logo, theme.foreground);
        theme.icon(views, R.id.widget_logo, theme.foreground);
        theme.text(views, theme.foreground, R.id.widget_header_title, R.id.widget_title, R.id.widget_timer);
        theme.text(views, theme.muted, R.id.widget_hint, R.id.widget_detail);
        theme.chip(views, R.id.widget_app_icon, R.id.widget_primary, R.id.widget_stop);
        theme.divider(views, R.id.widget_divider);
        theme.icon(views, R.id.widget_stop, WidgetTheme.NO_FILTER);
        // Everything is set on every build, never only for one case: the launcher may apply this over the view it already
        // showed, and anything not set here would keep its last value (a stopped game's platform icon stayed on that way).
        float density = context.getResources().getDisplayMetrics().density;
        int pad = Math.round((compact ? 10 : 16) * density);
        views.setViewVisibility(R.id.widget_title_bar, compact ? View.GONE : View.VISIBLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setViewLayoutMargin(R.id.widget_content, RemoteViews.MARGIN_TOP, compact ? 0 : 12, TypedValue.COMPLEX_UNIT_DIP);
            // The logo is as tall as three lines of the text beside it.
            views.setViewLayoutWidth(R.id.widget_logo, compact ? 62 : 76, TypedValue.COMPLEX_UNIT_DIP);
            views.setViewLayoutHeight(R.id.widget_logo, compact ? 62 : 76, TypedValue.COMPLEX_UNIT_DIP);
        }
        views.setViewPadding(R.id.widget_root, Math.round(16 * density), pad, Math.round(16 * density), pad);
        views.setTextViewTextSize(R.id.widget_title, TypedValue.COMPLEX_UNIT_SP, compact ? 18 : 21);
        views.setTextViewTextSize(R.id.widget_timer, TypedValue.COMPLEX_UNIT_SP, compact ? 26 : 36);
        views.setTextViewTextSize(R.id.widget_hint, TypedValue.COMPLEX_UNIT_SP, compact ? 16 : 21);
        views.setTextViewTextSize(R.id.widget_detail, TypedValue.COMPLEX_UNIT_SP, compact ? 16 : 21);
        // The big logo only shows without the header, which already has the small one.
        views.setViewVisibility(R.id.widget_logo, compact ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.widget_app_icon, View.GONE);
        views.setViewVisibility(R.id.widget_divider, View.GONE);
        views.setTextViewCompoundDrawablesRelative(R.id.widget_title, 0, 0, 0, 0);
        views.setContentDescription(R.id.widget_title, null);
        views.setViewVisibility(R.id.widget_timer, View.GONE);
        views.setViewVisibility(R.id.widget_hint, View.GONE);
        views.setViewVisibility(R.id.widget_detail, View.GONE);
        views.setViewVisibility(R.id.widget_actions, View.GONE);
        views.setViewVisibility(R.id.widget_stop, View.GONE);

        if (!signedIn) {
            views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_signed_out_title));
            showText(views, R.id.widget_hint, context.getString(R.string.widget_signed_out_hint));
            views.setOnClickPendingIntent(R.id.widget_root, openApp(context, "/games"));
            return views;
        }

        JSONObject active = state == null ? null : state.optJSONObject("active");
        JSONObject tracker = state == null ? null : state.optJSONObject("tracker");
        JSONObject session = active != null ? active : tracker;

        if (session == null) {
            String title = lastPlayed == null ? null : lastPlayed.optString("title", null);
            views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_idle_title));
            showText(views, R.id.widget_hint, title == null ? context.getString(R.string.widget_idle_hint) : boldGame(context, R.string.widget_last_session, title, narrow));
            double hours = lastPlayed == null ? 0 : lastPlayed.optDouble("last_session_hours", 0);
            if (title != null && hours > 0) {
                showText(views, R.id.widget_detail, narrow ? formatHours(hours) : context.getString(R.string.widget_last_duration, formatHours(hours)));
            }
            if (title != null) {
                showPrimary(views, theme, context.getString(R.string.widget_start), R.drawable.ic_widget_play, action(context, ACTION_START));
            }
            views.setOnClickPendingIntent(R.id.widget_root, openApp(context, "/games"));
            return views;
        }

        // At three columns, whatever the height, the buttons carry the session, so a Quick Session or LilyPad skips its icon and the divider.
        boolean hidePlatform = narrow;
        boolean paused = !session.isNull("paused_at");
        String game = session.optString("title", context.getString(R.string.widget_unknown_game));
        // A live dot (amber when paused) stands in for "In Game:"; the words stay for screen readers.
        views.setTextViewText(R.id.widget_title, boldGame(context, paused ? R.string.widget_paused_game : R.string.widget_in_game, game, true));
        views.setTextViewCompoundDrawablesRelative(R.id.widget_title, paused ? R.drawable.ic_widget_paused : R.drawable.ic_widget_live, 0, 0, 0);
        views.setContentDescription(R.id.widget_title, context.getString(paused ? R.string.widget_paused_game : R.string.widget_in_game, game));
        views.setViewVisibility(R.id.widget_timer, View.VISIBLE);
        views.setTextViewText(R.id.widget_timer, formatElapsed(elapsedMs(session)));
        views.setOnClickPendingIntent(R.id.widget_root, openApp(context, gameUrl(session)));

        if (active != null && "manual".equals(active.optString("source"))) {
            if (paused) {
                showPrimary(views, theme, context.getString(R.string.widget_resume), R.drawable.ic_widget_play, action(context, ACTION_RESUME));
            } else {
                showPrimary(views, theme, context.getString(R.string.widget_pause), R.drawable.ic_widget_pause, action(context, ACTION_PAUSE));
            }
            views.setViewVisibility(R.id.widget_stop, View.VISIBLE);
            views.setOnClickPendingIntent(R.id.widget_stop, openApp(context, URL_STOP));
            if (!hidePlatform) {
                showPlatformIcon(views, theme, R.drawable.ic_widget_stopwatch);
                views.setViewVisibility(R.id.widget_divider, View.VISIBLE);
            }
        } else if (active != null) {
            // Tracked by a platform we poll, which we can show but not control.
            showPlatformIcon(views, theme, platformIcon(active.optString("source")));
        } else {
            int icon = platformIcon(tracker.optString("platform"));
            boolean lilypad = icon == R.drawable.ic_widget_lilypad;
            if (!(hidePlatform && lilypad)) showPlatformIcon(views, theme, icon);
            JSONObject device = tracker.optJSONObject("device");
            if (device != null && device.optBoolean("remote_control") && !device.isNull("id") && !tracker.isNull("ledger_id")) {
                if (!(hidePlatform && lilypad)) views.setViewVisibility(R.id.widget_divider, View.VISIBLE);
                if (paused) {
                    showPrimary(views, theme, context.getString(R.string.widget_resume), R.drawable.ic_widget_play, action(context, ACTION_TRACKER_RESUME));
                } else {
                    showPrimary(views, theme, context.getString(R.string.widget_pause), R.drawable.ic_widget_pause, action(context, ACTION_TRACKER_PAUSE));
                }
            }
        }
        return views;
    }

    private static void showPlatformIcon(RemoteViews views, WidgetTheme theme, int icon) {
        views.setViewVisibility(R.id.widget_app_icon, View.VISIBLE);
        // The stopwatch is a white shape that takes the theme's text colour; the platform logos keep their own.
        theme.icon(views, R.id.widget_app_icon, icon == R.drawable.ic_widget_stopwatch ? theme.foreground : WidgetTheme.NO_FILTER);
        views.setImageViewResource(R.id.widget_app_icon, icon);
    }

    // "In Game: " in the normal weight and the game’s name in bold. The string takes the name as its one argument.
    private static CharSequence boldGame(Context context, int format, String game, boolean nameOnly) {
        int start = nameOnly ? 0 : context.getString(format, "").length();
        SpannableString text = new SpannableString(nameOnly ? game : context.getString(format, game));
        text.setSpan(new StyleSpan(Typeface.BOLD), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    private static void showText(RemoteViews views, int id, CharSequence text) {
        views.setViewVisibility(id, View.VISIBLE);
        views.setTextViewText(id, text);
    }

    private static void showPrimary(RemoteViews views, WidgetTheme theme, String text, int icon, PendingIntent intent) {
        // Pause is a white shape in the theme's text colour; play and stop keep their green and red.
        theme.icon(views, R.id.widget_primary, icon == R.drawable.ic_widget_pause ? theme.foreground : WidgetTheme.NO_FILTER);
        views.setViewVisibility(R.id.widget_actions, View.VISIBLE);
        views.setImageViewResource(R.id.widget_primary, icon);
        views.setContentDescription(R.id.widget_primary, text);
        views.setOnClickPendingIntent(R.id.widget_primary, intent);
    }

    // "2.4h", "2h" for a whole number, or "25m" under an hour.
    private static String formatHours(double hours) {
        if (hours < 1) return Math.max(1, Math.round(hours * 60)) + "m";
        double rounded = Math.round(hours * 10) / 10.0;
        return (rounded == Math.rint(rounded) ? String.valueOf((long) rounded) : String.valueOf(rounded)) + "h";
    }

    // The icon for a session’s source or platform key; LilyPad for anything else, as with platformName.
    private static int platformIcon(String key) {
        switch (key == null ? "" : key) {
            case "steam_poll": case "steam": return R.drawable.ic_widget_steam;
            case "psn_poll": case "psn": return R.drawable.ic_widget_psn;
            case "xbox_poll": case "xbox": return R.drawable.ic_widget_xbox;
            case "switch_poll": case "switch": return R.drawable.ic_widget_switch;
            default: return R.drawable.ic_widget_lilypad;
        }
    }

    private static String platformName(String key) {
        switch (key == null ? "" : key) {
            case "steam_poll": case "steam": return "Steam";
            case "psn_poll": case "psn": return "PlayStation";
            case "xbox_poll": case "xbox": return "Xbox";
            case "switch_poll": case "switch": return "Switch";
            default: return "LilyPad";
        }
    }

    private static String gameUrl(JSONObject session) {
        int id = session.optInt("game_id", 0);
        if (id == 0) return "/games";
        return "live_service".equals(session.optString("game_type")) ? "/games/" + id + "?view=live" : "/games/" + id;
    }

    // Matches the site's useElapsed: time since the start, less time spent paused, frozen while paused.
    private static long elapsedMs(JSONObject session) {
        long started = parseIso(session.optString("started_at", null));
        if (started < 0) return 0;
        long pausedAt = parseIso(session.isNull("paused_at") ? null : session.optString("paused_at", null));
        long end = pausedAt >= 0 ? pausedAt : System.currentTimeMillis();
        long pausedMs = Math.round(session.optDouble("paused_seconds", 0) * 1000);
        return Math.max(0, end - started - pausedMs);
    }

    private static final Pattern ISO = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})(?:\\.(\\d+))?(Z|[+-]\\d{2}:?\\d{2})?$");

    // Postgres sends milliseconds and LilyPad can send nanoseconds, which SimpleDateFormat can't read, so the fraction is handled here.
    static long parseIso(String value) {
        if (value == null) return -1;
        Matcher match = ISO.matcher(value);
        if (!match.matches()) return -1;
        String zone = match.group(3) == null || "Z".equals(match.group(3)) ? "+0000" : match.group(3).replace(":", "");
        try {
            long seconds = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).parse(match.group(1) + zone).getTime();
            String fraction = match.group(2);
            int millis = fraction == null ? 0 : Integer.parseInt((fraction + "00").substring(0, 3));
            return seconds + millis;
        } catch (Exception e) {
            return -1;
        }
    }

    private static JSONObject parse(String json) {
        return WidgetSupport.parse(json);
    }

    private static PendingIntent openApp(Context context, String url) {
        return WidgetSupport.openApp(context, url);
    }

    private static PendingIntent action(Context context, String action) {
        Intent intent = new Intent(context, QuickSessionWidget.class).setAction(action);
        return PendingIntent.getBroadcast(context, action.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
