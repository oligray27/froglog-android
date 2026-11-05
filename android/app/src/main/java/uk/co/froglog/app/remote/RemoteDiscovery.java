package uk.co.froglog.app.remote;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Finds LilyPad on the local network: it announces `_froglog-lilypad._tcp` (lilypad-core lan/discovery.rs) with its name and the
 * first 16 characters of its certificate fingerprint. What this finds is only a hint of where to connect; nothing in it is trusted.
 */
final class RemoteDiscovery {
    private static final String TAG = "FrogLogRemote";
    private static final String SERVICE_TYPE = "_froglog-lilypad._tcp";

    private RemoteDiscovery() {}

    /** This phone's own IPv4 addresses (Wi-Fi and the like), to tell which of a PC's addresses is on the same network. */
    private static java.util.List<byte[]> ownIpv4s() {
        java.util.List<byte[]> own = new java.util.ArrayList<>();
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) continue;
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address) own.add(address.getAddress());
                }
            }
        } catch (Exception ignored) {
            // No list of our own addresses: the first one given is used.
        }
        return own;
    }

    /**
     * The address to connect to. A PC announces its IPv4 addresses (the "ip" property), and the one on this phone's own network (the same
     * first three numbers) wins. The address mDNS itself hands over is only a fallback, and an IPv6 link-local one (fe80::...) is never
     * used: it can't be connected to without knowing the interface. Null if nothing usable is known.
     */
    static String chooseAddress(String announced, InetAddress resolved) {
        java.util.List<byte[]> own = ownIpv4s();
        String first = null;
        if (announced != null) {
            for (String candidate : announced.split(",")) {
                candidate = candidate.trim();
                String[] parts = candidate.split("\\.");
                if (parts.length != 4) continue;
                if (first == null) first = candidate;
                try {
                    for (byte[] mine : own) {
                        if ((mine[0] & 0xff) == Integer.parseInt(parts[0]) && (mine[1] & 0xff) == Integer.parseInt(parts[1]) && (mine[2] & 0xff) == Integer.parseInt(parts[2])) {
                            return candidate;
                        }
                    }
                } catch (NumberFormatException ignored) {
                    // Not an address after all.
                }
            }
        }
        if (resolved != null && (resolved instanceof Inet4Address || !resolved.isLinkLocalAddress())) return resolved.getHostAddress();
        return first;
    }

    /** Browses for `listenMs` and returns [{name, host, port, fp}] (fp is the fingerprint prefix). */
    static JSONArray discover(Context context, long listenMs) throws Exception {
        NsdManager nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        JSONArray found = new JSONArray();
        Queue<NsdServiceInfo> waiting = new ArrayDeque<>();
        Object lock = new Object();
        boolean[] resolving = { false };

        // Resolving is one at a time (Android refuses overlapping resolves).
        Runnable[] next = new Runnable[1];
        NsdManager.ResolveListener[] resolver = new NsdManager.ResolveListener[1];
        resolver[0] = new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                Log.w(TAG, "resolve failed: " + errorCode);
                synchronized (lock) {
                    resolving[0] = false;
                }
                next[0].run();
            }

            @Override
            public void onServiceResolved(NsdServiceInfo info) {
                try {
                    Map<String, byte[]> attributes = info.getAttributes();
                    byte[] name = attributes.get("name");
                    byte[] fp = attributes.get("fp");
                    byte[] announced = attributes.get("ip");
                    String address = chooseAddress(announced != null ? new String(announced, StandardCharsets.UTF_8) : null, info.getHost());
                    if (address != null) {
                        JSONObject device = new JSONObject();
                        device.put("name", name != null ? new String(name, StandardCharsets.UTF_8) : info.getServiceName());
                        device.put("host", address);
                        device.put("port", info.getPort());
                        device.put("fp", fp != null ? new String(fp, StandardCharsets.UTF_8) : "");
                        synchronized (lock) {
                            found.put(device);
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "could not read a found device: " + e);
                }
                synchronized (lock) {
                    resolving[0] = false;
                }
                next[0].run();
            }
        };
        next[0] = () -> {
            NsdServiceInfo info;
            synchronized (lock) {
                if (resolving[0] || (info = waiting.poll()) == null) return;
                resolving[0] = true;
            }
            try {
                nsd.resolveService(info, resolver[0]);
            } catch (Exception e) {
                synchronized (lock) {
                    resolving[0] = false;
                }
            }
        };

        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) { Log.w(TAG, "discovery failed to start: " + errorCode); }
            @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {}
            @Override public void onDiscoveryStarted(String serviceType) {}
            @Override public void onDiscoveryStopped(String serviceType) {}
            @Override public void onServiceLost(NsdServiceInfo info) {}

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                synchronized (lock) {
                    waiting.add(info);
                }
                next[0].run();
            }
        };

        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener);
        new CountDownLatch(1).await(listenMs, TimeUnit.MILLISECONDS);
        try {
            nsd.stopServiceDiscovery(listener);
        } catch (Exception ignored) {
            // Already stopped.
        }
        synchronized (lock) {
            return found;
        }
    }
}
