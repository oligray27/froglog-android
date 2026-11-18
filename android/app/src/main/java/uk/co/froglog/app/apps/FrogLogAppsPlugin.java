package uk.co.froglog.app.apps;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.Base64;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Lists and opens the apps installed on this phone, for Play on Phone (frontend utils/androidApps.js).
 *
 * Only apps with a launcher icon are visible to it, through the manifest's queries entry, which is all a game needs.
 * The link between a game and its app is kept on the server, so the site asks installed() which of its links this
 * phone can open.
 */
@CapacitorPlugin(name = "FrogLogApps")
public class FrogLogAppsPlugin extends Plugin {
    private static final int ICON_PX = 96;

    @PluginMethod
    public void listApps(PluginCall call) {
        Context context = getContext();
        PackageManager pm = context.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(launcher, 0);
        JSArray apps = new JSArray();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ResolveInfo info : found) {
            String pkg = info.activityInfo.packageName;
            if (pkg.equals(context.getPackageName()) || !seen.add(pkg)) continue;
            ApplicationInfo appInfo = info.activityInfo.applicationInfo;
            JSObject app = new JSObject();
            app.put("package", pkg);
            app.put("label", String.valueOf(info.loadLabel(pm)));
            app.put("game", isGame(appInfo));
            app.put("icon", iconDataUrl(info.loadIcon(pm)));
            apps.put(app);
        }
        JSObject result = new JSObject();
        result.put("apps", apps);
        call.resolve(result);
    }

    @PluginMethod
    public void installed(PluginCall call) {
        PackageManager pm = getContext().getPackageManager();
        JSONArray packages = call.getArray("packages", new JSArray());
        JSArray installed = new JSArray();
        for (int i = 0; i < packages.length(); i++) {
            String pkg = packages.optString(i, null);
            if (pkg != null && pm.getLaunchIntentForPackage(pkg) != null) installed.put(pkg);
        }
        JSObject result = new JSObject();
        result.put("installed", installed);
        call.resolve(result);
    }

    @PluginMethod
    public void launch(PluginCall call) {
        String pkg = call.getString("package");
        Intent intent = pkg == null ? null : getContext().getPackageManager().getLaunchIntentForPackage(pkg);
        JSObject result = new JSObject();
        if (intent == null) {
            result.put("launched", false);
            call.resolve(result);
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getActivity().runOnUiThread(() -> {
            try {
                getActivity().startActivity(intent);
                result.put("launched", true);
            } catch (Exception e) {
                result.put("launched", false);
            }
            call.resolve(result);
        });
    }

    @SuppressWarnings("deprecation")
    private static boolean isGame(ApplicationInfo info) {
        if (info == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && info.category == ApplicationInfo.CATEGORY_GAME) return true;
        return (info.flags & ApplicationInfo.FLAG_IS_GAME) != 0;
    }

    private static String iconDataUrl(Drawable icon) {
        if (icon == null) return null;
        try {
            Bitmap bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            icon.setBounds(0, 0, ICON_PX, ICON_PX);
            icon.draw(canvas);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            bitmap.recycle();
            return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }
}
