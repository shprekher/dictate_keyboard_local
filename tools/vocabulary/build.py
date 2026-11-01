#!/usr/bin/env python3
"""Copyright 2026 The Dictate Contributors. SPDX-License-Identifier: Apache-2.0.
Build-only Hunspell expansion for the pinned simple-flag en_US/ru_RU data.
No runtime Python, Hunspell library, downloads or third-party Python packages.
"""
import argparse
import gzip
import hashlib
import json
import re
import struct
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
ASSETS = ROOT / 'app/src/main/assets/vocabulary'
LO = '4f1df64b2e8e14751aa7c2205d170ca016071315'
RU = 'd68c007fe9260d2d40ce413f13f77335c5af1e62'
SOURCES = {
    'en_US.aff': f'https://raw.githubusercontent.com/LibreOffice/dictionaries/{LO}/en/en_US.aff',
    'en_US.dic': f'https://raw.githubusercontent.com/LibreOffice/dictionaries/{LO}/en/en_US.dic',
    'en_US-license.txt': f'https://raw.githubusercontent.com/LibreOffice/dictionaries/{LO}/en/README_en_US.txt',
    'ru_RU.aff': f'https://raw.githubusercontent.com/LibreOffice/dictionaries/{LO}/ru_RU/ru_RU.aff',
    'ru_RU.dic': f'https://raw.githubusercontent.com/LibreOffice/dictionaries/{LO}/ru_RU/ru_RU.dic',
    'ru_RU-license.txt': f'https://raw.githubusercontent.com/LibreOffice/dictionaries/{LO}/ru_RU/README_ru_RU.txt',
    'russian.txt': f'https://raw.githubusercontent.com/danakt/russian-words/{RU}/russian.txt',
    'danakt-license.txt': f'https://raw.githubusercontent.com/danakt/russian-words/{RU}/LICENSE',
}

def expand(aff, dic):
    rules, cross, special = {}, {}, {}
    for line in aff.splitlines():
        p = line.split()
        if not p or p[0].startswith('#'):
            continue
        if p[0] in ('FLAG', 'AF', 'NEEDAFFIX', 'CIRCUMFIX', 'FORBIDDENWORD'):
            raise ValueError(f'Unsupported Hunspell directive: {line}')
        if p[0] in ('ONLYINCOMPOUND', 'NOSUGGEST'):
            special[p[0]] = p[1]
        if p[0] not in ('PFX', 'SFX'):
            continue
        if len(p) == 4:
            cross[p[0], p[1]] = p[2] == 'Y'
        else:
            kind, flag, strip, add, condition = p[:5]
            if '/' in add:
                raise ValueError('Continuation classes need a full Hunspell compiler')
            pattern = re.compile(('^' + condition) if kind == 'PFX' else (condition + '$'))
            rules.setdefault((kind, flag), []).append(('' if strip == '0' else strip, '' if add == '0' else add, pattern))

    def apply(word, kind, flag):
        for strip, add, condition in rules.get((kind, flag), []):
            if not condition.search(word):
                continue
            if kind == 'PFX' and word.startswith(strip):
                yield add + word[len(strip):]
            elif kind == 'SFX' and word.endswith(strip):
                yield (word[:-len(strip)] if strip else word) + add

    for line in dic.splitlines()[1:]:
        if not line.strip():
            continue
        token = line.split()[0]
        word, _, flags = token.partition('/')
        if special.get('ONLYINCOMPOUND', '\0') in flags:
            continue
        yield word
        for flag in flags:
            yield from apply(word, 'SFX', flag)
            for prefixed in apply(word, 'PFX', flag):
                yield prefixed
                if cross.get(('PFX', flag)):
                    for sf in flags:
                        if cross.get(('SFX', sf)):
                            yield from apply(prefixed, 'SFX', sf)

def build(fetch=False):
    source = HERE / 'sources'
    source.mkdir(exist_ok=True)
    ASSETS.mkdir(parents=True, exist_ok=True)
    lock_path = HERE / 'sources.json'
    locked = json.loads(lock_path.read_text()) if lock_path.exists() else {}
    manifest = {}
    for name, url in SOURCES.items():
        path = source / (name + '.gz')
        if fetch and not path.exists():
            data = urllib.request.urlopen(url, timeout=120).read()
            path.write_bytes(gzip.compress(data, mtime=0))
        data = gzip.decompress(path.read_bytes())
        sha = hashlib.sha256(data).hexdigest()
        if name in locked and locked[name]['sha256'] != sha:
            raise ValueError(f'Source hash mismatch: {name}')
        manifest[name] = {'url': url, 'sha256': sha}
        if name.endswith('license.txt'):
            (ASSETS / name).write_bytes(data)
    lock_path.write_text(json.dumps(manifest, indent=2) + '\n')
    read = lambda name: gzip.decompress((source / (name + '.gz')).read_bytes())
    words = set()
    counts = {}
    for lang in ('en_US', 'ru_RU'):
        forms = {w.lower() for w in expand(read(lang + '.aff').decode('utf8'), read(lang + '.dic').decode('utf8'))}
        counts[lang] = len(forms)
        words.update(forms)
    russian = read('russian.txt').decode('cp1251').splitlines()
    counts['danakt_rows'] = len(russian)
    words.update(w.lower() for w in russian)
    words = sorted(w.encode('utf8') for w in words if w and len(w) <= 96 and not any(c.isspace() for c in w))
    # Big endian header, N+1 relative offsets, UTF-8 pool. No millions of heap objects on Android.
    pool = bytearray()
    offsets = [0]
    for word in words:
        pool.extend(word)
        offsets.append(len(pool))
    data = b'DVL1' + struct.pack('>I', len(words)) + struct.pack('>' + 'I' * len(offsets), *offsets) + pool
    packed = gzip.compress(data, compresslevel=9, mtime=0)
    (ASSETS / 'words.lex.gz').write_bytes(packed)
    report = dict(counts, words=len(words), bytes=len(data), compressed_bytes=len(packed), sha256=hashlib.sha256(data).hexdigest())
    (ASSETS / 'manifest.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--fetch', action='store_true', help='Explicit maintainer-only source download')
    build(parser.parse_args().fetch)
