// Offline runner for Pain and Gain ADVANCED: a live map (MAP=map-liveN.txt, written by mapfrom.py) with the arena's
// fixed layout — eleven flags, four towers linked to four of them, eight 2500 containers, sixteen creeps a side — and a
// scripted enemy. Usage (see README.md):
//   node --import ./register.mjs run.mjs <ticks> none|rush|farm|mirror
//   env: MAP=<file> (default map-live1.txt)  START=p1|p2 (the side our bot drives, default p1)  LOGTAG=<prefix>
//        BOT=<bundle url>  NOCLOCK=1 (getCpuTime() answers 0 — the run is deterministic on any machine)
//        TRACE=<t0>-<t1> (our creeps' cells and fatigue every tick of that window, to stdout)  STATUS=<n> (a status line
//        to stdout every n ticks, default 500; the log gets one every 100)
// The bot always drives the START side (owner 0); the other side (owner 1) is the enemy script, or with `mirror` a
// second, fully separate instance of the same bundle (hooks.mjs gives it its own module graph). Logs go to ./out/.
import { writeFileSync, mkdirSync, readFileSync } from 'node:fs';
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
const BUNDLE_DIR = new URL('../../../build/js/packages/screeps-kotlin-arena-starter/kotlin/screeps-kotlin-arena-starter/season4/painandgainadvanced/', import.meta.url);
const BOT = process.env.BOT || new URL('PainAndGainAdvanced.export.mjs', BUNDLE_DIR).href;
const MAP = process.env.MAP || fileURLToPath(new URL('map-live1.txt', import.meta.url));
const START = process.env.START || 'p1';
if (START !== 'p1' && START !== 'p2') throw new Error(`START must be p1 or p2, got ${START}`);
const ticks = Math.min(parseInt(process.argv[2] || String(TICKS_LIMIT), 10), TICKS_LIMIT);
const scenario = process.argv[3] || 'none';
if (!['none', 'rush', 'farm', 'mirror'].includes(scenario)) throw new Error(`unknown scenario ${scenario}`);
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

function build() {
  const rows = readFileSync(MAP, 'utf8').split('\n').filter((r) => r.length > 0);
  if (rows.length !== 100) throw new Error(`map must have 100 rows, got ${rows.length}`);
  rows.forEach((r, y) => { if (r.length !== 100) throw new Error(`row ${y} has ${r.length} chars`); for (let x = 0; x < 100; x++) world.terrain[idx(x, y)] = r[x] === '#' ? 1 : r[x] === '~' ? 2 : 0; });
  for (const [id, x, y, flagId] of STRUCTURES) {
    const o = flagId ? new StructureTower(x, y, undefined) : new StructureContainer(x, y, 2500, 2500);
    o.id = id;
    if (flagId) o.flagId = flagId;
    world.objects.push(o);
  }
  for (const [id, x, y, type, score] of FLAGS) { const f = new ScoreFlag(x, y, type, score); f.id = id; world.objects.push(f); }
  // player 1's creeps first, as the live object list has them; owner 0 is the side our bot drives
  for (const p of [1, 2]) {
    const owner = (START === 'p1') === (p === 1) ? 0 : 1;
    for (const [role, x, y, b] of ARMY) {
      const c = p === 1 ? new Creep(x, y, owner, b) : new Creep(98 - x, 98 - y, owner, b);
      c.id = `pg_player${p}_${role}`;
      c.role = role.startsWith('puller') ? 'puller' : b.includes(H) ? 'healer' : b.includes(R) ? 'ranged' : 'melee';
      world.objects.push(c);
    }
  }
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
    const tw = world.objects.find((o) => o.exists && o.kind === 'tower' && o.flagId === f.id);
    const box = world.objects.filter((o) => o.exists && o.kind === 'container' && range(o, f) <= 1).sort((a, b) => b.store.energy - a.store.energy)[0];
    if (tw && tw.owner === p.owner && tw.store.energy < TOWER_CAPACITY && p.store.energy > 0) p.transfer(tw, 'energy');
    else if (box && box.store.energy > 0 && p.store.free() >= CARRY_CAPACITY) p.withdraw(box, 'energy');
  }
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
  pullers(mine);
  if (scenario === 'rush') rush(mine, ours);
  else if (scenario === 'farm') farm(mine, ours);
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
  step(Resource);
  const c0 = creeps().filter((c) => c.owner === 0), c1 = creeps().filter((c) => c.owner === 1);
  movedOurs += c0.filter((c) => c.moved === t).length;
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
const log = `${outDir}run-${process.env.LOGTAG || ''}${scenario}.log`;
const st = world.stats;
const c0 = creeps().filter((c) => c.owner === 0).length, c1 = creeps().filter((c) => c.owner === 1).length;
const fxLine = (p) => Object.entries(st.effectsMax[p]).map(([k, v]) => `${k.replace(/^eff_|_modifier$/g, '')}=${v}@${st.effectTicks[p][k]}t`).join(' ') || '-';
const report = [
  `towers: ours shots=${st.towerShots[0]} dmg=${Math.round(st.towerDamage[0])} heals=${st.towerHeals[0]} fed=${st.towerFed[0]} | enemy shots=${st.towerShots[1]} dmg=${Math.round(st.towerDamage[1])} heals=${st.towerHeals[1]} fed=${st.towerFed[1]}`,
  `effects (strongest value @ ticks held): ours ${fxLine(0)} | enemy ${fxLine(1)}`,
  `hits loss taken: ours=${st.hitsLoss[0]} enemy=${st.hitsLoss[1]}; moves under a fatigue multiplier: ours=${st.fatigueMoves[0]} (+${st.fatigueExtra[0]} fatigue) enemy=${st.fatigueMoves[1]} (+${st.fatigueExtra[1]})`,
  `flags at the end: ours [${world.objects.filter((f) => f.kind === 'flag' && f.owner === 0).map((f) => f.id.replace('pg_flag_', '')).join(',')}] enemy [${world.objects.filter((f) => f.kind === 'flag' && f.owner === 1).map((f) => f.id.replace('pg_flag_', '')).join(',')}]`,
];
writeFileSync(log, lines.join('\n') + '\n\n=== STUB ===\n' + report.join('\n') + '\n\n=== EVENTS ===\n' + world.events.join('\n') + '\n');
origLog(`done: ${ended} score=${world.score[0]}/${world.score[1]} alive=${c0}/${c1} errors=${loopErrors} end=${endTick} tw=${st.towerShots[0]}/${st.towerShots[1]} time=${((Date.now() - t0) / 1000).toFixed(1)}s log=${log}`);
for (const l of report) origLog(l);
if (mirrorLoop) origLog(`mirror errors=${mirrorErrors}${firstMirrorError ? ` first at t=${firstMirrorError.t}:\n${firstMirrorError.text}` : ''}`);
if (firstError) origLog(`first error at t=${firstError.t}:\n${firstError.text}`);
