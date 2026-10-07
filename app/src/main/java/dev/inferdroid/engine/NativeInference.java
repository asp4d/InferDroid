package dev.inferdroid.engine;

import java.nio.charset.StandardCharsets;

final class NativeInference {
    static { System.loadLibrary("inferdroid_jni"); }

    private NativeInference() {}

    static GenerationResult generate(int fd, String modelPath, String libraryDir,
                                     String cacheDir, String prompt, boolean verbose) {
        return generateNative(fd, modelPath, libraryDir, cacheDir,
                prompt.getBytes(StandardCharsets.UTF_8), verbose);
    }

    private static native GenerationResult generateNative(int fd, String modelPath,
            String libraryDir, String cacheDir, byte[] prompt, boolean verbose);
}
