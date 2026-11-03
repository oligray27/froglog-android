package uk.co.froglog.app.widgets;

import androidx.annotation.NonNull;

import com.capacitorjs.plugins.pushnotifications.MessagingService;
import com.google.firebase.messaging.RemoteMessage;

/**
 * Takes the push plugin's place as the app's Firebase service (the manifest removes the plugin's
 * own), so silent widget refreshes are handled here, even with the app closed. Everything else
 * goes to the plugin as before, so notifications are unchanged.
 */
public class FrogLogMessagingService extends MessagingService {
    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        if ("widget_refresh".equals(message.getData().get("type"))) {
            WidgetSupport.refreshAll(getApplicationContext());
            return;
        }
        super.onMessageReceived(message);
    }

    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        WidgetSupport.WORKER.execute(() -> WidgetDevices.register(getApplicationContext()));
    }
}
