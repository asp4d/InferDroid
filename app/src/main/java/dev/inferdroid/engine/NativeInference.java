package dev.inferdroid.engine;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import java.io.IOException;

final class NativeInference {
    static { System.loadLibrary("inferdroid_jni"); }

    private NativeInference() {}

    static final class LoadResult {
        final long handle;
        final String diagnostics;

        // JNI load result; opaque native IDs stay inside this backend.
        LoadResult(long handle, byte[] diagnostics) {
            this.handle = handle;
            this.diagnostics = new String(diagnostics, StandardCharsets.UTF_8);
        }
    }

    static GenerationResult generate(long handle, long requestId, GenerationRequest request,
                                     GenerationListener listener) throws JSONException, IOException {
        JSONArray turns = new JSONArray();
        int imageCount = 0;
        int imageBytes = 0;
        for (ChatMessage message : modelMessageOrder(request.messages)) {
            JSONArray content = new JSONArray();
            for (ChatPart part : message.parts) {
                if (!part.isImage()) {
                    content.put(new JSONObject().put("type", "text").put("text", part.text));
                } else {
                    ImageInput image = part.image != null ? part.image : ImageInput.read(part.source);
                    imageBytes += image.sourceBytes;
                    if (++imageCount > ImageInput.MAX_IMAGES || imageBytes > ImageInput.MAX_TOTAL_BYTES) {
                        throw new IOException("Supply at most four images totaling at most 4 MiB.");
                    }
                    content.put(new JSONObject().put("type", "image").put("blob", image.base64Png()));
                }
            }
            if (message.role.equals("tool")) {
                // Gemma's template identifies responses by function name rather than wire ID.
                content = new JSONArray().put(new JSONObject().put("name", message.toolName)
                        .put("response", new JSONObject().put("result", message.text)));
            }
            JSONObject turn = new JSONObject().put("role", message.role).put("content", content);
            if (!message.toolCalls.isEmpty()) {
                JSONArray calls = new JSONArray();
                for (ToolCall call : message.toolCalls) {
                    calls.put(new JSONObject().put("type", "function").put("function", new JSONObject()
                            .put("name", call.name).put("arguments", new JSONObject(call.argumentsJson))));
                }
                turn.put("tool_calls", calls);
            }
            turns.put(turn);
        }
        JSONObject payload = new JSONObject().put("messages", turns)
                .put("max_output_tokens", request.maxOutputTokens).put("enable_thinking", request.enableThinking);
        if (request.temperature != null) payload.put("temperature", request.temperature);
        if (request.topP != null) payload.put("top_p", request.topP);
        if (request.seed != null) payload.put("seed", request.seed);
        JSONArray tools = new JSONArray();
        if (request.allowToolCalls) {
            for (ToolDefinition tool : request.tools) {
                JSONObject parameters = new JSONObject(tool.parametersJson);
                JSONArray missingFields = new JSONArray();
                templateOmittedFields(parameters, "", missingFields);
                String description = tool.description;
                if (missingFields.length() > 0) description += "\nAdditional parameter schema fields: " + missingFields;
                tools.put(new JSONObject().put("type", "function").put("function", new JSONObject()
                        .put("name", tool.name).put("description", description)
                        .put("parameters", parameters)));
            }
        }
        payload.put("tools", tools);
        TextCallback callback = listener == null ? null : new TextCallback(listener);
        GenerationResult result = generateNative(handle, requestId,
                payload.toString().getBytes(StandardCharsets.UTF_8), request.verbose, callback);
        if (callback != null) callback.finish();
        return result;
    }

    // Gemma's template identifies responses by name and position, without wire
    // IDs. Canonicalize each complete result batch to call order so two calls to
    // the same function cannot receive each other's out-of-order results.
    private static java.util.List<ChatMessage> modelMessageOrder(java.util.List<ChatMessage> messages) throws IOException {
        java.util.List<ChatMessage> ordered = new java.util.ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            ordered.add(message);
            if (message.toolCalls.isEmpty()) continue;
            java.util.Map<String, ChatMessage> results = new java.util.HashMap<>();
            for (int j = 0; j < message.toolCalls.size(); j++) {
                if (++i >= messages.size() || !messages.get(i).role.equals("tool")) throw new IOException("Missing function results in chat history.");
                ChatMessage result = messages.get(i);
                if (results.put(result.toolCallId, result) != null) throw new IOException("Duplicate function result in chat history.");
            }
            for (ToolCall call : message.toolCalls) {
                ChatMessage result = results.get(call.id);
                if (result == null) throw new IOException("Function result does not match its call ID.");
                ordered.add(result);
            }
        }
        return ordered;
    }

    // The G5 package's format_parameters macro skips these names even when they
    // are actual function properties (not schema metadata). Agora's memory and
    // skill tools use "description". Supply affected schemas in the declaration
    // description as well, preserving the proven model template and backend.
    private static void templateOmittedFields(JSONObject schema, String path, JSONArray fields) throws JSONException {
        JSONObject properties = schema.optJSONObject("properties");
        if (properties != null) {
            java.util.Iterator<String> names = properties.keys();
            while (names.hasNext()) {
                String name = names.next();
                JSONObject child = properties.getJSONObject(name);
                String location = path + "/properties/" + name.replace("~", "~0").replace("/", "~1");
                if (java.util.Set.of("description", "type", "properties", "required", "nullable").contains(name)) {
                    fields.put(new JSONObject().put("schema_path", location).put("schema", child));
                } else templateOmittedFields(child, location, fields);
            }
        }
        JSONObject items = schema.optJSONObject("items");
        if (items != null) templateOmittedFields(items, path + "/items", fields);
    }

    /** Retain incomplete UTF-8 across token boundaries, including emoji. */
    static final class TextCallback {
        private final Utf8Channel text;
        private final Utf8Channel reasoning;

        TextCallback(GenerationListener listener) {
            text = new Utf8Channel(listener::onText);
            reasoning = new Utf8Channel(listener::onReasoning);
        }

        public synchronized void onText(byte[] bytes) { text.decode(bytes, false); }
        public synchronized void onReasoning(byte[] bytes) { reasoning.decode(bytes, false); }
        synchronized void finish() {
            text.decode(new byte[0], true);
            reasoning.decode(new byte[0], true);
        }
    }

    private static final class Utf8Channel {
        private final java.util.function.Consumer<String> consumer;
        private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
        private byte[] pending = new byte[0];

        Utf8Channel(java.util.function.Consumer<String> consumer) { this.consumer = consumer; }

        private void decode(byte[] bytes, boolean end) {
            ByteBuffer input = ByteBuffer.allocate(pending.length + bytes.length);
            input.put(pending).put(bytes).flip();
            CharBuffer output = CharBuffer.allocate(input.remaining() + 2);
            decoder.decode(input, output, end);
            pending = new byte[input.remaining()];
            input.get(pending);
            if (end) decoder.flush(output);
            output.flip();
            if (output.hasRemaining()) consumer.accept(output.toString());
        }
    }

    static native LoadResult loadNative(int fd, String modelPath, String libraryDir,
            String cacheDir, boolean verbose);
    private static native GenerationResult generateNative(long handle, long requestId,
            byte[] request, boolean verbose, TextCallback callback);
    static native void cancelNative(long handle, long requestId);
    static native void unloadNative(long handle);
}
