package uk.co.froglog.app.widgets;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import org.json.JSONArray;
import org.json.JSONObject;

import uk.co.froglog.app.R;

/**
 * Feeds Online Now's scrolling list. It reads the same cached answer the widget draws its header from,
 * so a refresh only has to update that cache and tell the list its data changed.
 */
public class FriendsService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(getApplicationContext(), intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID));
    }

    private static class Factory implements RemoteViewsFactory {
        private final Context context;
        private final int appWidgetId;
        private JSONArray friends = new JSONArray();

        Factory(Context context, int appWidgetId) {
            this.context = context;
            this.appWidgetId = appWidgetId;
        }

        @Override
        public void onCreate() {
            load();
        }

        // Called on a notifyAppWidgetViewDataChanged, which is also how the minute alarm redraws the times.
        @Override
        public void onDataSetChanged() {
            load();
        }

        private void load() {
            JSONObject data = WidgetSupport.parse(WidgetStore.cache(context, FriendsWidget.CACHE_FRIENDS));
            JSONArray list = data == null ? null : data.optJSONArray("friends");
            friends = list == null ? new JSONArray() : list;
        }

        @Override
        public void onDestroy() {
            friends = new JSONArray();
        }

        @Override
        public int getCount() {
            return friends.length();
        }

        @Override
        public RemoteViews getViewAt(int position) {
            RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.widget_friend_item);
            JSONObject friend = friends.optJSONObject(position);
            if (friend == null) return row;
            WidgetTheme theme = WidgetTheme.forWidget(context, appWidgetId);
            theme.text(row, theme.foreground, R.id.friend_item_name);
            theme.text(row, theme.muted, R.id.friend_item_game, R.id.friend_item_timer);
            theme.divider(row, R.id.friend_item_divider);
            row.setViewVisibility(R.id.friend_item_divider, position > 0 ? android.view.View.VISIBLE : android.view.View.GONE);
            boolean online = friend.optBoolean("online", true);
            row.setTextViewText(R.id.friend_item_name, friend.optString("name", friend.optString("username")));
            // A green dot for people who are online and a grey one for people who are offline.
            row.setTextViewCompoundDrawablesRelative(R.id.friend_item_name, online ? R.drawable.ic_widget_live : R.drawable.ic_widget_offline, 0, 0, 0);
            if (online) {
                row.setTextViewText(R.id.friend_item_game, friend.isNull("title") ? context.getString(R.string.widget_friends_unknown_game) : friend.optString("title"));
                long elapsed = FriendsWidget.elapsedMs(friend);
                row.setViewVisibility(R.id.friend_item_timer, elapsed >= 0 ? android.view.View.VISIBLE : android.view.View.GONE);
                if (elapsed >= 0) row.setTextViewText(R.id.friend_item_timer, QuickSessionWidget.formatElapsed(elapsed));
            } else {
                row.setTextViewText(R.id.friend_item_game, lastSeen(friend));
                row.setViewVisibility(R.id.friend_item_timer, android.view.View.GONE);
            }
            // The list's template opens the app; this adds the page.
            row.setOnClickFillInIntent(R.id.friend_item_root, new Intent().putExtra(FrogLogWidgetsPlugin.EXTRA_URL, "/stats/social/" + friend.optString("username") + "/games"));
            return row;
        }

        // "Last seen 5m ago", or just "Offline" when we have never seen them.
        private String lastSeen(JSONObject friend) {
            long seen = QuickSessionWidget.parseIso(friend.isNull("last_seen_at") ? null : friend.optString("last_seen_at", null));
            if (seen < 0) return context.getString(R.string.widget_friends_never_seen);
            long minutes = Math.max(0, (System.currentTimeMillis() - seen) / 60_000);
            String ago = minutes < 1 ? context.getString(R.string.widget_ago_now)
                : minutes < 60 ? context.getString(R.string.widget_ago_minutes, (int) minutes)
                : minutes < 24 * 60 ? context.getString(R.string.widget_ago_hours, (int) (minutes / 60))
                : context.getString(R.string.widget_ago_days, (int) (minutes / (24 * 60)));
            return context.getString(R.string.widget_friends_last_seen, ago);
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
