// Offline runner for Escort Run ADVANCED: the live layout on a live terrain + a scripted enemy, or a second bot.
// A copy of tools/stub/escortrun/run.mjs (the basic level) adapted to the advanced level — copied, not shared, by the
// repository's rule. Usage (README.md):
//   node --import ./register.mjs run.mjs [ticks=5000] none|race|hunt|blob|siege[+harvest]
//   node --import ./register.mjs run.mjs [ticks=5000] kerobii|stachu1|stachu3   (a persona of a live bot, alone)
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
import { StructureTower } from './game/prototypes/tower.mjs';
import { StructureExtension } from './game/prototypes/extension.mjs';
import { TOWER_ENERGY_COST, TOWER_RANGE } from './game/constants.mjs';
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
// the personas (kerobii, stachu1, stachu3 — the field's three strongest bots, see "the personas" below) play alone: each
// has its own economy, army and escorts, and a `+` with another scenario would mix two opponents into one
const PERSONAS = ['kerobii', 'stachu1', 'stachu3'];
const KNOWN = new Set(['none', 'race', 'hunt', 'blob', 'siege', 'harvest', 'offline', ...PERSONAS]);
for (const s of scenario) if (!KNOWN.has(s)) { console.error(`run: unknown scenario '${s}' (known: ${[...KNOWN].join(' ')})`); process.exit(2); }
const persona = scenario.find((s) => PERSONAS.includes(s)) || null;
if (persona && scenario.length > 1) { console.error(`run: a persona plays alone ('${scenario.join('+')}')`); process.exit(2); }

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
  // the personas' fields follow moving escorts and cells, so the cache is bounded (40 KB a field; the node heap is capped
  // at 192 MB): the oldest goes first. A field is recomputed identically when it is needed again — the cap changes the
  // run time, never a step
  if (fields.size >= 400) fields.delete(fields.keys().next().value);
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
  if (persona) { personaTick(); return; }
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

// ---------- the personas: the field's three strongest bots, built from their replays (27.09.2026) ----------
// `kerobii` — けろびー#11/#15, `stachu1` — stachu3478#1, `stachu3` — stachu3478#3; the replays each one is built from are
// named above its rules (tools/er-replay.py <id> prints them; ~/ScreepsArena/replays). Bodies are written in the replay's
// part order (`t3m8r5`: 3 TOUGH, then 8 MOVE, then 5 RANGED_ATTACK), because damage takes parts from the front. His
// cells are written as the TOP player's and mirrored (y -> 99 - y) when he is the bottom one — his replays from both
// sides agree with the mirror — unless a note says otherwise. Build orders are the replays' orders and their timing comes
// from the energy, as it did live. What no replay shows is marked INVENTED. Nothing here is tuned to our bot: a persona
// built to lose measures nothing.
// Income is a model, not harvest intents: +1 a tick from the spawn plus what his home harvesters give while they are
// alive on their cells (the source gives SOURCE_ENERGY_REGEN 10 at most) — a raid of ours that kills them cuts it.
const hisTop = theirs.sp.y < 50;
const Y = (y) => (hisTop ? y : 99 - y);
const Pm = (x, y) => ({ x, y: Y(y) });
const PART = { m: M, t: T, r: R, a: A, h: 'heal', c: 'carry', w: 'work' };
const pBody = (spec) => [].concat(...[...spec.matchAll(/([a-z])(\d+)/g)].map(([, k, n]) => Array(parseInt(n, 10)).fill(PART[k])));
const pRole = new Map(); // creep id -> role
const pSeq = new Map(); // creep id -> its number among his orders
let pOrdered = 0;
const isAt = (c, p) => c.x === p.x && c.y === p.y;
const hisEscort = (name) => theirs.escorts.find((e) => e.bodyName === name && e.exists);
function pOrder(sp, spec, role) {
  const r = sp.spawnCreep(pBody(spec));
  if (!r.object) return false;
  pRole.set(r.object.id, role); pSeq.set(r.object.id, pOrdered++);
  world.events.push(`t=${world.tick} ${persona}: ${spec} as ${role}${sp === theirs.sp ? '' : ` at the spawn (${sp.x},${sp.y})`}`);
  return true;
}
/** The next order of a build list: the list in order, then `loop` round and round; the head waits for its energy. */
function pQueue(sp, st, list, loop) {
  if (!sp || !sp.exists || sp.spawning) return;
  const it = st.i < list.length ? list[st.i] : loop[(st.i - list.length) % loop.length];
  if (pOrder(sp, it[0], it[1])) st.i++;
}
// this tick's grid: the cells he cannot enter (terrain walls, structures, our ramparts) and the creeps by cell — the
// swarm of stachu3 is too large for a search over every object per neighbour per creep
let pBlock = null, pAt = null;
function pGrid() {
  pBlock = new Uint8Array(10000);
  for (let i = 0; i < 10000; i++) if (world.terrain[i] === 1) pBlock[i] = 1;
  pAt = new Map();
  for (const o of world.objects) {
    if (!o.exists) continue;
    if (STRUCT.has(o.kind) || (o.kind === 'rampart' && o.owner === 0)) pBlock[idx(o.x, o.y)] = 1;
    else if (o.kind === 'creep' && !o.spawning) pAt.set(idx(o.x, o.y), o);
  }
}
const pFree = (x, y) => inBounds(x, y) && !pBlock[idx(x, y)] && !pAt.has(idx(x, y));
/** fieldStep on this tick's grid; `allow` — a creep not in the way (it moves out this tick); `through` — his own mobile
 *  creeps are not in the way either: the engine swaps two creeps stepping into each other's cells and moves a chain,
 *  and without it two of his creeps meeting head-on in a narrow pass stood there for good. */
// A field's value at a cell is the cost from that cell on and leaves out the cost of entering it, so the step is the
// neighbour of least (entry + value), among those closer than here: fieldStep above takes the least value alone and walks
// the scripted enemy into a swamp cell beside a plain one (けろびー's breaker pair took (18,8) over (18,9) — 400 fatigue).
function pStep(c, dist, allow = null, through = false, skip = null) {
  const here = dist[idx(c.x, c.y)];
  if (here === 0) return null;
  const top = here < 0 ? Infinity : here;
  let best = null, bk = Infinity;
  for (let dx = -1; dx <= 1; dx++) for (let dy = -1; dy <= 1; dy++) {
    if (!dx && !dy) continue;
    const x = c.x + dx, y = c.y + dy;
    if (!inBounds(x, y)) continue;
    const d = dist[idx(x, y)];
    if (d < 0 || d >= top) continue;
    const o = pAt.get(idx(x, y));
    if (o && o !== allow && !(through && o.owner === c.owner && !o.escort && live(o, M) > 0)) continue;
    if (skip && skip.has(idx(x, y))) continue;
    const k = d + (terrainAt(x, y) === 2 ? 10 : 2);
    if (k < bk) { bk = k; best = { x, y }; }
  }
  return best;
}
/** A search round every creep, for a traveller whose field step is taken: a field knows no creep, and his own parked
 *  ones (stachu3478's escorts on the ring round his spawn) left the only better cells taken — nothing left his base. */
