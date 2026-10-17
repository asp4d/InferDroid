package dev.inferdroid.images;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.widget.EditText;
import android.widget.TextView;
import dev.inferdroid.R;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** UI persistence and failed-selection checks; no generation or model replacement. */
final class ImageUiTests {
    private final Instrumentation test;
    private final Context target;
    private ImageActivity activity;

    ImageUiTests(Instrumentation test) { this.test = test; target = test.getTargetContext(); }

    void run() throws Exception {
        SharedPreferences preferences = target.getSharedPreferences(ImageModels.PREFERENCES, Context.MODE_PRIVATE);
        String original = preferences.getString(ImageModels.KEY_MODEL, null);
        String imported = preferences.getString("imported_model", null);
        ImageModels models = new ImageModels(target);
        boolean available = models.available();
        Uri saved = Uri.parse("content://dev.inferdroid.missing/tree/saved-model");
        Uri temporary = Uri.parse("content://dev.inferdroid.missing/tree/temporary-model");
        try {
            preferences.edit().putString(ImageModels.KEY_MODEL, saved.toString()).commit();
            launch();
            check(source().equals(saved.toString()), "saved_model_preselected");
            check(onMain(() -> activity.findViewById(R.id.load_image_model).isEnabled()), "saved_model_load_enabled");
            AtomicReference<Intent> picker = new AtomicReference<>();
            Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
                @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                    if (!Intent.ACTION_OPEN_DOCUMENT_TREE.equals(intent.getAction())) return null;
                    picker.set(intent);
                    return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
                }
            };
            test.addMonitor(monitor);
            try {
                onMain(() -> activity.findViewById(R.id.choose_image_model).performClick());
                test.waitForIdleSync();
                Intent request = picker.get();
                check(request != null, "folder_picker_opened");
                check(saved.equals(request.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.class)), "picker_remembers_folder");
                check((request.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0, "picker_requests_persistent_access");
                check((request.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0, "picker_requests_read_access");
                check(source().equals(saved.toString()), "picker_cancellation_keeps_selection");
            } finally { test.removeMonitor(monitor); }

            onMain(() -> {
                activity.onActivityResult(10, Activity.RESULT_OK, new Intent().setData(temporary));
                return true;
            });
            check(source().equals(temporary.toString()), "temporary_model_selected");
            check(saved.toString().equals(preferences.getString(ImageModels.KEY_MODEL, "")), "temporary_grant_keeps_saved_selection");
            check(status().equals(onMain(() -> activity.getString(R.string.image_model_temporary))), "temporary_access_explained");
            onMain(() -> {
                ((EditText) activity.findViewById(R.id.image_prompt)).setText("Keep my prompt on rotation");
                ((EditText) activity.findViewById(R.id.image_seed)).setText("-7");
                return true;
            });
            Instrumentation.ActivityMonitor rotation = test.addMonitor(ImageActivity.class.getName(), null, false);
            try {
                onMain(() -> { activity.recreate(); return true; });
                Activity replacement = test.waitForMonitorWithTimeout(rotation, 10000);
                check(replacement instanceof ImageActivity, "activity_recreated");
                activity = (ImageActivity) replacement;
                waitUntilReady();
                check(source().equals(temporary.toString()), "rotation_keeps_temporary_selection");
                check(onMain(() -> text(R.id.image_prompt)).equals("Keep my prompt on rotation"), "rotation_keeps_prompt");
                check(onMain(() -> text(R.id.image_seed)).equals("-7"), "rotation_keeps_seed");
            } finally { test.removeMonitor(rotation); }
            close();
            launch();
            check(source().equals(saved.toString()), "reopening_restores_last_persistent_model");
            onMain(() -> {
                ((EditText) activity.findViewById(R.id.image_seed)).setText("9223372036854775808");
                activity.findViewById(R.id.generate_image).performClick();
                return true;
            });
            check(status().equals(onMain(() -> activity.getString(R.string.image_seed_invalid))), "seed_overflow_rejected");
            onMain(() -> {
                ((EditText) activity.findViewById(R.id.image_seed)).setText("0");
                ((EditText) activity.findViewById(R.id.image_prompt)).setText("");
                activity.findViewById(R.id.generate_image).performClick();
                return true;
            });
            check(status().equals(onMain(() -> activity.getString(R.string.image_prompt_invalid))), "empty_prompt_rejected");
            onMain(() -> {
                ((EditText) activity.findViewById(R.id.image_prompt)).setText("A red robot");
                activity.findViewById(R.id.generate_image).performClick();
                return true;
            });
            waitUntilReady();
            String failure = onMain(() -> activity.getString(R.string.image_model_import_failed, ""));
            check(status().startsWith(failure), "inaccessible_selection_reports_import_failure");
            check(models.available() == available, "failed_import_preserves_previous_models");
            check(java.util.Objects.equals(imported, preferences.getString("imported_model", null)), "failed_import_preserves_imported_source");
            check(!models.matches(saved), "failed_selection_never_uses_previous_model");
            for (Locale locale : new Locale[]{Locale.ENGLISH, Locale.ITALIAN}) {
                Configuration configuration = new Configuration(target.getResources().getConfiguration());
                configuration.setLocale(locale);
                Context localized = target.createConfigurationContext(configuration);
                check(localized.getString(R.string.image_generation_title).equals(locale.equals(Locale.ITALIAN)
                        ? "Genera immagini" : "Generate images"), "translated_image_title_" + locale);
                check(!localized.getString(R.string.image_model_help).isBlank(), "translated_model_help_" + locale);
            }
        } finally {
            close();
            SharedPreferences.Editor editor = preferences.edit();
            if (original == null) editor.remove(ImageModels.KEY_MODEL); else editor.putString(ImageModels.KEY_MODEL, original);
            editor.commit();
        }
    }

    private void launch() throws Exception {
        activity = (ImageActivity) test.startActivitySync(new Intent(target, ImageActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitUntilReady();
    }
    private void close() {
        if (activity != null) {
            test.runOnMainSync(() -> activity.finish());
            test.waitForIdleSync();
            activity = null;
        }
    }
    private String text(int id) { return ((TextView) activity.findViewById(id)).getText().toString(); }
    private String source() throws Exception { return onMain(() -> text(R.id.image_model_source)); }
    private String status() throws Exception { return onMain(() -> text(R.id.image_generation_status)); }
    private void waitUntilReady() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!onMain(() -> activity.findViewById(R.id.choose_image_model).isEnabled())) {
            if (System.nanoTime() > deadline) throw new AssertionError("image_ui_idle_timeout");
            Thread.sleep(25);
        }
    }
    private <T> T onMain(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        test.runOnMainSync(task);
        return task.get(10, TimeUnit.SECONDS);
    }
    private static void check(boolean condition, String name) { if (!condition) throw new AssertionError(name); }
}
