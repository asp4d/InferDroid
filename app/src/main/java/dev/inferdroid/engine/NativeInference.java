package dev.inferdroid.engine;

import java.nio.charset.StandardCharsets;

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

    static GenerationResult generate(long handle, long requestId, String prompt, boolean verbose) {
        return generateNative(handle, requestId, prompt.getBytes(StandardCharsets.UTF_8), verbose);
    }

    static native LoadResult loadNative(int fd, String modelPath, String libraryDir,
            String cacheDir, boolean verbose);
    private static native GenerationResult generateNative(long handle, long requestId,
            byte[] prompt, boolean verbose);
    static native void cancelNative(long handle, long requestId);
    static native void unloadNative(long handle);
}
