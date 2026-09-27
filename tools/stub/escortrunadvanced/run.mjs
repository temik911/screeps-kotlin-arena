// Offline runner for Escort Run ADVANCED: the live layout on a live terrain + a scripted enemy, or a second bot.
// A copy of tools/stub/escortrun/run.mjs (the basic level) adapted to the advanced level — copied, not shared, by the
// repository's rule. Usage (README.md):
//   node --import ./register.mjs run.mjs [ticks=5000] none|race|hunt|blob|siege[+harvest]
//   env: MAP=<map-*.txt, a 100-row terrain dump> (default map-6ab9267a.txt) or GAME=<a stored match's game.json>;
//   START=top (or match2, the basic stub's word) — we are the TOP player (spawn (9,9)); default: the bottom one (9,90);
//   LOGTAG=<prefix> BOT=<bundle url> PULL_MODEL=engine|off TRACE=from-to
//   bot against bot: BOT2=<bundle url of the enemy — a SEPARATE copy of a build, one module graph per side>,
//   ECON2/ECON2_FROM/ECON2_COST (the enemy bot's income, as in the basic stub), PERSONA/PERSONA2 (globalThis.ER_PERSONA)
// Win (measured live, 27.09.2026): all three of a side's escorts on its own flags, or one enemy escort dead. Both at
// once — a draw. 5000 ticks — a draw.
import { writeFileSync, mkdirSync, readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { world, process as step, idx, inBounds, range, creeps, live, creepAt, terrainAt } from './world.mjs';
import { Resource } from './game/prototypes/resource.mjs';
import { Flag } from './game/prototypes/flag.mjs';
import { Source } from './game/prototypes/source.mjs';
import { StructureSpawn } from './game/prototypes/spawn.mjs';
import { StructureContainer } from './game/prototypes/container.mjs';
import { EscortCreep } from './arena/season_4/escort_run/advanced/prototypes.mjs';
import { StructureRampart } from './game/prototypes/rampart.mjs';
import { StructureWall } from './game/prototypes/wall.mjs';
import { CostMatrix, searchPath } from './game/path-finder.mjs';
import { getDirection } from './game/utils.mjs';

// the bundle of THIS worktree's build (see the parallel-sessions rules: the stub tests what the worktree built)
const BOT = process.env.BOT || new URL('../../../build/js/packages/screeps-kotlin-arena-starter/kotlin/screeps-kotlin-arena-starter/season4/escortrunadvanced/EscortRunAdvanced.export.mjs', import.meta.url).href;
const DEFAULT_MAP = 'map-6ab9267a.txt'; // v3 against けろびー#11 (27.09.2026); we were the bottom player there
const GAME = process.env.GAME; // a stored game.json: terrain string y*100+x, '1' wall, '2' swamp
const MAP = GAME ? null : (process.env.MAP || DEFAULT_MAP); // '#' wall, '~' swamp, anything else plain
const BOT2 = process.env.BOT2;
const PERSONA = process.env.PERSONA || 'main', PERSONA2 = process.env.PERSONA2 || 'main';
const ticks = parseInt(process.argv[2] || '5000', 10);
const TRACE = process.env.TRACE ? process.env.TRACE.split('-').map((v) => parseInt(v, 10)) : null;
const scenario = (process.argv[3] || 'none').split('+');
const has = (s) => scenario.includes(s);
const KNOWN = new Set(['none', 'race', 'hunt', 'blob', 'siege', 'harvest', 'offline']);
for (const s of scenario) if (!KNOWN.has(s)) { console.error(`run: unknown scenario '${s}' (known: ${[...KNOWN].join(' ')})`); process.exit(2); }

const M = 'move', A = 'attack', R = 'ranged_attack', T = 'tough';

// ---------- the live layout (the probe of v1-v4, 27.09.2026; docs/escort-run-advanced.md) ----------
// the escorts: MOVE first in every segment of the body (`M TTTT`x10, `M T8`x5, `M T14`x3), so the first hundred of
// damage kills a MOVE — the order is the live one, and front-to-back damage makes it matter
const seg = (n, tough) => [].concat(...Array.from({ length: n }, () => [M, ...Array(tough).fill(T)]));
const BODY = { M10T40: seg(10, 4), M5T40: seg(5, 8), M3T42: seg(3, 14) };
// the escorts' ids are the live ones (the top player's 4, 6, 18, the bottom's 14, 16, 28), so a stub log reads like a
// live one; every other object gets an id from 100 up (the first live creep is 119/120)
const BOTTOM = { name: 'bottom', spawn: [9, 90], source: [2, 97], flags: [[95, 95], [94, 72], [62, 94]],
  escorts: [['M3T42', 7, 91, 14], ['M10T40', 7, 92, 16], ['M5T40', 8, 92, 28]] };
const TOP = { name: 'top', spawn: [9, 9], source: [2, 2], flags: [[95, 4], [94, 27], [62, 5]],
  escorts: [['M10T40', 7, 7, 4], ['M3T42', 7, 8, 6], ['M5T40', 8, 7, 18]] };
const SPAWN_START = 500;
const RAMPART_HITS = 10000;

function buildLiveMap(path) {
  const rows = readFileSync(path, 'utf8').split('\n').filter((r) => r.length > 0);
  if (rows.length !== 100) throw new Error(`map must have 100 rows, got ${rows.length}`);
  rows.forEach((r, y) => { if (r.length !== 100) throw new Error(`row ${y} has ${r.length} chars`); for (let x = 0; x < 100; x++) world.terrain[idx(x, y)] = r[x] === '#' ? 1 : r[x] === '~' ? 2 : 0; });
}
function buildGameMap(path) {
  const g = JSON.parse(readFileSync(path, 'utf8'));
  const t = ((g.game || {}).game || {}).terrain;
  if (!t || t.length !== 10000) throw new Error(`${path}: no 10000-char terrain`);
  for (let y = 0; y < 100; y++) for (let x = 0; x < 100; x++) { const ch = t[y * 100 + x]; world.terrain[idx(x, y)] = ch === '1' ? 1 : ch === '2' ? 2 : 0; }
}
const mapPath = MAP && (existsSync(MAP) ? MAP : fileURLToPath(new URL(MAP, import.meta.url)));
if (GAME) buildGameMap(GAME); else buildLiveMap(mapPath);
const mapName = GAME ? GAME : MAP;

world.nextId = 100;
function fixCell(x, y) { if (terrainAt(x, y) === 1) { world.terrain[idx(x, y)] = 0; console.error(`run: ${mapName}: (${x},${y}) is a terrain wall under the fixed layout — made plain`); } }
function place(side, owner) {
  const sp = new StructureSpawn(side.spawn[0], side.spawn[1], owner, SPAWN_START); sp.econ = true; world.objects.push(sp);
  // 25 ramparts of the owner, 5x5 round the spawn, the spawn cell included (live: a rampart over the spawn)
  for (let dx = -2; dx <= 2; dx++) for (let dy = -2; dy <= 2; dy++) {
    fixCell(side.spawn[0] + dx, side.spawn[1] + dy);
    world.objects.push(new StructureRampart(side.spawn[0] + dx, side.spawn[1] + dy, owner, RAMPART_HITS));
  }
  // the live terrain has a WALL under every source (game.json of 6ab9267a, 6ab92799: '1' at (2,2) and (2,97)); kept
  const src = new Source(side.source[0], side.source[1], 1000, 1000); world.objects.push(src);
  const flags = side.flags.map(([x, y]) => { fixCell(x, y); const f = new Flag(x, y); f.owner = owner; world.objects.push(f); return f; });
  return { side, owner, sp, src, flags, escorts: [] };
}
const weTop = process.env.START === 'top' || process.env.START === 'match2';
const ours = place(weTop ? TOP : BOTTOM, 0);
const theirs = place(weTop ? BOTTOM : TOP, 1);
// the six escorts in the order of their live ids
const escortSpecs = [...ours.side.escorts.map((e) => [...e, ours]), ...theirs.side.escorts.map((e) => [...e, theirs])].sort((a, b) => a[3] - b[3]);
for (const [body, x, y, id, who] of escortSpecs) {
  fixCell(x, y);
  const e = new EscortCreep(x, y, who.owner, BODY[body]);
  e.id = String(id); e.bodyName = body;
  world.objects.push(e); who.escorts.push(e);
}
for (const who of [ours, theirs]) who.escorts.sort((a, b) => parseInt(a.id, 10) - parseInt(b.id, 10));
// the far side and the walls: two sources and two 2500-containers at the right edge; the two corridors to the (62,y)
// flags walled at y=9 and y=90 (x 43..61, 3000 each) and the left-edge pass between the bases walled at x=6 (y 45..54,
// 5000 each) — walls are structures on passable terrain, a broken one leaves its cell open
world.objects.push(new Source(96, 24, 1000, 1000)); world.objects.push(new Source(96, 75, 1000, 1000));
world.objects.push(new StructureContainer(92, 49, 2500, 2500)); world.objects.push(new StructureContainer(92, 50, 2500, 2500));
for (let x = 43; x <= 61; x++) { world.objects.push(new StructureWall(x, 9, 3000)); world.objects.push(new StructureWall(x, 90, 3000)); }
for (let y = 45; y <= 54; y++) world.objects.push(new StructureWall(6, y, 5000));

// income: +1 a tick for both spawns (live); 'harvest' — the enemy economy as if it had a W5 harvester from tick 1
world.spawnRegen = [1, has('harvest') ? 11 : 1];
// bot against bot: ECON2=<energy a tick> gives the enemy BOT the income of an economy it does not play; ECON2_FROM=<tick>
// — the income starts there; ECON2_COST — what that economy cost at tick 1 (taken from the enemy spawn's 500)
const ECON2_FROM = parseInt(process.env.ECON2_FROM || '1', 10);
if (process.env.ECON2 && ECON2_FROM <= 1) world.spawnRegen[1] = parseFloat(process.env.ECON2);
if (process.env.ECON2_COST) theirs.sp.store.energy = Math.max(0, theirs.sp.store.energy - parseInt(process.env.ECON2_COST, 10));

// ---------- movement helpers of the scripted enemy ----------
// Distance fields (Dijkstra from the goal, plain 2 / swamp 10) instead of a search per creep per tick: the basic stub
// searched a path for every enemy creep every tick, which over 5000 ticks and a growing army costs minutes. A field
// knows terrain and structures, never a creep that can move; a creep steps to its best FREE neighbour. A creep without a
// live MOVE is a structure for as long as it lives: the first siege wave lost MOVEs in the one-cell pass at x=6, its
// cripple stood there for good, and every later wave queued behind it for 4000 ticks — no live player walks into that
// forever, so the field walks round it. Cached until a structure dies or a creep is crippled or dies.
const STRUCT = new Set(['spawn', 'extension', 'tower', 'wall', 'source']);
const immobile = (o) => o.exists && o.kind === 'creep' && !o.spawning && live(o, M) === 0;
let sigTick = -1, sig = '';
function structSig() {
  if (sigTick !== world.tick) {
    let n = 0; const still = [];
    for (const o of world.objects) { if (!o.exists) continue; if (STRUCT.has(o.kind) || o.kind === 'rampart') n++; else if (immobile(o)) still.push(idx(o.x, o.y)); }
    sig = `${n}:${still.join(',')}`; sigTick = world.tick;
  }
  return sig;
}
// the cost of ENTERING a cell for a creep of `owner`: -1 impassable; `rampartCost` > 0 makes the other side's ramparts
// passable at that cost (a siege breaks through them)
function cellCosts(owner, rampartCost) {
  const cost = new Int16Array(10000);
  for (let i = 0; i < 10000; i++) { const t = world.terrain[i]; cost[i] = t === 1 ? -1 : t === 2 ? 10 : 2; }
  for (const o of world.objects) {
    if (!o.exists) continue;
    if (STRUCT.has(o.kind) || immobile(o)) cost[idx(o.x, o.y)] = -1;
    else if (o.kind === 'rampart' && o.owner !== owner) { const i = idx(o.x, o.y); if (cost[i] >= 0) cost[i] = rampartCost > 0 ? rampartCost : -1; }
  }
  return cost;
}
const fields = new Map();
/** Distance to within `rng` of `target` for a creep of `owner`; when no cell within `rng` can be stood on, the ring widens. */
function field(target, rng, owner = 1, rampartCost = 0) {
  const key = `${target.x},${target.y},${rng},${owner},${rampartCost}`;
  const s = structSig();
  const hit = fields.get(key);
  if (hit && hit.sig === s) return hit.dist;
  const cost = cellCosts(owner, rampartCost);
  const dist = new Int32Array(10000).fill(-1);
  const heap = [];
  const push = (d, i) => { heap.push([d, i]); let k = heap.length - 1; while (k > 0) { const p = (k - 1) >> 1; if (heap[p][0] <= heap[k][0]) break; [heap[p], heap[k]] = [heap[k], heap[p]]; k = p; } };
  const pop = () => { const top = heap[0]; const last = heap.pop(); if (heap.length) { heap[0] = last; let k = 0; for (;;) { const l = 2 * k + 1, r = l + 1; let m = k; if (l < heap.length && heap[l][0] < heap[m][0]) m = l; if (r < heap.length && heap[r][0] < heap[m][0]) m = r; if (m === k) break; [heap[m], heap[k]] = [heap[k], heap[m]]; k = m; } } return top; };
  for (let rr = rng; rr <= rng + 6; rr++) {
    for (let dx = -rr; dx <= rr; dx++) for (let dy = -rr; dy <= rr; dy++) {
      const x = target.x + dx, y = target.y + dy;
      if (!inBounds(x, y) || cost[idx(x, y)] < 0) continue;
      dist[idx(x, y)] = 0; push(0, idx(x, y));
    }
    if (heap.length) break;
  }
  while (heap.length) {
    const [d, c] = pop();
    if (d !== dist[c]) continue;
    const cx = (c / 100) | 0, cy = c % 100;
    for (let dx = -1; dx <= 1; dx++) for (let dy = -1; dy <= 1; dy++) {
      if (!dx && !dy) continue;
      const nx = cx + dx, ny = cy + dy;
      if (!inBounds(nx, ny)) continue;
      const n = idx(nx, ny);
      if (cost[n] < 0) continue;
      const nd = d + cost[c];
      if (dist[n] < 0 || nd < dist[n]) { dist[n] = nd; push(nd, n); }
    }
  }
  fields.set(key, { sig: s, dist });
  return dist;
}
/** The best free neighbour down a field (null: arrived, or nothing free is closer). `allow` — creeps not in the way. */
function fieldStep(c, dist, allow = []) {
  const here = dist[idx(c.x, c.y)];
  if (here === 0) return null;
  let best = null, bd = here < 0 ? Infinity : here;
  for (let dx = -1; dx <= 1; dx++) for (let dy = -1; dy <= 1; dy++) {
    if (!dx && !dy) continue;
    const x = c.x + dx, y = c.y + dy;
    if (!inBounds(x, y)) continue;
    const d = dist[idx(x, y)];
    if (d < 0 || d >= bd) continue;
    const o = creepAt(x, y);
    if (o && !allow.includes(o)) continue;
    bd = d; best = { x, y };
  }
  return best;
}
const moveTo = (c, s) => { if (s) c.move(getDirection(s.x - c.x, s.y - c.y)); };
function fieldMove(c, target, rng, rampartCost = 0) {
  if (range(c, target) <= rng) return;
  moveTo(c, fieldStep(c, field(target, rng, c.owner, rampartCost)));
}
// a short chase (a moving target): the stub's searchPath round structures and creeps, capped
function structMatrix() { const cm = new CostMatrix(); for (const o of world.objects) if (o.exists && (STRUCT.has(o.kind) || (o.kind === 'rampart' && o.owner === 0))) cm.set(o.x, o.y, 255); return cm; }
function chase(c, target, stop) {
  if (range(c, target) <= stop) return;
  const cm = structMatrix();
  for (const o of creeps()) if (!o.spawning && o !== c && !(o.x === target.x && o.y === target.y)) cm.set(o.x, o.y, 255);
  moveTo(c, searchPath(c, { pos: target, range: stop }, { costMatrix: cm, maxOps: 4000 }).path[0]);
}
function stepAway(c, from) {
  const cm = structMatrix();
  for (const o of creeps()) if (!o.spawning && o !== c) cm.set(o.x, o.y, 255);
  moveTo(c, searchPath(c, from.map((f) => ({ pos: f, range: 4 })), { costMatrix: cm, flee: true, maxOps: 2000 }).path[0]);
}
const onRampart = (o) => world.objects.some((r) => r.exists && r.kind === 'rampart' && r.x === o.x && r.y === o.y);
const armed = (o) => live(o, R) + live(o, A) + live(o, 'heal') > 0;
function fireAt(c, targets) {
  const inRange = targets.filter((o) => range(c, o) <= 3);
  if (live(c, R) > 0 && inRange.length) {
    const esc = inRange.find((o) => o.escort);
    const arm = inRange.filter((o) => live(o, R) + live(o, A) > 0);
    c.rangedAttack(arm.length ? arm.sort((a, b) => a.hits - b.hits)[0] : esc || inRange.sort((a, b) => a.hits - b.hits)[0]);
  }
  if (live(c, A) > 0) {
    const adj = inRange.filter((o) => range(c, o) <= 1);
    if (adj.length) { const esc = adj.find((o) => o.escort); c.attack(esc || adj.sort((a, b) => a.hits - b.hits)[0]); }
  }
}

// ---------- the scripted enemy ----------
// 'race'  — Hardy#1 (6ab9255d, all three on his flags at 568): the escorts walk to the three flags from tick 1, each
//           towed by a pure-MOVE M5 puller as soon as one is free; a puller is ordered whenever there are fewer than
//           escorts still walking and 250 energy (his M5s were born at 1, 16, 251 and 501). The train is the basic
//           stub's: the puller stands on the escort's next cell, pulls, and both step when the puller is rested; within
//           two of the flag the escort walks the rest alone. Flags go to escorts by the assignment whose slowest
//           arrival (field distance x the escort's plain period) is the soonest.
// 'hunt'  — 76561198870429455#3 (6ab92172): M2A1 hunters (180 energy, 300 hits) whenever affordable, each for our
//           nearest escort, hitting whatever of ours stands beside it; his escorts stay home.
// 'blob'  — けろびー#11 (6ab9267a) held the centre with T3M8R5; that body is 1180 energy and one spawn holds 1000 (he
//           had an extension), so the blob is M5R5 (1000). The fighters hold (50,52) and fight whatever of ours comes
//           within five of them or of the point, kiting our armed creeps at two; his escorts stay home.
// 'siege' — M5A5 (650) gather five cells out of his block; every five gathered go as a wave for our nearest escort
//           through our ramparts: the escort when beside it (a hit on it goes into its rampart), else our armed creep
//           beside them standing off a rampart, else the rampart on their way. The next five gather again (a first
//           version sent every newborn after the first wave alone, and the stub measured a trickle, not a siege).
// 'harvest' — the enemy spawn gets +11 a tick instead of +1 (a W5 economy from tick 1), with any of the above.
const PULLER = Array(5).fill(M);
const HUNTER = [M, M, A];
const BLOBBER = [M, M, M, M, M, R, R, R, R, R];
const SIEGER = [M, M, M, M, M, A, A, A, A, A];
const BLOB_AT = { x: 50, y: 52 };
const SIEGE_GO = 5;
const roleOf = new Map();
const pullerOf = new Map(); // escort id -> puller
let flagOf = null; // escort id -> flag
const wave = new Set(); // the siegers that have gone; the rest gather until they are SIEGE_GO again
const isPuller = (c) => !c.escort && c.body.length >= 3 && c.body.every((p) => p.type === M);
const onFlag = (e, flags) => flags.some((f) => f.x === e.x && f.y === e.y);
const periodPlain = (e) => Math.ceil(2 * e.body.filter((p) => p.type !== M).length / Math.max(1, 2 * live(e, M)));

function assignFlags() {
  const es = theirs.escorts, fs = theirs.flags;
  const perms = [[0, 1, 2], [0, 2, 1], [1, 0, 2], [1, 2, 0], [2, 0, 1], [2, 1, 0]];
  let best = null, bestKey = null;
  for (const p of perms) {
    const eta = es.map((e, i) => { const d = field(fs[p[i]], 0, 1)[idx(e.x, e.y)]; return d < 0 ? 1e9 : d / 2 * periodPlain(e); });
    const k = [Math.max(...eta), eta.reduce((a, b) => a + b, 0)];
    if (!best || k[0] < bestKey[0] || (k[0] === bestKey[0] && k[1] < bestKey[1])) { best = p; bestKey = k; }
  }
  flagOf = new Map(es.map((e, i) => [e.id, fs[best[i]]]));
  world.events.push(`t=${world.tick} enemy race: ${es.map((e) => `${e.bodyName}->(${flagOf.get(e.id).x},${flagOf.get(e.id).y})`).join(' ')} eta=${Math.round(bestKey[0])}`);
}
function order(body, role) {
  const r = theirs.sp.spawnCreep(body);
  if (r.object) { roleOf.set(r.object.id, role); world.events.push(`t=${world.tick} enemy orders ${r.object.summary()} as ${role}`); return true; }
  return false;
}
function runRace(mine) {
  if (!flagOf) assignFlags();
  const walking = theirs.escorts.filter((e) => e.exists && !(e.x === flagOf.get(e.id).x && e.y === flagOf.get(e.id).y));
  // within two of its flag the escort walks alone (the basic stub's racer parked its puller ON the flag and stalled)
  const towed = walking.filter((e) => range(e, flagOf.get(e.id)) > 2);
  const pullers = mine.filter((c) => roleOf.get(c.id) === 'puller');
  // release the pullers of escorts that arrived, died or walk the last two cells, then give free pullers to the slowest
  // escorts still towed
  for (const [eid, p] of [...pullerOf]) if (!p.exists || !towed.some((e) => e.id === eid)) pullerOf.delete(eid);
  const busy = new Set([...pullerOf.values()].map((p) => p.id));
  const eta = (e) => field(flagOf.get(e.id), 0, 1)[idx(e.x, e.y)] * periodPlain(e);
  for (const e of [...towed].sort((a, b) => eta(b) - eta(a))) {
    if (pullerOf.has(e.id)) continue;
    const free = pullers.filter((p) => !p.spawning && !busy.has(p.id)).sort((a, b) => range(a, e) - range(b, e))[0];
    if (free) { pullerOf.set(e.id, free); busy.add(free.id); }
  }
  for (const e of walking) {
    const flag = flagOf.get(e.id);
    const dist = field(flag, 0, 1);
    const p = pullerOf.get(e.id);
    if (p) {
      // the World scheme: the puller stands on the escort's next cell (the puller is not in the way), pulls every tick,
      // and both move when the puller is rested; the escort waits for a puller within three cells
      const next = fieldStep(e, dist, [p]);
      if (!next) continue;
      if (p.x === next.x && p.y === next.y) {
        p.pull(e);
        if (e.fatigue === 0 && p.fatigue === 0) {
          const pn = fieldStep(p, dist);
          if (pn) { moveTo(p, pn); e.move(getDirection(p.x - e.x, p.y - e.y)); }
        }
      } else {
        chase(p, next, 0);
        if (range(p, e) > 3 && e.fatigue === 0) moveTo(e, next);
      }
    } else if (e.fatigue === 0) moveTo(e, fieldStep(e, dist));
  }
  // an idle puller stands where it is, unless it stands within one of a flag — there it is in an escort's way, and it
  // walks back toward its spawn
  for (const p of pullers) if (!p.spawning && !busy.has(p.id) && theirs.flags.some((f) => range(p, f) <= 1)) fieldMove(p, theirs.sp, 3);
}
function nearestOf(c, list) { return list.slice().sort((a, b) => range(c, a) - range(c, b))[0]; }
function enemyTick() {
  const mine = creeps().filter((c) => c.owner === 1);
  const oursC = creeps().filter((c) => c.owner === 0);
  const ourEscorts = ours.escorts.filter((e) => e.exists);
  // orders: the race's pullers first, then the fighters of the scenario
  if (!theirs.sp.spawning) {
    const e = theirs.sp.store.energy;
    const walking = flagOf ? theirs.escorts.filter((x) => x.exists && !onFlag(x, theirs.flags)).length : 3;
    const pullers = mine.filter((c) => roleOf.get(c.id) === 'puller').length;
    if (has('race') && pullers < walking) { if (e >= 250) order(PULLER, 'puller'); }
    else if (has('siege') && e >= 650) order(SIEGER, 'siege');
    else if (has('blob') && e >= 1000) order(BLOBBER, 'blob');
    else if (has('hunt') && e >= 180) order(HUNTER, 'hunt');
  }
  if (has('race')) runRace(mine);
  const siegers = mine.filter((c) => roleOf.get(c.id) === 'siege' && !c.spawning);
  const gathered = siegers.filter((c) => !wave.has(c.id));
  if (gathered.length >= SIEGE_GO) { for (const c of gathered) wave.add(c.id); world.events.push(`t=${world.tick} enemy siege wave of ${gathered.length} goes`); }
  const rally = { x: theirs.sp.x + 5, y: theirs.sp.y + (theirs.sp.y < 50 ? 5 : -5) };
  for (const c of mine) {
    if (c.spawning || c.escort) continue;
    const role = roleOf.get(c.id);
    if (role === 'hunt') {
      const adj = oursC.filter((o) => range(c, o) <= 1);
      if (adj.length) c.attack(adj.find((o) => o.escort) || adj.sort((a, b) => a.hits - b.hits)[0]);
      const tgt = nearestOf(c, ourEscorts);
      if (tgt) fieldMove(c, tgt, 1);
    } else if (role === 'blob') {
      fireAt(c, oursC);
      const near = oursC.filter((o) => (range(o, c) <= 5 || range(o, BLOB_AT) <= 5) && range(o, BLOB_AT) <= 8);
      const close = oursC.filter((o) => live(o, R) + live(o, A) > 0 && range(c, o) <= 2);
      if (close.length) stepAway(c, close);
      else if (near.length) { const t = nearestOf(c, near); if (range(c, t) > 3) chase(c, t, 3); }
      else fieldMove(c, BLOB_AT, 2);
    } else if (role === 'siege') {
      const tgt = nearestOf(c, ourEscorts);
      const adjArmed = oursC.filter((o) => range(c, o) <= 1 && armed(o) && !onRampart(o));
      if (tgt && range(c, tgt) <= 1) { c.attack(tgt); continue; }
      if (!wave.has(c.id) || !tgt) { if (adjArmed.length) c.attack(adjArmed.sort((a, b) => a.hits - b.hits)[0]); fieldMove(c, rally, 2); continue; }
      // the rampart on the way is the best cell down the field whoever stands on it (our creeps stand on our ramparts);
      // the step is the best FREE one
      const dist = field(tgt, 1, 1, 50);
      const ahead = fieldStep(c, dist, oursC.concat(mine));      const ramp = ahead && world.objects.find((o) => o.exists && o.kind === 'rampart' && o.owner === 0 && o.x === ahead.x && o.y === ahead.y);
      if (adjArmed.length) c.attack(adjArmed.sort((a, b) => a.hits - b.hits)[0]);
      else if (ramp) c.attack(ramp);
      if (!ramp) moveTo(c, fieldStep(c, dist));
    }
  }
}

// ---------- run ----------
const lines = [];
const lines2 = [];
let sink = lines; // where the bot's console goes: ours, or the enemy bot's while it runs
let loopErrors = 0;
const origWrite = process.stdout.write.bind(process.stdout);
let buf = '';
process.stdout.write = (chunk) => {
  buf += typeof chunk === 'string' ? chunk : chunk.toString();
  let i;
  while ((i = buf.indexOf('\n')) >= 0) {
    const s = buf.slice(0, i);
    buf = buf.slice(i + 1);
    sink.push(s);
    if (s.startsWith('loop error') && sink === lines) loopErrors++;
  }
  return true;
};
const origLog = (...args) => origWrite(args.join(' ') + '\n');
console.log = (...args) => { const s = args.join(' '); sink.push(s); if (s.startsWith('loop error') && sink === lines) loopErrors++; };
const bot = await import(BOT);
const bot2 = BOT2 ? await import(BOT2) : null;
origLog(`start: map=${mapName} we=${ours.side.name} scenario=${BOT2 ? 'BOT2' : scenario.join('+')} ticks=${ticks}`);
const t0 = Date.now();
let ended = '';
let cpuMax = 0, cpuMaxTick = 0, cpuSlow = 0, cpuOver = 0, cpuSum = 0;
const escDesc = (who) => who.escorts.map((e) => `${e.bodyName}${e.exists ? `(${e.x},${e.y})h${e.hits}${onFlag(e, who.flags) ? 'F' : ''}` : ':dead'}`).join(' ');
const sum = (cs) => { const m = {}; for (const c of cs) { if (c.escort) continue; const s = c.summary(); m[s] = (m[s] || 0) + 1; } return Object.entries(m).map(([k, v]) => `${k}x${v}`).join(' '); };
for (let t = 1; t <= ticks; t++) {
  world.perspective = 0;
  globalThis.ER_PERSONA = PERSONA;
  const tLoop = performance.now();
  try { bot.loop(); } catch (e) { loopErrors++; lines.push('loop error (uncaught): ' + (e && e.stack || e)); }
  const msLoop = performance.now() - tLoop;
  cpuSum += msLoop;
  if (msLoop > cpuMax) { cpuMax = msLoop; cpuMaxTick = t; }
  if (t > 1 && msLoop > 50) cpuSlow++;
  if (t > 1 && msLoop > 100) cpuOver++;
  if (bot2) {
    // the enemy is a bot too: its own module graph (a separate copy of a build), its own view of the world, its own
    // console
    world.perspective = 1;
    globalThis.ER_PERSONA = PERSONA2;
    sink = lines2;
    try { bot2.loop(); } catch (e) { lines2.push('loop error (uncaught): ' + (e && e.stack || e)); }
    sink = lines;
    world.perspective = 0;
  } else if (!has('none')) enemyTick();
  if (process.env.ECON2 && t + 1 === ECON2_FROM) world.spawnRegen[1] = parseFloat(process.env.ECON2);
  step(Resource);
  const c0 = creeps().filter((c) => c.owner === 0), c1 = creeps().filter((c) => c.owner === 1);
  if (TRACE && t >= TRACE[0] && t <= TRACE[1]) {
    origLog(`trace t=${t} ours ${c0.map((c) => `${c.summary()}@${c.x},${c.y}${c.fatigue ? '/' + c.fatigue : ''}`).join(' ')} | enemy ${c1.map((c) => `${c.summary()}@${c.x},${c.y}${c.fatigue ? '/' + c.fatigue : ''}`).join(' ')}`);
  }
  const now = world.tick - 1;
  const ourDead = ours.escorts.filter((e) => !e.exists), theirDead = theirs.escorts.filter((e) => !e.exists);
  const ourAll = ours.escorts.every((e) => e.exists && onFlag(e, ours.flags));
  const theirAll = theirs.escorts.every((e) => e.exists && onFlag(e, theirs.flags));
  const weWin = theirDead.length > 0 || ourAll, theyWin = ourDead.length > 0 || theirAll;
  if (weWin && theyWin) { ended = `DRAW: both sides won at t=${now} (${theirDead.length ? 'his escort dead' : 'ours on flags'} / ${ourDead.length ? 'our escort dead' : 'his on flags'})`; break; }
  if (theirDead.length) { ended = `WIN: enemy escort ${theirDead.map((e) => e.bodyName).join(',')} killed at t=${now}`; break; }
  if (ourAll) { ended = `WIN: our three escorts on our flags at t=${now}`; break; }
  if (ourDead.length) { ended = `LOSS: our escort ${ourDead.map((e) => e.bodyName).join(',')} died at t=${now}`; break; }
  if (theirAll) { ended = `LOSS: enemy escorts on its three flags at t=${now}`; break; }
  if (t % 100 === 0) {
    origLog(`cpu t=${t}: max=${cpuMax.toFixed(1)}ms at t=${cpuMaxTick} avg=${(cpuSum / t).toFixed(2)}ms slow(>50ms)=${cpuSlow} over(>100ms)=${cpuOver}`);
    origLog(`t=${t} spawnE=${ours.sp.store.energy}/${theirs.sp.store.energy} ours: ${escDesc(ours)} | enemy: ${escDesc(theirs)} | ours(${c0.length}): ${sum(c0)} | enemy(${c1.length}): ${sum(c1)} errors=${loopErrors}`);
  }
}
const outDir = fileURLToPath(new URL('out/', import.meta.url));
mkdirSync(outDir, { recursive: true });
const log = `${outDir}run-${process.env.LOGTAG || ''}${BOT2 ? 'bot2' : scenario.join('+')}.log`;
writeFileSync(log, lines.join('\n') + '\n\n=== EVENTS ===\n' + world.events.join('\n') + '\n');
if (bot2) writeFileSync(log.replace(/\.log$/, '.enemy.log'), lines2.join('\n') + '\n');
const n0 = creeps().filter((c) => c.owner === 0).length, n1 = creeps().filter((c) => c.owner === 1).length;
const hp = (who) => who.escorts.map((e) => (e.exists ? e.hits : 0)).join('+');
origLog(`cpu: max=${cpuMax.toFixed(1)}ms at t=${cpuMaxTick} avg=${(cpuSum / Math.max(1, world.tick - 1)).toFixed(2)}ms slow(>50ms)=${cpuSlow} over(>100ms)=${cpuOver}`);
origLog(`done: ${ended || `DRAW: ${ticks} ticks`} alive=${n0}/${n1} escorts=${hp(ours)}/${hp(theirs)} tw=${world.towerShots[0]}/${world.towerShots[1]} errors=${loopErrors} time=${((Date.now() - t0) / 1000).toFixed(1)}s log=${log}`);
const errs = lines.filter((l) => l.startsWith('loop error'));
if (errs.length) origLog('first error:\n' + errs.slice(0, 2).join('\n'));
