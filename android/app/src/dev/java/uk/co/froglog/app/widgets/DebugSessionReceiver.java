package uk.co.froglog.app.widgets;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Dev build only. Puts a made-up session into the Quick Session widget so each look can be seen
 * without a real one: adb shell am broadcast -n uk.co.froglog.app.dev/uk.co.froglog.app.widgets.DebugSessionReceiver --es type steam
 * (see scripts/debug-widget.js). Nothing is sent to the server. Type "clear" goes back to the real state.
 */
public class DebugSessionReceiver extends BroadcastReceiver {
    private static final String GAME = "Bayonetta";

    @Override
    public void onReceive(Context context, Intent intent) {
        String type = intent.getStringExtra("type");
        if (type == null) return;
        try {
            if ("clear".equals(type)) {
                QuickSessionWidget.clearDebugState(context);
                FriendsWidget.clearDebugState(context);
                return;
            }
            if (type.startsWith("theme_")) {
                String choice = type.substring("theme_".length());
                android.appwidget.AppWidgetManager manager = android.appwidget.AppWidgetManager.getInstance(context);
                for (Class<?> provider : new Class<?>[] { QuickSessionWidget.class, ThisWeekWidget.class, FriendsWidget.class }) {
                    for (int id : manager.getAppWidgetIds(new android.content.ComponentName(context, provider))) WidgetTheme.setChoice(context, id, choice);
                }
                WidgetSupport.refreshAll(context);
                return;
            }
            if (type.startsWith("friends")) {
                FriendsWidget.showDebugState(context, friends(type).toString());
                return;
            }
            JSONObject state = new JSONObject();
            JSONObject last = new JSONObject();
            switch (type) {
                case "manual": state.put("active", session("manual", false)); break;
                case "paused": state.put("active", session("manual", true)); break;
                case "steam": state.put("active", session("steam_poll", false)); break;
                case "psn": state.put("active", session("psn_poll", false)); break;
                case "xbox": state.put("active", session("xbox_poll", false)); break;
                case "switch": state.put("active", session("switch_poll", false)); break;
                case "lilypad": state.put("tracker", tracker(true, false)); break;
                case "lilypad_paused": state.put("tracker", tracker(true, true)); break;
                case "lilypad_view": state.put("tracker", tracker(false, false)); break;
                case "idle":
                    last.put("game_type", "game").put("game_id", 1).put("title", GAME).put("last_session_hours", 2.4);
                    break;
                default: return;
            }
            QuickSessionWidget.showDebugState(context, state.toString(), last.length() == 0 ? null : last.toString());
        } catch (Exception ignored) {
            // A debug helper: nothing to report to.
        }
    }

    // friends: everyone, four online (one paused) and four not, so it scrolls; friends_three has two online and one not;
    // friends_one is one person online; friends_none is an empty list.
    private static JSONObject friends(String type) throws Exception {
        // name, username, game, minutes playing, minutes paused for ("" = not paused), minutes since last seen ("" = online)
        String[][] people = {
            { "Leemotheyer", "leemotheyer", "Nier:Automata", "84", "", "" },
            { "vibefroggy", "vibefroggy", "Hades II", "7", "", "" },
            { "slutgamer72", "slutgamer72", "Elden Ring", "190", "3", "" },
            { "OttoLotto", "ottolotto", "Hollow Knight: Silksong", "45", "", "" },
            { "LAW0298", "law0298", "", "", "", "12" },
            { "Kloat", "kloat", "", "", "", "180" },
            { "Greenfrog", "greenfrog", "", "", "", "2880" },
            { "Wisp", "wisp", "", "", "", "13000" },
        };
        int[] picks = "friends_none".equals(type) ? new int[0]
            : "friends_one".equals(type) ? new int[] { 0 }
            : "friends_three".equals(type) ? new int[] { 0, 1, 4 }
            : new int[] { 0, 1, 2, 3, 4, 5, 6, 7 };
        org.json.JSONArray list = new org.json.JSONArray();
        int online = 0;
        for (int i : picks) {
            String[] p = people[i];
            boolean isOnline = p[5].isEmpty();
            if (isOnline) online++;
            list.put(new JSONObject()
                .put("name", p[0]).put("username", p[1]).put("online", isOnline)
                .put("title", isOnline ? p[2] : JSONObject.NULL)
                .put("started_at", isOnline ? iso(Long.parseLong(p[3]) * 60_000L) : JSONObject.NULL)
                .put("paused_at", isOnline && !p[4].isEmpty() ? iso(Long.parseLong(p[4]) * 60_000L) : JSONObject.NULL)
                .put("last_seen_at", isOnline ? JSONObject.NULL : iso(Long.parseLong(p[5]) * 60_000L)));
        }
        return new JSONObject().put("count", online).put("friends", list);
    }

    private static String iso(long millisAgo) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(System.currentTimeMillis() - millisAgo));
    }

    private static JSONObject session(String source, boolean paused) throws Exception {
        return new JSONObject()
            .put("source", source).put("title", GAME).put("game_id", 1).put("game_type", "game")
            .put("started_at", iso(84 * 60_000L))
            .put("paused_at", paused ? iso(2 * 60_000L) : JSONObject.NULL)
            .put("paused_seconds", 0);
    }

    private static JSONObject tracker(boolean remoteControl, boolean paused) throws Exception {
        return new JSONObject()
            .put("platform", "lilypad").put("title", GAME).put("game_id", 1).put("game_type", "game")
            .put("status", paused ? "paused" : "tracking").put("ledger_id", "debug")
            .put("started_at", iso(84 * 60_000L))
            .put("paused_at", paused ? iso(2 * 60_000L) : JSONObject.NULL)
            .put("paused_seconds", 0)
            .put("device", new JSONObject().put("id", "debug").put("name", "Gaming PC").put("remote_control", remoteControl));
    }
}
