package dev.inferdroid.images;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import dev.inferdroid.engine.GenerationListener;
import dev.inferdroid.engine.GenerationRequest;
import dev.inferdroid.engine.WorkGate;
import dev.inferdroid.server.ChatGateway;
import dev.inferdroid.server.ImageGateway;
import dev.inferdroid.server.OpenAiServer;
import dev.inferdroid.server.ServerConfig;
import java.io.File;
import java.io.FileOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real loopback transport and shared admission, with a PNG fixture and no model weights. */
public final class ImageApiTests {
    private static final String KEY = "image-api-test-key";
    private static final String PATH = "/v1/images/generations";
    private static final String REQUEST = "{\"prompt\":\"A red robot 🌻\",\"seed\":0}";
    private final Instrumentation instrumentation;

    public ImageApiTests(Instrumentation instrumentation) { this.instrumentation = instrumentation; }

    public void run(String name) throws Exception {
        switch (name) {
            case "imageGenerationValidationAndDiscovery" -> validationAndDiscovery();
            case "imageGenerationResponseAndDefaults" -> responseAndDefaults();
            case "imageGenerationSharedAdmission" -> sharedAdmission();
            case "imageGenerationCancellationAndRecovery" -> cancellationAndRecovery();
            default -> throw new AssertionError("Unknown image generation test: " + name);
        }
    }

    private void validationAndDiscovery() throws Exception {
        try (Harness h = new Harness()) {
            check(h.request("POST", PATH, REQUEST, false, "").status == 401, "unauthenticated image request admitted");
            for (String body : new String[]{"[]", "null", "1", "\"text\"", "", "{prompt:'x'}",
                    "{\"prompt\":\"x\",\"prompt\":\"y\"}", "{} {}"}) {
                Reply reply = h.post(body);
                check(reply.status == 400 && reply.json().has("error"), "malformed image request did not return 400: " + body);
            }
            for (Object[] invalid : new Object[][]{
                    {"prompt", ""}, {"prompt", "  "}, {"prompt", JSONObject.NULL}, {"prompt", 1},
                    {"prompt", "x\0y"}, {"prompt", "x".repeat(8193)}, {"model", 1},
                    {"n", 0}, {"n", 2}, {"n", "1"}, {"size", "1024x1024"}, {"size", 512},
                    {"response_format", "url"}, {"quality", "high"}, {"output_format", "jpeg"},
                    {"background", "transparent"}, {"stream", true}, {"stream", "false"},
                    {"seed", 1.5}, {"seed", "0"}, {"seed", true}, {"user", new JSONObject()},
                    {"negative_prompt", "blur"}}) {
                Reply reply = h.post(new JSONObject(REQUEST).put((String) invalid[0], invalid[1]).toString());
                check(reply.status == 400 && reply.json().getJSONObject("error").getString("param").equals(invalid[0]),
                        "unsupported image parameter accepted or misidentified: " + invalid[0]);
            }
            check(h.post("{\"prompt\":\"x\",\"seed\":9223372036854775808}").status == 400, "overflowing seed accepted");
            Reply unknown = h.post(new JSONObject(REQUEST).put("model", "dall-e-3").toString());
            check(unknown.status == 404 && unknown.json().getJSONObject("error").getString("code").equals("model_not_found"),
                    "unknown image model accepted");
            check(h.generations == 0, "validation touched image inference");
            Reply method = h.request("GET", PATH, null, true, "");
            check(method.status == 405 && method.json().getJSONObject("error").getString("message").contains(PATH),
                    "image method guidance missing");
            Reply preflight = h.request("OPTIONS", PATH, null, false, "Origin: https://example.test\r\n");
            check(preflight.status == 204 && preflight.headers.contains("Access-Control-Allow-Origin: *"), "image CORS preflight failed");
            JSONArray models = h.request("GET", "/v1/models", null, true, "").json().getJSONArray("data");
            check(models.length() == 1 && models.getJSONObject(0).getString("id").equals(ImageModels.MODEL_ID),
                    "image-only discovery failed");
            check(models.getJSONObject(0).getJSONObject("architecture").getJSONArray("output_modalities").getString(0).equals("image"),
                    "image model capability missing");
            h.available = false;
            check(h.request("GET", "/v1/models", null, true, "").json().getJSONArray("data").length() == 0,
                    "missing image model advertised");
            Reply unavailable = h.post(REQUEST);
            check(unavailable.status == 503 && unavailable.json().getJSONObject("error").getString("code").equals("image_model_unavailable"),
                    "missing image model error changed");
            check(h.generations == 0, "unavailable model touched image inference");
        }
    }

