package uk.co.froglog.app.remote;

import android.os.Build;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;

/**
 * The site's link to PCs running LilyPad on the same network (frontend utils/remoteDevices.js): starting a game on a linked PC.
 *
 * Nothing here goes through the FrogLog server. A PC is linked once with a PIN this app shows and the PC's user types in LilyPad
 * (pair), after which this app and that PC pin each other's certificates and talk over mutual TLS. LilyPad decides what "launch"
 * means from its own data; the app only ever sends a game id (and the game's Steam launch id), never a path or command.
 * Requests resolve with the PC's HTTP status and body, and reject only when the PC can't be reached.
 */
@CapacitorPlugin(name = "FrogLogRemote")
public class FrogLogRemotePlugin extends Plugin {
    private static final int DEFAULT_PORT = 47821;
    private final ExecutorService io = Executors.newCachedThreadPool();
    // The pairing that is waiting for its PIN to be typed on the PC, so it can be cancelled.
    private volatile RemoteClient.Cancel pairing;
    private volatile String pairingHost;
    private volatile int pairingPort;

    private static JSObject toJs(JSONObject json) {
        try {
            return new JSObject(json.toString());
        } catch (Exception e) {
            return new JSObject();
        }
    }

    /** The PCs found on the network now, marking the linked ones (and refreshing where they were last seen). */
    @PluginMethod
    public void discover(PluginCall call) {
        RemoteDevices.use(getContext(), call.getString("account"));
        io.execute(() -> {
            try {
                JSONArray found = RemoteDiscovery.discover(getContext(), 3500);
                JSArray devices = new JSArray();
                for (int i = 0; i < found.length(); i++) {
                    JSONObject device = found.getJSONObject(i);
                    String prefix = device.optString("fp");
                    JSONObject linked = linkedByPrefix(prefix);
                    device.put("linked", linked != null);
                    if (linked != null) {
                        device.put("id", linked.optString("id"));
                        RemoteDevices.save(getContext(), linked.optString("id"), linked.optString("name"), device.optString("host"), device.optInt("port"));
                    }
                    devices.put(device);
                }
                call.resolve(new JSObject().put("devices", devices));
            } catch (Exception e) {
                call.reject("Could not look for PCs: " + e.getMessage(), "discovery_failed");
            }
        });
    }

