#!/usr/bin/env python3
"""Package authored research sources; leave SDKs, models and results local."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile
from paths import overlay,private as root,project,tooling
from private_io import private_path
from pack_toolkit import FILES as TOOL_FILES
def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--archive',required=True,type=Path)
    args=parser.parse_args();os.umask(0o077);archive=private_path(root,args.archive)
    if archive.exists() or archive.with_suffix('.zip.sha256').exists():parser.error('Use a new archive filename.')
    names=['README.md','CMakeLists.txt','enable.gradle','paths.py','prepare.py','build.py','install.py','verify.py','validate_outputs.py','pack.py']
    for folder,suffix in [('src/java','.java'),('src/cpp','.cc'),('src/androidTest/java','.java')]:
        names.extend(str(path.relative_to(overlay)) for path in (overlay/folder).rglob('*'+suffix))
    sources={'app-overlay/'+name:overlay/name for name in names}
    sources.update({'tooling/'+name:tooling/name for name in TOOL_FILES})
    sources.update({name:overlay.parent/name for name in ('README.md','.gitignore')})
    contents={}
    for name,source in sorted(sources.items()):
        if source.is_symlink() or not source.is_file():raise ValueError('A source file is missing or is a symlink.')
        contents[name]=source.read_bytes()
    base_names=['app/build.gradle','app/src/main/cpp/CMakeLists.txt','app/src/main/AndroidManifest.xml',
                'app/src/main/java/dev/inferdroid/MainActivity.java','app/src/main/java/dev/inferdroid/InferenceService.java',
                'app/src/main/java/dev/inferdroid/server/OpenAiServer.java','app/src/main/java/dev/inferdroid/server/ServerConfig.java']
    contents['BASE.json']=(json.dumps({'git_commit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=project,text=True).strip(),
        'base_source_sha256':{name:hashlib.sha256((project/name).read_bytes()).hexdigest() for name in base_names}},indent=2)+'\n').encode()
    contents['FILES.sha256']=''.join(hashlib.sha256(data).hexdigest()+'  '+name+'\n' for name,data in sorted(contents.items())).encode()
    archive.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(archive,'x',compression=zipfile.ZIP_DEFLATED) as output:
        for name,data in sorted(contents.items()):
            info=zipfile.ZipInfo('research/tensor-g5/'+name);info.external_attr=0o100600<<16;info.compress_type=zipfile.ZIP_DEFLATED
            output.writestr(info,data)
    with zipfile.ZipFile(archive) as bundle:
        if bundle.testzip() is not None:raise RuntimeError('Source archive integrity failed.')
    digest=hashlib.sha256(archive.read_bytes()).hexdigest()
    with archive.with_suffix('.zip.sha256').open('x') as stream:stream.write(digest+'  '+archive.name+'\n')
    print('Research source archive created and checked in the private workspace.')
    return 0
if __name__=='__main__':raise SystemExit(main())
