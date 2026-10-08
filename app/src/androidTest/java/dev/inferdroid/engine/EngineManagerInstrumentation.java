package dev.inferdroid.engine;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.Looper;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Headless platform-SDK tests: no test libraries, model, Activity, or JNI required. */
public final class EngineManagerInstrumentation extends Instrumentation {
    private final GenerationRequest request = new GenerationRequest("test-model", "Hello", false);
    private final EngineManager.WorkGuard guard = new EngineManager.WorkGuard() {
        @Override public void begin() { offMain(); }
        @Override public void end() { offMain(); }
    };

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        int failures = 0;
        String[] names = {"busyAndReuse", "cancelAndReuse", "stopDuringLoad"};
        for (int index = 0; index < names.length; index++) {
            Bundle status = new Bundle();
            status.putString("class", getClass().getName());
            status.putString("test", names[index]);
            status.putInt("numtests", names.length);
            status.putInt("current", index + 1);
            sendStatus(1, status);
            try {
                if (index == 0) busyAndReuse();
                else if (index == 1) cancelAndReuse();
                else stopDuringLoad();
                sendStatus(0, status);
            } catch (Throwable error) {
                failures++;
                status.putString("stack", android.util.Log.getStackTraceString(error));
                sendStatus(-2, status);
            }
        }
        Bundle result = new Bundle();
        result.putString("stream", "\nLifecycle tests: " + (names.length - failures)
                + " passed, " + failures + " failed.\n");
        finish(failures == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void busyAndReuse() throws Exception {
        FakeEngine engine = new FakeEngine();
        EngineManager manager = onMain(() -> new EngineManager(engine, guard));
        try {
            check(onMain(() -> manager.generate(request)), "first request rejected");
            await(engine.generationEntered);
            check(!onMain(() -> manager.generate(request)), "overlapping request accepted");
            check(!onMain(() -> manager.load("other-model", false)), "load accepted while generating");
            engine.allowGeneration.countDown();
            awaitResult(manager, engine, 1, false);
            check(onMain(() -> manager.generate(request)), "warm request rejected");
            awaitResult(manager, engine, 2, false);
            check(engine.loads.get() == 1, "model was reloaded between requests");
            check(engine.unloads.get() == 0, "model unloaded between requests");
            CountDownLatch unloaded = new CountDownLatch(1);
            onMain(() -> { manager.unload(unloaded::countDown); return null; });
            await(unloaded);
            check(!engine.loaded && engine.unloads.get() == 1, "unload did not release model");
        } finally {
            engine.release();
            onMain(() -> { manager.close(); return null; });
        }
    }

    private void cancelAndReuse() throws Exception {
        FakeEngine engine = new FakeEngine();
        EngineManager manager = onMain(() -> new EngineManager(engine, guard));
        try {
            onMain(() -> manager.generate(request));
            await(engine.generationEntered);
            onMain(() -> { manager.cancel(); return null; });
            awaitResult(manager, engine, 1, true);
            check(engine.loaded, "cancellation unloaded the model");
            check(engine.cancels.get() == 1, "cancellation did not reach backend");
            onMain(() -> manager.generate(request));
            awaitResult(manager, engine, 2, false);
            check(engine.loads.get() == 1, "cancel recovery reloaded the engine");
        } finally {
            engine.release();
            onMain(() -> { manager.close(); return null; });
        }
    }

    private void stopDuringLoad() throws Exception {
        FakeEngine engine = new FakeEngine();
        engine.allowLoad = new CountDownLatch(1);
        EngineManager manager = onMain(() -> new EngineManager(engine, guard));
        try {
            onMain(() -> manager.generate(request));
            await(engine.loadEntered);
            CountDownLatch unloaded = new CountDownLatch(1);
            onMain(() -> { manager.unload(unloaded::countDown); return null; });
            check(onMain(() -> manager.getState().phase) == EngineManager.Phase.STOPPING,
                    "stop did not enter STOPPING");
            check(!onMain(() -> manager.generate(request)), "request accepted while stopping");
            check(engine.unloads.get() == 0, "model was freed before load returned");
            engine.allowLoad.countDown();
            await(unloaded);
            check(engine.generations.get() == 0, "pending generation ran after stop");
            check(!engine.loaded && engine.unloads.get() == 1, "loaded model was not released");
        } finally {
            engine.release();
            onMain(() -> { manager.close(); return null; });
        }
    }

    private void awaitResult(EngineManager manager, FakeEngine engine, int count,
                             boolean cancelled) throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<GenerationResult> result = new AtomicReference<>();
        EngineManager.Listener listener = state -> {
            if (state.phase == EngineManager.Phase.READY && state.result != null
                    && engine.generations.get() >= count) {
                result.set(state.result);
                ready.countDown();
            }
        };
        onMain(() -> { manager.attach(listener); return null; });
        try {
            await(ready);
            check(result.get().cancelled == cancelled, "incorrect cancellation result");
            check(result.get().success != cancelled, "incorrect generation result");
        } finally {
            onMain(() -> { manager.detach(listener); return null; });
        }
    }

    private <T> T onMain(Callable<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> {
            try { result.set(action.call()); }
            catch (Throwable problem) { error.set(problem); }
        });
        if (error.get() != null) throw new AssertionError(error.get());
        return result.get();
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        check(latch.await(5, TimeUnit.SECONDS), "timed out waiting for lifecycle event");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void offMain() {
        check(Looper.myLooper() != Looper.getMainLooper(), "backend operation ran on UI thread");
    }

    private static final class FakeEngine implements InferenceEngine {
        final AtomicInteger loads = new AtomicInteger();
        final AtomicInteger unloads = new AtomicInteger();
        final AtomicInteger generations = new AtomicInteger();
        final AtomicInteger cancels = new AtomicInteger();
        final CountDownLatch loadEntered = new CountDownLatch(1);
        final CountDownLatch generationEntered = new CountDownLatch(1);
        final CountDownLatch allowGeneration = new CountDownLatch(1);
        volatile CountDownLatch allowLoad = new CountDownLatch(0);
        volatile boolean loaded;
        volatile String source = "";

        @Override public GenerationResult load(String model, boolean verbose) throws Exception {
            offMain();
            if (!loaded || !source.equals(model)) {
                loadEntered.countDown();
                await(allowLoad);
                source = model;
                loaded = true;
                loads.incrementAndGet();
            }
            return new GenerationResult(true, "", "test load");
        }
        @Override public boolean isLoaded() { return loaded; }
        @Override public String getModelSource() { return source; }
        @Override public String getLoadDiagnostics() { return "test load"; }
        @Override public GenerationResult generate(GenerationRequest request,
                AtomicBoolean cancelled) throws Exception {
            offMain();
            generations.incrementAndGet();
            generationEntered.countDown();
            await(allowGeneration);
            return new GenerationResult(!cancelled.get(), cancelled.get(),
                    cancelled.get() ? "" : "test response", "test request");
        }
        @Override public void cancel() {
            offMain();
            cancels.incrementAndGet();
            allowGeneration.countDown();
        }
        @Override public void unload() {
            offMain();
            if (loaded) unloads.incrementAndGet();
            loaded = false;
            source = "";
        }
        void release() { allowLoad.countDown(); allowGeneration.countDown(); }
    }
}
