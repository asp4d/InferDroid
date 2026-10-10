package dev.inferdroid.server;

import android.app.Instrumentation;
import android.os.Looper;
import dev.inferdroid.engine.EngineManager;
import dev.inferdroid.engine.GenerationListener;
import dev.inferdroid.engine.GenerationRequest;
import dev.inferdroid.engine.GenerationResult;
import dev.inferdroid.engine.InferenceEngine;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Actual loopback sockets and EngineManager admission; no JNI or network dependencies. */
public final class OpenAiServerTests {
    private static final String KEY = "instrumentation-local-key";
    private static final String CHAT = "{\"model\":\"" + OpenAiServer.MODEL_ID
            + "\",\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}]}";
    private final Instrumentation instrumentation;

    public OpenAiServerTests(Instrumentation instrumentation) { this.instrumentation = instrumentation; }

    public void run(String name) throws Exception {
        switch (name) {
            case "apiAuthenticationAndValidation" -> authenticationAndValidation();
            case "apiChatHistoryAndErrors" -> chatHistoryAndErrors();
            case "apiLiveStreaming" -> liveStreaming();
            case "apiBusyDisconnectAndRecovery" -> busyDisconnectAndRecovery();
            case "apiCorsAndPortConflict" -> corsAndPortConflict();
            case "apiStopAndRestart" -> stopAndRestart();
            default -> throw new AssertionError("Unknown test: " + name);
        }
    }

    private void authenticationAndValidation() throws Exception {
        try (Fixture fixture = new Fixture(false, false)) {
            Response missing = fixture.request("GET", "/v1/models", null, "");
            check(missing.status == 401 && missing.json().getJSONObject("error").getString("type").equals("authentication_error"), "missing authentication");
            check(fixture.request("GET", "/v1/models", null, "Authorization: Bearer wrong\r\n").status == 401, "wrong key");
            Response models = fixture.request("GET", "/v1/models", null, auth());
            check(models.status == 200 && models.json().getJSONArray("data").getJSONObject(0).getString("id").equals(OpenAiServer.MODEL_ID), "model discovery");
            check(fixture.request("GET", "/v1/models", null, auth() + "Origin: https://client.example\r\n").status == 403, "CORS disabled");
            check(fixture.raw("GET /v1/models HTTP/1.1\r\nHost: rebound.example\r\n" + auth() + "\r\n").status == 403, "non-local Host accepted");
            check(fixture.raw("GET /v1/models HTTP/1.1\r\nHost: localhost\r\nHost: localhost\r\n" + auth() + "\r\n").status == 400, "duplicate Host accepted");
            check(fixture.request("POST", "/v1/chat/completions", "{broken", auth()).status == 400, "invalid JSON accepted");
            check(fixture.request("POST", "/v1/chat/completions", CHAT + "{}", auth()).status == 400, "trailing JSON accepted");
            check(fixture.request("POST", "/v1/chat/completions", "{'model':'x'}", auth()).status == 400, "non-JSON syntax accepted");
            check(fixture.request("POST", "/v1/chat/completions", "{\"model\":\"x\",\"model\":\"y\"}", auth()).status == 400, "duplicate JSON field accepted");
            check(fixture.request("POST", "/v1/chat/completions", "[".repeat(40) + "0" + "]".repeat(40), auth()).status == 400, "deep JSON accepted");
            check(fixture.raw("POST /v1/chat/completions HTTP/1.1\r\nHost: localhost\r\n" + auth()
                    + "Content-Type: application/json\r\nContent-Length: 300000\r\n\r\n").status == 413, "body limit missing");
            check(fixture.raw("POST /v1/chat/completions HTTP/1.1\r\nHost: localhost\r\n" + auth()
                    + "Content-Type: application/json\r\n\r\n").status == 411, "missing length accepted");
            check(fixture.raw("POST /v1/chat/completions HTTP/1.1\r\nHost: localhost\r\n" + auth()
                    + "Content-Type: text/plain\r\nContent-Length: 0\r\n\r\n").status == 415, "wrong content type accepted");
            check(fixture.engine.generations.get() == 0 && fixture.engine.loads.get() == 0, "invalid request touched engine");
        }
    }

