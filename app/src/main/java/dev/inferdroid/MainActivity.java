package dev.inferdroid;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
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
    private EngineManager manager;
    private SharedPreferences preferences;
    private EditText modelSource;
    private EditText prompt;
    private CheckBox verbose;
    private Button run;
    private Button choose;
    private TextView status;
    private TextView output;
    private TextView diagnostics;

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

        manager = ((InferDroidApplication) getApplication()).getEngineManager();
        preferences = getSharedPreferences("inference", MODE_PRIVATE);
        modelSource = findViewById(R.id.model_source);
        prompt = findViewById(R.id.prompt);
        verbose = findViewById(R.id.verbose);
        run = findViewById(R.id.run);
        choose = findViewById(R.id.choose_model);
        status = findViewById(R.id.status);
        output = findViewById(R.id.output);
        diagnostics = findViewById(R.id.diagnostics);
        modelSource.setText(preferences.getString("model", ""));
        verbose.setChecked(preferences.getBoolean("verbose", true));
        choose.setOnClickListener(view -> chooseModel());
        run.setOnClickListener(view -> runInference());
    }

    @Override protected void onStart() {
        super.onStart();
        manager.attach(this);
    }

    @Override protected void onStop() {
        manager.detach(this);
        super.onStop();
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
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            modelSource.setText(uri.toString());
            preferences.edit().putString("model", uri.toString()).apply();
            status.setText(R.string.model_ready);
        } catch (SecurityException error) {
            modelSource.setText(uri.toString());
            status.setText(R.string.model_temporary);
        }
    }

    private void runInference() {
        String source = modelSource.getText().toString().trim();
        String text = prompt.getText().toString();
        if (source.isEmpty()) {
            status.setText(R.string.model_required);
            return;
        }
        if (text.trim().isEmpty() || text.indexOf('\0') >= 0) {
            status.setText(R.string.prompt_required);
            return;
        }
        preferences.edit().putString("model", source).putBoolean("verbose", verbose.isChecked()).apply();
        manager.generate(new GenerationRequest(source, text, verbose.isChecked()));
    }

    @Override public void onStateChanged(EngineManager.State state) {
        run.setEnabled(!state.busy);
        choose.setEnabled(!state.busy);
        modelSource.setEnabled(!state.busy);
        prompt.setEnabled(!state.busy);
        verbose.setEnabled(!state.busy);
        status.setText(state.status);
        output.setText(state.result == null ? "" : state.result.text);
        diagnostics.setText(state.result == null ? "" : state.result.diagnostics);
    }
}
