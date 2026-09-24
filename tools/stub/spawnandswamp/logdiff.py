#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Identity of the stub's logs: the first difference of every gate log against a reference set.

Copied from tools/stub/painandgain/logdiff.py (24.09.2026) and fitted to this harness, per the copy-never-share rule.
`regress.sh <tag>` writes `out/<tag>_r2_<mode>.txt` / `out/<tag>_r3_<scenario>.txt`; `tools/play.py --ab <A> <B> --dry`
runs it with the tag `land` in two worktrees and calls this script on the two `out/` folders, so a change that must not
alter behaviour (an instrument, a switch that is off, a play meant for one opponent's signature) is PROVEN inert here —
the gate's one-line report compares outcome ticks only, this compares the logs line by line.

The logs are deterministic except the `--- loop ms:` line (wall time of the bot's loop) and the version in the greeting;
those two are the only things dropped or masked.

  python3 tools/stub/spawnandswamp/logdiff.py --old <ref out/> --new tools/stub/spawnandswamp/out [--tag land] [substring…]

Prints the number of logs that differ, a histogram of "line tag + first differing k=v field" and the first differences
(old/new) — for every log, or only for those whose name contains one of the substrings. Exit code 1 if anything differs.

A NEW FIELD IS MASKED BY ITSELF (the Pain and Gain rule of 20.09.2026): a `k=…` word whose key the reference never
printed under that line tag is taken out of the new line and not counted as a difference; a line whose tag the reference
never printed at all is a new instrument line and is skipped. Both are NAMED in the report — no silent mask. The other
direction has no leniency: a field the reference has, missing or changed in the new log, is a difference. That is the
oracle.
"""
import argparse
import os
import re
from collections import Counter

MASK = [
    (re.compile(r'hello season4 spawn-and-swamp v\d+'), 'hello season4 spawn-and-swamp vX'),
]


def lines(p):
    out = []
    for l in open(p, encoding='utf-8', errors='replace').read().split('\n'):
        l = re.sub(r'\x1b\[[0-9;]*m', '', l)
        if l.startswith('--- loop ms:'):
            continue
        for rx, rep in MASK:
            l = rx.sub(rep, l)
        out.append(l)
    return out


FIELD = re.compile(r'(?<!\S)([A-Za-z][\w.#]*)=')


def tag_of(line):
    """A log line's tag — its first word without a `=…` tail (`t=50` -> `t`, `posture:` -> `posture:`)."""
    return line.split(' ', 1)[0].split('=', 1)[0]


class Baseline:
    """What the WHOLE reference knows, not one of its logs: a tag or a field is new only if no reference log has it —
    otherwise an event that simply did not happen in this scenario before would pass for a new instrument, and that is
    a behaviour difference. Read lazily: while lines agree the reference is not needed in full."""

    def __init__(self, folder, prefix):
        self.folder, self.prefix, self.texts, self.memo = folder, prefix, None, {}

    def _load(self):
        if self.texts is None:
            self.texts = [open(os.path.join(self.folder, f), encoding='utf-8', errors='replace').read()
                          for f in sorted(os.listdir(self.folder)) if f.startswith(self.prefix)]

    def has(self, tag, key=None):
        k = (tag, key)
        if k not in self.memo:
            self._load()
            rx = re.compile(r'^%s[ =]%s' % (re.escape(tag), '' if key is None else r'[^\n]*(?<!\S)%s=' % re.escape(key)), re.M)
            self.memo[k] = any(rx.search(t) for t in self.texts)
        return self.memo[k]


def drop_new_fields(old, new, base, seen):
    """Take out of the new line the `k=…` words whose key is neither in the reference line nor anywhere in the reference
    under the same line tag; record what was taken in `seen`. A word runs from space to space: an instrument's field is
    printed without spaces inside."""
    have = set(FIELD.findall(old))
    tag = tag_of(old)
    out = []
    for w in new.split(' '):
        m = FIELD.match(w)
        if m and m.group(1) not in have and not base.has(tag, m.group(1)):
            seen[m.group(1)] += 1
            continue
        out.append(w)
    return ' '.join(out)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--old', required=True, help='reference logs: an out/ folder of the build the identity is checked against')
    ap.add_argument('--new', default='tools/stub/spawnandswamp/out')
    ap.add_argument('--tag', default='land', help='the regress.sh tag both folders were written with (file prefix <tag>_)')
    ap.add_argument('--new-tag', default=None, help='the tag of the new folder when it differs from --tag')
    ap.add_argument('--show', type=int, default=6, help='how many differences to print in full')
    ap.add_argument('only', nargs='*')
    a = ap.parse_args()
    old_prefix = a.tag + '_'
    new_prefix = (a.new_tag or a.tag) + '_'
    first_fields = Counter()
    new_fields, new_lines = Counter(), Counter()
    base = Baseline(a.old, old_prefix)
    rows, seen = [], 0
    for f in sorted(os.listdir(a.old)):
        if not f.startswith(old_prefix):
            continue
        if a.only and not any(o in f for o in a.only):
            continue
        seen += 1
        new_p = os.path.join(a.new, new_prefix + f[len(old_prefix):])
        if not os.path.exists(new_p):
            rows.append((f, 0, '<no log>', [], '', '<no new log>'))
            continue
        x = lines(os.path.join(a.old, f))
        y = lines(new_p)
        old_tags = {tag_of(l) for l in x}
        kept = []
        for l in y:                                     # a new instrument line: its tag is NOWHERE in the reference
            if l and tag_of(l) not in old_tags and not base.has(tag_of(l)):
                new_lines[tag_of(l)] += 1
            else:
                kept.append(l)
        y = kept
        k = None
        for i in range(min(len(x), len(y))):            # a new field: its key is not in the reference under this tag
            if x[i] != y[i]:
                found = Counter()
                y[i] = drop_new_fields(x[i], y[i], base, found)
                if x[i] != y[i]:                        # the first real difference: the lines after it are shifted
                    k = i
                    break
                new_fields.update(found)
        if k is None and len(x) == len(y):
            continue
        if k is None:
            k = min(len(x), len(y))
        la = x[k] if k < len(x) else '<end>'
        lb = y[k] if k < len(y) else '<end>'
        fa = dict(re.findall(r'(\S+?)=(\S+)', la))
        fb = dict(re.findall(r'(\S+?)=(\S+)', lb))
        diffk = [q for q in fa if q in fb and fa[q] != fb[q]]
        tag = la.split(' ')[0] if la else '?'
        first_fields[(tag, diffk[0] if diffk else '-')] += 1
        rows.append((f, k + 1, tag, diffk[:4], la[:150], lb[:150]))
    if seen == 0:
        print(f'no reference logs {old_prefix}* in {a.old}')
        return 2
    print(f'logs differing: {len(rows)} of {seen}')
    if new_fields:
        print('new fields (absent from the reference, not counted): %s' % ', '.join('%s= x%d' % kv for kv in sorted(new_fields.items())))
    if new_lines:
        print('new lines (tag absent from the reference, skipped): %s' % ', '.join('%s x%d' % kv for kv in sorted(new_lines.items())))
    for key, n in first_fields.most_common(12):
        print('%3d  %s %s' % (n, key[0], key[1]))
    for r in (rows if a.only else rows[:a.show]):
        print('---', r[0], 'line', r[1], 'fields', r[3])
        print('  old:', r[4])
        print('  new:', r[5])
    return 1 if rows else 0


if __name__ == '__main__':
    raise SystemExit(main())
