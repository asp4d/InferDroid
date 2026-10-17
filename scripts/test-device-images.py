#!/usr/bin/env python3
"""Run real Android image tests and retain all device diagnostics privately."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import tarfile
root=Path(__file__).resolve().parents[1]/'.local/tensor-g5'
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'research/tensor-g5/tooling'))
from private_io import prepare_output,run_private
PACKAGE='dev.inferdroid'
def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir',type=Path,required=True)
    parser.add_argument('--ui-only',action='store_true',help='Check model selection, persistence, rotation, validation and translations without generating images.')
    args=parser.parse_args();output=prepare_output(root,args.output_dir)
    def verify():
        subprocess.run(['adb','shell','run-as',PACKAGE,'rm','-f','files/image-app-verification.json'],check=True)
        command=['adb','shell','am','instrument','-w','-e','suite','images']
        if args.ui_only:command.extend(['-e','ui_only','true'])
        subprocess.run(command+[PACKAGE+'.test/dev.inferdroid.engine.EngineManagerInstrumentation'],check=True,timeout=900)
        response=subprocess.run(['adb','exec-out','run-as',PACKAGE,'cat','files/image-app-verification.json'],capture_output=True,check=True)
        (output/'verification.json').write_bytes(response.stdout)
        record=json.loads(response.stdout)
        if record.get('first_image'):
            with (output/'device-files.tar').open('wb') as stream:
                subprocess.run(['adb','exec-out','run-as',PACKAGE,'tar','-cf','-','files/image-runs','files/image-app-verification.json'],stdout=stream,check=True)
            with tarfile.open(output/'device-files.tar') as archive:
                archive.extractall(output/'device-files',filter='data')
        return 0 if record.get('passed') else 1
    return run_private(output,verify)
if __name__=='__main__':raise SystemExit(main())
