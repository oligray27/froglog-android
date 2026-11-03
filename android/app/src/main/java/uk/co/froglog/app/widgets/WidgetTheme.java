package uk.co.froglog.app.widgets;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.widget.RemoteViews;

/**
 * The colours one widget is drawn in. Each widget has its own choice, made when it is added (or from its
 * reconfigure option), of one of the site's themes (WidgetThemes) or the phone's wallpaper colours. It does
 * not follow the theme set in the app.
 *
 * Widget layouts carry the default (dark) colours, so a widget before Android 12 stays in them. From Android 12
 * a theme is applied on top: tints for backgrounds, colour filters for icons and text colours, none of which
 * need a different layout. Tinted drawables are opaque white from Android 12 (drawable-v31), so what shows is the tint.
 */
final class WidgetTheme {
    static final String WALLPAPER = "wallpaper";
    static final String DEFAULT = "dark";
    // A colour filter with no alpha leaves the icon as drawn, which is how an icon keeps its own colour.
    static final int NO_FILTER = 0x00000000;

    private static final String PREFS = "froglog_widget_themes";

    final boolean themed;
    final int card, foreground, muted, secondary, secondaryForeground, primary, primaryForeground;
    // Borders and dividers: the theme's --border.
    final int outline;
    private WidgetTheme(boolean themed, boolean light, int card, int foreground, int muted, int secondary, int secondaryForeground, int primary, int primaryForeground, int background, int border, int input) {
        this.themed = themed;
        this.card = card;
        this.foreground = foreground;
        this.muted = muted;
        this.secondary = secondary;
        this.secondaryForeground = secondaryForeground;
        this.primary = primary;
        this.primaryForeground = primaryForeground;
        this.outline = border;
    }

    // The colour with its alpha scaled, so a translucent --input at 30% is 30% of its own alpha.
    private static int withAlpha(int color, float scale) {
        return (color & 0x00FFFFFF) | (Math.round(Color.alpha(color) * scale) << 24);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // Kept apart from WidgetStore, whose contents are cleared on logout: a theme is the user's choice, not their login.
    static String choice(Context context, int appWidgetId) {
        return prefs(context).getString("id_" + appWidgetId, DEFAULT);
    }

    static void setChoice(Context context, int appWidgetId, String choice) {
        prefs(context).edit().putString("id_" + appWidgetId, choice).apply();
    }

    static void forget(Context context, int[] appWidgetIds) {
        SharedPreferences.Editor editor = prefs(context).edit();
        for (int id : appWidgetIds) editor.remove("id_" + id);
        editor.apply();
    }

    static boolean wallpaperAvailable() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    static WidgetTheme forWidget(Context context, int appWidgetId) {
        return of(context, choice(context, appWidgetId));
    }

    static WidgetTheme of(Context context, String choice) {
        if (WALLPAPER.equals(choice) && wallpaperAvailable()) return wallpaper(context);
        WidgetThemes.Entry entry = WidgetThemes.find(WALLPAPER.equals(choice) ? DEFAULT : choice);
        if (entry == null) entry = WidgetThemes.find(DEFAULT);
        return new WidgetTheme(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S, entry.light, entry.card, entry.cardForeground, entry.mutedForeground, entry.secondary, entry.secondaryForeground, entry.primary, entry.primaryForeground, entry.background, entry.border, entry.input);
    }

    // Material You tones from the wallpaper, light or dark with the phone.
    private static WidgetTheme wallpaper(Context context) {
        boolean night = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int card = context.getColor(night ? android.R.color.system_accent2_800 : android.R.color.system_accent2_50);
        int foreground = context.getColor(night ? android.R.color.system_neutral1_100 : android.R.color.system_neutral1_900);
        int faint = withAlpha(foreground | 0xFF000000, 0.15f);
        return new WidgetTheme(true, !night, card, foreground,
            context.getColor(night ? android.R.color.system_neutral2_200 : android.R.color.system_neutral2_700),
            context.getColor(night ? android.R.color.system_accent2_700 : android.R.color.system_accent2_100),
            context.getColor(night ? android.R.color.system_accent2_100 : android.R.color.system_accent2_700),
            context.getColor(night ? android.R.color.system_accent1_200 : android.R.color.system_accent1_600),
            context.getColor(night ? android.R.color.system_accent1_800 : android.R.color.system_accent1_0),
            card, faint, faint);
    }

    // The card behind everything.
    void card(RemoteViews views, int id) {
        tint(views, id, card);
    }

    // A filled chip: the count badge, and the buttons, which are the same colour so they match it.
    void chip(RemoteViews views, int... ids) {
        for (int id : ids) tint(views, id, secondary);
    }

    void divider(RemoteViews views, int id) {
        if (themed) views.setInt(id, "setBackgroundColor", outline);
    }

    void text(RemoteViews views, int color, int... ids) {
        if (!themed) return;
        for (int id : ids) views.setTextColor(id, color);
    }

    // Recolours an icon. Pass NO_FILTER for one that keeps its own colours.
    void icon(RemoteViews views, int id, int color) {
        if (themed) views.setInt(id, "setColorFilter", color);
    }

    private void tint(RemoteViews views, int id, int color) {
        if (themed) views.setColorStateList(id, "setBackgroundTintList", ColorStateList.valueOf(color));
    }
}
