package dev.inferdroid.server;

import android.app.Instrumentation;
import android.os.Handler;
import android.os.Looper;
import dev.inferdroid.engine.EngineManager;
import dev.inferdroid.engine.GenerationListener;
import dev.inferdroid.engine.GenerationRequest;
import dev.inferdroid.engine.GenerationResult;
import dev.inferdroid.engine.InferenceEngine;
import dev.inferdroid.engine.WorkGate;
import dev.inferdroid.speech.AudioDecoder;
import dev.inferdroid.speech.SpeechEngine;
import dev.inferdroid.speech.SpeechManager;
import dev.inferdroid.speech.SpeechModelStore;
import dev.inferdroid.speech.TranscriptionListener;
import dev.inferdroid.speech.TranscriptionRequest;
import dev.inferdroid.speech.TranscriptionResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Real HTTP sockets, audio decoder, and shared main/worker lifecycles; ASR timing is controlled. */
public final class SpeechTests {
    private static final String KEY = "speech-test-key";
    private static final String BOUNDARY = "inferdroid-audio-test";
    private final Instrumentation instrumentation;
    public SpeechTests(Instrumentation instrumentation) { this.instrumentation = instrumentation; }
    public void run(String name) throws Exception {
        switch (name) {
            case "audioDecodeBoundsAndFormats" -> decode();
            case "audioMultipartValidation" -> validation();
            case "audioResponsesAndModelDiscovery" -> responses();
            case "audioChatContention" -> contention();
            case "audioDisconnectCancelAndRecovery" -> disconnect();
            case "audioStopDuringLoadAndImport" -> stopping();
            default -> throw new AssertionError("Unknown speech test.");
        }
    }
    private void decode() throws Exception {
        AtomicBoolean cancel = new AtomicBoolean();
        AudioDecoder.Audio stereo = AudioDecoder.decode(new ByteArrayInputStream(wav(8000, 2, 8000, false)), cancel);
        check(stereo.samples.length == 16000, "8 kHz resampling duration");
        check(Math.abs(stereo.samples[5000] - 0.25f) < 0.001, "stereo downmix was corrupted");
        AudioDecoder.Audio floating = AudioDecoder.decode(new ByteArrayInputStream(wav(48000, 1, 48000, true)), cancel);
        check(floating.samples.length == 16000 && Math.abs(floating.samples[100] - 0.25f) < 0.001, "float32 WAV conversion");
        byte[] classic = wav(16000, 1, 16000, true);
        ByteBuffer extensible = ByteBuffer.allocate(classic.length + 24).order(ByteOrder.LITTLE_ENDIAN);
        extensible.put(classic, 0, 36);
        extensible.putInt(4, extensible.capacity() - 8).putInt(16, 40).putShort(20, (short) 0xfffe);
        extensible.putShort((short) 22).putShort((short) 32).putInt(0).putInt(3);
        extensible.put(new byte[]{0, 0, 16, 0, (byte) 128, 0, 0, (byte) 170, 0, 56, (byte) 155, 113});
        extensible.put(classic, 36, classic.length - 36);
        check(AudioDecoder.decode(new ByteArrayInputStream(extensible.array()), cancel).samples.length == 16000, "extensible float WAV rejected");
        byte[] malformed = wav(16000, 1, 100, false);
        malformed[4]--;
        invalidAudio(malformed, false);
        invalidAudio(new byte[]{1, 2, 3}, false);
        byte[] nan = wav(16000, 1, 1, true);
        ByteBuffer.wrap(nan).order(ByteOrder.LITTLE_ENDIAN).putFloat(44, Float.NaN);
        invalidAudio(nan, false);
        invalidAudio(wav(8000, 1, 121 * 8000, false), true);
        cancel.set(true);
        try { AudioDecoder.decode(new ByteArrayInputStream(wav(16000, 1, 100, false)), cancel); throw new AssertionError("cancel ignored"); }
        catch (AudioDecoder.AudioException expected) { }
    }
    private static void invalidAudio(byte[] bytes, boolean tooLarge) throws Exception {
        try { AudioDecoder.decode(new ByteArrayInputStream(bytes), new AtomicBoolean()); throw new AssertionError("invalid audio accepted"); }
        catch (AudioDecoder.AudioException expected) { check(expected.tooLarge == tooLarge, "incorrect audio error classification"); }
    }
    private void validation() throws Exception {
        try (Fixture f = new Fixture()) {
            byte[] good = upload("", wav(16000, 1, 160, false));
            check(f.request(good, "", type()).status == 401, "audio bypassed authentication");
            check(f.request(good, auth(), "application/json").status == 415, "wrong audio media type accepted");
            check(f.request(good, auth(), "multipart/form-data").status == 400, "missing boundary accepted");
            check(f.request(new byte[0], auth(), type()).status == 400, "empty multipart accepted");
            check(f.request(upload(field("model", SpeechModelStore.MODEL_ID), wav(16000, 1, 160, false)), auth(), type()).status == 400, "duplicate field accepted");
            check(f.request(upload(field("language", "xx"), wav(16000, 1, 160, false)), auth(), type()).status == 400, "unsupported language accepted");
            for (String option : new String[]{field("temperature", "0.2"), field("temperature", "NaN"), field("stream", "true"), field("prompt", "hints"), field("response_format", "srt"), field("timestamp_granularities[]", "word")}) {
                check(f.request(upload(option, wav(16000, 1, 160, false)), auth(), type()).status == 400, "unsupported transcription option accepted");
            }
            byte[] unknown = new String(good, StandardCharsets.ISO_8859_1).replace(SpeechModelStore.MODEL_ID, "unknown").getBytes(StandardCharsets.ISO_8859_1);
            check(f.request(unknown, auth(), type()).status == 404, "unknown speech model accepted");
            check(f.raw("POST /v1/audio/transcriptions HTTP/1.1\r\nHost: localhost\r\n" + auth() + "Content-Type: " + type() + "\r\nContent-Length: 99999999\r\n\r\n").status == 413, "oversize audio allocation allowed");
            check(f.engine.requests.get() == 0 && f.engine.loads.get() == 0, "invalid multipart touched speech engine");
            check(f.request(upload("", new byte[]{1, 2, 3}), auth(), type()).status == 400, "malformed audio error");
            check(f.request(upload("", wav(8000, 1, 121 * 8000, false)), auth(), type()).status == 413, "audio duration cap not enforced");
        }
    }
    private void responses() throws Exception {
        try (Fixture f = new Fixture()) {
            Response models = f.raw("GET /v1/models HTTP/1.1\r\nHost: localhost\r\n" + auth() + "\r\n");
            check(models.json().getJSONArray("data").length() == 2, "speech model missing from discovery");
            byte[] audio = wav(16000, 1, 16000, false);
            Response json = f.request(upload(field("language", "it") + field("temperature", "0"), audio), auth(), type());
            check(json.status == 200 && json.json().getString("text").equals("Ciao 🌍, perché sì."), "UTF-8 transcription JSON");
            check(f.engine.language.equals("it"), "language hint ignored");
            Response text = f.request(upload(field("response_format", "text"), audio), auth(), type());
            check(text.status == 200 && text.headers.contains("text/plain") && text.body.equals("Ciao 🌍, perché sì."), "plain text response");
            Response verbose = f.request(upload(field("response_format", "verbose_json"), audio), auth(), type());
            check(verbose.json().getDouble("duration") == 1 && verbose.json().getString("language").equals("it"), "verbose audio metadata");
            check(f.engine.loads.get() == 1, "speech model reloaded between requests");
            f.available = false;
            check(f.request(upload("", audio), auth(), type()).status == 503, "missing model not explained");
            check(f.raw("GET /v1/models HTTP/1.1\r\nHost: localhost\r\n" + auth() + "\r\n").json().getJSONArray("data").length() == 1, "unavailable speech model advertised");
        }
    }
    private void contention() throws Exception {
        try (Fixture f = new Fixture()) {
            f.engine.release = new CountDownLatch(1);
            try (Socket held = f.connect()) {
                f.send(held, upload("", wav(16000, 1, 16000, false)), auth(), type());
                await(f.engine.entered);
                check(f.request(upload("", wav(16000, 1, 100, false)), auth(), type()).status == 429, "overlapping speech accepted");
                GenerationRequest chat = new GenerationRequest("test-model", "Hello", false);
                check(!main(() -> f.chat.generate(chat)), "chat bypassed shared speech gate");
                String chatJson = "{\"model\":\"" + OpenAiServer.MODEL_ID + "\",\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}]}";
                check(f.raw("POST /v1/chat/completions HTTP/1.1\r\nHost: localhost\r\n" + auth() + "Content-Type: application/json\r\nContent-Length: " + chatJson.length() + "\r\n\r\n" + chatJson).status == 429, "HTTP chat bypassed speech gate");
                f.engine.release.countDown();
                check(read(held).status == 200, "held speech failed");
                f.chatEngine.release = new CountDownLatch(1);
                check(main(() -> f.chat.generate(chat)), "chat failed after speech");
                await(f.chatEngine.entered);
                check(f.request(upload("", wav(16000, 1, 100, false)), auth(), type()).status == 429, "speech bypassed chat gate");
                f.chatEngine.release.countDown();
            }
        }
    }
    private void disconnect() throws Exception {
        try (Fixture f = new Fixture()) {
            f.engine.release = new CountDownLatch(1);
            Socket abandoned = f.connect();
            f.send(abandoned, upload("", wav(16000, 1, 16000, false)), auth(), type());
            await(f.engine.entered);
            TranscriptionListener previous = f.owner;
            abandoned.close();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!f.engine.token.get() && System.nanoTime() < deadline) Thread.sleep(10);
            check(f.engine.token.get(), "disconnect did not cancel owned speech");
            check(main(() -> f.speech.getState().busy), "native speech slot freed before decode drained");
            check(f.request(upload("", wav(16000, 1, 100, false)), auth(), type()).status == 429, "new work admitted before cancel drained");
            f.engine.release.countDown();
            waitIdle(f.speech);
            f.engine.entered = new CountDownLatch(1);
            f.engine.release = new CountDownLatch(1);
            try (Socket next = f.connect()) {
                f.send(next, upload("", wav(16000, 1, 100, false)), auth(), type());
                await(f.engine.entered);
                main(() -> { f.speech.cancel(previous); return null; });
                check(!f.engine.token.get(), "stale owner cancelled another request");
                f.engine.release.countDown();
                check(read(next).status == 200, "speech did not recover after cancel");
            }
            check(f.engine.loads.get() == 1, "cancel recovery reloaded speech model");
        }
    }
    private void stopping() throws Exception {
        try (Fixture f = new Fixture()) {
            f.engine.loadRelease = new CountDownLatch(1);
            try (Socket held = f.connect()) {
                f.send(held, upload("", wav(16000, 1, 100, false)), auth(), type());
                await(f.engine.loadEntered);
                CountDownLatch unloaded = new CountDownLatch(1);
                f.server.close();
                main(() -> { f.speech.unload(unloaded::countDown); return null; });
                check(main(() -> f.speech.getState().phase) == SpeechManager.Phase.STOPPING && f.engine.unloads.get() == 0, "speech freed during load");
                f.engine.loadRelease.countDown();
                await(unloaded);
                check(f.engine.requests.get() == 0 && !f.engine.loaded, "pending ASR ran after stop");
            }
            CountDownLatch importing = new CountDownLatch(1), release = new CountDownLatch(1);
            check(main(() -> f.speech.importModel(token -> { importing.countDown(); await(release); })), "import rejected");
            await(importing);
            check(!main(() -> f.chat.load("test-model", false)), "chat overlapped import");
            CountDownLatch stopped = new CountDownLatch(1);
            main(() -> { f.speech.unload(stopped::countDown); return null; });
            release.countDown();
            await(stopped);
            check(!main(() -> f.speech.getState().busy), "import stop never drained");
        }
    }
    private final class Fixture implements AutoCloseable {
        final FakeSpeech engine = new FakeSpeech();
        final FakeChat chatEngine = new FakeChat();
        final SpeechManager speech;
        final EngineManager chat;
        final OpenAiServer server;
        volatile boolean available = true;
        volatile TranscriptionListener owner;
        Fixture() throws Exception {
            WorkGate gate = new WorkGate();
            EngineManager.WorkGuard guard = new EngineManager.WorkGuard() {
                @Override public void begin() { check(Looper.myLooper() != Looper.getMainLooper(), "audio on main"); }
                @Override public void end() { }
            };
            speech = main(() -> new SpeechManager(engine, guard, gate));
            chat = main(() -> new EngineManager(chatEngine, guard, gate));
            server = new OpenAiServer(new ServerConfig(0, KEY, true, false), "test-model", false, new ChatGateway() {
                @Override public boolean generate(GenerationRequest request, GenerationListener listener) throws Exception { return main(() -> chat.generate(request, listener)); }
                @Override public void cancel(GenerationListener listener) { new Handler(Looper.getMainLooper()).post(() -> chat.cancel(listener)); }
            }, new TranscriptionGateway() {
                @Override public boolean isAvailable() { return available; }
                @Override public boolean transcribe(TranscriptionRequest request, TranscriptionListener listener) throws Exception {
                    owner = listener; return main(() -> speech.transcribe(request, listener));
                }
                @Override public void cancel(TranscriptionListener listener) { new Handler(Looper.getMainLooper()).post(() -> speech.cancel(listener)); }
            }, null);
            server.start();
        }
        Socket connect() throws Exception { Socket socket = new Socket("127.0.0.1", server.getPort()); socket.setSoTimeout(5000); return socket; }
        void send(Socket socket, byte[] bytes, String auth, String type) throws Exception {
            String header = "POST /v1/audio/transcriptions HTTP/1.1\r\nHost: localhost\r\n" + auth
                    + "Content-Type: " + type + "\r\nContent-Length: " + bytes.length + "\r\n\r\n";
            socket.getOutputStream().write(header.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(bytes); socket.getOutputStream().flush();
        }
        Response request(byte[] bytes, String auth, String type) throws Exception {
            try (Socket socket = connect()) { send(socket, bytes, auth, type); return read(socket); }
        }
        Response raw(String request) throws Exception {
            try (Socket socket = connect()) { socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8)); socket.getOutputStream().flush(); return read(socket); }
        }
        @Override public void close() throws Exception {
            server.close(); engine.release.countDown(); engine.loadRelease.countDown(); chatEngine.release.countDown();
            main(() -> { speech.close(); chat.close(); return null; });
        }
    }
    private static final class FakeSpeech implements SpeechEngine {
        final AtomicInteger loads = new AtomicInteger(), requests = new AtomicInteger(), unloads = new AtomicInteger();
        final CountDownLatch loadEntered = new CountDownLatch(1);
        volatile CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(0), loadRelease = new CountDownLatch(0);
        volatile boolean loaded;
        volatile AtomicBoolean token;
        volatile String language;
        @Override public boolean isLoaded() { return loaded; }
        @Override public void load() throws Exception { if (!loaded) { loadEntered.countDown(); await(loadRelease); loaded = true; loads.incrementAndGet(); } }
        @Override public TranscriptionResult transcribe(TranscriptionRequest request, AtomicBoolean cancel) throws Exception {
            token = cancel; language = request.language;
            AudioDecoder.Audio audio;
            try (var input = request.audio.open()) { audio = AudioDecoder.decode(input, cancel); }
            requests.incrementAndGet(); entered.countDown(); await(release);
            return new TranscriptionResult("Ciao 🌍, perché sì.", "it", audio.durationSeconds(), "test CPU");
        }
        @Override public void unload() { loaded = false; unloads.incrementAndGet(); }
    }
    private static final class FakeChat implements InferenceEngine {
        volatile boolean loaded;
        volatile CountDownLatch release = new CountDownLatch(0);
        final CountDownLatch entered = new CountDownLatch(1);
        @Override public GenerationResult load(String source, boolean verbose) { loaded = true; return new GenerationResult(true, "", "test"); }
        @Override public boolean isLoaded() { return loaded; }
        @Override public String getModelSource() { return "test-model"; }
        @Override public String getLoadDiagnostics() { return "test"; }
        @Override public GenerationResult generate(GenerationRequest request, AtomicBoolean cancel) throws Exception { entered.countDown(); await(release); return new GenerationResult(true, "hello", "test"); }
        @Override public void cancel() { release.countDown(); }
        @Override public void unload() { loaded = false; }
    }
    private static byte[] wav(int rate, int channels, int frames, boolean floating) {
        int width = floating ? 4 : 2;
        ByteBuffer bytes = ByteBuffer.allocate(44 + frames * channels * width).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(bytes.capacity() - 8).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        bytes.putInt(16).putShort((short) (floating ? 3 : 1)).putShort((short) channels).putInt(rate).putInt(rate * channels * width);
        bytes.putShort((short) (channels * width)).putShort((short) (width * 8)).put("data".getBytes(StandardCharsets.US_ASCII)).putInt(frames * channels * width);
        for (int i = 0; i < frames * channels; i++) { if (floating) bytes.putFloat(0.25f); else bytes.putShort((short) 8192); }
        return bytes.array();
    }
    private static String field(String name, String value) { return "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n"; }
    private static byte[] upload(String options, byte[] audio) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write((field("model", SpeechModelStore.MODEL_ID) + options + "--" + BOUNDARY
                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"../../ignored.wav\"\r\nContent-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(audio); output.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII)); return output.toByteArray();
    }
    private static String type() { return "multipart/form-data; boundary=\"" + BOUNDARY + "\""; }
    private static String auth() { return "Authorization: Bearer " + KEY + "\r\n"; }
    private static final class Response {
        final int status;
        final String headers, body;
        Response(String text) { int end = text.indexOf("\r\n\r\n"); check(end > 0, "missing HTTP response"); headers = text.substring(0, end); body = text.substring(end + 4); status = Integer.parseInt(headers.split(" ", 3)[1]); }
        JSONObject json() throws Exception { return new JSONObject(body); }
    }
    private static Response read(Socket socket) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
        while ((count = socket.getInputStream().read(buffer)) >= 0) output.write(buffer, 0, count);
        return new Response(output.toString(StandardCharsets.UTF_8.name()));
    }
    private <T> T main(Callable<T> action) throws Exception {
        AtomicReference<T> value = new AtomicReference<>(); AtomicReference<Throwable> error = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> { try { value.set(action.call()); } catch (Throwable failure) { error.set(failure); } });
        if (error.get() != null) throw new AssertionError(error.get()); return value.get();
    }
    private void waitIdle(SpeechManager manager) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (main(() -> manager.getState().busy) && System.nanoTime() < deadline) Thread.sleep(10);
        check(!main(() -> manager.getState().busy), "speech never drained");
    }
    private static void await(CountDownLatch latch) throws InterruptedException { check(latch.await(5, TimeUnit.SECONDS), "speech test timed out"); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
