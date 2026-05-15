/**
 * Frida script to dump the RSA private key from the Android Auto app.
 * 
 * Usage:
 *   1. Install frida-server on the phone (needs root or debuggable app)
 *   2. Start Android Auto app (connect to head unit or DHU)
 *   3. Run: frida -U -n "com.google.android.projection.gearhead" -l dump_key_frida.js
 *
 * Alternative (no root): Use frida-gadget injected into a repackaged APK.
 *
 * This hooks KeyFactory.generatePrivate() and dumps the PKCS8 key bytes.
 */

Java.perform(function() {
    console.log("[*] Hooking KeyFactory.generatePrivate()...");

    var KeyFactory = Java.use("java.security.KeyFactory");
    KeyFactory.generatePrivate.implementation = function(keySpec) {
        console.log("[*] KeyFactory.generatePrivate() called!");
        
        var PKCS8 = Java.use("java.security.spec.PKCS8EncodedKeySpec");
        if (PKCS8.class.isInstance(keySpec)) {
            var encoded = Java.cast(keySpec, PKCS8).getEncoded();
            var len = encoded.length;
            console.log("[*] PKCS8EncodedKeySpec: " + len + " bytes");
            
            // Convert to hex
            var hex = "";
            for (var i = 0; i < len; i++) {
                var b = (encoded[i] & 0xFF).toString(16);
                hex += (b.length < 2 ? "0" : "") + b;
            }
            console.log("[KEY_HEX_START]");
            console.log(hex);
            console.log("[KEY_HEX_END]");
            
            // Also convert to base64 for easy PEM creation
            var Base64 = Java.use("android.util.Base64");
            var b64 = Base64.encodeToString(encoded, 0);
            console.log("[KEY_B64_START]");
            console.log(b64);
            console.log("[KEY_B64_END]");
        }
        
        return this.generatePrivate(keySpec);
    };

    // Also hook the SSLContext.init to catch the cert chain
    var SSLContext = Java.use("javax.net.ssl.SSLContext");
    SSLContext.init.overload('[Ljavax.net.ssl.KeyManager;', '[Ljavax.net.ssl.TrustManager;', 'java.security.SecureRandom').implementation = function(km, tm, sr) {
        console.log("[*] SSLContext.init() called with " + (km ? km.length : 0) + " KeyManagers");
        if (km && km.length > 0) {
            var X509KM = Java.use("javax.net.ssl.X509KeyManager");
            try {
                var xkm = Java.cast(km[0], X509KM);
                var chain = xkm.getCertificateChain("com.google.android.gms.car");
                if (chain) {
                    console.log("[*] Certificate chain length: " + chain.length);
                    for (var i = 0; i < chain.length; i++) {
                        var encoded = chain[i].getEncoded();
                        var Base64 = Java.use("android.util.Base64");
                        var b64 = Base64.encodeToString(encoded, 0);
                        console.log("[CERT_" + i + "_START]");
                        console.log(b64);
                        console.log("[CERT_" + i + "_END]");
                    }
                }
            } catch(e) {
                console.log("[*] Could not extract cert chain: " + e);
            }
        }
        return this.init(km, tm, sr);
    };

    console.log("[*] Hooks installed. Waiting for TLS initialization...");
});
