#!/usr/bin/env python3
"""Install the separate private APK and its local model files on one authorized Pixel."""
import argparse
import json
from pathlib import Path
import subprocess
import shlex
import sys
from paths import private as root,state
from private_io import prepare_output,run_private
PACKAGE='dev.inferdroid.imagesresearch'
def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir',type=Path,required=True)
    args=parser.parse_args();output=prepare_output(root,args.output_dir)
    def install():
        devices=subprocess.run(['adb','devices'],capture_output=True,text=True,check=True)
        serials=[line.split()[0] for line in devices.stdout.splitlines()[1:] if len(line.split())>=2 and line.split()[1]=='device']
        if len(serials)!=1:raise RuntimeError('Connect exactly one authorized Pixel.')
        adb=['adb','-s',serials[0]]
        model=subprocess.run(adb+['shell','getprop','ro.soc.model'],capture_output=True,text=True,check=True).stdout.strip()
        if model!='Tensor G5':raise RuntimeError('These private artifacts target Tensor G5 only.')
        apk=state/'build/app/outputs/apk/debug/app-debug.apk'
        tests=state/'build/app/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
        subprocess.run(adb+['install','-r',str(apk)],check=True)
        subprocess.run(adb+['install','-r',str(tests)],check=True)
        subprocess.run(adb+['shell','pm','grant',PACKAGE,'android.permission.POST_NOTIFICATIONS'],check=True)
        directory='files/image-models/sdxs-512-g5'
        subprocess.run(adb+['shell','run-as',PACKAGE,'mkdir','-p',directory],check=True)
        for name in ('text_encoder.tflite','unet.tflite','decoder.tflite'):
            command='run-as '+shlex.quote(PACKAGE)+' sh -c '+shlex.quote('umask 077; cat > '+shlex.quote(directory+'/'+name))
            with (state/'model-package'/name).open('rb') as stream:subprocess.run(adb+['shell','-T',command],stdin=stream,check=True)
        subprocess.run(adb+['shell','am','start','-n',PACKAGE+'/dev.inferdroid.MainActivity'],check=True)
        (output/'report.json').write_text(json.dumps({'installed':True,'application_id':PACKAGE,'original_app_replaced':False,'model_directory':directory},indent=2)+'\n')
        return 0
    return run_private(output,install)
if __name__=='__main__':raise SystemExit(main())
