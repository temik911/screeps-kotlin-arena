#!/usr/bin/env python3
"""The first line where the stub's log of our bot parts from the live log of the same match (same build, same record).

  python3 firstdiff.py <live bot log> <stub bot log> [-n 6] [--ignore REGEX]...

Both logs are the bot's own console; only what cannot be the same is masked: the CPU reading (`cpu=Nms`, `cpu t=` lines),
the ids of the objects made during the match (`role ... is <id>`) and the stub's EVENTS section; --ignore drops the lines a known, named difference makes (e.g. `^enemy objects` — the ghost
places his sites a tick before their first recorded build, and the record does not say when he really placed them). The
first differing line is where the stub stopped being the match — read it before any
table: a milestone that differs later is usually this line's consequence. -n prints that many lines on each side.
"""
import re
import sys


IGNORE = []


def lines(path):
    out = []
    for l in open(path, encoding='utf-8', errors='replace').read().split('\n'):
        if l.startswith('=== EVENTS'):
            break
        l = re.sub(r'\x1b\[[0-9;]*m', '', l)
        l = re.sub(r'cpu=\d+ms', 'cpu=_', l)
        l = re.sub(r' is \d+ ', ' is <id> ', l)   # object ids: the stub numbers what it makes from 10000, live from ~69
        if l.startswith('cpu t=') or not l.strip() or any(re.search(rx, l) for rx in IGNORE):
            continue
        out.append(l)
    return out


def main():
    argv = sys.argv[1:]
    n = 6
    args = []
    i = 0
    while i < len(argv):
        if argv[i] == '-n': n = int(argv[i + 1]); i += 2
        elif argv[i] == '--ignore': IGNORE.append(argv[i + 1]); i += 2
        else: args.append(argv[i]); i += 1
    a, b = lines(args[0]), lines(args[1])
    for i, (x, y) in enumerate(zip(a, b)):
        if x != y:
            tick = re.search(r't=(\d+)', x)
            print(f'first difference at line {i + 1}' + (f' (t={tick.group(1)})' if tick else ''))
            for k in range(i, min(i + n, len(a))):
                print(f'  live: {a[k][:260]}')
            for k in range(i, min(i + n, len(b))):
                print(f'  stub: {b[k][:260]}')
            return 1
    print(f'identical for {min(len(a), len(b))} lines (live {len(a)}, stub {len(b)})')
    return 0


if __name__ == '__main__':
    sys.exit(main())