    private void responseAndDefaults() throws Exception {
        try (Harness h = new Harness()) {
            assertImage(h.post(REQUEST));
            check(h.prompt.equals("A red robot 🌻") && h.seed == 0, "prompt or seed changed");
            JSONObject options = new JSONObject(REQUEST).put("model", ImageModels.MODEL_ID).put("n", 1)
                    .put("size", "auto").put("quality", "auto").put("output_format", "png")
                    .put("background", "auto").put("response_format", "b64_json").put("stream", false).put("user", "local-client");
            for (long seed : new long[]{Long.MIN_VALUE, Long.MAX_VALUE}) {
                assertImage(h.post(options.put("seed", seed).toString()));
                check(h.seed == seed, "signed 64-bit seed lost precision");
            }
            options.put("size", "512x512").put("quality", "standard").put("background", "opaque");
            assertImage(h.request("POST", "/v1/v1/images/generations", options.toString(), true, "Origin: https://example.test\r\n"));
            JSONObject defaults = new JSONObject().put("prompt", "Default options");
            for (String key : new String[]{"model", "n", "size", "quality", "output_format", "background", "response_format", "stream", "user", "seed"}) {
                defaults.put(key, JSONObject.NULL);
            }
            assertImage(h.post(defaults.toString()));
            assertImage(h.post("{\"prompt\":\"Random seed\"}"));
            h.failure = "Controlled image failure";
            Reply failure = h.post(REQUEST);
            check(failure.status == 503 && failure.json().getJSONObject("error").getString("code").equals("image_generation_failed"),
                    "image failure returned as success");
            h.failure = null;
            assertImage(h.post(REQUEST));
        }
    }

    private void sharedAdmission() throws Exception {
        try (Harness h = new Harness()) {
            Object other = new Object();
            onMain(() -> h.gate.acquire(other));
            check(h.post(REQUEST).status == 429, "image generation overlapped another engine");
            onMain(() -> { h.gate.release(other); return null; });
            h.block = true;
            FutureTask<Reply> first = h.start();
            await(h.entered);
            check(h.post(REQUEST).status == 429, "concurrent image generation admitted");
            check(!onMain(() -> h.gate.acquire(other)), "another engine overlapped image generation");
            onMain(() -> { h.finish(false); return null; });
            assertImage(first.get(5, TimeUnit.SECONDS));
            h.block = false;
            assertImage(h.post(REQUEST));
        }
    }

    private void cancellationAndRecovery() throws Exception {
        try (Harness h = new Harness()) {
            h.block = true;
            Socket socket = h.send("POST", PATH, REQUEST, true, "");
            await(h.entered);
            ImageController.Callback previous = h.owner;
            socket.close();
            await(h.cancelled);
            check(h.post(REQUEST).status == 429, "disconnect released image admission before worker drain");
            onMain(() -> { h.finish(true); return null; });
            h.entered = new CountDownLatch(1);
            h.cancelled = new CountDownLatch(1);
            FutureTask<Reply> next = h.start();
            await(h.entered);
            onMain(() -> { h.images.cancel(previous); return null; });
            check(h.cancelled.getCount() == 1 && onMain(h.gate::isBusy), "old image owner cancelled a later request");
            onMain(() -> { h.finish(false); return null; });
            assertImage(next.get(5, TimeUnit.SECONDS));
            h.entered = new CountDownLatch(1);
            try (Socket stopped = h.send("POST", PATH, REQUEST, true, "")) {
                await(h.entered);
                h.server.close();
                await(h.cancelled);
                check(onMain(h.gate::isBusy), "server stop released image admission before drain");
                onMain(() -> { h.finish(true); return null; });
                check(stopped.getInputStream().read() == -1, "server stop left image connection open");
            }
            check(!onMain(h.gate::isBusy), "stopped image request retained admission");
        }
    }

