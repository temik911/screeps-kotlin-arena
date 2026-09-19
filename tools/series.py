#!/usr/bin/env python3
"""Read a whole SERIES of live matches at once: which version played, against whom, how it ended, and
what the bot's own instruments printed while it happened.

Everything here comes out of the match store (the API's documents) through `tools/match-log.py` — no new source of
truth, and nothing to keep in sync by hand. What it adds is the join that was being done by eye:

    tools/series.py versions [--arena spawn-and-swamp] [--limit 40] [--by-opponent]
        one row per bot version: matches, won/lost/drawn, rating moved, and against whom. The version
        comes from the greeting the bot logs on tick 1, so it is the code that actually played.
    tools/series.py compare 42 43 [--arena ...]
        the same two versions per opponent. The arena gives one match slot and a random draw, so a
        plain series mixes "the version got worse" with "he drew a different opponent"; this is the
        only control we have over that, and it is a weak one — read the match counts, not the sign.
    tools/series.py field home home#3 [--t0 --t1]
        the same fields per match instead of averaged: value, how many ticks carried it, the version,
        the opponent and the result. A ranking says which instrument differs; this says whether it
        differs because the number is different or because it was printed on ten ticks instead of two
        hundred, and those are different diagnoses.
    tools/series.py metrics [--arena ...] [--version 43] [--by outcome|version|opponent] [--t0 --t1]
        every `key=value` the bot printed, aggregated per match and then across matches, ranked by how
        far the groups stand apart (difference over pooled deviation). The question it answers is
        "which of our own numbers differs between the matches we win and the ones we lose" — read the
        top of that list before designing the next version.

    tools/series.py reach [--arena pain-and-gain] [--version 444] [--by-opponent] [--stand DIR [--tag land]]
        the decision tables' reachability (the bot's `reach t=` line: per table, per row `tag:won/true/shadowed`,
        cumulative over a match — so the LAST line of each match is read, never a sum of lines). One row per
        table row: in how many matches its condition was never true, in how many it was true and never won
        (shadowed by the ORDER of the table — a row that cannot fire because of what stands above it), and the
        share of its true ticks it lost to a row above. `--stand DIR` reads the stub's logs instead of the
        store, so the gate and the live series answer the same question in the same words. Rare is not dead:
        the list is where to LOOK, the proof that a row is unreachable is an argument, not a count.

Why it exists: three versions in a row were designed off numbers read by eye out of ONE match's log
(the focus share that produced v42 among them). One match is an anecdote; the instrument was already
printing, nothing was aggregating it.

The metric parser is deliberately shape-driven, not name-driven: any line of the form `t=<n> k=v k=v`
or `<name>: ... k=v` is picked up, every number inside a value becomes a field (`enemies=5/1` gives
`enemies` and `enemies#2`), and a field that never decreases over a match is summarised by its last
value while everything else is summarised by its mean. So a new instrument added to the bot appears
here without touching this file — which is the point, since the bot's instruments change every version.
"""
import argparse, importlib.util, json, math, os, re, statistics, sys, time
from collections import Counter, defaultdict

_spec = importlib.util.spec_from_file_location(
    "matchlog", os.path.join(os.path.dirname(os.path.abspath(__file__)), "match-log.py"))
matchlog = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(matchlog)

CACHE_DIR = os.path.expanduser("~/.cache/screeps-arena-series")
CACHE_VERSION = 5  # bump when the parsed shape changes, so stale files are re-read instead of trusted
US = "temik911"
NUM = re.compile(r"-?\d+(?:\.\d+)?")
# the bot prints Int.MAX_VALUE and its cousins (1073741823 = 2^30-1, 268435455 = 2^28-1) for "no
# answer"; averaging one silently turns a missing verdict into a number. The cut is computed, not
# chosen: nothing this bot measures — energy, hits, ticks, power, damage summed over a match — comes
# within two orders of magnitude of ten million, so anything above it is arithmetic on a sentinel.
SENTINEL = 1e7
TICK_LINE = re.compile(r"^t=(\d+) (.+)$")
# a named line may stamp its own tick before the colon — `enemy structures t=1900: ...`, `sites t=101:`,
# `taken t=200:`. Without the optional stamp the pattern missed every one of those lines, which is how a
# rampart on the enemy spawn stayed out of every summary for a day
NAMED_LINE = re.compile(r"^([a-z][a-z ]*?)(?: t=\d+)?: (.+)$")
TOKEN = re.compile(r"([A-Za-z][\w.]*)=(\S+)")
# a number inside a value, with the letter that introduces it if there is one (`r14`, `s113`, `w160`)
LABELLED = re.compile(r"([A-Za-z])?(-?\d+(?:\.\d+)?)")


