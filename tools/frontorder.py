"""Who does his melee reach first — our melee or our soft creeps? And the same for him, as the control.

    tools/frontorder.py <replay.json.gz> <t0> <t1> [our-username-prefix]

Measured on the blob wipeouts of 08.09.2026 (6aa0008a, 6aa0037a, entry window 30-120): HIS melee reaches one of OUR
soft creeps first in 60 % of ticks, while OUR melee reaches one of his soft creeps first in only 0-10 % — his line
covers its ranged and healers, ours does not. Our melee is the nearest in 2-3 ticks of 28 against his 15-19.
This is the sharpest asymmetry found in the fight itself, and note what it did NOT yield: moving the planned ranged
row back (USE_RANGED_ROW_VS_BLOB) left the number at 63 %, because a slot is a plan and a creep in a fight walks to
its TARGET, so placement rules barely reach the battlefield.

Built on tools/replay.py's own loader. The entry trace showed his melee abreast in one line with his ranged a row
behind, while ours had ranged AHEAD of the melee; `planFight` forbids exactly that (`behindMelee`, v69) and is not the
planner this fight runs. Per tick: the distance from each side's nearest hostile melee to that side's nearest melee
and to its nearest ranged/healer/stripped creep.
"""
import sys
sys.path.insert(0, '/Users/zakharchukart/IdeaProjects/screeps/screeps-kotlin-arena/.claude/worktrees/pain-and-gain/tools')
import importlib.util
spec = importlib.util.spec_from_file_location(
    'rep', '/Users/zakharchukart/IdeaProjects/screeps/screeps-kotlin-arena/.claude/worktrees/pain-and-gain/tools/replay.py')
rep = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rep)

path, t0, t1 = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
us = sys.argv[4] if len(sys.argv) > 4 else 'temik911'
doc, meta, names = rep.load(path)
ours = rep.our_side(meta, us)

def rng(a, b):
    return max(abs(a['x'] - b['x']), abs(a['y'] - b['y']))

def armed(c):
    return c['hits'] > 100 * c['tail']

stat = {'ours': [0, 0, 0, 0], 'his': [0, 0, 0, 0]}   # melee-first, soft-first, level, ticks
for k, start, now, acts, raw in rep.ticks(doc):
    if k < t0 or k > t1:
        continue
    live = [c for c in now.values() if not c.get('spawning') and c['hits'] > 0]
    for label, side in (('ours', ours), ('his', 1 - ours)):
        mine = [c for c in live if c['side'] == side]
        his = [c for c in live if c['side'] != side]
        hm = [c for c in his if c['role'] == 'melee' and armed(c)]
        mm = [c for c in mine if c['role'] == 'melee' and armed(c)]
        ms = [c for c in mine if c['role'] in ('ranged', 'healer') or not armed(c)]
        if not hm or not mm or not ms:
            continue
        dm = min(rng(a, b) for a in mm for b in hm)
        ds = min(rng(a, b) for a in ms for b in hm)
        s = stat[label]
        s[3] += 1
        if dm < ds: s[0] += 1
        elif ds < dm: s[1] += 1
        else: s[2] += 1

for label in ('ours', 'his'):
    m, so, lv, n = stat[label]
    if not n:
        print(f"{label}: no ticks")
        continue
    print(f"{label:5s}: our-melee-nearest {m:4d}  soft-nearest {so:4d}  level {lv:4d}  of {n} ticks — "
          f"the enemy melee reaches a SOFT creep first in {100*so//n} % of ticks")
