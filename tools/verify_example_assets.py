#!/usr/bin/env python3
"""Verify every packaged official example and notice against the pinned corpus."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from zipfile import ZipFile

from import_official_examples import ASSETS, RESOURCE, digest, verify


def check(apk: Path) -> dict:
    inventory = verify()
    manifest_data = (RESOURCE/'manifest.json').read_bytes()
    manifest = json.loads(manifest_data)
    expected = {'assets/'+example['file']:example for example in manifest['examples']}
    with ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError('APK has duplicate ZIP entries')
        if archive.read('assets/examples/manifest.json') != manifest_data:
            raise ValueError('APK embeds a different official examples manifest')
        actual = {name for name in names if name.startswith('assets/examples/') and name.endswith('.ini')}
        if actual - {'assets/examples/starter.ini'} != set(expected):
            raise ValueError('APK has missing/unexpected official example INI files')
        for name, info in expected.items():
            data = archive.read(name)
            if len(data) != info['bytes'] or digest(data) != info['sha256']:
                raise ValueError(f'{name}: APK bytes differ from official corpus')
            data.decode('utf-8', errors='strict')
        for notice in ('NOTICE.txt','UPSTREAM-README.txt','MOTO-KIT-README.txt'):
            if archive.read('assets/examples/'+notice) != (ASSETS/notice).read_bytes():
                raise ValueError(f'{notice}: packaged attribution/provenance differs')
    return {'schema_version':1,'passed':True,'apk':apk.name,'apk_sha256':digest(apk.read_bytes()),
            'manifest_sha256':digest(manifest_data),'examples_verified':len(expected),
            'ini_bytes_verified':inventory['ini_bytes'],'notices_verified':3,
            'meaning':'Packaged bytes and provenance match pinned corpus; no firmware/hardware validation.'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk',type=Path)
    parser.add_argument('--report',type=Path)
    args = parser.parse_args()
    result = check(args.apk)
    if args.report:
        args.report.parent.mkdir(parents=True,exist_ok=True)
        args.report.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(result,indent=2))
