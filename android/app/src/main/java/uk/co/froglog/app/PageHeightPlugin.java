package uk.co.froglog.app;

import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Keeps the WebView at full height when the keyboard opens.
 *
 * Capacitor pads the window for the keyboard, which makes the whole page reflow. With its height
 * held, the keyboard overlaps the WebView instead, and the page is told how much it covers so
 * dialogs and low fields can make room for themselves. The site starts this, so a version of the
 * site that doesn't know about it keeps working.
 */
@CapacitorPlugin(name = "PageHeight")
public class PageHeightPlugin extends Plugin {
    private View.OnLayoutChangeListener watcher;

    @PluginMethod
    public void hold(PluginCall call) {
        getBridge().executeOnMainThread(() -> {
            if (watcher == null) {
                ViewGroup parent = (ViewGroup) getBridge().getWebView().getParent();
                watcher = (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> applyHeight();
                parent.addOnLayoutChangeListener(watcher);
                applyHeight();
            }
            call.resolve();
        });
    }

    // Runs on every layout, because folding the phone changes the full height while a dialog is open.
    private void applyHeight() {
        WebView webView = getBridge().getWebView();
        ViewGroup parent = (ViewGroup) webView.getParent();
        int fullHeight = getActivity().getWindow().getDecorView().getHeight();
        ViewGroup.LayoutParams params = webView.getLayoutParams();
        if (params.height != fullHeight) {
            params.height = fullHeight;
            // Changing layout params during a layout pass is ignored, so this waits for it to finish.
            webView.post(() -> webView.setLayoutParams(params));
        }
        notifyKeyboard(Math.max(0, fullHeight - parent.getHeight()));
    }

    private int lastKeyboardHeight = -1;

    private void notifyKeyboard(int heightPx) {
        if (heightPx == lastKeyboardHeight) return;
        lastKeyboardHeight = heightPx;
        float density = getContext().getResources().getDisplayMetrics().density;
        JSObject data = new JSObject();
        data.put("height", Math.round(heightPx / density));
        notifyListeners("keyboard", data);
    }
}
