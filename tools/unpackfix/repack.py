"""Repack an unpacked run: fix the Application entry, drop shell files, write an APK."""
import argparse
import json
import os
import shutil
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from axml import Axml                                    # noqa: E402
from dexlib import Dex                                   # noqa: E402
from heuristics import SHELL_FILES, shell_vendor         # noqa: E402

SHELL_APPLICATIONS = {
    'com.stub.StubApp': '360',
    'com.qihoo.util.StubApp': '360',
    'com.secneo.apkwrapper.ApplicationWrapper': 'bangcle',
    'com.secneo.apkwrapper.AW': 'bangcle',
    'com.tencent.StubShell.TxAppEntry': 'tencent-legu',
    'com.netease.nis.wrapper.MyApplication': 'netease',
}


LIFECYCLE_HOOKS = ('onCreate', 'onLowMemory', 'onTerminate', 'onTrimMemory',
                   'onConfigurationChanged', 'attachBaseContext', 'onTerminate')


def find_real_application(dex_paths):
    """Application subclass that is not the packer's own entry.

    Several classes can extend Application (facades, proxies). The real entry is
    the one that overrides the most lifecycle hooks -- a proxy usually forwards
    only onCreate.
    """
    scores = {}
    for path in dex_paths:
        try:
            dex = Dex(open(path, 'rb').read(), os.path.basename(path))
        except Exception:
            continue
        for cd in range(dex.class_defs_size):
            base = dex.class_defs_off + cd * 32
            super_idx = dex.u4(base + 8)
            if super_idx == 0xFFFFFFFF:
                continue
            super_name = dex.type_name(super_idx)
            if super_name not in ('Landroid/app/Application;',
                                  'Landroid/app/MultiDexApplication;'):
                continue
            class_name = dex.type_name(dex.u4(base))
            dotted = class_name[1:-1].replace('/', '.')
            if shell_vendor(dotted) or dotted in SHELL_APPLICATIONS:
                continue
            scores.setdefault(dotted, set())
            for desc, _cd, methods in dex.classes():
                if desc != class_name:
                    continue
                for _idx, mname, _acc, _code in methods:
                    if mname in LIFECYCLE_HOOKS:
                        scores[dotted].add(mname)
    ranked = sorted(scores.items(), key=lambda kv: (-len(kv[1]), kv[0]))
    return ranked


def plan(args):
    dex_paths = []
    for root in args.dex_dir:
        for dirpath, _dirs, files in os.walk(root):
            for name in files:
                if name.endswith('.dex'):
                    dex_paths.append(os.path.join(dirpath, name))

    ranked = find_real_application(dex_paths)
    manifest_path = args.manifest
    data = open(manifest_path, 'rb').read()
    axml = Axml(data)

    present_shell_apps = [s for s in axml.pool.strings if s in SHELL_APPLICATIONS]
    plan = {
        'real_applications': [n for n, _h in ranked],
        'application_scores': ranked,
        'shell_applications_in_manifest': present_shell_apps,
        'dex_files': len(dex_paths),
    }
    return plan, axml, manifest_path


def rewrite_manifest(axml, real_app, shell_apps):
    """Swap every shell Application reference for the real one."""
    replacements = {}
    for shell in shell_apps:
        replacements[shell] = real_app
    return axml.rewrite(replacements)


def strip_shell_files(entries, keep=()):
    """Yield (name, skip) for each APK entry, flagging packer payloads."""
    for name in entries:
        base = os.path.basename(name)
        if base in SHELL_FILES and name not in keep:
            yield name, True, SHELL_FILES[base]
            continue
        yield name, False, None


def main():
    ap = argparse.ArgumentParser(description='Repack an exported run into a fixed APK.')
    ap.add_argument('--dex-dir', action='append', required=True,
                    help='directory holding the captured DEX (repeatable)')
    ap.add_argument('--manifest', required=True, help='captured AndroidManifest.xml')
    ap.add_argument('--base-apk', required=True, help='original APK')
    ap.add_argument('--out', required=True, help='output APK')
    ap.add_argument('--real-app', help='override the resolved Application class')
    ap.add_argument('--keep-shell-libs', action='store_true',
                    help='keep packer .so files (required when extracted methods need them)')
    ap.add_argument('--dry-run', action='store_true')
    args = ap.parse_args()

    info, axml, manifest_path = plan(args)
    ranked = info['application_scores']
    real_app = args.real_app or (ranked[0][0] if ranked else None)
    print('real Application candidates (by lifecycle coverage):')
    for name, hooks in ranked[:4]:
        print(f'  {name:48} {len(hooks)} hooks  {sorted(hooks)[:4]}')
    print('shell Applications in manifest:', info['shell_applications_in_manifest'] or '(none)')

    if not real_app:
        print('FAIL: no Application subclass found; cannot repair the entry')
        return 1
    if not info['shell_applications_in_manifest'] and not args.real_app:
        print('note: manifest does not reference a known shell Application; '
              'entry may already be clean')

    new_manifest = rewrite_manifest(axml, real_app,
                                    info['shell_applications_in_manifest'])
    print(f'manifest: {len(info["shell_applications_in_manifest"])} reference(s) '
          f'-> {real_app}  ({len(axml.raw)} -> {len(new_manifest)} bytes)')

    with zipfile.ZipFile(args.base_apk) as src:
        entries = src.namelist()
        removed = []
        kept = []
        for name, skip, vendor in strip_shell_files(entries):
            (removed if skip else kept).append(name if not skip else (name, vendor))
        print(f'apk: {len(entries)} entries, {len(removed)} shell file(s) flagged')
        for name, vendor in removed[:8]:
            print(f'  drop {name} ({vendor})')

        if args.dry_run:
            print('dry run: no output written')
            return 0

        if args.keep_shell_libs:
            print('note: --keep-shell-libs set, packer libs are retained')

        drop = {name for name, _v in removed}
        with zipfile.ZipFile(args.out, 'w', zipfile.ZIP_DEFLATED) as dst:
            for name in entries:
                if name in drop:
                    continue
                data = src.read(name)
                if name == 'AndroidManifest.xml':
                    data = new_manifest
                dst.writestr(name, data)

    print(f'wrote {args.out} ({os.path.getsize(args.out)} bytes)')
    return 0


if __name__ == '__main__':
    sys.exit(main())
