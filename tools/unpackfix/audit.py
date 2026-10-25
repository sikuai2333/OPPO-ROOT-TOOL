"""Audit an exported r0dump run: completeness, extraction map, shell residue."""
import argparse
import glob
import hashlib
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dexlib import Dex, ACC_NATIVE, ACC_ABSTRACT  # noqa: E402
from heuristics import (SHELL_CLASS_PREFIXES, SHELL_FILES,  # noqa: E402
                        classify_native, shell_vendor)


def collect(paths):
    seen, skipped = {}, []
    for p in paths:
        try:
            data = open(p, 'rb').read()
            dex = Dex(data, os.path.basename(p))
        except Exception as exc:
            skipped.append({'path': p, 'error': str(exc)})
            continue
        digest = hashlib.sha256(data).hexdigest()
        if digest in seen:
            continue
        seen[digest] = (p, data, dex)
    return seen, skipped


def analyse(seen):
    classes, per_dex = {}, []
    totals = dict(methods=0, coded=0, native=0, abstract=0, empty=0)

    for _path, data, dex in seen.values():
        stats = dex.stats()
        for key in totals:
            totals[key] += stats[key]
        per_dex.append({
            'file': stats['file'],
            'sha256': hashlib.sha256(data).hexdigest(),
            'version': stats['dex_version'],
            'bytes': stats['bytes'],
            'classes': stats['classes'],
            'methods': stats['methods'],
            'coded': stats['coded'],
            'native': stats['native'],
            'empty': stats['empty'],
            'checksum_ok': stats['checksum_ok'],
            'file_size_ok': stats['file_size_ok'],
        })
        for descriptor, _cd, methods in dex.classes():
            entry = classes.setdefault(descriptor, {
                'coded': 0, 'native': [], 'empty': [], 'abstract': 0,
            })
            for _idx, name, acc, code_off in methods:
                if acc & ACC_NATIVE:
                    if name not in entry['native']:
                        entry['native'].append(name)
                elif acc & ACC_ABSTRACT:
                    entry['abstract'] += 1
                elif code_off == 0:
                    entry['empty'].append(name)
                else:
                    entry['coded'] += 1

    shell_classes, extracted = [], []
    for descriptor, entry in classes.items():
        kind, confidence, reason = classify_native(
            descriptor, entry['coded'], entry['native'])
        dotted = descriptor[1:-1].replace('/', '.') if descriptor.startswith('L') else descriptor
        if kind == 'shell':
            shell_classes.append({'class': dotted, 'vendor': shell_vendor(dotted) or 'unknown'})
        elif kind == 'extracted':
            extracted.append({
                'class': dotted,
                'java_methods': entry['coded'],
                'native_methods': sorted(entry['native']),
                'confidence': confidence,
                'reason': reason,
            })
    extracted.sort(key=lambda e: (-len(e['native_methods']), e['class']))

    residue = {}
    for item in shell_classes:
        residue.setdefault(item['vendor'], []).append(item['class'])

    return totals, per_dex, extracted, residue


def scan_apk(apk_path):
    import zipfile
    found = []
    try:
        with zipfile.ZipFile(apk_path) as zf:
            for name in zf.namelist():
                base = os.path.basename(name)
                if base in SHELL_FILES:
                    found.append({'entry': name, 'vendor': SHELL_FILES[base]})
                elif name.startswith('assets/') and any(
                        tag in base.lower() for tag in ('jiagu', 'bangcle', 'secshell', 'secexe')):
                    found.append({'entry': name, 'vendor': SHELL_FILES.get(base, 'unknown')})
    except Exception as exc:
        return {'error': str(exc), 'files': []}
    return {'files': found}


def main():
    ap = argparse.ArgumentParser(description='Audit an exported run for completeness and shell residue.')
    ap.add_argument('paths', nargs='+')
    ap.add_argument('--apk', help='original APK, to cross-check shell files')
    ap.add_argument('--json', dest='json_out', help='write the report here')
    args = ap.parse_args()

    dex_paths = []
    for arg in args.paths:
        if os.path.isdir(arg):
            dex_paths.extend(sorted(glob.glob(os.path.join(arg, '**', '*.dex'), recursive=True)))
        else:
            dex_paths.extend(sorted(glob.glob(arg)))

    seen, skipped = collect(dex_paths)
    totals, per_dex, extracted, residue = analyse(seen)

    report = {
        'schema': 1,
        'decoded_dex': len(seen),
        'skipped': skipped,
        'totals': totals,
        'coverage': {
            'coded_pct': round(totals['coded'] / totals['methods'] * 100, 2) if totals['methods'] else 0,
            'native_pct': round(totals['native'] / totals['methods'] * 100, 2) if totals['methods'] else 0,
            'empty_pct': round(totals['empty'] / totals['methods'] * 100, 2) if totals['methods'] else 0,
        },
        'per_dex': per_dex,
        'extracted_methods': extracted,
        'shell_classes': {k: sorted(v) for k, v in residue.items()},
    }
    if args.apk:
        report['apk_shell_files'] = scan_apk(args.apk)

    if args.json_out:
        with open(args.json_out, 'w', encoding='utf-8') as fh:
            json.dump(report, fh, indent=2, ensure_ascii=False)

    cov = report['coverage']
    print(f"DEX files (deduped): {report['decoded_dex']}")
    print(f"methods: {totals['methods']}   coded: {totals['coded']} ({cov['coded_pct']}%)   "
          f"native: {totals['native']} ({cov['native_pct']}%)   "
          f"empty: {totals['empty']} ({cov['empty_pct']}%)")
    bad = [d for d in per_dex if not d['checksum_ok'] or not d['file_size_ok']]
    print(f"structural: {'OK' if not bad else f'{len(bad)} DEX with bad header/checksum'}")
    print()

    high = [e for e in extracted if e['confidence'] == 'high']
    low = [e for e in extracted if e['confidence'] != 'high']
    print(f"extracted methods (high confidence): {len(high)} classes")
    for e in high[:10]:
        print(f"  {e['class']:58} {len(e['native_methods']):2} native  "
              f"{','.join(e['native_methods'][:3])}")
    if len(high) > 10:
        print(f"  ... and {len(high) - 10} more")
    if low:
        print(f"extracted methods (low confidence): {len(low)} classes")
        for e in low[:5]:
            print(f"  {e['class']:58} {len(e['native_methods']):2} native  {e['reason']}")
    print()
    if residue:
        print('shell residue classes:')
        for vendor, names in residue.items():
            print(f"  {vendor}: {len(names)}")
    else:
        print('shell residue classes: none')
    if args.apk and 'apk_shell_files' in report:
        files = report['apk_shell_files'].get('files', [])
        print(f"shell files in APK: {len(files)}")
        for f in files[:8]:
            print(f"  {f['entry']} ({f['vendor']})")
    return 0


if __name__ == '__main__':
    sys.exit(main())
