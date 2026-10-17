package dev.inferdroid.images;

/** Loaded exclusively in the private image worker process. */
final class NativeImages {
    static { System.loadLibrary("inferdroid_images_jni"); }
    static native void configure(String runtime, String dispatchDirectory, String log) throws java.io.IOException;
    static native byte[] run(String model, byte[][] inputs, boolean hybrid, int outputBytes) throws java.io.IOException;
    private NativeImages() { }
}