function pDetour(c, target, rng) {
  const cm = new CostMatrix();
  for (let i = 0; i < 10000; i++) if (pBlock[i]) cm.bits[i] = 255;
  for (const [i, o] of pAt) if (o !== c) cm.bits[i] = 255;
  const r = searchPath(c, { pos: target, range: rng }, { costMatrix: cm, maxOps: 10000 });
  const end = r.path[r.path.length - 1];
  return end && range(end, target) <= rng ? r.path[0] : null; // the stub's search returns a part-way path too
}
/** Out to `r` cells from `from`, round every creep (a group falling back to heal). */
function pFlee(c, from, r) {
  if (c.fatigue > 0 || range(c, from) >= r) return;
  const cm = new CostMatrix();
  for (let i = 0; i < 10000; i++) if (pBlock[i]) cm.bits[i] = 255;
  for (const [i, o] of pAt) if (o !== c) cm.bits[i] = 255;
  moveTo(c, searchPath(c, [{ pos: from, range: r - 1 }], { costMatrix: cm, flee: true, maxOps: 4000 }).path[0]);
}
/** Down the field; when its better cells are taken — round the creeps (`detour`), else into a cell of his own mobile
 *  creep (a swap or a chain, which the engine resolves). A crowd (stachu3478#3's swarm) does neither: sixty guards
 *  pushing into occupied cells won the engine's ranks over the pulled members of his own chain and cut it. */
function pMove(c, target, rng, rampartCost = 0, crowd = null) {
  if (c.fatigue > 0 || range(c, target) <= rng) return;
  const dist = field(target, rng, 1, rampartCost);
  moveTo(c, pStep(c, dist, null, false, crowd) || (!crowd && (pDetour(c, target, rng) || pStep(c, dist, null, true))));
}
/** One greedy step toward a moving target a few cells away (a field per target cell per tick is too dear). */
function pGreedy(c, t, stop = 1) {
  const r0 = range(c, t);
  if (c.fatigue > 0 || r0 <= stop) return;
  let best = null, bk = null;
  for (let dx = -1; dx <= 1; dx++) for (let dy = -1; dy <= 1; dy++) {
    if (!dx && !dy) continue;
    const x = c.x + dx, y = c.y + dy;
    if (!pFree(x, y)) continue;
    const k = [Math.max(Math.abs(x - t.x), Math.abs(y - t.y)), terrainAt(x, y) === 2 ? 1 : 0, Math.abs(x - t.x) + Math.abs(y - t.y)];
    if (k[0] >= r0) continue;
    if (!bk || k[0] < bk[0] || (k[0] === bk[0] && (k[1] < bk[1] || (k[1] === bk[1] && k[2] < bk[2])))) { best = { x, y }; bk = k; }
  }
  moveTo(c, best);
}
/** Any free neighbour not in `avoid` (cells) — a creep stepping out of the way. */
function pAside(c, avoid) {
  if (c.fatigue > 0) return;
  for (let dx = -1; dx <= 1; dx++) for (let dy = -1; dy <= 1; dy++) {
    if (!dx && !dy) continue;
    const x = c.x + dx, y = c.y + dy;
    if (pFree(x, y) && !avoid.some((a) => a.x === x && a.y === y)) { moveTo(c, { x, y }); return; }
  }
}
/** His home harvest this tick: 2 a live WORK on its cell, out of the source's store (1000, +10 a tick — nine WORK drain
 *  its buffer first, which is けろびー's measured 19.7 a tick between 180 and 270), at most `cap` delivered. */
function pHarvest(work, cap = Infinity) {
  const h = Math.min(theirs.src.energy, 2 * work, cap);
  theirs.src.energy -= h;
  return h;
}
function pHeal(c, friends) {
  const hurt = friends.filter((f) => f.exists && f.hits < f.hitsMax && range(c, f) <= 3).sort((a, b) => a.hits / a.hitsMax - b.hits / b.hitsMax);
  const adj = hurt.find((f) => range(c, f) <= 1);
  if (adj) c.heal(adj); else if (hurt.length) c.rangedHeal(hurt[0]);
}
/** A ranged/melee hit on the best of ours in reach: armed off a rampart, then anyone off a rampart, then anyone. */
function pFire(c, oursC) {
  const pick = (list) => { const off = list.filter((o) => !onRampart(o)); const pool = off.length ? off : list; const arm = pool.filter((o) => live(o, R) + live(o, A) > 0); return (arm.length ? arm : pool).sort((a, b) => a.hits - b.hits)[0]; };
  if (live(c, R) > 0) { const inR = oursC.filter((o) => range(c, o) <= 3); if (inR.length) c.rangedAttack(pick(inR)); }
  if (live(c, A) > 0) { const adj = oursC.filter((o) => range(c, o) <= 1); if (adj.length) c.attack(adj.find((o) => o.escort) || pick(adj)); }
}
/** His tower: one shot at the nearest of ours in range — armed first, then others, our escorts last (INVENTED order). */
function pTower(tw, oursC) {
  if (!tw || !tw.exists || tw.store.energy < TOWER_ENERGY_COST || tw.cooldown > 0) return;
  const inR = oursC.filter((o) => range(tw, o) <= TOWER_RANGE);
  if (!inR.length) return;
  const by = (a, b) => range(tw, a) - range(tw, b);
  const t = inR.filter((o) => armed(o) && !o.escort).sort(by)[0] || inR.filter((o) => !o.escort).sort(by)[0] || inR.sort(by)[0];
  world.perspective = 1; tw.attack(t); world.perspective = 0; // the tower API checks its owner against the perspective
}
/** A structure of his raised by a script (the stub has no enemy construction sites): `wait` while a creep stands on the
 *  cell of an obstacle, `skip` when anything solid (ours too) is already there. */
function pRaise(kind, x, y) {
  const here = world.objects.filter((o) => o.exists && o.x === x && o.y === y);
  let obj;
  if (kind === 'rampart') {
    if (here.some((o) => o.kind === 'rampart' || o.kind === 'site')) return 'skip';
    obj = new StructureRampart(x, y, 1, RAMPART_HITS);
  } else {
    if (here.some((o) => STRUCT.has(o.kind) || o.kind === 'site' || o.kind === 'container' || (o.kind === 'rampart' && o.owner === 0))) return 'skip';
    if (kind !== 'container' && here.some((o) => o.kind === 'creep')) return 'wait'; // a container is no obstacle
    obj = kind === 'tower' ? new StructureTower(x, y, 1) : kind === 'spawn' ? new StructureSpawn(x, y, 1, 0) : kind === 'extension' ? new StructureExtension(x, y, 1) : new StructureContainer(x, y, 0, 2000);
  }
  world.objects.push(obj);
  world.events.push(`t=${world.tick} ${persona}: ${kind} at (${x},${y})`);
  return obj;
}
/** Head pulls tail along `dist` (the tail steps into the head's cell — World's pull); the head waits for a tail that is
 *  not beside it, and the tail comes. */
