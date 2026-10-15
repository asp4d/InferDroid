package dev.inferdroid.server;

import dev.inferdroid.engine.ChatMessage;
import dev.inferdroid.engine.ChatPart;
import dev.inferdroid.engine.ImageInput;
import dev.inferdroid.engine.GenerationRequest;
import dev.inferdroid.engine.GenerationResult;
import dev.inferdroid.engine.ToolCall;
import dev.inferdroid.engine.ToolDefinition;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
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
            "tools", "tool_choice", "response_format", "frequency_penalty", "presence_penalty", "logprobs",
            "enable_thinking", "reasoning_effort", "reasoning", "prompt_cache_key");

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
        final boolean jsonObject;

        ChatRequest(GenerationRequest generation, boolean stream, boolean includeUsage, boolean jsonObject) {
            this.generation = generation;
            this.stream = stream;
            this.includeUsage = includeUsage;
            this.jsonObject = jsonObject;
        }

        GenerationRequest jsonRepair(GenerationResult result) {
            if (!jsonObject || !result.success || result.limitReached || !result.toolCalls.isEmpty()
                    || generation.allowToolCalls || result.completionTokens < 0
                    || result.completionTokens >= generation.maxOutputTokens || isJsonObject(result.text)) return null;
            List<ChatMessage> messages = new ArrayList<>(generation.messages);
            messages.add(new ChatMessage("assistant", result.text));
            messages.add(new ChatMessage("user", "The previous response was not valid JSON. Return the same object "
                    + "with only its JSON syntax corrected. Preserve the field names and values, balance all braces "
                    + "and brackets, and return no Markdown or commentary."));
            return new GenerationRequest(generation.modelSource, messages, generation.verbose,
                    generation.maxOutputTokens - result.completionTokens, generation.temperature,
                    generation.topP, generation.seed, generation.enableThinking, generation.tools, false);
        }
    }

    static ChatRequest parse(String body, String modelSource, boolean verbose) throws ApiError {
        return parse(body, modelSource, verbose, false);
    }

    static ChatRequest parse(String body, String modelSource, boolean verbose, boolean defaultThinking) throws ApiError {
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
            if (turns.length() < 1 || turns.length() > 128) throw invalid("Supply 1–128 messages.", "messages");
            List<ChatMessage> messages = new ArrayList<>();
            Map<String, ToolCall> pendingCalls = new LinkedHashMap<>();
            Set<String> seenCallIds = new HashSet<>();
            int imageCount = 0;
            int imageBytes = 0;
            int textBytes = 0;
            boolean conversationStarted = false;
            for (int i = 0; i < turns.length(); i++) {
                String param = "messages[" + i + "]";
                Object turn = turns.get(i);
                if (!(turn instanceof JSONObject)) throw invalid("Each message must be an object.", param);
                JSONObject message = (JSONObject) turn;
                keys(message, Set.of("role", "content", "name", "reasoning_content", "tool_calls", "tool_call_id"), param + ".");
                String role = string(message, "role");
                if (role.equals("developer")) role = "system";
                if (!Set.of("system", "user", "assistant", "tool").contains(role)) {
                    throw invalid("Supported roles: system, developer, user, assistant, and tool.", param + ".role");
                }
                if (role.equals("system") && conversationStarted) {
                    throw invalid("System/developer instructions must precede conversation turns.", param + ".role");
                }
                if (!role.equals("system")) conversationStarted = true;
                if (message.has("name") && !message.isNull("name")) string(message, "name");
                if (!role.equals("assistant") && optional(message, "tool_calls")) throw invalid("tool_calls is only supported on assistant history.", param + ".tool_calls");
                if (!role.equals("tool") && optional(message, "tool_call_id")) throw invalid("tool_call_id is only supported on tool results.", param + ".tool_call_id");
                if (!role.equals("tool") && !pendingCalls.isEmpty()) throw invalid("Supply one result for every pending tool call before the next conversation turn.", param + ".role");
                List<ToolCall> calls = OpenAiTools.historyCalls(message, param + ".tool_calls");
                for (ToolCall call : calls) {
                    if (!seenCallIds.add(call.id)) throw invalid("Tool call IDs must be unique across the conversation.", param + ".tool_calls");
                    pendingCalls.put(call.id, call);
                    textBytes += (call.name + call.id + call.argumentsJson).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                }
                String toolCallId = null;
                String toolName = null;
                if (role.equals("tool")) {
                    toolCallId = OpenAiTools.id(string(message, "tool_call_id"), param + ".tool_call_id");
                    ToolCall pending = pendingCalls.remove(toolCallId);
                    if (pending == null) throw invalid("tool_call_id must match an outstanding assistant function call exactly once.", param + ".tool_call_id");
                    toolName = pending.name;
                    if (optional(message, "name") && !toolName.equals(message.opt("name"))) throw invalid("Tool result name does not match its function call.", param + ".name");
                }
                // Accept a returned assistant message unchanged, but never prefill its thoughts.
                if (message.has("reasoning_content")) {
                    if (!role.equals("assistant")) throw invalid("reasoning_content is only supported on assistant history.", param + ".reasoning_content");
                    if (!message.isNull("reasoning_content") && !(message.opt("reasoning_content") instanceof String)) {
                        throw invalid("reasoning_content must be a string or null.", param + ".reasoning_content");
                    }
                }
                Object content = message.opt("content");
                List<ChatPart> contents = new ArrayList<>();
                if ((content == null || content == JSONObject.NULL) && !calls.isEmpty()) { /* Tool-only assistant turn. */ }
                else if (content instanceof String) contents.add(ChatPart.text((String) content));
                else if (content instanceof JSONArray) {
                    JSONArray parts = (JSONArray) content;
                    if (parts.length() < 1 || parts.length() > 256) throw invalid("Supply 1–256 content parts per message.", param + ".content");
                    for (int part = 0; part < parts.length(); part++) {
                        String partParam = param + ".content[" + part + "]";
                        JSONObject item = parts.optJSONObject(part);
                        if (item == null) throw invalid("Each content part must be an object.", partParam);
                        if ("text".equals(item.opt("type"))) {
                            keys(item, Set.of("type", "text"), partParam + ".");
                            contents.add(ChatPart.text(string(item, "text")));
                        } else if ("image_url".equals(item.opt("type"))) {
                            if (!role.equals("user")) throw invalid("Image input is supported in user messages only.", partParam);
                            keys(item, Set.of("type", "image_url"), partParam + ".");
                            JSONObject image = item.optJSONObject("image_url");
                            if (image == null) throw invalid("image_url must contain an object with a url string.", partParam + ".image_url");
                            keys(image, Set.of("url", "detail"), partParam + ".image_url.");
                            if (image.has("detail") && !"auto".equals(image.opt("detail"))) {
                                throw invalid("Only detail=auto (or omitted) is supported by this local vision configuration.", partParam + ".image_url.detail");
                            }
                            if (++imageCount > ImageInput.MAX_IMAGES) throw imageLimit("Supply at most four images across the conversation.", partParam);
                            try {
                                ImageInput decoded = ImageInput.fromDataUrl(string(image, "url"));
                                imageBytes += decoded.sourceBytes;
                                if (imageBytes > ImageInput.MAX_TOTAL_BYTES) throw imageLimit("Images must total at most 4 MiB before base64 encoding.", partParam);
                                contents.add(ChatPart.image(decoded));
                            } catch (IOException error) {
                                boolean large = error instanceof ImageInput.InvalidImage && ((ImageInput.InvalidImage) error).tooLarge;
                                throw new ApiError(large ? 413 : 400, error.getMessage(), "invalid_request_error",
                                        large ? "image_too_large" : "invalid_image", partParam + ".image_url.url");
                            }
                        } else {
                            throw invalid("Supported content part types: text and image_url.", partParam + ".type");
                        }
                    }
                } else throw invalid("content must be a string or an array of text/image_url parts.", param + ".content");
                ChatMessage decoded = new ChatMessage(role, contents, calls, toolCallId, toolName);
                boolean hasImage = contents.stream().anyMatch(ChatPart::isImage);
                if ((!hasImage && decoded.text.trim().isEmpty() && calls.isEmpty() && !role.equals("tool")) || decoded.text.indexOf('\0') >= 0) {
                    throw invalid("Message text must be non-empty and contain no null characters.", param + ".content");
                }
                textBytes += decoded.text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                if (textBytes > 256 * 1024) throw new ApiError(413, "Conversation text exceeds 256 KiB.", "invalid_request_error", "request_too_large", "messages");
                messages.add(decoded);
            }
            if (!pendingCalls.isEmpty()) throw invalid("Supply a result for every pending tool call.", "messages");
            String lastRole = messages.get(messages.size() - 1).role;
            if (!lastRole.equals("user") && !lastRole.equals("tool")) {
                throw invalid("The last message must be a user turn or a completed tool result.", "messages");
            }
            if (optional(json, "n")) integer(json, "n", 1, 1);
            if (optional(json, "max_tokens") && optional(json, "max_completion_tokens")) {
                throw invalid("Use either max_tokens or max_completion_tokens.", "max_completion_tokens");
            }
            boolean enableThinking = thinking(json, defaultThinking);
            boolean jsonObject = jsonObjectFormat(json);
            int maxTokens = optional(json, "max_completion_tokens")
                    ? integer(json, "max_completion_tokens", 1, 4096)
                    : optional(json, "max_tokens") ? integer(json, "max_tokens", 1, 4096)
                    : enableThinking || jsonObject ? 2048 : 256;
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
            if (optional(json, "prompt_cache_key")) {
                String cacheKey = string(json, "prompt_cache_key");
                if (cacheKey.length() > 256 || cacheKey.indexOf('\0') >= 0) throw invalid("prompt_cache_key must contain at most 256 characters and no null characters.", "prompt_cache_key");
                // A cache hint is metadata. Fresh conversations remain isolated per request.
            }
            for (String penalty : List.of("frequency_penalty", "presence_penalty")) {
                if (optional(json, penalty)) number(json, penalty, 0, 0);
            }
            if (bool(json, "logprobs", false)) throw invalid("Log probabilities are unsupported.", "logprobs");
            List<ToolDefinition> tools = OpenAiTools.definitions(json);
            String toolChoice = optional(json, "tool_choice") ? string(json, "tool_choice") : tools.isEmpty() ? "none" : "auto";
            if (!Set.of("none", "auto").contains(toolChoice)) throw invalid("Supported tool_choice values: auto and none.", "tool_choice");
            if (jsonObject) {
                String instruction = "Return exactly one valid JSON object matching the requested structure. "
                        + "Use double-quoted JSON keys and strings. Do not include Markdown fences, "
                        + "commentary, or text outside the object. Keep the JSON concise and complete.";
                if (messages.get(0).role.equals("system")) {
                    messages.set(0, new ChatMessage("system", messages.get(0).text + "\n\n" + instruction));
                } else messages.add(0, new ChatMessage("system", instruction));
            }
            return new ChatRequest(new GenerationRequest(modelSource, messages, verbose, maxTokens, temperature, topP, seed,
                    enableThinking, tools, toolChoice.equals("auto") && !tools.isEmpty()), stream, includeUsage, jsonObject);
        } catch (JSONException error) {
            throw new ApiError(400, "Malformed JSON or invalid field type.", "invalid_request_error", "invalid_json", null);
        }
    }

    private static boolean jsonObjectFormat(JSONObject json) throws ApiError {
        if (!optional(json, "response_format")) return false;
        JSONObject format = json.optJSONObject("response_format");
        if (format == null || format.length() != 1
                || !("text".equals(format.opt("type")) || "json_object".equals(format.opt("type")))) {
            throw invalid("Supported response_format types: text and json_object (without a schema).", "response_format");
        }
        return "json_object".equals(format.opt("type"));
    }

    static boolean isJsonObject(String text) {
        try {
            validateJson(text);
            JSONTokener tokener = new JSONTokener(text);
            return tokener.nextValue() instanceof JSONObject && tokener.nextClean() == 0;
        } catch (ApiError | JSONException error) {
            return false;
        }
    }

    private static boolean thinking(JSONObject json, boolean fallback) throws ApiError {
        Boolean enabled = optional(json, "enable_thinking") ? bool(json, "enable_thinking", false) : null;
        if (optional(json, "reasoning_effort")) enabled = mergeThinking(enabled, effort(json, "reasoning_effort", "reasoning_effort"), "reasoning_effort");
        if (optional(json, "reasoning")) {
            JSONObject reasoning = json.optJSONObject("reasoning");
            if (reasoning == null) throw invalid("reasoning must be an object.", "reasoning");
            keys(reasoning, Set.of("effort", "enabled"), "reasoning.");
            Boolean mode = optional(reasoning, "enabled") ? bool(reasoning, "enabled", false) : null;
            if (optional(reasoning, "effort")) mode = mergeThinking(mode, effort(reasoning, "effort", "reasoning.effort"), "reasoning.effort");
            // Presence of a reasoning object opts into thinking unless explicitly disabled.
            enabled = mergeThinking(enabled, mode == null || mode, "reasoning");
        }
        return enabled == null ? fallback : enabled;
    }

    private static boolean effort(JSONObject json, String key, String param) throws ApiError {
        Object value = json.opt(key);
        if (!(value instanceof String) || !Set.of("none", "minimal", "low", "medium", "high", "xhigh", "max").contains(value)) throw invalid("Unsupported reasoning effort. Use none, minimal, low, medium, high, xhigh, or max.", param);
        // Gemma exposes a thinking switch, not graduated effort levels.
        return !value.equals("none");
    }

    private static Boolean mergeThinking(Boolean prior, boolean next, String param) throws ApiError {
        if (prior != null && prior != next) throw invalid("Conflicting reasoning controls.", param);
        return next;
    }

    private static ApiError imageLimit(String message, String param) {
        return new ApiError(413, message, "invalid_request_error", "image_too_large", param);
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

    static boolean optional(JSONObject json, String key) { return json.has(key) && !json.isNull(key); }

    static void keys(JSONObject json, Set<String> allowed, String prefix) throws ApiError {
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!allowed.contains(key)) throw invalid("Unsupported field: " + prefix + key, prefix + key);
        }
    }

    static String string(JSONObject json, String key) throws ApiError {
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
        if (chat) models.put(model(MODEL).put("architecture", new JSONObject()
                .put("input_modalities", new JSONArray().put("text").put("image"))
                .put("output_modalities", new JSONArray().put("text"))));
        if (speech) models.put(model(dev.inferdroid.speech.SpeechModelStore.MODEL_ID));
        if (tts) models.put(model(dev.inferdroid.tts.TtsModelStore.MODEL_ID));
        JSONObject response = new JSONObject().put("object", "list").put("data", models);
        // FitBuddy's local-provider picker reads the llama.cpp/Ollama capability extension.
        // Preserve the standard data entries and exact model IDs for existing clients.
        JSONArray nativeModels = new JSONArray();
        if (chat) nativeModels.put(new JSONObject().put("name", MODEL).put("model", MODEL)
                .put("capabilities", new JSONArray().put("completion").put("vision")));
        return response.put("models", nativeModels);
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
        JSONObject message = new JSONObject().put("role", "assistant").put("content", result.text);
        if (!result.reasoning.isEmpty()) message.put("reasoning_content", result.reasoning);
        if (!result.toolCalls.isEmpty() && !result.limitReached) {
            message.put("tool_calls", OpenAiTools.wireCalls(result.toolCalls, false));
            if (result.text.isEmpty()) message.put("content", JSONObject.NULL);
        }
        JSONObject response = base(id, created, false).put("choices", new JSONArray().put(new JSONObject().put("index", 0)
                .put("message", message)
                .put("finish_reason", finishReason(result)).put("logprobs", JSONObject.NULL)));
        if (usage(result) != JSONObject.NULL) response.put("usage", usage(result));
        return response;
    }

    static String finishReason(GenerationResult result) {
        return result.limitReached ? "length" : result.toolCalls.isEmpty() ? "stop" : "tool_calls";
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
