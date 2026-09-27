#!/usr/bin/env python3
"""Escort Run league: our payload against our OWN stored bots (and anyone in the test list) — unrated, on the real engine.

The server lets `/api/test/start` take the code id of any code of ours that was ever played (measured 27.09.2026: v19's
code 6ab85501… against a fresh upload of v19, game 6ab8ee4f), so a league needs no stub: the payload is the candidate, the
opponents are stored code ids. Named members live in `tools/stub/escortrun/league.json` ({"v19": "<code id>", ...});
`--save-as NAME` stores the code id of THIS payload (the upload of the first hand), so a red-team persona played once
becomes a league member others can be played against.

A persona is our bot with one red-team trick switched on: the payload's `main.mjs` becomes a wrapper that sets
`globalThis.ER_PERSONA` and calls the bot's loop (`RedTeam.kt`; the default persona, with no wrapper, is `main`).

Who won: `result.winner` of `/api/game/<id>` is the SCORE of `usersCode[0]` (1 — that code won, 0 — the other one, 0.5 —
a draw), not an index into `codes[]` (tools/match-log.py replay_result reads it the same way). In self-play both codes are
ours, so our side is the code we uploaded — the one that is not the opponent's code id.

    tools/er-league.py --vs v19 -n 4                          # this worktree's build against v19
    tools/er-league.py --persona plug --vs v19 -n 4 --save-as plug-1
    tools/er-league.py --ref escort-run-v19 --vs plug-1 -n 2  # a tag's build against a stored red-team member
    tools/er-league.py --list                                 # league members
"""
import argparse, io, json, os, re, sys, time, zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import play  # noqa: E402

LEAGUE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "stub", "escortrun", "league.json")
HEX24 = re.compile(r"^[0-9a-f]{24}$")


