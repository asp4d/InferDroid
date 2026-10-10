package dev.inferdroid.engine;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

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
                                     GenerationListener listener) throws JSONException {
        JSONArray turns = new JSONArray();
        for (ChatMessage message : request.messages) {
            turns.put(new JSONObject().put("role", message.role).put("content",
                    new JSONArray().put(new JSONObject().put("type", "text").put("text", message.text))));
        }
        JSONObject payload = new JSONObject().put("messages", turns)
                .put("max_output_tokens", request.maxOutputTokens);
        if (request.temperature != null) payload.put("temperature", request.temperature);
        if (request.topP != null) payload.put("top_p", request.topP);
        if (request.seed != null) payload.put("seed", request.seed);
        TextCallback callback = listener == null ? null : new TextCallback(listener);
        GenerationResult result = generateNative(handle, requestId,
                payload.toString().getBytes(StandardCharsets.UTF_8), request.verbose, callback);
        if (callback != null) callback.finish();
        return result;
    }

    /** Retain incomplete UTF-8 across token boundaries, including emoji. */
    static final class TextCallback {
        private final GenerationListener listener;
        private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
        private byte[] pending = new byte[0];

        TextCallback(GenerationListener listener) { this.listener = listener; }

        public synchronized void onText(byte[] bytes) { decode(bytes, false); }
        synchronized void finish() { decode(new byte[0], true); }

        private void decode(byte[] bytes, boolean end) {
            ByteBuffer input = ByteBuffer.allocate(pending.length + bytes.length);
            input.put(pending).put(bytes).flip();
            CharBuffer output = CharBuffer.allocate(input.remaining() + 2);
            decoder.decode(input, output, end);
            pending = new byte[input.remaining()];
            input.get(pending);
            if (end) decoder.flush(output);
            output.flip();
            if (output.hasRemaining()) listener.onText(output.toString());
        }
    }

    static native LoadResult loadNative(int fd, String modelPath, String libraryDir,
            String cacheDir, boolean verbose);
    private static native GenerationResult generateNative(long handle, long requestId,
            byte[] request, boolean verbose, TextCallback callback);
    static native void cancelNative(long handle, long requestId);
    static native void unloadNative(long handle);
}
