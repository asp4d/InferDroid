package dev.inferdroid.server;

import java.security.SecureRandom;

/** The bind address is deliberately fixed; LAN serving is outside this milestone. */
public final class ServerConfig {
    public static final String HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 8080;
    public final int port;
    public final String apiKey;
    public final boolean requireKey;
    public final boolean cors;

    public ServerConfig(int port, String apiKey, boolean requireKey, boolean cors) {
        // Port zero is useful for isolated instrumentation tests, never offered in the UI.
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Port must be between 1 and 65535.");
        if (requireKey && (apiKey == null || apiKey.isEmpty() || apiKey.length() > 256
                || !apiKey.matches("[!-~]+"))) {
            throw new IllegalArgumentException("An API key must contain 1–256 printable ASCII characters without spaces.");
        }
        this.port = port;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.requireKey = requireKey;
        this.cors = cors;
    }

    public static String generateKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder key = new StringBuilder("local-");
        for (byte value : bytes) key.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return key.toString();
    }
}
