package uk.co.froglog.app.widgets;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;

import uk.co.froglog.app.R;

/**
 * Home screen widget for the last 7 days, from /api/widgets/week: hours against the week before,
 * sessions and the most played game, and a bar per day.
 *
 * It refreshes every 30 minutes and whenever the site's Quick Session changes, which covers a
 * session being logged from the app. Widgets can't draw, so the bars are a bitmap.
 */
public class ThisWeekWidget extends AppWidgetProvider {
    private static final String ACTION_REFRESH = "uk.co.froglog.app.widgets.THIS_WEEK_REFRESH";
    private static final String CACHE_WEEK = "this_week";
    private static final String TAG = "FrogLogWidgets";
    private static final String[] DAY_LETTERS = { "S", "M", "T", "W", "T", "F", "S" };

    static void refresh(Context context) {
        context.sendBroadcast(new Intent(context, ThisWeekWidget.class).setAction(ACTION_REFRESH));
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(action) && !ACTION_REFRESH.equals(action)) {
            super.onReceive(context, intent);
            return;
        }
        Context app = context.getApplicationContext();
        PendingResult pending = goAsync();
        WidgetSupport.WORKER.execute(() -> {
            try {
                fetchAndRender(app);
            } finally {
                pending.finish();
            }
        });
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        WidgetTheme.forget(context, appWidgetIds);
    }

    @Override
    public void onDisabled(Context context) {
        WidgetSupport.lastOneRemoved(context);
    }

    private static void fetchAndRender(Context context) {
        if (!WidgetStore.isSignedIn(context)) {
            render(context, null, false);
            return;
        }
        WidgetDevices.register(context);
        try {
            FroglogApi.Response week = FroglogApi.get(context, "/api/widgets/week");
            Log.i(TAG, "week: fetched, HTTP " + week.status);
            if (week.status == 401) {
                WidgetStore.clearLogin(context);
                render(context, null, false);
                return;
            }
            if (week.ok()) WidgetStore.putCache(context, CACHE_WEEK, week.body.toString());
        } catch (Exception offline) {
            // Falls through to the cached week.
            Log.w(TAG, "week: fetch failed, showing the saved week: " + offline);
        }
        render(context, WidgetSupport.parse(WidgetStore.cache(context, CACHE_WEEK)), true);
    }

    private static void render(Context context, JSONObject week, boolean signedIn) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = WidgetSupport.ids(context, ThisWeekWidget.class, ThisWeekFlexWidget.class);
        for (int id : ids) {
            float[] chart = chartSizeDp(context, manager.getAppWidgetOptions(id));
            // The cover screen's bottom corners curve in, so the chart stops higher up there.
            boolean cover = WidgetSupport.isCover(context, id);
            if (cover) chart[1] = Math.max(CHART_HEIGHT_DP, chart[1] - WidgetSupport.COVER_EXTRA_BOTTOM_DP);
            RemoteViews views = build(context, week, signedIn, chart[0], chart[1], WidgetTheme.forWidget(context, id), cover);
            float density = context.getResources().getDisplayMetrics().density;
            int side = Math.round(16 * density);
            views.setViewPadding(R.id.week_root, side, side, side, Math.round((16 + (cover ? WidgetSupport.COVER_EXTRA_BOTTOM_DP : 0)) * density));
            manager.updateAppWidget(id, WidgetSupport.squareOnCover(context, id, views, R.id.week_root));
        }
    }

    // Redraw when the widget is resized, because the chart is a bitmap made to the widget's width.
    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) {
        refresh(context);
    }

    // The room the chart has, in dp: the widget’s size less its 16dp padding, and less the header, hours and summary above it (about 152dp in all).
    // It is an estimate, so the picture is stretched to fit, and a few dp either way will not show.
    private static float[] chartSizeDp(Context context, Bundle options) {
        boolean landscape = context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        float width = options.getInt(landscape ? AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH : AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH);
        float height = options.getInt(landscape ? AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT : AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT);
        if (width <= 0 || height <= 0) return new float[] { CHART_WIDTH_DP, CHART_HEIGHT_DP };
        return new float[] { Math.max(120, width - 32), Math.max(CHART_HEIGHT_DP, height - 152) };
    }

    private static RemoteViews build(Context context, JSONObject week, boolean signedIn, float chartWidthDp, float chartHeightDp, WidgetTheme theme, boolean cover) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_this_week);
        theme.card(views, R.id.week_root);
        theme.icon(views, R.id.week_logo, theme.foreground);
        theme.text(views, theme.foreground, R.id.week_title, R.id.week_hours);
        theme.text(views, theme.muted, R.id.week_delta, R.id.week_summary);
        views.setOnClickPendingIntent(R.id.week_root, WidgetSupport.openApp(context, "/stats/personal"));
        views.setViewVisibility(R.id.week_chart, View.GONE);
        views.setTextViewText(R.id.week_delta, "");
        views.setViewVisibility(R.id.week_delta, View.VISIBLE);

        if (!signedIn || week == null) {
            views.setTextViewText(R.id.week_hours, "–");
            views.setTextViewText(R.id.week_summary, context.getString(signedIn ? R.string.widget_week_loading : R.string.widget_signed_out_hint));
            return views;
        }

        double hours = week.optDouble("hours", 0);
        double prev = week.optDouble("prevHours", 0);
        views.setTextViewText(R.id.week_hours, formatHours(hours));
        views.setTextViewText(R.id.week_delta, deltaText(context, hours, prev, cover));
        int sessions = week.optInt("sessions", 0);
        String summary = context.getResources().getQuantityString(R.plurals.widget_week_sessions, sessions, sessions);
        JSONObject top = cover ? null : week.optJSONObject("topGame");
        SpannableString text;
        if (top != null) {
            // The game’s name is bold; "Most Played:" and the session count are not.
            String title = top.optString("title");
            summary += " · " + context.getString(R.string.widget_week_top_game, title);
            text = new SpannableString(summary);
            if (!title.isEmpty()) text.setSpan(new StyleSpan(Typeface.BOLD), summary.length() - title.length(), summary.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else {
            text = new SpannableString(summary);
        }
        views.setTextViewText(R.id.week_summary, text);

        JSONArray dayList = week.optJSONArray("days");
        if (dayList != null && dayList.length() > 0) {
            views.setViewVisibility(R.id.week_chart, View.VISIBLE);
            views.setImageViewBitmap(R.id.week_chart, drawChart(context, dayList, chartWidthDp, chartHeightDp, theme));
        }
        return views;
    }

    // The cover screen keeps it short: the arrow and the difference ("▲ 2.5h"), without "more than last week".
    private static String deltaText(Context context, double hours, double prev, boolean cover) {
        double change = Math.round((hours - prev) * 10) / 10.0;
        if (change == 0) return context.getString(R.string.widget_week_same);
        if (cover) return (change > 0 ? "▲ " : "▼ ") + formatHours(Math.abs(change));
        return context.getString(change > 0 ? R.string.widget_week_up : R.string.widget_week_down, formatHours(Math.abs(change)));
    }

    // Matches the site's formatWeekHours: one decimal, without a trailing .0.
    private static String formatHours(double hours) {
        double rounded = Math.round(hours * 10) / 10.0;
        return (rounded == Math.floor(rounded) ? String.valueOf((long) rounded) : String.format(Locale.UK, "%.1f", rounded)) + "h";
    }

    private static final float CHART_WIDTH_DP = 280;
    // The least height the chart gets, and its height when the widget’s size is not known.
    private static final float CHART_HEIGHT_DP = 88;
    private static final float LABEL_SPACE_DP = 26;

    // A bar per day, the biggest in the primary colour. Days with no play get a short stub so the week still reads as seven days.
    private static Bitmap drawChart(Context context, JSONArray days, float widthDp, float heightDp, WidgetTheme theme) {
        // Drawn at up to 2x and scaled to the view, so a dp here is still a dp on screen while the bitmap stays within a widget's memory limit.
        float density = Math.min(2f, context.getResources().getDisplayMetrics().density);
        int width = Math.round(widthDp * density);
        int height = Math.round(heightDp * density);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        double max = 0;
        for (int i = 0; i < days.length(); i++) max = Math.max(max, days.optJSONObject(i).optDouble("hours", 0));

        int count = days.length();
        float slot = width / (float) count;
        float barWidth = slot * 0.78f;
        float barArea = height - LABEL_SPACE_DP * density;
        float radius = 4 * density;
        float stub = 2 * density;

        Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        label.setColor(theme.muted);
        label.setTextSize(17 * density);
        label.setTextAlign(Paint.Align.CENTER);

        for (int i = 0; i < count; i++) {
            JSONObject day = days.optJSONObject(i);
            double hours = day.optDouble("hours", 0);
            float centre = slot * i + slot / 2;
            float barHeight = max > 0 && hours > 0 ? Math.max(stub * 2, (float) (hours / max) * barArea) : stub;
            bar.setColor(hours > 0 && hours == max ? theme.primary : theme.secondary);
            canvas.drawRoundRect(new RectF(centre - barWidth / 2, barArea - barHeight, centre + barWidth / 2, barArea), radius, radius, bar);
            canvas.drawText(dayLetter(day.optString("day")), centre, height - 2 * density, label);
        }
        return bitmap;
    }

    private static String dayLetter(String isoDay) {
        try {
            String[] parts = isoDay.split("-");
            Calendar calendar = Calendar.getInstance();
            calendar.set(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) - 1, Integer.parseInt(parts[2]));
            return DAY_LETTERS[calendar.get(Calendar.DAY_OF_WEEK) - 1];
        } catch (Exception e) {
            return "";
        }
    }
}
