package dev.inferdroid.engine;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GemmaInferenceEngine implements InferenceEngine {
    private final Context context;
    private volatile long handle;
    private volatile long activeRequest;
    private long nextRequest;
    private volatile String modelSource = "";
    private volatile String loadDiagnostics = "";

    public GemmaInferenceEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override public GenerationResult load(String source, boolean verbose) throws IOException {
        if (isLoaded() && source.equals(modelSource)) {
            return new GenerationResult(true, "", loadDiagnostics);
        }
        unload();
        File cache = new File(context.getCacheDir(), "litert");
        if (!cache.isDirectory() && !cache.mkdirs()) {
            throw new IOException("Cannot create LiteRT cache directory: " + cache);
        }
        String libraryDir = context.getApplicationInfo().nativeLibraryDir;
        NativeInference.LoadResult loaded;
        if (source.startsWith("content://")) {
            Uri uri = Uri.parse(source);
            // Native's duplicated ScopedFile is retained with the loaded engine.
            // Java closes only its borrowed descriptor when loading finishes.
            try (ParcelFileDescriptor descriptor =
                         context.getContentResolver().openFileDescriptor(uri, "r")) {
                if (descriptor == null) throw new IOException("The model provider returned no file descriptor.");
                loaded = NativeInference.loadNative(descriptor.getFd(), "", libraryDir,
                        cache.getAbsolutePath(), verbose);
            }
        } else {
            File model = new File(source);
            if (!model.isAbsolute() || !model.isFile() || !model.canRead()) {
                throw new IOException("Model path is not readable by the app. Use Choose model for shared storage, or an app-private absolute path.");
            }
            loaded = NativeInference.loadNative(-1, model.getAbsolutePath(), libraryDir,
                    cache.getAbsolutePath(), verbose);
        }
        handle = loaded.handle;
        modelSource = handle == 0 ? "" : source;
        loadDiagnostics = loaded.diagnostics;
        return new GenerationResult(handle != 0, "", loadDiagnostics);
    }

    @Override public boolean isLoaded() { return handle != 0; }
    @Override public String getModelSource() { return modelSource; }
    @Override public String getLoadDiagnostics() { return loadDiagnostics; }

    @Override public GenerationResult generate(GenerationRequest request, AtomicBoolean cancelled) throws Exception {
        return generate(request, cancelled, null);
    }

    @Override public GenerationResult generate(GenerationRequest request, AtomicBoolean cancelled,
                                               GenerationListener listener) throws Exception {
        long requestId = ++nextRequest;
        activeRequest = requestId;
        try {
            if (cancelled.get()) {
                return new GenerationResult(false, true, "", "Request cancelled before generation.");
            }
            return NativeInference.generate(handle, requestId, request, listener);
        } finally {
            activeRequest = 0;
        }
    }

    @Override public void cancel() {
        cancellation().run();
    }

    @Override public Runnable cancellation() {
        long requestId = activeRequest;
        long engineHandle = handle;
        return () -> {
            if (engineHandle != 0 && requestId != 0) NativeInference.cancelNative(engineHandle, requestId);
        };
    }

    @Override public void unload() {
        long previous = handle;
        handle = 0;
        modelSource = "";
        loadDiagnostics = "";
        if (previous != 0) NativeInference.unloadNative(previous);
    }
}
