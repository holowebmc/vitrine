"""Can the jar built for one Minecraft version run on a neighbouring one?

Compares the external member references (owner.name:descriptor) in the compiled classes of two Stonecutter
nodes. Identical sets mean the base jar links on the neighbour exactly like the neighbour's own build.
Usage: add the neighbour as a temporary node, `./gradlew compileJava`, then
    python tools/compare_refs.py 1.21.2:1.21.4 1.21.3:1.21.4      (neighbour:base ...)
"""
import os
import re
import subprocess
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'versions') + '/'
JAVAP = os.environ.get('JAVAP', r'C:/Program Files/Java/jdk-25/bin/javap')
REF = re.compile(r'// (?:Method|InterfaceMethod|Field) ([\w/$]+)\.("?[\w<>$]+"?):(\S+)')
EXTERNAL = ('net/minecraft/', 'com/mojang/', 'net/fabricmc/')


def refs(version):
    base = ROOT + version + '/build/classes/java/main'
    files = [os.path.join(d, f) for d, _, fs in os.walk(base) for f in fs if f.endswith('.class')]
    out = subprocess.run([JAVAP, '-c', '-p'] + files, capture_output=True, text=True).stdout
    return {m.group(0)[3:] for m in REF.finditer(out) if m.group(1).startswith(EXTERNAL)}


pairs = [p.split(':') for p in sys.argv[1:]]
for neighbour, base in pairs:
    a, b = refs(neighbour), refs(base)
    if a == b:
        print(f'{neighbour} == {base}: {len(a)} refs identical')
    else:
        print(f'{neighbour} != {base}')
        for r in sorted(b - a):
            print(f'   only in {base}: {r}')
        for r in sorted(a - b):
            print(f'   only in {neighbour}: {r}')
