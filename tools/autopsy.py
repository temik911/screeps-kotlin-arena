#!/usr/bin/env python3
"""Autopsy of one live Pain and Gain match — one command, one report, with a diagnosis line per rule that fired.

    tools/autopsy.py <match-id | id-prefix | log-file> [--replay f.replay.json.gz] [--fetch] [--json] [--step 100]

What it reads: our console log (the match store via match-log.py — the API's documents, fetched through the client — or a file written by play.py --logs; the
replay's own `logs` only as a fallback — the tool records them incompletely) and, when there is one, the full replay from
arukuka/screeps-arena-tools (both sides' intents and positions per tick; see replay.py for the setup). Replays are
looked up as <dir>/<id>.replay.json.gz in --replays (default ~/ScreepsArena/replays, or $ARENA_REPLAYS); --fetch
downloads a missing one through the running client with the tool under --tools (default
~/ScreepsArena/screeps-arena-tools, or $ARENA_TOOLS). Without a replay the log-only sections still print.

What it says, in order: the header (opponent, result, rating, our version); the outcome's form (annihilation or
points, the tick the score and the damage ledger diverged); the opponent's form by the replay track (blob / line /
scatter / farmer / sentinel / touring, with the numbers it was judged on); the timeline every --step ticks; the
posture transitions and their flicker; the first contact's geometry (who marched and who stood, distance to the
map edge, compactness, who fired the first volleys, power ratio at contact); the fight's decomposition (expected
damage and healing per side from the intents, uptime per role, melee adjacency, deaths in order); the nets that
fired and the ones that stayed silent (stall, detach, press give-ups, plan yields, keepers, evades); the runners'
longest stands; then DIAGNOSIS lines — each a rule with its threshold and the number that crossed it — and the
history against the same opponent from the match store. --json prints the whole measurement as JSON for
aggregation across matches (tools/ledger.py).

Everything here used to be five scripts and half an hour per match (tfields, replay summary/track, replay-damage,
grep counts, a look at the evade lines); the rules at the bottom are the questions those half hours kept asking.
"""
import argparse, glob, gzip, json, os, re, subprocess, sys, time, importlib.util
from collections import Counter, defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))


def _load(name, fname):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, fname))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


rp = _load('replay', 'replay.py')
ml = _load('matchlog', 'match-log.py')

FLAGS = {(49, 49): 'D5', (90, 8): 'H4a', (8, 90): 'H4b', (67, 31): 'A3a', (31, 67): 'A3b', (13, 49): 'R3a', (85, 49): 'R3b'}
RANGED_RANGE = 3
GROUP_RANGE = 8          # replay.py track: his "largest group" is creeps within eight cells of each other
EDGE_CORNER = 8          # our armed centroid this close to the map edge = fighting with the back to the wall
STEP_TRACK = 50          # his centroid is sampled every fifty ticks for the form classification
LINE_MOVE = 2.0          # a line's centroid moves at most this many cells per ten ticks of contact; a blob keeps closing

# diagnosis thresholds — each rule below quotes the number it crossed
CORNER_SHARE = 0.5       # share of contact ticks with our centroid within EDGE_CORNER of the edge
FLICKER_PER_100 = 5      # posture transitions in any 100-tick window past the opening (t >= FLICKER_FROM)
FLICKER_FROM = 50
QUIET_TICKS = 500        # consecutive ticks without a target in reach while behind on score or rate
GIVEUP_PER_100 = 15      # press give-ups per 100 contact ticks
RUNNER_STAND = 300       # a runner on one cell this long with the flag not ours
MELEE_IDLE = 50          # idle melee creep-ticks with an enemy within engage range (the bot's `why` trace, v108)
STRIPPED_X = 3.0         # our stripped creep-ticks in his reach this many times his (a stripped creep has no weapon or heal part left)
STRIPPED_MIN = 30
ENTRY_TICKS = 20         # the entry: this many ticks from the first contact
OPENING_TICKS = 60       # the opening: this many ticks from the first contact — the window the reach rule reads
REACH_GAP = 0.10         # his ranged's share of creep-ticks with a target within 3 this far above ours in the opening
REACH_MIN = 100          # ... with at least this many ranged creep-ticks on each side
ENTRY_X = 1.5            # hits we lost in the entry this many times what he lost
UPTIME_LOW = 0.85        # our uptime in a role below this while his is above UPTIME_HIGH
UPTIME_HIGH = 0.90
ADJACENCY_X = 2.0        # his melee adjacent creep-ticks this many times ours
VOLLEY_X = 2.0           # his shots in the first five ticks of contact this many times ours
MARCH_CELLS = 5          # a centroid that moved this far in the ten ticks before contact was marching
FORMED_CELLS = 3         # ... and one that moved at most this far was formed
POWER_LOW = 0.9          # our/his power (the bot's own measure) at contact below this = went in short
SCORE_GAP = 300          # the score gap that counts as diverged
LEDGER_GAP = 1500        # the damage ledger gap that counts as diverged


def rng(a, b):
    return max(abs(a[0] - b[0]), abs(a[1] - b[1]))


def pct(a, b):
    return f"{a}/{b} ({100 * a // b if b else 0}%)"


# ---------------------------------------------------------------- the log

T_RE = re.compile(r'^t=(\d+) (.*)$')
KV_RE = re.compile(r'(\w+)=(\S+)')
POSTURE_RE = re.compile(r'^posture: (\w+) t=(\d+) (.*)$')
EVADE_RE = re.compile(r'^evade: t=(\d+) (to|flee)=\((\d+),(\d+)\)(.*)$')
NET_RE = re.compile(r'^(stall|detach|keeper|plan|flag|ghost damage) t=(\d+): (.*)$')
PRESS_RE = re.compile(r'^press t=(\d+): (.*)$')
RUNNER_RE = re.compile(r'^  r(\S+) \((\d+),(\d+)\) (\S+) hits=(\d+) (\w+) flag=(\S+) fatigue=(\d+) step=(\S+)')
ARMIES_RE = re.compile(r'^armies t=(\d+): ours\((\d+)\) (.*?) \| enemy\((\d+)\) (.*)$')
HELLO_RE = re.compile(r'^hello \S+ \S+ (v[\w.-]+):')
WHY_RE = re.compile(r'^why t=(\d+): (.*)$')
WHY_ITEM = re.compile(r'(\S+?)@\((\d+),(\d+)\)d(\d+)>(\S+?)\[([^\]]*)\](\S+?)/(\S+)')
FLAG_ITEM = re.compile(r'([ADHR])(\d)([+\-0])([se]?)(?:g(\d+))?')
PAIR_RE = re.compile(r'\((-?\d+),(-?\d+)\)')


def _pair(s):
    m = PAIR_RE.match(s or '')
    return (int(m.group(1)), int(m.group(2))) if m else None


def _ratio(s):
    a, b = s.split('/')[:2]
    return int(a), int(b)


