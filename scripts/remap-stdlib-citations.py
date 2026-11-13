"""Remap stdlib inventory citations to the lines that actually register each name.

Digits-only by construction: every replacement rewrites numbers in place and
leaves separators, ordering and surrounding prose byte-identical, so a diff
from this script can only ever be line numbers.
"""
import re, pathlib, sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else '.')
inv = ROOT / 'docs/governance/alpha-4-stdlib-inventory.md'
stdlib = ROOT / 'oq-stdlib/src/main/java/dev/oqthara/stdlib'

def registered(path):
    found = {}
    for i, line in enumerate(path.read_text(encoding='utf-8').split('\n'), 1):
        m = (re.search(r'defineBuiltin\(\s*NativeFunction\.\w+\("([a-z_0-9]+)"', line)
             or re.search(r'defineBuiltin\(\s*"([a-z_0-9]+)"', line))
        if m:
            found.setdefault(m.group(1), []).append(i)
    return found

tables = {f.name: registered(f) for f in stdlib.glob('*.java')}
text = inv.read_text(encoding='utf-8')
heading = re.search(r"^### The (\d+) names, by registration site$", text, re.M)
start = text.index(heading.group(0))
end = text.index("### What the " + heading.group(1) + " do not include")
section = text[start:end]

changed = unresolved = 0
mismatched = []

def fix(m):
    global changed, unresolved
    name, body = m.group(1), m.group(2)
    nums = re.findall(r'\d+', body)
    before = section[:m.start()]
    named = re.findall(r'in `(\w+\.java)`', before[max(0, len(before) - 2000):])
    file_name = next((c for c in named if name in tables.get(c, {})), 'Stdlib.java')
    if file_name == 'Stdlib.java':
        file_name = next((c for c in reversed(named) if name in tables.get(c, {})), file_name)
    truth = tables.get(file_name, {}).get(name)
    if not truth:
        unresolved += 1
        return m.group(0)
    if [int(n) for n in nums] == sorted(truth):
        return m.group(0)
    if len(nums) != len(truth):
        # A different number of registration sites is a structural mismatch, not a moved
        # line. Rewriting digits cannot express it -- the entry needs a separator added or
        # removed -- and improvising that here is how the prose got mangled before. Leave it
        # for a human and say so.
        mismatched.append(f"`{name}`: cites {len(nums)}, registers {len(truth)}")
        return m.group(0)
    changed += 1
    it = iter(sorted(truth))
    return f"`{name}`:" + re.sub(r'\d+', lambda n: str(next(it, n.group(0))), body)

inv.write_text(text[:start] + re.sub(r"`([a-z_0-9]+)`:((?:\d+(?:\s*(?:,|and)\s*)?)+)", fix, section)
               + text[end:], encoding='utf-8')
print(f"rewritten={changed} unresolved={unresolved}")
for note in mismatched:
    print(f"  needs a human: {note}", file=sys.stderr)
if mismatched:
    sys.exit(1)
