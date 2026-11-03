package uk.co.froglog.app.widgets;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * What the home screen widgets need between updates: the login the site handed over, and the last
 * answers from the API so a widget can redraw while offline.
 *
 * The site sets the login through FrogLogWidgetsPlugin, because native code can't read the
 * WebView's localStorage. Logging out clears it, and the widgets then ask the user to sign in.
 */
public final class WidgetStore {
    private static final String PREFS = "froglog_widgets";
    private static final String TOKEN = "token";
    private static final String API_URL = "api_url";

    private WidgetStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void setLogin(Context context, String token, String apiUrl) {
        prefs(context).edit().putString(TOKEN, token).putString(API_URL, apiUrl).apply();
    }

    // Also drops the cached answers, so a widget can't show the last user's session.
    public static void clearLogin(Context context) {
        prefs(context).edit().clear().apply();
    }

    public static String token(Context context) {
        return prefs(context).getString(TOKEN, null);
    }

    public static String apiUrl(Context context) {
        return prefs(context).getString(API_URL, null);
    }

    public static boolean isSignedIn(Context context) {
        return token(context) != null && apiUrl(context) != null;
    }

    public static void putCache(Context context, String key, String json) {
        prefs(context).edit().putString("cache_" + key, json).apply();
    }

    public static String cache(Context context, String key) {
        return prefs(context).getString("cache_" + key, null);
    }
}