function pTowPair(head, tail, dist) {
  if (range(head, tail) <= 1) {
    head.pull(tail);
    if (head.fatigue === 0) { const n = pStep(head, dist); if (n) { moveTo(head, n); tail.move(head); } }
  } else if (tail.fatigue === 0) pGreedy(tail, head, 1);
}
/** A puller in front of its escort (the race's train): the puller stands on the escort's next cell and pulls. `minD` —
 *  the train keeps behind a cell of that field value (a queue behind the one ahead of it). */
function pTowRace(e, p, dist, minD = -Infinity) {
  const next = pStep(e, dist, p);
  if (!next || dist[idx(next.x, next.y)] < minD) { if (range(p, e) <= 1) p.pull(e); return; }
  if (p.x === next.x && p.y === next.y) {
    p.pull(e);
    if (e.fatigue === 0 && p.fatigue === 0) { const pn = pStep(p, dist); if (pn && dist[idx(pn.x, pn.y)] >= minD) { moveTo(p, pn); e.move(p); } } // World's move(creep)
  } else {
    chase(p, next, 0);
    if (range(p, e) > 3 && e.fatigue === 0) moveTo(e, next);
  }
}
/** A train led by its front creep: each pulls the one behind it (World's pull — the one behind steps into the cell
 *  ahead, the whole train's fatigue goes to the leader and every MOVE in it sheds that). The leader steps down `dist`
 *  when rested and its next cell is not below `minD` (a queue behind the train ahead). */
function pChain(ch, dist, minD = -Infinity) {
  for (let i = 0; i < ch.length - 1; i++) { ch[i].pull(ch[i + 1]); ch[i + 1].move(ch[i]); }
  const h = ch[0];
  if (h.fatigue > 0) return;
  const n = pStep(h, dist);
  if (n && dist[idx(n.x, n.y)] >= minD) moveTo(h, n);
}
/** The next `n` cells down a field from a creep, creeps ignored — where a train is about to go. */
function pAhead(c, dist, n) {
  const cells = [];
  let cur = { x: c.x, y: c.y };
  for (let i = 0; i < n; i++) { const s = st3Descend(cur, dist); if (!s) break; cells.push(s); cur = s; }
  return cells;
}
/** A tug takes a creep without MOVE to `cell` along a search round every creep (his own stand about his base, and a
 *  field step has nowhere to go when its one better cell is taken): the tug stands on the next cell and pulls, and on
 *  the last step the two swap (World's pull lets the pulled creep step into its puller's cell while the puller steps
 *  into the pulled one's). */
function pTug(e, p, cell) {
  if (isAt(e, cell)) return;
  const cm = new CostMatrix();
  for (let i = 0; i < 10000; i++) if (pBlock[i]) cm.bits[i] = 255;
  for (const [i, o] of pAt) if (o !== e && o !== p) cm.bits[i] = 255;
  const path = searchPath(e, { pos: cell, range: 0 }, { costMatrix: cm, maxOps: 10000 }).path;
  const next = path[0];
  if (!next) return;
  if (isAt(p, next)) { p.pull(e); if (p.fatigue === 0) { moveTo(p, path[1] || e); e.move(p); } }
  else chase(p, next, 0);
}

