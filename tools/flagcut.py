#!/usr/bin/env python3
"""Flag cuts over the whole match store — what the flags, the post and the capture gate are worth.

`tools/autopsy.py` reads ONE match and `tools/ledger.py` adds up its diagnoses; this reads the first hundred ticks of
EVERY stored match and cuts them by what the bot did about flags, which is the half neither of those sees. It answers
three questions that came up on 08.09.2026 and are worth re-asking whenever the doctrine is touched:

  post      does standing at the centre post before contact pay? (08.09: outcome 68 % against 70 % — it does not move
            the outcome — but our score at t=100 is 221.7 against 102.9 and his 300.8 against 414.9, so it pays in
            points and denies him about a hundred; the suspicion that the post costs a fight for nothing was WRONG)
  occupant  among matches posted on the centre, does a creep of OURS stand ON a flag at t=100? (08.09: 80-20, 80 %,
            330.6 points against 23-26, 46 %, and 4.8 — the sharpest split any instrument here has produced. Points
            come from an OCCUPANT, not from guards: the wipeouts print `D50g12`, twelve of ours around a flag that is
            nobody's, scoring nothing. Flags are occupied by the two pure-MOVE runners)
  poised    how long a runner stood ONE CELL from a free flag with `step=stay` — the parity gate (`captureAllowed`)
            refusing the capture. (08.09: never 82 % / 301 points, poised at all 64 % / 112, ten ticks or more 66 % /
            zero. But the poised matches are almost all against his strong bots, which fight AT the flags — held fixed
            by his bot the gap survives pooled, 48-25 against 52-9, while inside けろびー#4 the sign reverses. The gate
            is NOT convicted; separating it from the opponent needs an experiment, not another cut of these records)

Reading it: a match counts only if it is `finished` and its `log-100.json` chunk is stored (`tools/match-log.py fetch`
brings the chunks down). The outcome comes from `game.json` the way match-log.py reads it — `winner` is an INDEX into
the match's code list, not a user id. The opponent is name#version, because one けろびー is several bots.

    tools/flagcut.py                 # every cut, all builds and the recent ones
    tools/flagcut.py --builds v133   # restrict to given bot versions (repeatable, comma-separated)
"""
import argparse
import glob
import json
import os
import re
from collections import Counter, defaultdict

GAMES = os.path.expanduser('~/ScreepsArena/games')
RECENT = ('v131', 'v132', 'v133', 'v134')


def read_store(builds=None):
    """One row per finished stored match with its first log chunk: what the bot did about flags in 100 ticks."""
    rows = []
    for d in sorted(glob.glob(GAMES + '/*/')):
        gp, lp = d + 'game.json', d + 'log-100.json'
        if not (os.path.exists(gp) and os.path.exists(lp)):
            continue
        try:
            g = json.load(open(gp))['game']
            log = json.load(open(lp))
        except Exception:
            continue
        inner = g.get('game') or {}
        if inner.get('status') != 'finished':
            continue
        res = inner.get('result') or {}
        me, code_list = g.get('user'), (g.get('codes') or [])
        win = res.get('winner')
        if win is None or win == 0.5:
            outcome = 'draw'
        elif isinstance(win, int) and 0 <= win < len(code_list):
            outcome = 'won' if code_list[win].get('user') == me else 'lost'
        else:
            continue
        text = '\n'.join(log[k] for k in sorted(log, key=lambda x: int(x)) if isinstance(log[k], str))
        if 'pain-and-gain' not in text:
            continue
        m = re.search(r'hello season4 pain-and-gain (v\d+)', text)
        ver = m.group(1) if m else '?'
        if builds and ver not in builds:
            continue
        users = {u['_id']: u.get('username', '?') for u in (g.get('users') or [])}
        codes = {c.get('user'): c.get('version') for c in (g.get('codes') or [])}
        foe_id = next((u for u in users if u != me), None)
        foe = f"{users.get(foe_id, '?')}#{codes.get(foe_id, '?')}"

        # the centre flag of this map, from the opening `flags:` line
        centre, best = None, 1e9
        for x, y, _t in re.findall(r'\((\d+),(\d+)\)([A-Z]\d)', text[:4000]):
            x, y = int(x), int(y)
            dd = max(abs(x - 50), abs(y - 50))
            if dd < best:
                best, centre = dd, (x, y)

        posts = re.findall(r'posture: (\w+) t=(\d+).*?contact=(\w+).*?post=\((\d+),(\d+)\)', text)
        contact = None
        for _name, t, c, _px, _py in posts:
            if c == 'true':
                contact = int(t)
                break
        if contact is None:
            m = re.search(r'posture: ANNIHILATE t=(\d+)', text)
            contact = int(m.group(1)) if m else None

        on_centre = False
        for _name, t, _c, px, py in posts:
            if contact is not None and int(t) >= contact:
                break
            if centre and (int(px), int(py)) == centre:
                on_centre = True

        m = re.search(r'score t=100: our=(\d+) \(\+(\d+)/t\) enemy=(\d+) \(\+(\d+)/t\)', text)
        ours100, orate, his100, hrate = (int(m.group(1)), int(m.group(2)), int(m.group(3)), int(m.group(4))) if m else (None,) * 4

        held = None
        m = re.search(r't=100 .*?flags=(\S+)', text)
        if m:
            held = 's' in m.group(1)   # `s` marks an occupant of ours in flagsSummary

        adj_poised = 0
        for line in text.split('\n'):
            mm = re.search(r'\((\d+),(\d+)\) M\d+ hits=\d+ POISED flag=\((\d+),(\d+)\)(\S*)', line)
            if mm and max(abs(int(mm.group(1)) - int(mm.group(3))), abs(int(mm.group(2)) - int(mm.group(4)))) <= 1 \
                    and 'my=undefined' in mm.group(5):
                adj_poised += 1

        rows.append(dict(ver=ver, foe=foe, outcome=outcome, contact=contact, on_centre=on_centre,
                         ours100=ours100, his100=his100, orate=orate, hrate=hrate, held=held, adj=adj_poised))
    return rows


