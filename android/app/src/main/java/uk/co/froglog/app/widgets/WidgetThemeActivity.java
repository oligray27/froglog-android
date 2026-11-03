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

import uk.co.froglog.app.R;

/**
 * Where a widget's theme is chosen: when it is added, and from its reconfigure option on Android 12 and later.
 * A list of the site's themes and, from Android 12, the phone's wallpaper colours. It is built in code, in the
 * default dark look, so it needs no layout file and no AppCompat.
 */
public class WidgetThemeActivity extends Activity {
    private int appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private String current;

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

        WidgetTheme chrome = WidgetTheme.of(this, WidgetTheme.DEFAULT);
        getWindow().getDecorView().setBackgroundColor(chrome.card);
        getWindow().setStatusBarColor(chrome.card);
        getWindow().setNavigationBarColor(chrome.card);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        // Extra room at the top, which sits under the status bar and the camera cutout.
        list.setPadding(dp(20), dp(72), dp(20), dp(24));

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
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(12));
        background.setColor(chrome.secondary & 0x00FFFFFF | 0x55000000);
        boolean selected = choice.equals(current);
        background.setStroke(dp(selected ? 2 : 1), selected ? chrome.foreground : chrome.outline);
        row.setBackground(background);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(10);
        row.setLayoutParams(rowParams);

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

        if (selected) {
            ImageView tick = new ImageView(this);
            tick.setImageResource(R.drawable.ic_widget_check);
            tick.setColorFilter(chrome.foreground);
            row.addView(tick, new LinearLayout.LayoutParams(dp(24), dp(24)));
        }

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
        setResult(RESULT_OK, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId));
        // A widget that has a configuration screen is not drawn until this says so.
        WidgetSupport.refreshAll(this);
        finish();
    }
}
