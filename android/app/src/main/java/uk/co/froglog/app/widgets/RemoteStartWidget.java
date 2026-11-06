package uk.co.froglog.app.widgets;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import uk.co.froglog.app.R;
import uk.co.froglog.app.remote.RemoteBridge;

/**
 * Home screen widget that starts a recently played game on a linked PC (REMOTE_START_PLAN.md). The list is your recent games from
 * /api/widgets/remote-start, kept to the ones the PC says it can launch (its /launchable: installed Steam games and mapped executables),
 * as a scrolling list (RemoteStartService). Tapping a row sends the launch straight to the PC over the LAN, as Play on PC does; nothing
 * about the launch goes through the FrogLog server.
 *
 * RemoteStartFlexWidget is the same widget on the Galaxy Z Flip's cover screen: square corners, extra room at the bottom, and the header
 * doesn't open the app there. The rows still start games, since that goes to the PC and opens nothing on the phone.
 *
 * Each widget has its own PC, chosen on the setup screen (WidgetThemeActivity) when more than one is linked, else the first linked PC, so
 * there can be one widget per PC. Lists and launch results are kept per PC. It refreshes when placed, every 30 minutes, when the site starts
 * or signs in (refreshAll), and after each launch. While a session is running anywhere (a Quick Session, LilyPad, or a platform's presence:
 * the same as Play on PC's "in game"), the play buttons are dimmed and a tap only says so. That state comes from /api/live-session/active,
 * fetched on every refresh, including the silent pushes the backend sends when your own session starts or stops.
 */
public class RemoteStartWidget extends AppWidgetProvider {
    private static final String TAG = "FrogLogWidgets";
    private static final String ACTION_REFRESH = "uk.co.froglog.app.widgets.REMOTE_START_REFRESH";
    private static final String ACTION_LAUNCH = "uk.co.froglog.app.widgets.REMOTE_START_LAUNCH";
    // Redraws once a launch's result has been shown for long enough, to take it away.
    private static final String ACTION_CLEAR = "uk.co.froglog.app.widgets.REMOTE_START_CLEAR";
    static final String EXTRA_TYPE = "game_type";
    static final String EXTRA_ID = "game_id";
    static final String EXTRA_STEAM = "steam_launch_id";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_PC = "pc_id";
    // The recent games from the API ({ username, games }).
    private static final String CACHE_RECENT = "remote_start_recent";
    // Per PC (the key ends with its id): { pc: {id, name}, state, problem, games }, and a launch in progress or just finished: { text, at, busy }.
    private static final String CACHE_LIST = "remote_start_";
    private static final String CACHE_STATUS = "remote_start_status_";
    // /api/live-session/active: { active, tracker }. Either one set means a session is running.
    static final String CACHE_ACTIVE = "remote_start_active";
    // Each widget's PC, kept apart from WidgetStore (which logout clears) like the themes are.
    private static final String PC_PREFS = "froglog_widget_pcs";
    private static final int SHOWN = 8;
    private static final long STATUS_SHOWN_MS = 12_000;
    private static final long RECHECK_AFTER_MS = 2 * 60 * 1000;
    // A launch waits up to 90 seconds for someone at the PC to answer "Ask me before launching", so it has its own thread rather than
    // holding up every widget's WORKER.
    private static final ExecutorService LAUNCHES = Executors.newSingleThreadExecutor();
    private static final ExecutorService PC_CHECKS = Executors.newSingleThreadExecutor();

