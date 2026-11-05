package uk.co.froglog.app.remote;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Requests to a PC running LilyPad, over mutual TLS. The PC's certificate is not checked against any authority: it has to be the
 * one whose fingerprint was pinned when pairing, and while pairing (no pin yet) whatever it presents is accepted and returned,
 * because the pairing proof is what then shows it was the right one.
 */
final class RemoteClient {
    static final class Response {
        final int status;
        final JSONObject body;
        /** The fingerprint of the certificate the PC presented. */
        final String serverFingerprint;

        Response(int status, JSONObject body, String serverFingerprint) {
            this.status = status;
            this.body = body;
            this.serverFingerprint = serverFingerprint;
        }
    }

    /** Lets another thread end a request that is waiting (the pairing wait for a PIN). */
    static final class Cancel {
        private volatile HttpsURLConnection connection;
        volatile boolean cancelled;

        void cancel() {
            cancelled = true;
            HttpsURLConnection current = connection;
            if (current != null) current.disconnect();
        }
    }

    private RemoteClient() {}

    /** Trusts exactly one certificate when given its fingerprint, or any (recording it) when not. */
    private static final class Pin implements X509TrustManager {
        private final String expected;
        volatile String seen;

        Pin(String expected) {
            this.expected = expected;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (chain == null || chain.length == 0) throw new CertificateException("no certificate");
            seen = RemoteCrypto.fingerprint(chain[0].getEncoded());
            if (expected != null && !expected.equals(seen)) throw new CertificateException("this is not the PC that was linked");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("not a server");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    static Response request(String host, int port, String method, String path, JSONObject body, String pinnedFingerprint, int readTimeoutMs) throws Exception {
        return request(host, port, method, path, body, pinnedFingerprint, readTimeoutMs, null);
    }

    static Response request(String host, int port, String method, String path, JSONObject body, String pinnedFingerprint, int readTimeoutMs, Cancel cancel) throws Exception {
        Pin pin = new Pin(pinnedFingerprint);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(RemoteIdentity.keyManagers(), new TrustManager[] { pin }, null);
        // An IPv6 address needs its brackets in a URL.
        String authority = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        HttpsURLConnection connection = (HttpsURLConnection) new URL("https://" + authority + ":" + port + path).openConnection();
        if (cancel != null) cancel.connection = connection;
        try {
            connection.setSSLSocketFactory(context.getSocketFactory());
            // The certificate is pinned (or checked by the pairing proof), and a LAN address has no name to check.
            connection.setHostnameVerifier((name, session) -> true);
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(readTimeoutMs);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Connection", "close");
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            JSONObject json = new JSONObject();
            if (stream != null) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[2048];
                int read;
                while ((read = stream.read(buffer)) != -1) bytes.write(buffer, 0, read);
                String text = bytes.toString("UTF-8");
                if (!text.isEmpty()) json = new JSONObject(text);
            }
            return new Response(status, json, pin.seen);
        } finally {
            connection.disconnect();
        }
    }
}
