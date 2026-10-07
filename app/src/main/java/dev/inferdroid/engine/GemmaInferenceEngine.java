package dev.inferdroid.engine;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.IOException;

public final class GemmaInferenceEngine implements InferenceEngine {
    private final Context context;

    public GemmaInferenceEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override public GenerationResult generate(GenerationRequest request) throws IOException {
        File cache = new File(context.getCacheDir(), "litert");
        if (!cache.isDirectory() && !cache.mkdirs()) {
            throw new IOException("Cannot create LiteRT cache directory: " + cache);
        }
        String libraryDir = context.getApplicationInfo().nativeLibraryDir;
        if (request.modelSource.startsWith("content://")) {
            Uri uri = Uri.parse(request.modelSource);
            // Keep Java's descriptor alive for the entire call. Native duplicates
            // it into an upstream ScopedFile, and releases its duplicate on return.
            try (ParcelFileDescriptor descriptor =
                         context.getContentResolver().openFileDescriptor(uri, "r")) {
                if (descriptor == null) throw new IOException("The model provider returned no file descriptor.");
                return NativeInference.generate(descriptor.getFd(), "", libraryDir,
                        cache.getAbsolutePath(), request.prompt, request.verbose);
            }
        }
        File model = new File(request.modelSource);
        if (!model.isAbsolute() || !model.isFile() || !model.canRead()) {
            throw new IOException("Model path is not readable by the app. Use Choose model for shared storage, or an app-private absolute path.");
        }
        return NativeInference.generate(-1, model.getAbsolutePath(), libraryDir,
                cache.getAbsolutePath(), request.prompt, request.verbose);
    }
}
