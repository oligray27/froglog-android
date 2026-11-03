package uk.co.froglog.app.widgets;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * The few FrogLog API calls the widgets make, with the login from WidgetStore.
 * Blocking, so callers run it off the main thread.
 */
final class FroglogApi {
    private static final int TIMEOUT_MS = 10_000;

    static final class Response {
        final int status;
        final JSONObject body;

        Response(int status, JSONObject body) {
            this.status = status;
            this.body = body;
        }

        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private FroglogApi() {}

    static Response get(Context context, String path) throws Exception {
        return request(context, "GET", path, null);
    }

    static Response post(Context context, String path, JSONObject body) throws Exception {
        return request(context, "POST", path, body == null ? new JSONObject() : body);
    }

    static Response delete(Context context, String path, JSONObject body) throws Exception {
        return request(context, "DELETE", path, body);
    }

    private static Response request(Context context, String method, String path, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(WidgetStore.apiUrl(context) + path).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("Authorization", "Bearer " + WidgetStore.token(context));
            connection.setRequestProperty("Accept", "application/json");
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String text = stream == null ? "" : readAll(stream);
            return new Response(status, text.isEmpty() ? new JSONObject() : new JSONObject(text));
        } finally {
            connection.disconnect();
        }
    }

    private static String readAll(InputStream stream) throws Exception {
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toString("UTF-8");
        }
    }
}
