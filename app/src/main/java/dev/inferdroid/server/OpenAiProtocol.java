package dev.inferdroid.server;

import dev.inferdroid.engine.ChatMessage;
import dev.inferdroid.engine.GenerationRequest;
import dev.inferdroid.engine.GenerationResult;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.io.StringReader;
import java.io.IOException;
import android.util.JsonReader;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** OpenAI JSON translation lives here rather than in the engine lifecycle. */
final class OpenAiProtocol {
    static final String MODEL = "gemma-4-E2B-it_Google_Tensor_G5";
    private static final Set<String> PARAMETERS = Set.of("model", "messages", "stream", "stream_options",
            "max_tokens", "max_completion_tokens", "temperature", "top_p", "seed", "n", "user",
            "tools", "tool_choice", "response_format", "frequency_penalty", "presence_penalty", "logprobs");

    static final class ApiError extends Exception {
        final int status;
        final String type;
        final String code;
        final String param;

        ApiError(int status, String message, String type, String code, String param) {
            super(message);
            this.status = status;
            this.type = type;
            this.code = code;
            this.param = param;
        }

        JSONObject json() throws JSONException {
            return new JSONObject().put("error", new JSONObject().put("message", getMessage())
                    .put("type", type).put("code", code).put("param", param == null ? JSONObject.NULL : param));
        }
    }

    static ApiError invalid(String message, String param) {
        return new ApiError(400, message, "invalid_request_error", "invalid_parameter", param);
    }

    static final class ChatRequest {
        final GenerationRequest generation;
        final boolean stream;
        final boolean includeUsage;

        ChatRequest(GenerationRequest generation, boolean stream, boolean includeUsage) {
            this.generation = generation;
            this.stream = stream;
            this.includeUsage = includeUsage;
        }
    }

