#!/usr/bin/env python3
"""The ledger: every Pain and Gain match in the match store (tools/match-log.py, fed from the API), autopsied and added up — priorities from numbers,
not from memory.

    tools/ledger.py [--last N] [--since DD.MM] [--opponent NAME] [--version vNN] [--rows] [--csv f.csv] [--json f.json]

For each stored match (tools/match-log.py fetches them from the server through the client) the ledger runs tools/autopsy.py's
measurement on the console log and, when ~/ScreepsArena/replays/<id>.replay.json.gz exists, on the replay, and keeps
one row: when, id, our version, opponent, result, ticks, rating delta, the outcome's form, the opponent's form (replay
only), the diagnosis tags, and a few numbers (posture flicker, quiet ticks behind, give-ups, contact tick, corner
share, first volleys). Then it prints:

  - by opponent: matches, W-L, rating won and lost, mean ticks, the forms seen, and which diagnosis rules fire in the
    losses to him — the "who takes our rating and how" table;
  - by our version: W-L and rating delta per version, in order — the series history without the docs;
  - by diagnosis rule: how often it fires in losses against wins — a rule that fires as often in wins is a hint, not a
    cause; one that fires only in losses is where the next version goes;
  - by outcome form (annihilated, annihilation, points, early): W-L;
  - the rating leaks: opponents by rating lost, largest first.

--rows prints the per-match rows too; --csv / --json write them for anything else. Old matches have older log formats
(fewer fields, no `why` trace); the parser takes what is there and the rows say what they could not measure.
"""
import argparse, csv, glob, gzip, json, os, sys, time, importlib.util
from collections import Counter, defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))


def _load(name, fname):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, fname))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


ap = _load('autopsy', 'autopsy.py')
ml, rp = ap.ml, ap.rp


def rating_delta(s):
    try:
        a, b = s.split('->')
        return int(b) - int(a)
    except (ValueError, AttributeError):
        return None