def rate(rs):
    w = sum(1 for r in rs if r['outcome'] == 'won')
    l = sum(1 for r in rs if r['outcome'] == 'lost')
    return f"{w}-{l}-{len(rs) - w - l}" + (f" ({100 * w // max(1, w + l)}%)" if w + l else "")


def avg(rs, k):
    v = [r[k] for r in rs if r[k] is not None]
    return round(sum(v) / len(v), 1) if v else None


def line(name, rs):
    print(f"  {name:42s} {len(rs):4d}  {rate(rs):14s} score at 100 ours {avg(rs, 'ours100')} his {avg(rs, 'his100')}")


def cuts(rows, title):
    print(f"\n=== {title} ({len(rows)} matches)")
    line('posted on the centre before contact', [r for r in rows if r['on_centre']])
    line('not posted on the centre', [r for r in rows if not r['on_centre']])
    hc = [r for r in rows if r['on_centre'] and r['held'] is not None]
    line('  of those: a flag OCCUPIED by us at t=100', [r for r in hc if r['held']])
    line('  of those: no occupant of ours', [r for r in hc if not r['held']])
    line('never poised beside a free flag', [r for r in rows if r['adj'] == 0])
    line('poised beside a free flag 1-9 ticks', [r for r in rows if 1 <= r['adj'] <= 9])
    line('poised beside a free flag 10+ ticks', [r for r in rows if r['adj'] >= 10])
    line('zero points of ours at t=100', [r for r in rows if r['ours100'] == 0])
    line('some points of ours at t=100', [r for r in rows if r['ours100'] is not None and r['ours100'] > 0])


def control(rows):
    """The poised split held fixed by HIS BOT — the strong bots fight at the flags, which is what fires the gate."""
    print("\n=== poised, held fixed by his bot (bots with matches on both sides)")
    by = defaultdict(lambda: {True: [0, 0], False: [0, 0]})
    for r in rows:
        if r['outcome'] == 'draw':
            continue
        by[r['foe']][r['adj'] > 0][0 if r['outcome'] == 'won' else 1] += 1
    tw = tl = nw = nl = 0
    for foe, v in sorted(by.items()):
        if sum(v[True]) and sum(v[False]):
            pw, pl = v[True]
            nw_, nl_ = v[False]
            tw += pw; tl += pl; nw += nw_; nl += nl_
            print(f"  {foe:26s} poised {pw}-{pl:<4d} never {nw_}-{nl_}")
    print(f"  --- pooled: poised {tw}-{tl} ({100 * tw // max(1, tw + tl)}%), never {nw}-{nl} ({100 * nw // max(1, nw + nl)}%)")
    print("  poised matches are against:", Counter(r['foe'] for r in rows if r['adj']).most_common(6))
    print("  never-poised are against:  ", Counter(r['foe'] for r in rows if not r['adj']).most_common(6))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--builds', action='append', help='only these bot versions, e.g. v133 (repeatable, comma-separated)')
    a = ap.parse_args()
    builds = set()
    for b in (a.builds or []):
        builds.update(x.strip() for x in b.split(',') if x.strip())
    rows = read_store(builds or None)
    if not rows:
        print('no matches read — is the store populated? (tools/match-log.py fetch --history N)')
        return
    cuts(rows, 'all stored builds')
    if not builds:
        cuts([r for r in rows if r['ver'] in RECENT], 'recent builds ' + '/'.join(RECENT))
    control(rows)


if __name__ == '__main__':
    main()