    static ChatRequest parse(String body, String modelSource, boolean verbose) throws ApiError {
        try {
            validateJson(body);
            JSONTokener tokener = new JSONTokener(body);
            Object value = tokener.nextValue();
            if (!(value instanceof JSONObject) || tokener.nextClean() != 0) throw invalid("Expected one JSON object.", null);
            JSONObject json = (JSONObject) value;
            keys(json, PARAMETERS, "");
            if (!MODEL.equals(string(json, "model"))) {
                throw new ApiError(404, "Unknown model. Use GET /v1/models to find the local model ID.",
                        "invalid_request_error", "model_not_found", "model");
            }
            Object input = json.opt("messages");
            if (!(input instanceof JSONArray)) throw invalid("messages must be a non-empty array.", "messages");
            JSONArray turns = (JSONArray) input;
            if (turns.length() < 1 || turns.length() > 128) throw invalid("Supply 1–128 text messages.", "messages");
            List<ChatMessage> messages = new ArrayList<>();
            boolean conversationStarted = false;
            for (int i = 0; i < turns.length(); i++) {
                String param = "messages[" + i + "]";
                Object turn = turns.get(i);
                if (!(turn instanceof JSONObject)) throw invalid("Each message must be an object.", param);
                JSONObject message = (JSONObject) turn;
                keys(message, Set.of("role", "content", "name"), param + ".");
                String role = string(message, "role");
                if (role.equals("developer")) role = "system";
                if (!Set.of("system", "user", "assistant").contains(role)) {
                    throw invalid("Only system, developer, user, and assistant text messages are supported.", param + ".role");
                }
                if (role.equals("system") && conversationStarted) {
                    throw invalid("System/developer instructions must precede conversation turns.", param + ".role");
                }
                if (!role.equals("system")) conversationStarted = true;
                if (message.has("name") && !message.isNull("name")) string(message, "name");
                Object content = message.opt("content");
                String text;
                if (content instanceof String) text = (String) content;
                else if (content instanceof JSONArray) {
                    StringBuilder joined = new StringBuilder();
                    JSONArray parts = (JSONArray) content;
                    for (int part = 0; part < parts.length(); part++) {
                        JSONObject item = parts.optJSONObject(part);
                        if (item == null || !"text".equals(item.opt("type"))) {
                            throw invalid("This milestone supports text content only.", param + ".content");
                        }
                        keys(item, Set.of("type", "text"), param + ".content.");
                        joined.append(string(item, "text"));
                    }
                    text = joined.toString();
                } else throw invalid("content must be a string or an array of text parts.", param + ".content");
                if (text.trim().isEmpty() || text.indexOf('\0') >= 0) {
                    throw invalid("Message text must be non-empty and contain no null characters.", param + ".content");
                }
                messages.add(new ChatMessage(role, text));
            }
            if (!messages.get(messages.size() - 1).role.equals("user")) {
                throw invalid("The last message must be a user turn.", "messages");
            }
            if (optional(json, "n")) integer(json, "n", 1, 1);
            if (optional(json, "max_tokens") && optional(json, "max_completion_tokens")) {
                throw invalid("Use either max_tokens or max_completion_tokens.", "max_completion_tokens");
            }
            int maxTokens = optional(json, "max_completion_tokens")
                    ? integer(json, "max_completion_tokens", 1, 1024)
                    : optional(json, "max_tokens") ? integer(json, "max_tokens", 1, 1024) : 256;
            Double temperature = optional(json, "temperature") ? number(json, "temperature", 0, 2) : null;
            Double topP = optional(json, "top_p") ? number(json, "top_p", Double.MIN_VALUE, 1) : null;
            Integer seed = optional(json, "seed") ? integer(json, "seed", Integer.MIN_VALUE, Integer.MAX_VALUE) : null;
            boolean stream = bool(json, "stream", false);
            boolean includeUsage = false;
            if (optional(json, "stream_options")) {
                JSONObject options = json.optJSONObject("stream_options");
                if (!stream || options == null) throw invalid("stream_options requires stream=true and an object.", "stream_options");
                keys(options, Set.of("include_usage"), "stream_options.");
                includeUsage = bool(options, "include_usage", false);
            }
            if (optional(json, "user")) string(json, "user"); // Client metadata; never logged or sent to the model.
            for (String penalty : List.of("frequency_penalty", "presence_penalty")) {
                if (optional(json, penalty)) number(json, penalty, 0, 0);
            }
            if (bool(json, "logprobs", false)) throw invalid("Log probabilities are unsupported.", "logprobs");
            if (optional(json, "tools") && (!(json.opt("tools") instanceof JSONArray)
                    || json.getJSONArray("tools").length() != 0)) throw invalid("Tool calling is future work.", "tools");
            if (optional(json, "tool_choice") && !"none".equals(json.opt("tool_choice"))) throw invalid("Only tool_choice=none is supported.", "tool_choice");
            if (optional(json, "response_format")) {
                JSONObject format = json.optJSONObject("response_format");
                if (format == null || !"text".equals(format.opt("type")) || format.length() != 1) {
                    throw invalid("Only response_format={\"type\":\"text\"} is supported.", "response_format");
                }
            }
            return new ChatRequest(new GenerationRequest(modelSource, messages, verbose, maxTokens, temperature, topP, seed), stream, includeUsage);
        } catch (JSONException error) {
            throw new ApiError(400, "Malformed JSON or invalid field type.", "invalid_request_error", "invalid_json", null);
        }
    }

