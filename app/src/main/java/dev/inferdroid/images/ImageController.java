package dev.inferdroid.images;

import android.app.*;
import android.content.*;
import android.os.*;
import dev.inferdroid.InferenceService;
import dev.inferdroid.R;
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
    private volatile boolean unloading;
    private boolean runtimeReleased;
    private boolean cancelled;
    private File image;
    private String status;
    private final Runnable timeout=()->cancel(null);
    private final Messenger inbox=new Messenger(new Handler(Looper.getMainLooper(),message->{
        if(!busy||!Objects.equals(id,message.getData().getString("id"))) return true;
        remotePid=message.arg1;
        String text=message.getData().getString("text","");
        if(message.what==ImageWorkerService.PROGRESS) {status=text;publish();}
        else if(message.what==ImageWorkerService.SUCCESS) finish(cancelled?new Result(null,localized(R.string.image_cancelled),true):new Result(new File(text),null,false));
        else if(message.what==ImageWorkerService.FAILURE) finish(new Result(null,cancelled?localized(R.string.image_cancelled):text,cancelled));
        return true;
    }));
    public ImageController(Context context,WorkGate gate,Runnable changed) {
        this.context=context;this.gate=gate;this.changed=changed;
        status=context.getString(R.string.image_backend_status);
        try {models=new ImageModels(context);} catch(Exception error) {throw new IllegalStateException("Private image assets are missing.",error);}
        wake=context.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"InferDroid:images");wake.setReferenceCounted(false);
        String path=context.getSharedPreferences(ImageModels.PREFERENCES,Context.MODE_PRIVATE).getString("last_image",null);
        if(path!=null&&new File(path).isFile()) image=new File(path);
    }
    public boolean isAvailable() {return models.available();}
    private String localized(int resource) {return context.getString(resource);}
    public boolean matchesModel(android.net.Uri folder) {return models.matches(folder);}
    public boolean isBusy() {return busy||unloading;}
    public boolean canCancel() {return busy&&connection!=null&&!cancelled&&!unloading;}
    public boolean canUnload() {return !unloading&&(busy||!runtimeReleased&&isAvailable());}
    public String status() {return status;}
    public File image() {return image;}
    public void attach(Runnable listener) {listeners.add(listener);listener.run();}
    public void detach(Runnable listener) {listeners.remove(listener);}
    public boolean generate(String prompt,long seed,Callback callback) {
        return generate(prompt,seed,null,callback);
    }
    public boolean generate(String prompt,long seed,android.net.Uri folder,Callback callback) {
        if(isBusy()||!gate.acquire(this)) return false;
        busy=true;cancelled=false;runtimeReleased=false;this.callback=callback;id=UUID.randomUUID().toString();
        wake.acquire(10*60*1000L);
        if(folder!=null&&!models.matches(folder)) importModel(folder,()->startGeneration(prompt,seed));
        else startGeneration(prompt,seed);
        return true;
    }
    private void startGeneration(String prompt,long seed) {
        status=context.getString(R.string.image_worker_starting);publish();
        connection=new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name,IBinder binder) {
                if(connection!=this||!busy) return;
                remote=new Messenger(binder);
                Message message=Message.obtain(null,ImageWorkerService.GENERATE);message.replyTo=inbox;
                Bundle data=new Bundle();data.putString("id",id);data.putString("prompt",prompt);data.putLong("seed",seed);message.setData(data);
                try {remote.send(message);} catch(RemoteException error) {finish(new Result(null,context.getString(R.string.image_worker_unreachable),false));}
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                if(connection==this) finish(new Result(null,context.getString(cancelled?R.string.image_cancelled:R.string.image_worker_stopped),cancelled));
            }
            @Override public void onBindingDied(ComponentName name) {onServiceDisconnected(name);}
            @Override public void onNullBinding(ComponentName name) {onServiceDisconnected(name);}
        };
        if(!context.bindService(new Intent(context,ImageWorkerService.class),connection,Context.BIND_AUTO_CREATE|Context.BIND_IMPORTANT)) {
            finish(new Result(null,context.getString(R.string.image_worker_unreachable),false));
        } else main.postDelayed(timeout,5*60*1000L);
    }
    public boolean importModels(android.net.Uri folder) {
        if(isBusy()||!gate.acquire(this)) return false;
        busy=true;cancelled=false;runtimeReleased=false;wake.acquire(10*60*1000L);
        importModel(folder,()->finish(new Result(null,null,false)));
        return true;
    }
    private void importModel(android.net.Uri folder,Runnable imported) {
        status=context.getString(R.string.image_model_importing);publish();
        new Thread(()->{
            try {
                models.importTree(folder);
                main.post(()->{if(cancelled) finish(new Result(null,context.getString(R.string.image_cancelled),true));else imported.run();});
            } catch(Exception error) {
                String message=context.getString(R.string.image_model_import_failed,error.getMessage());
                main.post(()->finish(new Result(null,message,false)));
            }
        },"InferDroid-image-import").start();
    }
    public void cancel(Callback owner) {
        if(!busy||(owner!=null&&callback!=owner)) return;
        cancelled=true;status=context.getString(unloading?R.string.image_unloading:R.string.image_cancelling);publish();
        if(connection==null) return; // An atomic model import finishes before releasing admission.
        if(remote==null) {finish(new Result(null,context.getString(R.string.image_cancelled),true));return;}
        try {remote.send(Message.obtain(null,ImageWorkerService.CANCEL));}
        catch(RemoteException error) {finish(new Result(null,context.getString(R.string.image_cancelled),true));}
        if(remotePid>0&&remotePid!=android.os.Process.myPid()) android.os.Process.killProcess(remotePid);
    }
    /** Release only the image process; imported weights and API availability are preserved. */
    public void unload(Runnable done) {
        if(done!=null) stopped.add(done);
        if(unloading) return;
        if(runtimeReleased&&!busy) {drain();return;}
        unloading=true;gate.hold(this);
        if(!wake.isHeld()) wake.acquire(10*60*1000L);
        status=context.getString(R.string.image_unloading);publish();
        if(busy) cancel(null);else releaseWorker();
    }
    public void shutdown(Runnable done) {unload(done);}
    public void close() {cancel(null);listeners.clear();}
    private void finish(Result result) {
        if(!busy) return;
        main.removeCallbacks(timeout);
        if(connection!=null) {try {context.unbindService(connection);} catch(IllegalArgumentException ignored) { }connection=null;}
        remote=null;remotePid=0;busy=false;
        if(!unloading) {gate.release(this);if(wake.isHeld()) wake.release();}
        Callback owner=callback;callback=null;
        if(result.image!=null) {image=result.image;context.getSharedPreferences(ImageModels.PREFERENCES,Context.MODE_PRIVATE).edit().putString("last_image",image.toString()).apply();}
        status=unloading?context.getString(R.string.image_unloading)
                :result.error!=null?result.error:context.getString(result.image!=null?R.string.image_generated:R.string.image_model_ready);
        publish();if(owner!=null) owner.complete(result);
        if(unloading) releaseWorker();else drain();
    }
    private void releaseWorker() {
        // The bound service has drained. Android may still cache its process,
        // including native libraries/allocator memory, after unbinding it.
        new Thread(()->{
            boolean released=false;
            try {
                context.stopService(new Intent(context,ImageWorkerService.class));
                ActivityManager manager=context.getSystemService(ActivityManager.class);
                long deadline=SystemClock.elapsedRealtime()+5000;
                while(true) {
                    List<ActivityManager.RunningAppProcessInfo> processes=manager.getRunningAppProcesses();
                    boolean found=false;
                    if(processes!=null) for(ActivityManager.RunningAppProcessInfo process:processes) {
                        if(process.uid==android.os.Process.myUid()
                                &&(context.getPackageName()+":images").equals(process.processName)
                                &&process.pid!=android.os.Process.myPid()) {
                            found=true;android.os.Process.killProcess(process.pid);
                        }
                    }
                    if(!found) {released=true;break;}
                    if(SystemClock.elapsedRealtime()>=deadline) break;
                    Thread.sleep(50);
                }
            } catch(Exception error) {
                if(error instanceof InterruptedException) Thread.currentThread().interrupt();
            }
            boolean success=released;
            main.post(()->{
                unloading=false;runtimeReleased=success;gate.release(this);
                if(wake.isHeld()) wake.release();
                status=context.getString(success?R.string.image_unloaded:R.string.image_unload_failed);
                publish();drain();
            });
        },"InferDroid-image-unload").start();
    }
    private void drain() {List<Runnable> pending=new ArrayList<>(stopped);stopped.clear();for(Runnable action:pending) action.run();}
    private void publish() {for(Runnable listener:new ArrayList<>(listeners)) listener.run();changed.run();}
    public Notification notification(String channel) {
        PendingIntent open=PendingIntent.getActivity(context,30,new Intent(context,ImageActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent cancel=PendingIntent.getService(context,31,new Intent(context,InferenceService.class).setAction("dev.inferdroid.CANCEL_INFERENCE"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(context,channel).setSmallIcon(dev.inferdroid.R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.image_notification_title)).setContentText(status).setSubText(context.getString(R.string.image_backend_status))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(new Notification.Action.Builder(null,context.getString(R.string.cancel),cancel).build()).build();
    }
}
