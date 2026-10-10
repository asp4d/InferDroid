package dev.inferdroid.server;

import dev.inferdroid.engine.GenerationListener;
import dev.inferdroid.engine.GenerationResult;
import dev.inferdroid.speech.TranscriptionListener;
import dev.inferdroid.speech.TranscriptionResult;
import dev.inferdroid.server.OpenAiProtocol.ApiError;
import dev.inferdroid.server.OpenAiProtocol.ChatRequest;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONException;
import org.json.JSONObject;

/** Bounded HTTP/1.1 transport. One request per connection; SSE ends on connection close. */
public final class OpenAiServer implements AutoCloseable {
    public static final String MODEL_ID = OpenAiProtocol.MODEL;
    private static final int MAX_BODY = 256 * 1024;
    private static final int MAX_HEADERS = 16 * 1024;
    private static final int REQUEST_TIMEOUT_SECONDS = 10 * 60;
    private final ServerConfig config;
    private final String modelSource;
    private final boolean verbose;
    private final ChatGateway gateway;
    private final TranscriptionGateway speech;
    private final Runnable onUnexpectedStop;
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), task -> new Thread(task, "InferDroid-http"));
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1,
            task -> new Thread(task, "InferDroid-http-deadlines"));
    private volatile ServerSocket listener;
    private volatile boolean running;

    public OpenAiServer(ServerConfig config, String modelSource, boolean verbose, ChatGateway gateway,
                        Runnable onUnexpectedStop) {
        this(config, modelSource, verbose, gateway, null, onUnexpectedStop);
    }

    public OpenAiServer(ServerConfig config, String modelSource, boolean verbose, ChatGateway gateway,
                        TranscriptionGateway speech, Runnable onUnexpectedStop) {
        this.config = config;
        this.modelSource = modelSource;
        this.verbose = verbose;
        this.gateway = gateway;
        this.speech = speech;
        this.onUnexpectedStop = onUnexpectedStop;
        deadlines.setRemoveOnCancelPolicy(true);
    }

    public synchronized void start() throws IOException {
        if (listener != null) throw new IllegalStateException("Server already started.");
        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getByName(ServerConfig.HOST), config.port), 16);
            listener = socket;
            running = true;
        } catch (IOException error) {
            socket.close();
            close();
            throw error;
        }
        new Thread(this::accept, "InferDroid-http-listener").start();
    }

    public boolean isRunning() { return running; }
    public int getPort() { return listener == null ? config.port : listener.getLocalPort(); }

    private void accept() {
        boolean unexpected = false;
        try {
            while (running) {
                Socket socket = listener.accept();
                if (!running) { closeSocket(socket); break; }
                socket.setSoTimeout(15_000);
                socket.setTcpNoDelay(true);
                clients.add(socket);
                try {
                    ScheduledFuture<?> timeout = deadlines.schedule(() -> closeSocket(socket), 15, TimeUnit.SECONDS);
                    workers.execute(() -> handle(socket, timeout));
                } catch (RejectedExecutionException error) {
                    // The response is small and bounded. The connection is immediately closed.
                    try {
                        json(socket.getOutputStream(), new ApiError(429, "HTTP server is busy. Retry shortly.",
                                "rate_limit_error", "server_busy", null));
                    } catch (IOException | JSONException ignored) { }
                    clients.remove(socket);
                    closeSocket(socket);
                }
            }
        } catch (IOException | RuntimeException error) {
            unexpected = running;
        } finally {
            close();
            if (unexpected && onUnexpectedStop != null) onUnexpectedStop.run();
        }
    }

    private void handle(Socket socket, ScheduledFuture<?> headerTimeout) {
        Session session = null;
        ScheduledFuture<?> requestTimeout = null;
        boolean streamingStarted = false;
        try {
            InputStream input = new BufferedInputStream(socket.getInputStream());
            OutputStream output = socket.getOutputStream();
            HttpRequest request = readRequest(input);
            String host = request.headers.get("host");
            if (host == null || !host.matches("(?i)(127\\.0\\.0\\.1|localhost)(:[0-9]{1,5})?")) {
                throw new ApiError(403, "Use a localhost URL to reach InferDroid.", "permission_error", "invalid_host", null);
            }
            String path = request.target.split("\\?", 2)[0];
            if (!Set.of("/v1/models", "/v1/chat/completions", "/v1/audio/transcriptions").contains(path)) {
                throw new ApiError(404, "Unknown API endpoint.", "invalid_request_error", "not_found", null);
            }
            if (request.headers.containsKey("origin") && !config.cors) {
                throw new ApiError(403, "Browser access is disabled. Enable CORS in InferDroid.", "permission_error", "cors_disabled", null);
            }
            if (request.method.equals("OPTIONS")) {
                if (!config.cors) throw new ApiError(403, "CORS is disabled.", "permission_error", "cors_disabled", null);
                headers(output, 204, null, 0);
                output.flush();
                return;
            }
            authenticate(request);
            if (request.headers.containsKey("transfer-encoding")) {
                throw new ApiError(411, "Use Content-Length; chunked request bodies are unsupported.", "invalid_request_error", "length_required", null);
            }
            if (request.headers.containsKey("expect")) {
                throw new ApiError(417, "Expect headers are unsupported.", "invalid_request_error", "expectation_failed", null);
            }
            if (path.equals("/v1/models")) {
                if (!request.method.equals("GET")) throw methodError();
                json(output, 200, OpenAiProtocol.models(!modelSource.isEmpty(), speech != null && speech.isAvailable()));
                return;
            }
            if (!request.method.equals("POST")) throw methodError();
            if (path.equals("/v1/audio/transcriptions")) {
                transcribe(socket, input, output, request, headerTimeout);
                return;
            }
            String contentType = request.headers.getOrDefault("content-type", "").split(";", 2)[0].trim();
            if (!contentType.equalsIgnoreCase("application/json")) {
                throw new ApiError(415, "Send Content-Type: application/json.", "invalid_request_error", "unsupported_media_type", null);
            }
            String length = request.headers.get("content-length");
            if (length == null || !length.matches("[0-9]{1,10}")) {
                throw new ApiError(411, "Supply a valid Content-Length.", "invalid_request_error", "length_required", null);
            }
            long bodySize = Long.parseLong(length);
            if (bodySize > MAX_BODY) throw new ApiError(413, "Request body exceeds 256 KiB.", "invalid_request_error", "request_too_large", null);
            byte[] bytes = new byte[(int) bodySize];
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) throw new EOFException();
                offset += count;
            }
            String body;
            try {
                body = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException error) {
                throw OpenAiProtocol.invalid("JSON must be valid UTF-8.", null);
            }
            ChatRequest chat = OpenAiProtocol.parse(body, modelSource, verbose);
            if (modelSource.isEmpty()) throw new ApiError(503, "Choose the Gemma chat model in InferDroid first.",
                    "server_error", "chat_model_unavailable", "model");
            headerTimeout.cancel(false);
            requestTimeout = deadlines.schedule(() -> closeSocket(socket), REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            session = new Session(socket, chat.stream);
            if (!gateway.generate(chat.generation, session)) {
                throw new ApiError(429, "Inference is busy loading, generating, or stopping. Retry when idle.",
                        "rate_limit_error", "engine_busy", null);
            }
            session.accepted = true;
            String id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "");
            long created = System.currentTimeMillis() / 1000;
            if (chat.stream) {
                headers(output, 200, "text/event-stream; charset=utf-8", -1);
                streamingStarted = true;
                event(output, OpenAiProtocol.chunk(id, created, new JSONObject().put("role", "assistant").put("content", ""), null, chat.includeUsage));
            }
            socket.setSoTimeout(50);
            long lastHeartbeat = System.nanoTime();
            StringBuilder streamed = new StringBuilder();
            while (session.result == null || !session.text.isEmpty()) {
                String delta = session.text.poll(100, TimeUnit.MILLISECONDS);
                if (delta != null) {
                    session.queuedCharacters.addAndGet(-delta.length());
                    streamed.append(delta);
                    event(output, OpenAiProtocol.chunk(id, created, new JSONObject().put("content", delta), null, chat.includeUsage));
                }
                if (session.result != null) continue;
                // Read only after the complete request body. EOF detects a disconnected
                // non-streaming client as well; reads and writes never run in native callbacks.
                try {
                    if (input.read() < 0) throw new EOFException("Client disconnected.");
                    throw new IOException("HTTP pipelining is unsupported.");
                } catch (SocketTimeoutException ignored) { }
                if (chat.stream && System.nanoTime() - lastHeartbeat >= TimeUnit.SECONDS.toNanos(1)) {
                    output.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    lastHeartbeat = System.nanoTime();
                }
            }
            GenerationResult result = session.result;
            if (!result.success) {
                ApiError error = new ApiError(500, result.cancelled ? "Generation cancelled." : "Local NPU inference failed. Check InferDroid diagnostics.",
                        "server_error", result.cancelled ? "request_cancelled" : "inference_failed", null);
                if (chat.stream) { event(output, error.json()); done(output); }
                else json(output, error);
            } else if (chat.stream) {
                // Never claim a successful stream if upstream text delivery diverged.
                if (!result.text.equals(streamed.toString())) {
                    event(output, new ApiError(500, "Native streaming text did not match the completed response.",
                            "server_error", "stream_mismatch", null).json());
                } else {
                    event(output, OpenAiProtocol.chunk(id, created, new JSONObject(), result.limitReached ? "length" : "stop", chat.includeUsage));
                    if (chat.includeUsage) event(output, OpenAiProtocol.usageChunk(id, created, result));
                }
                done(output);
            } else json(output, 200, OpenAiProtocol.completion(id, created, result));
        } catch (ApiError error) {
            try { json(socket.getOutputStream(), error); } catch (IOException | JSONException ignored) { }
        } catch (SocketTimeoutException error) {
            try { json(socket.getOutputStream(), new ApiError(408, "HTTP request timed out.", "invalid_request_error", "request_timeout", null)); }
            catch (IOException | JSONException ignored) { }
        } catch (IOException ignored) {
            // Disconnects are expected. The request owner is cancelled in finally.
        } catch (Exception error) {
            try {
                ApiError failure = new ApiError(500, "The local request could not be completed.", "server_error", "internal_error", null);
                if (streamingStarted) { event(socket.getOutputStream(), failure.json()); done(socket.getOutputStream()); }
                else json(socket.getOutputStream(), failure);
            } catch (IOException | JSONException ignored) { }
        } finally {
            headerTimeout.cancel(false);
            if (requestTimeout != null) requestTimeout.cancel(false);
            if (session != null && session.accepted && session.result == null) gateway.cancel(session);
            clients.remove(socket);
            closeSocket(socket);
        }
    }

    private void transcribe(Socket socket, InputStream input, OutputStream output, HttpRequest request,
                            ScheduledFuture<?> intakeDeadline) throws Exception {
        String type = request.headers.getOrDefault("content-type", "");
        if (!type.split(";", 2)[0].trim().equalsIgnoreCase("multipart/form-data")) {
            throw new ApiError(415, "Upload audio using multipart/form-data with file and model fields.",
                    "invalid_request_error", "unsupported_media_type", null);
        }
        String length = request.headers.get("content-length");
        if (length == null || !length.matches("[0-9]{1,10}")) throw new ApiError(411,
                "Supply a valid Content-Length.", "invalid_request_error", "length_required", null);
        long size = Long.parseLong(length);
        if (size > AudioProtocol.MAX_BODY) throw new ApiError(413, "Transcription upload exceeds 25 MiB plus 16 KiB metadata.",
                "invalid_request_error", "request_too_large", "file");
        byte[] body = new byte[(int) size];
        int pos = 0;
        while (pos < body.length) {
            int count = input.read(body, pos, body.length - pos);
            if (count < 0) throw new EOFException();
            pos += count;
        }
        AudioProtocol.Request audio = AudioProtocol.parse(type, body);
        if (speech == null || !speech.isAvailable()) throw AudioProtocol.unavailable();
        intakeDeadline.cancel(false);
        ScheduledFuture<?> deadline = deadlines.schedule(() -> closeSocket(socket), REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        AudioSession session = new AudioSession();
        boolean admitted = false;
        try {
            admitted = speech.transcribe(audio.transcription, session);
            if (!admitted) throw new ApiError(429, "Chat or speech inference is busy. Retry when idle.",
                    "rate_limit_error", "engine_busy", null);
            socket.setSoTimeout(100);
            while (session.result == null) {
                try {
                    if (input.read() < 0) throw new EOFException("Client disconnected.");
                    throw new IOException("HTTP pipelining is unsupported.");
                } catch (SocketTimeoutException ignored) { }
                if (Thread.currentThread().isInterrupted() || socket.isClosed()) throw new IOException("Server stopped.");
            }
            if (!session.result.success) throw AudioProtocol.failure(session.result);
            if (audio.responseFormat.equals("text")) {
                byte[] text = session.result.text.getBytes(StandardCharsets.UTF_8);
                headers(output, 200, "text/plain; charset=utf-8", text.length);
                output.write(text); output.flush();
            } else json(output, 200, AudioProtocol.response(session.result, audio.responseFormat));
        } finally {
            deadline.cancel(false);
            if (admitted && session.result == null) speech.cancel(session);
        }
    }

    private static final class AudioSession implements TranscriptionListener {
        volatile TranscriptionResult result;
        @Override public void onComplete(TranscriptionResult result) { this.result = result; }
    }

    private void authenticate(HttpRequest request) throws ApiError {
        if (!config.requireKey) return;
        String authorization = request.headers.getOrDefault("authorization", "");
        boolean bearer = authorization.length() > 7 && authorization.regionMatches(true, 0, "Bearer ", 0, 7);
        byte[] supplied = (bearer ? authorization.substring(7) : "").getBytes(StandardCharsets.UTF_8);
        if (!bearer || !MessageDigest.isEqual(config.apiKey.getBytes(StandardCharsets.UTF_8), supplied)) {
            throw new ApiError(401, "Supply the local API key as Authorization: Bearer <key>.", "authentication_error", "invalid_api_key", null);
        }
    }

    private static ApiError methodError() {
        return new ApiError(405, "Use GET /v1/models, POST /v1/chat/completions, or POST /v1/audio/transcriptions.", "invalid_request_error", "method_not_allowed", null);
    }

    private static final class HttpRequest {
        final String method;
        final String target;
        final Map<String, String> headers;
        HttpRequest(String method, String target, Map<String, String> headers) {
            this.method = method; this.target = target; this.headers = headers;
        }
    }

    private static HttpRequest readRequest(InputStream input) throws IOException, ApiError {
        String line = line(input);
        String[] start = line.split(" ", -1);
        if (start.length != 3 || !start[0].matches("[A-Z]+") || !start[1].startsWith("/") || !start[2].equals("HTTP/1.1")) {
            throw OpenAiProtocol.invalid("Expected an HTTP/1.1 request with an origin-form path.", null);
        }
        Map<String, String> headers = new HashMap<>();
        int total = line.length();
        while (!(line = line(input)).isEmpty()) {
            total += line.length() + 2;
            if (total > MAX_HEADERS || headers.size() >= 64) throw OpenAiProtocol.invalid("HTTP headers are too large.", null);
            int colon = line.indexOf(':');
            if (colon < 1 || !line.substring(0, colon).matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) throw OpenAiProtocol.invalid("Malformed HTTP header.", null);
            String key = line.substring(0, colon).toLowerCase(Locale.ROOT);
            if (headers.put(key, line.substring(colon + 1).trim()) != null) throw OpenAiProtocol.invalid("Duplicate HTTP header.", null);
        }
        return new HttpRequest(start[0], start[1], headers);
    }

    private static String line(InputStream input) throws IOException, ApiError {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (bytes.size() < 8192) {
            int value = input.read();
            if (value < 0) throw new EOFException();
            if (value == '\r') {
                if (input.read() != '\n') throw OpenAiProtocol.invalid("HTTP lines must end with CRLF.", null);
                return bytes.toString(StandardCharsets.US_ASCII.name());
            }
            if (value == '\n' || value < 32 && value != '\t' || value >= 127) throw OpenAiProtocol.invalid("Invalid HTTP header character.", null);
            bytes.write(value);
        }
        throw OpenAiProtocol.invalid("HTTP line is too long.", null);
    }

    private void json(OutputStream output, ApiError error) throws IOException, JSONException { json(output, error.status, error.json()); }

    private void json(OutputStream output, int status, JSONObject json) throws IOException {
        byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
        headers(output, status, "application/json; charset=utf-8", body.length);
        output.write(body);
        output.flush();
    }

    private void headers(OutputStream output, int status, String contentType, int length) throws IOException {
        String reason = switch (status) {
            case 200 -> "OK"; case 204 -> "No Content"; case 400 -> "Bad Request";
            case 401 -> "Unauthorized"; case 403 -> "Forbidden"; case 404 -> "Not Found";
            case 405 -> "Method Not Allowed"; case 408 -> "Request Timeout"; case 411 -> "Length Required";
            case 413 -> "Content Too Large"; case 415 -> "Unsupported Media Type"; case 417 -> "Expectation Failed";
            case 429 -> "Too Many Requests"; case 503 -> "Service Unavailable"; default -> "Internal Server Error";
        };
        StringBuilder headers = new StringBuilder("HTTP/1.1 " + status + " " + reason + "\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n");
        if (contentType != null) headers.append("Content-Type: ").append(contentType).append("\r\n");
        if (length >= 0) headers.append("Content-Length: ").append(length).append("\r\n");
        if (status == 401) headers.append("WWW-Authenticate: Bearer\r\n");
        if (status == 429) headers.append("Retry-After: 1\r\n");
        if (status == 405) headers.append("Allow: GET, POST, OPTIONS\r\n");
        if (config.cors) headers.append("Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS\r\nAccess-Control-Allow-Headers: Authorization, Content-Type\r\nAccess-Control-Max-Age: 600\r\nAccess-Control-Allow-Private-Network: true\r\n");
        headers.append("\r\n");
        output.write(headers.toString().getBytes(StandardCharsets.US_ASCII));
    }

    private static void event(OutputStream output, JSONObject json) throws IOException {
        output.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static void done(OutputStream output) throws IOException {
        output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private final class Session implements GenerationListener {
        private final Socket socket;
        private final boolean stream;
        final ArrayBlockingQueue<String> text = new ArrayBlockingQueue<>(128);
        final AtomicInteger queuedCharacters = new AtomicInteger();
        volatile GenerationResult result;
        boolean accepted;

        Session(Socket socket, boolean stream) { this.socket = socket; this.stream = stream; }
        @Override public void onText(String delta) {
            if (!stream || socket.isClosed()) return;
            if (queuedCharacters.addAndGet(delta.length()) > MAX_BODY || !text.offer(delta)) {
                closeSocket(socket);
                gateway.cancel(this);
            }
        }
        @Override public void onComplete(GenerationResult result) { this.result = result; }
    }

    private static void closeSocket(Socket socket) { try { socket.close(); } catch (IOException ignored) { } }

    @Override public synchronized void close() {
        running = false;
        if (listener != null) { try { listener.close(); } catch (IOException ignored) { } }
        for (Socket socket : clients) closeSocket(socket);
        workers.shutdownNow();
        deadlines.shutdownNow();
    }
}
