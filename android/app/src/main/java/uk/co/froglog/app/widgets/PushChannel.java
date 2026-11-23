package uk.co.froglog.app.widgets;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import uk.co.froglog.app.R;

/**
 * The channel FrogLog's pushes arrive on (the manifest makes it Firebase's default). High importance, so they pop
 * up, which is what lets One UI show them as brief pop-ups. Without it, Firebase used its own "Miscellaneous"
 * channel, whose default importance only puts a notification in the shade.
 */
public final class PushChannel {
    // Must match com.google.firebase.messaging.default_notification_channel_id in the manifest
    static final String ID = "froglog_notifications";

    private PushChannel() {}

    // Safe to call often: creating an existing channel only updates its name and description, never the user's choices.
    public static void ensure(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(ID, context.getString(R.string.push_channel), NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(context.getString(R.string.push_channel_description));
        manager.createNotificationChannel(channel);
    }
}
