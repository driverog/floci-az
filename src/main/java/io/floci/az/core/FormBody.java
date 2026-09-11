package io.floci.az.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Parses {@code application/x-www-form-urlencoded} request bodies. OAuth-style endpoints
 * (Entra's token endpoint, the ACR token exchange) are the only form-encoded surfaces in the
 * emulator, and both read the body as a flat map of decoded parameters.
 */
public final class FormBody {

    private FormBody() {
    }

    /** Decoded form parameters; an unreadable or empty body yields an empty map. */
    public static Map<String, String> parse(InputStream body) {
        Map<String, String> result = new HashMap<>();
        byte[] bytes;
        try {
            bytes = body == null ? new byte[0] : body.readAllBytes();
        } catch (IOException e) {
            return result;
        }
        String content = new String(bytes, StandardCharsets.UTF_8);
        if (content.isBlank()) {
            return result;
        }
        for (String pair : content.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            result.put(key, value);
        }
        return result;
    }
}