# ---------------------------------------------------------------- reading matches

def rows(args):
    """The matches of interest, oldest first, each as match-log.describe() plus its chunk paths."""
    logs, metas = matchlog.scan()
    out = []
    for game in logs:
        r = matchlog.describe(game, logs, metas)
        if args.arena and args.arena not in r["arena"]:
            continue
        if getattr(args, "version", None) and r["version"] not in args.version:
            continue
        if getattr(args, "opponent", None) and not any(
                args.opponent.lower() in (u or "").lower() for u in r["users"] if u != US):
            continue
        r["logs"] = logs[game]
        out.append(r)
    out.sort(key=lambda r: r["when"])
    limit = getattr(args, "limit", 0)
    if limit and not getattr(args, "all", False):
        out = out[-limit:]
    return out


def foe(r):
    # the opponent's NAME only; `bot` below adds his code version — every table keys on bot(r)
    others = [u for u in r["users"] if u and u != US]
    return others[0] if others else "?"


def bot(r):
    """The opponent as the ladder actually presents him: a NAME AND A CODE VERSION.

    One player is several bots over a day. Pooling them produced a false reading on 07.09.2026 — "v46
    is a regression against けろびー" — that vanished the moment his versions were held apart: our v44
    had drawn his v12/v14/v15 and never met v16 or v17, which no version of ours has ever beaten.
    """
    name = foe(r)
    v = r.get("opp_code")
    return f"{name}#{v}" if v is not None else name


# ---------------------------------------------------------------- instrument parsing

def fields_of(text):
    """One console line as {name: (value, raw token)}; empty for a line with no k=v tokens.

    A value contributes each number it contains: `income=17/27s185` gives income, income#2 and
    income#3, `push=false(defend)` gives push=0. A key repeated on the same line (the tick line carries
    two different `home=`) keeps both, as home and home@2, instead of one silently eating the other.
    Names are never enumerated here on purpose — see the module doc.
    """
    out, seen = {}, Counter()
    for key, value in TOKEN.findall(text):
        seen[key] += 1
        name = key if seen[key] == 1 else f"{key}@{seen[key]}"
        raw = f"{key}={value}"
        low = value.lower()
        if low.startswith("true") or low.startswith("false"):
            out[name] = (1.0 if low.startswith("true") else 0.0, raw)
            continue
        # A number introduced by a letter is NAMED by that letter; only the unlabelled ones are counted
        # by position. The bot writes `income=6/27r14s113` and, before a hauler exists, `income=0/27s909`
        # — with pure position the third field is the realised income in one line and the supply in the
        # other, and a comparison across a series silently mixes them. That mixing produced a reading
        # ("realised 42 in draws against 57 in wins") that was two different quantities.
        pos = 0
        for m in LABELLED.finditer(value):
            v = float(m.group(2))
            if abs(v) >= SENTINEL:
                continue
            label = m.group(1)
            if label:
                field = f"{name}.{label}"
            else:
                pos += 1
                field = name if pos == 1 else f"{name}#{pos}"
            if field in out:
                field = f"{field}@2"
            out[field] = (v, raw)
    return out


def series_of(text_by_tick, t0, t1):
    """({field: [values]}, {field: raw token}) for one match, over the tick window.

    The raw token is kept because a shape-driven parser names a field after its position in a line
    (`home#6`), and that name is unreadable without the text it came from.
    """
    series, samples = defaultdict(list), {}
    for tick in sorted(text_by_tick):
        if tick < t0 or tick > t1:
            continue
        for line in text_by_tick[tick].split("\n"):
            line = line.strip()
            if not line:
                continue
            m = TICK_LINE.match(line)
            if m:
                prefix, body = "", m.group(2)
            else:
                m = NAMED_LINE.match(line)
                if not m:
                    continue
                prefix, body = m.group(1).replace(" ", "_") + ".", m.group(2)
            for k, (v, raw) in fields_of(body).items():
                if k == "t":  # the line's own tick stamp, not a measurement
                    continue
                series[prefix + k].append(v)
                samples.setdefault(prefix + k, (prefix[:-1] or "t=") + " " + raw)
    return series, samples