    static void refresh(Context context) {
        context.sendBroadcast(new Intent(context, RemoteStartWidget.class).setAction(ACTION_REFRESH));
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Context app = context.getApplicationContext();
        if (ACTION_LAUNCH.equals(action)) {
            launch(app, intent);
            return;
        }
        if (!AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(action) && !ACTION_REFRESH.equals(action) && !ACTION_CLEAR.equals(action)) {
            super.onReceive(context, intent);
            return;
        }
        PendingResult pending = goAsync();
        WidgetSupport.WORKER.execute(() -> {
            try {
                // The site asks every widget to refresh on load and on every Quick Session change, which says nothing new about
                // what the PCs can start, so recent checks stand. Placing it, the 30-minute update and a launch always check.
                // Whether a session is running is fetched every time, since a Quick Session change is exactly what changes it.
                if (ACTION_CLEAR.equals(action)) {
                    render(app);
                } else if (ACTION_REFRESH.equals(action) && allCheckedRecently(app)) {
                    if (WidgetStore.isSignedIn(app)) fetchActive(app);
                    render(app);
                } else {
                    fetchAndRender(app);
                }
            } finally {
                pending.finish();
            }
        });
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        WidgetTheme.forget(context, appWidgetIds);
        SharedPreferences.Editor editor = pcPrefs(context).edit();
        for (int id : appWidgetIds) editor.remove(String.valueOf(id));
        editor.apply();
    }

    @Override
    public void onDisabled(Context context) {
        WidgetSupport.lastOneRemoved(context);
    }

    static boolean isRemoteStart(Context context, int appWidgetId) {
        android.appwidget.AppWidgetProviderInfo info = AppWidgetManager.getInstance(context).getAppWidgetInfo(appWidgetId);
        if (info == null) return false;
        String provider = info.provider.getClassName();
        return RemoteStartWidget.class.getName().equals(provider) || RemoteStartFlexWidget.class.getName().equals(provider);
    }

    // The home screen widgets and their cover screen twins, drawn together.
    static int[] ids(Context context) {
        return WidgetSupport.ids(context, RemoteStartWidget.class, RemoteStartFlexWidget.class);
    }

