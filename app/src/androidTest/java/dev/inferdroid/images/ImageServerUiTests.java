package dev.inferdroid.images;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.IBinder;
import android.widget.CheckBox;
import android.widget.EditText;
import dev.inferdroid.InferenceService;
import dev.inferdroid.MainActivity;
import dev.inferdroid.R;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Main-screen controls across image imports, including image-only server startup. */
final class ImageServerUiTests {
    private final Instrumentation test;
    private final Context target;
    private MainActivity activity;
    private InferenceService service;

    ImageServerUiTests(Instrumentation test) { this.test = test; target = test.getTargetContext(); }

    void run() throws Exception {
        SharedPreferences server = target.getSharedPreferences("server", Context.MODE_PRIVATE);
        SharedPreferences inference = target.getSharedPreferences("inference", Context.MODE_PRIVATE);
        Map<String, ?> savedServer = server.getAll(), savedInference = inference.getAll();
        CountDownLatch connected = new CountDownLatch(1);
        ServiceConnection connection = new ServiceConnection() {
            public void onServiceConnected(ComponentName name, IBinder binder) {
                service = ((InferenceService.LocalBinder) binder).getService(); connected.countDown();
            }
            public void onServiceDisconnected(ComponentName name) { service = null; }
        };
        boolean bound = false;
        try {
            activity = (MainActivity) test.startActivitySync(new Intent(target, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            bound = onMain(() -> target.bindService(new Intent(target, InferenceService.class), connection, Context.BIND_AUTO_CREATE));
            check(bound && connected.await(10, TimeUnit.SECONDS), "image_server_ui_service_binding");
            waitFor(() -> enabled(R.id.start_server), "main_server_initially_ready");

            onMain(() -> {
                check(service.importImageModels(Uri.parse("content://dev.inferdroid.missing/tree/server-ui")), "image_import_admitted");
                // Returning to the main screen takes this snapshot while import is pending.
                // Keep both operations on main so the asynchronous completion cannot race it.
                activity.onStateChanged(service.getManager().getState());
                check(!activity.findViewById(R.id.start_server).isEnabled(), "main_server_disabled_during_import");
                return null;
            });
            waitFor(() -> onMain(() -> !service.getImages().isBusy()), "image_import_completed");
            check(enabled(R.id.start_server), "main_server_not_refreshed_after_image_import");

            onMain(() -> {
                ((EditText) activity.findViewById(R.id.model_source)).setText("");
                ((EditText) activity.findViewById(R.id.server_port)).setText("18082");
                activity.findViewById(R.id.start_server).performClick();
                return null;
            });
            waitFor(() -> onMain(service::isServerRunning), "main_ui_starts_image_server_without_chat_model");
            check(!enabled(R.id.start_server) && enabled(R.id.stop_server), "main_server_running_controls");
            onMain(() -> {
                check(service.importImageModels(Uri.parse("content://dev.inferdroid.missing/tree/unload-import")), "unload_import_admitted");
                check(service.unloadImages(), "unload_during_import_admitted");
                check(service.getImages().isBusy(), "unload_keeps_import_admission_until_drain");
                check(!service.generateImage("Must wait for image unload", 0, null), "generation_admitted_during_image_unload");
                return null;
            });
            waitFor(() -> onMain(() -> !service.getImages().isBusy()), "image_import_unload_completed");
            check(onMain(() -> service.getImages().status().equals(target.getString(R.string.image_unloaded))), "image_import_unload_status");
            check(onMain(service::isServerRunning) && enabled(R.id.stop_server), "image_unload_keeps_server_running");
            String key = onMain(() -> ((EditText) activity.findViewById(R.id.api_key)).getText().toString());
            boolean requireKey = onMain(() -> ((CheckBox) activity.findViewById(R.id.require_api_key)).isChecked());
            try (Socket socket = new Socket("127.0.0.1", 18082)) {
                socket.setSoTimeout(5000);
                String headers = "GET /v1/models HTTP/1.1\r\nHost: localhost\r\n"
                        + (requireKey ? "Authorization: Bearer " + key + "\r\n" : "") + "\r\n";
                socket.getOutputStream().write(headers.getBytes(StandardCharsets.UTF_8));
                String wire = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                check(wire.startsWith("HTTP/1.1 200 "), "main_ui_started_server_responds");
                JSONArray models = new JSONObject(wire.substring(wire.indexOf("\r\n\r\n") + 4)).getJSONArray("data");
                boolean image = false;
                for (int i = 0; i < models.length(); i++) image |= ImageModels.MODEL_ID.equals(models.getJSONObject(i).getString("id"));
                check(image == onMain(() -> service.getImages().isAvailable()), "main_ui_started_server_image_discovery");
            }
            onMain(() -> { activity.findViewById(R.id.stop_server).performClick(); return null; });
            check(onMain(() -> !service.isServerRunning()) && enabled(R.id.start_server), "main_ui_stops_and_can_restart_server");
        } finally {
            onMain(() -> {
                if (service != null) service.stopServer();
                if (activity != null) activity.finish();
                return null;
            });
            if (bound) onMain(() -> { target.unbindService(connection); return null; });
            test.waitForIdleSync();
            restore(server, savedServer); restore(inference, savedInference);
        }
    }

    private boolean enabled(int id) throws Exception { return onMain(() -> activity.findViewById(id).isEnabled()); }
    private void waitFor(Callable<Boolean> condition, String name) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.call()) {
            if (System.nanoTime() > deadline) throw new AssertionError(name);
            Thread.sleep(25);
        }
    }
    private <T> T onMain(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        test.runOnMainSync(task);
        return task.get(10, TimeUnit.SECONDS);
    }
    @SuppressWarnings("unchecked")
    private static void restore(SharedPreferences preferences, Map<String, ?> saved) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        saved.forEach((key, value) -> {
            if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof Set) editor.putStringSet(key, (Set<String>) value);
        });
        editor.commit();
    }
    private static void check(boolean condition, String name) { if (!condition) throw new AssertionError(name); }
}
