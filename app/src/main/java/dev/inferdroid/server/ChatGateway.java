package dev.inferdroid.server;

import dev.inferdroid.engine.GenerationListener;
import dev.inferdroid.engine.GenerationRequest;

/** The HTTP adapter depends on admission/cancellation, never on JNI or a specific engine. */
public interface ChatGateway {
    boolean generate(GenerationRequest request, GenerationListener listener) throws Exception;
    void cancel(GenerationListener listener);
}
