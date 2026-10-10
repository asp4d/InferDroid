package dev.inferdroid.server;

import android.app.Instrumentation;
import android.os.Handler;
import android.os.Looper;
import dev.inferdroid.engine.WorkGate;
import dev.inferdroid.engine.GenerationListener;
import dev.inferdroid.engine.GenerationRequest;
import dev.inferdroid.engine.EngineManager;
import dev.inferdroid.speech.AudioDecoder;
import dev.inferdroid.tts.*;
import java.io.ByteArrayInputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Actual sockets, PCM/WAV decoding, and controlled TTS lifecycle/ownership. */
public final class TtsTests {
    private static final String KEY = "tts-test-key";
    private static final String REQUEST = "{\"model\":\"tts-1\",\"input\":\"Ciao, città!\",\"voice\":\"F1\",\"language\":\"it\"}";
    private final Instrumentation instrumentation;
    public TtsTests(Instrumentation instrumentation) { this.instrumentation = instrumentation; }
    public void run(String name) throws Exception {
        switch (name) {
            case "ttsValidationAndFormats" -> validation();
            case "ttsBusyAndReuse" -> busy();
            case "ttsDisconnectAndRecovery" -> disconnect();
            case "ttsStopDuringLoadAndImport" -> stopping();
            default -> throw new AssertionError("Unknown TTS test.");
        }
    }
    private void validation() throws Exception {
        try (Harness h = new Harness()) {
            Reply missingKey = request(h.server.getPort(), REQUEST, "");
            check(missingKey.status == 401 && h.engine.loads.get() == 0, "unauthorized synthesis admitted");
            for (String invalid : new String[]{"[]", "{model:'tts-1'}", REQUEST.replace("\"it\"", "\"xx\""),
                    REQUEST.replace("\"F1\"", "{}"), REQUEST.replace("Ciao, città!", "!!!"),
                    REQUEST.substring(0, REQUEST.length()-1) + ",\"speed\":\"1\"}",
                    REQUEST.substring(0, REQUEST.length()-1) + ",\"speed\":0}",
                    REQUEST.substring(0, REQUEST.length()-1) + ",\"speed\":2.1}",
                    REQUEST.substring(0, REQUEST.length()-1) + ",\"response_format\":\"mp3\"}",
                    REQUEST.substring(0, REQUEST.length()-1) + ",\"instructions\":\"whisper\"}",
                    REQUEST.substring(0, REQUEST.length()-1) + ",\"stream_format\":\"sse\"}",
                    REQUEST.substring(0, REQUEST.length()-1) + ",\"voice\":\"M1\"}"}) {
                check(request(h.server.getPort(), invalid, KEY).status == 400, "invalid TTS options accepted");
            }
            check(h.engine.loads.get() == 0, "validation touched the backend");
            check(request(h.server.getPort(), REQUEST.replace("tts-1", "unknown"), KEY).status == 404, "unknown model accepted");
            check(request(h.server.getPort(), REQUEST.replace("Ciao, città!", "x".repeat(4097)), KEY).status == 413, "oversized input accepted");
            h.available = false;
            check(request(h.server.getPort(), REQUEST, KEY).status == 503, "missing TTS bundle not surfaced");
            h.available = true;
            Reply wav = request(h.server.getPort(), REQUEST, KEY);
            check(wav.status == 200 && wav.header.contains("Content-Type: audio/wav"), "WAV response metadata");
            AudioDecoder.Audio decoded = AudioDecoder.decode(new ByteArrayInputStream(wav.body), new AtomicBoolean());
            check(decoded.samples.length == 16000 && Math.abs(decoded.samples[100]-.25f)<.002, "generated WAV headers/PCM corrupted");
            Reply pcm = request(h.server.getPort(), REQUEST.substring(0,REQUEST.length()-1)+",\"response_format\":\"pcm\",\"speed\":1.5}", KEY);
            check(pcm.status == 200 && pcm.header.contains("Content-Type: application/octet-stream") && pcm.body.length == 48000, "raw PCM format");
            check(h.engine.last.language.equals("it") && h.engine.last.text.equals("Ciao, città!") && h.engine.last.speed==1.5f, "Unicode or synthesis options corrupted");
            check(h.engine.loads.get()==1, "TTS model reloaded");
            for (String voice:TtsVoices.NAMES) check(TtsVoices.speaker(voice)==TtsVoices.NAMES.indexOf(voice), "stock voice mapping");
            h.engine.failure = SynthesisResult.Failure.AUDIO_TOO_LARGE;
            check(request(h.server.getPort(), REQUEST, KEY).status == 413, "excessive generated audio not rejected");
            h.engine.failure = SynthesisResult.Failure.NONE;
            check(request(h.server.getPort(), REQUEST, KEY).status == 200 && h.engine.loads.get() == 1,
                    "output-limit failure prevented retained model reuse");
        }
    }
    private void busy() throws Exception {
        try (Harness h = new Harness()) {
            Object other = new Object();
            onMain(() -> h.gate.acquire(other));
            check(request(h.server.getPort(),REQUEST,KEY).status==429, "TTS overlapped another engine");
            onMain(() -> {h.gate.release(other);return null;});
            h.engine.block = true;
            FutureTask<Reply> first = start(h.server.getPort());
            await(h.engine.entered);
            check(request(h.server.getPort(),REQUEST,KEY).status==429, "concurrent TTS accepted");
            check(!onMain(() -> h.gate.acquire(other)), "another engine overlapped TTS");
            h.engine.release.countDown();
            check(first.get(5,TimeUnit.SECONDS).status==200, "first synthesis failed");
            check(request(h.server.getPort(),REQUEST,KEY).status==200 && h.engine.loads.get()==1, "retained TTS engine not reused");
            Reply models = get(h.server.getPort(),"/v1/models");
            check(new String(models.body,StandardCharsets.UTF_8).contains(TtsModelStore.MODEL_ID), "TTS missing from discovery");
        }
    }
    private void disconnect() throws Exception {
        try (Harness h = new Harness()) {
            h.engine.block = true;
            Socket socket = send(h.server.getPort(),REQUEST,KEY);
            await(h.engine.entered);
            SynthesisListener previous=h.owner;
            socket.close();
            await(h.engine.cancelled);
            check(request(h.server.getPort(),REQUEST,KEY).status==429, "cancelled TTS freed its slot before drain");
            h.engine.release.countDown();
            for (int n=0;n<100 && onMain(h.gate::isBusy);n++) Thread.sleep(20);
            check(!onMain(h.gate::isBusy), "cancelled worker did not drain");
            h.engine.reset();
            FutureTask<Reply> next=start(h.server.getPort());
            await(h.engine.entered);
            onMain(() -> { h.manager.cancel(previous);return null; });
            check(!h.engine.token.get(), "stale request cancelled a successor");
            h.engine.release.countDown();
            check(next.get(5,TimeUnit.SECONDS).status==200 && h.engine.loads.get()==1, "disconnect recovery reloaded/failed");
        }
    }
    private void stopping() throws Exception {
        try (Harness h = new Harness()) {
            h.engine.blockLoad = true;
            onMain(h.manager::load);
            await(h.engine.loadEntered);
            CountDownLatch stopped=new CountDownLatch(1);
            onMain(() -> {h.manager.unload(stopped::countDown);return null;});
            check(onMain(h.gate::isBusy) && h.engine.unloads.get()==0, "unload raced model initialization");
            h.engine.loadRelease.countDown();
            await(stopped);
            check(!h.engine.isLoaded() && !onMain(h.gate::isBusy), "load-stop cleanup failed");
            CountDownLatch importing=new CountDownLatch(1), importCancelled=new CountDownLatch(1), release=new CountDownLatch(1);
            onMain(() -> h.manager.importModel(token -> {
                importing.countDown();
                while(!token.get()) Thread.sleep(10);
                importCancelled.countDown();
                release.await(5,TimeUnit.SECONDS);
            }));
            await(importing);
            CountDownLatch importedStopped=new CountDownLatch(1);
            onMain(() -> {h.manager.unload(importedStopped::countDown);return null;});
            await(importCancelled);
            check(onMain(h.gate::isBusy), "import-stop released slot early");
            release.countDown();await(importedStopped);
            check(!onMain(h.gate::isBusy), "import-stop failed to release slot");
        }
    }
    private final class Harness implements AutoCloseable {
        final WorkGate gate=new WorkGate();
        final FakeEngine engine=new FakeEngine();
        final TtsManager manager;
        final OpenAiServer server;
        volatile boolean available=true;
        volatile SynthesisListener owner;
        Harness() throws Exception {
            manager=onMain(() -> new TtsManager(engine,new EngineManager.WorkGuard(){
                public void begin(){check(Looper.myLooper()!=Looper.getMainLooper(),"TTS work on main");}
                public void end(){}
            },gate));
            ChatGateway chat=new ChatGateway(){
                public boolean generate(GenerationRequest r,GenerationListener l){return false;}
                public void cancel(GenerationListener l){}
            };
            SynthesisGateway tts=new SynthesisGateway(){
                public boolean isAvailable(){return available;}
                public boolean synthesize(SynthesisRequest r,SynthesisListener l) throws Exception {owner=l;return onMain(() -> manager.synthesize(r,l));}
                public void cancel(SynthesisListener l){new Handler(Looper.getMainLooper()).post(() -> manager.cancel(l));}
            };
            server=new OpenAiServer(new ServerConfig(0,KEY,true,true),"",false,chat,null,tts,null);
            server.start();
        }
        public void close() throws Exception {server.close();onMain(() -> {manager.close();return null;});}
    }
    private static final class FakeEngine implements TtsEngine {
        volatile boolean loaded,block,blockLoad;
        volatile SynthesisRequest last;
        volatile SynthesisResult.Failure failure = SynthesisResult.Failure.NONE;
        volatile AtomicBoolean token;
        final AtomicInteger loads=new AtomicInteger(),unloads=new AtomicInteger();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),cancelled=new CountDownLatch(1);
        final CountDownLatch loadEntered=new CountDownLatch(1),loadRelease=new CountDownLatch(1);
        public boolean isLoaded(){return loaded;}
        public void load() throws Exception {if(loaded)return;loads.incrementAndGet();loadEntered.countDown();if(blockLoad)await(loadRelease);loaded=true;}
        public SynthesisResult synthesize(SynthesisRequest r,AtomicBoolean t) throws Exception {
            last=r;token=t;entered.countDown();
            if(block)while(!release.await(10,TimeUnit.MILLISECONDS))if(t.get())cancelled.countDown();
            if (failure != SynthesisResult.Failure.NONE) return SynthesisResult.failure(failure, "Test output limit.");
            byte[] pcm=new byte[48000];for(int i=0;i<pcm.length;i+=2){pcm[i]=0;pcm[i+1]=32;}
            return new SynthesisResult(new PcmAudio(pcm),"Test CPU audio");
        }
        public void unload(){loaded=false;unloads.incrementAndGet();}
        void reset(){entered=new CountDownLatch(1);release=new CountDownLatch(1);cancelled=new CountDownLatch(1);}
    }
    private static FutureTask<Reply> start(int port){FutureTask<Reply> task=new FutureTask<>(() -> request(port,REQUEST,KEY));new Thread(task).start();return task;}
    private record Reply(int status,String header,byte[] body){}
    private static Socket send(int port,String json,String key) throws Exception {
        Socket socket=new Socket("127.0.0.1",port);socket.setSoTimeout(5000);
        byte[] body=json.getBytes(StandardCharsets.UTF_8);
        String header="POST /v1/audio/speech HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nAuthorization: Bearer "+key+"\r\n\r\n";
        socket.getOutputStream().write(header.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(body);socket.getOutputStream().flush();return socket;
    }
    private static Reply request(int port,String json,String key) throws Exception {try(Socket s=send(port,json,key)){return read(s);}}
    private static Reply get(int port,String path) throws Exception {try(Socket s=new Socket("127.0.0.1",port)){
        s.setSoTimeout(5000);s.getOutputStream().write(("GET "+path+" HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "+KEY+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));return read(s);
    }}
    private static Reply read(Socket s) throws Exception {
        byte[] wire=s.getInputStream().readAllBytes();
        for(int i=0;i<wire.length-3;i++)if(wire[i]==13&&wire[i+1]==10&&wire[i+2]==13&&wire[i+3]==10){
            String header=new String(wire,0,i,StandardCharsets.US_ASCII);
            return new Reply(Integer.parseInt(header.split(" ")[1]),header,Arrays.copyOfRange(wire,i+4,wire.length));
        }
        throw new AssertionError("Missing HTTP response");
    }
    private <T>T onMain(Callable<T> task) throws Exception {
        AtomicReference<T> result=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {try{result.set(task.call());}catch(Throwable e){error.set(e);}});
        if(error.get()!=null)throw new AssertionError(error.get());return result.get();
    }
    private static void await(CountDownLatch latch) throws Exception {check(latch.await(5,TimeUnit.SECONDS),"TTS test timed out");}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
