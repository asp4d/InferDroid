#!/usr/bin/env python3
"""Build the opt-in image variant, keeping generated output in .local/."""
import argparse
from pathlib import Path
import subprocess
import sys
from paths import overlay,private as root,project
from private_io import prepare_output,run_private
def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir',type=Path,required=True)
    parser.add_argument('--compiled-run',type=Path)
    args=parser.parse_args();output=prepare_output(root,args.output_dir)
    def build():
        command=[str(root/'tooling/.export-venv/bin/python'),str(overlay/'prepare.py'),'--output-dir',str(output/'prepare')]
        if args.compiled_run:command.extend(['--compiled-run',str(args.compiled_run)])
        subprocess.run(command,check=True)
        return subprocess.run(['./gradlew','-I',str(overlay/'enable.gradle'),':app:assembleDebug',':app:assembleDebugAndroidTest','--console=plain'],cwd=project).returncode
    return run_private(output,build)
if __name__=='__main__':raise SystemExit(main())
