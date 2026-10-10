package dev.inferdroid;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.DocumentsContract;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import dev.inferdroid.engine.EngineManager;
import dev.inferdroid.engine.GenerationRequest;
import dev.inferdroid.server.ServerConfig;
import dev.inferdroid.speech.SpeechManager;
import dev.inferdroid.speech.SpeechLanguages;
import dev.inferdroid.speech.TranscriptionRequest;

public final class MainActivity extends AppCompatActivity implements EngineManager.Listener, InferenceService.ServerListener, SpeechManager.Listener {
    private static final int PICK_MODEL = 1;
    private static final int NOTIFICATIONS = 2;
    private static final int PICK_SPEECH_MODEL = 3;
    private static final int PICK_AUDIO = 4;
    private InferenceService service;
    private EngineManager manager;
    private SpeechManager speech;
    private Button importSpeech;
    private Button loadSpeech;
    private Button unloadSpeech;
    private Button chooseAudio;
    private Button transcribe;
    private EditText speechLanguage;
    private TextView speechModelStatus;
    private TextView speechStatus;
    private TextView audioSource;
    private TextView transcript;
    private Uri selectedAudio;
    private SharedPreferences preferences;
    private EditText modelSource;
    private EditText prompt;
    private CheckBox verbose;
    private Button run;
    private Button choose;
    private Button load;
    private Button unload;
    private Button cancel;
    private TextView backend;
    private TextView notificationStatus;
    private TextView status;
    private TextView output;
    private TextView diagnostics;
    private SharedPreferences serverPreferences;
    private EditText serverPort;
    private EditText apiKey;
    private ImageButton apiKeyVisibility;
    private boolean apiKeyVisible;
    private CheckBox requireKey;
    private CheckBox cors;
    private Button startServer;
    private Button stopServer;
    private Button regenerateKey;
    private TextView serverStatus;
    private boolean started;
    private boolean bound;
    private boolean permissionInFlight;
    private Runnable pendingAction;
    private String currentAppliedTheme;
    private String currentAppliedLanguage;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (!started) return;
            service = ((InferenceService.LocalBinder) binder).getService();
            manager = service.getManager();
            speech = service.getSpeechManager();
            manager.attach(MainActivity.this);
            speech.attach(MainActivity.this);
            service.attachServer(MainActivity.this);
            executePending();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            if (manager != null) manager.detach(MainActivity.this);
            if (speech != null) speech.detach(MainActivity.this);
            if (service != null) service.detachServer(MainActivity.this);
            manager = null;
            speech = null;
            service = null;
            setDisconnected();
            status.setText(R.string.service_disconnected);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        currentAppliedTheme = ThemeHelper.getSelectedTheme(this);
        currentAppliedLanguage = ThemeHelper.getSelectedLanguage(this);