def measure(g, logs, metas, args):
    d = ml.describe(g, logs, metas)
    text = ml.full_log(g, logs)
    L = ap.parse_log(text)
    foes = [u for u in d['users'] if u and not u.startswith(args.us)]
    info = dict(id=g, result=d['result'], opponent=', '.join(foes) or '?', ticks=d['last'], rating=d['rating'],
                when=time.strftime('%d.%m %H:%M', time.localtime(d['when'])), opp_code=d.get('opp_code'))
    R = None
    hits = sorted(glob.glob(os.path.join(args.replays, f"{g}*.replay.json.gz")))
    if hits:
        try:
            doc = json.load(gzip.open(hits[0], 'rt', encoding='utf-8'))
            R = ap.analyse_replay(doc, rp.our_side(doc['meta'], args.us))
        except Exception as e:  # a half-written replay must not stop the ledger
            print(f"# {g}: replay unreadable ({e})", file=sys.stderr)
    info['form'], info['form_detail'] = ap.outcome_form(L, R, info['result'], info.get('ticks'))
    info['divergence'] = ap.divergence(L)
    if R and R.get('contact_t') is not None:
        before = [s for s in L['samples'] if s['t'] <= R['contact_t'] + 5]
        s = before[-1] if before else None
        if s and s['enemy'] > 0:
            info['power'] = (s['our'], s['enemy'])
            info['power_ratio'] = s['our'] / s['enemy']
    info['posture_at_contact'] = ap.posture_at(L, R['contact_t']) if R and R.get('contact_t') is not None else None
    diag = ap.diagnose(L, R, info)
    tags = [t for t, _ in diag if t != 'nothing fired']
    trans = ap.transitions(L)
    fl = ap.flicker(trans)
    q = ap.quiet_run(L)
    fin = info['divergence'].get('final')
    row = dict(when=info['when'], id=g, version=L['version'] or '?', opponent=info['opponent'], opp_code=info.get('opp_code'), result=info['result'], ticks=info['ticks'],
               delta=rating_delta(info['rating']), form=info['form'], his_form=(ap.classify(R)[0] if R else '-'),
               tags='|'.join(tags), flicker=fl[0], quiet=q['ticks'] if q else 0, giveups=len(L['giveups']), evades=len(L['evades']),
               score=f"{fin['score'][0]}:{fin['score'][1]}" if fin else '-', contact=R['contact_t'] if R else '-',
               corner=round(R['corner_ticks'] / R['contact_ticks'], 2) if R and R['contact_ticks'] else '-',
               first5=f"{R['fight'][R['us']]['first5']}:{R['fight'][1 - R['us']]['first5']}" if R and R['window'] else '-',
               entry_lost=f"{R['fight'][R['us']]['e_lost']}:{R['fight'][1 - R['us']]['e_lost']}" if R and R['window'] else '-',
               entry_shots=f"{R['fight'][R['us']]['e_shots']}:{R['fight'][1 - R['us']]['e_shots']}" if R and R['window'] else '-',
               entry_in3=f"{R['fight'][R['us']]['e_r_in3']}:{R['fight'][1 - R['us']]['e_r_in3']}" if R and R['window'] else '-',
               entry_stripped=f"{R['fight'][R['us']]['e_stripped']}:{R['fight'][1 - R['us']]['e_stripped']}" if R and R['window'] else '-',
               stripped=f"{R['fight'][R['us']]['stripped_in_reach']}:{R['fight'][1 - R['us']]['stripped_in_reach']}" if R and R['window'] else '-',
               our_moved=round(R['first']['our_moved'], 0) if R and R.get('first') and R['first']['our_moved'] is not None else '-',
               his_moved=round(R['first']['his_moved'], 0) if R and R.get('first') and R['first']['his_moved'] is not None else '-',
               our_comp=round(R['first']['ocomp'][0], 1) if R and R.get('first') else '-',
               his_comp=round(R['first']['hcomp'][0], 1) if R and R.get('first') else '-',
               our_edge=R['first']['oedge'] if R and R.get('first') else '-',
               at_contact=info.get('posture_at_contact') or '-',
               melee_idle=L['why_creep_ticks'], replay=bool(R), samples=len(L['samples']))
    return row


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--last', type=int, help='only the last N matches')
    p.add_argument('--since', help='only matches from this day on, DD.MM (this year)')
    p.add_argument('--opponent', help='only matches against this username (substring)')
    p.add_argument('--version', help='only matches played by this bot version, e.g. v105')
    p.add_argument('--replays', default=os.environ.get('ARENA_REPLAYS') or os.path.expanduser('~/ScreepsArena/replays'))
    p.add_argument('--us', default='temik911')
    p.add_argument('--arena-id', default='6a86d8c454a3948a1e35f90c', help="the arena's id in the cached meta (season 4 Pain and Gain), for matches whose greeting chunk is gone")
    p.add_argument('--rows', action='store_true', help='print the per-match rows')
    p.add_argument('--csv', help='write the rows to this CSV file')
    p.add_argument('--json', help='write the rows to this JSON file')
    args = p.parse_args()
    logs, metas = ml.scan()
    games = []
    for g in logs:
        d = ml.describe(g, logs, metas)
        meta = ml.meta_of(metas[g]) if g in metas else None
        if d['result'] not in ('won', 'lost'):
            continue
        if 'pain-and-gain' not in d['arena'] and not (meta and meta.get('arena') == args.arena_id):
            continue
        games.append((d['when'], g, d))
    games.sort()
    if args.since:
        day, month = args.since.split('.')
        t0 = time.mktime(time.strptime(f"{day}.{month}.{time.localtime().tm_year}", '%d.%m.%Y'))
        games = [x for x in games if x[0] >= t0]
    if args.opponent:
        games = [x for x in games if any(args.opponent in (u or '') for u in x[2]['users'])]
    if args.last:
        games = games[-args.last:]
    rows = []
    for i, (_, g, d) in enumerate(games):
        print(f"\r# {i + 1}/{len(games)} {g}", end='', file=sys.stderr, flush=True)
        try:
            row = measure(g, logs, metas, args)
        except Exception as e:
            print(f"\n# {g}: {e}", file=sys.stderr)
            continue
        if args.version and row['version'] != args.version:
            continue
        rows.append(row)
    print('', file=sys.stderr)
    if args.csv:
        with open(args.csv, 'w', newline='', encoding='utf-8') as f:
            w = csv.DictWriter(f, fieldnames=list(rows[0].keys()) if rows else [])
            w.writeheader()
            w.writerows(rows)
    if args.json:
        json.dump(rows, open(args.json, 'w', encoding='utf-8'), ensure_ascii=False, indent=1, default=str)
    report(rows, args)


