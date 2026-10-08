package dev.inferdroid;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import dev.inferdroid.engine.EngineManager;
import dev.inferdroid.engine.GenerationRequest;

public final class MainActivity extends Activity implements EngineManager.Listener {
    private static final int PICK_MODEL = 1;
    private static final int NOTIFICATIONS = 2;
    private InferenceService service;
    private EngineManager manager;
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
    private boolean started;
    private boolean bound;
    private boolean permissionInFlight;
    private Runnable pendingAction;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (!started) return;
            service = ((InferenceService.LocalBinder) binder).getService();
            manager = service.getManager();
            manager.attach(MainActivity.this);
            executePending();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            if (manager != null) manager.detach(MainActivity.this);
            manager = null;
            service = null;
            setDisconnected();
            status.setText(R.string.service_disconnected);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
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

    @Override protected void onStop() {
        started = false;
        if (manager != null) manager.detach(this);
        if (bound) unbindService(connection);
        bound = false;
        manager = null;
        service = null;
        super.onStop();
    }

    private void setDisconnected() {
        run.setEnabled(false);
        load.setEnabled(false);
        unload.setEnabled(false);
        cancel.setEnabled(false);
        status.setText(R.string.service_connecting);
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

    private void withNotificationPermission(Runnable action) {
        if (service == null || permissionInFlight) return;
        pendingAction = action;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !preferences.getBoolean("notification_permission_asked", false)) {
            permissionInFlight = true;
            preferences.edit().putBoolean("notification_permission_asked", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS);
        } else {
            executePending();
        }
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
        run.setEnabled(!state.busy);
        load.setEnabled(!state.busy);
        unload.setEnabled((state.loaded || state.busy) && state.phase != EngineManager.Phase.STOPPING);
        cancel.setEnabled(state.canCancel);
        choose.setEnabled(!state.busy);
        modelSource.setEnabled(!state.busy);
        prompt.setEnabled(!state.busy);
        verbose.setEnabled(!state.busy);
        backend.setText(state.backend);
        status.setText(state.status);
        output.setText(state.result == null ? "" : state.result.text);
        diagnostics.setText(state.result == null ? "" : state.result.diagnostics);
    }
}
