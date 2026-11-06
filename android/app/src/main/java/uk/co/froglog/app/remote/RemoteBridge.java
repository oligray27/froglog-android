package uk.co.froglog.app.remote;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.SocketTimeoutException;

/**
 * The same linked PCs and the same pinned mutual-TLS requests FrogLogRemotePlugin uses, for code that isn't called from the site. A PC that
 * can't be reached at its last address is looked for once on the network and tried again where it is found.
 */
public final class RemoteBridge {
    static final int DEFAULT_PORT = 47821;

    /** A PC's answer: its HTTP status and body, and whether it said this app is no longer linked (it is then forgotten here too). */
    public static final class Answer {
        public final int status;
        public final JSONObject body;
        public final boolean unlinked;

        Answer(int status, JSONObject body, boolean unlinked) {
            this.status = status;
            this.body = body;
            this.unlinked = unlinked;
        }
    }

    private RemoteBridge() {}

    /** The account the site last used here, for code that has none to give (it is "" until the site has asked about PCs once). */
    public static String lastAccount(Context context) {
        return RemoteDevices.lastAccount(context);
    }

    /** The account's linked PCs, each as the site sees them ({id, name, pcName, host, port}). */
    public static JSONArray devices(Context context, String account) {
        RemoteDevices.use(context, account);
        JSONArray shown = new JSONArray();
        JSONArray stored = RemoteDevices.list(context);
        for (int i = 0; i < stored.length(); i++) {
            JSONObject device = stored.optJSONObject(i);
            if (device != null) shown.put(RemoteDevices.shown(device));
        }
        return shown;
    }

    /**
     * Sends one request to a linked PC. Throws when it can't be reached; a read timeout (connected, but no answer in time, as when
     * nobody answers a prompt on the PC) is thrown as SocketTimeoutException so the caller can tell it apart.
     */
    public static Answer call(Context context, String account, String id, String method, String path, JSONObject body, int readTimeoutMs) throws Exception {
        RemoteDevices.use(context, account);
        JSONObject device = id == null ? null : RemoteDevices.find(context, id);
        if (device == null) throw new IOException("not linked");
        RemoteClient.Response response = request(context, device, method, path, body, readTimeoutMs);
        boolean unlinked = response.status == 401 && "not_paired".equals(response.body.optString("error"));
        if (unlinked) RemoteDevices.forget(context, id);
        return new Answer(response.status, response.body, unlinked);
    }

    /** One request to a linked PC, finding it again on the network once if its address has changed. */
    static RemoteClient.Response request(Context context, JSONObject device, String method, String path, JSONObject body, int readTimeoutMs) throws Exception {
        String id = device.optString("id");
        String host = device.optString("host");
        int port = device.optInt("port", DEFAULT_PORT);
        try {
            return RemoteClient.request(host, port, method, path, body, id, readTimeoutMs);
        } catch (SocketTimeoutException timeout) {
            throw timeout;
        } catch (IOException unreachable) {
            // It may have moved (a new address after a router restart): look for it once.
            JSONObject moved = null;
            JSONArray found = RemoteDiscovery.discover(context, 2500);
            for (int i = 0; i < found.length(); i++) {
                JSONObject candidate = found.getJSONObject(i);
                if (!candidate.optString("fp").isEmpty() && id.startsWith(candidate.optString("fp"))) moved = candidate;
            }
            if (moved == null) throw unreachable;
            host = moved.optString("host");
            port = moved.optInt("port", DEFAULT_PORT);
            RemoteDevices.save(context, id, device.optString("name"), host, port);
            return RemoteClient.request(host, port, method, path, body, id, readTimeoutMs);
        }
    }
}