def parse_log(text):
    L = dict(version=None, tuning=None, samples=[], postures=[], evades=[], stalls=[], detaches=[], keepers=[],
             plans=[], flags=[], ghosts=[], giveups=[], press_in=[], runners=defaultdict(list), armies=[],
             errors=Counter(), stuck=0, lines=0, why_ticks=0, why_creep_ticks=0, why_reasons=Counter(), why_did=Counter(), why_by=Counter(),
             why_first=None, why_last=None)
    tick = 0
    for line in text.splitlines():
        L['lines'] += 1
        m = T_RE.match(line)
        if m:
            tick = int(m.group(1))
            d = dict(KV_RE.findall(m.group(2)))
            try:
                run = re.search(r'runners=(\d+)\((\d+) detached\)', m.group(2)) or re.search(r'runners=(\d+)()', m.group(2))
                s = dict(t=tick, army=int(d['army']), runners=int(run.group(1)), detached=int(run.group(2) or 0),
                         enemies=_ratio(d['enemies'])[0], combat=_ratio(d['enemies'])[1],
                         reach=_ratio(d.get('reach', '0/0'))[0], reachable=_ratio(d.get('reach', '0/0'))[1],   # no reach= before v20
                         score=_ratio(d['score']), rate=_ratio(d['rate']), behind=d.get('behind') == 'true',
                         posture=d.get('posture'), obj=d.get('obj'), hunt=d.get('hunt') == 'true', rush=d.get('rush') == 'true',
                         our=int(d['our']), enemy=int(d['enemy']), ledger=int(d.get('ledger', 0)), wounded=int(d.get('wounded', 0)),
                         hits=_ratio(d['hits']), ehits=_ratio(d['enemyHits']),
                         cen=_pair(d.get('centroid')), ecen=_pair(d.get('enemyCentroid')))
                items = FLAG_ITEM.findall(d.get('flags', ''))
                s['flags_ours'] = sum(1 for it in items if it[2] == '+')
                s['flags_theirs'] = sum(1 for it in items if it[2] == '-')
                s['flags'] = d.get('flags', '')
                L['samples'].append(s)
            except (KeyError, ValueError, AttributeError):
                pass
            continue
        m = POSTURE_RE.match(line)
        if m:
            tick = int(m.group(2))
            d = dict(KV_RE.findall(m.group(3)))
            L['postures'].append(dict(t=tick, posture=m.group(1), contact=d.get('contact') == 'true', near=d.get('near') == 'true',
                                      pushing=d.get('pushing') == 'true', obj=d.get('obj', '-'), evadeTo=d.get('evadeTo', '-'),
                                      retreatTo=d.get('retreatTo', '-'), approach=d.get('approach'), our=d.get('our'), enemy=d.get('enemy')))
            continue
        m = EVADE_RE.match(line)
        if m:
            tick = int(m.group(1))
            L['evades'].append(dict(t=tick, kind=m.group(2), cell=(int(m.group(3)), int(m.group(4))), rest=m.group(5).strip()))
            continue
        m = NET_RE.match(line)
        if m:
            tick = int(m.group(2))
            key = {'stall': 'stalls', 'detach': 'detaches', 'keeper': 'keepers', 'plan': 'plans', 'flag': 'flags', 'ghost damage': 'ghosts'}[m.group(1)]
            L[key].append(dict(t=tick, text=m.group(3)))
            continue
        m = PRESS_RE.match(line)
        if m:
            tick = int(m.group(1))
            if 'keeps its distance' in m.group(2):
                L['giveups'].append(dict(t=tick, id=m.group(2).split()[0]))
            elif 'goes in' in m.group(2):
                L['press_in'].append(tick)
            continue
        m = RUNNER_RE.match(line)
        if m:
            L['runners'][m.group(1)].append(dict(t=tick, cell=(int(m.group(2)), int(m.group(3))), mode=m.group(6), flag=m.group(7), step=m.group(9)))
            continue
        m = ARMIES_RE.match(line)
        if m:
            L['armies'].append(dict(t=int(m.group(1)), ours=int(m.group(2)), enemy=int(m.group(4)), ours_bodies=m.group(3), enemy_bodies=m.group(5)))
            continue
        m = HELLO_RE.match(line)
        if m:
            L['version'] = m.group(1)
            continue
        m = WHY_RE.match(line)
        if m:
            tick = int(m.group(1))
            L['why_ticks'] += 1
            L['why_first'] = tick if L['why_first'] is None else L['why_first']
            L['why_last'] = tick
            for it in WHY_ITEM.finditer(m.group(2)):
                L['why_creep_ticks'] += 1
                L['why_by'][it.group(1)] += 1
                for r in it.group(6).split(','):
                    L['why_reasons'][r] += 1
                L['why_did'][it.group(7)] += 1
            continue
        if line.startswith('tuning: '):
            L['tuning'] = line[8:]
        elif 'loop error' in line:
            L['errors']['loop error'] += 1
        elif 'timed out' in line:
            L['errors']['timed out'] += 1
        elif line.startswith('stuck '):
            L['stuck'] += 1
    return L


# ---------------------------------------------------------------- the replay

def expand(body):
    out = []
    for t, n in re.findall(r'([a-z])(\d+)', body):
        out += [t] * int(n)
    return out


def live_parts(c, t):
    """Live parts of type t: the engine takes damage FRONT to back, so part i (0 = front) is dead once the damage
    taken reaches 100 * (i + 1) — the last part lives as long as the creep does. (The first cut counted from the
    back — `hits > i * 100` — which called a melee at 700 of 1 600 an eight-ATTACK creep; it is a bare-MOVE one.)"""
    parts = expand(c['body'])
    n = len(parts)
    hits = c['hits']
    return sum(1 for i, p in enumerate(parts) if p == t and hits > 100 * (n - i - 1))


def armed(c):
    return c['role'] in ('melee', 'ranged') and c['hits'] > 100 * c['tail']


def stripped(c):
    """No weapon or heal part left — a melee, ranged or healer reduced to its MOVE tail (hits <= 100 * tail)."""
    return c['role'] != 'scout' and c['hits'] <= 100 * c['tail']


def centroid(cs):
    return (sum(c['x'] for c in cs) / len(cs), sum(c['y'] for c in cs) / len(cs)) if cs else None


def compactness(cs):
    """(mean, max) Chebyshev distance of the creeps to their centroid."""
    if not cs:
        return (0.0, 0)
    c = centroid(cs)
    ds = [rng((k['x'], k['y']), c) for k in cs]
    return (sum(ds) / len(ds), max(ds))


def largest_group(cs):
    seen, best = set(), 0
    for i in range(len(cs)):
        if i in seen:
            continue
        stack, n = [i], 0
        seen.add(i)
        while stack:
            j = stack.pop()
            n += 1
            for k2 in range(len(cs)):
                if k2 not in seen and rng((cs[j]['x'], cs[j]['y']), (cs[k2]['x'], cs[k2]['y'])) <= GROUP_RANGE:
                    seen.add(k2)
                    stack.append(k2)
        best = max(best, n)
    return best


