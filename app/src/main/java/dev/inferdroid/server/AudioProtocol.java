package dev.inferdroid.server;

import dev.inferdroid.server.OpenAiProtocol.ApiError;
import dev.inferdroid.speech.AudioDecoder;
import dev.inferdroid.speech.SpeechLanguages;
import dev.inferdroid.speech.SpeechModelStore;
import dev.inferdroid.speech.TranscriptionRequest;
import dev.inferdroid.speech.TranscriptionResult;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/** Bounded multipart adapter. Uploaded filenames are metadata and never paths. */
final class AudioProtocol {
    static final int MAX_BODY = AudioDecoder.MAX_FILE_BYTES + 16 * 1024;
    static final class Request {
        final TranscriptionRequest transcription;
        final String responseFormat;
        Request(TranscriptionRequest transcription, String responseFormat) {
            this.transcription = transcription; this.responseFormat = responseFormat;
        }
    }
    private AudioProtocol() { }
    static Request parse(String contentType, byte[] body) throws ApiError {
        Map<String, String> parameters = parameters(contentType, "multipart/form-data", null);
        String boundary = parameters.remove("boundary");
        if (boundary == null || !boundary.matches("[0-9A-Za-z'()+_,./:=? -]{1,70}")
                || boundary.endsWith(" ") || !parameters.isEmpty()) throw invalid("Supply a valid multipart/form-data boundary.", null);
        byte[] marker = ("--" + boundary).getBytes(StandardCharsets.US_ASCII);
        byte[] delimiter = ("\r\n--" + boundary).getBytes(StandardCharsets.US_ASCII);
        Map<String, String> fields = new HashMap<>();
        byte[] audio = null;
        int pos = 0, parts = 0, metadataBytes = 0;
        while (true) {
            if (!at(body, pos, marker)) throw invalid("Malformed multipart boundary.", null);
            pos += marker.length;
            if (at(body, pos, new byte[]{'-', '-'})) {
                pos += 2;
                if (at(body, pos, new byte[]{'\r', '\n'})) pos += 2;
                if (pos != body.length) throw invalid("Unexpected multipart trailing data.", null);
                break;
            }
            if (!at(body, pos, new byte[]{'\r', '\n'}) || ++parts > 16) throw invalid("Malformed or excessive multipart parts.", null);
            pos += 2;
            int headerEnd = index(body, new byte[]{'\r', '\n', '\r', '\n'}, pos);
            if (headerEnd < 0 || headerEnd - pos > 2048) throw invalid("Multipart headers are missing or too large.", null);
            Map<String, String> headers = new HashMap<>();
            String raw = new String(body, pos, headerEnd - pos, StandardCharsets.ISO_8859_1);
            for (String line : raw.split("\r\n", -1)) {
                int colon = line.indexOf(':');
                if (colon < 1 || !line.substring(0, colon).matches("[A-Za-z0-9-]+")) throw invalid("Malformed multipart header.", null);
                String name = line.substring(0, colon).toLowerCase(Locale.ROOT);
                if (!name.equals("content-disposition") && !name.equals("content-type")) throw invalid("Unsupported multipart header.", null);
                if (headers.put(name, line.substring(colon + 1).trim()) != null) throw invalid("Duplicate multipart header.", null);
            }
            Map<String, String> disposition = parameters(headers.getOrDefault("content-disposition", ""), "form-data", null);
            String name = disposition.remove("name");
            String filename = disposition.remove("filename");
            if (name == null || !disposition.isEmpty()) throw invalid("Supply named form-data parts.", null);
            int start = headerEnd + 4;
            int end = start;
            while (true) {
                end = index(body, delimiter, end);
                if (end < 0) throw invalid("Multipart closing boundary is missing.", null);
                int suffix = end + delimiter.length;
                if (at(body, suffix, new byte[]{'\r', '\n'}) || at(body, suffix, new byte[]{'-', '-'})) break;
                end++;
            }
            if (name.equals("file")) {
                if (audio != null) throw invalid("Supply exactly one audio file.", "file");
                if (filename == null || end == start) throw invalid("file must contain a non-empty audio upload.", "file");
                if (end - start > AudioDecoder.MAX_FILE_BYTES) throw new ApiError(413, "Audio file exceeds 25 MiB.", "invalid_request_error", "request_too_large", "file");
                audio = Arrays.copyOfRange(body, start, end);
            } else {
                if (filename != null || end - start > 1024 || fields.containsKey(name)) throw invalid("Invalid or duplicate transcription field.", name);
                if (!java.util.Set.of("model", "language", "response_format", "temperature", "prompt", "stream").contains(name)) throw invalid("Unsupported transcription parameter.", name);
                fields.put(name, utf8(body, start, end - start, name));
                metadataBytes += end - start;
            }
            metadataBytes += headerEnd - pos + delimiter.length + 8;
            if (metadataBytes > 16 * 1024) throw invalid("Multipart metadata exceeds 16 KiB.", null);
            pos = end + 2;
        }
        if (audio == null) throw invalid("Supply the audio file as the file form-data part.", "file");
        String model = fields.get("model");
        if (model == null) throw invalid("Supply the local speech model ID.", "model");
        if (!model.equals(SpeechModelStore.MODEL_ID) && !model.equals("whisper-1")) throw new ApiError(404,
                "Unknown speech model. Use sherpa-onnx-whisper-tiny from GET /v1/models.", "invalid_request_error", "model_not_found", "model");
        String language = fields.getOrDefault("language", "");
        if (!SpeechLanguages.supports(language)) throw invalid("Use a supported Whisper language code such as en or it, or omit language for detection.", "language");
        String format = fields.getOrDefault("response_format", "json");
        if (!java.util.Set.of("json", "text", "verbose_json").contains(format)) throw invalid("Supported response formats: json, text, verbose_json (without timestamps).", "response_format");
        if (!fields.getOrDefault("prompt", "").isEmpty()) throw invalid("Transcription prompting is unsupported by this speech backend.", "prompt");
        if (fields.containsKey("temperature")) {
            try { if (Double.parseDouble(fields.get("temperature")) != 0) throw invalid("Only temperature=0 (greedy decoding) is supported.", "temperature"); }
            catch (NumberFormatException error) { throw invalid("temperature must be zero.", "temperature"); }
        }
        if (!fields.getOrDefault("stream", "false").equals("false")) throw invalid("Streaming transcription is future work. Use stream=false or omit it.", "stream");
        byte[] file = audio;
        return new Request(new TranscriptionRequest(() -> new ByteArrayInputStream(file), language), format);
    }
    static JSONObject response(TranscriptionResult result, String format) throws JSONException {
        JSONObject json = new JSONObject().put("text", result.text);
        if (format.equals("verbose_json")) json.put("task", "transcribe").put("language", result.language).put("duration", result.durationSeconds);
        return json;
    }
    static ApiError failure(TranscriptionResult result) {
        return switch (result.failure) {
            case INVALID_AUDIO -> new ApiError(400, result.diagnostics, "invalid_request_error", "invalid_audio", "file");
            case AUDIO_TOO_LARGE -> new ApiError(413, result.diagnostics, "invalid_request_error", "request_too_large", "file");
            case MODEL_UNAVAILABLE -> unavailable();
            case CANCELLED -> new ApiError(500, "Speech request cancelled.", "server_error", "request_cancelled", null);
            default -> new ApiError(500, "Local CPU speech recognition failed. Check InferDroid speech status.", "server_error", "transcription_failed", null);
        };
    }
    static ApiError unavailable() { return new ApiError(503, "Import the Whisper tiny speech model in InferDroid first.", "server_error", "speech_model_unavailable", "model"); }
    private static ApiError invalid(String message, String param) { return OpenAiProtocol.invalid(message, param); }
    private static String utf8(byte[] body, int start, int size, String param) throws ApiError {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body, start, size)).toString(); }
        catch (CharacterCodingException error) { throw invalid("Form fields must be valid UTF-8.", param); }
    }
    private static Map<String, String> parameters(String value, String expected, String param) throws ApiError {
        int first = value.indexOf(';');
        if (!(first < 0 ? value : value.substring(0, first)).trim().equalsIgnoreCase(expected)) throw invalid("Expected " + expected + ".", param);
        Map<String, String> result = new HashMap<>();
        int pos = first < 0 ? value.length() : first;
        while (pos < value.length()) {
            if (value.charAt(pos++) != ';') throw invalid("Malformed multipart parameter.", param);
            while (pos < value.length() && value.charAt(pos) == ' ') pos++;
            int equals = value.indexOf('=', pos);
            if (equals < 0) throw invalid("Malformed multipart parameter.", param);
            String name = value.substring(pos, equals).trim().toLowerCase(Locale.ROOT);
            if (!name.matches("[a-z0-9_-]+")) throw invalid("Invalid multipart parameter name.", param);
            pos = equals + 1;
            StringBuilder text = new StringBuilder();
            if (pos < value.length() && value.charAt(pos) == '"') {
                pos++;
                boolean closed = false;
                while (pos < value.length()) {
                    char ch = value.charAt(pos++);
                    if (ch == '"') { closed = true; break; }
                    if (ch == '\\') { if (pos == value.length()) break; ch = value.charAt(pos++); }
                    if (ch < 32 || ch == 127) throw invalid("Invalid multipart parameter value.", param);
                    text.append(ch);
                }
                if (!closed) throw invalid("Unclosed multipart parameter quote.", param);
                while (pos < value.length() && value.charAt(pos) == ' ') pos++;
            } else {
                int end = value.indexOf(';', pos);
                if (end < 0) end = value.length();
                text.append(value.substring(pos, end).trim()); pos = end;
            }
            if (result.put(name, text.toString()) != null) throw invalid("Duplicate multipart parameter.", param);
        }
        return result;
    }
    private static boolean at(byte[] input, int pos, byte[] needle) {
        if (pos < 0 || pos > input.length - needle.length) return false;
        for (int i = 0; i < needle.length; i++) if (input[pos + i] != needle[i]) return false;
        return true;
    }
    private static int index(byte[] input, byte[] needle, int start) {
        for (int pos = start; pos <= input.length - needle.length; pos++) if (input[pos] == needle[0] && at(input, pos, needle)) return pos;
        return -1;
    }
}
