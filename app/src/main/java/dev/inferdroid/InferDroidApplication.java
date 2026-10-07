package dev.inferdroid;

import android.app.Application;
import dev.inferdroid.engine.EngineManager;
import dev.inferdroid.engine.GemmaInferenceEngine;

public final class InferDroidApplication extends Application {
    private EngineManager engineManager;

    @Override public void onCreate() {
        super.onCreate();
        engineManager = new EngineManager(new GemmaInferenceEngine(this));
    }

    public EngineManager getEngineManager() { return engineManager; }
}