def analyse_replay(doc, us):
    meta = doc['meta']
    W, H = meta.get('width', 100), meta.get('height', 100)
    edge = lambda p: int(min(p[0], p[1], W - 1 - p[0], H - 1 - p[1]))
    names = {p['side']: p['username'] for p in meta['players']}
    R = dict(width=W, height=H, us=us, names=names, ticks=meta['ticks'], ticks_limit=meta.get('ticksLimit'),
             winner=meta['result'].get('winnerName'), draw=meta['result'].get('draw'))
    rec = {}
    first_reach, first_fire, last_action = None, {0: None, 1: None}, None
    deaths = {0: [], 1: []}
    visits, last_visit = [], {}
    track = []
    prev_hc = None
    disp_ticks, both_ticks = 0, 0
    # fight window accumulators (filled on the second pass once the window is known) — first pass: geometry
    for k, start, now, acts, raw in rp.ticks(doc):
        ours = [c for c in now.values() if c['side'] == us]
        his = [c for c in now.values() if c['side'] != us]
        oa = [c for c in ours if armed(c)]
        ha = [c for c in his if armed(c)]
        contact = bool(oa and ha and any(rng((o['x'], o['y']), (e['x'], e['y'])) <= RANGED_RANGE for o in oa for e in ha))
        oc, hc = centroid(oa or ours), centroid(ha or his)
        rec[k] = dict(oc=oc, hc=hc, contact=contact, oedge=edge(oc) if oc else None, hedge=edge(hc) if hc else None,
                      ocomp=compactness(oa), hcomp=compactness(ha), on=len(oa), hn=len(ha), o_all=len(ours), h_all=len(his))
        if contact and first_reach is None:
            first_reach = k
        fired = {0: False, 1: False}
        for a in raw:
            c = start.get(a[0]) or now.get(a[0])
            if not c or a[1] not in ('a', 'r', 'R', 'h', 'H'):
                continue
            if a[1] in ('a', 'r', 'R'):
                fired[c['side']] = True
            last_action = k
        for s in (0, 1):
            if fired[s] and first_fire[s] is None:
                first_fire[s] = k
        for cid, c in start.items():
            if cid not in now:
                deaths[c['side']].append((k, c['role']))
        for c in his:
            f = FLAGS.get((c['x'], c['y']))
            if f:
                if last_visit.get(f, -99) < k - 30:
                    visits.append((k, f, c['role']))
                last_visit[f] = k
        if oa and ha:
            both_ticks += 1
            big = largest_group(ha)
            if big * 2 <= len(ha):
                disp_ticks += 1
            if k % STEP_TRACK == 0:
                moved = rng(hc, prev_hc) if prev_hc else 0.0
                track.append(dict(t=k, hc=hc, oc=oc, dist=rng(oc, hc), moved=moved, largest=big, armed=len(ha), contact=contact))
                prev_hc = hc
    R['first_reach'], R['first_fire'], R['last_action'] = first_reach, first_fire, last_action
    steps = []
    ks = sorted(rec)
    for a, b in zip(ks, ks[10:]):
        if rec[a]['contact'] and rec[b]['contact'] and rec[a]['hc'] and rec[b]['hc']:
            steps.append(rng(rec[a]['hc'], rec[b]['hc']))
    R['his_move_in_contact'] = sum(steps) / len(steps) if steps else None
    if first_reach is not None and first_reach in rec and 0 in rec and rec[0]['hc'] and rec[first_reach]['hc']:
        R['his_approach'] = rng(rec[0]['hc'], rec[first_reach]['hc']) * STEP_TRACK / max(1, first_reach)
    else:
        R['his_approach'] = None
    R['deaths'] = deaths
    R['visits'] = visits
    R['track'] = track
    R['disp_share'] = disp_ticks / both_ticks if both_ticks else 0.0
    R['contact_ticks'] = sum(1 for r in rec.values() if r['contact'])
    R['corner_ticks'] = sum(1 for r in rec.values() if r['contact'] and r['oedge'] is not None and r['oedge'] <= EDGE_CORNER)
    R['his_corner_ticks'] = sum(1 for r in rec.values() if r['contact'] and r['hedge'] is not None and r['hedge'] <= EDGE_CORNER)
    con = [r for r in rec.values() if r['contact']]
    R['our_edge_mean'] = sum(r['oedge'] for r in con) / len(con) if con else None
    R['his_edge_mean'] = sum(r['hedge'] for r in con) / len(con) if con else None
    R['our_alive_end'] = rec[max(rec)]['o_all'] if rec else None
    R['his_alive_end'] = rec[max(rec)]['h_all'] if rec else None
    R['rec'] = rec
    # the first contact's geometry
    t0 = first_reach if first_reach is not None else min((t for t in first_fire.values() if t is not None), default=None)
    R['contact_t'] = t0
    if t0 is not None:
        before = rec.get(max(0, t0 - 10)) or rec.get(min(rec))
        at = rec[t0]
        R['first'] = dict(t=t0, oc=at['oc'], hc=at['hc'], oedge=at['oedge'], hedge=at['hedge'], ocomp=at['ocomp'], hcomp=at['hcomp'],
                          our_moved=rng(at['oc'], before['oc']) if at['oc'] and before['oc'] else None,
                          his_moved=rng(at['hc'], before['hc']) if at['hc'] and before['hc'] else None,
                          dist=rng(at['oc'], at['hc']) if at['oc'] and at['hc'] else None)
    # second pass: the fight window
    t1 = last_action if last_action is not None else meta['ticks']
    win = (t0, t1) if t0 is not None else None
    R['window'] = win
    F = {s: dict(shots=0, mass=0, swings=0, heals=0, rheals=0, exp_dmg=0, exp_heal=0, obs_lost=0, obs_gain=0,
                 h_can=0, h_did=0, m_can=0, m_did=0, r_can=0, r_did=0, m_adj=0, r_in3=0, r_ticks=0, m_ticks=0,
                 shots_by_role=Counter(), r_dist=Counter(), first5=0, stripped_in_reach=0, stripped_ticks=0,
                 e_shots=0, e_swings=0, e_heals=0, e_lost=0, e_stripped=0, e_deaths=0, e_r_in3=0, o_r_in3=0, o_r_ticks=0) for s in (0, 1)}
    if win:
        for k, start, now, acts, raw in rp.ticks(doc):
            if k < win[0] or k > win[1]:
                continue
            codes = {}
            for a in raw:
                codes.setdefault(a[0], set()).add(a[1])
            prev_at = {(c['x'], c['y']): cid for cid, c in start.items()}
            at = {(c['x'], c['y']): cid for cid, c in now.items()}
            for a in raw:
                cid, code = a[0], a[1]
                c = start.get(cid) or now.get(cid)
                if not c:
                    continue
                A = F[c['side']]
                if code == 'r':
                    A['shots'] += 1
                    A['exp_dmg'] += 10 * live_parts(c, 'r')
                elif code == 'R':
                    A['mass'] += 1
                    A['exp_dmg'] += 10 * live_parts(c, 'r')
                elif code == 'a':
                    A['swings'] += 1
                    A['exp_dmg'] += 30 * live_parts(c, 'a')
                elif code == 'h':
                    A['heals'] += 1
                    A['exp_heal'] += 12 * live_parts(c, 'h')
                elif code == 'H':
                    A['rheals'] += 1
                    A['exp_heal'] += 4 * live_parts(c, 'h')
                if k <= win[0] + ENTRY_TICKS:
                    if code in ('a', 'r', 'R'):
                        A['e_shots' if code != 'a' else 'e_swings'] += 1
                    elif code in ('h', 'H'):
                        A['e_heals'] += 1
                if code in ('a', 'r'):
                    if k <= win[0] + 5:
                        A['first5'] += 1
                    tgt = None
                    if len(a) >= 4:
                        tid = prev_at.get((a[2], a[3])) or at.get((a[2], a[3]))
                        tgt = (start.get(tid) or now.get(tid)) if tid else None
                    A['shots_by_role'][tgt['role'] if tgt else '?'] += 1
                elif code == 'R' and k <= win[0] + 5:
                    A['first5'] += 1
            entry = k <= win[0] + ENTRY_TICKS
            for cid, c in now.items():
                if cid in start:
                    d = c['hits'] - start[cid]['hits']
                    if d < 0:
                        F[c['side']]['obs_lost'] += -d
                        if entry:
                            F[c['side']]['e_lost'] += -d
                    else:
                        F[c['side']]['obs_gain'] += d
                    if entry and c['role'] != 'scout' and stripped(c) and not stripped(start[cid]):
                        F[c['side']]['e_stripped'] += 1
            for cid, c in start.items():
                if cid not in now:
                    F[c['side']]['obs_lost'] += c['hits']
                    if entry:
                        F[c['side']]['e_lost'] += c['hits']
                        F[c['side']]['e_deaths'] += 1
            for cid, c in start.items():
                s = c['side']
                U = F[s]
                mates = [o for oid, o in start.items() if o['side'] == s and oid != cid]
                foes = [o for o in start.values() if o['side'] != s]
                if not foes:
                    continue
                d = min(rng((c['x'], c['y']), (o['x'], o['y'])) for o in foes)
                my = codes.get(cid, set())
                if c['role'] != 'scout' and stripped(c):
                    U['stripped_ticks'] += 1
                    if any(armed(o) and rng((c['x'], c['y']), (o['x'], o['y'])) <= RANGED_RANGE for o in foes):
                        U['stripped_in_reach'] += 1
                    continue
                if live_parts(c, 'h') > 0 and live_parts(c, 'a') == 0 and live_parts(c, 'r') == 0:
                    if any(o['hits'] < o['hitsMax'] and rng((c['x'], c['y']), (o['x'], o['y'])) <= 1 for o in mates):
                        U['h_can'] += 1
                        U['h_did'] += 1 if 'h' in my else 0
                elif live_parts(c, 'a') > 0:
                    U['m_ticks'] += 1
                    if d <= 1:
                        U['m_can'] += 1
                        U['m_adj'] += 1
                        U['m_did'] += 1 if 'a' in my else 0
                elif live_parts(c, 'r') > 0:
                    U['r_ticks'] += 1
                    U['r_dist'][min(d, 7)] += 1
                    if k <= win[0] + OPENING_TICKS:
                        U['o_r_ticks'] += 1
                        if d <= RANGED_RANGE:
                            U['o_r_in3'] += 1
                    if d <= RANGED_RANGE:
                        U['r_can'] += 1
                        U['r_in3'] += 1
                        U['r_did'] += 1 if my & {'r', 'R'} else 0
                        if k <= win[0] + ENTRY_TICKS:
                            U['e_r_in3'] += 1
    R['fight'] = F
    return R


