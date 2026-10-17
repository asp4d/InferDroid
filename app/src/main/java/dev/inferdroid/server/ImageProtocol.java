package dev.inferdroid.server;

import android.util.Base64;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Set;
import dev.inferdroid.images.ImageController;
import dev.inferdroid.images.ImageModels;
import dev.inferdroid.server.OpenAiProtocol.ApiError;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** OpenAI Images generation subset for the fixed local SDXS profile. */
final class ImageProtocol {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> PARAMETERS = Set.of("model", "prompt", "n", "size", "response_format",
            "seed", "user", "quality", "output_format", "background", "stream");

    static final class Request {
        final String prompt;
        final long seed;
        Request(String prompt, long seed) { this.prompt = prompt; this.seed = seed; }
    }

    static Request parse(String body) throws ApiError {
        OpenAiProtocol.validateJson(body);
        try {
            Object root = new JSONTokener(body).nextValue();
            if (!(root instanceof JSONObject json)) throw invalid("Supply a JSON object.", null);
            OpenAiProtocol.keys(json, PARAMETERS, "");
            if (OpenAiProtocol.optional(json, "model") && !ImageModels.MODEL_ID.equals(OpenAiProtocol.string(json, "model"))) {
                throw new ApiError(404, "Unknown local image model. Use " + ImageModels.MODEL_ID + " from GET /v1/models.",
                        "invalid_request_error", "model_not_found", "model");
            }
            String prompt = OpenAiProtocol.string(json, "prompt");
            if (prompt.isBlank() || prompt.length() > 8192 || prompt.indexOf('\0') >= 0) {
                throw invalid("prompt must contain 1–8192 characters without null characters.", "prompt");
            }
            if (OpenAiProtocol.optional(json, "n") && (!(json.opt("n") instanceof Number)
                    || ((Number) json.opt("n")).doubleValue() != 1)) {
                throw invalid("This model generates one image per request; use n=1.", "n");
            }
            option(json, "size", Set.of("512x512", "auto"), "This compiled model supports size=512x512 or auto.");
            option(json, "response_format", Set.of("b64_json"), "Use response_format=b64_json.");
            option(json, "quality", Set.of("standard", "auto"), "This model supports quality=standard or auto.");
            option(json, "output_format", Set.of("png"), "This model supports output_format=png.");
            option(json, "background", Set.of("opaque", "auto"), "This model generates opaque backgrounds; use background=opaque or auto.");
            if (OpenAiProtocol.optional(json, "stream") && !Boolean.FALSE.equals(json.opt("stream"))) {
                throw invalid("Use stream=false or omit it; the PNG is returned after generation.", "stream");
            }
            if (OpenAiProtocol.optional(json, "user")) OpenAiProtocol.string(json, "user");
            long seed = RANDOM.nextLong();
            if (OpenAiProtocol.optional(json, "seed")) {
                Object value = json.opt("seed");
                if (!(value instanceof Number) || !value.toString().matches("-?[0-9]+")) {
                    throw invalid("seed must be a signed 64-bit integer.", "seed");
                }
                try { seed = Long.parseLong(value.toString()); }
                catch (NumberFormatException error) { throw invalid("seed must be a signed 64-bit integer.", "seed"); }
            }
            return new Request(prompt, seed);
        } catch (JSONException error) {
            throw invalid("Malformed image JSON.", null);
        }
    }

    private static void option(JSONObject json, String key, Set<String> values, String message) throws ApiError {
        if (OpenAiProtocol.optional(json, key) && !values.contains(json.opt(key))) throw invalid(message, key);
    }

    static JSONObject models(JSONObject response, boolean available) throws JSONException {
        if (available) response.getJSONArray("data").put(new JSONObject().put("id", ImageModels.MODEL_ID).put("object", "model")
                .put("created", 0).put("owned_by", "inferdroid").put("architecture", new JSONObject()
                        .put("input_modalities", new JSONArray().put("text")).put("output_modalities", new JSONArray().put("image"))));
        return response;
    }

    static JSONObject result(ImageController.Result result) throws Exception {
        if (result.error != null || result.image == null) throw new ApiError(503,
                result.error == null ? "No image was generated." : result.error,
                "server_error", result.cancelled ? "image_cancelled" : "image_generation_failed", null);
        byte[] png = Files.readAllBytes(result.image.toPath());
        return new JSONObject().put("created", System.currentTimeMillis() / 1000)
                .put("data", new JSONArray().put(new JSONObject().put("b64_json", Base64.encodeToString(png, Base64.NO_WRAP))));
    }

    private static ApiError invalid(String message, String param) { return OpenAiProtocol.invalid(message, param); }
}
