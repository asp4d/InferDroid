package dev.inferdroid.images;

import android.content.Context;
import android.graphics.Bitmap;
import java.io.*;
import java.nio.*;
import java.nio.file.Files;
import java.util.Random;
import org.json.JSONObject;

/** The complete one-step, 512px pipeline. No host Python participates. */
final class SdxsPipeline {
    interface Progress { void update(String text); }
    static File generate(Context context,String prompt,long seed,File output,Progress progress) throws Exception {
        ImageModels models=new ImageModels(context);
        progress.update("Checking local image models…"); models.verify(models.directory());
        File dispatch=new File(context.getCodeCacheDir(),"image-dispatch");
        if(!dispatch.isDirectory()&&!dispatch.mkdirs()) throw new IOException("Cannot prepare the image runtime.");
        File library=new File(context.getApplicationInfo().nativeLibraryDir,"libinferdroid_image_dispatch.so");
        File link=new File(dispatch,"libLiteRtDispatch_GoogleTensor.so");
        if(Files.isSymbolicLink(link.toPath())&&!Files.readSymbolicLink(link.toPath()).equals(library.toPath())) Files.delete(link.toPath());
        if(!Files.isSymbolicLink(link.toPath())) Files.createSymbolicLink(link.toPath(),library.toPath());
        NativeImages.configure(new File(context.getApplicationInfo().nativeLibraryDir,"libinferdroid_image_litert.so").toString(),dispatch.toString(),new File(output,"runtime.log").toString());
        progress.update("Encoding your prompt…");
        int[] ids=new ClipTokenizer(context).encode(prompt);
        ByteBuffer tokens=ByteBuffer.allocate(77*4).order(ByteOrder.LITTLE_ENDIAN);
        for(int value:ids) tokens.putInt(value);
        save(output,"tokens.bin",tokens.array());
        byte[] embeddings=NativeImages.run(new File(models.directory(),"text_encoder.tflite").toString(),new byte[][]{tokens.array()},true,77*768*4);
        check(embeddings);save(output,"embedding.bin",embeddings);
        float sigma=(float)models.profile.getDouble("sigma");
        float denominator=(float)models.profile.getDouble("input_scale_denominator");
        float initialSigma=(float)models.profile.getDouble("initial_noise_sigma");
        float scaling=(float)models.profile.getDouble("vae_scaling_factor");
        Random random=new Random(seed);float[] latents=new float[4*64*64],scaled=new float[latents.length];
        for(int i=0;i<latents.length;i++) {latents[i]=(float)random.nextGaussian()*initialSigma;scaled[i]=latents[i]/denominator;}
        save(output,"initial-latents.bin",bytes(latents));save(output,"scaled-latents.bin",bytes(scaled));
        progress.update("Generating the image on Tensor G5…");
        byte[] timestep=bytes(new float[]{(float)models.profile.getDouble("timestep")});
        byte[] prediction=NativeImages.run(new File(models.directory(),"unet.tflite").toString(),new byte[][]{bytes(scaled),timestep,embeddings},true,4*64*64*4);
        float[] noise=check(prediction);save(output,"noise.bin",prediction);
        float[] decodedInput=new float[latents.length];
        for(int i=0;i<latents.length;i++) {
            float predicted=latents[i]-sigma*noise[i];
            float derivative=(latents[i]-predicted)/sigma;
            decodedInput[i]=(latents[i]+derivative*-sigma)/scaling;
        }
        save(output,"decoder-input.bin",bytes(decodedInput));
        progress.update("Decoding the image on Tensor G5…");
        byte[] decoded=NativeImages.run(new File(models.directory(),"decoder.tflite").toString(),new byte[][]{bytes(decodedInput)},false,3*512*512*4);
        float[] pixels=check(decoded);save(output,"decoded.bin",decoded);
        int[] argb=new int[512*512];
        for(int i=0;i<argb.length;i++) argb[i]=0xff000000|channel(pixels[i])<<16|channel(pixels[i+argb.length])<<8|channel(pixels[i+argb.length*2]);
        Bitmap bitmap=Bitmap.createBitmap(argb,512,512,Bitmap.Config.ARGB_8888);
        File image=new File(output,"image.png");
        try(OutputStream stream=new FileOutputStream(image)) {if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,stream)) throw new IOException("Cannot save the generated image.");}
        finally {bitmap.recycle();}
        JSONObject report=new JSONObject().put("model",ImageModels.MODEL_ID).put("prompt",prompt).put("seed",seed)
            .put("width",512).put("height",512).put("steps",1).put("scheduler","EulerDiscreteScheduler")
            .put("inference_location","Android application image worker process").put("host_required",false)
            .put("text_encoder_backend","CPU+NPU").put("denoiser_backend","CPU+NPU").put("decoder_backend","NPU")
            .put("accuracy_status","Experimental: prior denoiser tensor check did not pass")
            .put("rng","java.util.Random.nextGaussian; deterministic within this implementation");
        save(output,"report.json",report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return image;
    }
    private static int channel(float value) {return (int)Math.rint(Math.max(0,Math.min(1,(value+1)/2))*255);}
    private static byte[] bytes(float[] values) {ByteBuffer buffer=ByteBuffer.allocate(values.length*4).order(ByteOrder.LITTLE_ENDIAN);buffer.asFloatBuffer().put(values);return buffer.array();}
    private static float[] check(byte[] bytes) throws IOException {
        FloatBuffer buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();float[] result=new float[buffer.remaining()];buffer.get(result);
        for(float value:result) if(!Float.isFinite(value)) throw new IOException("Image backend produced a non-finite tensor.");
        return result;
    }
    private static void save(File folder,String name,byte[] data) throws IOException {try(OutputStream stream=new FileOutputStream(new File(folder,name))) {stream.write(data);}}
}