def classify(R):
    """The opponent's form and the numbers it was judged on."""
    if not R:
        return None, ''
    pre = [t for t in R['track'] if R['contact_t'] is None or t['t'] <= R['contact_t']] or R['track']
    move = sum(t['moved'] for t in pre[1:]) / max(1, len(pre) - 1) if len(pre) > 1 else (R['his_approach'] or 0.0)
    mic = R['his_move_in_contact']
    flags_seen = sorted({f for _, f, _ in R['visits']})
    ct = R['contact_ticks']
    his = R['fight'][1 - R['us']]
    r_dist = his['r_dist']
    mode = max(r_dist, key=r_dist.get) if r_dist else None
    adj_share = his['m_adj'] / his['m_ticks'] if his['m_ticks'] else 0.0
    ev = (f"dispersion {R['disp_share'] * 100:.0f}% of both-armed ticks, his centroid moved {move:.1f} cells per {STEP_TRACK} before contact, "
          f"contact ticks {ct}, flags visited {','.join(flags_seen) or '-'}; in the fight his ranged stood at {mode if mode is not None else '-'} most, "
          f"his melee adjacent {adj_share * 100:.0f}% of their creep-ticks, his centroid moved {mic if mic is None else round(mic, 1)} cells per 10 in contact")
    if R['disp_share'] >= 0.5:
        return ('scatter' if move < 4 else 'farmer'), ev
    if ct < 20 or ct < 0.1 * R['ticks']:   # a tourer engages briefly and leaves (match 274: 105 contact ticks of 1 830)
        return ('sentinel' if move < 4 else 'touring'), ev
    if mode in (RANGED_RANGE, RANGED_RANGE + 1) and adj_share < 0.10 and ct >= 30 and (mic is None or mic <= LINE_MOVE):
        return 'line', ev
    return 'blob', ev


# ---------------------------------------------------------------- reading the log's shape

def outcome_form(L, R, meta_result, ticks):
    """(form, detail): annihilation / points at the limit / early on points, with the tick it was decided."""
    S = L['samples']
    ours_gone = next((s['t'] for s in S if s['army'] == 0 and s['hits'][1] == 0), None)
    his_gone = next((s['t'] for s in S if s['enemies'] == 0), None)
    if R:
        od = R['deaths'][R['us']]
        hd = R['deaths'][1 - R['us']]
        if R['our_alive_end'] == 0 and od:
            ours_gone = od[-1][0]
        if R['his_alive_end'] == 0 and hd:
            his_gone = hd[-1][0]
    if meta_result == 'lost' and ours_gone is not None:
        return 'annihilated', f"our last creep died at t={ours_gone}"
    if meta_result == 'won' and his_gone is not None:
        return 'annihilation', f"his last creep died at t={his_gone}"
    if ticks and ticks >= 2000:
        return 'points at the limit', f"t={ticks}"
    # an early end is either an unreachable lead (|score gap| > 25 per remaining tick) or an annihilation the ten-tick
    # samples missed — the last creep died between two t= lines; the arena has no third way to end early
    last = S[-1] if S else None
    if last:
        gap = abs(last['score'][0] - last['score'][1])
        end = max(ticks or 0, last['t'])   # the lead is judged at the END, not at the last ten-tick sample (match 274: reachable at 1810, unreachable at 1830)
        if gap <= 25 * (2000 - end):
            if meta_result == 'won':
                return 'annihilation', f"his army gone after t={last['t']} (lead {gap} was reachable, the end came between samples)"
            if meta_result == 'lost':
                return 'annihilated', f"our army gone after t={last['t']} (his lead {gap} was reachable, the end came between samples)"
        return 'early on points', f"ended after t={last['t']} with a lead of {gap} — unreachable"
    return 'early', f"ended at t={ticks}, no t= samples to say how"


def divergence(L):
    S = L['samples']
    sc = next((s for s in S if abs(s['score'][0] - s['score'][1]) >= SCORE_GAP), None)
    lg = next((s for s in S if abs(s['ledger']) >= LEDGER_GAP), None)
    out = {}
    if sc:
        out['score'] = dict(t=sc['t'], who='ours' if sc['score'][0] > sc['score'][1] else 'his', score=sc['score'])
    if lg:
        out['ledger'] = dict(t=lg['t'], who='ours' if lg['ledger'] > 0 else 'his', ledger=lg['ledger'])
    if S:
        last = S[-1]
        out['final'] = dict(t=last['t'], score=last['score'], rate=last['rate'], hits=last['hits'], ehits=last['ehits'],
                            flags=(last['flags_ours'], last['flags_theirs']))
    return out


def transitions(L):
    out = []
    for p in L['postures']:
        if not out or out[-1]['posture'] != p['posture']:
            out.append(p)
    return out


def flicker(trans):
    """The 100-tick window with the most posture transitions: (count, t_from)."""
    ts = [p['t'] for p in trans if p['t'] >= FLICKER_FROM]
    best = (0, 0)
    for i, t in enumerate(ts):
        n = sum(1 for u in ts[i:] if u < t + 100)
        if n > best[0]:
            best = (n, t)
    return best


