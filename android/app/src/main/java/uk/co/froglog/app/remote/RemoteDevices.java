package uk.co.froglog.app.remote;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The PCs this app is linked to: each one's certificate fingerprint (its id, and what is pinned), the name it gave, and the last
 * address it was seen at (found again through discovery when it moves).
 */
final class RemoteDevices {
    private static final String PREFS = "froglog_remote";
    // The list from before PCs were kept per account. The first account to look adopts it.
    private static final String LEGACY_KEY = "devices";
    // The FrogLog account the site says is signed in. Linked PCs belong to it: each account in the app has its own list, and signing in as
    // someone else must neither show another account's PCs nor start games on them (LilyPad would refuse, being signed in as the other user).
    private static volatile String account = "";

    /** Sets whose list the calls that follow read and write. Called at the start of every plugin call with the site's account. */
    static synchronized void use(Context context, String username) {
        account = username == null ? "" : username.trim().toLowerCase(java.util.Locale.ROOT);
        SharedPreferences prefs = prefs(context);
        if (!account.isEmpty() && prefs.contains(LEGACY_KEY)) {
            if (!prefs.contains(key())) prefs.edit().putString(key(), prefs.getString(LEGACY_KEY, "[]")).apply();
            prefs.edit().remove(LEGACY_KEY).apply();
        }
    }

    private static String key() {
        return "devices_" + account;
    }

    private RemoteDevices() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static synchronized JSONArray list(Context context) {
        try {
            return new JSONArray(prefs(context).getString(key(), "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    static synchronized JSONObject find(Context context, String id) {
        JSONArray all = list(context);
        for (int i = 0; i < all.length(); i++) {
            JSONObject device = all.optJSONObject(i);
            if (device != null && id.equals(device.optString("id"))) return device;
        }
        return null;
    }

    static synchronized void save(Context context, String id, String name, String host, int port) {
        try {
            JSONArray all = list(context);
            JSONArray next = new JSONArray();
            for (int i = 0; i < all.length(); i++) {
                JSONObject device = all.optJSONObject(i);
                if (device != null && !id.equals(device.optString("id"))) next.put(device);
            }
            // The name you gave it stays when the PC's own name or address is refreshed.
            JSONObject before = find(context, id);
            JSONObject entry = new JSONObject().put("id", id).put("name", name).put("host", host).put("port", port);
            if (before != null && !before.optString("alias").isEmpty()) entry.put("alias", before.optString("alias"));
            next.put(entry);
            prefs(context).edit().putString(key(), next.toString()).apply();
        } catch (Exception ignored) {
            // JSON of plain strings and ints cannot fail to build.
        }
    }

    /** Gives a PC a name of your own; an empty one goes back to the name the PC gives itself. */
    static synchronized void setAlias(Context context, String id, String alias) {
        try {
            JSONArray all = list(context);
            for (int i = 0; i < all.length(); i++) {
                JSONObject device = all.optJSONObject(i);
                if (device != null && id.equals(device.optString("id"))) {
                    if (alias == null || alias.trim().isEmpty()) device.remove("alias");
                    else device.put("alias", alias.trim().substring(0, Math.min(alias.trim().length(), 60)));
                }
            }
            prefs(context).edit().putString(key(), all.toString()).apply();
        } catch (Exception ignored) {
            // Plain strings cannot fail to store.
        }
    }

    /** A copy for the site: `name` is the one to show (yours, else the PC's own), and `pcName` the PC's own. */
    static JSONObject shown(JSONObject device) {
        try {
            JSONObject copy = new JSONObject(device.toString());
            String alias = device.optString("alias");
            copy.put("pcName", device.optString("name"));
            if (!alias.isEmpty()) copy.put("name", alias);
            return copy;
        } catch (Exception e) {
            return device;
        }
    }

    static synchronized void forget(Context context, String id) {
        JSONArray all = list(context);
        JSONArray next = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject device = all.optJSONObject(i);
            if (device != null && !id.equals(device.optString("id"))) next.put(device);
        }
        prefs(context).edit().putString(key(), next.toString()).apply();
    }
}