def load_league():
    try:
        return json.load(open(LEAGUE, encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def save_league(league):
    with open(LEAGUE, "w", encoding="utf-8") as f:
        json.dump(league, f, ensure_ascii=False, indent=2, sort_keys=True)
        f.write("\n")


def with_persona(data, persona):
    """The payload with main.mjs replaced by a wrapper that names the persona before every tick."""
    src = zipfile.ZipFile(io.BytesIO(data))
    main = src.read("main.mjs").decode()
    m = re.search(r"from\s+['\"]([^'\"]+)['\"]", main)
    if not m:
        raise SystemExit("main.mjs names no module to wrap")
    wrapper = (f"import {{ loop as inner }} from '{m.group(1)}';\n"
               f"export function loop() {{ globalThis.ER_PERSONA = {json.dumps(persona)}; return inner(); }}\n")
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for info in src.infolist():
            z.writestr(info, wrapper if info.filename == "main.mjs" else src.read(info.filename))
    return out.getvalue()


def game_doc(c, gid):
    return c.json_eval(f"""(async () => {{
      {play.JS_GET}
      const r = await GET('{play.API}/game/{gid}');
      const j = await r.json(); const g = j.game || {{}}; const i = g.game || {{}};
      return JSON.stringify({{status: i.status, winner: i.result ? i.result.winner : null, usersCode: i.usersCode || [],
                              codes: (g.codes || []).map(x => ({{id: x._id, version: x.version || null}})),
                              ticks: (i.meta || {{}}).ticks || (g.meta || {{}}).ticks || null}});
    }})()""")


def verdict(doc, opponent_code):
    """(result, our code id): won / lost / draw from usersCode[0]'s score."""
    ours = next((x["id"] for x in doc["codes"] if x["id"] != opponent_code), None)
    w = doc.get("winner")
    if doc.get("status") != "finished" or w is None:
        return doc.get("status") or "?", ours
    if w == 0.5:
        return "draw", ours
    first_won = w == 1
    uc = doc.get("usersCode") or []
    if not uc or ours is None:
        return f"winner={w}", ours
    return ("won" if (uc[0] == ours) == first_won else "lost"), ours


def side_of(log_path):
    """top / bottom from our console's `world: spawn=(x,y)` line."""
    try:
        for line in open(log_path, encoding="utf-8"):
            m = re.search(r"world: spawn=\((\d+),(\d+)\)", line)
            if m:
                return "top" if int(m.group(2)) < 50 else "bottom"
    except OSError:
        pass
    return "?"


def resolve(c, arena_id, spec, league):
    if spec in league:
        return spec, league[spec]
    if HEX24.match(spec):
        return spec[-6:], spec
    row = play.resolve_test(play.test_codes(c, arena_id), spec)
    return (f"{row['name']}#{row['version']}" if row["version"] else row["name"]), row["codeId"]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--vs", action="append", default=[], help="opponent: a league name, a code id, or a test-list bot (repeatable)")
    ap.add_argument("-n", "--count", type=int, default=2, help="hands per opponent (default 2)")
    ap.add_argument("--persona", help="red-team persona of the payload (RedTeam.kt); none = main")
    ap.add_argument("--ref", help="build the payload from this git ref in a temporary worktree instead of this worktree")
    ap.add_argument("--keep", action="store_true", help="with --ref: keep the temporary worktree")
    ap.add_argument("--save-as", metavar="NAME", help="store this payload's code id (first hand) in the league under NAME")
    ap.add_argument("--logs", metavar="DIR", default="runs/league", help="where match consoles go (default runs/league)")
    ap.add_argument("--list", action="store_true", help="print the league and exit")
    a = ap.parse_args()

    league = load_league()
    if a.list:
        for k, v in sorted(league.items()):
            print(f"{k:<16} {v}")
        return
    if not a.vs:
        raise SystemExit("name at least one opponent with --vs")

    folder = play.folder_for("escort-run")
    wt = None
    if a.ref:
        wt, starter, sha = play.ab_worktree(a.ref, "L")
        data, files = play.build_zip_from(folder, starter)
        label = f"{a.ref} ({sha[:10]})"
    else:
        data, files = play.build_zip(folder)
        label = "this worktree"
    if a.persona:
        data = with_persona(data, a.persona)
        label += f" persona={a.persona}"

    c = play.CDP()
    arena = play.pick(c, "escort-run")
    s = play.slot(c, arena["id"])
    if s["game"] and s["status"] != "finished":
        raise SystemExit(f"{arena['name']}: a match is already running ({s['game']})")
    opponents = [resolve(c, arena["id"], v, league) for v in a.vs]
    print(f"league: {label}, {files} files, {len(data) / 1024 / 1024:.2f} MB — {a.count} hands against "
          + ", ".join(f"{n} ({cid})" for n, cid in opponents), flush=True)
    os.makedirs(a.logs, exist_ok=True)
    size = play.push_zip(c, data)
    table = {}
    saved = False
    try:
        for i in range(1, a.count + 1):
            for name, code in opponents:
                if play.payload_size(c) != size:
                    size = play.push_zip(c, data)
                r = play.start(c, arena["id"], code_id=code)
                # other sessions play too, and the server caps the running games per user: "Running games limit
                # exceeded" (27.09.2026, with Spawn and Swamp advanced in a series) — wait for a slot instead of giving up
                for _ in range(30):
                    if r.get("id") or "limit exceeded" not in str(r.get("err") or r.get("raw") or ""):
                        break
                    time.sleep(10)
                    r = play.start(c, arena["id"], code_id=code)
                if r["status"] not in (200, 201) or not r.get("id"):
                    print(f"{name} {i}/{a.count}: start failed ({r['status']} {r.get('err')}) {r.get('raw') or ''}", flush=True)
                    continue
                gid = r["id"]
                play.wait(c, gid)
                doc = game_doc(c, gid)
                res, ours = verdict(doc, code)
                path = os.path.join(a.logs, f"{time.strftime('%m%d-%H%M')}-{res}-{gid[-6:]}.txt")
                n = play.save_match(c, gid, path)
                side = side_of(path)
                greeting = open(path, encoding="utf-8").readline().strip() if n else ""
                t = table.setdefault(name, {})
                t[(side, res)] = t.get((side, res), 0) + 1
                print(f"{name} {i}/{a.count} {gid} {res:<5} {side:<6} ticks={doc.get('ticks')} ours={ours} | {greeting[:60]} | {path}", flush=True)
                if a.save_as and not saved and ours:
                    league[a.save_as] = ours
                    save_league(league)
                    saved = True
                    print(f"league: saved {a.save_as} = {ours}", flush=True)
    finally:
        c.eval(f"delete window[{json.dumps(play.SLOT)}]; 1")
        c.close()
        if wt and not a.keep:
            play.ab_cleanup([wt])
    print("summary:")
    for name, t in table.items():
        won = sum(v for (sd, r), v in t.items() if r == "won")
        lost = sum(v for (sd, r), v in t.items() if r == "lost")
        drawn = sum(v for (sd, r), v in t.items() if r == "draw")
        by_side = ", ".join(f"{sd} {r} {v}" for (sd, r), v in sorted(t.items()))
        print(f"  vs {name:<16} {won}-{lost}-{drawn}   ({by_side})")


if __name__ == "__main__":
    main()
