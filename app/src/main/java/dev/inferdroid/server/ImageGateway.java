package dev.inferdroid.server;

import dev.inferdroid.images.ImageController;

/** Private image capability for the existing authenticated HTTP transport. */
public interface ImageGateway {
    boolean isAvailable();
    boolean generate(String prompt,long seed,ImageController.Callback callback) throws Exception;
    void cancel(ImageController.Callback callback);
}
