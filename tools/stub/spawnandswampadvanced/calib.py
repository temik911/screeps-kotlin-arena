#!/usr/bin/env python3
"""Calibration of the Spawn and Swamp ADVANCED stub against a played match: one table, "tick in the match / tick in the stub".

  python3 calib.py <game id or prefix> <stub stdout> [<stub bot log>] [--live <live bot log>]

Two sources on each side, the same questions asked of both:
  - the REPLAY of the match (./replays/ first, then ~/ScreepsArena/replays/) against the stub's `milestones:` line — when
    each side's spawns, towers and ramparts appeared (the replay's first reading of the structure; the stub's `built`
    event of the same tick: both are the state after that tick), the first hit on a creep and the first creep death of
    each side;
  - OUR BOT'S OWN LOG of the match (runs/**/<...><last six of the id>.txt, else `tools/match-log.py dump <id>`) against the
    stub's log of the same build — the bot prints its decisions with their tick (`spawn up`, `founder ... leaves`,
    `expansion spawn up`, `twin spawn up`, `tower site`, `posture ...->defend`, `vault N: breached`, `push`/`strike`,
    `my creeps lost`), so a difference there is a difference in what the bot saw.
A row whose two ticks differ by more than 10 is marked `<<`: that is a finding of the stub (README, "Calibration").
"""
import glob
import gzip
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..', '..'))
US = os.environ.get('US', 'temik911')


def find_replay(arg):
    for d in (os.path.join(HERE, 'replays'), os.path.expanduser('~/ScreepsArena/replays')):
        hits = glob.glob(os.path.join(d, arg + '*.replay.json.gz'))
        if len(hits) == 1:
            return hits[0]
    sys.exit(f'calib: no single replay {arg}* in ./replays or ~/ScreepsArena/replays')


def record_milestones(path):
    doc = json.load(gzip.open(path, 'rt', encoding='utf-8'))
    meta = doc['meta']
    us = next(p['side'] for p in meta['players'] if str(p['username']).startswith(US))
    objs = {o['id']: o for o in doc['objects']}
    first = {}
    for tk in doc['ticks']:
        for s in tk.get('s', []):
            first.setdefault(s[0], tk['k'])
    out = {}
    for oid, k in sorted(first.items(), key=lambda kv: kv[1]):
        o = objs.get(oid)
        if not o or o['kind'] not in ('spawn', 'tower', 'rampart', 'extension') or o['side'] is None:
            continue
        key = ('ours' if o['side'] == us else 'enemy') + '.' + o['kind']
        out.setdefault(key, []).append((k, o['x'], o['y']))
    hits = {}
    side = {}
    hit = {0: None, 1: None}
    death = {0: None, 1: None}
    deaths = {0: 0, 1: 0}
    for tk in doc['ticks']:
        k = tk['k']
        for n in tk.get('n', []):
            side[n[0]] = 0 if n[1] == us else 1
            hits[n[0]] = n[4]
        for cid, x, y, h, f, sp in tk.get('u', []):
            if cid in hits and h < hits[cid] and hit[side[cid]] is None:
                hit[side[cid]] = k
            if cid in hits:
                hits[cid] = h
        for cid in tk.get('x', []):
            if cid in side and k < meta['ticks'] - 1:
                deaths[side[cid]] += 1
                if death[side[cid]] is None:
                    death[side[cid]] = k
    res = meta.get('result') or {}
    return out, hit, death, deaths, meta, (res.get('winnerName') + ' won') if not res.get('draw') and res.get('winnerName') else 'draw'


def stub_milestones(stdout):
    txt = open(stdout, encoding='utf-8').read()
    m = re.search(r'^milestones: (.*)$', txt, re.M)
    if not m:
        sys.exit(f'calib: no milestones line in {stdout}')
    out = {}
    for key, val in re.findall(r'(\w+\.\w+)=(\S+)', m.group(1)):
        out[key] = [(int(a), int(b), int(c)) for a, b, c in re.findall(r'(\d+)@(\d+),(\d+)', val)]
    hit = re.search(r'hit=(\w+)/(\w+)', m.group(1))
    death = re.search(r'death=(\w+)/(\w+)', m.group(1))
    deaths = re.search(r'deaths=(\d+)/(\d+)', m.group(1))
    n = lambda v: None if v == 'null' else int(v)
    done = re.search(r'^done: (.*?) alive=', txt, re.M)
    return out, {0: n(hit.group(1)), 1: n(hit.group(2))}, {0: n(death.group(1)), 1: n(death.group(2))}, \
        {0: int(deaths.group(1)), 1: int(deaths.group(2))}, done.group(1) if done else '?'