def quiet_run(L):
    """The longest run of samples without a target in reach while behind on score or on rate: (ticks, t0, t1, postures, his_moved)."""
    best, cur = None, []
    for s in L['samples'] + [None]:
        if s is not None and s['reach'] == 0 and (s['behind'] or s['rate'][1] > s['rate'][0]) and s['army'] > 0:
            cur.append(s)
            continue
        if len(cur) >= 2 and (best is None or len(cur) > len(best)):
            best = cur
        cur = []
    if not best:
        return None
    ticks = best[-1]['t'] - best[0]['t'] + 10
    posts = Counter(s['posture'] for s in best)
    ec = [s['ecen'] for s in best if s['ecen']]
    moved = max((rng(a, ec[0]) for a in ec), default=0) if ec else 0
    return dict(ticks=ticks, t0=best[0]['t'], t1=best[-1]['t'], postures=dict(posts.most_common(3)), his_moved=moved)


def runner_stands(L):
    out = []
    for rid, rows in L['runners'].items():
        modes = Counter(r['mode'] for r in rows)
        best = None
        i = 0
        while i < len(rows):
            j = i
            while j + 1 < len(rows) and rows[j + 1]['cell'] == rows[i]['cell'] and rows[j + 1]['t'] - rows[j]['t'] <= 20:
                j += 1
            span = rows[j]['t'] - rows[i]['t'] + 10
            if best is None or span > best['ticks']:
                best = dict(ticks=span, t0=rows[i]['t'], t1=rows[j]['t'], cell=rows[i]['cell'], mode=Counter(r['mode'] for r in rows[i:j + 1]).most_common(1)[0][0],
                            flag=rows[j]['flag'])
            i = j + 1
        out.append(dict(id=rid, samples=len(rows), modes=dict(modes.most_common(4)), stand=best))
    return out


def posture_at(L, t):
    """The posture in force at tick t (the last posture line at or before it)."""
    cur = None
    for p in L['postures']:
        if p['t'] <= t:
            cur = p['posture']
        else:
            break
    return cur


def edge_of(cell, W=100, H=100):
    return min(cell[0], cell[1], W - 1 - cell[0], H - 1 - cell[1])


# ---------------------------------------------------------------- diagnosis

def diagnose(L, R, info):
    D = []
    S = L['samples']
    us = R['us'] if R else None
    if L['errors']:
        D.append(('errors', f"the log has {dict(L['errors'])} — read those lines before anything else"))
    if info['form'] == 'annihilated':
        lead = next((s for s in reversed(S) if s['army'] > 0), None)
        if lead and lead['score'][0] > lead['score'][1]:
            D.append(('annihilated ahead', f"annihilated while ahead {lead['score'][0]}:{lead['score'][1]} — the parity doctrine's case: the army was spent on flags"))
    if R and R['contact_ticks']:
        share = R['corner_ticks'] / R['contact_ticks']
        if share >= CORNER_SHARE:
            D.append(('corner fight', f"{share * 100:.0f}% of {R['contact_ticks']} contact ticks with our centroid within {EDGE_CORNER} of the edge "
                                      f"(mean {R['our_edge_mean']:.1f}, his {R['his_edge_mean']:.1f}) — the fight was where he chose it, with our back to the wall"))
    trans = transitions(L)
    n, t = flicker(trans)
    if n >= FLICKER_PER_100:
        D.append(('posture flicker', f"{n} posture transitions in the 100 ticks from t={t} (threshold {FLICKER_PER_100}): "
                                     f"{' '.join(f'{p[chr(116)]}:{p[chr(112) + chr(111) + chr(115) + chr(116) + chr(117) + chr(114) + chr(101)]}' for p in trans if t <= p[chr(116)] < t + 100)} — a hysteresis is missing somewhere"))
    q = quiet_run(L)
    if q and q['ticks'] >= QUIET_TICKS:
        nets = [x for x in L['stalls'] if q['t0'] <= x['t'] <= q['t1']] + [x for x in L['detaches'] if q['t0'] <= x['t'] <= q['t1']]
        what = 'he TOURED' if q['his_moved'] >= 20 else 'he STOOD'
        D.append(('quiet behind', f"{q['ticks']} ticks (t={q['t0']}..{q['t1']}) without a target in reach while behind, postures {q['postures']}, "
                                  f"his centroid moved {q['his_moved']} cells ({what}); stall/detach lines in that span: {len(nets)}"))
    if L['giveups']:
        span = L['giveups'][-1]['t'] - L['giveups'][0]['t'] + 10
        ct = max(R['contact_ticks'] if R else 0, span)
        per100 = 100 * len(L['giveups']) / max(1, ct)
        if per100 >= GIVEUP_PER_100:
            D.append(('give-up storm', f"{len(L['giveups'])} press give-ups over {ct} contact ticks ({per100:.0f} per 100, threshold {GIVEUP_PER_100}) — the melee stopped chasing the same creeps again and again"))
    if L['why_creep_ticks'] >= MELEE_IDLE:
        top = ', '.join(f"{k} {v}" for k, v in L['why_reasons'].most_common(4))
        D.append(('melee idle', f"{L['why_creep_ticks']} melee creep-ticks with an enemy within engage range and no target (threshold {MELEE_IDLE}) — "
                                f"the filters that removed the throw: {top}; what they did instead {dict(L['why_did'].most_common(3))}"))
    for r in runner_stands(L):
        st = r['stand']
        if st and st['ticks'] >= RUNNER_STAND and 'my=true' not in st['flag']:
            D.append(('runner stood', f"runner {r['id']} stood {st['ticks']} ticks at {st['cell']} in {st['mode']} (t={st['t0']}..{st['t1']}), flag {st['flag']} — not ours the whole time"))
    if R and R['window']:
        F = R['fight']
        o, h = F[us], F[1 - us]
        for role, can, did in (('healers', 'h_can', 'h_did'), ('melee', 'm_can', 'm_did'), ('ranged', 'r_can', 'r_did')):
            if o[can] >= 20 and h[can] >= 20:
                ou, hu = o[did] / o[can], h[did] / h[can]
                if ou < UPTIME_LOW and hu >= UPTIME_HIGH:
                    D.append(('uptime', f"our {role} uptime {ou * 100:.0f}% against his {hu * 100:.0f}% ({pct(o[did], o[can])} vs {pct(h[did], h[can])}) — they had the chance and did not act"))
        if o['stripped_in_reach'] >= STRIPPED_MIN and o['stripped_in_reach'] >= STRIPPED_X * max(1, h['stripped_in_reach']):
            D.append(('stripped in reach', f"our creeps with no weapon or heal part left stood within 3 of his armed {o['stripped_in_reach']} creep-ticks "
                                           f"(of {o['stripped_ticks']} stripped), his {h['stripped_in_reach']} of {h['stripped_ticks']} — he pulls the stripped out, we leave them in the fire"))
        if o['e_lost'] >= 1000 and o['e_lost'] >= ENTRY_X * max(1, h['e_lost']):
            D.append(('entry lost', f"in the first {ENTRY_TICKS} ticks of contact we lost {o['e_lost']} hits to his {h['e_lost']} (shots {o['e_shots']}:{h['e_shots']}, "
                                    f"armed ranged in 3 {o['e_r_in3']}:{h['e_r_in3']}, stripped {o['e_stripped']}:{h['e_stripped']}) — the entry was his"))
        if o['o_r_ticks'] >= REACH_MIN and h['o_r_ticks'] >= REACH_MIN:
            orr, hrr = o['o_r_in3'] / o['o_r_ticks'], h['o_r_in3'] / h['o_r_ticks']
            if hrr >= orr + REACH_GAP:
                D.append(('reach', f"in the first {OPENING_TICKS} ticks of contact his ranged had a target within 3 in {hrr * 100:.0f}% of their creep-ticks against our "
                                   f"{orr * 100:.0f}% ({h['o_r_in3']}/{h['o_r_ticks']} vs {o['o_r_in3']}/{o['o_r_ticks']}) — his line reached ours and ours did not reach his"))
        if h['m_adj'] >= 20 and h['m_adj'] >= ADJACENCY_X * max(1, o['m_adj']):
            D.append(('melee adjacency', f"his melee were adjacent {h['m_adj']} creep-ticks against our {o['m_adj']} ({h['swings']} vs {o['swings']} swings) — his melee found targets, ours held a line nobody attacked"))
        if info.get('posture_at_contact') == 'EVADE':
            D.append(('caught evading', f"the first contact (t={R['contact_t']}) came while the army was in EVADE — running from an equal-speed opponent, backs to him "
                                        f"(first five ticks' shots ours {o['first5']} his {h['first5']})"))
        if h['first5'] >= 6 and h['first5'] >= VOLLEY_X * max(1, o['first5']):
            D.append(('first volleys', f"in the first five ticks of contact he fired {h['first5']} against our {o['first5']} — the entry was his"))
        fi = R.get('first')
        if fi and fi['our_moved'] is not None and fi['our_moved'] >= MARCH_CELLS and fi['his_moved'] is not None and fi['his_moved'] <= FORMED_CELLS:
            D.append(('marched into a formed line', f"in the ten ticks before contact our centroid moved {fi['our_moved']:.0f} cells and his {fi['his_moved']:.0f} — we walked into his formation"))
        net_o = o['exp_dmg'] - h['exp_heal']
        net_h = h['exp_dmg'] - o['exp_heal']
        if net_h > 0 and net_o > 0 and net_h >= 1.3 * net_o:
            D.append(('outdamaged', f"expected net damage on us {net_h} against {net_o} on him over the window t={R['window'][0]}..{R['window'][1]} — "
                                    f"shots {h['shots']}:{o['shots']}, swings {h['swings']}:{o['swings']}, heals {h['heals']}:{o['heals']}"))
        if info.get('power_ratio') is not None and info['power_ratio'] < POWER_LOW:
            D.append(('went in short', f"the bot's own power ratio at contact was {info['power_ratio']:.2f} (our {info['power'][0]} vs his {info['power'][1]}, threshold {POWER_LOW})"))
        od = [d for d in R['deaths'][us] if d[1] != 'scout']
        hd = [d for d in R['deaths'][1 - us] if d[1] != 'scout']
        if od and (not hd or od[0][0] < hd[0][0]):
            D.append(('first blood his', f"our first death at t={od[0][0]} ({od[0][1]}), his first at {hd[0][0] if hd else '-'}"))
    if not D:
        D.append(('nothing fired', 'no rule crossed its threshold — read the timeline and the fight numbers by hand'))
    return D