def summarise(series, min_points=3):
    """One number per field: the last value for a counter, the mean for a state.

    The split is measured, not listed: a field that never decreases over the match is a running total,
    and its mean would say only how long the match was.
    """
    out = {}
    for field, vals in series.items():
        if len(vals) < min_points:
            continue
        counter = all(b >= a for a, b in zip(vals, vals[1:])) and vals[-1] > vals[0]
        out[field] = vals[-1] if counter else statistics.fmean(vals)
    return out


def match_metrics(r, t0, t1):
    """(summary, samples, counts) of one match, cached — a finished match's log never changes.

    counts is how many ticks carried each field: a field printed only while something is happening (the
    home fight) means one thing when its value moves and another when its tick count does."""
    path = os.path.join(CACHE_DIR, f"{r['game']}_{t0}_{t1}.json")
    final = r["result"] in ("won", "lost", "draw")
    if final and os.path.exists(path):
        try:
            doc = json.load(open(path))
            if doc.get("v") == CACHE_VERSION:
                return doc["m"], doc["s"], doc["c"]
        except (ValueError, KeyError):
            pass
    ticks = matchlog.log_ticks(r["game"], {r["game"]: r["logs"]})
    series, samples = series_of(ticks, t0, t1)
    data = summarise(series)
    counts = {k: len(v) for k, v in series.items() if k in data}
    if final:
        os.makedirs(CACHE_DIR, exist_ok=True)
        json.dump(dict(v=CACHE_VERSION, m=data, s=samples, c=counts), open(path, "w"))
    return data, samples, counts


# ---------------------------------------------------------------- commands

def wld(rs):
    c = Counter(r["result"] for r in rs)
    return c["won"], c["lost"], c["draw"]


def cmd_versions(args):
    rs = rows(args)
    if not rs:
        sys.exit("no matches matched")
    by = defaultdict(list)
    for r in rs:
        by[r["version"]].append(r)
    span = (f"{time.strftime('%d.%m %H:%M', time.localtime(rs[0]['when']))}"
            f" .. {time.strftime('%d.%m %H:%M', time.localtime(rs[-1]['when']))}")
    print(f"{len(rs)} matches, {span}")
    print(f"{'version':<8} {'n':>3} {'W-L-D':>8} {'rating':>7} {'per match':>10}  opponents")
    for ver in sorted(by, key=lambda v: (v is None, v)):
        rs_v = by[ver]
        w, l, d = wld(rs_v)
        deltas = [r["delta"] for r in rs_v if r["delta"] is not None]
        total = sum(deltas)
        foes = ", ".join(f"{n} {c}" for n, c in Counter(bot(r) for r in rs_v).most_common())
        name = f"v{ver}" if ver is not None else "-"
        per = f"{total / len(deltas):+.1f}" if deltas else "-"
        print(f"{name:<8} {len(rs_v):>3} {f'{w}-{l}-{d}':>8} {total:>+7} {per:>10}  {foes}")
    if args.by_opponent:
        foes = sorted({bot(r) for r in rs})
        print(f"\n{'version':<8} " + " ".join(f"{f[:14]:>14}" for f in foes))
        for ver in sorted(by, key=lambda v: (v is None, v)):
            cells = []
            for f in foes:
                sub = [r for r in by[ver] if bot(r) == f]
                cells.append(f"{'-'.join(map(str, wld(sub))):>14}" if sub else f"{'.':>14}")
            print(f"{('v' + str(ver)) if ver is not None else '-':<8} " + " ".join(cells))
        print("cells are won-lost-drawn")


