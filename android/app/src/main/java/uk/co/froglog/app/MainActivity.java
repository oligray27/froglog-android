package uk.co.froglog.app;

import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebView;

import androidx.activity.OnBackPressedCallback;

import com.getcapacitor.BridgeActivity;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import uk.co.froglog.app.remote.FrogLogRemotePlugin;
import uk.co.froglog.app.widgets.FrogLogWidgetsPlugin;

public class MainActivity extends BridgeActivity {
    // Pages where Back leaves the app: the Games page is the root once signed in.
    private static final Set<String> ROOT_PATHS = new HashSet<>(Arrays.asList("", "/", "/games", "/login", "/register"));
    private static final String ROOT_PAGE = "/games";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        registerPlugin(PageHeightPlugin.class);
        registerPlugin(FrogLogWidgetsPlugin.class);
        registerPlugin(FrogLogRemotePlugin.class);
        super.onCreate(savedInstanceState);

        // Capacitor only handles Back through @capacitor/app, which this app doesn't use, so
        // without this Back always closed the app.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                WebView webView = getBridge() != null ? getBridge().getWebView() : null;
                if (webView == null || isRoot(webView.getUrl())) {
                    leaveApp();
                } else if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    // Opened straight onto a page (a notification, a widget): go up to the root instead of leaving
                    webView.evaluateJavascript(
                        "history.replaceState(null,'','" + ROOT_PAGE + "');window.dispatchEvent(new PopStateEvent('popstate'));",
                        null);
                }
            }

            private void leaveApp() {
                // Hand Back to the system's own behaviour
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
                setEnabled(true);
            }
        });
    }

    private static boolean isRoot(String url) {
        if (url == null) return true;
        String path = Uri.parse(url).getPath();
        return path == null || ROOT_PATHS.contains(path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path);
    }
}