# ---------------------------------------------------------------- report

def render(L, R, info, history, step):
    out = []
    p = out.append
    p(f"# {info['id']}  {info.get('when', '')}  {info['result'].upper()}  vs {info['opponent']}  rating {info.get('rating', '?')}  "
      f"ticks {info.get('ticks', '?')}  ours {L['version'] or '?'}  {'replay' if R else 'NO REPLAY (log only)'}")
    if L['tuning']:
        p(f"tuning: {L['tuning']}")
    p(f"outcome: {info['form']} — {info['form_detail']}")
    dv = info['divergence']
    if 'final' in dv:
        f = dv['final']
        p(f"final (t={f['t']}): score {f['score'][0]}:{f['score'][1]} at {f['rate'][0]}:{f['rate'][1]}/t, hits {f['hits'][0]}/{f['hits'][1]} vs {f['ehits'][0]}/{f['ehits'][1]}, flags ours {f['flags'][0]} his {f['flags'][1]}")
    p(f"diverged: score {('t=%d %s %s' % (dv['score']['t'], dv['score']['who'], dv['score']['score'])) if 'score' in dv else 'never'}; "
      f"ledger {('t=%d %s %+d' % (dv['ledger']['t'], dv['ledger']['who'], dv['ledger']['ledger'])) if 'ledger' in dv else 'never'}")
    if R:
        form, ev = classify(R)
        p(f"his form: {form.upper()} — {ev}")
        if R['visits']:
            p("his flag visits: " + ' '.join(f"{t}:{f}:{r[0]}" for t, f, r in R['visits'][:40]))
    p('')
    p('timeline (t posture army/combat hits:ehits score flags ours/his dist our-edge his-edge):')
    rec = R['rec'] if R else {}
    for s in L['samples']:
        if s['t'] % step and s is not L['samples'][-1]:
            continue
        r = rec.get(s['t'])
        d = f"{rng(s['cen'], s['ecen']):3d}" if s['cen'] and s['ecen'] else '  -'
        oe = f"{r['oedge']:2d}" if r and r['oedge'] is not None else (f"{edge_of(s['cen']):2d}" if s['cen'] else ' -')
        he = f"{r['hedge']:2d}" if r and r['hedge'] is not None else (f"{edge_of(s['ecen']):2d}" if s['ecen'] else ' -')
        p(f"  t={s['t']:4d} {s['posture']:10s} {s['army']:2d}/{s['combat']:2d} {s['hits'][0]:5d}:{s['ehits'][0]:5d} {s['score'][0]:5d}:{s['score'][1]:5d} "
          f"{s['flags_ours']}/{s['flags_theirs']} d={d} e={oe}/{he}{' contact' if r and r['contact'] else ''}{' det=' + str(s['detached']) if s['detached'] else ''}")
    trans = transitions(L)
    p('')
    p(f"postures ({len(trans)} transitions): " + ' '.join(f"{t['t']}:{t['posture']}{'*' if t['contact'] else ''}" for t in trans[:60]) + (' …' if len(trans) > 60 else ''))
    n, t = flicker(trans)
    p(f"  flicker: {n} transitions in the 100 ticks from t={t} (opening before t={FLICKER_FROM} not counted); * = in contact")
    if R and R.get('first'):
        fi = R['first']
        us = R['us']
        F = R['fight']
        p('')
        p(f"first contact t={fi['t']} in {info.get('posture_at_contact') or '?'} (first fire ours t={R['first_fire'][us]} his t={R['first_fire'][1 - us]}): "
          f"our centroid ({fi['oc'][0]:.0f},{fi['oc'][1]:.0f}) edge {fi['oedge']}, his ({fi['hc'][0]:.0f},{fi['hc'][1]:.0f}) edge {fi['hedge']}, dist {fi['dist']:.0f}")
        p(f"  ten ticks before: our centroid moved {fi['our_moved']:.0f}, his {fi['his_moved']:.0f}; compactness (mean/max to centroid) ours {fi['ocomp'][0]:.1f}/{fi['ocomp'][1]:.0f} his {fi['hcomp'][0]:.1f}/{fi['hcomp'][1]:.0f}")
        p(f"  first five ticks: shots ours {F[us]['first5']} his {F[1 - us]['first5']}; the bot's power at contact ours {info['power'][0]} his {info['power'][1]} "
          f"(ratio {info['power_ratio']:.2f})" if info.get('power_ratio') is not None else f"  first five ticks: shots ours {F[us]['first5']} his {F[1 - us]['first5']}")
    if R and R['window']:
        us = R['us']
        F = R['fight']
        p('')
        p(f"fight t={R['window'][0]}..{R['window'][1]} ({R['contact_ticks']} contact ticks, {R['corner_ticks']} with our centroid within {EDGE_CORNER} of the edge, his {R['his_corner_ticks']}):")
        for s, tag in ((us, 'OURS'), (1 - us, 'HIS '), ):
            A = F[s]
            o = F[1 - s]
            p(f"  {tag} shots={A['shots']} mass={A['mass']} swings={A['swings']} heals={A['heals']}+{A['rheals']}r  expected dmg={A['exp_dmg']} heal={A['exp_heal']} "
              f"net on the other={A['exp_dmg'] - o['exp_heal']}  observed on the other: lost={o['obs_lost']} gained={o['obs_gain']}")
            p(f"       uptime healers {pct(A['h_did'], A['h_can'])} melee {pct(A['m_did'], A['m_can'])} ranged {pct(A['r_did'], A['r_can'])}; "
              f"melee adjacent {A['m_adj']} of {A['m_ticks']} creep-ticks; ranged in 3: {pct(A['r_in3'], A['r_ticks'])}, opening {pct(A['o_r_in3'], A['o_r_ticks'])}; "
              f"shots by target {dict(A['shots_by_role'].most_common(4))}; ranged dist hist {' '.join(f'{d}:{n}' for d, n in sorted(A['r_dist'].items()))}")
        o, h = F[us], F[1 - us]
        p(f"  entry (first {ENTRY_TICKS} ticks from t={R['window'][0]}): hits lost ours {o['e_lost']} his {h['e_lost']}; shots ours {o['e_shots']} his {h['e_shots']}; "
          f"swings {o['e_swings']}:{h['e_swings']}; heals {o['e_heals']}:{h['e_heals']}; armed ranged creep-ticks in 3: ours {o['e_r_in3']} his {h['e_r_in3']}; "
          f"stripped ours {o['e_stripped']} his {h['e_stripped']}; deaths ours {o['e_deaths']} his {h['e_deaths']}")
        p(f"  stripped creeps (no weapon or heal part) still within 3 of an armed enemy: ours {o['stripped_in_reach']} of {o['stripped_ticks']} stripped creep-ticks, "
          f"his {h['stripped_in_reach']} of {h['stripped_ticks']}")
        od, hd = R['deaths'][us], R['deaths'][1 - us]
        p(f"  deaths ours ({len(od)}): {' '.join(f'{t}:{r[0]}' for t, r in od[:16])}")
        p(f"  deaths his  ({len(hd)}): {' '.join(f'{t}:{r[0]}' for t, r in hd[:16])}")
    p('')
    p(f"nets: stalls {len(L['stalls'])} detach {len(L['detaches'])} keepers {len(L['keepers'])} plan-yields {len(L['plans'])} press-in {len(L['press_in'])} "
      f"give-ups {len(L['giveups'])} evades {len(L['evades'])} flag-events {len(L['flags'])} ghost-damage {len(L['ghosts'])} stuck {L['stuck']}")
    for x in L['stalls'][:6]:
        p(f"  stall t={x['t']}: {x['text'][:110]}")
    for x in L['detaches'][:6]:
        p(f"  detach t={x['t']}: {x['text'][:110]}")
    if L['evades']:
        p("  evades: " + ' '.join(f"{e['t']}:{e['kind'][0]}({e['cell'][0]},{e['cell'][1]})e{edge_of(e['cell'])}" for e in L['evades'][:20]))
    if L['flags']:
        p("  flags: " + ' | '.join(f"{x['t']}: {x['text'][:60]}" for x in L['flags'][:10]))
    if L['giveups']:
        by = Counter(g['id'] for g in L['giveups'])
        p(f"  give-ups by creep: {dict(by.most_common(6))}; first at t={L['giveups'][0]['t']}, last t={L['giveups'][-1]['t']}")
    q = quiet_run(L)
    if q:
        p(f"  longest quiet run behind: {q['ticks']} ticks t={q['t0']}..{q['t1']} postures {q['postures']} his centroid moved {q['his_moved']}")
    if L['why_creep_ticks']:
        p(f"  melee idle with an enemy within engage range (why trace): {L['why_creep_ticks']} creep-ticks over {L['why_ticks']} ticks (t={L['why_first']}..{L['why_last']}); "
          f"filters {dict(L['why_reasons'].most_common(8))}; did {dict(L['why_did'].most_common(5))}; by creep {dict(L['why_by'].most_common(4))}")
    rs = runner_stands(L)
    if rs:
        p('runners: ' + '; '.join(f"{r['id']} modes {r['modes']} longest stand {r['stand']['ticks']}t at {r['stand']['cell']} {r['stand']['mode']} {r['stand']['flag']}" for r in rs if r['stand']))
    p('')
    p('DIAGNOSIS:')
    for tag, text in info['diagnosis']:
        p(f"  [{tag}] {text}")
    if history:
        p('')
        p(f"history vs {info['opponent']} (store, the opponent's code version after #): " + ', '.join(f"{h['when']} {h['result'][0].upper()}{h['ticks']}#{h['code']}{'*' if h['game'] == info['id'] else ''}" for h in history))
        w = sum(1 for h in history if h['result'] == 'won')
        byv = Counter((h['code'], h['result']) for h in history)
        vers = sorted({h['code'] for h in history}, key=lambda v: (v is None, v))
        p(f"  {w}:{len(history) - w} over {len(history)} stored matches; by his version: " + ', '.join(f"#{v} {byv[(v, 'won')]}:{byv[(v, 'lost')]}" for v in vers))
    return '\n'.join(out)


