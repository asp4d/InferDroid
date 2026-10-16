package dev.inferdroid.images;

import android.content.*;
import android.os.*;
import android.graphics.BitmapFactory;
import android.view.ViewGroup;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import dev.inferdroid.InferenceService;
import dev.inferdroid.ThemeHelper;
import java.io.*;

/** Local image UI for the private build. */
public final class ImageActivity extends AppCompatActivity {
    private InferenceService service;
    private ImageController controller;
    private TextView status;
    private EditText prompt,seed;
    private Button generate,cancel,importModels,save;
    private ImageView preview;
    private File displayed;
    private String pendingSavePath;
    private android.net.Uri pendingImport;
    private final java.util.concurrent.ExecutorService io=java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Runnable changed=this::render;
    private final ServiceConnection connection=new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name,IBinder binder) {service=((InferenceService.LocalBinder)binder).getService();controller=service.getImages();controller.attach(changed);if(pendingImport!=null) {android.net.Uri folder=pendingImport;pendingImport=null;service.importImageModels(folder);}}
        @Override public void onServiceDisconnected(ComponentName name) {service=null;controller=null;render();}
    };
    @Override public void onCreate(Bundle state) {
        ThemeHelper.applyTheme(this);super.onCreate(state);
        pendingSavePath=state==null?null:state.getString("save_image_path");
        ScrollView scroll=new ScrollView(this);LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);int padding=(int)(20*getResources().getDisplayMetrics().density);body.setPadding(padding,padding,padding,padding);scroll.addView(body);setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((view,insets)->{android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout()|android.view.WindowInsets.Type.ime());view.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});scroll.requestApplyInsets();
        TextView title=new TextView(this);title.setText("Generate images");title.setTextSize(25);body.addView(title);
        TextView description=new TextView(this);description.setText("SDXS · 512×512 · one step\nExperimental image quality · generated entirely on this phone");body.addView(description);
        prompt=new EditText(this);prompt.setHint("Describe an image");prompt.setMinLines(3);prompt.setText(state==null?"A small red robot reading a book in a sunny garden, digital illustration":state.getString("prompt"));body.addView(prompt);
        seed=new EditText(this);seed.setHint("Seed");seed.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);seed.setText(state==null?"0":state.getString("seed"));body.addView(seed);
        generate=button(body,"Generate image",()->{try {if(service==null||!service.generateImage(prompt.getText().toString(),Long.parseLong(seed.getText().toString()),null)) status.setText("Inference is busy; retry when idle.");} catch(Exception error) {status.setText("Enter a prompt and an integer seed.");}});
        cancel=button(body,"Cancel",()->{if(controller!=null) controller.cancel(null);});
        importModels=button(body,"Import local model folder",()->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),10));
        save=button(body,"Save image",()->{if(displayed!=null) {pendingSavePath=displayed.toString();startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("image/png").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"inferdroid-image.png"),11);}});
        status=new TextView(this);status.setTextIsSelectable(true);body.addView(status);
        preview=new ImageView(this);preview.setAdjustViewBounds(true);preview.setContentDescription("Generated image");body.addView(preview,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        button(body,"Back to InferDroid",this::finish);render();
    }
    private Button button(LinearLayout parent,String text,Runnable action) {Button button=new com.google.android.material.button.MaterialButton(this);button.setText(text);button.setOnClickListener(view->action.run());parent.addView(button);return button;}
    @Override protected void onStart() {super.onStart();bindService(new Intent(this,InferenceService.class),connection,BIND_AUTO_CREATE);}
    @Override protected void onStop() {if(controller!=null) controller.detach(changed);unbindService(connection);controller=null;service=null;super.onStop();}
    @Override protected void onDestroy() {io.shutdown();super.onDestroy();}
    @Override public void onSaveInstanceState(Bundle state) {state.putString("prompt",prompt.getText().toString());state.putString("seed",seed.getText().toString());state.putString("save_image_path",pendingSavePath);super.onSaveInstanceState(state);}
    private void render() {
        boolean ready=controller!=null;
        generate.setEnabled(ready&&controller.isAvailable()&&!service.isBusy());cancel.setEnabled(ready&&controller.canCancel());importModels.setEnabled(ready&&!service.isBusy());
        status.setText(ready?controller.isAvailable()?controller.status():"Import the local SDXS model folder to enable generation.":"Connecting to InferDroid…");
        File image=ready?controller.image():null;save.setEnabled(image!=null);
        if(image!=null&&!image.equals(displayed)) {displayed=image;io.execute(()->{android.graphics.Bitmap bitmap=BitmapFactory.decodeFile(image.toString());runOnUiThread(()->{if(image.equals(displayed)&&!isDestroyed()) preview.setImageBitmap(bitmap);});});}
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null) return;
        if(request==10) {pendingImport=data.getData();if(service!=null) {if(!service.importImageModels(pendingImport)) status.setText("Wait for active inference to finish before importing.");pendingImport=null;}}
        else if(request==11) {File image=pendingSavePath!=null?new File(pendingSavePath):displayed;pendingSavePath=null;if(image==null) return;android.net.Uri destination=data.getData();io.execute(()->{try(InputStream input=new FileInputStream(image);OutputStream output=getContentResolver().openOutputStream(destination)) {input.transferTo(output);runOnUiThread(()->status.setText("Image saved."));} catch(Exception error) {runOnUiThread(()->status.setText("Cannot save this image."));}});}
    }
}