// 'kerobii' — けろびー#11 / #15. Replays: 6ab92dd5, 6ab9267a, 6ab941fb (#11, top), 6ab93352 (#15, bottom).
//   economy: M1 on (8,8) at t=1, W4C1 at 4 on (2,3), C1 at 51 on (5,5), W5C1 at 129 on (3,3), C1 at 148 on (7,7);
//     extensions (4,4) at 73 and (6,6) at 177 (200 each out of his harvest). The stub's spawn spends only its own store,
//     so each extension adds its 100 to the spawn's capacity (1200 — T3M8R5 is 1180) and costs the spawn 200. Income
//     1 + 10 with the W5C1 in place (measured 11.3 a tick between 100 and 200), before it 1 + 4 from the W4C1 alone
//     (measured 5.5 gross between 4 and 100);
//   his escorts step off at t=1-5 and stand M10T40 (5,6), M5T40 (8,6) — OUTSIDE his ramparts — and M3T42 (7,9) until
//     the corridor march;
//   army, in the replay's order: T3M8R5 226, T1M6A5 274, M1H1 310, M5H3 357, T3M8R5 463, T3M8R5 570, M4H2 635, four C1M1
//     653-671, T3M8R5 796, T3M8R5 953, M3H2 1031, two C1M1 1046-1052; the breaker A10M1 at 1169 and three pullers M10
//     1225/1284/1341 (the stub orders them from 1160 ahead of the list); after the list T3M8R5, T3M8R5, M4H2 round and
//     round (the replays show T3M8R5 at 1516 and 1689 — the loop is INVENTED past them);
//   the blob holds (48-51,51-54) — the point (50,52) — with the `blob` rules (fire in reach, chase what comes within
//     five of it or the point, leashed at eight, kite our armed at two); its healers heal and follow the hurt;
//   raids to our home source of the older fighters and a healer: 510-550 (the first wave, all four), 960-1100 (three
//     T3M8R5 and M4H2, back in the blob by 1150), 1650-1700 (all); the stub sends up to four fighters and one healer at
//     520, 900 and 1620 and keeps them within reach of our source 100 ticks after they arrive; they shoot our creeps off
//     ramparts first — harvesters and haulers, as live (the order is INVENTED);
//   the centre: the W4C1 walks to (44,45) at ~650, a container there at 785, four (later six) C1M1 carry energy from his
//     spawn to it along the diagonal, towers (44,46) at 1157 and (45,46) at 1576 — 1250 each, built at 20 a tick out of
//     the container (4 WORK x BUILD_POWER) — which the keeper refills (capacity 10);
//   the breaker walks with the M10T40 behind it, pulling it (alone A10M1 is 10 ticks a cell; measured (17,9) at 1250,
//     (43,9) at 1350 — the pair's 4.5), breaks the corridor y=9, x=43..61, wall by wall (measured 1332->1539, 11 ticks a
//     wall) and tows the M10T40 on to its flag; the pullers tow M3T42 and M5T40 behind them; flags: M10T40 (95,4),
//     M3T42 (62,5), M5T40 (94,27), all three on by ~1650-1710 live.
const KER = {
  hold: { M10T40: Pm(5, 6), M5T40: Pm(8, 6), M3T42: Pm(7, 9) },
  flag: { M10T40: Pm(95, 4), M3T42: Pm(62, 5), M5T40: Pm(94, 27) },
  cell: { tug: Pm(8, 8), fillA: Pm(5, 5), fillB: Pm(7, 7), keeper: Pm(2, 3), harvester: Pm(3, 3) },
  blob: Pm(50, 52), cont: Pm(44, 45), towers: [Pm(44, 46), Pm(45, 46)], ext: [[73, Pm(4, 4)], [177, Pm(6, 6)]],
  raids: [520, 900, 1620], raidSize: 4, raidStay: 100, keeperGo: 650, marchFrom: 1160,
  list: [['m1', 'tug'], ['w4c1', 'keeper'], ['c1', 'fillA'], ['w5c1', 'harvester'], ['c1', 'fillB'],
    ['t3m8r5', 'fighter'], ['t1m6a5', 'fighter'], ['m1h1', 'medic'], ['m5h3', 'medic'], ['t3m8r5', 'fighter'], ['t3m8r5', 'fighter'],
    ['m4h2', 'medic'], ['c1m1', 'hauler'], ['c1m1', 'hauler'], ['c1m1', 'hauler'], ['c1m1', 'hauler'],
    ['t3m8r5', 'fighter'], ['t3m8r5', 'fighter'], ['m3h2', 'medic'], ['c1m1', 'hauler'], ['c1m1', 'hauler']],
  loop: [['t3m8r5', 'fighter'], ['t3m8r5', 'fighter'], ['m4h2', 'medic']],
  march: [['a10m1', 'breaker'], ['m10', 'puller'], ['m10', 'puller'], ['m10', 'puller']],
  q: { i: 0 }, mq: { i: 0 }, container: null, contAt: 0, build: 0, raid: null, raidN: 0, pullerOf: new Map(), open: false,
  extDone: new Set(), marched: false, breakerLeads: true,
};
const kerWalls = () => world.objects.filter((o) => o.exists && o.kind === 'wall' && o.y === Y(9) && o.x >= 43 && o.x <= 61).sort((a, b) => a.x - b.x);
function kerFighter(c, oursC, point, raid) {
  if (raid) pFire(c, oursC); else fireAt(c, oursC);
  const ranged = live(c, R) > 0;
  // what comes within five of the creep, wherever it is (6ab92dd5: his fresh T3M8R5 stayed at (12,12) until our M5A5 at
  // (15,15) was dead, 286-301), or within five of the point, leashed at eight (the `blob` rules)
  const near = oursC.filter((o) => (range(o, c) <= 5 || (range(o, point) <= 5 && range(o, point) <= 8)) && !(raid && o.escort));
  const close = ranged ? oursC.filter((o) => live(o, R) + live(o, A) > 0 && range(c, o) <= 2) : [];
  if (close.length) stepAway(c, close);
  else if (near.length) { const t = nearestOf(c, near); if (range(c, t) > (ranged ? 3 : 1)) chase(c, t, ranged ? 3 : 1); }
  else pMove(c, point, raid ? 3 : 2);
}
function kerMedic(c, group, point) {
  pHeal(c, group);
  const hurt = group.filter((f) => f !== c && f.hits < f.hitsMax).sort((a, b) => a.hits / a.hitsMax - b.hits / b.hitsMax)[0];
  const lead = hurt || nearestOf(c, group.filter((f) => f !== c && pRole.get(f.id) === 'fighter'));
  if (lead && range(lead, point) <= 10) { if (range(c, lead) > 1) chase(c, lead, 1); }
  else pMove(c, point, 2);
}
function kerobiiTick(mine, oursC) {
  const t = world.tick;
  const out = (r) => mine.filter((c) => pRole.get(c.id) === r && !c.spawning);
  const bySeq = (a, b) => pSeq.get(a.id) - pSeq.get(b.id);
  const cellOf = (c) => KER.cell[pRole.get(c.id)];
  // the extensions: capacity for a T3M8R5, paid out of his harvest
  KER.ext.forEach(([at, cell], i) => {
    if (t < at || KER.extDone.has(i)) return;
    const o = pRaise('extension', cell.x, cell.y);
    if (o === 'wait') return;
    KER.extDone.add(i);
    if (typeof o === 'object') { theirs.sp.store.capacity += 100; theirs.sp.store.energy = Math.max(0, theirs.sp.store.energy - 200); }
  });
  const work = out('harvester').concat(out('keeper')).filter((c) => isAt(c, cellOf(c))).reduce((n, c) => n + live(c, 'work'), 0);
  world.spawnRegen[1] = 1 + pHarvest(work);
  // orders: the march (breaker, three pullers) from 1160 ahead of the list; a breaker lost while the corridor stands is
  // ordered again (INVENTED — no replay lost one)
  const walls = kerWalls();
  const breaker = out('breaker')[0] || mine.find((c) => pRole.get(c.id) === 'breaker');
  if (t >= KER.marchFrom && KER.mq.i < KER.march.length) { if (!theirs.sp.spawning && pOrder(theirs.sp, ...KER.march[KER.mq.i])) KER.mq.i++; }
  else if (t >= KER.marchFrom && walls.length && !breaker) { if (!theirs.sp.spawning) pOrder(theirs.sp, 'a10m1', 'breaker'); }
  else pQueue(theirs.sp, KER.q, KER.list, KER.loop);
  // the economy's bodies have no MOVE (W4C1, C1, W5C1, C1): the M1 born at t=1 tugs each to its cell and then sits on
  // (8,8) (measured: (7,7) at 50, (6,6) at 100-150, (8,8) from 200); the keeper goes to the centre towed by the first
  // C1M1 (measured: the one born 653 beside it, (28,28) at 700, (44,45) by 750)
  const cargo = mine.filter((c) => !c.spawning && ['keeper', 'fillA', 'harvester', 'fillB'].includes(pRole.get(c.id))
    && !(pRole.get(c.id) === 'keeper' && t >= KER.keeperGo) && !isAt(c, cellOf(c))).sort(bySeq)[0];
  const tug = out('tug')[0];
  if (tug) { if (cargo) pTug(cargo, tug, cellOf(cargo)); else pMove(tug, KER.cell.tug, 0); }
  const keeperC = out('keeper')[0];
  const keeperTug = keeperC && t >= KER.keeperGo && !isAt(keeperC, KER.cont) ? out('hauler').sort(bySeq)[0] : null;
  if (keeperTug) pTug(keeperC, keeperTug, KER.cont);
  // the centre: container, towers, refills
  const keeper = out('keeper').find((c) => isAt(c, KER.cont));
  if (keeper && !KER.container) {
    if (!KER.contAt) KER.contAt = t + 5; // 100 at 20 a tick (measured site 781, container 785)
    if (t >= KER.contAt) { const o = pRaise('container', KER.cont.x, KER.cont.y); if (typeof o === 'object') KER.container = o; }
  }
  const towers = world.objects.filter((o) => o.exists && o.kind === 'tower' && o.owner === 1);
  if (keeper && KER.container && KER.container.exists) {
    const st = KER.container.store;
    for (const tw of towers) { const g = Math.min(tw.store.capacity - tw.store.energy, st.energy); tw.store.energy += g; st.energy -= g; }
    if (towers.length < KER.towers.length) {
      const g = Math.min(20, st.energy); st.energy -= g; KER.build += g;
      if (KER.build >= 1250) { const cell = KER.towers[towers.length]; const o = pRaise('tower', cell.x, cell.y); if (o !== 'wait') KER.build -= 1250; }
    }
  }
  for (const tw of towers) pTower(tw, oursC);
  for (const h of out('hauler')) {
    if (h === keeperTug) continue;
    const cont = KER.container && KER.container.exists ? KER.container : null;
    if (h.store.energy === 0) { if (range(h, theirs.sp) <= 1) { if (theirs.sp.store.energy >= 50) h.withdraw(theirs.sp); } else pMove(h, theirs.sp, 1); }
    else if (cont) { if (range(h, cont) <= 1) h.transfer(cont); else pMove(h, cont, 1); }
    else pMove(h, KER.cont, 2);
  }
  // the raids
  if (KER.raids.includes(t) && !KER.raid) {
    const bySeq = (a, b) => pSeq.get(a.id) - pSeq.get(b.id);
    const fighters = out('fighter').filter((c) => live(c, M) > 0).sort(bySeq).slice(0, KER.raidSize); // a cripple stays
    const medic = out('medic').sort(bySeq)[0];
    if (fighters.length) {
      KER.raid = { m: new Set([...fighters, ...(medic ? [medic] : [])].map((c) => c.id)), arrived: 0, n: ++KER.raidN };
      world.events.push(`t=${t} ${persona}: raid ${KER.raid.n} to our source: ${[...fighters, ...(medic ? [medic] : [])].map((c) => c.summary()).join(' ')}`);
    }
  }
  if (KER.raid) {
    const raiders = mine.filter((c) => KER.raid.m.has(c.id));
    if (!KER.raid.arrived && raiders.some((c) => range(c, ours.src) <= 5)) KER.raid.arrived = t;
    if (!raiders.length || (KER.raid.arrived && t >= KER.raid.arrived + KER.raidStay)) {
      world.events.push(`t=${t} ${persona}: raid ${KER.raid.n} ends, ${raiders.length} back to the blob`);
      KER.raid = null;
    }
  }
  const army = mine.filter((c) => !c.spawning && (pRole.get(c.id) === 'fighter' || pRole.get(c.id) === 'medic'));
  for (const c of army) {
    const inRaid = !!(KER.raid && KER.raid.m.has(c.id));
    const point = inRaid ? ours.src : KER.blob;
    if (pRole.get(c.id) === 'medic') kerMedic(c, army.filter((o) => !!(KER.raid && KER.raid.m.has(o.id)) === inRaid), point);
    else kerFighter(c, oursC, point, inRaid);
  }
  // the escorts: at their posts until the march; the breaker tows the M10T40, the pullers the other two
  const e10 = hisEscort('M10T40'), e5 = hisEscort('M5T40'), e3 = hisEscort('M3T42');
  if (!walls.length && !KER.open) { KER.open = true; world.events.push(`t=${t} ${persona}: the corridor y=${Y(9)} is open`); }
  // the march, as the replay has it: every train is led by its FRONT creep, which pulls the one behind — the breaker pulls
  // the M10T40 through the corridor, and each escort pulls its M10 behind it, whose MOVEs shed the leader's fatigue
  // (measured 1300: breaker (32,9), M10T40 (31,9), M10 (30,9); 1550: M5T40 (59,9) with an M10 at (58,9), M3T42 (55,9) with
  // one at (54,9); 1600: M10T40 (78,11), its M10 (77,10), the breaker left at (63,11)). The M10s go to M10T40, M3T42,
  // M5T40 in their order (403, 405, 407), a later one to whoever lacks one.
  const b = out('breaker')[0];
  if (b) KER.marched = true; // the other two leave their posts when the breaker is out (measured ~1250)
  const pushers = out('puller');
  for (const [eid, p] of [...KER.pullerOf]) if (!p.exists) KER.pullerOf.delete(eid);
  const busy = new Set([...KER.pullerOf.values()].map((p) => p.id));
  for (const e of [e10, e3, e5]) {
    if (!e || KER.pullerOf.has(e.id) || isAt(e, KER.flag[e.bodyName])) continue;
    const free = pushers.filter((p) => !busy.has(p.id)).sort(bySeq)[0];
    if (free) { KER.pullerOf.set(e.id, free); busy.add(free.id); }
  }
  // the breaker leads the M10T40 until both are out of the corridor, 2 cells past its last wall (61,y)
  if (b && !walls.length && KER.breakerLeads && range(b, { x: 61, y: Y(9) }) >= 3) {
    KER.breakerLeads = false; world.events.push(`t=${t} ${persona}: the breaker leaves the M10T40 at (${b.x},${b.y})`);
  }
  const leads = b && e10 && KER.breakerLeads;
  const trainOf = (e) => { const ch = e === e10 && leads ? [b, e] : [e]; const q = KER.pullerOf.get(e.id); if (q && range(q, e) <= 1) ch.push(q); return ch; };
  // while the corridor stands every train follows one field (to the first standing wall) and keeps two cells behind the
  // train ahead of it
  const wallDist = walls.length ? field(walls[0], 1, 1) : null;
  const dAt = (o) => (wallDist ? wallDist[idx(o.x, o.y)] : -1);
  const queueMin = new Map();
  if (wallDist && leads) {
    let last = Math.max(...trainOf(e10).map(dAt));
    for (const e of [e3, e5].filter(Boolean).sort((x, y) => dAt(x) - dAt(y))) { queueMin.set(e.id, last + 4); last = Math.max(last + 4, ...trainOf(e).map(dAt)); }
  }
  if (b) {
    if (walls.length && range(b, walls[0]) <= 1) { b.attack(walls[0]); if (e10 && range(b, e10) <= 1) { b.pull(e10); for (let ch = trainOf(e10), i = 1; i < ch.length - 1; i++) ch[i].pull(ch[i + 1]); } }
    else if (leads) { if (range(b, e10) <= 1) pChain(trainOf(e10), wallDist || field(KER.flag.M10T40, 0, 1)); else if (e10.fatigue === 0) pGreedy(e10, b, 1); }
    else if (walls.length) pMove(b, walls[0], 1);
    else if (e10 && range(b, e10) <= 1) pAside(b, pAhead(e10, field(KER.flag.M10T40, 0, 1), 3));
    else if (theirs.flags.some((f) => range(b, f) <= 1)) stepAway(b, theirs.flags);
  }
  for (const e of [e10, e5, e3]) {
    if (!e) continue;
    const flag = KER.flag[e.bodyName];
    const q = KER.pullerOf.get(e.id);
    if (q && range(q, e) > 1) { if (range(q, e) <= 4) pGreedy(q, e, 1); else chase(q, e, 1); } // the pusher comes up behind
    if (isAt(e, flag)) continue;
    if (e === e10 && leads) continue; // the breaker moves this train
    if (!KER.marched) { pMove(e, KER.hold[e.bodyName], 0); continue; }
    if (walls.length && !b) continue; // the breaker died with the corridor standing: the trains wait (INVENTED)
    pChain(trainOf(e), wallDist || field(flag, 0, 1), queueMin.get(e.id) ?? -Infinity);
  }
  for (const p of pushers) if (![...KER.pullerOf.values()].includes(p) && theirs.flags.some((f) => range(p, f) <= 1)) stepAway(p, theirs.flags);
}

