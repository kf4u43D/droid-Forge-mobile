#!/usr/bin/env python3
"""Import the complete pinned blue-7 official INI/TXT corpus without rewriting it.

PDF code transcriptions are separately identified and pinned; their line layout
is UTF-8/LF rather than a claim of byte identity with a PDF. No network is used.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
RESOURCE = ROOT / 'resources/official/examples'
ASSETS = ROOT / 'app/src/main/assets/examples'
DOWNLOADS = 'https://shop.dermannmitdermaschine.de/pages/downloads'
ARCHIVE_URL = 'https://dermannmitdermaschine.de/download/droid-blue-7.zip'
PDF_URL = 'https://dermannmitdermaschine.de/download/droid-manual-blue-7.pdf'
ARCHIVE_SHA = 'e3f3ef93bd70b88e101fbc0254df0c26534a432d241ba6b177d5648a1b68258c'
PDF_SHA = 'e324801d355524354223d3d0e7528f8077dc330f193f10b7544f01d76a6f94ad'
TRANSCRIPTS_SHA = '65e2337095ab8c86cd9423840233a5babfbde6f485bb99b557f9d5e9851ffb1f'
MANIFEST_SHA = '30b246488c8b98200a193879941fca3f0347c0e9e108760087e87e4cc01ee4e3'
SOURCE_FILES = {
    'UPSTREAM-README.txt': ('droid-blue-7/README.txt',
        'bc393cefb3fe94c8b746121857e1ec8d86828629b33b49d4ae7db600d9f3bd61'),
    'MOTO-KIT-README.txt': ('droid-blue-7/motokit/README.txt',
        '9a5fd0470b68535b0cbf28a04fb613059525f27d0b37e5be2e6be7c9fe1cc26e'),
    'droid-patch-snippets-blue-7.txt': ('droid-blue-7/manual/droid-patch-snippets-blue-7.txt',
        'f528ea80398218d59d00cd8ffded2b70aeac6c020840501e20eccebb0fa3c55d'),
}
NOTICE = '''Official DROID blue-7 examples — Der Mann mit der Maschine
Provenance: https://shop.dermannmitdermaschine.de/pages/downloads
DROID / Forge PC author: Mathias Kettner (Der Mann mit der Maschine).
Dr Delos: contribution to the Android port, as credited for this project.

Files from the patches and motokit directories and the bodies of the 332 TXT
excerpts are preserved byte for byte, without corrections. The 262 additional PDF
excerpts are code block transcriptions, identified as such and referenced by
page/column. No omitted lines or hardware configuration are invented.

The official README accompanies the corpus. It offers snippets to copy into
your patches. It provides no explicit standalone licence for these examples:
do not treat graphics permission as a GPL licence for the patches.
The GPLv3 licence of the Android/Forge PC software does not change corpus attribution.

Some official files are older versions or require the declared controllers
in the given order. The two large Moto Kit INI files are intended for study in
an editor; the official README says they are too large to load directly on
the MASTER and refers to the supplier's compressed hardware patches.

Fragments, ellipses, placeholders and counterexamples remain intentionally
unchanged. Passing the read/export test proves neither full firmware validity
nor operation on DROID hardware.
'''


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def pinned(data: bytes, expected: str, name: str) -> bytes:
    if digest(data) != expected:
        raise ValueError(f'{name}: SHA-256 differs from the pinned official source')
    data.decode('utf-8', errors='strict')
    return data


def snippets(data: bytes) -> list[tuple[dict, bytes]]:
    """Extract each official separator-delimited body as one contiguous byte slice."""
    lines = data.splitlines(keepends=True)
    offsets = [0]
    for line in lines:
        offsets.append(offsets[-1] + len(line))
    page = 0
    title = ''
    result = []
    seen = set()
    index = 0
    while index < len(lines):
        text = lines[index].decode('utf-8').strip()
        match = re.fullmatch(r'\|\s*Page (\d+)\s*\|', text)
        if match:
            page = int(match[1])
        match = re.fullmatch(r'\|\s*([a-z][a-z0-9_]*)\s*\|', text)
        if match:
            title = match[1]
        match = re.fullmatch(r'Example (\d+):', text)
        if match:
            number = int(match[1])
            if not page or not title or (page, number) in seen:
                raise ValueError('Missing/duplicate official snippet heading')
            seen.add((page, number))
            if index + 1 >= len(lines) or lines[index + 1].strip() != b'-' * 30:
                raise ValueError('Missing opening snippet separator')
            start = index + 2
            end = start
            while end < len(lines) and lines[end].strip() != b'-' * 30:
                end += 1
            if end == len(lines):
                raise ValueError('Missing closing snippet separator')
            body = data[offsets[start]:offsets[end]]
            source = body.decode('utf-8')
            fragment = (not re.search(r'^\s*\[[\w]+\]', source, re.M)
                        or bool(re.search(r'^\s*\.\.\.', source, re.M)))
            result.append(({
                'id': f'manual-{title}-p{page:03d}-{number:02d}',
                'title': f'{title} — exemple {number}',
                'category': 'Manuel — circuits',
                'kind': 'fragment' if fragment else 'snippet',
                'page': page, 'pages': [page], 'example_number': number,
                'source': ARCHIVE_URL, 'source_path': SOURCE_FILES['droid-patch-snippets-blue-7.txt'][0],
                'source_byte_start': offsets[start], 'source_byte_end': offsets[end],
                'source_line_start': start + 1, 'source_line_end': end,
                'extraction': 'byte-exact contiguous slice of official UTF-8 TXT',
                'context_required': True,
                'note': ('Fragment du manuel : lignes ou contexte omis volontairement.' if fragment
                         else 'Extrait du manuel : contrôleurs et câbles extérieurs à adapter au contexte.'),
            }, body))
            index = end
        index += 1
    if len(result) != 332:
        raise ValueError(f'Expected all 332 official TXT examples, found {len(result)}')
    return result


def make_manifest(entries: list[tuple[dict, bytes]]) -> dict:
    examples = []
    for info, body in entries:
        item = dict(info)
        item.update(file=f"examples/{info['id']}.ini", firmware='blue-7',
                    bytes=len(body), sha256=digest(body))
        examples.append(item)
    return {
        'schema_version': 1, 'firmware': 'blue-7', 'publisher': 'Der Mann mit der Maschine',
        'downloads': DOWNLOADS, 'archive': {'url': ARCHIVE_URL, 'sha256': ARCHIVE_SHA},
        'manual': {'url': PDF_URL, 'sha256': PDF_SHA, 'pages': 413},
        'counts': {'total': len(examples), 'kind': dict(Counter(x['kind'] for x in examples)),
                   'category': dict(Counter(x['category'] for x in examples))},
        'limitations': 'Lecture/export local ; matériel DROID et validation intégrale du firmware non testés.',
        'notice_file': 'examples/NOTICE.txt', 'examples': examples,
    }


def import_examples(archive_path: Path) -> dict:
    if digest(archive_path.read_bytes()) != ARCHIVE_SHA:
        raise ValueError('Official firmware archive SHA-256 differs')
    transcript_data = pinned((RESOURCE/'manual-pdf-transcriptions.json').read_bytes(),
                             TRANSCRIPTS_SHA, 'PDF transcription inventory')
    entries = []
    with ZipFile(archive_path) as archive:
        for local, (source, sha) in SOURCE_FILES.items():
            data = pinned(archive.read(source), sha, source)
            (RESOURCE/local).write_bytes(data)
        for directory, category, count in [('patches', 'Patches officiels', 34), ('motokit', 'Moto Kit', 2)]:
            names = sorted(n for n in archive.namelist()
                           if n.startswith(f'droid-blue-7/{directory}/') and n.endswith('.ini'))
            if len(names) != count:
                raise ValueError(f'Official {directory} corpus count differs')
            for name in names:
                body = archive.read(name)
                body.decode('utf-8', errors='strict')
                stem = Path(name).stem
                info = {'id': f'official-{stem}', 'title': stem, 'category': category,
                        'kind': 'full', 'page': 0, 'pages': [], 'source': ARCHIVE_URL,
                        'source_path': name, 'extraction': 'byte-exact ZIP entry',
                        'context_required': False,
                        'note': ('INI lisible destiné à l’étude : trop grand pour chargement direct sur le master ; voir le README Moto Kit officiel.'
                                 if directory == 'motokit' else 'Patch officiel ; certains sont anciens. Les contrôleurs déclarés doivent être présents dans cet ordre.')}
                entries.append((info, body))
        entries.extend(snippets(archive.read(SOURCE_FILES['droid-patch-snippets-blue-7.txt'][0])))
    for original in json.loads(transcript_data)['examples']:
        info = {key: value for key, value in original.items() if key not in ('lines', 'notes')}
        info.update(source=PDF_URL, source_path='droid-manual-blue-7.pdf',
                    extraction='PDF code transcription, UTF-8/LF; semantic content unchanged',
                    note='\n'.join(original['notes']))
        body = ('\n'.join(original['lines'])+'\n').encode('utf-8')
        entries.append((info, body))
    manifest = make_manifest(entries)
    if len({info['id'] for info, _ in entries}) != len(entries) or len(entries) != 630:
        raise ValueError('Duplicate/missing corpus entry')
    (RESOURCE/'patches').mkdir(exist_ok=True)
    ASSETS.mkdir(parents=True, exist_ok=True)
    for info, body in entries:
        (RESOURCE/'patches'/f"{info['id']}.ini").write_bytes(body)
        (ASSETS/f"{info['id']}.ini").write_bytes(body)
    encoded = (json.dumps(manifest, ensure_ascii=False, indent=2)+'\n').encode('utf-8')
    (RESOURCE/'manifest.json').write_bytes(encoded)
    (ASSETS/'manifest.json').write_bytes(encoded)
    (RESOURCE/'NOTICE.txt').write_text(NOTICE, encoding='utf-8', newline='\n')
    (ASSETS/'NOTICE.txt').write_text(NOTICE, encoding='utf-8', newline='\n')
    for name in ('UPSTREAM-README.txt', 'MOTO-KIT-README.txt'):
        (ASSETS/name).write_bytes((RESOURCE/name).read_bytes())
    return verify()


def verify() -> dict:
    manifest_data = pinned((RESOURCE/'manifest.json').read_bytes(), MANIFEST_SHA, 'Official corpus manifest')
    if (ASSETS/'manifest.json').read_bytes() != manifest_data:
        raise ValueError('Embedded manifest differs from source manifest')
    manifest = json.loads(manifest_data)
    if manifest['counts']['total'] != 630 or len(manifest['examples']) != 630:
        raise ValueError('Corpus count differs')
    pinned((RESOURCE/'manual-pdf-transcriptions.json').read_bytes(), TRANSCRIPTS_SHA, 'PDF transcriptions')
    for local, (_, sha) in SOURCE_FILES.items():
        pinned((RESOURCE/local).read_bytes(), sha, local)
    expected = {Path(info['file']).name for info in manifest['examples']}
    for directory in (RESOURCE/'patches', ASSETS):
        non_corpus = {'starter.ini'} if directory == ASSETS else set()
        if {p.name for p in directory.glob('*.ini')} - non_corpus != expected:
            raise ValueError(f'Unexpected/missing INI in {directory}')
        for item in manifest['examples']:
            if not re.fullmatch(r'examples/[a-zA-Z0-9_-]+\.ini', item['file']):
                raise ValueError('Unsafe manifest asset path')
            data = (directory/Path(item['file']).name).read_bytes()
            if len(data) != item['bytes']:
                raise ValueError(f"{item['id']}: size differs")
            pinned(data, item['sha256'], item['id'])
    for name in ('NOTICE.txt', 'UPSTREAM-README.txt', 'MOTO-KIT-README.txt'):
        if (RESOURCE/name).read_bytes() != (ASSETS/name).read_bytes():
            raise ValueError(f'Embedded {name} differs')
    # Re-derive TXT snippets: verifies byte offsets and extraction, not just copies.
    by_id = {x['id']: x for x in manifest['examples']}
    for info, body in snippets((RESOURCE/'droid-patch-snippets-blue-7.txt').read_bytes()):
        if digest(body) != by_id[info['id']]['sha256']:
            raise ValueError('Official TXT slice differs')
    return {'passed': True, **manifest['counts'],
            'ini_bytes': sum(x['bytes'] for x in manifest['examples']),
            'manifest_sha256': digest(manifest_data), 'network_used': False}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', type=Path, default=ROOT/'.cache/droid-blue-7.zip')
    parser.add_argument('--verify', action='store_true', help='Verify source and assets offline; do not modify anything')
    args = parser.parse_args()
    print(json.dumps(verify() if args.verify else import_examples(args.archive), indent=2))