def report(rows, args):
    W = [r for r in rows if r['result'] == 'won']
    Ls = [r for r in rows if r['result'] == 'lost']
    dsum = sum(r['delta'] or 0 for r in rows)
    print(f"ledger: {len(rows)} matches ({len(W)} won, {len(Ls)} lost), rating delta {dsum:+d}, replays for {sum(1 for r in rows if r['replay'])}, "
          f"{rows[0]['when'] if rows else '-'} .. {rows[-1]['when'] if rows else '-'}")
    if args.rows:
        print('\nrows (when id version opponent result ticks delta form his-form c=contact e=corner v=first5 f=flicker q=quiet g=giveups i=idle tags):')
        for r in rows:
            print(f"  {r['when']} {r['id'][-6:]} {r['version']:5s} {r['opponent'][:14]:14s} {r['result'][0].upper()} {r['ticks']:5d} {r['delta'] if r['delta'] is not None else '?':>4} "
                  f"{r['form'][:12]:12s} {r['his_form']:8s} c={r['contact']!s:>4} e={r['corner']!s:>4} v={r['first5']:>5} f={r['flicker']:2d} q={r['quiet']:4d} g={r['giveups']:3d} i={r['melee_idle']:4d} "
                  f"E={r['entry_lost']:>10} s={r['entry_shots']:>6} in3={r['entry_in3']:>6} st={r['entry_stripped']:>4} S={r['stripped']:>8} mv={r['our_moved']}/{r['his_moved']} cp={r['our_comp']}/{r['his_comp']} {r['tags'].replace('|', ', ')}")
    # by opponent AND his code version — a username is not a bot: the server counts his uploads, and けろびー's version 1 (a blob,
    # 06.09) and version 3 (07.09) lose and win differently (the operator, 07.09.2026)
    byv = defaultdict(list)
    for r in rows:
        byv[(r['opponent'], r['opp_code'])].append(r)
    print('\nby opponent VERSION (matches W-L, rating won/lost, first..last seen, his forms, rules firing in the losses):')
    for (name, ver), rs in sorted(byv.items(), key=lambda kv: (kv[0][0], kv[0][1] if kv[0][1] is not None else -1)):
        w = sum(1 for r in rs if r['result'] == 'won')
        won = sum(r['delta'] for r in rs if (r['delta'] or 0) > 0)
        lost = sum(r['delta'] for r in rs if (r['delta'] or 0) < 0)
        forms = Counter(r['his_form'] for r in rs if r['his_form'] != '-')
        tags = Counter(t for r in rs if r['result'] == 'lost' for t in r['tags'].split('|') if t)
        print(f"  {name[:16]:16s}#{str(ver):<4} {len(rs):3d}  {w}-{len(rs) - w:<3d} {won:+4d}/{lost:<5d} {rs[0]['when']}..{rs[-1]['when']}  {dict(forms.most_common(3)) if forms else ''}  {dict(tags.most_common(3))}")
    by = defaultdict(list)
    for r in rows:
        by[r['opponent']].append(r)
    print('\nby opponent (matches W-L, rating won/lost, mean ticks, his forms, rules firing in the losses):')
    for name, rs in sorted(by.items(), key=lambda kv: sum(r['delta'] or 0 for r in kv[1])):
        w = sum(1 for r in rs if r['result'] == 'won')
        won = sum(r['delta'] for r in rs if (r['delta'] or 0) > 0)
        lost = sum(r['delta'] for r in rs if (r['delta'] or 0) < 0)
        forms = Counter(r['his_form'] for r in rs if r['his_form'] != '-')
        tags = Counter(t for r in rs if r['result'] == 'lost' for t in r['tags'].split('|') if t)
        ticks = sum(r['ticks'] for r in rs) / len(rs)
        print(f"  {name[:22]:22s} {len(rs):3d}  {w}-{len(rs) - w:<3d} {won:+4d}/{lost:<5d} {ticks:5.0f}  {dict(forms.most_common(3)) if forms else ''}  {dict(tags.most_common(4))}")
    # by version
    byv = defaultdict(list)
    for r in rows:
        byv[r['version']].append(r)
    print('\nby our version (matches W-L, rating delta, losses by outcome form):')
    for v, rs in sorted(byv.items(), key=lambda kv: (len(kv[0]), kv[0])):
        w = sum(1 for r in rs if r['result'] == 'won')
        forms = Counter(r['form'] for r in rs if r['result'] == 'lost')
        print(f"  {v:6s} {len(rs):3d}  {w}-{len(rs) - w:<3d} {sum(r['delta'] or 0 for r in rs):+4d}  {dict(forms)}")
    # by rule
    print('\nby diagnosis rule (fires in losses / in wins; a rule that fires as often in wins is a hint, not a cause):')
    tl = Counter(t for r in Ls for t in r['tags'].split('|') if t)
    tw = Counter(t for r in W for t in r['tags'].split('|') if t)
    for t in sorted(set(tl) | set(tw), key=lambda t: -(tl[t] / max(1, len(Ls)) - tw[t] / max(1, len(W)))):
        print(f"  {t:26s} losses {tl[t]:3d}/{len(Ls)} ({100 * tl[t] // max(1, len(Ls)):3d}%)   wins {tw[t]:3d}/{len(W)} ({100 * tw[t] // max(1, len(W)):3d}%)")
    # by outcome form
    print('\nby outcome form (W-L):')
    bf = defaultdict(lambda: [0, 0])
    for r in rows:
        bf[r['form']][0 if r['result'] == 'won' else 1] += 1
    for f, (w, l) in sorted(bf.items(), key=lambda kv: -sum(kv[1])):
        print(f"  {f:20s} {w}-{l}")
    # by his form (replays only)
    hf = defaultdict(lambda: [0, 0])
    for r in rows:
        if r['his_form'] != '-':
            hf[r['his_form']][0 if r['result'] == 'won' else 1] += 1
    if hf:
        print('\nby his form (replays only, W-L):')
        for f, (w, l) in sorted(hf.items(), key=lambda kv: -sum(kv[1])):
            print(f"  {f:10s} {w}-{l}")
    print('\nrating leaks (opponents by rating lost):')
    for name, rs in sorted(by.items(), key=lambda kv: sum(r['delta'] for r in kv[1] if (r['delta'] or 0) < 0))[:8]:
        lost = sum(r['delta'] for r in rs if (r['delta'] or 0) < 0)
        if lost == 0:
            break
        print(f"  {name[:22]:22s} {lost:+5d} over {sum(1 for r in rs if r['result'] == 'lost')} losses of {len(rs)}")


if __name__ == '__main__':
    main()
