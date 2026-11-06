package uk.co.froglog.app.widgets;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;

import uk.co.froglog.app.R;

/**
 * Feeds Remote Start's scrolling list from the widget's cached answer: the recent games the PC can start, each a cover, the title,
 * when it was last played, and a play button. A tap anywhere on the row starts it (the widget's template plus this row's game).
 */
public class RemoteStartService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(getApplicationContext(), intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID));
    }

    private static class Factory implements RemoteViewsFactory {
        // The cover is drawn 42x56dp; this keeps it sharp on a dense screen without a large bitmap in every row.
        private static final int COVER_W = 132;
        private static final int COVER_H = 176;
        private final Context context;
        private final int appWidgetId;
        private JSONArray games = new JSONArray();
        private String pcId;
        private boolean unavailable;

        Factory(Context context, int appWidgetId) {
            this.context = context;
            this.appWidgetId = appWidgetId;
        }

        @Override
        public void onCreate() {
            load();
        }

        @Override
        public void onDataSetChanged() {
            load();
        }

        // This widget's PC and the games kept for it.
        private void load() {
            JSONObject pc = WidgetStore.isSignedIn(context) ? RemoteStartWidget.pcFor(context, appWidgetId, RemoteStartWidget.devices(context)) : null;
            pcId = pc == null ? null : pc.optString("id");
            JSONObject list = RemoteStartWidget.listFor(context, pcId);
            JSONArray shown = list == null ? null : list.optJSONArray("games");
            games = shown == null ? new JSONArray() : shown;
            unavailable = RemoteStartWidget.isUnavailable(list);
        }

        @Override
        public void onDestroy() {
            games = new JSONArray();
        }

        @Override
        public int getCount() {
            return games.length();
        }

        @Override
        public RemoteViews getViewAt(int position) {
            RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.widget_remote_start_item);
            JSONObject game = games.optJSONObject(position);
            if (game == null) return row;
            WidgetTheme theme = WidgetTheme.forWidget(context, appWidgetId);
            theme.text(row, theme.foreground, R.id.remote_item_title);
            theme.text(row, theme.muted, R.id.remote_item_played);
            theme.chip(row, R.id.remote_item_play);
            theme.icon(row, R.id.remote_item_play, WidgetTheme.NO_FILTER);
            theme.divider(row, R.id.remote_item_divider);
            // Dimmed while a session is running or the PC is unavailable; a tap then only says so, or checks the PC again (RemoteStartWidget.launch).
            row.setFloat(R.id.remote_item_play, "setAlpha", unavailable || RemoteStartWidget.runningSession(context) != null ? 0.4f : 1f);
            row.setViewVisibility(R.id.remote_item_divider, position > 0 ? View.VISIBLE : View.GONE);

            String title = game.optString("title", context.getString(R.string.widget_unknown_game));
            row.setTextViewText(R.id.remote_item_title, title);
            row.setTextViewText(R.id.remote_item_played, played(game.isNull("last_played") ? null : game.optString("last_played", null)));
            row.setContentDescription(R.id.remote_item_play, context.getString(R.string.widget_remote_play, title));

            Bitmap cover = CoverArt.cached(context, RemoteStartWidget.cover(game));
            if (cover != null) {
                row.setImageViewBitmap(R.id.remote_item_cover, Bitmap.createScaledBitmap(cover, COVER_W, COVER_H, true));
                row.setInt(R.id.remote_item_cover, "setColorFilter", WidgetTheme.NO_FILTER);
            } else {
                // No cover yet: the frog, in the muted colour.
                row.setImageViewResource(R.id.remote_item_cover, R.drawable.ic_notification);
                theme.icon(row, R.id.remote_item_cover, theme.muted);
            }

            Intent fill = new Intent()
                .putExtra(RemoteStartWidget.EXTRA_TYPE, game.optString("type"))
                .putExtra(RemoteStartWidget.EXTRA_ID, game.optInt("id"))
                .putExtra(RemoteStartWidget.EXTRA_TITLE, title)
                .putExtra(RemoteStartWidget.EXTRA_PC, pcId);
            if (!game.isNull("steam_launch_id")) fill.putExtra(RemoteStartWidget.EXTRA_STEAM, game.optString("steam_launch_id"));
            row.setOnClickFillInIntent(R.id.remote_item_root, fill);
            return row;
        }

        // "Played today", "Played yesterday", "Played 5 days ago", from the session date (a calendar day, YYYY-MM-DD).
        private String played(String date) {
            if (date == null || date.length() < 10) return "";
            try {
                Calendar day = Calendar.getInstance();
                day.clear();
                day.set(Integer.parseInt(date.substring(0, 4)), Integer.parseInt(date.substring(5, 7)) - 1, Integer.parseInt(date.substring(8, 10)));
                Calendar today = Calendar.getInstance();
                today.set(Calendar.HOUR_OF_DAY, 0);
                today.set(Calendar.MINUTE, 0);
                today.set(Calendar.SECOND, 0);
                today.set(Calendar.MILLISECOND, 0);
                long days = Math.max(0, Math.round((today.getTimeInMillis() - day.getTimeInMillis()) / 86_400_000.0));
                if (days == 0) return context.getString(R.string.widget_remote_played_today);
                if (days == 1) return context.getString(R.string.widget_remote_played_yesterday);
                return context.getResources().getQuantityString(R.plurals.widget_remote_played_days, (int) days, (int) days);
            } catch (Exception e) {
                return "";
            }
        }

        @Override
        public RemoteViews getLoadingView() {
            return null;
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean hasStableIds() {
            return false;
        }
    }
}
