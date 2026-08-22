package io.floci.az.core.arm;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ARM CloudError responses — {@code {"error":{"code":"...","message":"..."}}}.
 * The management-plane counterpart of {@code core/AzureErrorResponse} (which covers
 * the storage data-plane XML/JSON shape).
 */
public final class ArmErrors {

    private ArmErrors() {
    }

    public static Response error(int status, String code, String message) {
        return error(status, code, message, null);
    }

    /**
     * CloudError carrying a {@code target} — the JSON path or parameter name the error is
     * about. {@code target} is omitted from the body when it is null or blank, so this is a
     * drop-in for the three-argument form.
     */
    public static Response error(int status, String code, String message, String target) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        if (target != null && !target.isBlank()) {
            body.put("target", target);
        }
        return Response.status(status)
                .entity(Map.of("error", body))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    public static Response notFound(String message) {
        return error(404, "ResourceNotFound", message);
    }
}