# ---------------------------------------------------------------- sources

def find_replay(gid, args):
    if args.replay:
        return args.replay
    dirs = [args.replays] if args.replays else []
    dirs += [os.environ.get('ARENA_REPLAYS', ''), os.path.expanduser('~/ScreepsArena/replays')]
    for d in dirs:
        if not d:
            continue
        hits = sorted(glob.glob(os.path.join(d, f"{gid}*.replay.json.gz")))
        if hits:
            return hits[0]
    if args.fetch and len(gid) == 24:
        tools = args.tools or os.environ.get('ARENA_TOOLS', '') or os.path.expanduser('~/ScreepsArena/screeps-arena-tools')
        cli = os.path.join(tools, 'dist', 'src', 'cli.js')
        node = sorted(glob.glob(os.path.expanduser('~/.gradle/nodejs/*/bin/node')))
        if not os.path.isfile(cli) or not node:
            print(f"# no replay tool at {cli} (or no node under ~/.gradle/nodejs); see replay.py for the setup", file=sys.stderr)
            return None
        out_dir = dirs[0] if dirs and dirs[0] else os.path.expanduser('~/ScreepsArena/replays')
        os.makedirs(out_dir, exist_ok=True)
        out = os.path.join(out_dir, f"{gid}.replay.json.gz")
        r = subprocess.run([node[-1], cli, 'fetch', gid, '-o', out], capture_output=True, text=True)
        if r.returncode == 0 and os.path.isfile(out):
            return out
        print(f"# fetch failed: {(r.stdout + r.stderr).strip()[-300:]}", file=sys.stderr)
    return None


