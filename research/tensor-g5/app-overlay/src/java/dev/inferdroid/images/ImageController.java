package dev.inferdroid.images;

import android.app.*;
import android.content.*;
import android.os.*;
import dev.inferdroid.InferenceService;
import dev.inferdroid.engine.WorkGate;
import java.io.File;
import java.util.*;

/** Main-process owner: shared admission gate, Binder lifecycle and result metadata. */
public final class ImageController {
    public interface Callback {void complete(Result result);}
    public static final class Result {
        public final File image;public final String error;public final boolean cancelled;
        Result(File image,String error,boolean cancelled) {this.image=image;this.error=error;this.cancelled=cancelled;}
    }
    private final Context context;
    private final WorkGate gate;
    private final Runnable changed;
    private final ImageModels models;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Set<Runnable> listeners=new LinkedHashSet<>();
    private final List<Runnable> stopped=new ArrayList<>();
    private final PowerManager.WakeLock wake;
    private ServiceConnection connection;
    private Messenger remote;
    private int remotePid;
    private String id;
    private Callback callback;
    private volatile boolean busy;
    private boolean cancelled;
    private File image;
    private String status="Experimental SDXS · 512×512 · CPU + Tensor G5 NPU";
    private final Runnable timeout=()->cancel(null);
    private final Messenger inbox=new Messenger(new Handler(Looper.getMainLooper(),message->{
        if(!busy||!Objects.equals(id,message.getData().getString("id"))) return true;
        remotePid=message.arg1;
        String text=message.getData().getString("text","");
        if(message.what==ImageWorkerService.PROGRESS) {status=text;publish();}
        else if(message.what==ImageWorkerService.SUCCESS) finish(cancelled?new Result(null,"Image generation cancelled.",true):new Result(new File(text),null,false));
        else if(message.what==ImageWorkerService.FAILURE) finish(new Result(null,text,false));
        return true;
    }));
    public ImageController(Context context,WorkGate gate,Runnable changed) {
        this.context=context;this.gate=gate;this.changed=changed;
        try {models=new ImageModels(context);} catch(Exception error) {throw new IllegalStateException("Private image assets are missing.",error);}
        wake=context.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"InferDroid:images");wake.setReferenceCounted(false);
        String path=context.getSharedPreferences("images",Context.MODE_PRIVATE).getString("last_image",null);
        if(path!=null&&new File(path).isFile()) image=new File(path);
    }
    public boolean isAvailable() {return models.available();}
    public boolean isBusy() {return busy;}
    public boolean canCancel() {return busy&&connection!=null&&!cancelled;}
    public String status() {return status;}
    public File image() {return image;}
    public void attach(Runnable listener) {listeners.add(listener);listener.run();}
    public void detach(Runnable listener) {listeners.remove(listener);}
    public boolean generate(String prompt,long seed,Callback callback) {
        if(busy||!gate.acquire(this)) return false;
        busy=true;cancelled=false;this.callback=callback;id=UUID.randomUUID().toString();
        status="Starting the image worker…";wake.acquire(10*60*1000L);publish();
        connection=new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name,IBinder binder) {
                if(connection!=this||!busy) return;
                remote=new Messenger(binder);
                Message message=Message.obtain(null,ImageWorkerService.GENERATE);message.replyTo=inbox;
                Bundle data=new Bundle();data.putString("id",id);data.putString("prompt",prompt);data.putLong("seed",seed);message.setData(data);
                try {remote.send(message);} catch(RemoteException error) {finish(new Result(null,"Cannot contact the image worker.",false));}
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                if(connection==this) finish(new Result(null,cancelled?"Image generation cancelled.":"Image worker stopped; retry when memory is available.",cancelled));
            }
            @Override public void onBindingDied(ComponentName name) {onServiceDisconnected(name);}
            @Override public void onNullBinding(ComponentName name) {onServiceDisconnected(name);}
        };
        if(!context.bindService(new Intent(context,ImageWorkerService.class),connection,Context.BIND_AUTO_CREATE|Context.BIND_IMPORTANT)) {
            finish(new Result(null,"Cannot bind the image worker.",false));
        } else main.postDelayed(timeout,5*60*1000L);
        return true;
    }
    public boolean importModels(android.net.Uri folder) {
        if(busy||!gate.acquire(this)) return false;
        busy=true;cancelled=false;status="Importing and checking local image models…";wake.acquire(10*60*1000L);publish();
        new Thread(()->{
            Result result;
            try {models.importTree(folder);result=new Result(null,null,false);}
            catch(Exception error) {result=new Result(null,"Model import failed: "+error.getMessage(),false);}
            Result completed=result;main.post(()->finish(completed));
        },"InferDroid-image-import").start();return true;
    }
    public void cancel(Callback owner) {
        if(!busy||(owner!=null&&callback!=owner)) return;
        cancelled=true;status="Cancelling image generation…";publish();
        if(connection==null) return; // An atomic model import finishes before releasing admission.
        if(remote==null) {finish(new Result(null,"Image generation cancelled.",true));return;}
        try {remote.send(Message.obtain(null,ImageWorkerService.CANCEL));}
        catch(RemoteException error) {finish(new Result(null,"Image worker stopped.",true));}
        if(remotePid>0&&remotePid!=android.os.Process.myPid()) android.os.Process.killProcess(remotePid);
    }
    public void shutdown(Runnable done) {if(done!=null) stopped.add(done);if(busy) cancel(null);else drain();}
    public void close() {cancel(null);listeners.clear();}
    private void finish(Result result) {
        if(!busy) return;
        main.removeCallbacks(timeout);
        if(connection!=null) {try {context.unbindService(connection);} catch(IllegalArgumentException ignored) { }connection=null;}
        remote=null;remotePid=0;busy=false;gate.release(this);if(wake.isHeld()) wake.release();
        Callback owner=callback;callback=null;
        if(result.image!=null) {image=result.image;context.getSharedPreferences("images",Context.MODE_PRIVATE).edit().putString("last_image",image.toString()).apply();}
        status=result.error!=null?result.error:result.image!=null?"Image generated · SDXS · CPU + Tensor G5 NPU":"Local image models imported.";
        publish();if(owner!=null) owner.complete(result);drain();
    }
    private void drain() {List<Runnable> pending=new ArrayList<>(stopped);stopped.clear();for(Runnable action:pending) action.run();}
    private void publish() {for(Runnable listener:new ArrayList<>(listeners)) listener.run();changed.run();}
    public Notification notification(String channel) {
        PendingIntent open=PendingIntent.getActivity(context,30,new Intent(context,ImageActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent cancel=PendingIntent.getService(context,31,new Intent(context,InferenceService.class).setAction("dev.inferdroid.CANCEL_INFERENCE"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(context,channel).setSmallIcon(dev.inferdroid.R.drawable.ic_launcher_monochrome)
            .setContentTitle("InferDroid · Image generation").setContentText(status).setSubText("SDXS · CPU + Tensor G5 NPU")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(new Notification.Action.Builder(null,"Cancel",cancel).build()).build();
    }
}
