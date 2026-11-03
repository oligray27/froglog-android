package uk.co.froglog.app.widgets;

import android.content.Intent;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * The site's link to the home screen widgets (frontend utils/appWidgets.js).
 *
 * update() hands over the login, or clears it when called without one, and redraws the widgets.
 * The site calls it on load, after logging in, and whenever its Quick Session state changes.
 * A widget tap opens the app with a url, which reaches the site as an "open" event, or through
 * takeLaunchUrl() when the tap launched the app before the site was listening.
 */
@CapacitorPlugin(name = "FrogLogWidgets")
public class FrogLogWidgetsPlugin extends Plugin {
    static final String ACTION_OPEN = "uk.co.froglog.app.widgets.OPEN";
    static final String EXTRA_URL = "uk.co.froglog.app.widgets.URL";

    private String launchUrl;

    @Override
    public void load() {
        launchUrl = takeUrl(getActivity().getIntent());
    }

    @PluginMethod
    public void update(PluginCall call) {
        String token = call.getString("token");
        String apiUrl = call.getString("apiUrl");
        android.content.Context context = getContext().getApplicationContext();
        if (token != null && apiUrl != null) {
            WidgetStore.setLogin(context, token, apiUrl);
            WidgetSupport.refreshAll(context);
        } else if (WidgetStore.isSignedIn(context)) {
            // Logging out: the device is unregistered with the old login before it's cleared.
            WidgetSupport.WORKER.execute(() -> {
                WidgetDevices.unregister(context);
                WidgetStore.clearLogin(context);
                WidgetSupport.refreshAll(context);
            });
        }
        call.resolve();
    }

    @PluginMethod
    public void takeLaunchUrl(PluginCall call) {
        JSObject result = new JSObject();
        result.put("url", launchUrl);
        launchUrl = null;
        call.resolve(result);
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        String url = takeUrl(intent);
        if (url == null) return;
        JSObject data = new JSObject();
        data.put("url", url);
        notifyListeners("open", data);
    }

    // Removes the url once read, so reopening the app from the launcher doesn't repeat it.
    private static String takeUrl(Intent intent) {
        if (intent == null || !ACTION_OPEN.equals(intent.getAction())) return null;
        String url = intent.getStringExtra(EXTRA_URL);
        intent.removeExtra(EXTRA_URL);
        return url;
    }
}
