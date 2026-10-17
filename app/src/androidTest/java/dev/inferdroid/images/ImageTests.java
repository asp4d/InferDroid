package dev.inferdroid.images;

import android.app.*;
import android.content.*;
import android.os.*;
import android.graphics.BitmapFactory;
import dev.inferdroid.InferenceService;
import dev.inferdroid.MainActivity;
import dev.inferdroid.R;
import dev.inferdroid.server.ServerConfig;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;

/** Real model, tokenization, HTTP admission, disconnect and recovery checks. */
public final class ImageTests {
    private final Instrumentation instrumentation;
    private static final int PORT=18081;
    private static final String KEY="private-image-instrumentation";
    private final JSONObject report=new JSONObject();
    private InferenceService service;
    private Context target;
    private ImageActivity imageActivity;
    private final boolean uiOnly;
    private final String prompt="A small red robot reading a book in a sunny garden, digital illustration";
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private ImageTests(Instrumentation instrumentation, boolean uiOnly) { this.instrumentation=instrumentation; this.uiOnly=uiOnly; }
    public static void run(Instrumentation instrumentation, boolean uiOnly) { new ImageTests(instrumentation,uiOnly).run(); }
    private void run() {
        target=instrumentation.getTargetContext();Bundle result=new Bundle();int outcome=Activity.RESULT_CANCELED;
        ServiceConnection connection=null;
        try {
            stage("image_ui");
            new ImageUiTests(instrumentation).run();
            report.put("image_ui_passed",true);save();
            if(!uiOnly) {
            stage("tokenizer");
            JSONArray fixtures=new JSONArray(new String(instrumentation.getContext().getAssets().open("image-generation/tokenizer-fixtures.json").readAllBytes(),StandardCharsets.UTF_8));
            ClipTokenizer tokenizer=new ClipTokenizer(target);
            for(int i=0;i<fixtures.length();i++) {
                JSONObject fixture=fixtures.getJSONObject(i);int[] actual=tokenizer.encode(fixture.getString("prompt"));JSONArray ids=fixture.getJSONArray("ids");
                for(int j=0;j<actual.length;j++) check(actual[j]==ids.getInt(j),"tokenizer_fixture_"+i+"_token_"+j);
            }
            report.put("tokenizer_fixtures_passed",fixtures.length());save();
            instrumentation.startActivitySync(new Intent(target,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            CountDownLatch bound=new CountDownLatch(1);
            connection=new ServiceConnection() {
                public void onServiceConnected(ComponentName name,IBinder binder) {service=((InferenceService.LocalBinder)binder).getService();bound.countDown();}
                public void onServiceDisconnected(ComponentName name) {service=null;}
            };
            ServiceConnection binding=connection;
            instrumentation.runOnMainSync(()->target.bindService(new Intent(target,InferenceService.class),binding,Context.BIND_AUTO_CREATE));
            check(bound.await(10,TimeUnit.SECONDS),"service_binding");
            check(onMain(()->service.getImages().isAvailable()),"local_models_present");
            onMain(()->{service.startServer(new ServerConfig(PORT,KEY,true,false),"",false);return true;});
            stage("http_contracts");
            check(request("GET","/v1/models",null,false).status==401,"authentication");
            Reply discovery=request("GET","/v1/models",null,true);
            check(discovery.status==200&&discovery.body.contains(ImageModels.MODEL_ID),"image_model_discovery");
            for(String invalid:new String[]{"{\"prompt\":\"x\",\"n\":2}","{\"prompt\":\"x\",\"size\":\"1024x1024\"}","{\"prompt\":\"x\",\"response_format\":\"url\"}","{\"prompt\":\"\"}","{\"prompt\":\"x\",\"seed\":1.5}"}) {
                check(request("POST","/v1/images/generations",invalid,true).status==400,"parameter_validation");
            }
            report.put("http_contracts_passed",true);save();
            stage("generate_first");
            Future<Reply> first=io.submit(()->request("POST","/v1/images/generations",body(0),true));
            waitFor(()->onMain(()->service.getImages().isBusy()),15,"image_admission");
            check(request("POST","/v1/images/generations",body(1),true).status==429,"concurrent_image_rejected");
            check(!onMain(()->service.getManager().load("unused-test-path",false)),"chat_admission_serialized");
            checkImage(first.get(6,TimeUnit.MINUTES));
            report.put("first_image",onMain(()->service.getImages().image().toString()));
            report.put("busy_gate_passed",true);save();
            stage("unload_idle_and_active");
            imageActivity=(ImageActivity)instrumentation.startActivitySync(new Intent(target,ImageActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitFor(()->onMain(()->imageActivity.findViewById(R.id.unload_image_model).isEnabled()),15,"image_unload_ui_ready");
            File previousImage=onMain(()->service.getImages().image());
            String selection=target.getSharedPreferences(ImageModels.PREFERENCES,Context.MODE_PRIVATE).getString(ImageModels.KEY_MODEL,null);
            var chat=onMain(()->service.getManager().getState());
            var speech=onMain(()->service.getSpeechManager().getState());
            var tts=onMain(()->service.getTtsManager().getState());
            onMain(()->imageActivity.findViewById(R.id.unload_image_model).performClick());
            checkUnloaded(previousImage,selection);
            report.put("idle_unload_passed",true);save();
            Future<Reply> stopped=io.submit(()->request("POST","/v1/images/generations",body(2),true));
            waitFor(()->onMain(()->service.getImages().canCancel()
                    &&!service.getImages().status().equals(target.getString(R.string.image_worker_starting))),15,"active_worker_started");
            check(onMain(()->imageActivity.findViewById(R.id.unload_image_model).isEnabled()),"image_unload_available_during_generation");
            onMain(()->imageActivity.findViewById(R.id.unload_image_model).performClick());
            Reply cancelled=stopped.get(30,TimeUnit.SECONDS);
            check(cancelled.status==503&&new JSONObject(cancelled.body).getJSONObject("error").getString("code").equals("image_cancelled"),"image_unload_cancels_api_owner");
            checkUnloaded(previousImage,selection);
            check(onMain(()->service.getManager().getState().loaded==chat.loaded&&service.getManager().getState().phase==chat.phase
                    &&service.getSpeechManager().getState().loaded==speech.loaded&&service.getSpeechManager().getState().phase==speech.phase
                    &&service.getTtsManager().getState().loaded==tts.loaded&&service.getTtsManager().getState().phase==tts.phase),"image_unload_preserves_other_engines");
            report.put("active_unload_passed",true);save();
            stage("disconnect_and_recovery");
            try(Socket socket=new Socket("127.0.0.1",PORT)) {
                byte[] bytes=body(9).getBytes(StandardCharsets.UTF_8);
                OutputStream stream=socket.getOutputStream();
                stream.write(("POST /v1/images/generations HTTP/1.1\r\nHost: 127.0.0.1:"+PORT+"\r\nAuthorization: Bearer "+KEY+"\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));stream.write(bytes);stream.flush();
                waitFor(()->onMain(()->service.getImages().isBusy()),15,"disconnect_request_admitted");
            }
            waitFor(()->!onMain(()->service.getImages().isBusy()),20,"disconnect_cancelled_worker");
            report.put("disconnect_cancel_passed",true);save();
            checkImage(request("POST","/v1/images/generations",body(7),true));
            report.put("recovery_image",onMain(()->service.getImages().image().toString()));
            report.put("recovery_passed",true);
            report.put("reload_after_unload_passed",true);
            }
            report.put("passed",true);stage("completed");
            result.putString("image_generation_result","passed");
            outcome=Activity.RESULT_OK;
        } catch(Throwable error) {
            try {JSONArray causes=new JSONArray();Throwable cause=error;for(int i=0;cause!=null&&i<8;i++,cause=cause.getCause()) causes.put(cause.getClass().getSimpleName()+": "+cause.getMessage());report.put("passed",false).put("failure",error.getClass().getSimpleName()+": "+error.getMessage()).put("failure_causes",causes);save();} catch(Exception ignored) { }
            result.putString("image_generation_result","failed; inspect the private application verification report");
        } finally {
            io.shutdownNow();
            if(service!=null) instrumentation.runOnMainSync(()->service.stopServer());
            if(imageActivity!=null) instrumentation.runOnMainSync(()->imageActivity.finish());
            if(connection!=null) {ServiceConnection binding=connection;instrumentation.runOnMainSync(()->target.unbindService(binding));}
        }
        instrumentation.finish(outcome,result);
    }
    private void checkUnloaded(File previousImage,String selection) throws Exception {
        waitFor(()->onMain(()->!service.getImages().isBusy()),15,"image_unload_drained");
        check(onMain(()->service.getImages().status().equals(target.getString(R.string.image_unloaded))),"image_unload_completed");
        check(!onMain(()->imageActivity.findViewById(R.id.unload_image_model).isEnabled()),"image_unload_button_reset");
        List<ActivityManager.RunningAppProcessInfo> processes=target.getSystemService(ActivityManager.class).getRunningAppProcesses();
        if(processes!=null)for(ActivityManager.RunningAppProcessInfo process:processes) {
            check(process.uid!=android.os.Process.myUid()||!process.processName.equals(target.getPackageName()+":images"),"image_unload_releases_process");
        }
        check(onMain(()->service.isServerRunning()&&service.getImages().isAvailable()),"image_unload_keeps_server_and_models");
        check(onMain(()->previousImage.equals(service.getImages().image()))&&previousImage.isFile(),"image_unload_keeps_preview");
        check(Objects.equals(selection,target.getSharedPreferences(ImageModels.PREFERENCES,Context.MODE_PRIVATE).getString(ImageModels.KEY_MODEL,null)),"image_unload_keeps_folder");
        Reply discovery=request("GET","/v1/models",null,true);
        check(discovery.status==200&&discovery.body.contains(ImageModels.MODEL_ID),"image_unload_keeps_model_discovery");
    }
    private void checkImage(Reply reply) throws Exception {
        if(reply.status!=200) {report.put("http_failure_response",reply.body);save();}
        check(reply.status==200,"image_http_status_"+reply.status);
        JSONObject response=new JSONObject(reply.body);byte[] png=android.util.Base64.decode(response.getJSONArray("data").getJSONObject(0).getString("b64_json"),android.util.Base64.DEFAULT);
        android.graphics.Bitmap bitmap=BitmapFactory.decodeByteArray(png,0,png.length);
        check(bitmap!=null&&bitmap.getWidth()==512&&bitmap.getHeight()==512,"png_dimensions");bitmap.recycle();
    }
    private String body(long seed) throws Exception {return new JSONObject().put("model",ImageModels.MODEL_ID).put("prompt",prompt).put("seed",seed).put("n",1).put("size","512x512").put("response_format","b64_json").put("quality","auto").put("output_format","png").put("background","auto").put("stream",false).toString();}
    private Reply request(String method,String path,String body,boolean authenticate) throws Exception {
        try(Socket socket=new Socket("127.0.0.1",PORT)) {
            socket.setSoTimeout(6*60*1000);
            byte[] bytes=body==null?new byte[0]:body.getBytes(StandardCharsets.UTF_8);
            String headers=method+" "+path+" HTTP/1.1\r\nHost: 127.0.0.1:"+PORT+"\r\n"+(authenticate?"Authorization: Bearer "+KEY+"\r\n":"")+"Content-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n";
            OutputStream stream=socket.getOutputStream();stream.write(headers.getBytes(StandardCharsets.US_ASCII));stream.write(bytes);stream.flush();
            String response=new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
            int split=response.indexOf("\r\n\r\n");check(split>=0,"http_response_headers");
            return new Reply(Integer.parseInt(response.substring(0,response.indexOf("\r\n")).split(" ")[1]),response.substring(split+4));
        }
    }
    private static final class Reply {final int status;final String body;Reply(int status,String body) {this.status=status;this.body=body;}}
    private <T>T onMain(Callable<T> action) throws Exception {FutureTask<T> task=new FutureTask<>(action);instrumentation.runOnMainSync(task);return task.get(10,TimeUnit.SECONDS);}
    private void waitFor(Callable<Boolean> condition,int seconds,String name) throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);while(System.nanoTime()<until) {if(condition.call()) return;Thread.sleep(25);}throw new AssertionError(name);}
    private void check(boolean value,String name) {if(!value) throw new AssertionError(name);}
    private void stage(String name) throws Exception {report.put("stage",name);save();}
    private void save() throws Exception {try(OutputStream stream=new FileOutputStream(new File(target.getFilesDir(),"image-app-verification.json"))) {stream.write(report.toString(2).getBytes(StandardCharsets.UTF_8));}}
}