    private static SharedPreferences pcPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PC_PREFS, Context.MODE_PRIVATE);
    }

    static void setPcChoice(Context context, int appWidgetId, String pcId) {
        pcPrefs(context).edit().putString(String.valueOf(appWidgetId), pcId).apply();
    }

    // The account linked PCs are kept under: the one the API named, else the last one the site used.
    static String account(Context context) {
        JSONObject recent = WidgetSupport.parse(WidgetStore.cache(context, CACHE_RECENT));
        String named = recent == null ? "" : recent.optString("username", "");
        return named.isEmpty() ? RemoteBridge.lastAccount(context) : named;
    }

    static JSONArray devices(Context context) {
        return RemoteBridge.devices(context, account(context));
    }

    /** The widget's PC: the one chosen for it while it is still linked, else the first linked PC; null with none linked. */
    static JSONObject pcFor(Context context, int appWidgetId, JSONArray devices) {
        if (devices.length() == 0) return null;
        String chosen = pcPrefs(context).getString(String.valueOf(appWidgetId), null);
        for (int i = 0; chosen != null && i < devices.length(); i++) {
            JSONObject device = devices.optJSONObject(i);
            if (device != null && chosen.equals(device.optString("id"))) return device;
        }
        return devices.optJSONObject(0);
    }

    // The PC can't take a launch: it didn't answer, or LilyPad there is signed out, in another account or has remote devices off.
    static boolean isUnavailable(JSONObject list) {
        String state = list == null ? "" : list.optString("state");
        return "unreachable".equals(state) || "problem".equals(state);
    }

    static JSONObject listFor(Context context, String pcId) {
        return pcId == null ? null : WidgetSupport.parse(WidgetStore.cache(context, CACHE_LIST + pcId));
    }

    // The PCs the placed widgets use, each once.
    private static Set<String> pcsInUse(Context context, JSONArray devices) {
        Set<String> used = new LinkedHashSet<>();
        for (int id : ids(context)) {
            JSONObject pc = pcFor(context, id, devices);
            if (pc != null) used.add(pc.optString("id"));
        }
        return used;
    }

    private static boolean allCheckedRecently(Context context) {
        if (WidgetStore.cache(context, CACHE_RECENT) == null) return false;
        for (String pcId : pcsInUse(context, devices(context))) {
            if (WidgetStore.cacheAge(context, CACHE_LIST + pcId) >= RECHECK_AFTER_MS) return false;
        }
        return true;
    }

    private static void fetchAndRender(Context context) {
        if (ids(context).length == 0) return;
        if (!WidgetStore.isSignedIn(context)) {
            render(context);
            return;
        }
        WidgetDevices.register(context);
        fetchActive(context);
        try {
            FroglogApi.Response recent = FroglogApi.get(context, "/api/widgets/remote-start");
            Log.i(TAG, "remote start: fetched, HTTP " + recent.status);
            if (recent.status == 401) {
                WidgetStore.clearLogin(context);
                render(context);
                return;
            }
            if (recent.ok()) WidgetStore.putCache(context, CACHE_RECENT, recent.body.toString());
        } catch (Exception offline) {
            Log.w(TAG, "remote start: fetch failed, using the saved list: " + offline);
        }
        render(context);
        JSONObject recent = WidgetSupport.parse(WidgetStore.cache(context, CACHE_RECENT));
        if (recent == null) return;
        // A PC that is off takes a connect timeout, a look on the network and a retry, past a broadcast's 10 seconds, so this runs
        // on its own thread after the broadcast has finished, and draws again when it knows.
        PC_CHECKS.execute(() -> {
            String account = recent.optString("username", "");
            JSONArray devices = RemoteBridge.devices(context, account);
            for (String pcId : pcsInUse(context, devices)) {
                for (int i = 0; i < devices.length(); i++) {
                    JSONObject device = devices.optJSONObject(i);
                    if (device != null && pcId.equals(device.optString("id"))) checkPc(context, account, device, recent);
                }
            }
            render(context);
            fetchCovers(context);
        });
    }

    private static void fetchActive(Context context) {
        try {
            FroglogApi.Response active = FroglogApi.get(context, "/api/live-session/active");
            if (active.ok()) WidgetStore.putCache(context, CACHE_ACTIVE, active.body.toString());
        } catch (Exception offline) {
            Log.w(TAG, "remote start: session state fetch failed: " + offline);
        }
    }

    // The running session ({title, ...}) from the saved state, or null when nothing is running.
    static JSONObject runningSession(Context context) {
        JSONObject state = WidgetSupport.parse(WidgetStore.cache(context, CACHE_ACTIVE));
        if (state == null) return null;
        JSONObject active = state.optJSONObject("active");
        return active != null ? active : state.optJSONObject("tracker");
    }

    // The site's "Session in Progress" wording.
    private static String inGameText(Context context, JSONObject session) {
        String title = session.isNull("title") ? "" : session.optString("title", "");
        return title.isEmpty() ? context.getString(R.string.widget_remote_in_game) : context.getString(R.string.widget_remote_in_game_title, title);
    }

    // Asks one PC what it can launch and keeps the recent games it can start. A PC that doesn't answer keeps its last list.
    private static void checkPc(Context context, String account, JSONObject device, JSONObject recent) {
        String pcId = device.optString("id");
        try {
            JSONObject pc = new JSONObject().put("id", pcId).put("name", device.optString("name"));
            JSONObject list = new JSONObject().put("pc", pc);
            RemoteBridge.Answer answer = null;
            try {
                answer = RemoteBridge.call(context, account, pcId, "GET", "/launchable", null, 8000);
            } catch (Exception unreachable) {
                // Handled below as unreachable.
            }
            if (answer != null && answer.unlinked) {
                // Removed on the PC: it is forgotten, and the widget moves to another PC or says none is linked.
                WidgetStore.putCache(context, CACHE_LIST + pcId, null);
                return;
            }
            if (answer != null && answer.status == 200) {
                list.put("state", "ok").put("games", launchable(recent.optJSONArray("games"), answer.body));
            } else if (answer != null) {
                list.put("state", "problem").put("problem", answer.body.optString("error"));
            } else {
                JSONObject previous = listFor(context, pcId);
                list.put("state", "unreachable");
                if (previous != null && previous.optJSONArray("games") != null) list.put("games", previous.optJSONArray("games"));
            }
            WidgetStore.putCache(context, CACHE_LIST + pcId, list.toString());
        } catch (Exception e) {
            Log.w(TAG, "remote start: checking the PC failed: " + e);
        }
    }

    // The recent games the PC can start: its installed Steam games by launch id, and its mapped executables by FrogLog game.
    private static JSONArray launchable(JSONArray recent, JSONObject answer) throws Exception {
        Set<String> steam = new HashSet<>();
        JSONArray installed = answer.optJSONArray("steam_installed");
        if (installed != null) for (int i = 0; i < installed.length(); i++) steam.add(installed.optString(i));
        Set<String> mapped = new HashSet<>();
        JSONArray games = answer.optJSONArray("games");
        if (games != null) {
            for (int i = 0; i < games.length(); i++) {
                JSONObject game = games.optJSONObject(i);
                if (game != null) mapped.add(game.optString("game_type") + ":" + game.optString("game_id"));
            }
        }
        JSONArray kept = new JSONArray();
        if (recent == null) return kept;
        for (int i = 0; i < recent.length() && kept.length() < SHOWN; i++) {
            JSONObject game = recent.getJSONObject(i);
            String launchId = game.isNull("steam_launch_id") ? "" : game.optString("steam_launch_id");
            boolean viaSteam = !launchId.isEmpty() && steam.contains(launchId);
            boolean viaMapping = mapped.contains(game.optString("type") + ":" + game.optString("id"));
            if (viaSteam || viaMapping) kept.put(game);
        }
        return kept;
    }

    // Covers download on their own thread (WidgetSupport.IMAGES), and the lists are told to redraw once any arrive.
    private static void fetchCovers(Context context) {
        Set<String> urls = new LinkedHashSet<>();
        for (String pcId : pcsInUse(context, devices(context))) {
            JSONObject list = listFor(context, pcId);
            JSONArray games = list == null ? null : list.optJSONArray("games");
            for (int i = 0; games != null && i < games.length(); i++) {
                String url = cover(games.optJSONObject(i));
                if (url != null) urls.add(url);
            }
        }
        if (urls.isEmpty()) return;
        WidgetSupport.IMAGES.execute(() -> {
            boolean any = false;
            for (String url : urls) {
                if (CoverArt.cached(context, url) == null) {
                    CoverArt.ensure(context, url);
                    any = true;
                }
            }
            if (any) AppWidgetManager.getInstance(context).notifyAppWidgetViewDataChanged(ids(context), R.id.remote_start_list);
        });
    }

    static String cover(JSONObject game) {
        if (game == null || game.isNull("cover")) return null;
        String url = game.optString("cover", "");
        return url.isEmpty() ? null : url;
    }

    private static void launch(Context context, Intent intent) {
        String pcId = intent.getStringExtra(EXTRA_PC);
        if (pcId == null) return;
        JSONObject status = WidgetSupport.parse(WidgetStore.cache(context, CACHE_STATUS + pcId));
        if (status != null && status.optBoolean("busy") && System.currentTimeMillis() - status.optLong("at") < 100_000) return;
        JSONObject list = listFor(context, pcId);
        JSONObject pc = list == null ? null : list.optJSONObject("pc");
        if (pc == null) return;
        String account = account(context);
        // The play buttons are dimmed while the PC is unavailable. A tap then checks it again instead of launching, so one that has come
        // back lights the buttons up without waiting for the next refresh.
        if (isUnavailable(list)) {
            JSONObject recent = WidgetSupport.parse(WidgetStore.cache(context, CACHE_RECENT));
            JSONObject device = null;
            JSONArray devices = devices(context);
            for (int i = 0; i < devices.length(); i++) if (pcId.equals(devices.optJSONObject(i).optString("id"))) device = devices.optJSONObject(i);
            if (recent == null || device == null) return;
            JSONObject found = device;
            PC_CHECKS.execute(() -> {
                checkPc(context, account, found, recent);
                render(context);
            });
            return;
        }
        String pcName = pc.optString("name");
        String title = intent.getStringExtra(EXTRA_TITLE);
        LAUNCHES.execute(() -> {
            // Checked again here rather than trusted from the last refresh, so a session that has ended since can't block a launch.
            fetchActive(context);
            if (runningSession(context) != null) {
                render(context);
                return;
            }
            setStatus(context, pcId, context.getString(R.string.widget_remote_starting, title, pcName), true);
            render(context);
            // Any refusal or failure is just "Launch Failed" on the widget (the user's call); the reason goes to logcat.
            String text = context.getString(R.string.widget_remote_launch_failed);
            try {
                JSONObject body = new JSONObject()
                    .put("account", account)
                    .put("game_type", intent.getStringExtra(EXTRA_TYPE))
                    .put("game_id", intent.getIntExtra(EXTRA_ID, 0))
                    .put("title", title);
                String steam = intent.getStringExtra(EXTRA_STEAM);
                if (steam != null && !steam.isEmpty()) body.put("steam_launch_id", steam);
                RemoteBridge.Answer answer = RemoteBridge.call(context, account, pcId, "POST", "/launch", body, 90_000);
                if (answer.status == 200) text = context.getString(R.string.widget_remote_started, title, pcName);
                else Log.i(TAG, "remote start: refused, HTTP " + answer.status + " " + answer.body.optString("error") + (answer.unlinked ? " (unlinked)" : ""));
            } catch (Exception failed) {
                Log.i(TAG, "remote start: launch failed: " + failed);
            }
            Log.i(TAG, "remote start: " + text);
            setStatus(context, pcId, text, false);
            render(context);
            AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            alarms.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + STATUS_SHOWN_MS + 500, clearIntent(context));
        });
    }

    private static void setStatus(Context context, String pcId, String text, boolean busy) {
        try {
            WidgetStore.putCache(context, CACHE_STATUS + pcId, new JSONObject().put("text", text).put("at", System.currentTimeMillis()).put("busy", busy).toString());
        } catch (Exception ignored) {
            // A string, a long and a boolean cannot fail to build.
        }
    }

    private static PendingIntent clearIntent(Context context) {
        Intent intent = new Intent(context, RemoteStartWidget.class).setAction(ACTION_CLEAR);
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // The row template: each row's fill-in adds the game and the PC. Mutable so the fill-in can be added.
    private static PendingIntent launchTemplate(Context context) {
        Intent intent = new Intent(context, RemoteStartWidget.class).setAction(ACTION_LAUNCH);
        int mutable = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0;
        return PendingIntent.getBroadcast(context, 7003, intent, PendingIntent.FLAG_UPDATE_CURRENT | mutable);
    }

    private static void render(Context context) {
        int[] ids = ids(context);
        if (ids.length == 0) return;
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        boolean signedIn = WidgetStore.isSignedIn(context);
        JSONArray devices = signedIn ? devices(context) : new JSONArray();
        float density = context.getResources().getDisplayMetrics().density;
        int side = Math.round(16 * density);
        for (int id : ids) {
            RemoteViews views = build(context, id, signedIn, devices);
            // The cover screen's bottom corners curve in, so the list stops higher up there. Set on every build, for both.
            boolean cover = WidgetSupport.isCover(context, id);
            views.setViewPadding(R.id.remote_start_root, side, side, side, Math.round((16 + (cover ? WidgetSupport.COVER_EXTRA_BOTTOM_DP : 0)) * density));
            if (cover) views.setOnClickPendingIntent(R.id.remote_start_header, null);
            manager.updateAppWidget(id, WidgetSupport.squareOnCover(context, id, views, R.id.remote_start_root));
        }
        manager.notifyAppWidgetViewDataChanged(ids, R.id.remote_start_list);
    }

    private static RemoteViews build(Context context, int appWidgetId, boolean signedIn, JSONArray devices) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_remote_start);
        WidgetTheme theme = WidgetTheme.forWidget(context, appWidgetId);
        theme.card(views, R.id.remote_start_root);
        theme.icon(views, R.id.remote_start_logo, theme.foreground);
        theme.text(views, theme.foreground, R.id.remote_start_title);
        theme.text(views, theme.muted, R.id.remote_start_empty, R.id.remote_start_status);

        JSONObject device = signedIn ? pcFor(context, appWidgetId, devices) : null;
        String pcId = device == null ? null : device.optString("id");
        String pcName = device == null ? "" : device.optString("name");
        JSONObject list = listFor(context, pcId);
        String state = !signedIn ? "signed_out" : device == null ? (WidgetStore.cache(context, CACHE_RECENT) == null ? "loading" : "no_pc")
            : list == null ? "loading" : list.optString("state");
        // The header opens the app: at Remote Start settings when there is no PC to use, else your games.
        views.setOnClickPendingIntent(R.id.remote_start_header, WidgetSupport.openApp(context, "no_pc".equals(state) ? "/profile?section=remoteDevices" : "/games"));
        views.setViewVisibility(R.id.remote_start_pc, device != null ? View.VISIBLE : View.GONE);
        // The badge names the PC, or turns into a red "Unavailable" when it can't take a launch (not answering, or LilyPad there signed
        // out, in another account or with remote devices off); the message below says which. Both looks are set every time.
        boolean unavailable = isUnavailable(list);
        views.setTextViewText(R.id.remote_start_pc, unavailable ? context.getString(R.string.widget_remote_unavailable) : pcName);
        if (unavailable) {
            views.setInt(R.id.remote_start_pc, "setBackgroundResource", R.drawable.widget_badge_destructive);
            views.setTextColor(R.id.remote_start_pc, 0xFFFFFFFF);
            // A theme tints the badge from Android 12, so the red has to be the tint there too.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                views.setColorStateList(R.id.remote_start_pc, "setBackgroundTintList", android.content.res.ColorStateList.valueOf(context.getColor(R.color.widget_stop)));
            }
        } else {
            views.setInt(R.id.remote_start_pc, "setBackgroundResource", R.drawable.widget_badge);
            views.setTextColor(R.id.remote_start_pc, theme.secondaryForeground);
            // Set outright rather than through theme.chip, which does nothing for an unthemed widget and would leave the red tint on.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                views.setColorStateList(R.id.remote_start_pc, "setBackgroundTintList", android.content.res.ColorStateList.valueOf(theme.secondary));
            }
        }

        Intent adapter = new Intent(context, RemoteStartService.class).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        adapter.setData(Uri.parse(adapter.toUri(Intent.URI_INTENT_SCHEME)));
        views.setRemoteAdapter(R.id.remote_start_list, adapter);
        views.setEmptyView(R.id.remote_start_list, R.id.remote_start_empty);
        views.setPendingIntentTemplate(R.id.remote_start_list, launchTemplate(context));
        views.setTextViewText(R.id.remote_start_empty, emptyText(context, state, list, pcName));

        // A launch's own line comes first; otherwise a running session says why the buttons are dimmed.
        JSONObject status = pcId == null ? null : WidgetSupport.parse(WidgetStore.cache(context, CACHE_STATUS + pcId));
        boolean showStatus = status != null && (status.optBoolean("busy") || System.currentTimeMillis() - status.optLong("at") < STATUS_SHOWN_MS);
        JSONObject running = signedIn ? runningSession(context) : null;
        String line = showStatus ? status.optString("text") : running != null ? inGameText(context, running) : null;
        views.setViewVisibility(R.id.remote_start_status, line != null ? View.VISIBLE : View.GONE);
        views.setTextViewText(R.id.remote_start_status, line != null ? line : "");
        return views;
    }

    private static String emptyText(Context context, String state, JSONObject list, String pc) {
        switch (state) {
            case "signed_out": return context.getString(R.string.widget_signed_out_hint);
            case "loading": return context.getString(R.string.widget_friends_loading);
            case "no_pc": return context.getString(R.string.widget_remote_no_pc);
            case "unreachable": return context.getString(R.string.widget_remote_unreachable, pc);
            case "problem":
                String problem = list.optString("problem");
                if ("different_account".equals(problem)) return context.getString(R.string.widget_remote_different_account, pc);
                if ("lilypad_signed_out".equals(problem)) return context.getString(R.string.widget_remote_signed_out, pc);
                if ("disabled".equals(problem)) return context.getString(R.string.widget_remote_disabled, pc);
                return context.getString(R.string.widget_remote_unreachable, pc);
            default: return context.getString(R.string.widget_remote_empty, pc);
        }
    }
}
