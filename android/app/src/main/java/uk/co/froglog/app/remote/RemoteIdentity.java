package uk.co.froglog.app.remote;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Date;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.security.auth.x500.X500Principal;

/**
 * This app's own certificate for talking to LilyPad: a self-signed one on an EC key that lives in the Android Keystore (the key
 * itself never leaves it), made on first use. LilyPad pins its fingerprint when pairing, so there is nothing for an authority to
 * vouch for. Clearing the app's data (or a new install) makes a new one, and the PCs then need linking again.
 */
final class RemoteIdentity {
    private static final String ALIAS = "froglog_remote_client";
    private static final String PROVIDER = "AndroidKeyStore";

    private RemoteIdentity() {}

    private static synchronized KeyStore store() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(PROVIDER);
        keyStore.load(null);
        if (!keyStore.containsAlias(ALIAS)) {
            long now = System.currentTimeMillis();
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER);
            generator.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                .setCertificateSubject(new X500Principal("CN=FrogLog"))
                .setCertificateNotBefore(new Date(now - 24L * 3600 * 1000))
                .setCertificateNotAfter(new Date(now + 20L * 365 * 24 * 3600 * 1000))
                .build());
            generator.generateKeyPair();
        }
        return keyStore;
    }

    /** The certificate's SHA-256 fingerprint, as LilyPad sees it. */
    static String fingerprint() throws Exception {
        Certificate certificate = store().getCertificate(ALIAS);
        return RemoteCrypto.fingerprint(certificate.getEncoded());
    }

    /** Key managers that present this certificate when a server asks for one. */
    static KeyManager[] keyManagers() throws Exception {
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(store(), null);
        return factory.getKeyManagers();
    }
}
