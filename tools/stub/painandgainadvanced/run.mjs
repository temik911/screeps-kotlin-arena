// Offline runner for Pain and Gain ADVANCED: a live map (MAP=map-liveN.txt, written by mapfrom.py) with the arena's
// fixed layout — eleven flags, four towers linked to four of them, eight 2500 containers, sixteen creeps a side — and a
// scripted enemy. Usage (see README.md):
//   node --import ./register.mjs run.mjs <ticks> none|rush|farm|line|line+lag|chase|mirror|ghost
//   env: MAP=<file> (default map-live1.txt)  START=p1|p2 (the side our bot drives, default p1)  LOGTAG=<prefix>
//        REPLAY=<game id | id prefix | path to .replay.json.gz> — the map, the bodies and the start cells of a played
//          match, and our side is the side we played there (US=<name>, default temik911); MAP and START are ignored.
//          Any scenario plays on it; `ghost` needs it (the enemy walks the recorded cells).
//        BOT=<bundle url>  NOCLOCK=1 (getCpuTime() answers 0 — the run is deterministic on any machine)
//        TRACE=<t0>-<t1> (our creeps' cells and fatigue every tick of that window, to stdout)  LINETRACE=<t0>-<t1> (the
//        `line` / `chase` script's state per tick)  STATUS=<n> (a status line to stdout every n ticks, default 500; the log gets
//        one every 100)
// The bot always drives the START side (owner 0); the other side (owner 1) is the enemy script, or with `mirror` a
// second, fully separate instance of the same bundle (hooks.mjs gives it its own module graph). Logs go to ./out/.
import { writeFileSync, mkdirSync, readFileSync, existsSync, readdirSync } from 'node:fs';
import { gunzipSync } from 'node:zlib';
import { homedir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { world, process as step, idx, inBounds, range, creeps, live, towerPower } from './world.mjs';
import { Creep } from './game/prototypes/creep.mjs';
import { Resource } from './game/prototypes/resource.mjs';
import { StructureTower } from './game/prototypes/tower.mjs';
import { StructureContainer } from './game/prototypes/container.mjs';
import { ScoreFlag } from './arena/season_4/pain_and_gain/advanced/prototypes.mjs';
import { TICKS_LIMIT, MAX_SCORE_PER_TICK } from './arena/season_4/pain_and_gain/advanced/constants.mjs';
import { getDirection } from './game/utils.mjs';
import { TOWER_POWER_ATTACK, TOWER_RANGE, TOWER_ENERGY_COST, TOWER_CAPACITY, CARRY_CAPACITY } from './game/constants.mjs';

// the bundle of THIS worktree's build (the parallel-sessions rules: the stub tests what the worktree built)
const BOT = process.env.BOT || new URL('../../../build/js/packages/screeps-kotlin-arena-starter/kotlin/screeps-kotlin-arena-starter/season4/painandgainadvanced/PainAndGainAdvanced.export.mjs', import.meta.url).href;
const BUNDLE_DIR = new URL('./', BOT);   // the mirror loads its second copy from the same bundle
const MAP = process.env.MAP || fileURLToPath(new URL('map-live1.txt', import.meta.url));
const ticks = Math.min(parseInt(process.argv[2] || String(TICKS_LIMIT), 10), TICKS_LIMIT);
// a scenario and its modifiers: `line+lag` is the line whose healers keep their rank (below)
const [scenario, ...MODS] = (process.argv[3] || 'none').split('+');
if (!['none', 'rush', 'farm', 'line', 'chase', 'mirror', 'ghost'].includes(scenario)) throw new Error(`unknown scenario ${scenario}`);
if (MODS.some((m) => m !== 'lag') || (MODS.length && scenario !== 'line')) throw new Error(`unknown modifier in ${process.argv[3]}`);
// REPLAY: ./replays/ first (a record kept with the stub), then ~/ScreepsArena/replays/ (tools/match-log.py replay <id>)
function findReplay(arg) {
  if (existsSync(arg)) return arg;
  for (const dir of [fileURLToPath(new URL('replays/', import.meta.url)), `${homedir()}/ScreepsArena/replays/`]) {
    if (!existsSync(dir)) continue;
    const hits = readdirSync(dir).filter((f) => f.endsWith('.replay.json.gz') && f.startsWith(arg));
    if (hits.length === 1) return dir + hits[0];
  }
  throw new Error(`REPLAY ${arg}: no single <id>.replay.json.gz in ./replays/ or ~/ScreepsArena/replays/`);
}
const REPLAY = process.env.REPLAY ? JSON.parse(gunzipSync(readFileSync(findReplay(process.env.REPLAY))).toString('utf8')) : null;
if (scenario === 'ghost' && !REPLAY) throw new Error('ghost needs REPLAY=<id or path>');
const US = process.env.US || 'temik911';
// our side in the replay (0 = player 1): the side of our username; in self-play the first side
const REC_US = REPLAY ? ((REPLAY.meta.players.find((p) => String(p.username).startsWith(US)) || { side: 0 }).side) : 0;
const START = REPLAY ? (REC_US === 0 ? 'p1' : 'p2') : (process.env.START || 'p1');
if (START !== 'p1' && START !== 'p2') throw new Error(`START must be p1 or p2, got ${START}`);
const TRACE = process.env.TRACE ? process.env.TRACE.split('-').map((v) => parseInt(v, 10)) : null;
const STATUS = parseInt(process.env.STATUS || '500', 10);
world.ticksLimit = TICKS_LIMIT;
world.maxScorePerTick = MAX_SCORE_PER_TICK;

// ---------- the fixed layout (27.09.2026, v1 probe and replay 6ab90e68: the same on every map) ----------
const V = 'eff_damage_taken_modifier', Hm = 'eff_heal_modifier', Am = 'eff_attack_modifier', Rm = 'eff_ranged_attack_modifier', L = 'eff_hits_loss', F = 'eff_fatigue_modifier';
const FLAGS = [
  ['pg_flag_vulnerability', 49, 49, V, 5],
  ['pg_flag_heal_reduction_a', 90, 8, Hm, 4], ['pg_flag_heal_reduction_b', 8, 90, Hm, 4],
  ['pg_flag_attack_reduction_a', 74, 24, Am, 3], ['pg_flag_attack_reduction_b', 24, 74, Am, 3],
  ['pg_flag_ranged_attack_reduction_a', 13, 49, Rm, 3], ['pg_flag_ranged_attack_reduction_b', 85, 49, Rm, 3],
  ['pg_flag_hits_loss_a', 39, 59, L, 4], ['pg_flag_hits_loss_b', 59, 39, L, 4],
  ['pg_flag_fatigue_multiplier_a', 49, 13, F, 5], ['pg_flag_fatigue_multiplier_b', 49, 85, F, 5],
];
// id, x, y, tower's flag (null: a container, 2500 energy); the ids and their order are the live ones
const STRUCTURES = [
  ['1', 14, 50, 'pg_flag_ranged_attack_reduction_a'], ['2', 84, 48, 'pg_flag_ranged_attack_reduction_b'],
  ['3', 12, 48, null], ['4', 86, 50, null], ['5', 49, 84, null], ['6', 49, 14, null],
  ['7', 49, 86, 'pg_flag_fatigue_multiplier_b'], ['8', 49, 12, 'pg_flag_fatigue_multiplier_a'],
  ['9', 48, 50, null], ['10', 50, 48, null], ['11', 50, 50, null], ['12', 48, 48, null],
];
const M = 'move', A = 'attack', R = 'ranged_attack', H = 'heal', T = 'tough', C = 'carry';
const body = (...spec) => spec.flatMap(([t, n]) => Array(n).fill(t));
const C6M6 = body([C, 6], [M, 6]), A4M4 = body([A, 4], [M, 4]), R4M4 = body([R, 4], [M, 4]), H4M4 = body([H, 4], [M, 4]);
const T4A8M6 = body([T, 4], [A, 8], [M, 6]), T4R8M6 = body([T, 4], [R, 8], [M, 6]), T4H8M6 = body([T, 4], [H, 8], [M, 6]);
// player 1 (top-left); player 2 stands at (98 - x, 98 - y) under the same role names
const ARMY = [
  ['puller_1', 15, 8, C6M6], ['puller_2', 9, 8, C6M6],
  ['melee_1', 9, 11, A4M4], ['melee_2', 15, 11, A4M4],
  ['ranged_1', 9, 9, R4M4], ['ranged_2', 15, 9, R4M4],
  ['healer_1', 9, 10, H4M4], ['healer_2', 15, 10, H4M4],
  ['heavy_melee_1', 12, 11, T4A8M6], ['heavy_melee_2', 13, 11, T4A8M6], ['heavy_melee_3', 11, 11, T4A8M6],
  ['heavy_ranged_1', 13, 9, T4R8M6], ['heavy_ranged_2', 12, 9, T4R8M6], ['heavy_ranged_3', 11, 9, T4R8M6],
  ['heavy_healer_1', 11, 10, T4H8M6], ['heavy_healer_2', 13, 10, T4H8M6],
];

const roleOf = (b) => (b.includes(C) ? 'puller' : b.includes(H) ? 'healer' : b.includes(R) ? 'ranged' : 'melee');
const PART = { a: A, r: R, h: H, m: M, t: T, c: C, w: 'work' };
const expandBody = (s) => [...s.matchAll(/([a-z])(\d+)/g)].flatMap(([, ch, n]) => Array(+n).fill(PART[ch] || M));
// the record of a replay: every creep's cell after each tick (ghost), and what the match did — its contact tick, both
// sides' hits per tick, deaths — to set the stand's numbers against (the report at the end)
const ghostPos = new Map();   // creep id -> [tick] -> {x, y} while it lived
const rec = { contact: null, hits: [], deaths: [0, 0], ticks: 0, winner: '', end: [0, 0] };
function build() {
  if (REPLAY) {
    let i = 0;
    for (const [, ch, n] of REPLAY.terrain.matchAll(/([wps])(\d+)/g)) for (let k = 0; k < +n; k++, i++) world.terrain[idx(i % 100, Math.floor(i / 100))] = ch === 'w' ? 1 : ch === 's' ? 2 : 0;
    if (i !== 10000) throw new Error(`replay terrain has ${i} cells`);
  } else {
    const rows = readFileSync(MAP, 'utf8').split('\n').filter((r) => r.length > 0);
    if (rows.length !== 100) throw new Error(`map must have 100 rows, got ${rows.length}`);
    rows.forEach((r, y) => { if (r.length !== 100) throw new Error(`row ${y} has ${r.length} chars`); for (let x = 0; x < 100; x++) world.terrain[idx(x, y)] = r[x] === '#' ? 1 : r[x] === '~' ? 2 : 0; });
  }
  for (const [id, x, y, flagId] of STRUCTURES) {
    const o = flagId ? new StructureTower(x, y, undefined) : new StructureContainer(x, y, 2500, 2500);
    o.id = id;
    if (flagId) o.flagId = flagId;
    world.objects.push(o);
  }
  for (const [id, x, y, type, score] of FLAGS) { const f = new ScoreFlag(x, y, type, score); f.id = id; world.objects.push(f); }
  if (REPLAY) {
    // the creeps as the record's tick 0 has them; owner 0 is our side of the record
    const t0 = REPLAY.ticks.find((tk) => tk.k === 0);
    for (const [id, sd, x, y, , , b] of t0.n) {
      const c = new Creep(x, y, sd === REC_US ? 0 : 1, expandBody(b));
      c.id = id; c.role = roleOf(c.body.map((p) => p.type));
      world.objects.push(c);
    }
    readRecord();
    return;
  }
  // player 1's creeps first, as the live object list has them; owner 0 is the side our bot drives
  for (const p of [1, 2]) {
    const owner = (START === 'p1') === (p === 1) ? 0 : 1;
    for (const [role, x, y, b] of ARMY) {
      const c = p === 1 ? new Creep(x, y, owner, b) : new Creep(98 - x, 98 - y, owner, b);
      c.id = `pg_player${p}_${role}`;
      c.role = roleOf(b);
      world.objects.push(c);
    }
  }
}
function readRecord() {
  const last = new Map(), sideOf = new Map(), hitsOf = new Map(), puller = new Set();
  rec.ticks = REPLAY.meta.ticks;
  const w = REPLAY.meta.result || {};
  rec.winner = w.draw ? 'draw' : w.winnerName || (REPLAY.meta.players.find((p) => p.side === w.winner) || {}).username || '?';
  for (const tk of REPLAY.ticks) {
    for (const [id, sd, x, y, h, , b] of tk.n || []) { sideOf.set(id, sd === REC_US ? 0 : 1); last.set(id, { x, y }); hitsOf.set(id, h); if (/c\d/.test(b)) puller.add(id); if (!ghostPos.has(id)) ghostPos.set(id, []); }
    for (const [id, x, y, h] of tk.u || []) { last.set(id, { x, y }); hitsOf.set(id, h); }
    for (const id of tk.x || []) { last.delete(id); hitsOf.delete(id); rec.deaths[sideOf.get(id)]++; }
    for (const [id, pos] of last) ghostPos.get(id)[tk.k] = pos;
    const sum = [0, 0];
    for (const [id, h] of hitsOf) sum[sideOf.get(id)] += h;
    rec.hits[tk.k] = sum;
    if (rec.contact === null) {
      const o = [...last].filter(([id]) => sideOf.get(id) === 0 && !puller.has(id)).map(([, p]) => p);
      const e = [...last].filter(([id]) => sideOf.get(id) === 1 && !puller.has(id)).map(([, p]) => p);
      if (o.some((a) => e.some((b) => range(a, b) <= 3))) rec.contact = tk.k;
    }
  }
  for (const [id] of last) rec.end[sideOf.get(id)]++;
}
build();

// ---------- flow fields (the enemy scripts walk down them) ----------
const INF = 1 << 29;
const DX = [0, 1, 1, 1, 0, -1, -1, -1], DY = [-1, -1, 0, 1, 1, 1, 0, -1];
const towerCell = new Set(world.objects.filter((o) => o.kind === 'tower').map((o) => idx(o.x, o.y)));
const passable = (x, y) => inBounds(x, y) && world.terrain[idx(x, y)] !== 1 && !towerCell.has(idx(x, y));
class Heap {
  constructor() { this.k = []; this.v = []; }
  get size() { return this.k.length; }
  push(k, v) { const K = this.k, W = this.v; K.push(k); W.push(v); let i = K.length - 1; while (i > 0) { const p = (i - 1) >> 1; if (K[p] <= K[i]) break; [K[p], K[i]] = [K[i], K[p]]; [W[p], W[i]] = [W[i], W[p]]; i = p; } }
  pop() { const K = this.k, W = this.v; const k = K[0], v = W[0]; const lk = K.pop(), lv = W.pop(); if (K.length) { K[0] = lk; W[0] = lv; let i = 0; for (;;) { const l = 2 * i + 1, r = l + 1; let m = i; if (l < K.length && K[l] < K[m]) m = l; if (r < K.length && K[r] < K[m]) m = r; if (m === i) break; [K[m], K[i]] = [K[i], K[m]]; [W[m], W[i]] = [W[i], W[m]]; i = m; } } return [k, v]; }
}
/** Cost from every cell to the nearest goal: entering a plain cell costs 1, a swamp 5 (the bot's own Grid does the same). */
function flow(goals) {
  const dist = new Int32Array(10000).fill(INF);
  const heap = new Heap();
  for (const g of goals) { if (!passable(g.x, g.y)) continue; const i = idx(g.x, g.y); if (dist[i] > 0) { dist[i] = 0; heap.push(0, i); } }
  while (heap.size) {
    const [d, i] = heap.pop();
    if (d > dist[i]) continue;
    const x = (i / 100) | 0, y = i % 100;
    const enter = world.terrain[i] === 2 ? 5 : 1;
    for (let k = 0; k < 8; k++) {
      const nx = x + DX[k], ny = y + DY[k];
      if (!passable(nx, ny)) continue;
      const n = idx(nx, ny), nd = d + enter;
      if (nd < dist[n]) { dist[n] = nd; heap.push(nd, n); }
    }
  }
  return dist;
}
// fields to fixed cells (flags) are kept for the match; fields to a creep's cell live one tick — a moving target would
// otherwise leave a 40 KB field per cell it ever stood on, and the heap is capped at 192 MB (regress.sh)
const flowCache = new Map();
const flowTo = (x, y) => { const k = idx(x, y); let f = flowCache.get(k); if (!f) { f = flow([{ x, y }]); flowCache.set(k, f); } return f; };
let tickCache = new Map(), tickCacheAt = -1;
const flowNow = (x, y) => { if (tickCacheAt !== world.tick) { tickCache = new Map(); tickCacheAt = world.tick; } const k = idx(x, y); let f = tickCache.get(k); if (!f) { f = flow([{ x, y }]); tickCache.set(k, f); } return f; };

// ---------- enemy scripts ----------
// one enemy tick: `occ` holds every creep's cell, `claim` the cells enemy movers took this tick and `leaving` the enemy
// creeps that registered a move — a follower may step into a cell whose owner is leaving (the engine's chain)
let occ = new Map(), claim = new Set(), leaving = new Map();
function beginMoves() {
  occ = new Map(); claim = new Set(); leaving = new Map();
  for (const c of creeps()) occ.set(idx(c.x, c.y), c);
}
function free(c, n) {
  if (claim.has(n)) return false;
  const o = occ.get(n);
  return !o || o === c || (o.owner === c.owner && leaving.has(o.id) && leaving.get(o.id) !== idx(c.x, c.y));
}
function moveTo(c, n) {
  const nx = (n / 100) | 0, ny = n % 100;
  c.move(getDirection(nx - c.x, ny - c.y));
  claim.add(n); leaving.set(c.id, n);
}
const canMove = (c) => c.fatigue === 0 && live(c, M) > 0;
/** One step down a field, strictly downhill, into a free cell. */
function stepDown(c, f) {
  if (!canMove(c)) return;
  const here = f[idx(c.x, c.y)];
  let best = -1, bv = here;
  for (let k = 0; k < 8; k++) {
    const nx = c.x + DX[k], ny = c.y + DY[k];
    if (!passable(nx, ny)) continue;
    const n = idx(nx, ny);
    if (f[n] < bv && free(c, n)) { bv = f[n]; best = n; }
  }
  if (best >= 0) moveTo(c, best);
}
/** One greedy step that widens the gap to the nearest of `from` (plain preferred to swamp). */
function stepAway(c, from) {
  if (!canMove(c) || !from.length) return;
  const gap = (x, y) => Math.min(...from.map((o) => Math.max(Math.abs(o.x - x), Math.abs(o.y - y))));
  let best = -1, bv = gap(c.x, c.y) * 10;
  for (let k = 0; k < 8; k++) {
    const nx = c.x + DX[k], ny = c.y + DY[k];
    if (!passable(nx, ny)) continue;
    const n = idx(nx, ny);
    const v = gap(nx, ny) * 10 - (world.terrain[n] === 2 ? 5 : 0);
    if (v > bv && free(c, n)) { bv = v; best = n; }
  }
  if (best >= 0) moveTo(c, best);
}
const armed = (c) => live(c, A) + live(c, R) > 0;
/** Fire and heal by rule: melee at the adjacent lowest hits, ranged single shots at the lowest hits in three, healers at
 *  the most wounded mate (adjacent heal, else ranged heal in three). */
function act(c, mine, ours) {
  if (live(c, A) > 0) {
    const adj = ours.filter((o) => range(c, o) <= 1).sort((a, b) => a.hits - b.hits)[0];
    if (adj) c.attack(adj);
  }
  if (live(c, R) > 0) {
    const t = ours.filter((o) => range(c, o) <= 3).sort((a, b) => a.hits - b.hits)[0];
    if (t) c.rangedAttack(t);
  }
  if (live(c, H) > 0) {
    const hurt = mine.filter((o) => o.hits < o.hitsMax && range(c, o) <= 3).sort((a, b) => (b.hitsMax - b.hits) - (a.hitsMax - a.hits))[0];
    if (hurt) { if (range(c, hurt) <= 1) c.heal(hurt); else c.rangedHeal(hurt); }
  }
}
// pullers: the two tower flags nearest their start by path, one each; on the flag, withdraw from the container beside it
// and keep the tower full once it is theirs (the bot's own v2 plan, so the towers fire on both sides)
const pullerPost = new Map();
function pullers(mine) {
  const ps = mine.filter((c) => c.role === 'puller');
  if (!ps.length) return;
  if (!pullerPost.size) {
    const towerFlags = world.objects.filter((o) => o.kind === 'flag' && world.objects.some((t) => t.kind === 'tower' && t.flagId === o.id));
    const home = towerFlags.map((f) => ({ f, d: Math.min(...ps.map((p) => flowTo(f.x, f.y)[idx(p.x, p.y)])) })).sort((a, b) => a.d - b.d).slice(0, 2).map((e) => e.f);
    for (const p of ps) {
      const left = home.filter((f) => ![...pullerPost.values()].includes(f));
      const pick = left.sort((a, b) => flowTo(a.x, a.y)[idx(p.x, p.y)] - flowTo(b.x, b.y)[idx(p.x, p.y)])[0];
      if (pick) pullerPost.set(p.id, pick);
    }
  }
  for (const p of ps) {
    const f = pullerPost.get(p.id);
    if (!f) continue;
    if (p.x !== f.x || p.y !== f.y) { stepDown(p, flowTo(f.x, f.y)); continue; }
    feed(p, f);
  }
}
/** A puller on a tower flag: withdraw from the container beside it, keep the tower full once it is its side's. */
function feed(p, f) {
  const tw = world.objects.find((o) => o.exists && o.kind === 'tower' && o.flagId === f.id);
  const box = world.objects.filter((o) => o.exists && o.kind === 'container' && range(o, f) <= 1).sort((a, b) => b.store.energy - a.store.energy)[0];
  if (tw && tw.owner === p.owner && tw.store.energy < TOWER_CAPACITY && p.store.energy > 0) p.transfer(tw, 'energy');
  else if (box && box.store.energy > 0 && p.store.free() >= CARRY_CAPACITY) p.withdraw(box, 'energy');
}
/** The enemy's towers: the nearest creep of ours in range (the hardest shot), else the most wounded of its own. */
function enemyTowers(mine, ours) {
  world.perspective = 1;
  for (const tw of world.objects) {
    if (!tw.exists || tw.kind !== 'tower' || tw.owner !== 1 || tw.store.energy < TOWER_ENERGY_COST || tw.cooldown > 0) continue;
    const t = ours.filter((o) => range(tw, o) <= TOWER_RANGE).sort((a, b) => range(tw, a) - range(tw, b) || a.hits - b.hits)[0];
    if (t) { tw.attack(t); continue; }
    const hurt = mine.filter((o) => range(tw, o) <= TOWER_RANGE && o.hitsMax - o.hits >= 200).sort((a, b) => (b.hitsMax - b.hits) - (a.hitsMax - a.hits))[0];
    if (hurt) tw.heal(hurt);
  }
  world.perspective = 0;
}
// rush: every fighter walks at the nearest creep of ours and fights; melee close to one, ranged to three, healers keep
// to their own armed creeps
function rush(mine, ours) {
  const fighters = mine.filter((c) => c.role !== 'puller');
  if (!ours.length || !fighters.length) return;
  const toOurs = flow(ours.map((o) => ({ x: o.x, y: o.y })));
  const ownArmed = fighters.filter(armed);
  const toArmed = ownArmed.length ? flow(ownArmed.map((o) => ({ x: o.x, y: o.y }))) : toOurs;
  for (const c of fighters.sort((a, b) => toOurs[idx(a.x, a.y)] - toOurs[idx(b.x, b.y)])) {
    act(c, mine, ours);
    const near = Math.min(...ours.map((o) => range(c, o)));
    if (c.role === 'healer') {
      const hurt = mine.filter((o) => o !== c && o.hits < o.hitsMax && range(c, o) <= 6).sort((a, b) => (b.hitsMax - b.hits) - (a.hitsMax - a.hits))[0];
      if (hurt && range(c, hurt) > 1) stepDown(c, flowNow(hurt.x, hurt.y));
      else if (!hurt && Math.min(...ownArmed.map((o) => range(c, o)), 99) > 1) stepDown(c, toArmed);
      continue;
    }
    if (near > (c.role === 'melee' ? 1 : 3)) stepDown(c, toOurs);
  }
}
// farm: the fighters take the flags as one group — the nearest flag not theirs with no armed creep of ours within six of
// it — never close on our fighters (a creep with one of our armed creeps within six steps away) and fire at what comes
// within three
const farmState = { flag: null };
function farm(mine, ours) {
  const fighters = mine.filter((c) => c.role !== 'puller');
  if (!fighters.length) return;
  const ourArmed = ours.filter(armed);
  const flags = world.objects.filter((o) => o.exists && o.kind === 'flag');
  const safe = (f) => !ourArmed.some((o) => range(o, f) <= 6);
  let f = farmState.flag;
  if (!f || f.owner === 1 || !safe(f)) {
    const cands = flags.filter((x) => x.owner !== 1 && safe(x));
    const cost = (x) => Math.min(...fighters.map((c) => flowTo(x.x, x.y)[idx(c.x, c.y)]));
    f = farmState.flag = cands.sort((a, b) => cost(a) - cost(b))[0] || null;
  }
  const fl = f ? flowTo(f.x, f.y) : null;
  const capturer = f ? fighters.slice().sort((a, b) => fl[idx(a.x, a.y)] - fl[idx(b.x, b.y)])[0] : null;
  for (const c of fighters.sort((a, b) => (fl ? fl[idx(a.x, a.y)] - fl[idx(b.x, b.y)] : 0))) {
    act(c, mine, ours);
    const threat = ourArmed.filter((o) => range(c, o) <= 6);
    if (threat.length) { stepAway(c, threat); continue; }
    if (!f) continue;
    if (c === capturer || range(c, f) > 3) stepDown(c, fl);
  }
}

// ---------- line: stachu3478#5 ----------
// The bot that beat v5-v7 live (27.09.2026). Measured on the replays 6ab91898 (v5), 6ab91924 (v6), 6ab91a90 (v7),
// 6ab91b4b and 6ab91b62 (v9), the first 100 ticks of contact: all sixteen walk at our army as one body at the heavy
// pace — head to tail 3-6 cells — and take no flag (only what they walk over). In contact, by the distance to our
// nearest fighter, his heavy melee stand at 1-3 (mode 2-3: the shield — stripped by our fire, still in front), the
// light melee at 2-4, the ranged at 2-3, the healers at 3-4, the pullers at 4-5. His ranged shoot one target, the lowest
// hits in reach (83-92 % of shots), nearly always from 3; a mass attack comes when it out-damages a single shot (10/4/1
// at 1/2/3 summed above 10: with three of ours within two it was mass 133 times of 133, with two 47 of 76). His melee
// swing at the adjacent lowest hits (100 %). His healers heal ADJACENT (192-215 heals of a hurt mate in the window
// against 54-131 ranged heals), one on a mate as a rule and two on one in 20-28 % of healing ticks.
// The approach: his body is slow long before contact — its centroid moved 5 cells in the first 80 ticks of 6ab91924,
// 14 in 6ab91898, and crept at 0.1-0.25 cells a tick after that (the line reforming, one cell at a time) until 7-11
// cells from our army, then pushed in at the heavy pace; 6ab91a90 alone came in at 0.3-0.4. Contact: t=102-314.
// Modelled: distances are steps to our nearest fighter (walls respected). The head is the front member (the melee)
// nearest to us; in contact each role stands at its distance (LINE.goal), before contact the others keep ranks behind
// the head (LINE.offset: heavy ranged and heavy healers 2, light ranged and light healers 3, pullers 4) and the head
// steps only while nobody is more than two behind its slot, once in creepEvery ticks until creepNear cells from us
// and every tick after; a creep behind its slot swaps cells with a mate that belongs further back or stands ahead of
// its own slot, and sidesteps round its own members when nothing is free downhill; a ranged or a healer with one of
// ours adjacent steps back; a healer leaves its rank for the most wounded mate (two for one that lost 50+ hits the
// tick before). A creep that wanted to move and has not for six ticks is stuck and does not hold the line back.
const LAG = 3;
// a formation script is a PROFILE for `formation()` below: the distance each role keeps from our nearest fighter in contact
// (goal), the rank it keeps behind the head before contact (offset), which roles are the front, when contact begins
// (null: one cell past the front's goal), the head's creep before contact (one step in creepEvery ticks until creepNear
// cells from us), whether a ranged or healer with one of ours adjacent steps back, whether the body takes the centre
// flag first (flagFirst: unless our fighters are within `engage` steps), and whether its pullers stay at home
const LINE = {
  name: 'line',
  goal: { heavy_melee: 2, melee: 2, heavy_ranged: 3, ranged: 3, heavy_healer: 3, healer: 3, puller: 5 },
  offset: { heavy_melee: 0, melee: 1, heavy_ranged: 2, ranged: 3, heavy_healer: 2, healer: 3, puller: 4 },
  front: ['heavy_melee'], contact: null, creepNear: 10, creepEvery: 5, backOff: true, flagFirst: false, engage: 0, pullersStay: false,
};
const LINE_KEYS = new Set(Object.keys(LINE.goal));
// ---------- chase: Hardy#3 ----------
// The bot that routed v17 and v18 in 200-300 ticks (27.09.2026; records 6ab9349f v17 and 6ab93d04 v18, and 6ab916f5
// where it beat v4 the same way). Measured on the records: his pullers never leave their start cells; the other
// fourteen walk as one clump at the heavy pace (5 cells in 10 ticks) straight to the centre flag, which a light creep
// of the clump takes at t=75, and on through it after OUR army, round the big wall to our fortress by the ranged-
// reduction tower, and attack as they arrive (contact t=121-128); with the fight won they walk on to our pullers
// (killed at t=198 on the tower flag and at t=297 on the fatigue flag). On the way the lights walk a cell ahead of the
// heavy melee, the heavy healers one behind them and the heavy ranged two or three behind. In the 80 ticks after
// contact, by the distance to our nearest fighter: heavy melee 1-2 (86 and 92 creep-ticks of 267), light melee 1-2,
// light ranged 1-3, heavy ranged 2-5, light healers 1-2, heavy healers 1-3 — a brawl at arm's length, nobody steps
// back. Melee swing at the adjacent lowest hits (90 % of swings); ranged shoot the lowest in reach (68-75 %, the
// nearest 75-85 %) and mass-attack often (60 + 25 mass against 106 + 31 single shots of v17's record); healers heal
// BESIDE (146-159 heals of a hurt mate against 20-55 from range), one on a mate, two in 13-25 % of healing ticks; his
// heavy healers stood next to his heavy melee in 80 % of contact ticks and healed them (65, 54 heals) and the heavy
// ranged, his light healers themselves and the melee.
// Modelled: the clump walks to the centre flag and, once within three of it, sends its nearest light onto it and walks
// on at the heavy pace toward our nearest fighter, round swamps (path costs 1/5), the heavy melee in front with the
// lights beside them, the heavy healers one rank and the heavy ranged two behind; the front never waits (noWait), a
// mate at its slot lets it through (yieldAtSlot); the healers escort — heavy healers the nearest heavy melee, light
// healers the nearest melee — and heal what is hurt beside them; five cells out the ranks collapse into the brawl:
// melee and healers to one, ranged to two. His pullers stay home. With our army dead the clump walks to our pullers.
// Against v17 and v18 on their own records it reproduces the timeline — the centre at t=75 (75 live), our army's FIGHT
// at t=116 (115), contact at t=128 (128 and 121) — and the rout: our army destroyed at t=331 and 336 (298 and 311),
// eight of his left (nine and eight); tools/stub/painandgainadvanced/README.md has the numbers.
const CHASE = {
  name: 'chase',
  goal: { heavy_melee: 1, melee: 1, heavy_ranged: 2, ranged: 2, heavy_healer: 1, healer: 1, puller: 5 },
  offset: { heavy_melee: 0, melee: 0, heavy_ranged: 2, ranged: 0, heavy_healer: 1, healer: 0, puller: 4 },
  front: ['heavy_melee'], contact: 5, creepNear: 0, creepEvery: 1, backOff: false, flagFirst: true, engage: 8, pullersStay: true,
  yieldAtSlot: true, weighted: true, noWait: true, escort: { heavy_healer: ['heavy_melee'], healer: ['melee', 'heavy_melee'] },
};
function lineKey(c) {
  const key = c.id.replace(/^pg_player\d_/, '').replace(/_\d+$/, '');
  if (LINE_KEYS.has(key)) return key;
  return (c.body.length > 8 ? 'heavy_' : '') + c.role;   // not a live id: by body
}
/** Steps (every passable cell costs 1) from every cell to the nearest goal: the line's distances, walls respected. */
function steps(goals) {
  const dist = new Int32Array(10000).fill(INF);
  const q = new Int32Array(10000);
  let h = 0, t = 0;
  for (const g of goals) { const i = idx(g.x, g.y); if (dist[i] !== 0) { dist[i] = 0; q[t++] = i; } }
  while (h < t) {
    const i = q[h++], x = (i / 100) | 0, y = i % 100;
    for (let k = 0; k < 8; k++) {
      const nx = x + DX[k], ny = y + DY[k];
      if (!passable(nx, ny)) continue;
      const n = idx(nx, ny);
      if (dist[n] > dist[i] + 1) { dist[n] = dist[i] + 1; q[t++] = n; }
    }
  }
  return dist;
}
/** One step along a field, down (toward) or up (away), plain before swamp at an equal value, into a free cell — or,
 *  when `canSwap(o)` allows it, into the cell of a standing mate that takes this creep's cell in exchange (the engine
 *  moves both): a melee line that fell behind its own ranks passes through them instead of waiting behind them. */
function stepField(c, f, away, canSwap, from) {
  if (!canMove(c)) return false;
  const here = f[idx(c.x, c.y)], mine = idx(c.x, c.y);
  let best = -1, bk = 0, bs = 0, bo = null;
  for (let k = 0; k < 8; k++) {
    const nx = c.x + DX[k], ny = c.y + DY[k];
    if (!passable(nx, ny)) continue;
    const n = idx(nx, ny), v = f[n];
    if (away ? v <= here || v >= INF : v >= here) continue;
    const key = away ? -v : v, sw = world.terrain[n] === 2 ? 1 : 0;
    let o = null;
    if (!free(c, n)) {
      o = occ.get(n);
      if (!canSwap || !o || o.owner !== c.owner || leaving.has(o.id) || claim.has(n) || claim.has(mine) || !canMove(o) || !canSwap(o)) continue;
    }
    // a free cell beats a swap at the same value
    const kk = key * 4 + (o ? 2 : 0) + sw;
    if (best < 0 || kk < bk) { best = n; bk = kk; bo = o; }
  }
  if (best < 0 && !away && from !== undefined) {
    // nothing downhill: a free cell at the same value, not the one it came from — a blob flows round its own members
    for (let k = 0; k < 8; k++) {
      const nx = c.x + DX[k], ny = c.y + DY[k];
      if (!passable(nx, ny)) continue;
      const n = idx(nx, ny);
      if (f[n] !== here || n === from || !free(c, n)) continue;
      const sw = world.terrain[n] === 2 ? 1 : 0;
      if (best < 0 || sw < bs) { best = n; bs = sw; }
    }
  }
  if (best < 0) return false;
  if (bo) { bo.move(getDirection(c.x - bo.x, c.y - bo.y)); claim.add(mine); leaving.set(bo.id, mine); }
  moveTo(c, best);
  return true;
}
const lineState = { last: new Map(), prev: new Map(), wanted: new Map(), still: new Map(), hits: new Map() };
const LINETRACE = process.env.LINETRACE ? process.env.LINETRACE.split('-').map((v) => parseInt(v, 10)) : null;
function formation(all, ours, P) {
  const mine = P.pullersStay ? all.filter((c) => c.role !== 'puller') : all;
  if (!mine.length) return;
  const G0 = P.goal, OFF = P.offset;
  const goals = ours.filter((o) => o.role !== 'puller');
  const targets = goals.length ? goals : ours;
  if (!targets.length) return;
  // distances: steps (every cell 1), or with `weighted` the path cost (plain 1, swamp 5 — a pathfinder's route, which
  // walks round a swamp where the heavies would pay ten ticks a cell)
  const D = P.weighted ? flow(targets.map((o) => ({ x: o.x, y: o.y }))) : steps(targets);
  // flagFirst: the body walks to the centre flag while it is not theirs and none of our fighters is within `engage`
  const centre = P.flagFirst ? world.objects.find((o) => o.exists && o.kind === 'flag' && o.effectType === 'eff_damage_taken_modifier') : null;
  let toFlag = !!centre && centre.owner !== 1 && Math.min(...mine.map((c) => D[idx(c.x, c.y)])) > P.engage;
  const F = toFlag ? (P.weighted ? flowTo(centre.x, centre.y) : flowSteps(centre)) : null;
  // the flag is on the way, not the stop: once the clump is within three of it, one creep (the nearest light) steps on
  // it and the rest walk on toward us — Hardy's clump crossed the centre at full pace (a light took it at t=75, the
  // centroid was 3 cells past it five ticks later)
  let capturer = null;
  if (toFlag && Math.min(...mine.map((c) => F[idx(c.x, c.y)])) <= 3) {
    capturer = mine.slice().sort((a, b) => F[idx(a.x, a.y)] - F[idx(b.x, b.y)] || a.body.length - b.body.length)[0];
    toFlag = false;
  }
  const G = toFlag ? F : D;
  const d = (c) => G[idx(c.x, c.y)];
  const goalOf = (c) => (toFlag ? 0 : G0[lineKey(c)]);
  // a creep that wanted to move and has not for six ticks is stuck: it does not hold the line
  for (const c of mine) {
    const p = lineState.last.get(c.id), moved = p === undefined || p !== idx(c.x, c.y);
    if (moved && p !== undefined) lineState.prev.set(c.id, p);   // the cell it came from: a sidestep does not go back there
    lineState.still.set(c.id, moved || !lineState.wanted.get(c.id) ? 0 : (lineState.still.get(c.id) || 0) + 1);
    lineState.last.set(c.id, idx(c.x, c.y));
    lineState.wanted.set(c.id, false);
  }
  const stuck = (c) => (lineState.still.get(c.id) || 0) >= 6;
  // the front: the profile's front roles (kept in front however stripped); with none left, whatever has the lowest offset
  let front = mine.filter((c) => P.front.includes(lineKey(c)));
  if (!front.length) { const m0 = Math.min(...mine.map((c) => OFF[lineKey(c)])); front = mine.filter((c) => OFF[lineKey(c)] === m0); }
  const frontOff = Math.min(...front.map((c) => OFF[lineKey(c)]));
  // L is the head — the front member nearest to the goal; every rank's slot counts from it, and the head steps only
  // while no one (stuck or nursing excepted) is more than two behind its slot: the head never leaves the tail
  const L = Math.min(...front.map(d));
  const frontGoal = Math.max(...front.map(goalOf));
  const inContact = !toFlag && L <= (P.contact ?? frontGoal + 1);
  const ranked = (c) => Math.max(goalOf(c), L + OFF[lineKey(c)] - frontOff);
  // line+lag: his healers hang LAG cells behind their rank and never close in (the records he lost: 7-11 cells back)
  const lagMode = P === LINE && MODS.includes('lag');
  const lagging = (c) => lagMode && c.role === 'healer';
  // in contact the ranks collapse: every role goes to its goal distance
  const slot = (c) => (lagging(c) ? ranked(c) + LAG : inContact ? goalOf(c) : ranked(c));
  // healers: the most wounded mate first, two healers for one that lost 50+ hits last tick
  const lost = (c) => Math.max(0, (lineState.hits.get(c.id) ?? c.hits) - c.hits);
  const healers = mine.filter((c) => live(c, H) > 0);
  const patientOf = new Map();
  const wounded = mine.filter((c) => c.hits < c.hitsMax).sort((a, b) => (b.hitsMax - b.hits) - (a.hitsMax - a.hits));
  // line+lag: the healers do not walk to the wounded — the two records of six he LOST (919a7, 91b4b) had his heavy
  // healers 7-11 cells behind a front that was being stripped, where the four he won had them at 3-4 (see `slot`)
  // escort (chase): a healer keeps beside the nearest mate of the roles it escorts and heals what is hurt beside it —
  // Hardy's heavy healers stood next to his heavy melee in 80 % of contact ticks and healed them and the heavy ranged;
  // a healer whose escorted roles are dead takes a patient like any other
  const escortOf = new Map();
  if (P.escort) for (const h of healers) {
    const roles = P.escort[lineKey(h)];
    const cands = roles ? mine.filter((o) => o !== h && roles.includes(lineKey(o))) : [];
    if (cands.length) escortOf.set(h.id, cands.sort((a, b) => range(h, a) - range(h, b))[0]);
  }
  for (const m of lagMode ? [] : wounded) {
    if (patientOf.size + escortOf.size >= healers.length) break;
    const idle = healers.filter((h) => !patientOf.has(h.id) && !escortOf.has(h.id)).sort((a, b) => range(a, m) - range(b, m) || b.body.length - a.body.length);
    for (const h of idle.slice(0, lost(m) >= 50 ? 2 : 1)) patientOf.set(h.id, m);
  }
  for (const c of mine) lineState.hits.set(c.id, c.hits);
  // (an escort counts: the line waits for the healers walking with its melee — a patient's nurse does not)
  const together = mine.every((c) => stuck(c) || patientOf.has(c.id) || d(c) <= slot(c) + 2);
  // fire: melee the adjacent lowest; ranged a mass attack when it out-damages one shot, else the lowest in reach
  for (const c of mine) {
    if (live(c, A) > 0) { const t = ours.filter((o) => range(c, o) <= 1).sort((a, b) => a.hits - b.hits)[0]; if (t) c.attack(t); }
    if (live(c, R) > 0) {
      const reach = ours.filter((o) => range(c, o) <= 3);
      if (reach.reduce((s, o) => s + [10, 10, 4, 1][range(c, o)], 0) > 10) c.rangedMassAttack();
      else if (reach.length) c.rangedAttack(reach.sort((a, b) => a.hits - b.hits || range(c, a) - range(c, b))[0]);
    }
    if (live(c, H) > 0) {
      const m = patientOf.get(c.id);
      const adj = mine.filter((o) => o.hits < o.hitsMax && range(c, o) <= 1).sort((a, b) => (b.hitsMax - b.hits) - (a.hitsMax - a.hits))[0];
      const near = mine.filter((o) => o.hits < o.hitsMax && range(c, o) <= 3).sort((a, b) => (b.hitsMax - b.hits) - (a.hitsMax - a.hits))[0];
      if (m && range(c, m) <= 1) c.heal(m);
      else if (adj) c.heal(adj);
      else if (m && range(c, m) <= 3) c.rangedHeal(m);
      else if (!m && near) c.rangedHeal(near);
    }
  }
  // LINETRACE=t0-t1: the script's state per tick of the window (the head L, contact, together, each creep's distance/slot)
  if (LINETRACE && world.tick >= LINETRACE[0] && world.tick <= LINETRACE[1]) origLog(`${P.name} t=${world.tick} L=${L}${toFlag ? ' flag' : ''} contact=${inContact} together=${together} ${mine.map((c) => `${lineKey(c)}@${c.x},${c.y}:${d(c)}/${slot(c)}~${Math.min(...targets.map((o) => range(c, o)))}${stuck(c) ? 's' : ''}${patientOf.get(c.id) ? 'p' : ''}${escortOf.get(c.id) ? 'e' : ''}`).join(' ')}`);
  if (capturer && (capturer.x !== centre.x || capturer.y !== centre.y)) stepField(capturer, F, false, (o) => o !== capturer && !front.includes(o));
  // move, front first
  for (const c of mine.slice().sort((a, b) => d(a) - d(b))) {
    if (c === capturer) continue;
    const m = patientOf.get(c.id) || escortOf.get(c.id);
    if (m && m !== c) {
      // an escort passes through the ranks to its melee the way a creep reaches its slot (swapping with a mate that
      // belongs further back or stands ahead of its own slot); a nurse walks round them
      const byRank = escortOf.has(c.id) ? (o) => !patientOf.has(o.id) && !escortOf.has(o.id) && !front.includes(o) && (OFF[lineKey(o)] > OFF[lineKey(c)] || d(o) <= slot(o)) : undefined;
      if (range(c, m) > 1) { lineState.wanted.set(c.id, true); stepField(c, flowNow(m.x, m.y), false, byRank); }
      continue;
    }
    const here = d(c);
    // the head's pace: one step in creepEvery ticks until creepNear cells from us, then full
    const creeping = L > P.creepNear && world.tick % P.creepEvery !== 0;
    // the front steps one cell past the head, and only when every rank is at its slot (while it creeps, a front member
    // closes only a gap of two to the head: the distance is to OUR army, which moves too)
    // (noWait: the front walks at its own pace and the ranks keep up as they can — Hardy's clump never paused)
    const goal = front.includes(c) ? Math.max(goalOf(c), P.noWait || (together && !creeping) ? L - 1 : creeping ? L + 1 : L) : slot(c);
    // a mate gives way (the two swap cells) when it belongs further back than this one or stands ahead of its own slot
    // (yieldAtSlot: also one AT its slot, and never a front member backwards — a clump lets its front through it)
    const givesWay = (o) => !patientOf.has(o.id) && !escortOf.has(o.id) && !(P.yieldAtSlot && front.includes(o)) && (OFF[lineKey(o)] > OFF[lineKey(c)] || d(o) < slot(o) || (P.yieldAtSlot && d(o) <= slot(o)));
    if (here > goal) { lineState.wanted.set(c.id, true); stepField(c, G, false, givesWay, lineState.prev.get(c.id)); }
    else if (P.backOff && here <= 1 && live(c, A) === 0 && c.role !== 'melee') stepField(c, G, true);
  }
}
/** Steps to a fixed cell (the centre flag), kept for the match. */
const stepsCache = new Map();
function flowSteps(o) { const k = idx(o.x, o.y); let f = stepsCache.get(k); if (!f) { f = steps([o]); stepsCache.set(k, f); } return f; }
// ---------- ghost: the recorded opponent of a replay ----------
// Every creep of his takes, each tick, one step toward the cell the record has it on after this tick (a path step when
// it fell two or more behind); a creep of ours on that cell refuses the step and the ghost catches up when it frees.
// Fatigue is not paid — the record already paid it. Fire and healing are by rule (as in `rush`): the recorded targets
// stood where OUR recorded army stood, and the live bot is somewhere else. A ghost that outlives its record stands
// where it ended and keeps fighting; a puller of his on a tower flag of his feeds the tower. What it measures is the
// opening and the approach — his route, tempo and formation are the real ones; the exchange is not.
const ghostMeta = { on: 0, off: 0, outlived: 0, ourOn: 0, ourOff: 0, ourDev: null, ourDevWhere: '' };
function ghost(mine, ours) {
  const t = world.tick;
  // our own creeps against OUR record: the first tick our build walks a cell the live one did not
  for (const o of ours) {
    const r = ghostPos.get(o.id), p = r && r[t - 1];
    if (!p) continue;
    ghostMeta.ourOn++;
    if (p.x !== o.x || p.y !== o.y) { ghostMeta.ourOff++; if (ghostMeta.ourDev === null) { ghostMeta.ourDev = t - 1; ghostMeta.ourDevWhere = `${o.id} at (${o.x},${o.y}) recorded (${p.x},${p.y})`; } }
  }
  const flags = world.objects.filter((o) => o.exists && o.kind === 'flag');
  for (const c of mine) {
    c.fatigue = 0;
    const r = ghostPos.get(c.id), prev = r && r[t - 1], p = r && r[t];
    if (prev) { ghostMeta.on++; if (prev.x !== c.x || prev.y !== c.y) ghostMeta.off++; } else ghostMeta.outlived++;
    act(c, mine, ours);
    if (c.role === 'puller') { const f = flags.find((x) => x.x === c.x && x.y === c.y); if (f) feed(c, f); }
    if (!p || (p.x === c.x && p.y === c.y)) continue;
    if (range(c, p) <= 1) moveTo(c, idx(p.x, p.y)); else stepDown(c, flowNow(p.x, p.y));
  }
}

// ---------- mirror: a second instance of the bot drives the enemy ----------
let mirrorLoop = null;
if (scenario === 'mirror') {
  const mod = await import(new URL('PainAndGainAdvanced.mjs?mirror', BUNDLE_DIR).href);
  mirrorLoop = Object.entries(mod).find(([k, v]) => k.startsWith('loop') && typeof v === 'function')?.[1];
  if (!mirrorLoop) throw new Error('mirror: no loop export in PainAndGainAdvanced.mjs');
}

function enemyTick() {
  const mine = creeps().filter((c) => c.owner === 1);
  const ours = creeps().filter((c) => c.owner === 0);
  if (scenario === 'none') return;
  beginMoves();
  if (scenario === 'ghost') ghost(mine, ours);
  else if (scenario === 'line') formation(mine, ours, LINE);
  else if (scenario === 'chase') formation(mine, ours, CHASE);
  else {
    pullers(mine);
    if (scenario === 'rush') rush(mine, ours);
    else if (scenario === 'farm') farm(mine, ours);
  }
  enemyTowers(mine, ours);
}

// ---------- run ----------
// the bot's console: every line into the log; a tick whose output holds a stack frame (`    at ...` — the mapped trace
// runWithSourceMapSupport prints for any throw, Kotlin's or the runtime's) counts as one error of that tick
const lines = [];
let sink = 'ours', tickErr = false, mirrorTickErr = false;
const take = (s) => {
  if (sink === 'mirror') { lines.push('[mirror] ' + s); if (/^\s+at /.test(s)) mirrorTickErr = true; return; }
  lines.push(s);
  if (/^\s+at /.test(s) || s.startsWith('loop error')) tickErr = true;
};
const origWrite = process.stdout.write.bind(process.stdout);
let buf = '';
process.stdout.write = (chunk) => {
  buf += typeof chunk === 'string' ? chunk : chunk.toString();
  let i;
  while ((i = buf.indexOf('\n')) >= 0) { take(buf.slice(0, i)); buf = buf.slice(i + 1); }
  return true;
};
const origLog = (...args) => origWrite(args.join(' ') + '\n');
console.log = (...args) => { for (const s of args.join(' ').split('\n')) take(s); };
console.error = console.log;
const bot = await import(BOT);

let loopErrors = 0, mirrorErrors = 0, firstError = null, firstMirrorError = null;
const t0 = Date.now();
let ended = '', endTick = 0, movedOurs = 0;
// the entry, as tools/stub/painandgain measures it: the first tick a fighter of each side stands within three of the
// other's, and the hits each side lost over the next 20/50/100 ticks — the fight's quality as numbers the outcome does
// not carry; with REPLAY the record's own entry is printed beside it
const entry = { contact: null, hits: [], deaths: [0, 0], alive: [0, 0] };
function entryTick(t, c0, c1) {
  entry.hits[t] = [c0.reduce((a, c) => a + c.hits, 0), c1.reduce((a, c) => a + c.hits, 0)];
  const f0 = c0.filter((c) => c.role !== 'puller'), f1 = c1.filter((c) => c.role !== 'puller');
  if (entry.contact === null && f0.some((a) => f1.some((b) => range(a, b) <= 3))) entry.contact = t;
}
// what each role of each side DID in the 100 ticks from contact, counted from the intents as tools/pga-replay.py `fight`
// counts a record's action log (a attack, r ranged, R mass, h heal beside, H heal from range), so a stand fight and a
// live one can be set side by side: `tools/pga-replay.py fight <id> <contact> <contact+100>`
const fightCount = [new Map(), new Map()];
const hitCount = [new Map(), new Map()];   // side -> the role its single-target attacks and shots went to, first 50 ticks
function fightTick(t) {
  if (entry.contact === null || t < entry.contact || t > entry.contact + 100) return;
  const roleOfId = (o) => o.id.replace(/^pg_player\d_/, '').replace(/_\d+$/, '');
  for (const c of creeps()) {
    const m = world.intents.get(c.id);
    if (!m) continue;
    if (t <= entry.contact + 50) for (const tg of [m.melee && m.melee.target, m.ranged && m.ranged.type === 'attack' && m.ranged.target]) {
      if (tg && tg.kind === 'creep') hitCount[c.owner].set(roleOfId(tg), (hitCount[c.owner].get(roleOfId(tg)) || 0) + 1);
    }
    const key = c.id.replace(/^pg_player\d_/, '').replace(/_\d+$/, '');
    const cnt = fightCount[c.owner].get(key) || {};
    const add = (k) => { cnt[k] = (cnt[k] || 0) + 1; };
    if (m.melee) add('a');
    if (m.ranged) add(m.ranged.type === 'attack' ? 'r' : m.ranged.type === 'mass' ? 'R' : 'H');
    if (m.heal) add('h');
    fightCount[c.owner].set(key, cnt);
  }
}
const fightLine = (p) => [...fightCount[p]].sort().map(([k, v]) => `${k} ${Object.entries(v).map(([a, n]) => `${a}=${n}`).join(' ')}`).join(', ') || '-';
const lostAfter = (arr, t0, dd, i) => { const a = arr[Math.max(0, t0 - 1)] || arr[t0], b = arr[Math.min(t0 + dd, arr.length - 1)]; return a && b ? Math.round(a[i] - b[i]) : '?'; };
const entryLine = (arr, c) => (c === null ? 'no contact' : `contact t=${c} hits lost ours/his +20 ${lostAfter(arr, c, 20, 0)}/${lostAfter(arr, c, 20, 1)} +50 ${lostAfter(arr, c, 50, 0)}/${lostAfter(arr, c, 50, 1)} +100 ${lostAfter(arr, c, 100, 0)}/${lostAfter(arr, c, 100, 1)}`);
const side = (o) => (o === 0 ? '+' : o === 1 ? '-' : '0');
const sum = (cs) => { const m = {}; for (const c of cs) { const s = c.summary(); m[s] = (m[s] || 0) + 1; } return Object.entries(m).map(([k, v]) => `${k}x${v}`).join(' '); };
function status(t) {
  const c0 = creeps().filter((c) => c.owner === 0), c1 = creeps().filter((c) => c.owner === 1);
  const flags = world.objects.filter((o) => o.kind === 'flag').map((f) => side(f.owner)).join('');
  const towers = world.objects.filter((o) => o.kind === 'tower').map((o) => `${o.x}:${o.y}${side(o.owner)}${o.store.energy}`).join(' ');
  const fx = (p) => { const c = creeps().find((x) => x.owner === p); return c && c.effects.length ? c.effects.map((e) => `${e.effectType.replace(/^eff_|_modifier$/g, '')}:${e.data.multiplier ?? e.data.offset}`).join(',') : '-'; };
  const s = `stub t=${t} score=${world.score[0]}/${world.score[1]} flags=${flags} towers=${towers} moved=${movedOurs} hits=${c0.reduce((a, c) => a + c.hits, 0)}/${c1.reduce((a, c) => a + c.hits, 0)} fx=${fx(0)}|${fx(1)} ours(${c0.length}): ${sum(c0)} | enemy(${c1.length}): ${sum(c1)} errors=${loopErrors}${mirrorLoop ? '/' + mirrorErrors : ''}`;
  movedOurs = 0;
  return s;
}
for (let t = 1; t <= ticks; t++) {
  world.perspective = 0;
  if (!process.env.NOCLOCK) world.tickStartNs = process.hrtime.bigint();
  tickErr = false;
  const mark = lines.length;
  try { bot.loop(); } catch (e) { take('loop error (uncaught): ' + (e && e.stack || e)); tickErr = true; }
  if (tickErr) { loopErrors++; if (!firstError) firstError = { t, text: lines.slice(mark).filter((l) => l.trim()).slice(0, 12).join('\n') }; }
  if (mirrorLoop) {
    world.perspective = 1; sink = 'mirror'; mirrorTickErr = false;
    if (!process.env.NOCLOCK) world.tickStartNs = process.hrtime.bigint();
    const m2 = lines.length;
    try { mirrorLoop(); } catch (e) { take('loop error (uncaught): ' + (e && e.stack || e)); mirrorTickErr = true; }
    if (mirrorTickErr) { mirrorErrors++; if (!firstMirrorError) firstMirrorError = { t, text: lines.slice(m2).slice(0, 12).join('\n') }; }
    sink = 'ours'; world.perspective = 0;
  } else enemyTick();
  fightTick(t);
  step(Resource);
  const c0 = creeps().filter((c) => c.owner === 0), c1 = creeps().filter((c) => c.owner === 1);
  movedOurs += c0.filter((c) => c.moved === t).length;
  entryTick(t, c0, c1);
  if (TRACE && t >= TRACE[0] && t <= TRACE[1]) origLog(`trace t=${t} ${c0.map((c) => `${c.id.replace(/^pg_player\d_/, '')}@${c.x},${c.y}${c.fatigue ? '/' + c.fatigue : ''}${c.store.energy ? 'e' + c.store.energy : ''}`).join(' ')}`);
  if (t % 100 === 0 || t === 1) { const s = status(t); lines.push(s); if (t % STATUS === 0) origLog(s); }
  endTick = t;
  if (c0.length === 0) { ended = `our army destroyed at t=${t}`; break; }
  if (c1.length === 0) { ended = `enemy army destroyed at t=${t}`; break; }
  const remaining = world.ticksLimit - t;
  if (Math.abs(world.score[0] - world.score[1]) > world.maxScorePerTick * remaining) { ended = `${remaining === 0 ? 'time limit' : 'unreachable lead'} at t=${t}`; break; }
}
if (!ended) ended = ticks >= world.ticksLimit ? `time limit at t=${endTick}` : `cap of ${ticks} ticks`;
if (endTick % 100 !== 0) lines.push(status(endTick));

const outDir = fileURLToPath(new URL('out/', import.meta.url));
mkdirSync(outDir, { recursive: true });
const log = `${outDir}run-${process.env.LOGTAG || ''}${[scenario, ...MODS].join('+')}${REPLAY ? '-' + String(REPLAY.meta.gameId || 'replay').slice(0, 8) : ''}.log`;
const st = world.stats;
const c0 = creeps().filter((c) => c.owner === 0).length, c1 = creeps().filter((c) => c.owner === 1).length;
const fxLine = (p) => Object.entries(st.effectsMax[p]).map(([k, v]) => `${k.replace(/^eff_|_modifier$/g, '')}=${v}@${st.effectTicks[p][k]}t`).join(' ') || '-';
const report = [
  `towers: ours shots=${st.towerShots[0]} dmg=${Math.round(st.towerDamage[0])} heals=${st.towerHeals[0]} fed=${st.towerFed[0]} | enemy shots=${st.towerShots[1]} dmg=${Math.round(st.towerDamage[1])} heals=${st.towerHeals[1]} fed=${st.towerFed[1]}`,
  `effects (strongest value @ ticks held): ours ${fxLine(0)} | enemy ${fxLine(1)}`,
  `hits loss taken: ours=${st.hitsLoss[0]} enemy=${st.hitsLoss[1]}; moves under a fatigue multiplier: ours=${st.fatigueMoves[0]} (+${st.fatigueExtra[0]} fatigue) enemy=${st.fatigueMoves[1]} (+${st.fatigueExtra[1]})`,
  `entry: ${entryLine(entry.hits, entry.contact)}`,
  `fight from contact +100: ours ${fightLine(0)} | his ${fightLine(1)}`,
  `targets of single attacks and shots, contact +50: ours ${[...hitCount[0]].sort((a, b) => b[1] - a[1]).map(([k, v]) => `${k}=${v}`).join(' ') || '-'} | his ${[...hitCount[1]].sort((a, b) => b[1] - a[1]).map(([k, v]) => `${k}=${v}`).join(' ') || '-'}`,
  ...(REPLAY ? [
    `record ${REPLAY.meta.gameId || ''}: ${rec.winner} won in ${rec.ticks} ticks, deaths ours=${rec.deaths[0]} his=${rec.deaths[1]}, alive at the end ${rec.end[0]}:${rec.end[1]}; we are ${START}`,
    `record entry: ${entryLine(rec.hits, rec.contact)}`,
  ] : []),
  ...(scenario === 'ghost' ? [`ghost: his creeps off their recorded cell ${ghostMeta.off} of ${ghostMeta.on} creep-ticks, outlived the record ${ghostMeta.outlived}; OURS off our recorded cell ${ghostMeta.ourOff} of ${ghostMeta.ourOn}, first at t=${ghostMeta.ourDev} (${ghostMeta.ourDevWhere})`] : []),
  `flags at the end: ours [${world.objects.filter((f) => f.kind === 'flag' && f.owner === 0).map((f) => f.id.replace('pg_flag_', '')).join(',')}] enemy [${world.objects.filter((f) => f.kind === 'flag' && f.owner === 1).map((f) => f.id.replace('pg_flag_', '')).join(',')}]`,
];
writeFileSync(log, lines.join('\n') + '\n\n=== STUB ===\n' + report.join('\n') + '\n\n=== EVENTS ===\n' + world.events.join('\n') + '\n');
origLog(`done: ${ended} score=${world.score[0]}/${world.score[1]} alive=${c0}/${c1} errors=${loopErrors} end=${endTick} tw=${st.towerShots[0]}/${st.towerShots[1]} time=${((Date.now() - t0) / 1000).toFixed(1)}s log=${log}`);
for (const l of report) origLog(l);
if (mirrorLoop) origLog(`mirror errors=${mirrorErrors}${firstMirrorError ? ` first at t=${firstMirrorError.t}:\n${firstMirrorError.text}` : ''}`);
if (firstError) origLog(`first error at t=${firstError.t}:\n${firstError.text}`);