def cmd_compare(args):
    args.version = [args.a, args.b]
    rs = rows(args)
    by = defaultdict(lambda: defaultdict(list))
    for r in rs:
        by[bot(r)][r["version"]].append(r)
    print(f"{'opponent bot':<20} " + " ".join(f"{'v' + str(v):>18}" for v in (args.a, args.b)))
    for f in sorted(by):
        cells = []
        for v in (args.a, args.b):
            sub = by[f][v]
            if not sub:
                cells.append(f"{'.':>18}")
                continue
            w, l, d = wld(sub)
            delta = sum(x["delta"] for x in sub if x["delta"] is not None)
            cells.append(f"{f'{w}-{l}-{d} {delta:+}':>18}")
        print(f"{f:<20} " + " ".join(cells))
    print("cells are won-lost-drawn and the rating moved; a version is only comparable inside a row,")
    print("and a row is ONE BOT of his (name#code version) — a bare name pools bots he has rewritten")


def cmd_metrics(args):
    rs = rows(args)
    if not rs:
        sys.exit("no matches matched")
    groups, samples = defaultdict(list), {}
    for r in rs:
        m, s, _ = match_metrics(r, args.t0, args.t1)
        samples.update(s)
        if not m:
            continue
        if args.by == "outcome":
            key = r["result"] or "?"
        elif args.by == "version":
            key = f"v{r['version']}"
        else:
            key = bot(r)
        groups[key].append(m)
    order = [k for k in sorted(groups, key=lambda k: -len(groups[k])) if len(groups[k]) >= args.min]
    if len(order) < 2:
        sys.exit(f"need two groups of at least {args.min} matches; got "
                 + ", ".join(f"{k}={len(v)}" for k, v in groups.items()))
    a, b = order[0], order[1]
    fields = sorted(set().union(*[set(m) for g in groups.values() for m in g]))

    def stat(group, field):
        vals = [m[field] for m in groups[group] if field in m]
        if not vals:
            return None, None, 0
        return statistics.fmean(vals), (statistics.stdev(vals) if len(vals) > 1 else 0.0), len(vals)

    scored = []
    for f in fields:
        ma, sa, na = stat(a, f)
        mb, sb, nb = stat(b, f)
        if na < args.min or nb < args.min:
            continue
        pooled = math.sqrt((sa ** 2 + sb ** 2) / 2)
        effect = abs(ma - mb) / pooled if pooled else (0.0 if ma == mb else float("inf"))
        scored.append((effect, f, ma, mb))
    scored.sort(key=lambda x: -x[0])
    window = "" if (args.t0, args.t1) == (0, 10 ** 9) else f", ticks {args.t0}..{args.t1}"
    print(f"{len(rs)} matches{window}; groups by {args.by}: "
          + ", ".join(f"{k}={len(groups[k])}" for k in order))
    print(f"comparing {a} against {b}, ranked by how far apart they stand\n")
    print(f"{'field':<26} {a[:11]:>11} {b[:11]:>11} {'diff':>10} {'effect':>7}  printed as")
    for effect, f, ma, mb in scored[:args.top]:
        e = "inf" if effect == float("inf") else f"{effect:.2f}"
        print(f"{f:<26} {ma:>11.2f} {mb:>11.2f} {mb - ma:>+10.2f} {e:>7}  {samples.get(f, '')[:40]}")
    bases = []
    for _, f, _, _ in scored[:args.top]:
        base = f.split("#")[0]
        if base not in bases and base in samples:
            bases.append(base)
    print("\nwhere those fields come from — a `#k` is the k-th number of its token:")
    for base in bases:
        raw = samples[base]
        pos, bits = 0, []
        for m in LABELLED.finditer(raw.split("=", 1)[-1]):
            if m.group(1):
                bits.append(f".{m.group(1)}:{m.group(2)}")
            else:
                pos += 1
                bits.append(f"{pos}:{m.group(2)}")
        parts = " ".join(bits)
        print(f"  {raw}\n      {parts}")
    print("\neffect = |difference| / pooled deviation: 0.8 and up is a gap wider than the spread inside\n"
          "each group. It points at an instrument, never at a cause — read the matches it names. And a\n"
          "field measured over the whole match can be the outcome wearing a number's clothes: `push` is\n"
          "high in won matches because winning is what pushing looks like. The honest window is one that\n"
          "ends before the match was decided — --t0 1 --t1 400.")