        View content = findViewById(R.id.content);
        int padding = Math.round(16 * getResources().getDisplayMetrics().density);
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            view.setPadding(padding + bars.left, padding + bars.top,
                    padding + bars.right, padding + bars.bottom);
            return insets;
        });
        content.requestApplyInsets();

        findViewById(R.id.btn_ui_settings).setOnClickListener(view ->
            startActivity(new Intent(this, UiSettingsActivity.class))
        );

        preferences = getSharedPreferences("inference", MODE_PRIVATE);
        modelSource = findViewById(R.id.model_source);
        prompt = findViewById(R.id.prompt);
        verbose = findViewById(R.id.verbose);
        run = findViewById(R.id.run);
        choose = findViewById(R.id.choose_model);
        load = findViewById(R.id.load_model);
        unload = findViewById(R.id.unload);
        cancel = findViewById(R.id.cancel);
        backend = findViewById(R.id.backend_status);
        notificationStatus = findViewById(R.id.notification_status);
        status = findViewById(R.id.status);
        output = findViewById(R.id.output);
        diagnostics = findViewById(R.id.diagnostics);
        importSpeech = findViewById(R.id.import_speech_model);
        loadSpeech = findViewById(R.id.load_speech_model);
        unloadSpeech = findViewById(R.id.unload_speech_model);
        chooseAudio = findViewById(R.id.choose_audio);
        transcribe = findViewById(R.id.transcribe_audio);
        speechLanguage = findViewById(R.id.speech_language);
        speechModelStatus = findViewById(R.id.speech_model_status);
        speechStatus = findViewById(R.id.speech_status);
        audioSource = findViewById(R.id.audio_source);
        transcript = findViewById(R.id.transcript);
        String audio = preferences.getString("audio_uri", "");
        selectedAudio = audio.isEmpty() ? null : Uri.parse(audio);
        audioSource.setText(selectedAudio == null ? getString(R.string.audio_not_selected) : selectedAudio.toString());
        speechLanguage.setText(preferences.getString("speech_language", ""));
        importSpeech.setOnClickListener(view -> chooseSpeechModel());
        chooseAudio.setOnClickListener(view -> chooseAudioFile());
        loadSpeech.setOnClickListener(view -> withNotificationPermission(() -> {
            if (!service.loadSpeech()) speechStatus.setText(R.string.engine_busy);
        }));
        unloadSpeech.setOnClickListener(view -> { if (service != null) service.unloadSpeech(); });
        transcribe.setOnClickListener(view -> transcribeAudio());
        serverPreferences = getSharedPreferences("server", MODE_PRIVATE);
        serverPort = findViewById(R.id.server_port);
        apiKey = findViewById(R.id.api_key);
        apiKeyVisibility = findViewById(R.id.toggle_api_key_visibility);
        requireKey = findViewById(R.id.require_api_key);
        cors = findViewById(R.id.cors);
        startServer = findViewById(R.id.start_server);
        stopServer = findViewById(R.id.stop_server);
        regenerateKey = findViewById(R.id.regenerate_key);
        serverStatus = findViewById(R.id.server_status);
        if (!serverPreferences.contains("api_key")) {
            serverPreferences.edit().putString("api_key", ServerConfig.generateKey()).apply();
        }
        serverPort.setText(String.format(java.util.Locale.ROOT, "%d", serverPreferences.getInt("port", ServerConfig.DEFAULT_PORT)));
        apiKey.setText(serverPreferences.getString("api_key", ""));
        setApiKeyVisible(false);
        apiKeyVisibility.setOnClickListener(view -> setApiKeyVisible(!apiKeyVisible));
        requireKey.setChecked(serverPreferences.getBoolean("require_key", true));
        cors.setChecked(serverPreferences.getBoolean("cors", false));
        startServer.setOnClickListener(view -> startLocalServer());
        stopServer.setOnClickListener(view -> { if (service != null) service.stopServer(); });
        regenerateKey.setOnClickListener(view -> {
            String key = ServerConfig.generateKey();
            apiKey.setText(key);
            serverPreferences.edit().putString("api_key", key).apply();
        });
        findViewById(R.id.copy_key).setOnClickListener(view -> {
            ClipData clip = ClipData.newPlainText("InferDroid local API key", apiKey.getText().toString());
            if (Build.VERSION.SDK_INT >= 33) {
                android.os.PersistableBundle extras = new android.os.PersistableBundle();
                extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
                clip.getDescription().setExtras(extras);
            }
            getSystemService(ClipboardManager.class).setPrimaryClip(clip);
        });
        modelSource.setText(preferences.getString("model", ""));
        verbose.setChecked(preferences.getBoolean("verbose", true));
        choose.setOnClickListener(view -> chooseModel());
        load.setOnClickListener(view -> loadModel());
        run.setOnClickListener(view -> runInference());
        unload.setOnClickListener(view -> { if (service != null) service.unload(); });
        cancel.setOnClickListener(view -> { if (service != null) service.cancel(); });
        setDisconnected();
    }

    @Override protected void onStart() {
        super.onStart();
        started = true;
        bound = bindService(new Intent(this, InferenceService.class),
                connection, BIND_AUTO_CREATE);
        updateNotificationStatus();
    }

    @Override protected void onResume() {
        super.onResume();
        String newTheme = ThemeHelper.getSelectedTheme(this);
        String newLang = ThemeHelper.getSelectedLanguage(this);
        if (!newTheme.equals(currentAppliedTheme) || !newLang.equals(currentAppliedLanguage)) {
            recreate();
        }
    }

    @Override protected void onStop() {
        setApiKeyVisible(false);
        started = false;
        if (manager != null) manager.detach(this);
        if (speech != null) speech.detach(this);
        if (service != null) service.detachServer(this);
        if (bound) unbindService(connection);
        bound = false;
        manager = null;
        speech = null;
        service = null;
        super.onStop();
    }

    private void setDisconnected() {
        run.setEnabled(false);
        load.setEnabled(false);
        unload.setEnabled(false);
        cancel.setEnabled(false);
        startServer.setEnabled(false);
        stopServer.setEnabled(false);
        importSpeech.setEnabled(false);
        loadSpeech.setEnabled(false);
        unloadSpeech.setEnabled(false);
        chooseAudio.setEnabled(false);
        transcribe.setEnabled(false);
        status.setText(R.string.service_connecting);
    }

    private void chooseSpeechModel() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        picker.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                Uri.parse("content://com.android.externalstorage.documents/document/primary%3AAIModels"));
        startActivityForResult(picker, PICK_SPEECH_MODEL);
    }

    private void chooseAudioFile() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("audio/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), PICK_AUDIO);
    }

    private void chooseModel() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        picker.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                Uri.parse("content://com.android.externalstorage.documents/document/primary%3AAIModels%2Flitert-npu"));
        startActivityForResult(picker, PICK_MODEL);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri selected = data.getData();
            if (requestCode == PICK_SPEECH_MODEL) {
                withNotificationPermission(() -> {
                    if (!service.importSpeechModel(selected)) speechStatus.setText(R.string.engine_busy);
                });
                return;
            }
            if (requestCode == PICK_AUDIO) {
                selectedAudio = selected;
                audioSource.setText(selected.toString());
                try {
                    getContentResolver().takePersistableUriPermission(selected, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    preferences.edit().putString("audio_uri", selected.toString()).apply();
                } catch (SecurityException error) { speechStatus.setText(R.string.audio_temporary); }
                if (manager != null) onStateChanged(manager.getState());
                return;
            }
        }
        if (requestCode != PICK_MODEL || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        modelSource.setText(uri.toString());
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            preferences.edit().putString("model", uri.toString()).apply();
            status.setText(R.string.model_ready);
        } catch (SecurityException error) {
            status.setText(R.string.model_temporary);
        }
    }

    private String selectedModel() {
        String source = modelSource.getText().toString().trim();
        if (source.isEmpty()) {
            status.setText(R.string.model_required);
            return null;
        }
        preferences.edit().putString("model", source)
                .putBoolean("verbose", verbose.isChecked()).apply();
        return source;
    }

    private void loadModel() {
        String source = selectedModel();
        if (source == null) return;
        boolean logs = verbose.isChecked();
        withNotificationPermission(() -> {
            if (!service.load(source, logs)) status.setText(R.string.engine_busy);
        });
    }

    private void runInference() {
        String source = selectedModel();
        if (source == null) return;
        String text = prompt.getText().toString();
        if (text.trim().isEmpty() || text.indexOf('\0') >= 0) {
            status.setText(R.string.prompt_required);
            return;
        }
        GenerationRequest request = new GenerationRequest(source, text, verbose.isChecked());
        withNotificationPermission(() -> {
            if (!service.generate(request)) status.setText(R.string.engine_busy);
        });
    }

    private void setApiKeyVisible(boolean visible) {
        int selectionStart = apiKey.getSelectionStart();
        int selectionEnd = apiKey.getSelectionEnd();
        apiKeyVisible = visible;
        apiKey.setTransformationMethod(visible ? null : PasswordTransformationMethod.getInstance());
        if (selectionStart >= 0 && selectionEnd >= 0) apiKey.setSelection(selectionStart, selectionEnd);
        apiKeyVisibility.setImageResource(visible ? R.drawable.ic_visibility_off : R.drawable.ic_visibility);
        String action = getString(visible ? R.string.hide_api_key : R.string.show_api_key);
        apiKeyVisibility.setContentDescription(action);
        apiKeyVisibility.setTooltipText(action);
    }

    private void startLocalServer() {
        String source = modelSource.getText().toString().trim();
        preferences.edit().putString("model", source).putBoolean("verbose", verbose.isChecked()).apply();
        try {
            int port = Integer.parseInt(serverPort.getText().toString().trim());
            if (port < 1 || port > 65535) throw new IllegalArgumentException(getString(R.string.invalid_port));
            ServerConfig config = new ServerConfig(port, apiKey.getText().toString(), requireKey.isChecked(), cors.isChecked());
            boolean logs = verbose.isChecked();
            serverPreferences.edit().putInt("port", port).putString("api_key", config.apiKey)
                    .putBoolean("require_key", config.requireKey).putBoolean("cors", config.cors).apply();
            withNotificationPermission(() -> {
                try {
                    service.startServer(config, source, logs);
                } catch (java.io.IOException | RuntimeException error) {
                    serverStatus.setText(getString(R.string.error_detail, "Server start failed", error.getMessage()));
                }
            });
        } catch (IllegalArgumentException error) {
            serverStatus.setText(error instanceof NumberFormatException ? getString(R.string.invalid_port) : error.getMessage());
        }
    }

    private void transcribeAudio() {
        if (selectedAudio == null) { speechStatus.setText(R.string.audio_not_selected); return; }
        String language = speechLanguage.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
        if (!SpeechLanguages.supports(language)) { speechStatus.setText(R.string.speech_language_invalid); return; }
        preferences.edit().putString("speech_language", language).apply();
        Uri audio = selectedAudio;
        TranscriptionRequest request = new TranscriptionRequest(() -> getContentResolver().openInputStream(audio), language);
        withNotificationPermission(() -> {
            if (!service.transcribe(request)) speechStatus.setText(R.string.engine_busy);
        });
    }

    private void withNotificationPermission(Runnable action) {
        if (permissionInFlight) return;
        pendingAction = action;
        executePending();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != NOTIFICATIONS) return;
        permissionInFlight = false;
        updateNotificationStatus();
        executePending();
    }

    private void executePending() {
        if (!started || service == null || permissionInFlight || pendingAction == null) return;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !preferences.getBoolean("notification_permission_asked", false)) {
            permissionInFlight = true;
            preferences.edit().putBoolean("notification_permission_asked", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS);
            return;
        }
        Runnable action = pendingAction;
        pendingAction = null;
        try {
            action.run();
        } catch (RuntimeException error) {
            status.setText(R.string.service_start_failed);
            diagnostics.setText(getString(R.string.error_detail,
                    error.getClass().getSimpleName(), error.getMessage()));
        }
    }

    private void updateNotificationStatus() {
        boolean denied = Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED;
        notificationStatus.setVisibility(denied ? View.VISIBLE : View.GONE);
        if (denied) notificationStatus.setText(R.string.notifications_disabled);
    }

    @Override public void onStateChanged(EngineManager.State state) {
        boolean busy = service != null && service.isBusy();
        SpeechManager.State audio = speech == null ? null : speech.getState();
        run.setEnabled(!busy);
        load.setEnabled(!busy);
        boolean serving = service != null && service.isServerRunning();
        boolean stopping = state.phase == EngineManager.Phase.STOPPING || audio != null && audio.phase == SpeechManager.Phase.STOPPING;
        unload.setEnabled((serving || state.loaded || busy || audio != null && audio.loaded) && !stopping);
        cancel.setEnabled(state.canCancel || audio != null && audio.canCancel);
        choose.setEnabled(!busy && !serving);
        modelSource.setEnabled(!busy && !serving);
        prompt.setEnabled(!busy);
        verbose.setEnabled(!busy && !serving);
        startServer.setEnabled(!serving && !busy);
        stopServer.setEnabled(serving);
        serverPort.setEnabled(!serving);
        apiKey.setEnabled(!serving);
        requireKey.setEnabled(!serving);
        cors.setEnabled(!serving);
        regenerateKey.setEnabled(!serving);
        boolean hasSpeechModel = service != null && service.hasSpeechModel();
        importSpeech.setEnabled(!busy && !serving);
        loadSpeech.setEnabled(!busy && hasSpeechModel);
        unloadSpeech.setEnabled(audio != null && audio.loaded && !busy);
        chooseAudio.setEnabled(!busy);
        transcribe.setEnabled(!busy && hasSpeechModel && selectedAudio != null);
        speechLanguage.setEnabled(!busy);
        speechModelStatus.setText(hasSpeechModel ? R.string.speech_model_ready : R.string.speech_model_missing);
        backend.setText(state.backend);
        status.setText(state.status);
        output.setText(state.result == null ? "" : state.result.text);
        diagnostics.setText(state.result == null ? "" : state.result.diagnostics);
    }

    @Override public void onSpeechChanged(SpeechManager.State state) {
        speechStatus.setText(state.status);
        transcript.setText(state.result == null ? "" : state.result.text);
        if (manager != null) onStateChanged(manager.getState());
    }

    @Override public void onServerChanged(boolean running, String message) {
        serverStatus.setText(message);
        if (manager != null) onStateChanged(manager.getState());
    }
}
