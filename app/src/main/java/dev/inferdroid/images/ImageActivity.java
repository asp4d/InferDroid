package dev.inferdroid.images;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.DocumentsContract;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import dev.inferdroid.InferenceService;
import dev.inferdroid.R;
import dev.inferdroid.ThemeHelper;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local image UI, using the same model selection and theme conventions as chat. */
public final class ImageActivity extends AppCompatActivity {
    private static final int PICK_MODEL = 10;
    private static final int SAVE_IMAGE = 11;
    private InferenceService service;
    private ImageController controller;
    private SharedPreferences preferences;
    private TextView status, modelSource, modelStatus;
    private EditText prompt, seed;
    private Button choose, load, unload, generate, cancel, save;
    private ImageView preview;
    private File displayed;
    private String pendingSavePath;
    private Uri selectedModel;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable changed = this::render;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((InferenceService.LocalBinder) binder).getService();
            controller = service.getImages();
            controller.attach(changed);
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            controller = null;
            render();
        }
    };

    @Override public void onCreate(Bundle state) {
        ThemeHelper.applyTheme(this);
        super.onCreate(state);
        setContentView(R.layout.activity_image_generation);
        preferences = getSharedPreferences(ImageModels.PREFERENCES, MODE_PRIVATE);
        String source = state == null ? preferences.getString(ImageModels.KEY_MODEL, "")
                : state.getString("selected_model", "");
        selectedModel = source.isEmpty() ? null : Uri.parse(source);
        pendingSavePath = state == null ? null : state.getString("save_image_path");
        modelSource = findViewById(R.id.image_model_source);
        modelStatus = findViewById(R.id.image_model_status);
        status = findViewById(R.id.image_generation_status);
        prompt = findViewById(R.id.image_prompt);
        seed = findViewById(R.id.image_seed);
        choose = findViewById(R.id.choose_image_model);
        load = findViewById(R.id.load_image_model);
        unload = findViewById(R.id.unload_image_model);
        generate = findViewById(R.id.generate_image);
        cancel = findViewById(R.id.cancel_image);
        save = findViewById(R.id.save_generated_image);
        preview = findViewById(R.id.generated_image);
        if (state != null) {
            prompt.setText(state.getString("prompt"));
            seed.setText(state.getString("seed"));
        }
        findViewById(R.id.image_content).setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                    | android.view.WindowInsets.Type.displayCutout() | android.view.WindowInsets.Type.ime());
            int padding = Math.round(16 * getResources().getDisplayMetrics().density);
            view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom);
            return insets;
        });
        findViewById(R.id.image_content).requestApplyInsets();
        choose.setOnClickListener(view -> chooseModel());
        load.setOnClickListener(view -> {
            if (selectedModel == null) status.setText(R.string.image_model_required);
            else if (service == null || !service.importImageModels(selectedModel)) status.setText(R.string.engine_busy);
        });
        unload.setOnClickListener(view -> {
            if (service == null || !service.unloadImages()) status.setText(R.string.engine_busy);
        });
        generate.setOnClickListener(view -> generate());
        cancel.setOnClickListener(view -> { if (controller != null) controller.cancel(null); });
        save.setOnClickListener(view -> {
            if (displayed == null) return;
            pendingSavePath = displayed.toString();
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("image/png")
                    .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "inferdroid-image.png"), SAVE_IMAGE);
        });
        findViewById(R.id.back_from_images).setOnClickListener(view -> finish());
        render();
    }

    private void chooseModel() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if (selectedModel != null) picker.putExtra(DocumentsContract.EXTRA_INITIAL_URI, selectedModel);
        startActivityForResult(picker, PICK_MODEL);
    }

    private void generate() {
        if (selectedModel == null && (controller == null || !controller.isAvailable())) {
            status.setText(R.string.image_model_required);
            return;
        }
        String text = prompt.getText().toString();
        if (text.isBlank() || text.length() > 8192 || text.indexOf('\0') >= 0) {
            status.setText(R.string.image_prompt_invalid);
            return;
        }
        long value;
        try { value = Long.parseLong(seed.getText().toString().trim()); }
        catch (NumberFormatException error) { status.setText(R.string.image_seed_invalid); return; }
        if (service == null || !service.generateImage(text, value, selectedModel, null)) status.setText(R.string.engine_busy);
    }

    @Override protected void onStart() {
        super.onStart();
        bindService(new Intent(this, InferenceService.class), connection, BIND_AUTO_CREATE);
    }
    @Override protected void onStop() {
        if (controller != null) controller.detach(changed);
        unbindService(connection);
        controller = null;
        service = null;
        super.onStop();
    }
    @Override protected void onDestroy() { io.shutdown(); super.onDestroy(); }
    @Override public void onSaveInstanceState(Bundle state) {
        state.putString("selected_model", selectedModel == null ? "" : selectedModel.toString());
        state.putString("prompt", prompt.getText().toString());
        state.putString("seed", seed.getText().toString());
        state.putString("save_image_path", pendingSavePath);
        super.onSaveInstanceState(state);
    }

    private void render() {
        boolean ready = controller != null;
        boolean idle = ready && !service.isBusy();
        choose.setEnabled(idle);
        load.setEnabled(idle && selectedModel != null);
        unload.setEnabled(ready && controller.canUnload() && (idle || controller.isBusy()));
        generate.setEnabled(idle && (selectedModel != null || controller.isAvailable()));
        cancel.setEnabled(ready && controller.canCancel());
        modelSource.setText(selectedModel != null ? selectedModel.toString()
                : getString(ready && controller.isAvailable() ? R.string.image_model_imported : R.string.image_model_not_selected));
        boolean imported = ready && controller.isAvailable()
                && (selectedModel == null || controller.matchesModel(selectedModel));
        modelStatus.setText(imported ? R.string.image_model_ready
                : selectedModel != null ? R.string.image_model_selected : R.string.image_model_required);
        status.setText(ready ? controller.status() : getString(R.string.service_connecting));
        File image = ready ? controller.image() : null;
        save.setEnabled(image != null);
        if (image != null && !image.equals(displayed)) {
            displayed = image;
            io.execute(() -> {
                android.graphics.Bitmap bitmap = BitmapFactory.decodeFile(image.toString());
                runOnUiThread(() -> {
                    if (image.equals(displayed) && !isDestroyed()) preview.setImageBitmap(bitmap);
                    else if (bitmap != null) bitmap.recycle();
                });
            });
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (request == PICK_MODEL) {
            selectedModel = data.getData();
            boolean persistent = true;
            try {
                getContentResolver().takePersistableUriPermission(selectedModel, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                preferences.edit().putString(ImageModels.KEY_MODEL, selectedModel.toString()).apply();
            } catch (SecurityException error) { persistent = false; }
            render();
            status.setText(persistent ? R.string.image_model_selected : R.string.image_model_temporary);
        } else if (request == SAVE_IMAGE) {
            File image = pendingSavePath != null ? new File(pendingSavePath) : displayed;
            pendingSavePath = null;
            if (image == null) return;
            Uri destination = data.getData();
            io.execute(() -> {
                try (InputStream input = new FileInputStream(image);
                     OutputStream output = getContentResolver().openOutputStream(destination)) {
                    if (output == null) throw new java.io.IOException("No output stream");
                    ImageFiles.copy(input, output);
                    runOnUiThread(() -> status.setText(R.string.image_saved));
                } catch (Exception error) { runOnUiThread(() -> status.setText(R.string.image_save_failed)); }
            });
        }
    }
}