def resolve(args):
    """(id, log text, replay doc or None, info from the store's document, history rows)."""
    arg = args.match
    log_text, gid, doc, info, history = None, None, None, {}, []
    logs, metas = None, None
    if os.path.isfile(arg):
        log_text = open(arg, encoding='utf-8', errors='replace').read()
        m = re.match(r'# ([0-9a-f]{24}) ', log_text)
        if m:
            gid = m.group(1)
        else:
            m = re.search(r'-([0-9a-f]{6})\.txt$', arg)
            if m:
                suffix = m.group(1)
                hits = glob.glob(os.path.expanduser(f'~/ScreepsArena/replays/*{suffix}.replay.json.gz')) + \
                    (glob.glob(os.path.join(args.replays, f'*{suffix}.replay.json.gz')) if args.replays else [])
                if hits:
                    gid = os.path.basename(hits[0]).split('.')[0]
                else:
                    logs, metas = ml.scan()
                    cands = [g for g in logs if g.endswith(suffix)]
                    if len(cands) == 1:
                        gid = cands[0]
    else:
        gid = arg
    if gid and len(gid) < 24:
        logs, metas = ml.scan() if logs is None else (logs, metas)
        cands = [g for g in logs if g.startswith(gid)] + [os.path.basename(h).split('.')[0] for h in glob.glob(os.path.expanduser(f'~/ScreepsArena/replays/{gid}*.replay.json.gz'))]
        cands = sorted(set(cands))
        if len(cands) != 1:
            sys.exit(f"{'no' if not cands else len(cands)} matches for id prefix {gid!r}: {cands[:5]}")
        gid = cands[0]
    if gid:
        path = find_replay(gid, args)
        if path:
            doc = json.load(gzip.open(path, 'rt', encoding='utf-8'))
            info['replay_path'] = path
    # the log: the store first — the replay's `logs` are INCOMPLETE (match 275: 175 ticks with text, six posture
    # lines against the store's full console; read against it the autopsy missed `32:HOLD 42:ANNIHILATE` and called the
    # contact an evade) — the replay's copy only when the store has no log for the match
    if log_text is None and gid:
        logs, metas = ml.scan() if logs is None else (logs, metas)
        if gid in logs:
            log_text = ml.full_log(gid, logs)
    if log_text is None and doc and doc.get('logs'):
        log_text = ''.join(doc['logs'][k] for k in sorted(doc['logs'], key=int))
        info['log_source'] = 'replay (incomplete)'
    if log_text is None:
        sys.exit(f"no log for {arg}: not a file, not in the match store (tools/match-log.py fetch), no replay with logs")
    info['id'] = gid or os.path.basename(arg)
    if not args.no_history and gid:
        logs, metas = ml.scan() if logs is None else (logs, metas)
        d = ml.describe(gid, logs, metas) if gid in logs else None
        if d:
            info['when'] = time.strftime('%d.%m %H:%M', time.localtime(d['when']))
            info['result'] = d['result'] or '?'
            info['rating'] = d['rating']
            info['ticks'] = d['last']
            foes = [u for u in d['users'] if u and not u.startswith(args.us)]
            info['opponent'] = d.get('opponent') or ', '.join(foes) or '?'
            info['opp_code'] = d.get('opp_code')
            for g in logs:
                dd = ml.describe(g, logs, metas)
                if ('pain-and-gain' in dd['arena'] or not dd['arena']) and any(u in dd['users'] for u in foes) and dd['result'] in ('won', 'lost'):
                    history.append(dict(game=g, when=time.strftime('%d.%m %H:%M', time.localtime(dd['when'])), result=dd['result'], ticks=dd['last'], rating=dd['rating'], code=dd.get('opp_code')))
            history.sort(key=lambda h: h['when'])
            history = history[-12:]
    if doc:
        meta = doc['meta']
        us = rp.our_side(meta, args.us)
        info.setdefault('opponent', next(p['username'] for p in meta['players'] if p['side'] != us))
        info.setdefault('ticks', meta['ticks'])
        try:
            import datetime
            created = datetime.datetime.fromisoformat(meta.get('createdAt', '').replace('Z', '+00:00')).astimezone()
            info.setdefault('when', created.strftime('%d.%m %H:%M'))
        except ValueError:
            info.setdefault('when', meta.get('createdAt', '')[:16].replace('T', ' '))
        w = meta['result'].get('winner')
        info.setdefault('result', 'draw' if meta['result'].get('draw') else ('won' if w == us else 'lost'))
    info.setdefault('result', '?')
    info.setdefault('opponent', '?')
    return gid, log_text, doc, info, history


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('match', help='match id, a unique prefix of it, or a log file (play.py --logs / match-log.py dump)')
    ap.add_argument('--replay', help='the replay file; default <replays>/<id>.replay.json.gz')
    ap.add_argument('--replays', help='directory of replays (default ~/ScreepsArena/replays or $ARENA_REPLAYS)')
    ap.add_argument('--fetch', action='store_true', help='fetch a missing replay through the running client (arukuka tools under --tools)')
    ap.add_argument('--tools', help='screeps-arena-tools checkout (default ~/ScreepsArena/screeps-arena-tools or $ARENA_TOOLS)')
    ap.add_argument('--us', default='temik911', help='username prefix of our side')
    ap.add_argument('--step', type=int, default=100, help='timeline step in ticks')
    ap.add_argument('--json', action='store_true', help='print the measurement as JSON instead of the report')
    ap.add_argument('--no-history', action='store_true', help='skip the store scan for the history against this opponent')
    args = ap.parse_args()
    gid, text, doc, info, history = resolve(args)
    L = parse_log(text)
    R = analyse_replay(doc, rp.our_side(doc['meta'], args.us)) if doc else None
    info['form'], info['form_detail'] = outcome_form(L, R, info['result'], info.get('ticks'))
    info['divergence'] = divergence(L)
    if R and R.get('contact_t') is not None:
        before = [s for s in L['samples'] if s['t'] <= R['contact_t'] + 5]
        s = before[-1] if before else None
        if s and s['enemy'] > 0:
            info['power'] = (s['our'], s['enemy'])
            info['power_ratio'] = s['our'] / s['enemy']
    info['posture_at_contact'] = posture_at(L, R['contact_t']) if R and R.get('contact_t') is not None else None
    info['diagnosis'] = diagnose(L, R, info)
    if R:
        info['his_form'], info['his_form_evidence'] = classify(R)
    if args.json:
        J = dict(info={k: v for k, v in info.items() if k != 'diagnosis'}, diagnosis=info['diagnosis'], version=L['version'], tuning=L['tuning'],
                 samples=len(L['samples']), postures=len(transitions(L)), flicker=flicker(transitions(L)), quiet=quiet_run(L),
                 nets=dict(stalls=len(L['stalls']), detach=len(L['detaches']), keepers=len(L['keepers']), plans=len(L['plans']), press_in=len(L['press_in']),
                           giveups=len(L['giveups']), evades=len(L['evades']), flags=len(L['flags']), ghosts=len(L['ghosts'])),
                 evades=[dict(t=e['t'], kind=e['kind'], cell=e['cell'], edge=edge_of(e['cell'])) for e in L['evades']],
                 runners=runner_stands(L), errors=dict(L['errors']), history=history,
                 why=dict(ticks=L['why_ticks'], creep_ticks=L['why_creep_ticks'], reasons=dict(L['why_reasons']), did=dict(L['why_did'])))
        if R:
            J['replay'] = {k: v for k, v in R.items() if k not in ('rec', 'track', 'names')}
            J['replay']['names'] = R['names']
            J['replay']['fight'] = {s: {k: (dict(v) if isinstance(v, Counter) else v) for k, v in F.items()} for s, F in R['fight'].items()}
        print(json.dumps(J, ensure_ascii=False, default=str))
        return
    print(render(L, R, info, history, args.step))


if __name__ == '__main__':
    main()
