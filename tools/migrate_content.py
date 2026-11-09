"""Compile and run the explicit world-ROM migration helper; private configuration stays outside the repo."""
from pathlib import Path
import argparse
import json
import os
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--name', required=True)
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--content', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    if any(os.environ.get(k) for k in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS')):
        raise ValueError('Unexpected inherited Java options')
    if Path(args.name).name != args.name or not args.name or any(c not in 'abcdefghijklmnopqrstuvwxyz0123456789-' for c in args.name):
        raise ValueError('Choose a new lowercase build name')
    out = ROOT / 'build' / args.name
    if out.exists():
        raise ValueError('This build name already exists')
    config = args.config.absolute().resolve()
    if config.is_relative_to(ROOT):
        raise ValueError('Use a server-private configuration outside the product repository')
    content = args.content.absolute().resolve()
    if not content.is_dir():
        raise ValueError('Choose an existing world content directory')
    sources = sorted((ROOT / 'domain/src/main/java').rglob('*.java')) + [ROOT / 'common/src/main/java/net/muxigame/console/ops/ContentMigration.java']
    out.mkdir(parents=True)
    classes = out / 'classes'
    classes.mkdir()
    options = ['--release', '21', '-encoding', 'UTF-8', '-proc:none', '-implicit:none', '-d', str(classes), *map(str, sources)]
    argfile = out / 'compile.args'
    argfile.write_text('\n'.join(json.dumps(x) for x in options), encoding='utf-8')
    exe = '.exe' if os.name == 'nt' else ''
    result = subprocess.run([str(args.java_home / 'bin' / ('javac' + exe)), '-J-Xmx512m', '-J-XX:ActiveProcessorCount=1', '@' + str(argfile)], capture_output=True)
    (out / 'compile.log').write_bytes(result.stdout + result.stderr)
    if result.returncode:
        raise RuntimeError('Migration helper compilation failed; inspect compile.log')
    command = [str(args.java_home / 'bin' / ('java' + exe)), '-Xmx128m', '-XX:ActiveProcessorCount=1', '-cp', str(classes), 'net.muxigame.console.ops.ContentMigration', str(config), str(content)]
    if args.apply:
        command.append('--apply')
    subprocess.run(command, check=True)


if __name__ == '__main__':
    main()
