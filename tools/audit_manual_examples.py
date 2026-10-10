#!/usr/bin/env python3
"""Measure coverage of code-font DROID examples in the pinned 413-page manual.

The optional HTML input is produced by Xpdf 4.04 pdftohtml. PDF prose, register
tables, shell commands, status dumps and screenshots are not INI programs. The
official 332 TXT bodies are preferred to a PDF transcription whenever present.
Coverage matches code-line occurrences after whitespace/comment normalization;
byte identity is checked separately by import_official_examples.py.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
import html
import json
from pathlib import Path
import re

from import_official_examples import RESOURCE, ROOT, PDF_SHA, digest, verify


def normalized(text: str) -> str:
    # This is only a coverage search key, never the data written to an INI.
    return re.sub(r'\s+', '', text.split('#', 1)[0]).lower()


def page_candidates(path: Path) -> list[dict]:
    text = path.read_text('utf-8')
    fonts = set(re.findall(r'\.(f\d+) \{ font-family:monospace', text))
    rows = defaultdict(list)
    candidates = []
    for match in re.finditer(r'<div class="txt" style="position:absolute; left:(\d+)px; top:(\d+)px;">(.*?)</div>', text):
        x, y = int(match[1]), int(match[2])
        spans = re.findall(r'<span class="(f\d+)"[^>]*>(.*?)</span>', match[3])
        decode = lambda source: html.unescape(re.sub('<[^>]+>', '', source))
        if spans and all(font in fonts for font, _ in spans):
            rows[(x//250+1, y)].append((x, ''.join(decode(part) for _, part in spans)))
        else:
            for font, source in spans:
                content = decode(source).strip()
                if font in fonts and re.match(r'^\w+\s*=', content):
                    candidates.append({'column': x//250+1, 'top': y, 'type': 'inline', 'text': content})
    for (column, y), parts in sorted(rows.items()):
        content = ' '.join(part for _, part in sorted(parts))
        if re.match(r'^\[\w+\]|^\s*\w+\s*=', content):
            candidates.append({'column': column, 'top': y, 'type': 'standalone', 'text': content})
    return candidates


def audit(directories: list[Path]) -> dict:
    verify()
    pdf = ROOT/'resources/official/droid-manual-blue-7.pdf'
    if digest(pdf.read_bytes()) != PDF_SHA:
        raise ValueError('PDF differs from pinned official manual')
    manifest = json.loads((RESOURCE/'manifest.json').read_text('utf-8'))
    matches = defaultdict(set)
    for example in manifest['examples']:
        source = (RESOURCE/'patches'/Path(example['file']).name).read_text('utf-8')
        for line in source.splitlines():
            matches[normalized(line)].add(example['id'])
    pages = []
    for number in range(1, 414):
        options = [directory/f'page{number}.html' for directory in directories]
        path = next((p for p in options if p.is_file()), None)
        if path is None:
            raise ValueError(f'Missing extracted PDF page {number}')
        candidates = page_candidates(path)
        missing = [item for item in candidates if normalized(item['text']) not in matches]
        pages.append({'page': number, 'standalone_candidates': sum(x['type']=='standalone' for x in candidates),
                      'inline_candidates': sum(x['type']=='inline' for x in candidates),
                      'covered': len(candidates)-len(missing), 'missing': missing})
    missing_count = sum(len(page['missing']) for page in pages)
    return {'schema_version':1, 'firmware':'blue-7', 'manual_sha256':PDF_SHA,
            'manual_pages':413, 'extraction':'Xpdf 4.04 pdftohtml, code-font rows/spans, explicit columns',
            'scope':'All standalone INI headers/assignments and inline assignment spans in the PDF text layer; GUI screenshots, register tables, shell commands and status dumps are not source programs.',
            'matching':'Coverage only: ignore whitespace, case and trailing comments. Imported files are never rewritten by this matching.',
            'corpus_count':len(manifest['examples']), 'txt_examples':332, 'pdf_transcriptions':262,
            'standalone_candidates':sum(p['standalone_candidates'] for p in pages),
            'inline_candidates':sum(p['inline_candidates'] for p in pages),
            'missing_count':missing_count, 'passed':missing_count==0, 'pages':pages}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--html', type=Path, action='append', required=True,
                        help='Xpdf output directory; repeat for separate page ranges')
    parser.add_argument('--report', type=Path, default=RESOURCE/'manual-coverage.json')
    args = parser.parse_args()
    report = audit(args.html)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({key:value for key,value in report.items() if key!='pages'}, indent=2))
    if not report['passed']:
        raise SystemExit(1)