    private void assertImage(Reply reply) throws Exception {
        check(reply.status == 200 && reply.headers.contains("Content-Type: application/json"), "image response failed: " + reply.body);
        JSONObject response = reply.json();
        JSONArray data = response.getJSONArray("data");
        check(response.getLong("created") > 0 && data.length() == 1 && !data.getJSONObject(0).has("url"), "image response shape changed");
        byte[] png = Base64.decode(data.getJSONObject(0).getString("b64_json"), Base64.DEFAULT);
        Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length);
        check(bitmap != null && bitmap.getWidth() == 8 && bitmap.getHeight() == 8, "base64 PNG corrupted");
        bitmap.recycle();
    }

    private final class Harness implements AutoCloseable {
        final WorkGate gate = new WorkGate();
        final OpenAiServer server;
        final File png;
        volatile boolean available = true, block;
        volatile String prompt, failure;
        volatile long seed;
        volatile int generations;
        volatile ImageController.Callback owner;
        volatile CountDownLatch entered = new CountDownLatch(1), cancelled = new CountDownLatch(1);
        final ImageGateway images = new ImageGateway() {
            public boolean isAvailable() { return available; }
            public boolean generate(String prompt, long seed, ImageController.Callback callback) throws Exception {
                return onMain(() -> {
                    if (!gate.acquire(this)) return false;
                    Harness.this.prompt = prompt; Harness.this.seed = seed; owner = callback; generations++;
                    entered.countDown();
                    if (!block) finish(false);
                    return true;
                });
            }
            public void cancel(ImageController.Callback callback) {
                new Handler(Looper.getMainLooper()).post(() -> { if (owner == callback) cancelled.countDown(); });
            }
        };
        Harness() throws Exception {
            png = File.createTempFile("image-api-", ".png", instrumentation.getTargetContext().getCacheDir());
            Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
            try (FileOutputStream output = new FileOutputStream(png)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); }
            finally { bitmap.recycle(); }
            ChatGateway chat = new ChatGateway() {
                public boolean generate(GenerationRequest request, GenerationListener listener) { throw new AssertionError("image routed to chat"); }
                public void cancel(GenerationListener listener) { }
            };
            server = new OpenAiServer(new ServerConfig(0, KEY, true, true), "", false, chat, null);
            server.setImages(images);
            server.start();
        }
        void finish(boolean wasCancelled) {
            ImageController.Callback callback = owner;
            owner = null;
            gate.release(images);
            if (callback != null) callback.complete(new ImageController.Result(failure == null && !wasCancelled ? png : null,
                    wasCancelled ? "Cancelled" : failure, wasCancelled));
        }
        Reply post(String body) throws Exception { return request("POST", PATH, body, true, ""); }
        FutureTask<Reply> start() {
            FutureTask<Reply> task = new FutureTask<>(() -> post(REQUEST));
            new Thread(task, "image-api-client").start();
            return task;
        }
        Socket send(String method, String path, String body, boolean auth, String extraHeaders) throws Exception {
            Socket socket = new Socket("127.0.0.1", server.getPort());
            socket.setSoTimeout(5000);
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            String headers = method + " " + path + " HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: "
                    + bytes.length + "\r\n" + (auth ? "Authorization: Bearer " + KEY + "\r\n" : "") + extraHeaders + "\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(bytes); socket.getOutputStream().flush();
            return socket;
        }
        Reply request(String method, String path, String body, boolean auth, String extraHeaders) throws Exception {
            try (Socket socket = send(method, path, body, auth, extraHeaders)) {
                String wire = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int split = wire.indexOf("\r\n\r\n");
                check(split >= 0, "missing image HTTP response");
                return new Reply(Integer.parseInt(wire.split(" ", 3)[1]), wire.substring(0, split), wire.substring(split + 4));
            }
        }
        public void close() throws Exception {
            server.close();
            onMain(() -> { finish(true); return null; });
            png.delete();
        }
    }

    private record Reply(int status, String headers, String body) {
        JSONObject json() throws Exception { return new JSONObject(body); }
    }
    private <T> T onMain(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        instrumentation.runOnMainSync(task);
        return task.get(5, TimeUnit.SECONDS);
    }
    private static void await(CountDownLatch latch) throws Exception { check(latch.await(5, TimeUnit.SECONDS), "image API test timed out"); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