    // Android's JSONObject parser accepts JavaScript-like syntax. Validate strict
    // JSON first, with an explicit depth bound and duplicate-name rejection.
    static void validateJson(String text) throws ApiError {
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            ArrayDeque<Set<String>> objects = new ArrayDeque<>();
            int depth = 0;
            boolean finished = false;
            while (!finished) {
                switch (reader.peek()) {
                    case BEGIN_OBJECT -> {
                        if (++depth > 32) throw invalid("JSON nesting exceeds 32 levels.", null);
                        reader.beginObject(); objects.push(new HashSet<>());
                    }
                    case END_OBJECT -> { reader.endObject(); objects.pop(); depth--; }
                    case BEGIN_ARRAY -> {
                        if (++depth > 32) throw invalid("JSON nesting exceeds 32 levels.", null);
                        reader.beginArray();
                    }
                    case END_ARRAY -> { reader.endArray(); depth--; }
                    case NAME -> {
                        if (!objects.peek().add(reader.nextName())) throw invalid("Duplicate JSON field.", null);
                    }
                    case STRING, NUMBER -> reader.nextString();
                    case BOOLEAN -> reader.nextBoolean();
                    case NULL -> reader.nextNull();
                    case END_DOCUMENT -> finished = true;
                }
            }
        } catch (IOException | IllegalStateException error) {
            throw new ApiError(400, "Malformed JSON.", "invalid_request_error", "invalid_json", null);
        }
    }

    private static boolean optional(JSONObject json, String key) { return json.has(key) && !json.isNull(key); }

    private static void keys(JSONObject json, Set<String> allowed, String prefix) throws ApiError {
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!allowed.contains(key)) throw invalid("Unsupported field: " + prefix + key, prefix + key);
        }
    }

    private static String string(JSONObject json, String key) throws ApiError {
        Object value = json.opt(key);
        if (!(value instanceof String)) throw invalid(key + " must be a string.", key);
        return (String) value;
    }

    private static double number(JSONObject json, String key, double min, double max) throws ApiError {
        Object value = json.opt(key);
        if (!(value instanceof Number)) throw invalid(key + " must be a number.", key);
        double result = ((Number) value).doubleValue();
        if (!Double.isFinite(result) || result < min || result > max) throw invalid(key + " is outside the supported range [" + min + ", " + max + "].", key);
        if ((key.equals("temperature") || key.equals("top_p")) && result > 0 && (float) result == 0) {
            throw invalid(key + " is too small for the native sampler.", key);
        }
        return result;
    }

    private static int integer(JSONObject json, String key, int min, int max) throws ApiError {
        double value = number(json, key, min, max);
        if (Math.rint(value) != value) throw invalid(key + " must be an integer.", key);
        return (int) value;
    }

    private static boolean bool(JSONObject json, String key, boolean fallback) throws ApiError {
        if (!optional(json, key)) return fallback;
        Object value = json.opt(key);
        if (!(value instanceof Boolean)) throw invalid(key + " must be a boolean.", key);
        return (Boolean) value;
    }

    static JSONObject models() throws JSONException {
        return models(true, false);
    }
    static JSONObject models(boolean chat, boolean speech) throws JSONException {
        return models(chat, speech, false);
    }
    static JSONObject models(boolean chat, boolean speech, boolean tts) throws JSONException {
        JSONArray models = new JSONArray();
        if (chat) models.put(model(MODEL));
        if (speech) models.put(model(dev.inferdroid.speech.SpeechModelStore.MODEL_ID));
        if (tts) models.put(model(dev.inferdroid.tts.TtsModelStore.MODEL_ID));
        return new JSONObject().put("object", "list").put("data", models);
    }
    private static JSONObject model(String id) throws JSONException {
        return new JSONObject().put("id", id).put("object", "model").put("created", 0).put("owned_by", "inferdroid");
    }

    private static JSONObject base(String id, long created, boolean stream) throws JSONException {
        return new JSONObject().put("id", id).put("created", created).put("model", MODEL)
                .put("object", stream ? "chat.completion.chunk" : "chat.completion");
    }

    static Object usage(GenerationResult result) throws JSONException {
        if (result.promptTokens < 0 || result.completionTokens < 0) return JSONObject.NULL;
        return new JSONObject().put("prompt_tokens", result.promptTokens).put("completion_tokens", result.completionTokens)
                .put("total_tokens", result.promptTokens + result.completionTokens);
    }

    static JSONObject completion(String id, long created, GenerationResult result) throws JSONException {
        JSONObject response = base(id, created, false).put("choices", new JSONArray().put(new JSONObject().put("index", 0)
                .put("message", new JSONObject().put("role", "assistant").put("content", result.text))
                .put("finish_reason", result.limitReached ? "length" : "stop").put("logprobs", JSONObject.NULL)));
        if (usage(result) != JSONObject.NULL) response.put("usage", usage(result));
        return response;
    }

    static JSONObject chunk(String id, long created, JSONObject delta, String finishReason, boolean includeUsage) throws JSONException {
        JSONObject chunk = base(id, created, true).put("choices", new JSONArray().put(new JSONObject().put("index", 0)
                .put("delta", delta).put("finish_reason", finishReason == null ? JSONObject.NULL : finishReason)
                .put("logprobs", JSONObject.NULL)));
        if (includeUsage) chunk.put("usage", JSONObject.NULL);
        return chunk;
    }

    static JSONObject usageChunk(String id, long created, GenerationResult result) throws JSONException {
        return base(id, created, true).put("choices", new JSONArray()).put("usage", usage(result));
    }
}