# the match filter is the same for every subcommand, and lives on the subcommands so that it can be
# written after them: `series.py versions --limit 60`, the order one actually types
common = argparse.ArgumentParser(add_help=False)
common.add_argument("--arena", default="spawn-and-swamp", help="substring of the arena name")
common.add_argument("--limit", type=int, default=40, help="how many of the most recent matches to read")
common.add_argument("--all", action="store_true", help="every stored match, not just the last --limit")

def cmd_field(args):
    rs = rows(args)
    if not rs:
        sys.exit("no matches matched")
    window = "" if (args.t0, args.t1) == (0, 10 ** 9) else f", ticks {args.t0}..{args.t1}"
    print(f"{len(rs)} matches{window}; value and (ticks carrying it) per match\n")
    head = f"{'when':<12} {'ver':>4} {'result':<5} {'opponent':<12}"
    print(head + " " + " ".join(f"{f[:16]:>18}" for f in args.fields))
    for r in rs:
        m, s, c = match_metrics(r, args.t0, args.t1)
        cells = []
        for f in args.fields:
            cells.append(f"{m[f]:>12.1f} ({c.get(f, 0):>3})" if f in m else f"{'-':>18}")
        when = time.strftime('%d.%m %H:%M', time.localtime(r["when"]))
        ver = f"v{r['version']}" if r["version"] is not None else "-"
        print(f"{when:<12} {ver:>4} {r['result']:<5} {bot(r)[:16]:<16} " + " ".join(cells))
    for f in args.fields:
        for r in rs:
            _, s, _ = match_metrics(r, args.t0, args.t1)
            if f in s:
                print(f"\n{f} is printed as: {s[f]}")
                break


def cmd_foes(args):
    """One row per opponent BOT, every version of ours pooled.

    The unit here is deliberately the other side's code version, and our own is deliberately pooled: the
    question this answers is "which of their bots do we lose to", which no per-our-version cut can ask
    with the handful of matches a single version gets. What it found on 07.09.2026: けろびー v16 is
    0-8-0 across six different versions of ours, and v17 0-4-3 — a bot we have never beaten, whose
    presence or absence in a series moves the rating more than any change of ours did.
    """
    rs = rows(args)
    if not rs:
        sys.exit("no matches matched")
    by = defaultdict(list)
    for r in rs:
        by[bot(r)].append(r)
    print(f"{len(rs)} matches against {len(by)} opponent bots")
    print(f"{'opponent bot':<20} {'n':>3} {'W-L-D':>8} {'rating':>7} {'per match':>10}  our versions")
    for f in sorted(by, key=lambda k: (sum(x["delta"] or 0 for x in by[k]), -len(by[k]))):
        sub = by[f]
        w, l, d = wld(sub)
        deltas = [r["delta"] for r in sub if r["delta"] is not None]
        total = sum(deltas)
        per = f"{total / len(deltas):+.1f}" if deltas else "-"
        ours = ",".join(f"v{v}" for v in sorted({r["version"] for r in sub if r["version"] is not None}))
        print(f"{f:<20} {len(sub):>3} {f'{w}-{l}-{d}':>8} {total:>+7} {per:>10}  {ours}")
    print("worst first by rating moved; a bot we never beat is a hole in ours, not a bad draw")


# tables that are not "first true wins": what the two counters of a row mean there
SEQUENTIAL = {"pass": ("ran", "assigned a cell"), "gate": ("reached", "decided")}
REACH_TABLE = re.compile(r" (\w+)=((?:[\w.]+:\d+/\d+/\d+,?)+)")   # a tag may carry a dot: contact.mass, race.stall
REACH_ROW = re.compile(r"([\w.]+):(\d+)/(\d+)/(\d+)")


def last_reach(lines):
    """{table: [(tag, won, true), ...]} out of the last `reach t=` line of one match, rows in table order."""
    last = None
    for line in lines:
        if line.startswith("reach t="):
            last = line
    if last is None:
        return None
    return {table: [(tag, int(w), int(o)) for tag, w, o, _ in REACH_ROW.findall(body)]
            for table, body in REACH_TABLE.findall(last)}


