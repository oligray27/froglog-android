package uk.co.froglog.app.remote;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The pairing proof, matching LilyPad's lilypad-core lan/pairing.rs exactly (its test holds the same known answers).
 *
 * The app makes a four digit PIN and a salt and shows the PIN. LilyPad's user types it in, and each side sends an HMAC over
 * (role, LilyPad's certificate fingerprint, this app's certificate fingerprint). A tag only verifies if the PIN is right and both
 * sides saw the same two certificates, so someone in the middle presenting their own certificate fails it.
 */
final class RemoteCrypto {
    static final String ROLE_PC = "pc";
    static final String ROLE_PHONE = "phone";
    private static final int KEY_ROUNDS = 20_000;
    private static final byte[] CONTEXT = "froglog-lilypad-pair-v1".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();

    private RemoteCrypto() {}

    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format("%02x", b));
        return out.toString();
    }

    /** SHA-256 of a certificate's DER, lowercase hex. */
    static String fingerprint(byte[] der) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(der));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String newPin() {
        return String.format("%04d", RANDOM.nextInt(10_000));
    }

    static byte[] newSalt() {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return salt;
    }

    static byte[] deriveKey(byte[] salt, String pin) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(CONTEXT);
            sha.update(salt);
            sha.update(pin.getBytes(StandardCharsets.UTF_8));
            byte[] key = sha.digest();
            for (int i = 0; i < KEY_ROUNDS; i++) {
                sha.reset();
                sha.update(key);
                sha.update(salt);
                key = sha.digest();
            }
            return key;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String tag(byte[] key, String role, String pcFingerprint, String phoneFingerprint) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(role.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '|');
            mac.update(pcFingerprint.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '|');
            mac.update(phoneFingerprint.getBytes(StandardCharsets.UTF_8));
            return hex(mac.doFinal());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Compared in constant time. */
    static boolean verify(byte[] key, String role, String pcFingerprint, String phoneFingerprint, String received) {
        byte[] expected = tag(key, role, pcFingerprint, phoneFingerprint).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, received.toLowerCase().getBytes(StandardCharsets.UTF_8));
    }
}