// 'stachu1' — stachu3478#1. Replays: 6ab92e1f, 6ab94091 (top), 6ab92799, 6ab940d7 (bottom).
//   economy: W3M1C1 at 1 and ~115 on (3,3)/(3,2), two M1C1 at 16 and ~50 carrying their piles to the spawn: income 1 + 10
//     (6 WORK on a source of 10) while a harvester is in place and a hauler lives; the orders 271-1045 need ~10.4 a tick;
//   the outpost: M3W3C1 at ~181 walks to (95,25) beside the far source (96,24) — (96,24) from EITHER side, all four
//     replays build there, so as the bottom player he builds next to OUR flag (94,27) — and raises a rampart over itself
//     (369), a rampart (94,25) (417), a tower under it (710), a rampart (94,26) (760) and a spawn under that (994): the
//     stub builds them at the replays' pace, 4.25 energy a tick from its arrival (2850 by 994 from 323), refilling the
//     tower (capacity 10) first, then feeds the new spawn with its 6 a tick (the spawn's own +1 comes on top); the new
//     spawn makes the same three bodies, which hold round it (measured (95,26) (95,27) (90,26) (90,27) from ~1200;
//     their engagement radius, seven, is INVENTED);
//   his escorts stand on the inner ring of his block all match — top M3T42 (9,8) M10T40 (9,10) M5T40 (10,9), bottom
//     M3T42 (9,91) M10T40 (10,91) M5T40 (10,90) (not a mirror);
//   the army: M4H3M1 (healer), M4R5M1 (ranged), M7A6M1 (melee) round and round from ~271/363/445, energy-driven; they
//     wait by his block ((11,10)-(12,12) top, (7,87)-(9,88) bottom: the stub's rally is (12,10) mirrored) and whatever
//     waits goes as one group once a healer and a ranged are among it — the first pair alone at ~430, then the first
//     melee with the second pair at ~700 (6ab94091, 6ab940d7: the melee stood at home 480-675) — through the centre, or
//     through x=6 if that pass is open; at our block the `siege` rules (our escort when beside it, else an armed creep of
//     ours off its rampart, else the rampart on the way to our nearest escort — measured: they gnawed our block from its
//     corner), and the group falls back to ~14 cells from our escorts to heal and comes again: the replays show that
//     dance ((13,86) <-> (22,77)-(24,75), a ranged back at 334/1000 and in again at 974); its thresholds — back when one
//     is under 75 %, in again when all are over 95 % — are INVENTED; so is the healer following the most hurt; NOT in the
//     stub: the whole army walking home at ~1000 in 6ab94091 (no rule seen);
//   NOT in the stub: the W1C1M1 pair of three replays of four (at 300 or ~860; one sat on our flag (94,72), one raised
//     ramparts and a tower (91,49) over the container (92,49)) — its timing did not repeat.
const ST1 = {
  ring: hisTop ? { M3T42: { x: 9, y: 8 }, M10T40: { x: 9, y: 10 }, M5T40: { x: 10, y: 9 } } : { M3T42: { x: 9, y: 91 }, M10T40: { x: 10, y: 91 }, M5T40: { x: 10, y: 90 } },
  cell: [Pm(3, 3), Pm(3, 2)], haul: [Pm(5, 5), Pm(7, 8)], rally: Pm(12, 10),
  post: { x: 95, y: 25 }, rate: 4.25, feed: 6,
  works: [[200, 'rampart', 95, 25], [200, 'rampart', 94, 25], [1250, 'tower', 94, 25], [200, 'rampart', 94, 26], [1000, 'spawn', 94, 26]],
  list: [['w3m1c1', 'harvester'], ['m1c1', 'hauler'], ['m1c1', 'hauler'], ['w3m1c1', 'harvester'], ['m3w3c1', 'builder']],
  loop: [['m4h3m1', 'healer'], ['m4r5m1', 'ranged'], ['m7a6m1', 'melee']],
  q: { i: 0 }, q2: { i: 0 }, arrived: 0, spent: 0, done: 0, sp2: null, tower: null, cellOf: new Map(), group: new Map(), groups: 0, gstate: new Map(), stage: 14,
};
function st1Tick(mine, oursC) {
  const t = world.tick;
  const out = (r) => mine.filter((c) => pRole.get(c.id) === r && !c.spawning);
  // orders: the list, then the three bodies round and round
  pQueue(theirs.sp, ST1.q, ST1.list, ST1.loop);
  if (ST1.sp2 && ST1.sp2.exists) {
    pQueue(ST1.sp2, ST1.q2, [], ST1.loop.map(([s, r]) => [s, 'g-' + r]));
  }
  // income: 6 WORK on the home source (capped at 10) while a hauler lives
  let work = 0;
  for (const c of out('harvester')) {
    if (!ST1.cellOf.has(c.id)) ST1.cellOf.set(c.id, ST1.cell[ST1.cellOf.size % ST1.cell.length]);
    const cell = ST1.cellOf.get(c.id);
    if (isAt(c, cell)) work += live(c, 'work'); else pMove(c, cell, 0);
  }
  const haulers = out('hauler');
  haulers.forEach((c, i) => pMove(c, ST1.haul[i % ST1.haul.length], 0));
  world.spawnRegen[1] = 1 + (haulers.length ? pHarvest(work, 10) : 0); // measured 11 a tick with the regen (200-360, 640-700): his two M1C1 carry no more
  // the outpost
  const b = out('builder')[0];
  if (b && !isAt(b, ST1.post)) pMove(b, ST1.post, 0);
  else if (b) {
    if (!ST1.arrived) { ST1.arrived = t; world.events.push(`t=${t} ${persona}: the outpost builder is at (${ST1.post.x},${ST1.post.y})`); }
    // its harvest comes out of the far source's store (ours too, when our outpost shares the source)
    const src = world.objects.find((o) => o.exists && o.kind === 'source' && o.x === 96 && o.y === 24);
    const h = src ? Math.min(src.energy, 2 * live(b, 'work')) : 0;
    if (src) src.energy -= h;
    let budget = Math.min(h, ST1.done < ST1.works.length ? ST1.rate : ST1.feed);
    if (ST1.tower && ST1.tower.exists) { const g = Math.min(budget, ST1.tower.store.capacity - ST1.tower.store.energy); ST1.tower.store.energy += g; budget -= g; }
    if (ST1.done < ST1.works.length) {
      ST1.spent += budget;
      const [cost, kind, x, y] = ST1.works[ST1.done];
      if (ST1.spent >= cost) {
        const o = pRaise(kind, x, y);
        if (o !== 'wait') { ST1.spent -= cost; ST1.done++; if (kind === 'tower' && typeof o === 'object') ST1.tower = o; if (kind === 'spawn' && typeof o === 'object') ST1.sp2 = o; }
      }
    } else if (ST1.sp2 && ST1.sp2.exists) ST1.sp2.store.energy = Math.min(ST1.sp2.store.capacity, ST1.sp2.store.energy + budget);
  }
  pTower(ST1.tower, oursC);
  // his escorts: the inner ring, all match
  for (const e of theirs.escorts) if (e.exists) pMove(e, ST1.ring[e.bodyName], 0);
  // the army: whatever waits at home goes as one group as soon as a healer and a ranged are among it (the first pair
  // alone, then each melee with the next pair); at our block a group falls back to heal when one of it is hurt
  const army = mine.filter((c) => !c.spawning && ['healer', 'ranged', 'melee'].includes(pRole.get(c.id)));
  const waiting = army.filter((c) => !ST1.group.has(c.id));
  if (waiting.some((c) => pRole.get(c.id) === 'healer') && waiting.some((c) => pRole.get(c.id) === 'ranged')) {
    const g = ST1.groups++;
    for (const c of waiting) ST1.group.set(c.id, g);
    ST1.gstate.set(g, 'attack');
    world.events.push(`t=${t} ${persona}: group ${g} goes for our block: ${waiting.map((c) => c.summary()).join(' ')}`);
  }
  const groups = new Map();
  for (const c of army) { const g = ST1.group.get(c.id); if (g === undefined) continue; if (!groups.has(g)) groups.set(g, []); groups.get(g).push(c); }
  for (const [g, cs] of groups) {
    const st = ST1.gstate.get(g);
    if (st === 'attack' && cs.some((c) => c.hits < 0.75 * c.hitsMax)) { ST1.gstate.set(g, 'back'); world.events.push(`t=${t} ${persona}: group ${g} falls back to heal`); }
    else if (st === 'back' && cs.every((c) => c.hits >= 0.95 * c.hitsMax)) { ST1.gstate.set(g, 'attack'); world.events.push(`t=${t} ${persona}: group ${g} goes in again`); }
  }
  const ourEscorts = ours.escorts.filter((e) => e.exists);
  for (const c of waiting) { pFire(c, oursC); if (pRole.get(c.id) === 'healer') pHeal(c, waiting); pMove(c, ST1.rally, 2); }
  for (const [g, cs] of groups) for (const c of cs) {
    const r = pRole.get(c.id);
    const tgt = nearestOf(c, ourEscorts);
    if (!tgt) continue;
    if (r === 'healer') {
      pHeal(c, mine.filter((o) => range(o, c) <= 3));
      const mates = cs.filter((o) => o !== c);
      const hurt = mates.filter((o) => o.hits < o.hitsMax).sort((x, y) => x.hits / x.hitsMax - y.hits / y.hitsMax)[0];
      const lead = hurt || nearestOf(c, mates.filter((o) => pRole.get(o.id) !== 'healer'));
      if (lead) { if (range(c, lead) > 1) { if (range(c, lead) <= 4) pGreedy(c, lead, 1); else chase(c, lead, 1); } }
      else pMove(c, tgt, ST1.stage);
      continue;
    }
    if (ST1.gstate.get(g) === 'back') { pFire(c, oursC); if (range(c, tgt) < ST1.stage) pFlee(c, tgt, ST1.stage); continue; }
    if (range(c, tgt) <= 1 && r === 'melee') { c.attack(tgt); continue; }
    const dist = field(tgt, 1, 1, 50);
    const ahead = fieldStep(c, dist, oursC.concat(mine));
    const ramp = ahead && world.objects.find((o) => o.exists && o.kind === 'rampart' && o.owner === 0 && o.x === ahead.x && o.y === ahead.y);
    const off = oursC.filter((o) => !onRampart(o) && range(c, o) <= (r === 'ranged' ? 3 : 1));
    if (off.length) pFire(c, off);
    else if (r === 'ranged' && range(c, tgt) <= 3) c.rangedAttack(tgt);
    else if (ramp) { if (r === 'ranged') c.rangedAttack(ramp); else c.attack(ramp); }
    if (!ramp && c.fatigue === 0) moveTo(c, pStep(c, dist) || (range(c, tgt) > 6 && pDetour(c, tgt, 4)) || pStep(c, dist, null, true));
  }
  // the outpost's garrison: holds round the new spawn, fights what comes within seven of it
  const home = ST1.sp2 && ST1.sp2.exists ? ST1.sp2 : ST1.post;
  const garrison = mine.filter((c) => !c.spawning && String(pRole.get(c.id)).startsWith('g-'));
  for (const c of garrison) {
    const r = pRole.get(c.id);
    const near = oursC.filter((o) => range(o, home) <= 7);
    if (r === 'g-healer') { pHeal(c, garrison.concat([c])); const hurt = garrison.filter((o) => o !== c && o.hits < o.hitsMax)[0]; if (hurt) { if (range(c, hurt) > 1) pGreedy(c, hurt, 1); } else pMove(c, home, 3); continue; }
    pFire(c, oursC);
    if (near.length && range(c, home) <= 8) { const tg = nearestOf(c, near); pGreedy(c, tg, r === 'g-ranged' ? 3 : 1); }
    else pMove(c, home, 3);
  }
}