def cmd_reach(args):
    import glob
    matches = []                                    # (group, reach)
    if args.stand:
        for f in sorted(glob.glob(os.path.join(args.stand, f"run-{args.tag}-*.log"))):
            got = last_reach(open(f, encoding="utf-8", errors="replace"))
            if got:
                matches.append(("stand", got))
    else:
        for r in rows(args):
            ticks = matchlog.log_ticks(r["game"], {r["game"]: r["logs"]})
            got = last_reach(line for t in sorted(ticks) for line in ticks[t].split("\n"))
            if got:
                matches.append((bot(r) if args.by_opponent else "all", got))
    if not matches:
        sys.exit("no match carries a `reach t=` line (the instrument is printed since pain-and-gain v444)")
    for group in sorted({g for g, _ in matches}):
        sub_ = [m for g, m in matches if g == group]
        print(f"\n{group}: {len(sub_)} matches — per row: matches never true / true but never won; won, true, "
              f"share of true lost to a row above")
        order, agg = [], {}
        for m in sub_:
            for table, table_rows in m.items():
                for tag, won, on in table_rows:
                    k = (table, tag)
                    if k not in agg:
                        agg[k] = [0, 0, 0, 0]
                        order.append(k)
                    a = agg[k]
                    a[0] += on == 0
                    a[1] += on > 0 and won == 0
                    a[2] += won
                    a[3] += on
        for table, tag in order:
            never, shadowed, won, on = agg[(table, tag)]
            if table in SEQUENTIAL:
                # a sequence (pass) runs every row, gates are walked in order until one decides: `true` means RAN /
                # REACHED, `won` means cells assigned / decided - "shadowed" is not a notion here
                a, b = SEQUENTIAL[table]
                note = f"   <- never {a}" if on == 0 else f"   <- never {b}" if won == 0 else ""
                print(f"  {table + '.' + tag:<20} {never:>4} {shadowed:>4}   {b} {won:>9}  {a} {on:>9}{note}")
                continue
            note = ("   <- never true" if on == 0 else "   <- true, never wins: shadowed by the order" if won == 0
                    else "")
            print(f"  {table + '.' + tag:<20} {never:>4} {shadowed:>4}   won {won:>9}  true {on:>9}  "
                  f"lost {100.0 * (on - won) / max(on, 1):5.1f}%{note}")


# ---------------------------------------------------------------- cpu

CPU_WINDOW = re.compile(r"^cpu t=(\d+): max=([\d.]+)ms at t=(\d+) slow\(>(\d+)ms\)=(\d+)")
CPU_PHASES = re.compile(r"^cpu t=(\d+) total=([\d.]+)ms: (.+)$")
CPU_GUARD = re.compile(r"^cpu t=(\d+) guard: (.+?)(?: \([\d.]+ms\))?$")


def match_cpu(r):
    """One match's CPU as the bot printed it: the hundred-tick windows, the guards that spoke, the timeouts and the
    phase breakdowns (ticks 1-3, every slow tick, every hundredth tick - only the last kind is an unbiased sample)."""
    ticks = matchlog.log_ticks(r["game"], {r["game"]: r["logs"]})
    out = {"windows": {}, "guards": Counter(), "timeouts": 0, "phases": {}, "last": max(ticks, default=0)}
    for t in sorted(ticks):
        for line in ticks[t].split("\n"):
            if "timed out" in line:
                out["timeouts"] += 1
            if not line.startswith("cpu t="):
                continue
            m = CPU_WINDOW.match(line)
            if m:
                out["windows"][int(m.group(1))] = (float(m.group(2)), int(m.group(3)), int(m.group(5)))
                continue
            m = CPU_GUARD.match(line)
            if m:
                out["guards"][m.group(2)] += 1
                continue
            m = CPU_PHASES.match(line)
            if m:
                out["phases"][int(m.group(1))] = (float(m.group(2)),
                                                  {k: float(v) for k, v in re.findall(r"([\w.]+)=([\d.]+)", m.group(3))})
    return out


def spread(xs):
    xs = sorted(xs)
    return f"{xs[0]:6.1f} {statistics.median(xs):6.1f} {xs[-1]:6.1f}" if xs else f"{'-':>6} {'-':>6} {'-':>6}"


