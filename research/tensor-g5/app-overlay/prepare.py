#!/usr/bin/env python3
"""Prepare generated Android sources and local model assets for the opt-in build."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import subprocess
import xml.etree.ElementTree as ET

from paths import overlay,private,project,state,tooling
from private_io import prepare_output,private_path,run_private

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir',required=True,type=Path)
    parser.add_argument('--compiled-run',type=Path,help='A run_sdxs.py output directory; otherwise use the original validated artifacts.')
    args=parser.parse_args()
    output=prepare_output(private,args.output_dir)
    compiled_run=private_path(private,args.compiled_run) if args.compiled_run else None
    def prepare():
        import torch
        from diffusers import EulerDiscreteScheduler
        from transformers import CLIPTokenizer
        generated=state/'generated'
        generated.mkdir(parents=True,exist_ok=True)
        java=generated/'java'
        if java.exists(): shutil.rmtree(java)
        shutil.copytree(project/'app/src/main/java',java)
        shutil.copytree(overlay/'src/java',java,dirs_exist_ok=True)
        def edit(name,changes):
            path=java/name
            text=path.read_text()
            for old,new in changes:
                if text.count(old)!=1: raise RuntimeError('The base application changed; review the private overlay patch: '+name)
                text=text.replace(old,new,1)
            path.write_text(text)
        edit('dev/inferdroid/MainActivity.java',[
            ('setContentView(R.layout.activity_main);','setContentView(R.layout.activity_main);\n        findViewById(R.id.open_image_generation).setOnClickListener(view -> startActivity(new Intent(this, dev.inferdroid.images.ImageActivity.class)));')])
        gateway='''        candidate.setImages(new dev.inferdroid.server.ImageGateway() {
            public boolean isAvailable() { return images.isAvailable(); }
            public boolean generate(String prompt, long seed, dev.inferdroid.images.ImageController.Callback callback) throws Exception {
                FutureTask<Boolean> admission = new FutureTask<>(() -> !shuttingDown && isServerRunning() && generateImage(prompt, seed, callback));
                main.post(admission);
                try { return admission.get(5, TimeUnit.SECONDS); }
                catch (Exception error) { admission.cancel(false); cancel(callback); throw error; }
            }
            public void cancel(dev.inferdroid.images.ImageController.Callback callback) { main.post(() -> images.cancel(callback)); }
        });
'''
        image_methods='''    public dev.inferdroid.images.ImageController getImages() { return images; }
    public boolean generateImage(String prompt, long seed, dev.inferdroid.images.ImageController.Callback callback) {
        if (shuttingDown || isBusy() || prompt == null || prompt.isBlank() || prompt.length() > 8192) return false;
        ensureForeground();
        return images.generate(prompt, seed, callback);
    }
    public boolean importImageModels(Uri folder) {
        if (shuttingDown || isBusy()) return false;
        ensureForeground();
        return images.importModels(folder);
    }
'''
        edit('dev/inferdroid/InferenceService.java',[
            ('private OpenAiServer server;','private OpenAiServer server;\n    private dev.inferdroid.images.ImageController images;'),
            ('        tts.attach(this);','        images = new dev.inferdroid.images.ImageController(this, gate, () -> { if (foreground) notifications.notify(NOTIFICATION, notification(manager.getState())); stopIfIdle(); });\n        tts.attach(this);'),
            ('    public EngineManager getManager()',image_methods+'\n    public EngineManager getManager()'),
            ('        try {\n            candidate.start();',gateway+'        try {\n            candidate.start();'),
            ('if (!isServerRunning() && manager.getState().phase','if ((images == null || !images.isBusy()) && !isServerRunning() && manager.getState().phase'),
            ('public void cancel() { manager.cancel(); speech.cancel(); tts.cancel(); }','public void cancel() { manager.cancel(); speech.cancel(); tts.cancel(); images.cancel(null); }'),
            ('int[] remaining = {3};','int[] remaining = {4};'),
            ('        manager.unload(stopped);','        images.shutdown(stopped);\n        manager.unload(stopped);'),
            ('    private Notification notification(EngineManager.State state) {','    private Notification notification(EngineManager.State state) {\n        if (images != null && images.isBusy()) return images.notification(CHANNEL);'),
            ('        serverListeners.clear();','        images.close();\n        serverListeners.clear();')])
        endpoint='''    private void generateImage(Socket socket, InputStream input, OutputStream output, String body,
                               ScheduledFuture<?> intakeDeadline) throws Exception {
        ImageProtocol.Request request = ImageProtocol.parse(body);
        if (images == null || !images.isAvailable()) throw new ApiError(503, "Import the local image models in InferDroid first.", "server_error", "image_model_unavailable", "model");
        intakeDeadline.cancel(false);
        ScheduledFuture<?> deadline = deadlines.schedule(() -> closeSocket(socket), REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        ImageSession session = new ImageSession(); boolean admitted = false;
        try {
            admitted = images.generate(request.prompt, request.seed, session);
            if (!admitted) throw new ApiError(429, "Inference is busy. Retry when idle.", "rate_limit_error", "engine_busy", null);
            socket.setSoTimeout(100);
            while (session.result == null) {
                try { if (input.read() < 0) throw new EOFException("Client disconnected."); throw new IOException("HTTP pipelining is unsupported."); }
                catch (SocketTimeoutException ignored) { }
                if (Thread.currentThread().isInterrupted() || socket.isClosed()) throw new IOException("Server stopped.");
            }
            json(output, 200, ImageProtocol.result(session.result));
        } finally { deadline.cancel(false); if (admitted && session.result == null) images.cancel(session); }
    }
    private static final class ImageSession implements dev.inferdroid.images.ImageController.Callback {
        volatile dev.inferdroid.images.ImageController.Result result;
        public void complete(dev.inferdroid.images.ImageController.Result result) { this.result = result; }
    }
'''
        edit('dev/inferdroid/server/OpenAiServer.java',[
            ('    private final ChatGateway gateway;','    private final ChatGateway gateway;\n    private ImageGateway images;\n    public void setImages(ImageGateway images) { this.images = images; }'),
            ('Set.of("/v1/models", "/v1/chat/completions", "/v1/audio/transcriptions", "/v1/audio/speech")','Set.of("/v1/models", "/v1/chat/completions", "/v1/audio/transcriptions", "/v1/audio/speech", "/v1/images/generations")'),
            ('json(output, 200, OpenAiProtocol.models(!modelSource.isEmpty(), speech != null && speech.isAvailable(), tts != null && tts.isAvailable()));','json(output, 200, ImageProtocol.models(OpenAiProtocol.models(!modelSource.isEmpty(), speech != null && speech.isAvailable(), tts != null && tts.isAvailable()), images != null && images.isAvailable()));'),
            ('            if (path.equals("/v1/audio/speech")) {','            if (path.equals("/v1/images/generations")) { generateImage(socket, input, output, body, headerTimeout); return; }\n            if (path.equals("/v1/audio/speech")) {'),
            ('    private void synthesize(Socket socket,',endpoint+'\n    private void synthesize(Socket socket,')])
        edit('dev/inferdroid/server/ServerConfig.java',[('DEFAULT_PORT = 8080','DEFAULT_PORT = 8081')])
        # A resource overlay replaces the layout only in this opt-in build.
        android='http://schemas.android.com/apk/res/android';ET.register_namespace('android',android);ET.register_namespace('app','http://schemas.android.com/apk/res-auto')
        layout=ET.parse(project/'app/src/main/res/layout/activity_main.xml')
        button=ET.Element('com.google.android.material.button.MaterialButton',{
            '{'+android+'}id':'@+id/open_image_generation','{'+android+'}layout_width':'match_parent',
            '{'+android+'}layout_height':'wrap_content','{'+android+'}text':'Generate images'})
        layout.getroot().find('LinearLayout').insert(1,button)
        (generated/'res/layout').mkdir(parents=True,exist_ok=True)
        layout.write(generated/'res/layout/activity_main.xml',encoding='utf-8',xml_declaration=True)
        manifest=ET.parse(project/'app/src/main/AndroidManifest.xml');application=manifest.getroot().find('application')
        application.set('{'+android+'}label','InferDroid G5 Research')
        ET.SubElement(application,'activity',{'{'+android+'}name':'dev.inferdroid.images.ImageActivity','{'+android+'}exported':'false'})
        ET.SubElement(application,'service',{'{'+android+'}name':'dev.inferdroid.images.ImageWorkerService','{'+android+'}process':':images','{'+android+'}exported':'false'})
        manifest.write(generated/'AndroidManifest.xml',encoding='utf-8',xml_declaration=True)
        assets=generated/'assets/image-research';assets.mkdir(parents=True,exist_ok=True)
        model=private/'models/sdxs-512-dreamshaper'
        for name in ['vocab.json','merges.txt']:shutil.copyfile(model/'tokenizer'/name,assets/name)
        scheduler=EulerDiscreteScheduler.from_pretrained(str(model/'scheduler'),local_files_only=True);scheduler.set_timesteps(1)
        profile={'source_revision':'76f720262bb051da75666b22c902a78c8e16c763','sigma':scheduler.sigmas[0].item(),
                 'initial_noise_sigma':scheduler.init_noise_sigma.item(),'timestep':scheduler.timesteps[0].item(),
                 'input_scale_denominator':((scheduler.sigmas[0]**2+1)**.5).item(),
                 'vae_scaling_factor':json.loads((model/'vae/config.json').read_text())['scaling_factor'],'files':{}}
        package=state/'model-package';package.mkdir(exist_ok=True)
        artifacts={'text_encoder':'text-encoder-g5-hybrid-01','unet':'unet-g5-hybrid-half-01','decoder':'decoder-g5-fp32-01'}
        for component,folder in artifacts.items():
            source=(compiled_run/(component+'-g5') if compiled_run else private/'outputs'/folder)/'models'/(component+'_Google_Tensor_G5.tflite');destination=package/(component+'.tflite')
            with source.open('rb') as stream:digest=hashlib.file_digest(stream,'sha256').hexdigest()
            profile['files'][destination.name]={'sha256':digest,'bytes':source.stat().st_size}
            if destination.exists() and not os.path.samefile(source,destination):destination.unlink()
            if not destination.exists():os.link(source,destination)
        (assets/'profile.json').write_text(json.dumps(profile,indent=2)+'\n')
        (package/'profile.json').write_text(json.dumps(profile,indent=2)+'\n')
        tokenizer=CLIPTokenizer.from_pretrained(str(model/'tokenizer'),local_files_only=True)
        prompts=['A small red robot reading a book in a sunny garden, digital illustration','A blue ceramic teapot beside a yellow lemon on a wooden table, studio photograph',"A cat's blue hat, isn't it?",'  Caffè   italiano!\nLuce e colori.','café cafe\u0301 naïve — 東京 猫 😊 12345','ΟΣ İstanbul İ and Straße','<|startoftext|> a robot <|endoftext|>','a '*200]
        fixtures=[{'prompt':prompt,'ids':tokenizer(prompt,padding='max_length',max_length=77,truncation=True)['input_ids']} for prompt in prompts]
        (assets/'tokenizer-fixtures.json').write_text(json.dumps(fixtures,ensure_ascii=False)+'\n')
        libraries=generated/'jniLibs/arm64-v8a';libraries.mkdir(parents=True,exist_ok=True)
        runtime_root=private/'cache/device-runtime-2.3.0-r2'
        if not (runtime_root/'include/litert/c/litert_model.h').is_file():
            subprocess.run([str(private/'tooling/.venv/bin/python'),str(tooling/'prepare_device_runtime.py'),'--output-dir',str(runtime_root)],check=True)
        runtime=runtime_root/'arm64-v8a'
        for original,renamed in [('libLiteRt.so','libinferdroid_image_litert.so'),('libLiteRtDispatch_GoogleTensor.so','libinferdroid_image_dispatch.so')]:shutil.copyfile(runtime/original,libraries/renamed)
        (output/'report.json').write_text(json.dumps({'prepared':True,'application_id':'dev.inferdroid.imagesresearch','profile':profile,'public_app_sources_modified':False},indent=2)+'\n')
        return 0
    return run_private(output,prepare)
if __name__=='__main__':raise SystemExit(main())