EVENTS = [
    ('spawn up (first base)', r'^spawn up t=(\d+)'),
    ('founder leaves', r'^founder t=(\d+) .* leaves'),
    ('expansion spawn up #1', r'^expansion spawn up t=(\d+)'),
    ('expansion spawn up #2', r'^expansion spawn up t=(\d+)', 1),
    ('twin spawn up', r'^twin spawn up t=(\d+)'),
    ('first tower site', r'^tower site t=(\d+)'),
    ('first posture ->defend', r'^posture t=(\d+) \w+->defend'),
    ('first vault breached', r'^vault \d: breached t=(\d+)'),
    ('first vault spawn up', r'^vault \d: spawn up t=(\d+)'),
    ('first push', r'^push t=(\d+)'),
    ('first strike', r'^strike t=(\d+)'),
    ('first creep of ours lost', r'^my creeps lost t=(\d+)'),
    ('first base fallen', r'^base fallen t=(\d+)'),
]


def log_events(text):
    out = {}
    for ev in EVENTS:
        name, rx = ev[0], ev[1]
        nth = ev[2] if len(ev) > 2 else 0
        found = [int(m.group(1)) for m in re.finditer(rx, text, re.M)]
        out[name] = found[nth] if len(found) > nth else None
    return out


def live_log(gid, given):
    if given:
        return open(given, encoding='utf-8', errors='replace').read()
    hits = glob.glob(os.path.join(ROOT, 'runs', '**', f'*{gid[-6:]}.txt'), recursive=True)
    if hits:
        return open(sorted(hits)[0], encoding='utf-8', errors='replace').read()
    r = subprocess.run([sys.executable, os.path.join(ROOT, 'tools', 'match-log.py'), 'dump', gid], capture_output=True, text=True)
    return r.stdout


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    live_arg = None
    if '--live' in sys.argv:
        live_arg = sys.argv[sys.argv.index('--live') + 1]
        args = [a for a in args if a != live_arg]
    if len(args) < 2:
        sys.exit(__doc__)
    rpath = find_replay(args[0])
    rec, rhit, rdeath, rdeaths, meta, rres = record_milestones(rpath)
    stub, shit, sdeath, sdeaths, sres = stub_milestones(args[1])
    gid = meta.get('gameId') or args[0]
    rows = []

    def row(name, a, b):
        d = '' if a is None or b is None else f'{b - a:+d}'
        flag = '  <<' if a is not None and b is not None and abs(b - a) > 10 else ('  <<' if (a is None) != (b is None) else '')
        rows.append(f'  {name:<34} {str(a) if a is not None else "-":>7} {str(b) if b is not None else "-":>7} {d:>6}{flag}')

    horizon = None
    m = re.search(r'ticks=(\d+)', open(args[1], encoding='utf-8').read())
    if m:
        horizon = int(m.group(1))
    for key in ('ours.spawn', 'ours.tower', 'ours.rampart', 'enemy.spawn', 'enemy.tower', 'enemy.rampart'):
        a = [r for r in rec.get(key, []) if horizon is None or r[0] <= horizon]
        b = stub.get(key, [])
        for i in range(min(4 if key.endswith('rampart') else 6, max(len(a), len(b)))):
            ra = a[i] if i < len(a) else None
            rb = b[i] if i < len(b) else None
            cell = f'@{ra[1]},{ra[2]}' if ra else (f'@{rb[1]},{rb[2]}' if rb else '')
            if ra and rb and (ra[1], ra[2]) != (rb[1], rb[2]):
                cell = f'@{ra[1]},{ra[2]}|{rb[1]},{rb[2]}'
            row(f'{key} #{i + 1} {cell}', ra[0] if ra else None, rb[0] if rb else None)
    row('first hit on a creep of ours', rhit[0], shit[0])
    row('first hit on a creep of his', rhit[1], shit[1])
    row('first creep of ours dead', rdeath[0], sdeath[0])
    row('first creep of his dead', rdeath[1], sdeath[1])
    live = log_events(live_log(gid, live_arg))
    stublog = open(args[2], encoding='utf-8', errors='replace').read() if len(args) > 2 else ''
    sev = log_events(stublog) if stublog else {}
    print(f'{gid}: the match {rres} in {meta["ticks"]} ticks; the stub: {sres} (horizon {horizon})')
    print(f'  {"milestone (replay / stub stdout)":<34} {"match":>7} {"stub":>7} {"d":>6}')
    print('\n'.join(rows))
    if stublog:
        print(f'  {"bot log line (live log / stub log)":<34} {"match":>7} {"stub":>7}')
        rows.clear()
        for name, _ in [(e[0], e[1]) for e in EVENTS]:
            a, b = live.get(name), sev.get(name)
            if horizon is not None and a is not None and a > horizon and b is None:
                continue
            row(name, a, b)
        print('\n'.join(rows))
    print(f'  deaths within the stub horizon: ours {sdeaths[0]} (match {rdeaths[0]} over the whole match), his {sdeaths[1]} (match {rdeaths[1]})')


if __name__ == '__main__':
    main()