    private JSONObject linkedByPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) return null;
        JSONArray all = RemoteDevices.list(getContext());
        for (int i = 0; i < all.length(); i++) {
            JSONObject device = all.optJSONObject(i);
            if (device != null && device.optString("id").startsWith(prefix)) return device;
        }
        return null;
    }

    @PluginMethod
    public void list(PluginCall call) {
        RemoteDevices.use(getContext(), call.getString("account"));
        JSObject result = new JSObject();
        try {
            JSONArray shown = new JSONArray();
            JSONArray stored = RemoteDevices.list(getContext());
            for (int i = 0; i < stored.length(); i++) shown.put(RemoteDevices.shown(stored.getJSONObject(i)));
            result.put("devices", new JSArray(shown.toString()));
        } catch (Exception e) {
            result.put("devices", new JSArray());
        }
        call.resolve(result);
    }

    /** The PC no longer has this app on its paired list (it was removed there): a 401 "not_paired". */
    private static boolean isUnlinkedAnswer(RemoteClient.Response response) {
        return response.status == 401 && "not_paired".equals(response.body.optString("error"));
    }

    /**
     * Checks every linked PC. One that no longer lists this app (removed on the PC, or "Unlink all") is forgotten here too, so the
     * app's list follows the PC's. A PC that can't be reached stays, marked reachable: false, since it may only be switched off.
     * Resolves with the devices that remain: [{id, name, host, port, reachable}].
     */
    @PluginMethod
    public void refresh(PluginCall call) {
        RemoteDevices.use(getContext(), call.getString("account"));
        io.execute(() -> {
            try {
                JSONArray all = RemoteDevices.list(getContext());
                List<Callable<JSONObject>> checks = new ArrayList<>();
                for (int i = 0; i < all.length(); i++) checks.add(checker(all.getJSONObject(i)));
                List<JSONObject> results = new ArrayList<>();
                for (Future<JSONObject> future : io.invokeAll(checks)) {
                    JSONObject result = future.get();
                    if (result != null) results.add(result);
                }
                // Some may have moved to a new address: look once, and check those again where they were found.
                boolean anyLost = false;
                for (JSONObject result : results) if (!result.optBoolean("reachable")) anyLost = true;
                if (anyLost) {
                    JSONArray found = RemoteDiscovery.discover(getContext(), 2500);
                    List<Callable<JSONObject>> again = new ArrayList<>();
                    List<JSONObject> kept = new ArrayList<>();
                    for (JSONObject result : results) {
                        JSONObject moved = null;
                        if (!result.optBoolean("reachable")) {
                            for (int i = 0; i < found.length(); i++) {
                                JSONObject candidate = found.getJSONObject(i);
                                String prefix = candidate.optString("fp");
                                if (!prefix.isEmpty() && result.optString("id").startsWith(prefix)) moved = candidate;
                            }
                        }
                        if (moved != null) {
                            RemoteDevices.save(getContext(), result.optString("id"), result.optString("name"), moved.optString("host"), moved.optInt("port"));
                            again.add(checker(RemoteDevices.find(getContext(), result.optString("id"))));
                        } else {
                            kept.add(result);
                        }
                    }
                    for (Future<JSONObject> future : io.invokeAll(again)) {
                        JSONObject result = future.get();
                        if (result != null) kept.add(result);
                    }
                    results = kept;
                }
                JSArray devices = new JSArray();
                for (JSONObject result : results) devices.put(RemoteDevices.shown(result));
                call.resolve(new JSObject().put("devices", devices));
            } catch (Exception e) {
                call.reject("Could not check the PCs: " + e.getMessage(), "refresh_failed");
            }
        });
    }

    /**
     * A quick check of one PC for the Play on PC button: is it there, and is LilyPad on it ready for this app. Resolves with
     * {reachable, problem} ("different_account" or "lilypad_signed_out" when LilyPad answered but can't be used), or {unlinked: true} if it
     * dropped this app. Shorter than a launch: a PC that doesn't answer is looked for once on the network, then it is called unreachable.
     */
    @PluginMethod
    public void ping(PluginCall call) {
        RemoteDevices.use(getContext(), call.getString("account"));
        String id = call.getString("id");
        JSONObject device = id == null ? null : RemoteDevices.find(getContext(), id);
        if (device == null) {
            call.reject("That PC isn't linked", "not_linked");
            return;
        }
        io.execute(() -> {
            try {
                JSONObject result = checker(device).call();
                if (result != null && !result.optBoolean("reachable")) {
                    // It may have moved: look for it once, and check again where it was found.
                    JSONArray found = RemoteDiscovery.discover(getContext(), 2000);
                    for (int i = 0; i < found.length(); i++) {
                        JSONObject candidate = found.getJSONObject(i);
                        String prefix = candidate.optString("fp");
                        if (!prefix.isEmpty() && id.startsWith(prefix)) {
                            RemoteDevices.save(getContext(), id, device.optString("name"), candidate.optString("host"), candidate.optInt("port"));
                            result = checker(RemoteDevices.find(getContext(), id)).call();
                            break;
                        }
                    }
                }
                JSObject out = new JSObject();
                if (result == null) out.put("unlinked", true);
                else out.put("reachable", result.optBoolean("reachable")).put("problem", result.optString("problem"));
                call.resolve(out);
            } catch (Exception e) {
                call.resolve(new JSObject().put("reachable", false));
            }
        });
    }

    /** Asks one PC about itself. Null when it has dropped this app (and it is forgotten); otherwise the device with reachable set. */
    private Callable<JSONObject> checker(JSONObject device) {
        return () -> {
            String id = device.optString("id");
            try {
                RemoteClient.Response response = RemoteClient.request(device.optString("host"), device.optInt("port", DEFAULT_PORT), "GET", "/info", null, id, 4000);
                if (isUnlinkedAnswer(response)) {
                    RemoteDevices.forget(getContext(), id);
                    return null;
                }
                // Its name is its own to change (LilyPad's Device name), so the list follows it.
                String name = response.status == 200 ? response.body.optString("name", device.optString("name")) : device.optString("name");
                RemoteDevices.save(getContext(), id, name, device.optString("host"), device.optInt("port", DEFAULT_PORT));
                // 409: linked, but LilyPad is signed in as someone else (or signed out). It is there, and still linked, so it stays listed.
                String problem = response.status == 409 ? response.body.optString("error") : "";
                return new JSONObject(device.toString()).put("name", name).put("reachable", response.status == 200 || response.status == 409).put("problem", problem);
            } catch (Exception unreachable) {
                return new JSONObject(device.toString()).put("reachable", false);
            }
        };
    }

    /**
     * Links to the PC at host:port. Makes a PIN and emits it as a "pairingPin" event for the site to show; the PC's user types it into
     * LilyPad, which lets the exchange finish. Resolves with {id, name}.
     */
    @PluginMethod
    public void pair(PluginCall call) {
        RemoteDevices.use(getContext(), call.getString("account"));
        String host = call.getString("host");
        int port = call.getInt("port", DEFAULT_PORT);
        String name = call.getString("name", Build.MODEL);
        if (host == null || host.isEmpty()) {
            call.reject("No address for the PC", "bad_request");
            return;
        }
        RemoteClient.Cancel cancel = new RemoteClient.Cancel();
        io.execute(() -> {
            try {
                String myFingerprint = RemoteIdentity.fingerprint();
                String pin = RemoteCrypto.newPin();
                byte[] salt = RemoteCrypto.newSalt();
                notifyListeners("pairingPin", new JSObject().put("pin", pin).put("host", host));

                pairing = cancel;
                pairingHost = host;
                pairingPort = port;
                RemoteClient.Response begin;
                try {
                    begin = RemoteClient.request(host, port, "POST", "/pair/begin",
                        new JSONObject().put("name", name).put("salt", RemoteCrypto.hex(salt)).put("account", call.getString("account", "")), null, 130_000, cancel);
                } finally {
                    pairing = null;
                }
                if (begin.status != 200) {
                    call.reject(pairingMessage(begin.body.optString("error")), begin.body.optString("error", "pairing_failed"));
                    return;
                }
                String pcFingerprint = begin.serverFingerprint;
                byte[] key = RemoteCrypto.deriveKey(salt, pin);
                // LilyPad's tag covers the certificate it really has; one made by someone in the middle, or the wrong PIN, fails here.
                // A wrong PIN is asked about again: LilyPad lets its user type it again, up to its limit, and refuses after that.
                String tag = begin.body.optString("tag");
                while (pcFingerprint == null || !RemoteCrypto.verify(key, RemoteCrypto.ROLE_PC, pcFingerprint, myFingerprint, tag)) {
                    if (pcFingerprint == null) {
                        call.reject("Couldn't identify the PC.", "unreachable");
                        return;
                    }
                    // Nothing is shown for a wrong PIN: the PC lets its user type it again, and only a final refusal is reported.
                    pairing = cancel;
                    RemoteClient.Response next;
                    try {
                        next = RemoteClient.request(host, port, "POST", "/pair/next", null, pcFingerprint, 130_000, cancel);
                    } finally {
                        pairing = null;
                    }
                    if (next.status != 200) {
                        String error = next.body.optString("error");
                        call.reject(error.equals("too_many_attempts") ? "Too many attempts." : pairingMessage(error), error.isEmpty() ? "pairing_failed" : error);
                        return;
                    }
                    tag = next.body.optString("tag");
                }
                RemoteClient.Response finish = RemoteClient.request(host, port, "POST", "/pair/finish",
                    new JSONObject().put("tag", RemoteCrypto.tag(key, RemoteCrypto.ROLE_PHONE, pcFingerprint, myFingerprint)), pcFingerprint, 10_000);
                if (finish.status != 200) {
                    call.reject(pairingMessage(finish.body.optString("error")), finish.body.optString("error", "pairing_failed"));
                    return;
                }
                String pcName = finish.body.optString("name", host);
                RemoteDevices.save(getContext(), pcFingerprint, pcName, host, port);
                call.resolve(new JSObject().put("id", pcFingerprint).put("name", pcName));
            } catch (Exception e) {
                // Cancelling closes the waiting connection, which surfaces here as an exception.
                if (cancel.cancelled) call.reject("Linking was cancelled.", "cancelled");
                else call.reject("Couldn't reach the PC: " + e.getMessage(), "unreachable");
            }
        });
    }

    /** Stops a pairing that is waiting for its PIN: tells the PC (so it can take another try) and ends the wait here. */
    @PluginMethod
    public void cancelPairing(PluginCall call) {
        RemoteClient.Cancel current = pairing;
        String host = pairingHost;
        int port = pairingPort;
        if (current != null) current.cancel();
        if (current != null && host != null) {
            io.execute(() -> {
                try {
                    RemoteClient.request(host, port, "POST", "/pair/cancel", null, null, 3000);
                } catch (Exception ignored) {
                    // The PC may have gone; its own wait times out.
                }
            });
        }
        call.resolve();
    }

    private static String pairingMessage(String code) {
        switch (code) {
            case "too_many_attempts": return "Too many attempts.";
            case "cancelled": return "Linking was cancelled.";
            case "refused": return "The PC refused the link.";
            case "different_account": return "The LilyPad instance on that PC is logged in with another user.";
            case "lilypad_signed_out": return "The LilyPad instance on that PC is not logged in.";
            case "pairing_closed": return "On the PC, open LilyPad's Link Remote Device first, then try again.";
            case "already_in_progress": return "Another device is linking to that PC right now.";
            case "no_pin": return "The PIN wasn't entered on the PC in time. Try again.";
            case "disabled": return "Remote devices aren't allowed on that PC. Turn on \"Allow connections from remote devices\" in LilyPad.";
            default: return "Linking didn't work. Try again.";
        }
    }

    /** Unlinks: tells the PC to forget this app (if it can be reached), then forgets the PC. */
    @PluginMethod
    public void forget(PluginCall call) {
        RemoteDevices.use(getContext(), call.getString("account"));
        String id = call.getString("id");
        if (id == null) {
            call.reject("No PC given", "bad_request");
            return;
        }
        io.execute(() -> {
            try {
                JSONObject device = RemoteDevices.find(getContext(), id);
                if (device != null) {
                    RemoteClient.request(device.optString("host"), device.optInt("port", DEFAULT_PORT), "DELETE", "/pair", null, id, 3000);
                }
            } catch (Exception ignored) {
                // The PC may be off; the LilyPad user can still remove this device there.
            }
            RemoteDevices.forget(getContext(), id);
            call.resolve();
        });
    }

    /** Gives a linked PC a name of your own ({id, alias}); an empty alias goes back to the PC's own name. */
    @PluginMethod
    public void rename(PluginCall call) {
        RemoteDevices.use(getContext(), call.getString("account"));
        String id = call.getString("id");
        if (id == null || RemoteDevices.find(getContext(), id) == null) {
            call.reject("That PC isn't linked", "not_linked");
            return;
        }
        RemoteDevices.setAlias(getContext(), id, call.getString("alias", ""));
        call.resolve();
    }

    @PluginMethod
    public void info(PluginCall call) {
        RemoteDevices.use(getContext(), call.getString("account"));
        callDevice(call, "GET", "/info", null);
    }

    @PluginMethod
    public void launchable(PluginCall call) {
        RemoteDevices.use(getContext(), call.getString("account"));
        callDevice(call, "GET", "/launchable", null);
    }

    /** Starts a game on the PC: {id, gameType ("game" or "live_service"), gameId, steamLaunchId?, title?}. */
    @PluginMethod
    public void launch(PluginCall call) {
        RemoteDevices.use(getContext(), call.getString("account"));
        try {
            // The FrogLog account this app is in: a game id only means something in its own account's library, so LilyPad checks it is the same one.
            JSONObject body = new JSONObject()
                .put("account", call.getString("account", ""))
                .put("game_type", call.getString("gameType"))
                .put("game_id", call.getInt("gameId"));
            String steam = call.getString("steamLaunchId");
            if (steam != null && !steam.isEmpty()) body.put("steam_launch_id", steam);
            String title = call.getString("title");
            if (title != null) body.put("title", title);
            // Long enough for someone at the PC to answer "Ask me on this PC before launching a game".
            callDevice(call, "POST", "/launch", body, 90_000);
        } catch (Exception e) {
            call.reject("Bad request", "bad_request");
        }
    }

    /** Sends the request to the PC in call.id, finding it again on the network once if its address has changed. */
    private void callDevice(PluginCall call, String method, String path, JSONObject body) {
        callDevice(call, method, path, body, 12_000);
    }

    private void callDevice(PluginCall call, String method, String path, JSONObject body, int readTimeoutMs) {
        String id = call.getString("id");
        JSONObject device = id == null ? null : RemoteDevices.find(getContext(), id);
        if (device == null) {
            call.reject("That PC isn't linked", "not_linked");
            return;
        }
        io.execute(() -> {
            String host = device.optString("host");
            int port = device.optInt("port", DEFAULT_PORT);
            try {
                RemoteClient.Response response;
                try {
                    response = RemoteClient.request(host, port, method, path, body, id, readTimeoutMs);
                } catch (java.net.SocketTimeoutException silent) {
                    // Connected, but nothing came back in time (nobody answered a prompt on the PC): the PC is there, so it is not looked for again.
                    if (silent.getMessage() == null || !silent.getMessage().contains("Read timed out")) throw silent;
                    call.resolve(new JSObject().put("ok", false).put("status", 0).put("error", "no_answer"));
                    return;
                } catch (java.io.IOException unreachable) {
                    // It may have moved (a new address after a router restart): look for it once.
                    JSONObject moved = null;
                    JSONArray found = RemoteDiscovery.discover(getContext(), 2500);
                    for (int i = 0; i < found.length(); i++) {
                        JSONObject candidate = found.getJSONObject(i);
                        if (id.startsWith(candidate.optString("fp")) && !candidate.optString("fp").isEmpty()) moved = candidate;
                    }
                    if (moved == null) throw unreachable;
                    host = moved.optString("host");
                    port = moved.optInt("port", DEFAULT_PORT);
                    RemoteDevices.save(getContext(), id, device.optString("name"), host, port);
                    response = RemoteClient.request(host, port, method, path, body, id, readTimeoutMs);
                }
                JSObject result = toJs(response.body);
                result.put("status", response.status);
                result.put("ok", response.status == 200);
                // Removed on the PC: forget it here too, and say so, so the site can drop it from its list.
                if (isUnlinkedAnswer(response)) {
                    RemoteDevices.forget(getContext(), id);
                    result.put("unlinked", true);
                }
                call.resolve(result);
            } catch (Exception e) {
                call.reject("Couldn't reach " + RemoteDevices.shown(device).optString("name") + ": " + e.getMessage(), "unreachable");
            }
        });
    }
}