    private void chatHistoryAndErrors() throws Exception {
        try (Fixture fixture = new Fixture(false, false)) {
            JSONObject request = new JSONObject(CHAT);
            request.put("messages", new JSONArray().put(new JSONObject().put("role", "developer").put("content", "Be brief"))
                    .put(new JSONObject().put("role", "user").put("content", "My name is Ada"))
                    .put(new JSONObject().put("role", "assistant").put("content", "Hello Ada"))
                    .put(new JSONObject().put("role", "user").put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", "Who am I?")))));
            request.put("max_completion_tokens", 4).put("temperature", 0).put("top_p", 0.5).put("seed", 42);
            Response response = fixture.request("POST", "/v1/chat/completions", request.toString(), auth());
            check(response.status == 200, "non-streaming failed: " + response.body);
            JSONObject json = response.json();
            check(json.getString("object").equals("chat.completion"), "wrong completion object");
            check(json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").equals("Hello 🌍!"), "UTF-8 response corrupted");
            check(json.getJSONArray("choices").getJSONObject(0).getString("finish_reason").equals("length"), "token limit result discarded");
            check(json.getJSONObject("usage").getInt("total_tokens") == 16, "upstream counts discarded");
            GenerationRequest accepted = fixture.engine.lastRequest;
            check(accepted.messages.size() == 4 && accepted.messages.get(0).role.equals("system") && accepted.messages.get(3).text.equals("Who am I?"), "history was flattened/lost");
            check(accepted.maxOutputTokens == 4 && accepted.temperature == 0 && accepted.topP == 0.5 && accepted.seed == 42, "sampling not translated");
            check(fixture.request("POST", "/v1/chat/completions", CHAT, auth()).status == 200, "repeat failed");
            check(fixture.engine.loads.get() == 1 && fixture.engine.lastRequest.messages.size() == 1, "request history leaked or engine reloaded");
            for (String option : new String[]{"\"max_tokens\":0", "\"max_tokens\":1.5", "\"stream\":\"true\"", "\"temperature\":3", "\"top_p\":0", "\"seed\":2147483648", "\"stop\":\"x\"", "\"n\":2", "\"tools\":[{}]"}) {
                check(fixture.request("POST", "/v1/chat/completions", CHAT.substring(0, CHAT.length() - 1) + "," + option + "}", auth()).status == 400, "unsupported parameter accepted: " + option);
            }
            check(fixture.request("POST", "/v1/chat/completions", CHAT.replace(OpenAiServer.MODEL_ID, "unknown"), auth()).status == 404, "unknown model accepted");
            check(fixture.request("POST", "/v1/chat/completions", CHAT.replace("\"content\":\"Hello\"", "\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"x\"}}]"), auth()).status == 400, "image input accepted");
            fixture.engine.fail = true;
            Response failed = fixture.request("POST", "/v1/chat/completions", CHAT, auth());
            check(failed.status == 500 && failed.json().getJSONObject("error").getString("code").equals("inference_failed"), "backend error shape");
        }
    }

    private void liveStreaming() throws Exception {
        try (Fixture fixture = new Fixture(true, false); Socket client = fixture.connect()) {
            fixture.send(client, "POST", "/v1/chat/completions", CHAT.substring(0, CHAT.length() - 1) + ",\"stream\":true,\"stream_options\":{\"include_usage\":true}}", auth());
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            check(reader.readLine().contains("200"), "stream status");
            String line;
            while (!(line = reader.readLine()).isEmpty()) { }
            StringBuilder text = new StringBuilder();
            String id = null;
            int contentChunks = 0;
            boolean gotUsage = false;
            boolean finished = false;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6);
                if (data.equals("[DONE]")) { finished = true; break; }
                JSONObject chunk = new JSONObject(data);
                check(!chunk.has("error"), "stream error: " + chunk);
                if (id == null) id = chunk.getString("id");
                check(chunk.getString("id").equals(id), "stream ID changed");
                JSONArray choices = chunk.getJSONArray("choices");
                if (choices.length() == 0) {
                    gotUsage = chunk.getJSONObject("usage").getInt("completion_tokens") == 4;
                    continue;
                }
                JSONObject delta = choices.getJSONObject(0).getJSONObject("delta");
                String part = delta.optString("content", "");
                if (!part.isEmpty()) {
                    text.append(part);
                    contentChunks++;
                    if (contentChunks == 1) {
                        check(fixture.engine.finished.getCount() == 1, "first content buffered until completion");
                        fixture.engine.allowFinish.countDown();
                    }
                }
            }
            check(finished && gotUsage && contentChunks == 2 && text.toString().equals("Hello 🌍!"), "incomplete live SSE stream");
        }
    }

    private void busyDisconnectAndRecovery() throws Exception {
        try (Fixture fixture = new Fixture(true, false)) {
            Socket abandoned = fixture.connect();
            fixture.send(abandoned, "POST", "/v1/chat/completions", CHAT, auth());
            await(fixture.engine.entered);
            Response busy = fixture.request("POST", "/v1/chat/completions", CHAT, auth());
            check(busy.status == 429 && busy.json().getJSONObject("error").getString("code").equals("engine_busy"), "overlap not rejected");
            check(!onMain(() -> fixture.manager.generate(new GenerationRequest("test-model", "UI", false))), "UI bypassed API admission");
            abandoned.close();
            await(fixture.engine.cancelled);
            waitReady(fixture);
            check(fixture.request("POST", "/v1/chat/completions", CHAT, auth()).status == 200, "disconnect recovery failed");
            check(fixture.engine.loads.get() == 1, "disconnect unloaded retained model");
            GenerationListener oldOwner = fixture.lastOwner;
            fixture.engine.allowFinish = new CountDownLatch(1);
            fixture.engine.entered = new CountDownLatch(1);
            try (Socket next = fixture.connect()) {
                fixture.send(next, "POST", "/v1/chat/completions", CHAT, auth());
                await(fixture.engine.entered);
                onMain(() -> { fixture.manager.cancel(oldOwner); return null; });
                check(onMain(() -> fixture.manager.getState().canCancel), "stale owner cancelled newer request");
                fixture.engine.allowFinish.countDown();
                check(read(next).status == 200, "new request failed after stale cancel");
            }
        }
    }

    private void corsAndPortConflict() throws Exception {
        try (Fixture fixture = new Fixture(false, true)) {
            Response options = fixture.request("OPTIONS", "/v1/chat/completions", null,
                    "Origin: https://client.example\r\nAccess-Control-Request-Method: POST\r\nAccess-Control-Request-Headers: authorization,content-type\r\n");
            check(options.status == 204 && options.headers.contains("Access-Control-Allow-Origin: *"), "authenticated preflight required or missing CORS");
            Response authError = fixture.request("GET", "/v1/models", null, "Origin: https://client.example\r\n");
            check(authError.status == 401 && authError.headers.contains("Access-Control-Allow-Origin"), "CORS hides authentication error");
            OpenAiServer collision = new OpenAiServer(new ServerConfig(fixture.server.getPort(), KEY, true, false), "test-model", false, fixture.gateway, null);
            boolean failed = false;
            try { collision.start(); } catch (java.io.IOException expected) { failed = true; }
            finally { collision.close(); }
            check(failed && fixture.server.isRunning(), "port conflict replaced original listener");
            check(fixture.request("GET", "/v1/models", null, auth()).status == 200, "original server lost after conflict");
        }
    }

    private void stopAndRestart() throws Exception {
        try (Fixture fixture = new Fixture(true, false)) {
            Socket client = fixture.connect();
            fixture.send(client, "POST", "/v1/chat/completions", CHAT, auth());
            await(fixture.engine.entered);
            int port = fixture.server.getPort();
            fixture.server.close();
            await(fixture.engine.cancelled);
            waitReady(fixture);
            client.close();
            check(fixture.engine.loaded, "stopping HTTP server unloaded the engine");
            fixture.server = new OpenAiServer(new ServerConfig(port, "rotated-key", true, false), "test-model", false, fixture.gateway, null);
            fixture.server.start();
            check(fixture.request("GET", "/v1/models", null, auth()).status == 401, "old key survived rotation");
            check(fixture.request("GET", "/v1/models", null, "Authorization: Bearer rotated-key\r\n").status == 200, "restart on same port failed");
            fixture.server.close();
            fixture.server = new OpenAiServer(new ServerConfig(port, "", false, false), "test-model", false, fixture.gateway, null);
            fixture.server.start();
            check(fixture.request("GET", "/v1/models", null, "").status == 200, "explicit key opt-out failed");
        }
    }

    private final class Fixture implements AutoCloseable {
        final FakeEngine engine = new FakeEngine();
        final EngineManager manager;
        final ChatGateway gateway;
        volatile GenerationListener lastOwner;
        OpenAiServer server;

        Fixture(boolean hold, boolean cors) throws Exception {
            if (hold) engine.allowFinish = new CountDownLatch(1);
            manager = onMain(() -> new EngineManager(engine, new EngineManager.WorkGuard() {
                @Override public void begin() { check(Looper.myLooper() != Looper.getMainLooper(), "native work on main"); }
                @Override public void end() { }
            }));
            gateway = new ChatGateway() {
                @Override public boolean generate(GenerationRequest request, GenerationListener listener) throws Exception {
                    boolean accepted = onMain(() -> manager.generate(request, listener));
                    if (accepted) lastOwner = listener;
                    return accepted;
                }
                @Override public void cancel(GenerationListener listener) {
                    new android.os.Handler(Looper.getMainLooper()).post(() -> manager.cancel(listener));
                }
            };
            server = new OpenAiServer(new ServerConfig(0, KEY, true, cors), "test-model", false, gateway, null);
            server.start();
        }

        Socket connect() throws Exception {
            Socket socket = new Socket("127.0.0.1", server.getPort());
            socket.setSoTimeout(5000);
            return socket;
        }
        void send(Socket socket, String method, String path, String body, String extra) throws Exception {
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            String request = method + " " + path + " HTTP/1.1\r\nHost: localhost:" + server.getPort() + "\r\n" + extra;
            if (body != null) request += "Content-Type: application/json\r\nContent-Length: " + bytes.length + "\r\n";
            socket.getOutputStream().write((request + "\r\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().write(bytes);
            socket.getOutputStream().flush();
        }
        Response request(String method, String path, String body, String extra) throws Exception {
            try (Socket socket = connect()) { send(socket, method, path, body, extra); return read(socket); }
        }
        Response raw(String request) throws Exception {
            try (Socket socket = connect()) {
                socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
                return read(socket);
            }
        }
        @Override public void close() throws Exception {
            server.close();
            engine.allowFinish.countDown();
            onMain(() -> { manager.close(); return null; });
        }
    }

    private static final class FakeEngine implements InferenceEngine {
        final AtomicInteger loads = new AtomicInteger();
        final AtomicInteger generations = new AtomicInteger();
        volatile CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final CountDownLatch cancelled = new CountDownLatch(1);
        volatile CountDownLatch allowFinish = new CountDownLatch(0);
        volatile GenerationRequest lastRequest;
        volatile boolean loaded;
        volatile boolean fail;

        @Override public GenerationResult load(String model, boolean verbose) {
            if (!loaded) { loaded = true; loads.incrementAndGet(); }
            return new GenerationResult(true, "", "test load");
        }
        @Override public boolean isLoaded() { return loaded; }
        @Override public String getModelSource() { return loaded ? "test-model" : ""; }
        @Override public String getLoadDiagnostics() { return "test load"; }
        @Override public GenerationResult generate(GenerationRequest request, AtomicBoolean cancel) throws Exception { return generate(request, cancel, null); }
        @Override public GenerationResult generate(GenerationRequest request, AtomicBoolean cancel, GenerationListener listener) throws Exception {
            lastRequest = request;
            generations.incrementAndGet();
            if (listener != null) listener.onText("Hello ");
            entered.countDown();
            await(allowFinish);
            if (cancel.get()) return new GenerationResult(false, true, "", "cancelled");
            if (fail) return new GenerationResult(false, "", "test failure");
            if (listener != null) listener.onText("🌍!");
            finished.countDown();
            return new GenerationResult(true, false, "Hello 🌍!", "test result", 12, 4, request.maxOutputTokens == 4);
        }
        @Override public void cancel() { cancelled.countDown(); allowFinish.countDown(); }
        @Override public void unload() { loaded = false; }
    }

    private static final class Response {
        final int status;
        final String headers;
        final String body;
        Response(String response) {
            int end = response.indexOf("\r\n\r\n");
            check(end >= 0, "response missing headers");
            headers = response.substring(0, end);
            status = Integer.parseInt(headers.split(" ", 3)[1]);
            body = response.substring(end + 4);
        }
        JSONObject json() throws Exception { return new JSONObject(body); }
    }

    private static Response read(Socket socket) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = socket.getInputStream().read(buffer)) >= 0) output.write(buffer, 0, count);
        return new Response(output.toString(StandardCharsets.UTF_8.name()));
    }
    private static String auth() { return "Authorization: Bearer " + KEY + "\r\n"; }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void await(CountDownLatch latch) throws InterruptedException { check(latch.await(5, TimeUnit.SECONDS), "timed out waiting for test event"); }
    private <T> T onMain(Callable<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { result.set(action.call()); } catch (Throwable problem) { error.set(problem); }
        });
        if (error.get() != null) throw new AssertionError(error.get());
        return result.get();
    }
    private void waitReady(Fixture fixture) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (onMain(() -> fixture.manager.getState().busy) && System.nanoTime() < deadline) Thread.sleep(10);
        check(!onMain(() -> fixture.manager.getState().busy), "cancelled request never drained");
    }
}
