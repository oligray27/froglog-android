package uk.co.froglog.app.widgets;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import uk.co.froglog.app.R;

/**
 * Where a widget's theme is chosen: when it is added, and from its reconfigure option on Android 12 and later.
 * A list of the site's themes and, from Android 12, the phone's wallpaper colours. It is built in code, in the
 * default dark look, so it needs no layout file and no AppCompat.
 *
 * A Remote Start widget with more than one PC linked also picks its PC here, above the themes. Picking a PC keeps the
 * screen open (it is saved at once); picking a theme finishes, as for every widget.
 */
public class WidgetThemeActivity extends Activity {
    private int appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private String current;
    // Remote Start only: the linked PCs, and the one this widget uses.
    private JSONArray pcs = new JSONArray();
    private String currentPc;
    private WidgetTheme chrome;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle extras = getIntent().getExtras();
        if (extras != null) appWidgetId = extras.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        // Backing out leaves a new widget unplaced, as the system expects of a configuration screen.
        setResult(RESULT_CANCELED, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId));
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }
        current = WidgetTheme.choice(this, appWidgetId);
        if (RemoteStartWidget.isRemoteStart(this, appWidgetId)) {
            pcs = RemoteStartWidget.devices(this);
            JSONObject pc = RemoteStartWidget.pcFor(this, appWidgetId, pcs);
            currentPc = pc == null ? null : pc.optString("id");
        }

        chrome = WidgetTheme.of(this, WidgetTheme.DEFAULT);
        getWindow().getDecorView().setBackgroundColor(chrome.card);
        getWindow().setStatusBarColor(chrome.card);
        getWindow().setNavigationBarColor(chrome.card);
        showChoices();
    }

    private void showChoices() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        // Extra room at the top, which sits under the status bar and the camera cutout.
        list.setPadding(dp(20), dp(72), dp(20), dp(24));

        // With one PC (or none) there is nothing to choose, so the screen is the themes alone.
        if (pcs.length() > 1) {
            TextView pcTitle = text("PC", 26, chrome.foreground, true);
            pcTitle.setPadding(0, 0, 0, dp(6));
            list.addView(pcTitle);
            TextView pcHint = text("The PC this widget starts games on.", 14, chrome.muted, false);
            pcHint.setPadding(0, 0, 0, dp(16));
            list.addView(pcHint);
            for (int i = 0; i < pcs.length(); i++) {
                JSONObject pc = pcs.optJSONObject(i);
                if (pc != null) list.addView(pcRow(pc));
            }
            TextView spacer = new TextView(this);
            spacer.setPadding(0, dp(14), 0, 0);
            list.addView(spacer);
        }

        TextView title = text("Widget Theme", 26, chrome.foreground, true);
        title.setPadding(0, 0, 0, dp(20));
        list.addView(title);

        if (WidgetTheme.wallpaperAvailable()) list.addView(row(WidgetTheme.WALLPAPER, "Wallpaper colours", "Follows your home screen wallpaper, light or dark with your phone", WidgetTheme.of(this, WidgetTheme.WALLPAPER), chrome));
        for (WidgetThemes.Entry entry : WidgetThemes.ALL) {
            list.addView(row(entry.id, entry.label, entry.light ? "Light" : "Dark", WidgetTheme.of(this, entry.id), chrome));
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
    }

    // One linked PC: its name, the name the PC gives itself if you renamed it, and a tick on the one in use.
    private View pcRow(JSONObject pc) {
        String id = pc.optString("id");
        boolean selected = id.equals(currentPc);
        LinearLayout row = choiceRow(selected);
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(4), 0, dp(8), 0);
        String name = pc.optString("name");
        String own = pc.optString("pcName");
        labels.addView(text(name, 17, chrome.foreground, true));
        labels.addView(text(!own.isEmpty() && !own.equals(name) ? own : pc.optString("host"), 13, chrome.muted, false));
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (selected) row.addView(tick());
        row.setOnClickListener((v) -> {
            currentPc = id;
            RemoteStartWidget.setPcChoice(this, appWidgetId, id);
            // Saved now, so backing out from here still places the widget, on this PC.
            setResult(RESULT_OK, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId));
            WidgetSupport.refreshAll(this);
            showChoices();
        });
        return row;
    }

    // The outlined box every choice sits in, with a heavier border on the one in use.
    private LinearLayout choiceRow(boolean selected) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(12));
        background.setColor(chrome.secondary & 0x00FFFFFF | 0x55000000);
        background.setStroke(dp(selected ? 2 : 1), selected ? chrome.foreground : chrome.outline);
        row.setBackground(background);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(10);
        row.setLayoutParams(rowParams);
        return row;
    }

    private View tick() {
        ImageView tick = new ImageView(this);
        tick.setImageResource(R.drawable.ic_widget_check);
        tick.setColorFilter(chrome.foreground);
        tick.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));
        return tick;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    // One choice: a swatch drawn in its own colours, its name, and a tick if it is the current one.
    private View row(String choice, String name, String detail, WidgetTheme theme, WidgetTheme chrome) {
        boolean selected = choice.equals(current);
        LinearLayout row = choiceRow(selected);

        // The swatch: the theme's card with its text, a chip and its highlight, the way a widget will look.
        LinearLayout swatch = new LinearLayout(this);
        swatch.setOrientation(LinearLayout.VERTICAL);
        swatch.setPadding(dp(8), dp(7), dp(8), dp(7));
        GradientDrawable card = new GradientDrawable();
        card.setCornerRadius(dp(10));
        card.setColor(theme.card);
        card.setStroke(dp(1), theme.outline);
        swatch.setBackground(card);
        TextView aa = text("Aa", 15, theme.foreground, true);
        swatch.addView(aa);
        LinearLayout dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.addView(dot(theme.primary));
        dots.addView(dot(theme.secondary));
        dots.addView(dot(theme.muted));
        swatch.addView(dots);
        row.addView(swatch, new LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(14), 0, dp(8), 0);
        labels.addView(text(name, 17, chrome.foreground, true));
        labels.addView(text(detail, 13, chrome.muted, false));
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (selected) row.addView(tick());

        row.setOnClickListener((v) -> choose(choice));
        return row;
    }

    private View dot(int color) {
        View dot = new View(this);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(color);
        shape.setStroke(1, Color.argb(60, 128, 128, 128));
        dot.setBackground(shape);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(12), dp(12));
        params.rightMargin = dp(5);
        params.topMargin = dp(4);
        dot.setLayoutParams(params);
        return dot;
    }

    private void choose(String choice) {
        WidgetTheme.setChoice(this, appWidgetId, choice);
        if (currentPc != null) RemoteStartWidget.setPcChoice(this, appWidgetId, currentPc);
        setResult(RESULT_OK, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId));
        // A widget that has a configuration screen is not drawn until this says so.
        WidgetSupport.refreshAll(this);
        finish();
    }
}
