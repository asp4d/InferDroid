package dev.inferdroid.server;

import dev.inferdroid.server.OpenAiProtocol.ApiError;
import dev.inferdroid.tts.SynthesisRequest;
import dev.inferdroid.tts.SynthesisResult;
import dev.inferdroid.tts.TtsModelStore;
import dev.inferdroid.tts.TtsVoices;
import java.util.Iterator;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** OpenAI-compatible subset. WAV is the deliberate local default. */
final class TtsProtocol {
    static final class Request {
        final SynthesisRequest synthesis;
        final String format;
        Request(SynthesisRequest synthesis, String format) { this.synthesis = synthesis; this.format = format; }
    }
    static Request parse(String text) throws ApiError {
        OpenAiProtocol.validateJson(text);
        try {
            Object root = new JSONTokener(text).nextValue();
            if (!(root instanceof JSONObject json)) throw OpenAiProtocol.invalid("Supply a JSON object.", null);
            Set<String> allowed = Set.of("model", "input", "voice", "response_format", "speed", "language", "instructions", "stream_format", "stream");
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!allowed.contains(key)) throw OpenAiProtocol.invalid("Unsupported speech parameter.", key);
            }
            String model = string(json, "model", null);
            if (!model.equals(TtsModelStore.MODEL_ID) && !model.equals("tts-1"))
                throw new ApiError(404, "Unknown TTS model. Use sherpa-onnx-supertonic-3-int8 from GET /v1/models.", "invalid_request_error", "model_not_found", "model");
            String input = string(json, "input", null);
            if (input.length() > SynthesisRequest.MAX_TEXT) throw new ApiError(413, "TTS input exceeds 4096 characters.", "invalid_request_error", "request_too_large", "input");
            String voice = string(json, "voice", null);
            int speaker = TtsVoices.speaker(voice);
            if (speaker < 0) throw OpenAiProtocol.invalid("Use F1–F5, M1–M5, or a documented compatibility voice alias.", "voice");
            String language = string(json, "language", "en");
            if (!TtsVoices.supportsLanguage(language)) throw OpenAiProtocol.invalid("Use a supported TTS language code such as en or it.", "language");
            String format = string(json, "response_format", "wav");
            if (!format.equals("wav") && !format.equals("pcm")) throw OpenAiProtocol.invalid("Supported TTS output formats: wav or pcm (24 kHz mono PCM16).", "response_format");
            float speed = 1;
            if (json.has("speed")) {
                Object value = json.get("speed");
                if (!(value instanceof Number)) throw OpenAiProtocol.invalid("speed must be a number between 0.25 and 2.0.", "speed");
                double n = ((Number) value).doubleValue();
                if (!Double.isFinite(n) || n < 0.25 || n > 2) throw OpenAiProtocol.invalid("speed must be between 0.25 and 2.0.", "speed");
                speed = (float) n;
            }
            if (!string(json, "instructions", "").isEmpty()) throw OpenAiProtocol.invalid("Voice instructions are unsupported by this TTS backend.", "instructions");
            if (!string(json, "stream_format", "audio").equals("audio")) throw OpenAiProtocol.invalid("Use stream_format=audio; speech SSE is unsupported.", "stream_format");
            if (json.has("stream") && !Boolean.FALSE.equals(json.get("stream"))) throw OpenAiProtocol.invalid("Use stream=false or omit it; TTS is returned after generation.", "stream");
            try { return new Request(new SynthesisRequest(input, language, speaker, speed), format); }
            catch (IllegalArgumentException error) { throw OpenAiProtocol.invalid(error.getMessage(), "input"); }
        } catch (JSONException error) { throw OpenAiProtocol.invalid("Malformed speech JSON.", null); }
    }
    private static String string(JSONObject json, String key, String fallback) throws ApiError {
        if (!json.has(key) && fallback != null) return fallback;
        Object value = json.opt(key);
        if (!(value instanceof String)) throw OpenAiProtocol.invalid("Supply " + key + " as a string.", key);
        return (String) value;
    }
    static ApiError unavailable() { return new ApiError(503, "Import the Supertonic 3 TTS model folder in InferDroid first.", "server_error", "tts_model_unavailable", "model"); }
    static ApiError failure(SynthesisResult result) {
        return switch (result.failure) {
            case MODEL_UNAVAILABLE -> unavailable();
            case AUDIO_TOO_LARGE -> new ApiError(413, result.diagnostics, "invalid_request_error", "request_too_large", "input");
            case CANCELLED -> new ApiError(500, "TTS request cancelled.", "server_error", "request_cancelled", null);
            default -> new ApiError(500, "Local CPU speech synthesis failed. Check InferDroid TTS status.", "server_error", "synthesis_failed", null);
        };
    }
}
