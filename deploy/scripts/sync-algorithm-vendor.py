#!/usr/bin/env python3
"""Mechanically import a pinned Git tree and retain byte-level provenance."""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--source', type=Path, required=True)
parser.add_argument('--commit', required=True)
args = parser.parse_args()
assert len(args.commit) == 40 and all(char in '0123456789abcdef' for char in args.commit)
source = args.source.resolve()
target = Path(__file__).resolve().parents[2] / 'fusioncareer-agent/app/vendor/fusioncareer_algorithm'
manifest_file = target / 'PROVENANCE.json'
previous = json.loads(manifest_file.read_text())
names = subprocess.check_output(['git','-C',str(source),'ls-tree','-rz','--name-only',args.commit]).decode().split('\0')
names = [name for name in names if name]
assert 'PROVENANCE.json' not in names
assert not (set(previous['sha256']) - set(names)), 'Handle removed upstream files explicitly before syncing'
for name, expected in previous['sha256'].items():
    assert hashlib.sha256((target / name).read_bytes()).hexdigest() == expected, f'Local vendor modification: {name}'
hashes = {}
for name in names:
    path = Path(name)
    assert not path.is_absolute() and '..' not in path.parts
    data = subprocess.check_output(['git','-C',str(source),'show',f'{args.commit}:{name}'])
    destination = target / path
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(data)
    hashes[name] = hashlib.sha256(data).hexdigest()
manifest = {'repository':previous['repository'],'commit':args.commit,'sha256':hashes}
manifest_file.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
print(f'Imported {len(hashes)} tracked files at {args.commit}; upstream bytes unchanged.')