// 'stachu3' — stachu3478#3. Replay: 6ab92e36 (top).
//   economy: W3M1C1 at 1 and 103 on (3,3)/(3,2), two M1C1 at 16 and 47 on (7,7)/(6,6); income 1 + 2 a WORK in place while
//     a hauler lives (76 M1A1 of 130 and the economy by 950 out of 500 + ~12.4 a tick);
//   the army is only M1A1 (130), one whenever affordable from 153 — 76 alive at 950;
//   his escorts walk to their flags from tick 1 without pullers — M10T40 (95,4) and M5T40 (62,5) through the centre,
//     each stopping about ten cells short ((78,18)-(87,15) from 400, (73,9) from 750) until the M3T42 is near; the M3T42
//     (94,27) at its own pace (14 a cell) until ~620, then it LEADS a tail of M1A1 behind it — it pulls the first, each
//     pulls the next, and their MOVEs shed its fatigue (84 + 2N a plain step over 6 + 2N a tick): 9 to 35 M1A1 in a line
//     behind it at 780-945, the escort carrying the fatigue (62-174); (42,36) at 600, (57,42) at 800, (83,19) at 900,
//     (94,27) at ~950; all three step on together at ~950;
//   the swarm keeps near his escorts (within ten of the M3T42 / M5T40) and attacks what comes near them.
//   INVENTED: the swarm's split between the two escorts (two of three to the M3T42), the rings it keeps (4-12; the replay
//   has it spread over ~50 cells, its centre 10-20 behind the M3T42), its engagement radius (ours within 10 of his escort
//   and 6 of the creep), the tail's start (620, the measured speed-up between 600 and 650, as a tick, not a rule), its
//   length (20) and how it gathers (the nearest four come up behind the tail at a time); the go of the waiting escorts
//   (when the M3T42's time to its flag is within ten of theirs); the swarm keeping off his flags and the M3T42's next cells.
const ST3 = {
  flag: { M10T40: Pm(95, 4), M5T40: Pm(62, 5), M3T42: Pm(94, 27) },
  cell: [Pm(3, 3), Pm(3, 2)], haul: [Pm(7, 7), Pm(6, 6)],
  list: [['w3m1c1', 'harvester'], ['m1c1', 'hauler'], ['m1c1', 'hauler'], ['w3m1c1', 'harvester']],
  loop: [['m1a1', 'swarm']], q: { i: 0 },
  wait: 10, tailAt: 620, tailN: 20, recruits: 4,
  go: new Set(), cellOf: new Map(), chain: null, started: false,
};
function st3Descend(p, dist) {
  let best = null, bd = dist[idx(p.x, p.y)];
  for (let dx = -1; dx <= 1; dx++) for (let dy = -1; dy <= 1; dy++) {
    if (!dx && !dy) continue;
    const x = p.x + dx, y = p.y + dy;
    if (!inBounds(x, y)) continue;
    const d = dist[idx(x, y)];
    if (d >= 0 && d < bd) { bd = d; best = { x, y }; }
  }
  return best;
}
function st3Tick(mine, oursC) {
  const t = world.tick;
  const out = (r) => mine.filter((c) => pRole.get(c.id) === r && !c.spawning);
  pQueue(theirs.sp, ST3.q, ST3.list, ST3.loop);
  let work = 0;
  for (const c of out('harvester')) {
    if (!ST3.cellOf.has(c.id)) ST3.cellOf.set(c.id, ST3.cell[ST3.cellOf.size % ST3.cell.length]);
    const cell = ST3.cellOf.get(c.id);
    if (isAt(c, cell)) work += live(c, 'work'); else pMove(c, cell, 0);
  }
  const haulers = out('hauler');
  haulers.forEach((c, i) => pMove(c, ST3.haul[i % ST3.haul.length], 0));
  world.spawnRegen[1] = 1 + (haulers.length ? pHarvest(work) : 0); // measured ~12.4 a tick (76 M1A1 from 153 to 950)
  const e10 = hisEscort('M10T40'), e5 = hisEscort('M5T40'), e3 = hisEscort('M3T42');
  const swarm = out('swarm');
  const flag3 = ST3.flag.M3T42;
  // the M3T42 leads its tail: a dead or detached member cuts the tail behind it, the nearest few free M1A1 come up behind
  // the last one and join when beside it
  const busy = new Set();
  if (e3 && !isAt(e3, flag3)) {
    let ch = ST3.chain || [e3];
    for (let i = 1; i < ch.length; i++) if (!ch[i].exists || range(ch[i], ch[i - 1]) > 1) { world.events.push(`t=${t} ${persona}: the M3T42's tail is cut at ${i} of ${ch.length - 1}`); ch = ch.slice(0, i); break; }
    if (t >= ST3.tailAt && ch.length - 1 < ST3.tailN) {
      const tail = ch[ch.length - 1];
      const free = swarm.filter((s) => !ch.includes(s)).sort((a, b) => range(a, tail) - range(b, tail)).slice(0, ST3.recruits);
      const join = free.find((s) => range(s, tail) <= 1);
      if (join) { ch.push(join); if (!ST3.started) { ST3.started = true; world.events.push(`t=${t} ${persona}: the M3T42 at (${e3.x},${e3.y}) takes an M1A1 tail`); } }
      for (const s of free) if (s !== join) { busy.add(s.id); if (range(s, tail) > 4) pMove(s, tail, 1); else pGreedy(s, tail, 1); }
    }
    ST3.chain = ch;
    for (const c of ch) busy.add(c.id);
    pChain(ch, field(flag3, 0, 1));
  } else if (ST3.chain) { world.events.push(`t=${t} ${persona}: the M3T42 is on its flag, ${ST3.chain.length - 1} in its tail`); ST3.chain = null; }
  const tailN = () => (ST3.chain ? ST3.chain.length - 1 : 0);
  const eta = (e) => field(ST3.flag[e.bodyName], 0, 1)[idx(e.x, e.y)] / 2 * (e === e3 ? (84 + 2 * tailN()) / (6 + 2 * tailN()) : periodPlain(e));
  // the fast two: to about ten cells short of the flag, then on when the M3T42 is near
  for (const e of [e10, e5]) {
    if (!e) continue;
    const flag = ST3.flag[e.bodyName];
    if (isAt(e, flag)) continue;
    const dist = field(flag, 0, 1);
    if (!ST3.go.has(e.id) && dist[idx(e.x, e.y)] / 2 <= ST3.wait) {
      if (e3 && !isAt(e3, flag3) && eta(e3) > eta(e) + 10) continue;
      ST3.go.add(e.id); world.events.push(`t=${t} ${persona}: ${e.bodyName} goes on to its flag`);
    }
    if (e.fatigue === 0) moveTo(e, pStep(e, dist));
  }
  // the swarm: near his escorts, on whatever of ours comes near them; off his flags and the M3T42's next cells
  const keepCells = (e3 && !isAt(e3, flag3) ? pAhead(e3, field(flag3, 0, 1), 6) : []).concat(theirs.flags.map((f) => ({ x: f.x, y: f.y })));
  const keepOff = new Set(keepCells.map((s) => idx(s.x, s.y)));
  for (const c of swarm) {
    const adj = oursC.filter((o) => range(c, o) <= 1);
    if (adj.length) c.attack(adj.find((o) => o.escort) || adj.sort((a, b) => a.hits - b.hits)[0]);
    if (busy.has(c.id)) continue;
    const k = pSeq.get(c.id);
    const g = (k % 3 === 2 ? e5 : e3) || e3 || e5 || e10;
    if (!g) continue;
    const foes = oursC.filter((o) => range(o, g) <= 10 && range(o, c) <= 6);
    if (foes.length) pGreedy(c, nearestOf(c, foes), 1);
    else if (keepOff.has(idx(c.x, c.y))) pAside(c, keepCells);
    else pMove(c, g, 4 + (Math.floor(k / 3) % 9), 0, keepOff);
  }
}
function personaTick() {
  pGrid();
  const all = creeps();
  const mine = all.filter((c) => c.owner === 1 && !c.escort);
  const oursC = all.filter((c) => c.owner === 0 && !c.spawning);
  if (persona === 'kerobii') kerobiiTick(mine, oursC);
  else if (persona === 'stachu1') st1Tick(mine, oursC);
  else st3Tick(mine, oursC);
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
