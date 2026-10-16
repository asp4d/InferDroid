package dev.inferdroid.images;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import java.io.File;
import java.util.concurrent.Executors;

/** A separate process holds the image LiteRT ABI and can be cancelled safely. */
public final class ImageWorkerService extends Service {
    static final int GENERATE=1,CANCEL=2,PROGRESS=3,SUCCESS=4,FAILURE=5;
    private final java.util.concurrent.ExecutorService worker=Executors.newSingleThreadExecutor();
    private boolean running;
    private final Messenger endpoint=new Messenger(new Handler(Looper.getMainLooper(),message->{
        if(message.what==CANCEL) {android.os.Process.killProcess(android.os.Process.myPid());return true;}
        if(message.what!=GENERATE||running||message.replyTo==null) return true;
        running=true;
        Bundle data=message.getData();String id=data.getString("id");Messenger reply=message.replyTo;
        worker.execute(()->{
            try {
                File directory=new File(getFilesDir(),"image-runs/"+id);
                if(!directory.mkdirs()) throw new java.io.IOException("Cannot create image output storage.");
                File image=SdxsPipeline.generate(this,data.getString("prompt"),data.getLong("seed"),directory,
                    text->send(reply,PROGRESS,id,text));
                send(reply,SUCCESS,id,image.toString());
            } catch(Exception|LinkageError|OutOfMemoryError error) {
                // Own sanitized API errors are useful locally; native logs remain on device.
                send(reply,FAILURE,id,error instanceof java.io.IOException?error.getMessage():"Image generation failed; inspect private device diagnostics.");
            } finally {new Handler(Looper.getMainLooper()).post(()->{running=false;stopSelf();});}
        });return true;
    }));
    private static void send(Messenger reply,int what,String id,String text) {
        Message result=Message.obtain(null,what);result.arg1=android.os.Process.myPid();Bundle data=new Bundle();data.putString("id",id);data.putString("text",text);result.setData(data);
        try {reply.send(result);} catch(RemoteException ignored) {android.os.Process.killProcess(android.os.Process.myPid());}
    }
    @Override public IBinder onBind(Intent intent) {return endpoint.getBinder();}
    @Override public void onDestroy() {worker.shutdown();super.onDestroy();}
}