def cmd_cpu(args):
    groups = {"control": [], "final": []}
    for r in rows(args):
        side = "control" if r["version"] in args.control else "final" if r["version"] in args.final else None
        if side:
            groups[side].append((r, match_cpu(r)))
    for side, ms in groups.items():
        print(f"\n{side}: {len(ms)} matches — per match: max after tick 3 (at tick), slow ticks, slow per 100 ticks, "
              f"guard lines, timeouts")
        for r, c in ms:
            late = [(mx, at) for mx, at, _ in c["windows"].values() if at > 3]
            # a window whose max sits on ticks 1-3 says nothing about the rest of it; the breakdown lines of the slow
            # ticks do, so the late maximum is read from both
            late += [(tot, t) for t, (tot, _) in c["phases"].items() if t > 3]
            mx, at = max(late, default=(0.0, 0))
            slow = sum(s for _, _, s in c["windows"].values())
            guards = ",".join(f"{k.split(' (')[0]}:{n}" for k, n in c["guards"].most_common()) or "-"
            print(f"  {r['game'][-6:]} v{r['version']} {bot(r):<16} {r['result']:<5} {c['last']:>5}t  "
                  f"max {mx:6.1f} at {at:<5} slow {slow:>3} ({100.0 * slow / max(c['last'], 1):4.1f}/100t)  "
                  f"guard {sum(c['guards'].values()):>2} [{guards}]  timeouts {c['timeouts']}")
    ctl, fin = groups["control"], groups["final"]
    if not ctl or not fin:
        return
    print("\nper match — min median max across matches; the final version is inside the control's spread when its "
          "numbers do not leave the control's min..max")
    def per_match(ms, f):
        return [f(c) for _, c in ms]
    cuts = [
        ("max after tick 3, ms", lambda c: max([mx for mx, at, _ in c["windows"].values() if at > 3]
                                               + [tot for t, (tot, _) in c["phases"].items() if t > 3], default=0.0)),
        ("max of ticks 2-35, ms", lambda c: max([tot for t, (tot, _) in c["phases"].items() if 2 <= t <= 35],
                                                default=0.0)),
        ("tick 1, ms", lambda c: c["phases"].get(1, (0.0,))[0]),
        ("slow per 100 ticks", lambda c: 100.0 * sum(s for _, _, s in c["windows"].values()) / max(c["last"], 1)),
        ("guard lines per match", lambda c: float(sum(c["guards"].values()))),
        ("guard per 100 ticks", lambda c: 100.0 * sum(c["guards"].values()) / max(c["last"], 1)),
        ("timeouts", lambda c: float(c["timeouts"])),
    ]
    print(f"  {'':<24} {'control min med max':>22}   {'final min med max':>22}   final above control's max")
    for name, f in cuts:
        a, b = per_match(ctl, f), per_match(fin, f)
        over = sum(1 for x in b if x > max(a))
        print(f"  {name:<24} {spread(a):>22}   {spread(b):>22}   {over}/{len(b)}")
    print("\nper window (the bot's `cpu t=N: max= slow=` line) — max= and slow= across matches that reached it")
    print(f"  {'window':>6} {'n':>3} {'control max= min med max':>26} {'slow med max':>13} | {'n':>3} "
          f"{'final max= min med max':>26} {'slow med max':>13}  above")
    for w in sorted({w for _, c in ctl + fin for w in c["windows"]}):
        if w == 100:
            continue                     # tick 1 owns the first window's maximum in every match of every version
        a = [c["windows"][w] for _, c in ctl if w in c["windows"]]
        b = [c["windows"][w] for _, c in fin if w in c["windows"]]
        if not a or not b:
            continue
        sa, sb = sorted(s for _, _, s in a), sorted(s for _, _, s in b)
        over = sum(1 for mx, _, _ in b if mx > max(m for m, _, _ in a))
        print(f"  {w:>6} {len(a):>3} {spread([m for m, _, _ in a]):>26} {statistics.median(sa):>6.1f} {sa[-1]:>6} | "
              f"{len(b):>3} {spread([m for m, _, _ in b]):>26} {statistics.median(sb):>6.1f} {sb[-1]:>6}  "
              f"{over}/{len(b)}")
    print("\nphases — mean ms on the periodic sample (every hundredth tick; slow ticks are a biased sample and are "
          "left out), and on ticks 1-3")
    for label, keep in (("every 100th", lambda t: t >= 100 and t % 100 == 0), ("ticks 1-3", lambda t: t <= 3)):
        means = {}
        for side, ms in groups.items():
            acc = defaultdict(list)
            for _, c in ms:
                for t, (tot, ph) in c["phases"].items():
                    if keep(t):
                        acc["total"].append(tot)
                        for k, v in ph.items():
                            acc[k].append(v)
            means[side] = {k: statistics.fmean(v) for k, v in acc.items()}
        keys = sorted(set(means["control"]) | set(means["final"]),
                      key=lambda k: -max(means["control"].get(k, 0), means["final"].get(k, 0)))
        print(f"  {label}: " + "  ".join(
            f"{k}={means['control'].get(k, 0):.1f}>{means['final'].get(k, 0):.1f}" for k in keys[:14]))


ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
sub = ap.add_subparsers(dest="cmd", required=True)

