#!/usr/bin/env python3
"""Compare the app's saved component boundaries and scheduler against CPU references."""
import argparse
import json
from pathlib import Path
import sys
from paths import private as root
from private_io import prepare_output,private_path,run_private
def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device-results',required=True,type=Path)
    parser.add_argument('--output-dir',required=True,type=Path)
    args=parser.parse_args();source=private_path(root,args.device_results);output=prepare_output(root,args.output_dir)
    def validate():
        import numpy as np
        import torch
        from PIL import Image
        from diffusers import EulerDiscreteScheduler
        from transformers import CLIPTokenizer
        from ai_edge_litert.interpreter import Interpreter
        torch.set_num_threads(4)
        model=root/'models/sdxs-512-dreamshaper'
        tokenizer=CLIPTokenizer.from_pretrained(str(model/'tokenizer'),local_files_only=True)
        folders={'text_encoder':'text-encoder-export-01','unet':'unet-export-01','decoder':'decoder-export-03'}
        engines,contracts={},{}
        for name,folder in folders.items():
            directory=root/'outputs'/folder
            engines[name]=Interpreter(model_path=str(directory/(name+'.tflite')),num_threads=4)
            engines[name].allocate_tensors();contracts[name]=json.loads((directory/'report.json').read_text())
        def cpu(name,inputs):
            engine=engines[name]
            for detail in engine.get_input_details():
                entry=next(item for item in contracts[name]['inputs'] if item['name']==detail['name'])
                engine.set_tensor(detail['index'],inputs[entry['reference_index']])
            engine.invoke();return engine.get_tensor(engine.get_output_details()[0]['index'])
        verified=json.loads((source/'verification.json').read_text());record={'samples':{},'scheduler_verified':True,'tokenizer_verified':True,'image_accuracy_accepted':False}
        for label,key in [('first','first_image'),('recovery','recovery_image')]:
            directory=source/'device-files/files/image-runs'/Path(verified[key]).parent.name
            local=output/label;local.mkdir()
            metadata=json.loads((directory/'report.json').read_text())
            def read(name,shape,dtype=np.float32):return np.fromfile(directory/name,dtype=dtype).reshape(shape)
            tokens=read('tokens.bin',(1,77),np.int32)
            expected=np.asarray(tokenizer(metadata['prompt'],padding='max_length',max_length=77,truncation=True)['input_ids'],dtype=np.int32)[None,:]
            np.testing.assert_array_equal(tokens,expected)
            initial=read('initial-latents.bin',(1,4,64,64));scaled=read('scaled-latents.bin',(1,4,64,64))
            embeddings=read('embedding.bin',(1,77,768));noise=read('noise.bin',(1,4,64,64));decoder_input=read('decoder-input.bin',(1,4,64,64));decoded=read('decoded.bin',(1,3,512,512))
            scheduler=EulerDiscreteScheduler.from_pretrained(str(model/'scheduler'),local_files_only=True);scheduler.set_timesteps(1)
            step=scheduler.timesteps[0]
            expected_scaled=scheduler.scale_model_input(torch.from_numpy(initial),step).numpy()
            np.testing.assert_allclose(scaled,expected_scaled,rtol=0,atol=1e-6)
            expected_decoder=scheduler.step(torch.from_numpy(noise),step,torch.from_numpy(initial),return_dict=False)[0].numpy()
            np.testing.assert_allclose(decoder_input,expected_decoder,rtol=0,atol=1e-6)
            stage_values={'text_encoder':((tokens,),embeddings),'unet':((scaled,np.array([step.item()],np.float32),embeddings),noise),'decoder':((decoder_input,),decoded)}
            checks={}
            for name,(inputs,actual) in stage_values.items():
                reference=cpu(name,inputs);delta=np.abs(actual-reference)
                checks[name]={'allclose_at_1e_2':bool(np.allclose(actual,reference,rtol=1e-2,atol=1e-2)),
                              'max_absolute_error':float(delta.max()),'mean_absolute_error':float(delta.mean())}
            baseline_embedding=cpu('text_encoder',(tokens,))
            baseline_noise=cpu('unet',(expected_scaled,np.array([step.item()],np.float32),baseline_embedding))
            scheduler=EulerDiscreteScheduler.from_pretrained(str(model/'scheduler'),local_files_only=True);scheduler.set_timesteps(1);scheduler.scale_model_input(torch.from_numpy(initial),step)
            baseline_input=scheduler.step(torch.from_numpy(baseline_noise),step,torch.from_numpy(initial),return_dict=False)[0].numpy()
            baseline=cpu('decoder',(baseline_input,))
            pixels=np.rint(np.clip((baseline[0].transpose(1,2,0)+1)/2,0,1)*255).astype(np.uint8)
            Image.fromarray(pixels).save(local/'cpu-reference.png')
            image=Image.open(directory/'image.png').convert('RGB');image.save(local/'app.png')
            actual=np.asarray(image).astype(np.float64);delta=np.abs(actual-pixels)
            record['samples'][label]={'seed':metadata['seed'],'component_checks':checks,
                'scheduler_max_absolute_error':float(np.max(np.abs(decoder_input-expected_decoder))),
                'mean_absolute_channel_error_0_255':float(delta.mean()),'max_absolute_channel_error_0_255':float(delta.max()),
                'rmse':float(np.sqrt(np.mean((actual-pixels)**2))),'generated_entirely_on_phone':True}
        (output/'report.json').write_text(json.dumps(record,indent=2)+'\n')
        return 0
    return run_private(output,validate,min_free_bytes=2*1024**3)
if __name__=='__main__':raise SystemExit(main())
