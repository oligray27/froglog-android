package uk.co.froglog.app.widgets;

import android.Manifest;
import android.os.Build;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/**
 * The site's link to the running session notification (frontend utils/sessionNotification.js).
 *
 * own() hands over a Quick Session the site has just started on this phone, asking for notification permission the
 * first time it's needed. getSettings() and setSettings() are Settings > Notifications' "Running Session" choice,
 * which is kept on the phone.
 */
@CapacitorPlugin(
    name = "FrogLogSession",
    permissions = { @Permission(alias = FrogLogSessionPlugin.NOTIFICATIONS, strings = { Manifest.permission.POST_NOTIFICATIONS }) }
)
public class FrogLogSessionPlugin extends Plugin {
    static final String NOTIFICATIONS = "notifications";

    @PluginMethod
    public void own(PluginCall call) {
        SessionNotification.own(getContext(), call.getObject("session"), call.getString("package"));
        if (SessionNotification.enabled(getContext()) && needsAsking()) {
            requestPermissionForAlias(NOTIFICATIONS, call, "afterPermission");
            return;
        }
        resolveSettings(call);
    }

    @PluginMethod
    public void getSettings(PluginCall call) {
        resolveSettings(call);
    }

    @PluginMethod
    public void setSettings(PluginCall call) {
        boolean enabled = Boolean.TRUE.equals(call.getBoolean("enabled", true));
        SessionNotification.setEnabled(getContext(), enabled);
        if (enabled && needsAsking()) {
            requestPermissionForAlias(NOTIFICATIONS, call, "afterPermission");
            return;
        }
        resolveSettings(call);
    }

    @PermissionCallback
    private void afterPermission(PluginCall call) {
        SessionNotification.redraw(getContext());
        resolveSettings(call);
    }

    // Android 13 and later ask; a refusal is final, so only an unanswered prompt is shown.
    private boolean needsAsking() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || SessionNotification.canShow(getContext())) return false;
        PermissionState state = getPermissionState(NOTIFICATIONS);
        return state == PermissionState.PROMPT || state == PermissionState.PROMPT_WITH_RATIONALE;
    }

    private void resolveSettings(PluginCall call) {
        JSObject result = new JSObject();
        result.put("enabled", SessionNotification.enabled(getContext()));
        result.put("allowed", SessionNotification.canShow(getContext()));
        call.resolve(result);
    }
}