p = sub.add_parser("versions", parents=[common],
                   help="one row per bot version: matches, W-L-D, rating, opponents")
p.add_argument("--by-opponent", action="store_true", help="add a version x opponent table")
p.set_defaults(func=cmd_versions)

p = sub.add_parser("foes", parents=[common],
                   help="one row per opponent BOT (his code version), our versions pooled")
p.add_argument("--version", type=int, nargs="*", help="only these bot versions of ours")
p.add_argument("--opponent", help="only this opponent (substring of the name)")
p.set_defaults(func=cmd_foes)

p = sub.add_parser("compare", parents=[common], help="two versions side by side, per opponent")
p.add_argument("a", type=int)
p.add_argument("b", type=int)
p.set_defaults(func=cmd_compare)

p = sub.add_parser("field", parents=[common], help="named fields per match, with their tick counts")
p.add_argument("fields", nargs="+")
p.add_argument("--version", type=int, nargs="*", help="only these bot versions")
p.add_argument("--opponent", help="only matches against this opponent (substring)")
p.add_argument("--t0", type=int, default=0)
p.add_argument("--t1", type=int, default=10 ** 9)
p.set_defaults(func=cmd_field)

p = sub.add_parser("reach", parents=[common],
                   help="decision tables: rows never true, rows shadowed by the order (the bot's reach t= line)")
p.add_argument("--version", type=int, nargs="*", help="only these bot versions")
p.add_argument("--opponent", help="only matches against this opponent (substring)")
p.add_argument("--by-opponent", action="store_true", help="one table per opponent bot (name#version)")
p.add_argument("--stand", help="read the stub's logs in this directory instead of the match store")
p.add_argument("--tag", default="land", help="with --stand: the regress.sh tag of the logs")
p.set_defaults(func=cmd_reach)

p = sub.add_parser("metrics", parents=[common],
                   help="the bot's own printed numbers, aggregated across matches")
p.add_argument("--version", type=int, nargs="*", help="only these bot versions")
p.add_argument("--opponent", help="only matches against this opponent (substring)")
p.add_argument("--by", choices=("outcome", "version", "opponent"), default="outcome")
p.add_argument("--t0", type=int, default=0, help="first tick of the window")
p.add_argument("--t1", type=int, default=10 ** 9, help="last tick of the window")
p.add_argument("--min", type=int, default=2, help="skip groups and fields with fewer matches")
p.add_argument("--top", type=int, default=30)
p.set_defaults(func=cmd_metrics)

p = sub.add_parser("cpu", parents=[common],
                   help="the bot's own CPU lines of two groups of versions: windows, slow ticks, guards, phases")
p.add_argument("--control", type=int, nargs="+", required=True, help="the versions whose spread is the yardstick")
p.add_argument("--final", type=int, nargs="+", required=True, help="the versions measured against it")
p.add_argument("--opponent", help="only matches against this opponent (substring)")
p.set_defaults(func=cmd_cpu, version=None)

args = ap.parse_args()
if args.cmd == "cpu":
    args.version = args.control + args.final
elif args.cmd not in ("metrics", "field", "reach"):
    args.version = None
    args.opponent = None
args.func(args)
