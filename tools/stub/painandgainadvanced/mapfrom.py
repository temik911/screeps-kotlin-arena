#!/usr/bin/env python3
"""Write a Pain and Gain ADVANCED stub map out of a played match: 100 rows of 100 chars, '#' wall, '~' swamp, '.' plain,
row y = line y, column x = char x (the format run.mjs reads with MAP=).

Usage:
  python3 mapfrom.py <source> [-o map-liveN.txt]

<source> is any of
  - a replay, `<id>.replay.json.gz` or `.json` (tools/match-log.py replay; `terrain` is run-length w/p/s in ROW order);
  - a console log holding the bot's own dump, lines `map NN <100 chars>` anywhere in the line (runs/*.txt,
    `tools/match-log.py dump` output);
  - a match-store folder ~/ScreepsArena/games/<id>/ or its game.json (`game.game.terrain`, 10 000 digits in row order,
    0 plain, 1 wall, 2 swamp — every stored match has it, with or without a replay);
  - a bare game id (or its unique suffix): looked up in ~/ScreepsArena/replays/, then ~/ScreepsArena/games/.

Without -o the map goes to stdout. Which side we played goes to stderr as `START=p1|p2` (the value run.mjs takes):
by the replay's player names (env US, default temik911), by the log's `mine: ... id=pg_playerN_` lines, or, for a
store folder without such a line, by usersCode[firstPlayerIndex] being our code (usersCode[0] is the code that
started the match — ours). Nothing is written into the repository unless -o says so.
"""
import glob
import gzip
import json
import os
import re
import sys

HOME = os.path.expanduser('~')
REPLAYS = os.path.join(HOME, 'ScreepsArena', 'replays')
GAMES = os.path.join(HOME, 'ScreepsArena', 'games')
US = os.environ.get('US', 'temik911')
CH = {'w': '#', 's': '~', 'p': '.', '1': '#', '2': '~', '0': '.'}


def rows_from_cells(cells):
    if len(cells) != 10000:
        sys.exit('mapfrom: terrain has %d cells, not 10000' % len(cells))
    return [''.join(CH[c] for c in cells[y * 100:(y + 1) * 100]) for y in range(100)]


def from_replay(path):
    op = gzip.open if path.endswith('.gz') else open
    with op(path, 'rt', encoding='utf-8') as f:
        doc = json.load(f)
    cells = []
    for ch, n in re.findall(r'([wps])(\d+)', doc['terrain']):
        cells.extend(ch * int(n))
    side = None
    for p in doc.get('meta', {}).get('players', []):
        if str(p.get('username', '')).startswith(US):
            side = 'p%d' % (int(p['side']) + 1)
    return rows_from_cells(cells), side, 'replay %s' % os.path.basename(path)


def side_from_text(text):
    m = re.search(r'mine: \(\d+,\d+\) \S+ hits=\d+ id=pg_player(\d)_', text)
    return 'p' + m.group(1) if m else None


def from_log(path):
    rows = {}
    with open(path, encoding='utf-8', errors='replace') as f:
        text = f.read()
    for m in re.finditer(r'map (\d\d) ([#~.]{100})', text):
        rows[int(m.group(1))] = m.group(2)
    if len(rows) != 100:
        sys.exit('mapfrom: %s holds %d map rows, not 100 (the bot prints `map NN ...` on tick 1)' % (path, len(rows)))
    return [rows[y] for y in range(100)], side_from_text(text), 'log %s' % os.path.basename(path)


def from_store(folder):
    with open(os.path.join(folder, 'game.json'), encoding='utf-8') as f:
        doc = json.load(f)
    g = doc['game']['game']
    rows = rows_from_cells(g['terrain'])
    side, how = None, ''
    for p in sorted(glob.glob(os.path.join(folder, 'log-*.json'))):
        with open(p, encoding='utf-8') as f:
            side = side_from_text(json.dumps(json.load(f), ensure_ascii=False).replace('\\n', '\n'))
        if side:
            how = ' (side by the log)'
            break
    if side is None and isinstance(g.get('usersCode'), list) and g.get('firstPlayerIndex') is not None:
        side = 'p1' if g['usersCode'][g['firstPlayerIndex']] == g['usersCode'][0] else 'p2'
        how = ' (side by usersCode/firstPlayerIndex)'
    return rows, side, 'store %s%s' % (os.path.basename(folder.rstrip('/')), how)


def resolve(src):
    if os.path.isdir(src):
        return from_store(src)
    if os.path.isfile(src):
        if src.endswith('.json.gz') or src.endswith('.replay.json'):
            return from_replay(src)
        if os.path.basename(src) == 'game.json':
            return from_store(os.path.dirname(os.path.abspath(src)))
        return from_log(src)
    hits = sorted(glob.glob(os.path.join(REPLAYS, '*%s*.replay.json.gz' % src)))
    if len(hits) == 1:
        return from_replay(hits[0])
    hits = sorted(d for d in glob.glob(os.path.join(GAMES, '*%s*' % src)) if os.path.isdir(d))
    if len(hits) == 1:
        return from_store(hits[0])
    sys.exit('mapfrom: %s is not a file, and matches %d stored games' % (src, len(hits)))


def main():
    args = sys.argv[1:]
    out = None
    if '-o' in args:
        i = args.index('-o')
        out = args[i + 1]
        del args[i:i + 2]
    if len(args) != 1:
        sys.exit(__doc__)
    rows, side, what = resolve(args[0])
    text = '\n'.join(rows) + '\n'
    if out:
        with open(out, 'w', encoding='utf-8') as f:
            f.write(text)
    else:
        sys.stdout.write(text)
    walls = sum(r.count('#') for r in rows)
    swamps = sum(r.count('~') for r in rows)
    sys.stderr.write('mapfrom: %s -> %s: %d wall, %d swamp; we played START=%s\n'
                     % (what, out or 'stdout', walls, swamps, side or '? (not found)'))


if __name__ == '__main__':
    main()
