#!/usr/bin/env python3
"""Test every official example with the native engine and the official catalogue.

Diagnostics are expected for contextual fragments, documented counterexamples,
legacy parameters and upstream mistakes. They are compared with a reviewed
baseline, never silently removed or used as a hardware-validation certificate.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

from import_official_examples import RESOURCE, ROOT, digest, verify

BASELINE = RESOURCE/'expected-diagnostics.json'
CATALOGUE = ROOT/'resources/official/circuits.blue-7.json'


def independent_schema(source: dict) -> dict:
    result = {}
    for name, circuit in source['circuits'].items():
        parameters = {}
        for direction, collection in [('input', 'inputs'), ('output', 'outputs')]:
            # Long names win over short aliases, matching the documented grammar.
            expanded = []
            for parameter in circuit[collection]:
                if 'prefix' in parameter:
                    start = int(parameter.get('start_at', 1))
                    for index in range(start, start+int(parameter['count'])):
                        expanded.append((parameter['prefix']+str(index),
                                         parameter.get('short', '')+str(index) if parameter.get('short') else '', direction))
                else:
                    expanded.append((parameter['name'], parameter.get('short', ''), direction))
            for long_name, _, value in expanded:
                parameters.setdefault(long_name, value)
            for _, short, value in expanded:
                if short:
                    parameters.setdefault(short, value)
        result[name] = parameters
        if circuit.get('short'):
            result.setdefault(circuit['short'], parameters)
    return result


def classify(diagnostic: dict) -> str:
    message = diagnostic['message']
    if message.startswith('Affectation avant'):
        return 'fragment_without_circuit'
    if message.startswith('Ligne invalide'):
        return 'upstream_placeholder_or_non_ini_fragment'
    if message.startswith('Paramètre inconnu'):
        return 'upstream_parameter_absent_from_blue_7_catalogue'
    if message.startswith('Paramètre affecté plusieurs'):
        return 'upstream_duplicate_assignment'
    if message.startswith('Expression DROID invalide'):
        return 'upstream_invalid_literal'
    if 'Aucune sortie' in message:
        return 'context_missing_cable_emitter'
    if 'Aucune entrée' in message:
        return 'context_missing_cable_receiver'
    if message.startswith('Validation partielle du câble'):
        return 'unknown_parameter_cable_direction'
    if '64 000' in message or '63 octets' in message:
        return 'firmware_size_limit_notice'
    raise ValueError(f'Unreviewed diagnostic category: {message}')


def validate(engine_report: dict) -> dict:
    inventory = verify()
    manifest = json.loads((RESOURCE/'manifest.json').read_text('utf-8'))
    expected = json.loads(BASELINE.read_text('utf-8'))
    by_id = {item['id']:item for item in manifest['examples']}
    if not engine_report['passed'] or engine_report['files_count'] != len(by_id):
        raise ValueError('Native corpus test did not pass or is incomplete')
    native = {Path(item['name']).stem:item for item in engine_report['files']}
    if set(native) != set(by_id) or len(engine_report['files']) != len(by_id):
        raise ValueError('Native result contains duplicate/missing examples')
    official = json.loads(CATALOGUE.read_text('utf-8'))
    schema = independent_schema(official)
    known = unknown = blocks = errors = warnings = python_checks = 0
    categories = Counter()
    records = []
    for example in manifest['examples']:
        identifier = example['id']
        actual = native[identifier]
        state = actual['state']
        baseline = expected['files'][identifier]
        if actual['bytes'] != example['bytes'] or not actual['roundtrip_exact']:
            raise ValueError(f'{identifier}: source size/export changed')
        if baseline['sha256'] != example['sha256']:
            raise ValueError(f'{identifier}: baseline belongs to a different upstream source')
        if state['diagnostics'] != baseline['diagnostics']:
            raise ValueError(f'{identifier}: diagnostics differ from the reviewed upstream baseline')
        if not state['analysisComplete']:
            raise ValueError(f'{identifier}: native analysis was unexpectedly bounded/partial')
        python_checks += 4
        file_known = file_unknown = 0
        for block in state['blocks']:
            blocks += 1
            if block['controller']:
                continue
            if block['name'] not in schema:
                raise ValueError(f'{identifier}: unknown circuit {block["name"]}')
            parameters = schema[block['name']]
            for parameter in block['parameters']:
                name = parameter['name']
                # An array's first index may be omitted in Forge INI syntax.
                direction = parameters.get(name, parameters.get(name+'1', 'unknown'))
                if parameter['direction'] != direction:
                    raise ValueError(f'{identifier}: {block["name"]}.{name} direction differs from official catalogue')
                python_checks += 1
                if direction == 'unknown':
                    file_unknown += 1
                else:
                    file_known += 1
        file_errors = sum(d['severity']=='error' for d in state['diagnostics'])
        file_warnings = sum(d['severity']=='warning' for d in state['diagnostics'])
        if identifier.startswith('official-') and file_errors:
            raise ValueError(f'{identifier}: unexpected error in the official standalone patch corpus')
        for diagnostic in state['diagnostics']:
            categories[classify(diagnostic)] += 1
        known += file_known
        unknown += file_unknown
        errors += file_errors
        warnings += file_warnings
        records.append({key:example[key] for key in ('id','title','category','kind','page','pages','source_path','sha256','bytes')} |
                       {'roundtrip_exact':True,'analysis_complete':True,
                        'blocks':len(state['blocks']),'known_parameters':file_known,'unknown_parameters':file_unknown,
                        'errors':file_errors,'warnings':file_warnings,
                        'diagnostics':state['diagnostics'],'expected_context':baseline['reason']})
    coverage = json.loads((RESOURCE/'manual-coverage.json').read_text('utf-8'))
    if not coverage['passed'] or coverage['corpus_count'] != len(by_id):
        raise ValueError('Manual code coverage did not pass')
    return {'schema_version':1,'version':'0.4.0','firmware':'blue-7','passed':True,
            'meaning':'All source bytes preserved and parsed/catalogued consistently; expected upstream diagnostics retained. DROID hardware and full firmware semantics were not tested.',
            'corpus':inventory,'manual_coverage':{key:value for key,value in coverage.items() if key!='pages'},
            'tests':{'files':len(by_id),'native_checks':engine_report['checks'],
                     'catalogue_and_report_checks':python_checks,'roundtrips_exact':len(by_id),
                     'analysis_complete':len(by_id),'blocks':blocks,
                     'known_parameters':known,'unknown_parameters':unknown,
                     'expected_errors':errors,'expected_warnings':warnings,
                     'expected_diagnostic_categories':dict(categories)},
            'catalogue_sha256':digest(CATALOGUE.read_bytes()),
            'engine_source_sha256':digest((ROOT/'core/src/patch_engine.cpp').read_bytes()),
            'baseline_sha256':digest(BASELINE.read_bytes()),'files':records}


def run(engine: Path) -> dict:
    with tempfile.TemporaryDirectory(prefix='droid-official-') as directory:
        raw = Path(directory)/'engine.json'
        subprocess.run([str(engine.resolve()),str(RESOURCE/'patches'),str(raw)], check=True)
        return validate(json.loads(raw.read_text('utf-8')))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    input_group = parser.add_mutually_exclusive_group(required=True)
    input_group.add_argument('--engine', type=Path, help='Native standalone corpus test executable')
    input_group.add_argument('--engine-report', type=Path, help='Recheck an existing native test JSON')
    parser.add_argument('--report', type=Path, default=ROOT/'dist/official-examples-0.4.0.json')
    args = parser.parse_args()
    result = run(args.engine) if args.engine else validate(json.loads(args.engine_report.read_text('utf-8')))
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({'passed':result['passed'],**result['tests'],'report':str(args.report)},indent=2))
