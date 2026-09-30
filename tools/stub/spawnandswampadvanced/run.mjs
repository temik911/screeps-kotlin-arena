// Offline runner for Spawn and Swamp ADVANCED: the live layout on a live terrain + an opponent — nothing, a recorded
// match (ghost), a responsive persona of a live bot, or a second bot. A copy of the escort-run advanced runner
// (tools/stub/escortrunadvanced/run.mjs: the loop, the console capture, BOT2, the fields and the persona helpers), fitted
// to this arena — copied, not shared, by the repository's rule. Usage (README.md):
//   node --import ./register.mjs run.mjs [ticks=5000] none|ghost|kerobii|kerobii22|ricardo
//   env: REPLAY=<game id | id prefix | path to .replay.json.gz> — the terrain and the side we played in that match
//          (./replays/ first, then ~/ScreepsArena/replays/; default 6abc0dc7); `ghost` plays its recorded opponent;
//        GAME=<a stored match's game.json> — its terrain; MAP=<map-*.txt> — a 100-row terrain dump;
//        START=left|right — we start at (10,70) | (89,29) (default: the side of the record, else left);
//        BOT=<bundle url> BOT2=<bundle url of a SEPARATE copy of a build | self> LOGTAG=<prefix> TRACE=from-to
// The win (measured live, docs/spawn-and-swamp-advanced.md): a side with no creep and no structure left loses (sites do
// not count, ramparts do — the replay 6abc0dc7 ran on until his last rampart and tower fell); both at once — a draw;
// 5000 ticks — a draw.
import { writeFileSync, mkdirSync, readFileSync, existsSync, readdirSync, cpSync, rmSync, openSync, writeSync, closeSync, unlinkSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { gunzipSync, gzipSync } from 'node:zlib';
import { homedir } from 'node:os';
import { world, process as step, idx, inBounds, range, creeps, live, terrainAt, finishSite, towerPower, blockedAt } from './world.mjs';
import { Resource } from './game/prototypes/resource.mjs';
import { Source } from './game/prototypes/source.mjs';
import { StructureSpawn } from './game/prototypes/spawn.mjs';
import { StructureContainer } from './game/prototypes/container.mjs';
import { StructureRampart } from './game/prototypes/rampart.mjs';
import { StructureWall } from './game/prototypes/wall.mjs';
import { StructureTower } from './game/prototypes/tower.mjs';
import { StructureExtension } from './game/prototypes/extension.mjs';
import { StructureRoad } from './game/prototypes/road.mjs';
import { ConstructionSite } from './game/prototypes/construction-site.mjs';
import { Creep } from './game/prototypes/creep.mjs';
import { TOWER_ENERGY_COST, TOWER_RANGE, CONSTRUCTION_COST, TOWER_POWER_ATTACK, TOWER_POWER_HEAL } from './game/constants.mjs';
import { CostMatrix, searchPath } from './game/path-finder.mjs';
import { getDirection, createConstructionSite } from './game/utils.mjs';

world.classes = { Resource, StructureSpawn, StructureRampart, StructureTower, StructureExtension, StructureWall, StructureContainer, StructureRoad };
const PROTO = { spawn: StructureSpawn, rampart: StructureRampart, tower: StructureTower, extension: StructureExtension, constructedWall: StructureWall, wall: StructureWall };

// the bundle of THIS worktree's build (the parallel-sessions rules: the stub tests what the worktree built)
const BUNDLE = 'kotlin/screeps-kotlin-arena-starter/season4/spawnandswampadvanced/SpawnAndSwampAdvanced.export.mjs';
const BOT = process.env.BOT || new URL('../../../build/js/packages/screeps-kotlin-arena-starter/' + BUNDLE, import.meta.url).href;
const ticks = parseInt(process.argv[2] || '5000', 10);
const TRACE = process.env.TRACE ? process.env.TRACE.split('-').map((v) => parseInt(v, 10)) : null;
// HITS=a-b: after each of those ticks, every creep's hits (`hitdump`) and every hit and heal the engine dealt (`hitlog`),
// by record id (his keep theirs; ours through the calibration's match) — hitcmp.py sets them beside the replay's
const HITS = process.env.HITS ? process.env.HITS.split('-').map((v) => parseInt(v, 10)) : null;
const scenario = (process.argv[3] || 'none');
const PERSONAS = ['kerobii', 'kerobii22', 'ricardo'];
const KNOWN = new Set(['none', 'ghost', ...PERSONAS]);
if (!KNOWN.has(scenario)) { console.error(`run: unknown scenario '${scenario}' (known: ${[...KNOWN].join(' ')})`); process.exit(2); }
const US = process.env.US || 'temik911';

// ---------- the record (REPLAY) ----------
function findReplay(arg) {
  if (existsSync(arg)) return arg;
  for (const dir of [fileURLToPath(new URL('replays/', import.meta.url)), `${homedir()}/ScreepsArena/replays/`]) {
    if (!existsSync(dir)) continue;
    const hit = readdirSync(dir).filter((f) => f.endsWith('.replay.json.gz') && f.startsWith(arg));
    if (hit.length === 1) return dir + hit[0];
  }
  throw new Error(`REPLAY ${arg}: no single <id>.replay.json.gz in ./replays/ or ~/ScreepsArena/replays/`);
}
// without REPLAY, GAME or MAP the terrain (and our side) is the one of 6abc0dc7, kept in ./replays/
const REPLAY_ARG = process.env.REPLAY || (process.env.GAME || process.env.MAP ? null : '6abc0dc7');
const REPLAY = REPLAY_ARG ? JSON.parse(gunzipSync(readFileSync(findReplay(REPLAY_ARG))).toString('utf8')) : null;
if (scenario === 'ghost' && !REPLAY) { console.error('run: ghost needs REPLAY=<id or path>'); process.exit(2); }
// our side in the record: the player named US (the stub's owner 0); the record's side 0 starts at (10,70)
const REC_US = REPLAY ? (REPLAY.meta.players.find((p) => String(p.username).startsWith(US)) || { side: 0 }).side : null;

// ---------- the terrain ----------
function mapFromRows(rows) {
  if (rows.length !== 100) throw new Error(`map must have 100 rows, got ${rows.length}`);
  rows.forEach((r, y) => { if (r.length !== 100) throw new Error(`row ${y} has ${r.length} chars`); for (let x = 0; x < 100; x++) world.terrain[idx(x, y)] = r[x] === '#' ? 1 : r[x] === '~' ? 2 : 0; });
}
let mapName;
if (REPLAY) {
  let i = 0;
  for (const [, ch, n] of REPLAY.terrain.matchAll(/([wps])(\d+)/g)) for (let k = 0; k < +n; k++, i++) world.terrain[idx(i % 100, Math.floor(i / 100))] = ch === 'w' ? 1 : ch === 's' ? 2 : 0;
  mapName = `replay ${REPLAY.meta.gameId}`;
} else if (process.env.GAME) {
  const g = JSON.parse(readFileSync(process.env.GAME, 'utf8'));
  const t = g.game.game.terrain;
  for (let y = 0; y < 100; y++) for (let x = 0; x < 100; x++) { const ch = t[y * 100 + x]; world.terrain[idx(x, y)] = ch === '1' ? 1 : ch === '2' ? 2 : 0; }
  mapName = process.env.GAME;
} else {
  const m = process.env.MAP;
  const p = existsSync(m) ? m : fileURLToPath(new URL(m, import.meta.url));
  mapFromRows(readFileSync(p, 'utf8').split('\n').filter((r) => r.length > 0));
  mapName = m;
}

// ---------- the live layout (constant in every match; the record 6abc0dc7 and six more agree cell for cell) ----------
// Two workers W4C1M5 (the body in the record's order: WORK first — damage takes them first), 1000 hits, carry 50; six
// sources 1000/1000 (+10 a tick) on terrain walls; two vaults of four containers of 2500 in a frame of 52 StructureWall of
// 10000 hits closed by the terrain's centre wall x=47..52; ids as live — every object of the start numbered in the order
// of x, then y (the record: 1 source (1,32), 2 the left worker, 3 source (32,98), 4.. the walls, 21 a container ...)
const LEFT = { x: 10, y: 70 }, RIGHT = { x: 89, y: 29 };
const weLeft = process.env.START ? process.env.START === 'left' : (REPLAY ? REC_US === 0 : true);
const START0 = weLeft ? LEFT : RIGHT, START1 = weLeft ? RIGHT : LEFT;
const WORKER = ['work', 'work', 'work', 'work', 'carry', 'move', 'move', 'move', 'move', 'move'];
const layout = [];
for (const [x, y] of [[1, 32], [32, 98], [49, 22], [50, 77], [67, 1], [98, 67]]) layout.push({ kind: 'source', x, y });
for (let y = 27; y <= 36; y++) layout.push({ kind: 'wall', x: 38, y });
for (let x = 39; x <= 46; x++) { layout.push({ kind: 'wall', x, y: 27 }); layout.push({ kind: 'wall', x, y: 36 }); }
for (let y = 63; y <= 72; y++) layout.push({ kind: 'wall', x: 61, y });
for (let x = 53; x <= 60; x++) { layout.push({ kind: 'wall', x, y: 63 }); layout.push({ kind: 'wall', x, y: 72 }); }
for (const [x0, y0] of [[42, 31], [56, 67]]) for (const [dx, dy] of [[0, 0], [0, 1], [1, 0], [1, 1]]) layout.push({ kind: 'container', x: x0 + dx, y: y0 + dy });
layout.push({ kind: 'worker', x: START0.x, y: START0.y, owner: 0 });
layout.push({ kind: 'worker', x: START1.x, y: START1.y, owner: 1 });
layout.sort((a, b) => a.x - b.x || a.y - b.y);
const ours = { owner: 0, start: START0 }, theirs = { owner: 1, start: START1 };
layout.forEach((o, i) => {
  const id = String(i + 1);
  if (terrainAt(o.x, o.y) === 1 && o.kind !== 'source') { world.terrain[idx(o.x, o.y)] = 0; console.error(`run: ${mapName}: (${o.x},${o.y}) is a terrain wall under the fixed layout — made plain`); }
  let obj;
  if (o.kind === 'source') obj = new Source(o.x, o.y, 1000, 1000);
  else if (o.kind === 'wall') obj = new StructureWall(o.x, o.y, 10000);
  else if (o.kind === 'container') obj = new StructureContainer(o.x, o.y, 2500, 2500);
  else { obj = new Creep(o.x, o.y, o.owner, WORKER); (o.owner === 0 ? ours : theirs).worker = obj; }
  obj.id = id;
  world.objects.push(obj);
});
world.nextId = 10000;

// ---------- the record, replayed tick by tick beside the stub ----------
// `rec` is the recorded state AFTER tick k (the replay's k is the state after that tick's processing; the stub's tick t
// is processed after both sides' loops, so a stub event at t and a record entry at k = t are the same moment). It is
// advanced to k = t before the enemy acts on tick t: the ghost walks toward where his creep stood after that tick.
const PART = { m: 'move', w: 'work', c: 'carry', a: 'attack', r: 'ranged_attack', h: 'heal', t: 'tough' };
const PART_CH = Object.fromEntries(Object.entries(PART).map(([k, v]) => [v, k]));
const bodyOf = (rle) => [].concat(...[...rle.matchAll(/([a-z])(\d+)/g)].map(([, k, n]) => Array(parseInt(n, 10)).fill(PART[k])));
const rleOf = (types) => { let s = '', prev = null, n = 0; for (const t of types) { if (t === prev) n++; else { if (prev) s += PART_CH[prev] + n; prev = t; n = 1; } } if (prev) s += PART_CH[prev] + n; return s; };
const rec = { k: -1, i: 0, sp: new Map(), pos: new Map(), last: new Map(), drop: new Map(), hits: new Map(), fat: new Map(), side: new Map(), born: new Map(), body: new Map(), died: new Map(), series: new Map(), builds: [], objs: new Map() };
if (REPLAY) for (const o of REPLAY.objects) rec.objs.set(o.id, o);
/** Advance the record to tick k: creeps born (n), moved (u), gone (x), structure values (s), actions (a) of that tick. */
function recTo(k) {
  const T = REPLAY.ticks;
  rec.builds = [];
  while (rec.i < T.length && T[rec.i].k <= k) {
    const tk = T[rec.i++];
    rec.k = tk.k;
    for (const [id, side, x, y, hits, , body, sp] of tk.n || []) { rec.sp.set(id, !!sp); rec.pos.set(id, [x, y]); rec.last.set(id, [x, y]); rec.hits.set(id, hits); rec.side.set(id, side === REC_US ? 0 : 1); rec.born.set(id, tk.k); rec.body.set(id, body); }
    if (tk.k === k) rec.drop = new Map();
    for (const [id, x, y, hits, fat, sp] of tk.u || []) {
      rec.sp.set(id, !!sp);
      if (tk.k === k && rec.hits.has(id) && hits < rec.hits.get(id)) rec.drop.set(id, rec.hits.get(id) - hits);
      rec.pos.set(id, [x, y]); rec.last.set(id, [x, y]); rec.hits.set(id, hits); rec.fat.set(id, fat);
    }
    for (const id of tk.x || []) { rec.pos.delete(id); rec.died.set(id, tk.k); }
    for (const [id, hits, e] of tk.s || []) rec.series.set(id, { k: tk.k, hits, e });
    if (tk.k === k) for (const a of tk.a || []) if (a[1] === 'build') rec.builds.push(a);
  }
}

// ---------- the calibration instrument: our creeps against our recorded ones ----------
// Our starting worker keeps its live id; a creep our bot orders in the stub is matched to the recorded creep of ours born
// on the same tick at the same spawn cell with the same body. After every tick each matched creep's cell is compared with
// the recorded one: the first tick one of ours stands elsewhere is where the stub and the match part (`ourDev`).
const ourMatch = new Map(); // stub creep -> record id
const dev = { first: null, where: '', on: 0, off: 0 };
function matchOurs(t) {
  if (!REPLAY) return;
  // his creeps (the ghost keeps their recorded ids): the first tick one of them has other hits than recorded
  if (scenario === 'ghost') for (const c of creeps()) {
    if (c.owner !== 1 || c.spawning || dev.hits1 !== undefined || !rec.pos.has(c.id)) continue;
    if (rec.hits.get(c.id) !== c.hits) dev.hits1 = `t=${t} ${c.summary()} ${c.id} at (${c.x},${c.y}) hits ${c.hits} recorded ${rec.hits.get(c.id)}`;
  }
  for (const c of creeps()) {
    if (c.owner !== 0) continue;
    if (!ourMatch.has(c)) {
      if (rec.side.get(c.id) === 0 && rec.born.get(c.id) === 0) ourMatch.set(c, c.id);
      else if (c.bornAt !== undefined) {
        for (const [id, b] of rec.born) {
          if (b !== c.bornAt || rec.side.get(id) !== 0 || rec.body.get(id) !== rleOf(c.body.map((p) => p.type))) continue;
          if ([...ourMatch.values()].includes(id)) continue;
          ourMatch.set(c, id); break;
        }
      }
    }
    const id = ourMatch.get(c);
    const p = id && rec.pos.get(id);
    if (!p) continue;
    if (dev.hits0 === undefined && rec.hits.get(id) !== c.hits) dev.hits0 = `t=${t} ${c.summary()} ${c.id} hits ${c.hits} recorded ${rec.hits.get(id)}`;
    dev.on++;
    if (p[0] !== c.x || p[1] !== c.y) {
      dev.off++;
      if (dev.first === null) { dev.first = t; dev.where = `${c.summary()} ${c.id} at (${c.x},${c.y}) recorded (${p[0]},${p[1]})`; }
    }
  }
}

// STATEDIFF=N: after every tick, the stub against the record, object by object — each creep's cell, hits and fatigue (his
// by id, ours through the match above), each structure's hits and energy, each pile, each site's progress (by kind and
// cell) — printing the first N differences, each item once: the first line is where the stub stopped being the match
const SD = process.env.STATEDIFF ? { n: parseInt(process.env.STATEDIFF, 10) || 40, seen: new Set(), out: 0, maxE: new Map() } : null;
const REC_KIND = { spawn: 'spawn', tower: 'tower', rampart: 'rampart', extension: 'extension', constructedWall: 'wall', container: 'container', energy: 'resource', constructionSite: 'site' };
function stateDiff(t) {
  if (!SD || SD.out >= SD.n || !REPLAY) return;
  const say = (key, msg) => { if (SD.seen.has(key) || SD.out >= SD.n) return; SD.seen.add(key); SD.out++; origLog(`statediff t=${t} ${msg}`); };
  const byRec = new Map([...ourMatch].map(([c, id]) => [id, c]));
  for (const [id, p] of rec.pos) {
    const side = rec.side.get(id);
    const c = side === 1 ? world.objects.find((o) => o.exists && o.kind === 'creep' && o.id === id) : byRec.get(id);
    if (!c || !c.exists) { say(`gone:${id}`, `${side ? 'his' : 'our'} ${rec.body.get(id)} ${id} (born ${rec.born.get(id)}) alive in the record at (${p[0]},${p[1]}), ${side === 0 && !byRec.has(id) ? 'no creep of ours matched to it' : 'dead'} in the stub`); continue; }
    if (c.spawning !== !!rec.sp.get(id)) say(`sp:${id}`, `${side ? 'his' : 'our'} ${c.summary()} ${id} ${c.spawning ? 'still spawning' : 'out'} in the stub, ${rec.sp.get(id) ? 'spawning' : 'out'} in the record`);
    if (c.spawning) continue;
    if (c.x !== p[0] || c.y !== p[1]) say(`cell:${id}`, `${side ? 'his' : 'our'} ${c.summary()} ${id} at (${c.x},${c.y}) recorded (${p[0]},${p[1]})`);
    if (c.hits !== rec.hits.get(id)) say(`hits:${id}`, `${side ? 'his' : 'our'} ${c.summary()} ${id} hits ${c.hits} recorded ${rec.hits.get(id)}`);
    if (side === 0 && rec.fat.has(id) && c.fatigue !== rec.fat.get(id)) say(`fat:${id}`, `our ${c.summary()} ${id} fatigue ${c.fatigue} recorded ${rec.fat.get(id)}`);
  }
  for (const [id, se] of rec.series) {
    const o = rec.objs.get(id);
    const kind = o && REC_KIND[o.kind];
    if (!kind || rec.died.has(id)) continue;
    const owner = o.side === null ? undefined : (o.side === REC_US ? 0 : 1);
    const st = world.objects.find((q) => q.exists && q.kind === kind && q.x === o.x && q.y === o.y && (owner === undefined || q.owner === undefined || q.owner === owner) && (kind !== 'site' || (q.proto && q.proto.name === o.structure)));
    // the record writes a container's hits as 0 (the vault's four, and ours): only its energy says anything
    // a site finished keeps its id in the record with its energy back at 0; a pile gone may have a new pile (a new id) on its cell
    const gone = kind === 'resource' ? se.e <= 0 : kind === 'site' ? (se.e === 0 && (SD.maxE.get(id) || 0) > 0) : (kind !== 'container' && se.hits <= 0);
    if (kind === 'site') SD.maxE.set(id, Math.max(SD.maxE.get(id) || 0, se.e));
    if (gone && kind === 'resource' && [...rec.series].some(([q, e]) => q !== id && e.e > 0 && rec.objs.get(q) && rec.objs.get(q).kind === 'energy' && rec.objs.get(q).x === o.x && rec.objs.get(q).y === o.y)) continue;
    if (gone && kind === 'site') continue;
    if (gone) { if (st) say(`gone:${id}`, `${o.kind} ${id} (${o.x},${o.y}) gone in the record, standing in the stub`); continue; }
    if (!st) { if (kind === 'site' && world.objects.some((q) => q.exists && q.x === o.x && q.y === o.y && q.kind === (o.structure || '').replace('Structure', '').toLowerCase())) continue; say(`miss:${id}`, `${o.kind} ${id} (${o.x},${o.y}) in the record (hits ${se.hits}, e ${se.e}), not in the stub`); continue; }
    if (kind !== 'site' && kind !== 'resource' && kind !== 'container' && st.hits !== se.hits) say(`h:${id}`, `${o.kind} ${id} (${o.x},${o.y}) hits ${st.hits} recorded ${se.hits}`);
    const e = kind === 'resource' ? st.amount : kind === 'site' ? st.progress : st.store ? st.store.energy : undefined;
    if (e !== undefined && e !== se.e && !(kind === 'site' && owner === 1)) say(`e:${id}`, `${o.kind} ${id} (${o.x},${o.y}) energy ${e} recorded ${se.e}`);
  }
}

// ---------- the opponents ----------
const M = 'move', A = 'attack', R = 'ranged_attack', H = 'heal', W = 'work', C = 'carry';
const armed = (o) => live(o, R) + live(o, A) > 0;
const onRampart = (o) => world.objects.some((r) => r.exists && r.kind === 'rampart' && r.x === o.x && r.y === o.y);
const ourStuff = () => world.objects.filter((o) => o.exists && o.owner === 0 && o.kind !== 'site' && !(o.kind === 'creep' && o.spawning));
const moveDir = (c, x, y) => { const d = getDirection(x - c.x, y - c.y); if (d) c.move(d); };
/** A step toward `target` within `rng`: one direct step when adjacent, else the first step of game/utils findPath (round
 *  obstacles and creeps). */
function goTo(c, target, rng = 0) {
  if (c.fatigue > 0 || range(c, target) <= rng) return;
  if (range(c, target) === 1 && rng === 0) { moveDir(c, target.x, target.y); return; }
  c.moveTo(target);
}
/** Fire of a scripted creep, responsive: melee at an adjacent creep of ours (armed first, lowest hits), else at an adjacent
 *  structure of ours; ranged at a creep in three (a mass attack when three or more stand adjacent), else a structure in
 *  three; a healer heals its most hurt friend beside it, else in three. */
function fight(c, oursC, oursS, friends) {
  const pick = (list) => { const arm = list.filter(armed); return (arm.length ? arm : list).sort((a, b) => a.hits - b.hits)[0]; };
  if (live(c, A) > 0) {
    const adj = oursC.filter((o) => range(c, o) <= 1);
    const st = oursS.filter((o) => range(c, o) <= 1 && o.hits !== undefined).sort((a, b) => (a.kind === 'rampart') - (b.kind === 'rampart'));
    if (adj.length) c.attack(pick(adj)); else if (st.length) c.attack(st[0]);
  }
  if (live(c, R) > 0) {
    const inR = oursC.filter((o) => range(c, o) <= 3);
    if (inR.filter((o) => range(c, o) <= 1).length >= 3) c.rangedMassAttack();
    else if (inR.length) c.rangedAttack(pick(inR));
    else { const st = oursS.filter((o) => range(c, o) <= 3 && o.hits !== undefined).sort((a, b) => range(c, a) - range(c, b))[0]; if (st) c.rangedAttack(st); }
  }
  if (live(c, H) > 0) {
    const hurt = friends.filter((f) => f.exists && !f.spawning && f.hits < f.hitsMax && range(c, f) <= 3).sort((a, b) => a.hits / a.hitsMax - b.hits / b.hitsMax);
    const adj = hurt.find((f) => range(c, f) <= 1);
    if (adj) c.heal(adj); else if (hurt.length) c.rangedHeal(hurt[0]);
  }
}
/** His tower: the nearest of ours in range — armed first (INVENTED order). */
function towerFire(tw, oursC) {
  if (!tw.exists || tw.store.energy < TOWER_ENERGY_COST || tw.cooldown > 0) return;
  const inR = oursC.filter((o) => range(tw, o) <= TOWER_RANGE);
  if (!inR.length) return;
  const by = (a, b) => range(tw, a) - range(tw, b);
  tw.attack(inR.filter(armed).sort(by)[0] || inR.sort(by)[0]);
}

// 'ghost' — the recorded opponent of REPLAY. His creeps are born on their recorded tick at their recorded spawn (the
// spawn must stand in the stub and be free; the energy is granted — his economy is the record's), and each walks toward
// the cell it stood on after that tick (a direct step, or a path round obstacles when it fell behind). His building
// follows the record too: a site of his appears on its first recorded build and gains the recorded progress only while
// the creep that built it in the record is alive in the stub — kill his builder and his base is never raised; a structure
// of his appears on its recorded tick only from a site kept in step. His spawns' and towers' energy is the record's.
// His fire is the record's too, by target cell (below): his recorded shot at a cell hits what of ours stands there in the
// stub; a shot at a cell we have left, or a creep of his that outlives its record (we did not kill it live, or killed it
// later), fights by the stub's rule (`fight`, `towerFire`) — the latter standing and fighting what comes within five. On
// the tick a creep of his dies the record keeps neither its fire nor its last step: `inferDeathFire` and `lastStep`.
const gh = { sites: new Map(), dead: new Set(), stats: { born: 0, missed: 0, siteLost: 0, off: 0, on: 0, fallback: 0 } };
function ghostTick(t) {
  const his = creeps().filter((c) => c.owner === 1);
  const oursC = creeps().filter((c) => c.owner === 0 && !c.spawning);
  const oursS = ourStuff().filter((o) => o.kind !== 'creep');
  const hisById = new Map(his.map((c) => [c.id, c]));
  // births of this tick
  for (const [id, side, x, y, , , body] of REPLAY.birthsAt.get(t) || []) {
    if ((side === REC_US ? 0 : 1) !== 1) continue;
    const sp = world.objects.find((o) => o.exists && o.kind === 'spawn' && o.owner === 1 && o.x === x && o.y === y);
    if (!sp || sp.spawning) { gh.stats.missed++; world.events.push(`t=${t} ghost: ${body} not born — ${sp ? 'spawn busy' : 'no spawn'} at (${x},${y})`); continue; }
    // born on the recorded cell (his MOVE-less harvesters must come out exactly on their slot — he sets directions)
    const out = REPLAY.outCell.get(id);
    const d = out ? getDirection(out[0] - x, out[1] - y) : 0;
    sp.begin(bodyOf(body), undefined, id, d ? [d, ...[1, 2, 3, 4, 5, 6, 7, 8].filter((q) => q !== d)] : undefined); gh.stats.born++;
  }
  for (const c of his) c.s0 = c.store.energy;
  // his harvesters harvest when the record says so (the source's energy is what our bot reads: live, his W5C1 kept
  // (50,77) at 10 — drained every tick — and the ghost without it left it at 1000)
  for (const a of REPLAY.actsAt.get(t) || []) {
    if (a[1] !== 'harvest') continue;
    const c = hisById.get(a[0]);
    const src = c && world.objects.find((o) => o.exists && o.kind === 'source' && o.x === a[2] && o.y === a[3]);
    if (src && range(c, src) <= 1) c.harvest(src);
  }
  // his fire: the record's own, target by cell — his shot at a cell hits what of ours stands there in the stub; a
  // recorded shot at an empty cell (we are elsewhere by now), or a creep that outlived its record, fights by `fight`
  const recActs = new Map();
  for (const a of REPLAY.actsAt.get(t) || []) { if (!recActs.has(a[0])) recActs.set(a[0], []); recActs.get(a[0]).push(a); }
  // what his recorded shot at a cell hits here: our creep there, else a structure there that is not his — ours, or a
  // neutral StructureWall (stachu3478 breaks the vault frame: 6abc107c t=852 `r` at (59,72))
  const oursAt = (x, y) => oursC.find((o) => o.x === x && o.y === y) ||
    world.objects.find((o) => o.exists && o.x === x && o.y === y && o.kind !== 'creep' && o.kind !== 'site' && o.owner !== 1 && typeof o.hits === 'number') || null;
  const hisAt = (x, y) => his.find((o) => o.x === x && o.y === y && !o.spawning) || null;
  for (const tw of world.objects.filter((o) => o.exists && o.kind === 'tower' && o.owner === 1)) {
    // a tower the record has already lost (or a record that has ended) fires by the stub's rule
    const se = rec.series.get(tw.recId);
    if (t > REPLAY.meta.ticks || !tw.recId || (se && se.hits === 0)) { towerFire(tw, oursC); continue; }
    for (const a of recActs.get(tw.recId) || []) {
      if (a[1] === 'a') { const tg = oursAt(a[2], a[3]); if (tg) tw.attack(tg); else towerFire(tw, oursC); }
      else if (a[1] === 'h') { const tg = hisAt(a[2], a[3]); if (tg) tw.heal(tg); }
    }
  }
  const replayFire = (c) => {
    let fallback = false;
    for (const a of recActs.get(c.id) || []) {
      if (a[1] === 'r') { const tg = oursAt(a[2], a[3]); if (tg) c.rangedAttack(tg); else fallback = true; }
      else if (a[1] === 'R') c.rangedMassAttack();
      else if (a[1] === 'a') { const tg = oursAt(a[2], a[3]); if (tg) c.attack(tg); else fallback = true; }
      else if (a[1] === 'h') { const tg = hisAt(a[2], a[3]); if (tg) c.heal(tg); else fallback = true; }
      else if (a[1] === 'H') { const tg = hisAt(a[2], a[3]); if (tg) c.rangedHeal(tg); else fallback = true; }
    }
    return fallback;
  };
  const outlived = (id, tk) => { const d = rec.died.get(id); return (d !== undefined && d < tk) || (!rec.pos.has(id) && d === undefined); };
  // his creeps: to the recorded cell, firing as recorded
  for (const c of his) {
    if (c.spawning) continue;
    const dbg = process.env.GHOSTACT && (() => { const [a, b] = process.env.GHOSTACT.split('-').map(Number); return t >= a && t <= b; })();
    // the record keeps no action of a creep on the tick it dies (6abc107c: 22 of 22 hits whose attacker has no action were
    // dealt by a creep dying that tick) — its fire that tick is read off its victims' entries (`A` at its cell)
    if (rec.died.get(c.id) === t && !recActs.has(c.id)) { deathFire(c, t, oursC, oursS); lastStep(c, t, his); continue; }
    if (outlived(c.id, t) || replayFire(c)) { gh.stats.fallback++; if (dbg) origLog(`ghostact t=${t} ${c.summary()} ${c.id} (${c.x},${c.y}) falls back to fight (recorded ${JSON.stringify(recActs.get(c.id) || [])})`); fight(c, oursC, oursS, his); }
    else if (dbg && recActs.get(c.id)) origLog(`ghostact t=${t} ${c.summary()} ${c.id} (${c.x},${c.y}) replays ${JSON.stringify(recActs.get(c.id))}`);
    const p = rec.pos.get(c.id);
    if (p) {
      if (c.x === p[0] && c.y === p[1]) continue;
      // a creep that cannot step by itself (tired, or no MOVE alive) and still moved in the record was PULLED — the record
      // does not write pulls: the creep of his standing on its next cell and leaving it this tick is the puller (World's
      // pull: the pulled one steps into the puller's cell). Seen in 6abc0dc7 t=1183: a crippled M5R5 moved with fatigue 26
      if (c.fatigue > 0 || live(c, M) === 0) {
        const pl = his.find((q) => q !== c && !q.spawning && q.x === p[0] && q.y === p[1] && rec.pos.get(q.id) && (rec.pos.get(q.id)[0] !== q.x || rec.pos.get(q.id)[1] !== q.y));
        if (pl) { pl.pull(c); c.move(pl); }
        continue;
      }
      goTo(c, { x: p[0], y: p[1] }, 0);
    } else {
      const near = oursC.filter((o) => range(c, o) <= 5).sort((a, b) => range(c, a) - range(c, b))[0];
      if (near) goTo(c, near, live(c, R) > 0 ? 3 : 1);
    }
  }
}
/** The fire of his creep on the tick it dies in the record, where the record keeps no action of it: first what its
 *  victims' hits say (inferDeathFire — our creeps' unexplained loss, his creeps' unexplained heal); then, for a weapon that
 *  found no victim there, each victim entry (`[victim, 'A', x, y]` at the creep's recorded cell — a structure, or a creep
 *  under a rampart) is a hit it dealt; the victim here is our creep matched to the recorded one, or our structure on the
 *  victim's cell. A melee hit when it stood beside the victim; a ranged hit single or mass by which of the two the victim's
 *  recorded loss is nearer (10 a RANGED_ATTACK part, or that times 1 / 0.4 / 0.1). */
function deathFire(c, t, oursC, oursS) {
  const at = rec.last.get(c.id);
  if (!at) return;
  const byRec = new Map([...ourMatch].map(([cr, id]) => [id, cr]));
  const used = new Set();
  const his = creeps().filter((q) => q.owner === 1 && !q.spawning);
  for (const sh of (REPLAY.deathFire.get(t) && REPLAY.deathFire.get(t).get(c.id)) || []) {
    if (sh.how === 'R') { c.rangedMassAttack(); used.add('ranged'); continue; }
    if (sh.how === 'h' || sh.how === 'H') { const f = his.find((q) => q.id === sh.victim); if (f) { if (sh.how === 'h') c.heal(f); else c.rangedHeal(f); } continue; }
    let v = byRec.get(sh.victim);
    const so = rec.objs.get(sh.victim);
    if (so && REC_KIND[so.kind]) v = world.objects.find((q) => q.exists && q.kind === REC_KIND[so.kind] && q.x === so.x && q.y === so.y && typeof q.hits === 'number');
    else if (!v || !v.exists) { const p = rec.last.get(sh.victim); v = p && oursC.find((q) => q.x === p[0] && q.y === p[1]); }
    if (!v) continue;
    if (sh.how === 'a') { c.attack(v); used.add('melee'); } else { c.rangedAttack(v); used.add('ranged'); }
  }
  for (const a of REPLAY.actsAt.get(t) || []) {
    if (a[1] !== 'A' || a[2] !== at[0] || a[3] !== at[1]) continue;
    let v = byRec.get(a[0]);
    if (!v || !v.exists) { const o = rec.objs.get(a[0]); const p = o ? [o.x, o.y] : rec.last.get(a[0]); v = p && (oursC.find((q) => q.x === p[0] && q.y === p[1]) || oursS.find((q) => q.x === p[0] && q.y === p[1] && q.hits !== undefined)); }
    if (!v) continue;
    const r = range(c, v);
    if (live(c, A) > 0 && r <= 1 && !used.has('melee')) { c.attack(v); continue; }
    if (live(c, R) === 0 || r > 3 || used.has('ranged')) continue;
    const drop = rec.drop.get(a[0]) || 0, single = 10 * live(c, R), mass = single * [1, 1, 0.4, 0.1][r];
    if (Math.abs(drop - mass) < Math.abs(drop - single)) c.rangedMassAttack(); else c.rangedAttack(v);
  }
}
/** The last step of his creep on the tick it dies in the record — not written either (the creep is gone from the state
 *  after the tick). The engine moves before it applies the tick's damage (World's creep tick; the processor's movement
 *  check counts every creep standing still as an obstacle, a dying one too), so when another creep enters the dying one's
 *  cell on that tick the dying one stepped out first. Measured over the 146 stored replays of this arena: 182 creeps
 *  entered the cell of a creep dying on that tick, and every one of the 182 dying creeps could step (fatigue 0, a live
 *  MOVE); of the 14 353 that died unable to step, not one cell was entered. 6abc107c t=857: his m4r5m1 dies on (65,7) and
 *  his healer steps onto it; the ghost kept the dying one in place, the healer stood a cell short on (66,7), our M5R5 shot
 *  it there (50 hits instead of 100) and the logs parted at 860. It steps the way the one entering its cell steps (a
 *  column walking on), else round that way, else into the entering one's cell (a swap). */
function lastStep(c, t, his) {
  if (c.fatigue > 0 || live(c, M) === 0) return;
  const byRec = new Map([...ourMatch].map(([cr, id]) => [id, cr]));
  let q = his.find((o) => o !== c && !o.spawning && rec.pos.get(o.id) && rec.pos.get(o.id)[0] === c.x && rec.pos.get(o.id)[1] === c.y && (o.x !== c.x || o.y !== c.y));
  if (!q) for (const [id, p] of rec.pos) { const o = byRec.get(id); if (o && o.exists && p[0] === c.x && p[1] === c.y && (o.x !== c.x || o.y !== c.y) && range(o, c) === 1) { q = o; break; } }
  if (!q || range(q, c) !== 1) return;
  const d = getDirection(c.x - q.x, c.y - q.y);
  const free = (dd) => { const [dx, dy] = DIRXY[dd]; const x = c.x + dx, y = c.y + dy; return inBounds(x, y) && !blockedAt(x, y, c.owner) && !creeps().some((o) => o !== q && !o.spawning && o.x === x && o.y === y); };
  const order = [d, (d % 8) + 1, ((d + 6) % 8) + 1];
  const pick = order.find(free);
  c.move(pick || getDirection(q.x - c.x, q.y - c.y));
}
const DIRXY = { 1: [0, -1], 2: [1, -1], 3: [1, 0], 4: [1, 1], 5: [0, 1], 6: [-1, 1], 7: [-1, 0], 8: [-1, -1] };
/** After the stub's tick t: what the record says of his base after that tick — his sites' progress, his structures that
 *  appear, his spawns' and towers' energy (the replay is the state after a tick, and so is this). */
function ghostAfter(t) {
  const his = creeps().filter((c) => c.owner === 1);
  const hisById = new Map(his.map((c) => [c.id, c]));
  // how closely his creeps walk their record: after the tick, on the recorded cell or not (GHOSTDBG=1 names the first).
  // Their fatigue is the record's: his squads pull one another, the record does not write pulls, and the MOVEs of the
  // pulled shed the head's fatigue (_add-fatigue.js) — stachu3478's M4R5M1 in 6abc107c went from 40 to 0 in a tick
  // without moving, 78 times, each time with three of his beside it; with its own MOVEs alone the ghost fell a tick
  // behind its record at t=590 and stayed behind
  for (const c of his) {
    const p = rec.pos.get(c.id);
    if (c.spawning || !p) continue;
    if (rec.fat.has(c.id)) c.fatigue = rec.fat.get(c.id);
    gh.stats.on++;
    if (p[0] !== c.x || p[1] !== c.y) { gh.stats.off++; if (process.env.GHOSTDBG && gh.stats.off < 30) origLog(`ghostdbg t=${t} ${c.summary()} ${c.id} at (${c.x},${c.y}) f=${c.fatigue} recorded (${p[0]},${p[1]})`); }
  }
  // building: a site of his is placed the tick before its first recorded build (live, our bot saw his spawn site at 0 a
  // tick before the replay's first progress, 6abc0dc7 t=54) if the creep that builds it then lives here; its progress is
  // the record's while a recorded builder of it lives here, and freezes for good when none does
  const builderAt = (k, x, y) => (REPLAY.actsAt.get(k) || []).filter((a) => a[1] === 'build' && a[2] === x && a[3] === y && rec.side.get(a[0]) === 1).map((a) => a[0]);
  for (const o of REPLAY.siteFirst.get(t + 1) || []) {
    if (gh.sites.has(o.id) || gh.dead.has(o.id)) continue;
    if (!builderAt(t + 1, o.x, o.y).some((id) => hisById.has(id))) { gh.dead.add(o.id); gh.stats.siteLost++; world.events.push(`t=${t} ghost: his ${o.structure} site at (${o.x},${o.y}) never placed — its builder is dead here`); continue; }
    const s = new ConstructionSite(o.x, o.y, 1, CONSTRUCTION_COST[o.structure] || o.energyCapacity);
    s.proto = PROTO[o.structure.replace('Structure', '').toLowerCase()] || null;
    s.recId = o.id;
    world.objects.push(s);
    gh.sites.set(o.id, s);
  }
  for (const [id, s] of gh.sites) {
    const se = rec.series.get(id);
    if (!s.exists || s.lost || !se || se.k !== t) continue;
    if (builderAt(t, s.x, s.y).some((b) => hisById.has(b))) s.progress = Math.min(s.progressTotal - 1, se.e);
    else { s.lost = true; gh.stats.siteLost++; world.events.push(`t=${t} ghost: his ${s.proto ? s.proto.name : '?'} site at (${s.x},${s.y}) stops at ${s.progress} — its builder is dead here`); }
  }
  // structures of his that the record finishes on this tick
  for (const o of rec.objs.values()) {
    if (o.side === REC_US || o.side === null || !PROTO[o.kind]) continue;
    const se = rec.series.get(o.id);
    const first = REPLAY.firstSeen.get(o.id);
    if (first !== t || !se) continue;
    const site = [...gh.sites.values()].find((s) => s.exists && s.x === o.x && s.y === o.y && !s.lost);
    const noSiteRecord = ![...rec.objs.values()].some((q) => q.kind === 'constructionSite' && q.side !== REC_US && q.x === o.x && q.y === o.y);
    if (site) { site.progress = site.progressTotal; const made = finishSite(site, t); if (made) made.recId = o.id; }
    else if (noSiteRecord) { const P = PROTO[o.kind]; const made = o.kind === 'spawn' ? new P(o.x, o.y, 1, 0) : o.kind === 'rampart' ? new P(o.x, o.y, 1, 10000) : o.kind === 'constructedWall' ? new P(o.x, o.y, 10000) : new P(o.x, o.y, 1); made.recId = o.id; world.objects.push(made); world.events.push(`t=${t} enemy built Structure${o.kind === 'constructedWall' ? 'Wall' : o.kind[0].toUpperCase() + o.kind.slice(1)} at (${o.x},${o.y})`); }
  }
  // his spawns' and towers' energy: the record's, every tick (the record writes a value only when it changes)
  for (const o of world.objects) {
    if (!o.exists || o.owner !== 1 || !o.recId || (o.kind !== 'spawn' && o.kind !== 'tower')) continue;
    const se = rec.series.get(o.recId);
    if (se) o.store.energy = Math.min(o.store.capacity, se.e);
  }
  // his creeps' own energy: a harvest of his is a real harvest (the source drains as live); a recorded build spends what
  // the build spends (its progress is the record's, above); a creep beside his spawn hands over its start-of-tick store
  // (harvest first, then the transfer of what it held — the rule measured on our side) — the record does not write
  // transfers, and his spawn's energy is the record's anyway. So his builder carries what it carried
  // live (the bot prints it: `hunt ... e=8/50`), and his harvesters do not pile energy under themselves
  const spawns1 = world.objects.filter((o) => o.exists && o.kind === 'spawn' && o.owner === 1);
  for (const c of his) {
    const b = (REPLAY.actsAt.get(t) || []).find((a) => a[0] === c.id && a[1] === 'build');
    if (b) c.store.energy = Math.max(0, c.store.energy - live(c, W) * 5);
    else if (spawns1.some((sp) => range(sp, c) <= 1)) c.store.energy = Math.max(0, c.store.energy - (c.s0 || 0)); // the start-of-tick store handed over, as live
  }
}
if (REPLAY) {
  REPLAY.firstSeen = new Map();
  REPLAY.birthsAt = new Map();
  REPLAY.outCell = new Map(); // creep id -> the cell it stepped out of its spawn onto
  REPLAY.actsAt = new Map(); // tick -> the record's actions of that tick
  REPLAY.siteFirst = new Map(); // tick -> his sites whose first recorded progress is on that tick
  const inside = new Set();
  for (const tk of REPLAY.ticks) {
    for (const [id] of tk.s || []) if (!REPLAY.firstSeen.has(id)) {
      REPLAY.firstSeen.set(id, tk.k);
      const o = rec.objs.get(id);
      if (o && o.kind === 'constructionSite' && o.side !== REC_US && o.side !== null) { if (!REPLAY.siteFirst.has(tk.k)) REPLAY.siteFirst.set(tk.k, []); REPLAY.siteFirst.get(tk.k).push(o); }
    }
    if (tk.k > 0 && tk.n) { REPLAY.birthsAt.set(tk.k, tk.n); for (const n of tk.n) inside.add(n[0]); }
    if (tk.a) REPLAY.actsAt.set(tk.k, tk.a);
    for (const [id, x, y, , , sp] of tk.u || []) if (inside.has(id) && !sp) { REPLAY.outCell.set(id, [x, y]); inside.delete(id); }
  }
  REPLAY.deathFire = inferDeathFire();
  recTo(0);
}
/**
 * What the record does not keep of a creep of his on the tick it dies, and the engine did. The replay is the state after a
 * tick, and a creep that died on it is gone from that state together with its own actionLog: neither its fire nor its last
 * step of that tick is written (6abc107c: 22 of 22 hits whose attacker has no action were dealt by a creep dying that tick).
 * Its victims do not make up for it: World's actionLog.attacked is ONE cell, the last hit processed, so a creep hit by two
 * names one of them — 6abc0dc7 t=1314: our M8R8 lost 100 to two M5R5s at range, its `A` names only the one that lived, the
 * other's last shot was lost, and from that 50 hits the stub parted from the match (our M8R8 at 1600 against 1550, then a
 * different retreat at 1351, its death at 1358 missing, the logs apart at 1359 and a win at 2280 against the live 3601).
 * So its fire is read off the hits: a creep of ours whose recorded loss this tick is more than the tick's recorded actions
 * deal (the engine's rule on the record's bodies and hits at the start of the tick: 10 a live RANGED_ATTACK, 30 an ATTACK,
 * 1 / 0.4 / 0.1 of it for a mass attack at 1 / 2 / 3, 12 / 4 a HEAL, a tower by range; nothing through a rampart) took the
 * rest from a creep dying in reach; so does a creep of his that gained more than the recorded heals give (a dying healer).
 * Returns tick -> Map(his dying creep -> [{how: 'r' | 'R' | 'a' | 'h' | 'H', victim: record id}]).
 */
function inferDeathFire() {
  const out = new Map();
  const cr = new Map(); // creeps: id -> {side, x, y, hits, body, sp}
  const st = new Map(); // structures with hits (spawns, towers, ramparts, extensions, the vault walls): id -> {side, x, y, hits, kind}
  const towers = new Map([...rec.objs.values()].filter((o) => o.kind === 'tower').map((o) => [o.id, o]));
  const partsAlive = (c, type) => { const n = c.body.length; let k = 0; for (let i = 0; i < n; i++) if (c.body[i] === type && c.hits > 100 * (n - 1 - i)) k++; return k; };
  const MASS = [1, 1, 0.4, 0.1];
  const ourSide = (v) => v.side === REC_US;
  for (const tk of REPLAY.ticks) {
    const start = new Map([...cr].map(([id, c]) => [id, { ...c }]));
    const stStart = new Map([...st].map(([id, o]) => [id, { ...o }]));
    for (const [id, side, x, y, hits, , body, sp] of tk.n || []) cr.set(id, { side, x, y, hits, body: bodyOf(body), sp: !!sp, creep: true });
    for (const [id, x, y, hits, , sp] of tk.u || []) { const c = cr.get(id); if (c) Object.assign(c, { x, y, hits, sp: !!sp }); }
    for (const [id, body] of tk.b || []) { const c = cr.get(id); if (c) c.body = bodyOf(body); }
    for (const [id, hits] of tk.s || []) {
      const o = rec.objs.get(id);
      if (!o || !['spawn', 'tower', 'rampart', 'extension', 'constructedWall'].includes(o.kind)) continue;
      if (hits > 0) st.set(id, { side: o.side, x: o.x, y: o.y, hits, kind: o.kind }); else if (st.has(id)) st.get(id).hits = 0;
    }
    const gone = new Set(tk.x || []);
    const acting = new Set((tk.a || []).map((a) => a[0]));
    const dying = [...gone].filter((id) => { const c = start.get(id); return c && !c.sp && !ourSide(c) && !acting.has(id); });
    if (dying.length) {
      // the start of the tick: what stands on each cell, and which cells a rampart covers
      const rampAt = new Map(), creepAt = new Map(), structAt = new Map();
      for (const [id, o] of stStart) if (o.hits > 0) (o.kind === 'rampart' ? rampAt : structAt).set(idx(o.x, o.y), id);
      for (const [id, c] of start) if (!c.sp) creepAt.set(idx(c.x, c.y), id);
      const at = (id) => start.get(id) || stStart.get(id);
      // a hit at a cell lands on the rampart there, else the creep, else the structure (World's attack/rangedAttack swap the
      // target for the rampart over it)
      const hitAt = (x, y) => rampAt.get(idx(x, y)) ?? creepAt.get(idx(x, y)) ?? structAt.get(idx(x, y));
      // a mass attack: every owned object of the other side in three, not what stands under a rampart (the rampart is hit)
      const massTargets = (c) => [...start, ...stStart].filter(([, v]) => !v.sp && (v.creep || v.hits > 0) && v.side !== null && v.side !== c.side &&
        range(c, v) <= 3 && (v.kind === 'rampart' || !rampAt.has(idx(v.x, v.y))));
      const known = new Map();
      const add = (id, d) => { if (id !== undefined) known.set(id, (known.get(id) || 0) + d); };
      for (const [src, how, x, y] of tk.a || []) {
        const c = start.get(src), tw = towers.get(src);
        if (c && how === 'R') { for (const [vid, v] of massTargets(c)) add(vid, partsAlive(c, R) * 10 * MASS[range(c, v)]); continue; }
        if (c && how === 'r') add(hitAt(x, y), partsAlive(c, R) * 10);
        else if (c && how === 'a') add(hitAt(x, y), partsAlive(c, A) * 30);
        else if (c && how === 'h') add(creepAt.get(idx(x, y)), -partsAlive(c, H) * 12);
        else if (c && how === 'H') add(creepAt.get(idx(x, y)), -partsAlive(c, H) * 4);
        else if (tw && how === 'a') add(hitAt(x, y), towerPower(TOWER_POWER_ATTACK, range(tw, { x, y })));
        else if (tw && how === 'h') add(creepAt.get(idx(x, y)), -towerPower(TOWER_POWER_HEAL, range(tw, { x, y })));
      }
      // the rest: start - known - end (positive: damage nobody recorded; negative: a heal nobody recorded). A creep ending at
      // full hits says nothing (a heal may have been capped); what died gives a lower bound
      const rest = new Map();
      for (const [id, v] of [...start, ...stStart]) {
        if (v.sp || !(v.creep || v.hits > 0)) continue;
        if (v.creep && rampAt.has(idx(v.x, v.y))) continue; // shielded: its hits say nothing of fire at it
        const end = v.creep ? (gone.has(id) ? 0 : cr.get(id).hits) : st.get(id).hits;
        const dead = end <= 0;
        if (v.creep && !dead && end >= v.body.length * 100) continue;
        const r = v.hits - (known.get(id) || 0) - end;
        if (r !== 0) rest.set(id, { r, dead });
      }
      const m = new Map();
      for (const id of dying.sort()) {
        const c = start.get(id);
        const shots = [];
        const nA = partsAlive(c, A), nR = partsAlive(c, R), nH = partsAlive(c, H);
        // his fire goes at what is not his: our creeps and structures, the neutral vault walls
        const foes = [...rest].filter(([vid]) => { const v = at(vid); return v.side !== c.side && (v.creep ? true : v.kind === 'rampart' || !rampAt.has(idx(v.x, v.y))); });
        const takes = (e, d) => e.r >= d - 0.5 || (e.dead && e.r > 0);
        if (nA > 0) {
          const v = foes.find(([vid, e]) => range(c, at(vid)) <= 1 && takes(e, nA * 30));
          if (v) { shots.push({ how: 'a', victim: v[0] }); v[1].r -= nA * 30; }
        }
        if (nR > 0) {
          const inR = massTargets(c);
          const single = foes.filter(([vid, e]) => range(c, at(vid)) <= 3 && takes(e, nR * 10)).sort((a, b) => Math.abs(a[1].r - nR * 10) - Math.abs(b[1].r - nR * 10))[0];
          const mass = inR.length > 0 && inR.every(([vid, v]) => rest.get(vid) && takes(rest.get(vid), nR * 10 * MASS[range(c, v)])) && (inR.length > 1 || range(c, inR[0][1]) > 1);
          if (mass && (inR.length > 1 || !single)) { shots.push({ how: 'R' }); for (const [vid, v] of inR) rest.get(vid).r -= nR * 10 * MASS[range(c, v)]; }
          else if (single) { shots.push({ how: 'r', victim: single[0] }); single[1].r -= nR * 10; }
        }
        if (nH > 0) {
          const his = [...rest].filter(([vid, e]) => vid !== id && start.get(vid) && start.get(vid).side === c.side && e.r < 0 && range(c, start.get(vid)) <= 3);
          const adj = his.find(([vid, e]) => range(c, start.get(vid)) <= 1 && -e.r >= nH * 12 - 0.5);
          const far = his.find(([, e]) => -e.r >= nH * 4 - 0.5);
          if (adj) { shots.push({ how: 'h', victim: adj[0] }); adj[1].r += nH * 12; }
          else if (far) { shots.push({ how: 'H', victim: far[0] }); far[1].r += nH * 4; }
        }
        if (shots.length) m.set(id, shots);
      }
      if (m.size) out.set(tk.k, m);
    }
    for (const id of gone) cr.delete(id);
    for (const [id, o] of st) if (o.hits <= 0) st.delete(id);
  }
  return out;
}
// ---------- the personas: live bots of the field, rebuilt from their replays, answering what we do ----------
// A persona plays its own economy with real intents (harvest, transfer, build, withdraw — the engine's, not a granted
// income), so killing its builder or harvester cuts it; its army picks its targets from the state of this tick. Bodies are
// in the replay's part order. What the replays show is measured (the numbers are named with their source); the rules no
// replay shows are marked INVENTED. Nothing here is tuned to lose: a persona built to lose measures nothing.
const SOURCES = () => world.objects.filter((o) => o.exists && o.kind === 'source');
const walkable = (x, y) => inBounds(x, y) && x > 0 && y > 0 && x < 99 && y < 99 && terrainAt(x, y) !== 1 &&
  !world.objects.some((o) => o.exists && o.x === x && o.y === y && (o.kind === 'wall' || o.kind === 'spawn' || o.kind === 'tower' || o.kind === 'extension' || o.kind === 'site' || o.kind === 'container'));
/** Path ticks of a W4C1M5-like body (plain 1, swamp 5 — the bot's own `pathTicks`), terrain only. */
const pathTicks = (from, to) => { const r = searchPath(from, { pos: to, range: 1 }, { plainCost: 1, swampCost: 5 }); return r.incomplete ? 1e6 : r.cost; };
/** A base at a source, planned as live bases stand: the spawn two cells from the source on the cell with the most cells
 *  beside both (a harvester stands there and delivers without a step), the harvester's slot one of those, a second one for
 *  the founder while he builds, the tower beside the slot (けろびー#19: spawn (49,20), slot (48,21), tower (47,20); #23:
 *  (50,79), (49,78), (48,78)). */
function planBaseAt(src, from) {
  let best = null, bs = -1;
  for (let dx = -2; dx <= 2; dx++) for (let dy = -2; dy <= 2; dy++) {
    const c = { x: src.x + dx, y: src.y + dy };
    if (range(c, src) !== 2 || !walkable(c.x, c.y)) continue;
    const both = [], out = [];
    for (let ax = -1; ax <= 1; ax++) for (let ay = -1; ay <= 1; ay++) {
      const a = { x: c.x + ax, y: c.y + ay };
      if ((!ax && !ay) || !walkable(a.x, a.y)) continue;
      (range(a, src) === 1 ? both : out).push(a);
    }
    if (!both.length || !out.length) continue;
    const score = both.length * 1000 + (terrainAt(c.x, c.y) === 2 ? 0 : 100) - range(c, from);
    if (score > bs) { bs = score; best = { spawn: c, slot: both[0], work: both[1] || both[0], src }; }
  }
  if (!best) return null;
  const tw = [];
  for (let ax = -1; ax <= 1; ax++) for (let ay = -1; ay <= 1; ay++) {
    const a = { x: best.slot.x + ax, y: best.slot.y + ay };
    if ((!ax && !ay) || !walkable(a.x, a.y) || range(a, src) < 2 || (a.x === best.spawn.x && a.y === best.spawn.y)) continue;
    tw.push(a);
  }
  best.tower = tw.sort((a, b) => range(a, best.spawn) - range(b, best.spawn))[0] || null;
  return best;
}
const his1 = () => creeps().filter((c) => c.owner === 1);
const spawnAt = (p) => world.objects.find((o) => o.exists && o.kind === 'spawn' && o.x === p.x && o.y === p.y);
const siteAt = (p, owner = 1) => world.objects.find((o) => o.exists && o.kind === 'site' && o.owner === owner && o.x === p.x && o.y === p.y);
const structAt = (p, kind) => world.objects.find((o) => o.exists && o.kind === kind && o.x === p.x && o.y === p.y);
const dirTo = (from, to) => getDirection(to.x - from.x, to.y - from.y);
const dpsOf = (c) => live(c, A) * 30 + live(c, R) * 10;
const PB = (spec) => [].concat(...[...spec.matchAll(/([a-z])(\d+)/g)].map(([, k, n]) => Array(parseInt(n, 10)).fill(PART[k])));
/** A builder's cycle, as live builders work: harvest while the batch is not full (the bot's `batchFor`: 40 for W4C1), then
 *  build — one of the two a tick (the engine's priority). `batch` 1: build whenever it holds energy — harvest, build,
 *  harvest, build (けろびー#20/#22/#23's founder, below). */
function buildCycle(c, src, site, batch = Math.min(c.store.capacity, 40)) {
  const e = c.store.energy;
  if (site && (e >= batch || (src.energy === 0 && e > 0)) && range(c, site) <= 3) c.build(site);
  else if (src.energy > 0 && range(c, src) <= 1) c.harvest(src);
}
const pers = { role: new Map(), base: new Map(), bases: [], plan: null, st: {}, spawned: 0, left: new Set(), reached: new Set(), ev: (s) => world.events.push(`t=${world.tick} ${scenario}: ${s}`) };
function pOrder(sp, spec, role, dir) {
  if (!sp || !sp.exists || sp.spawning) return false;
  const r = sp.spawnCreep(PB(spec));
  if (!r.object) return false;
  if (dir) sp.spawning.directions = [dir, ...[1, 2, 3, 4, 5, 6, 7, 8].filter((d) => d !== dir)];
  pers.role.set(r.object.id, role); pers.spawned++;
  pers.ev(`${spec} as ${role} at (${sp.x},${sp.y})`);
  return true;
}
/** The next source for a founder of his: the nearest by path among the free ones — no spawn or spawn site of anyone within
 *  four cells, none of our combat creeps within twelve (INVENTED: a live founder is seen to skip sources under our army),
 *  and not two thirds of the way into our half (his bases stayed on his side and the centre; INVENTED as a rule). */
function nextSource(from) {
  const ours = creeps().filter((c) => c.owner === 0 && dpsOf(c) > 0);
  const taken = (s) => world.objects.some((o) => o.exists && (o.kind === 'spawn' || (o.kind === 'site' && o.proto && o.proto.name === 'StructureSpawn')) && range(o, s) <= 4);
  const cand = SOURCES().filter((s) => !taken(s) && !ours.some((c) => range(c, s) <= 12) && range(s, theirs.start) <= range(s, ours0()) * 1.5 + 10);
  return cand.map((s) => [s, pathTicks(from, s)]).sort((a, b) => a[1] - b[1])[0]?.[0] || null;
}
const ours0 = () => world.objects.find((o) => o.exists && o.owner === 0 && o.kind === 'spawn') || ours.start;

// 'kerobii' — けろびー#19/#20 (bodies m5r5 / m4h2); 'kerobii22' — #22/#23 (t4m8r3a1 / m4h2). Replays: 6abc1c61 (#19, right),
//   6abc0dc7 (#20, left), 6abc0b76 (#22, right), 6abc21a1 (#23, left). Measured:
//   - one founder, his starting W4C1M5, raises every base: spawns at 231/716/1199 (#19), 300/939/1579 (#20), 296/936
//     (#22), 294/933/1690/2457 (#23) — first the central source nearest his start (left: (50,77); right: (49,22)), then his
//     home corner, then the far ones; a spawn site goes down on arrival and is built by his own harvest (5.7 a tick);
//   - on a new spawn he fills it by hand (harvest + transfer, 9 a tick) to a W5C1 without MOVE, born onto its slot
//     63 ticks later (294 for 231); a rampart over the slot ~108 after the spawn, the tower beside the slot ~280 after it
//     (#19: 339, 517), then he leaves for the next source;
//   - each base's spawn orders its W5C1, then the army: a fighter, a medic, then three fighters to a medic, round and round
//     (#19 6abc1c61: m5r5 434, m4h2 500, m5r5 615 / 706 / 802, m4h2 825; #20 6abc0dc7: 503, 569, 691 / 777 / 869, 920;
//     #22 6abc0b76 and #23 6abc21a1 the same with t4m8r3a1: 514, 562, 700 / 781 / 869, 903 and 512, 560, 698 / 781 / 871,
//     905 — the ticks each is first out of its spawn), as its energy allows (11 a tick a base);
//   - his army, measured on all 68 stored replays of #19-#23 against us (`python3 kerobii.py` beside this file reads them
//     off `tools/match-log.py list --arena spawn-and-swamp-advanced` and prints every number below), has no rally and no
//     ball: every fighter walks out as it is born — from birth to 12 cells off every spawn of his, median 33 ticks (p10 13,
//     p90 187) over 7 167 fighters, half of them (3 582) with no other armed creep of his within 6 as they leave; the first
//     fighter always alone (#20 6abc0dc7: born 503, out 540, on our second base's rampart (50,21) at 637 and on it until
//     866; #23 6abc21a1: 512 / 558 / 626, on it until 841; #22 6abc0b76: 514 / 553 / at our base (50,79) by 589);
//   - it goes for our base nearest to his spawns (kerobiiTarget); a base with no fed tower of ours it walks onto (beside our
//     nearest structure of it), a base under one it stands off at 12-17 from our nearest structure (KERO_HOLD);
//   - the one strength test is local: against our armed creep within 7 he closes in when the damage of his armed round him
//     is at least 1.5 times that of ours round it, else he holds 4-5 off (KERO_ENGAGE — the steepest step of a measured
//     slope); a worker of ours within 5 he closes on to adjacent;
//   - no push rule against our towers and no retreat rule is modelled, because the replays show none: his largest group
//     coming within 14 of our fed tower for 10 ticks (155 such pushes) against it standing at 15-26 (3 067 samples) does
//     not separate by his group's size (6 or fewer: 55 % of pushes, 35 % of holds), its damage, our damage within 15 (none:
//     27 % / 48 %) or our armed in all; his "retreats" are his largest group changing. When 3+ of ours stand within 12 of a
//     spawn of his, his creeps farther than 20 from it step toward it 20 % of the ticks and away 9 % (without an attack
//     16 % / 22 %): a drift, not a recall — not modelled either;
//   - his fire (keroFire): a mass attack whenever anything of ours is adjacent, else a single shot at the weakest (by share
//     of its hits) of our creeps in three, else at our nearest structure — 82 605 mass attacks, 61 160 single shots at our
//     creeps, 21 936 at our structures.
function kerobiiTick(t) {
  const variant = scenario === 'kerobii22' ? 't4m8r3a1' : 'm5r5';
  // his founder's rhythm (the replays' own action lists, spawn site to spawn): #19 harvests two or three ticks and builds
  // one (125 harvests, 50 builds; 6abc1c61, spawn at 231), #20/#22/#23 harvest one tick and build the next (125 / 124;
  // 6abc0dc7, 6abc0b76, 6abc21a1, spawns at 300 / 296 / 294) — a build of 8 where #19 builds 20. His spawns only: the
  // rampart and the tower of #23 are built by his W5C1 (6abc21a1: harvest, harvest, build, 378-402 and 404-652) while the
  // founder harvests into the spawn; the founder here builds them at the old pace, which puts them near the live ticks
  const batch = scenario === 'kerobii22' ? 1 : undefined;
  const mine = his1(), oursC = creeps().filter((c) => c.owner === 0 && !c.spawning);
  const oursS = world.objects.filter((o) => o.exists && o.owner === 0 && o.kind !== 'creep' && o.kind !== 'site');
  // the founder: his starting worker (no other of his builds bases)
  const f = mine.find((c) => pers.role.get(c.id) === 'founder' || (c === theirs.worker && !pers.role.has(c.id)));
  if (f && !pers.role.has(f.id)) pers.role.set(f.id, 'founder');
  if (f && !f.spawning) {
    if (!pers.plan) {
      // his order (all four replays): the central source of his half first, his home corner second, then the nearest free
      const half = SOURCES().filter((q) => (q.y > 50) === (theirs.start.y > 50));
      const central = half.sort((a, b) => (Math.abs(a.x - 50) + Math.abs(a.y - 50)) - (Math.abs(b.x - 50) + Math.abs(b.y - 50)))[0];
      const free = (q) => q && !world.objects.some((o) => o.exists && (o.kind === 'spawn' || (o.kind === 'site' && o.proto && o.proto.name === 'StructureSpawn')) && range(o, q) <= 4);
      const n = pers.bases.length;
      const corner = half.filter((q) => q !== central).sort((a, b) => pathTicks(central, a) - pathTicks(central, b))[0];
      const s = n === 0 && free(central) ? central : n === 1 && free(corner) ? corner : nextSource(f);
      pers.plan = s ? planBaseAt(s, f) : null;
      if (pers.plan) { pers.plan.phase = 'go'; pers.ev(`founder -> source (${s.x},${s.y}) spawn (${pers.plan.spawn.x},${pers.plan.spawn.y})`); }
    }
    const P = pers.plan;
    if (P) {
      const sp = spawnAt(P.spawn);
      const cell = (P.phase === 'go' || P.phase === 'spawn' || P.phase === 'fill') ? P.work : P.work;
      if (f.x !== cell.x || f.y !== cell.y) { if (range(f, P.src) > 1 || P.phase === 'go') goTo(f, cell, 0); }
      if (range(f, P.src) <= 1) {
        if (P.phase === 'go') P.phase = 'spawn';
        if (P.phase === 'spawn') {
          if (sp && sp.owner === 1) { P.phase = 'fill'; pers.bases.push(P); P.spawnObj = sp; pers.ev(`spawn up (${sp.x},${sp.y})`); }
          else {
            let site = siteAt(P.spawn);
            if (!site) { const r = createConstructionSite(P.spawn.x, P.spawn.y, StructureSpawn); site = r.object; if (!site) { pers.plan = null; } }
            if (site) buildCycle(f, P.src, site, batch);
          }
        }
        if (P.phase === 'fill') {
          const harv = mine.find((c) => pers.base.get(c.id) === P && pers.role.get(c.id) === 'harvester');
          if (harv && !harv.spawning) P.phase = 'rampart';
          else if (sp) { if (f.store.energy > 0 && range(f, sp) <= 1) f.transfer(sp, 'energy'); if (P.src.energy > 0) f.harvest(P.src); }
        }
        if (P.phase === 'rampart') {
          if (structAt(P.slot, 'rampart')) P.phase = P.tower ? 'tower' : 'leave';
          else { let site = siteAt(P.slot); if (!site) site = createConstructionSite(P.slot.x, P.slot.y, StructureRampart).object; if (site) buildCycle(f, P.src, site); else P.phase = 'tower'; }
        }
        if (P.phase === 'tower') {
          if (structAt(P.tower, 'tower')) P.phase = 'leave';
          else { let site = siteAt(P.tower); if (!site) site = createConstructionSite(P.tower.x, P.tower.y, StructureTower).object; if (site) buildCycle(f, P.src, site); else P.phase = 'leave'; }
        }
        if (P.phase === 'leave') pers.plan = null;
      }
    }
  }
  // the bases: harvester (born onto its slot), tower fed, then the army 3:1
  for (const B of pers.bases) {
    const sp = spawnAt(B.spawn);
    if (!sp || sp.owner !== 1) continue;
    const harv = mine.find((c) => pers.base.get(c.id) === B && pers.role.get(c.id) === 'harvester');
    const tw = B.tower && structAt(B.tower, 'tower');
    if (tw && tw.owner === 1) towerFire(tw, oursC);
    if (harv && !harv.spawning) {
      const src = B.src;
      if (harv.store.energy > 0) {
        if (tw && tw.store.energy < 10 && range(harv, tw) <= 1) harv.transfer(tw, 'energy');
        else if (range(harv, sp) <= 1 && sp.store.free() > 0) harv.transfer(sp, 'energy');
      }
      if (src.energy > 0 && harv.store.free() >= live(harv, W) * 2) harv.harvest(src);
    }
    if (sp.spawning) continue;
    if (!harv) {
      if (sp.store.energy >= 600 && pOrder(sp, 'w5c1', 'harvester', dirTo(sp, B.slot))) { const c = world.objects[world.objects.length - 1]; pers.base.set(c.id, B); }
      continue;
    }
    B.n = B.n || 0;
    const spec = (B.n % 4 === 1) ? 'm4h2' : variant; // fighter, medic, then three fighters to a medic (the header)
    const cost = Creep.cost(PB(spec));
    if (sp.store.energy >= cost && pOrder(sp, spec, spec === 'm4h2' ? 'medic' : 'fighter')) B.n++;
  }
  // the army — measured on the 68 stored replays of #19-#23 against us (the header): no ball, no rally, no strength test.
  // Each fighter walks out as soon as it is born to the target base; there it stands off our towers and fires at what
  // comes into reach
  const army = mine.filter((c) => !c.spawning && (pers.role.get(c.id) === 'fighter' || pers.role.get(c.id) === 'medic'));
  if (!army.length) return;
  const T = kerobiiTarget();
  const fighters = army.filter((c) => dpsOf(c) > 0);
  for (const c of army) {
    keroFire(c, oursC, oursS, mine);
    if (c.outAt === undefined) c.outAt = t; // out of its spawn: the replay's first tick of the creep not spawning
    if (!pers.left.has(c.id) && !pers.bases.some((B) => spawnAt(B.spawn) && range(c, B.spawn) <= 12)) {
      pers.left.add(c.id);
      if (pers.left.size <= 6) pers.ev(`${c.summary()} ${c.id} out of home (ordered ${c.bornAt}, out of its spawn ${c.outAt}) with ${army.filter((q) => range(q, c) <= 6).length} of his armed within 6, target ${T ? `(${T.pos.x},${T.pos.y})` : '-'}`);
    }
    if (T && !pers.reached.has(c.id) && T.structs.concat(T.sites).some((o) => range(o, c) <= 8)) {
      pers.reached.add(c.id);
      if (pers.reached.size <= 6) pers.ev(`${c.summary()} ${c.id} at our base (${T.pos.x},${T.pos.y}) with ${army.filter((q) => range(q, c) <= 6).length} of his armed within 6`);
    }
    if (pers.role.get(c.id) === 'medic') {
      const hurt = army.filter((o) => o !== c && o.hits < o.hitsMax).sort((a, b) => a.hits / a.hitsMax - b.hits / b.hitsMax)[0];
      goTo(c, hurt || fighters.sort((a, b) => range(a, c) - range(b, c))[0] || (T && T.pos) || theirs.start, 1);
      continue;
    }
    // our armed creep within 7: he closes in when the damage of his armed within 6 of him is at least KERO_ENGAGE times
    // that of ours within 6 of it, else he keeps out of its reach at 4-5 (kerobii.py, table `strength`)
    const foe = oursC.filter((o) => dpsOf(o) > 0).sort((a, b) => range(a, c) - range(b, c))[0];
    if (foe && range(c, foe) <= 7) {
      const mineD = fighters.filter((q) => range(q, c) <= 6).reduce((a, q) => a + dpsOf(q), 0);
      const theirD = oursC.filter((o) => range(o, foe) <= 6).reduce((a, o) => a + dpsOf(o), 0);
      if (mineD >= KERO_ENGAGE * theirD) { goTo(c, foe, 1); continue; }
      if (range(c, foe) <= 3) { const away = getDirection(c.x - foe.x, c.y - foe.y); if (away) c.move(away); continue; }
      if (range(c, foe) <= 5) continue;
    }
    // a worker of ours within 5 he closes on to adjacent — under a rampart or not (kerobii.py, table `engage`: with our
    // worker the nearest of ours, his m5r5 stood adjacent to it 59 % of 38 321 creep-ticks when it was under a rampart,
    // his t4m8r3a1 36 %, the next ring 15 % / 28 %); our armed creeps he does not chase (the range to the nearest armed
    // one of ours within 5 is spread flat, 1: 15 %, 2: 14 %, 3: 19 %, 4: 20 %, 5: 30 % of 122 953 for m5r5) — keroFire
    // shoots whatever comes within three
    const near = oursC.filter((o) => range(o, c) <= 5 && dpsOf(o) === 0 && live(o, W) > 0).sort((a, b) => range(a, c) - range(b, c))[0];
    if (near) { goTo(c, near, 1); continue; }
    if (!T) { const any = oursC.concat(oursS).sort((a, b) => range(a, c) - range(b, c))[0]; if (any) goTo(c, any, live(c, R) > 0 ? 3 : 1); continue; }
    // no fed tower: onto the base, beside our nearest structure of it (#20's first m5r5 on (51,21) beside our rampart (50,21)
    // 642-866, #23's t4m8r3a1 on the same cell 631-841, swinging at the rampart and mass-attacking every tick)
    if (!T.fed) { goTo(c, T.structs.length ? T.structs.sort((a, b) => range(a, c) - range(b, c))[0] : T.pos, 1); continue; }
    // a fed tower covers the base: stand off at KERO_HOLD from our nearest structure of it
    const st = T.structs.sort((a, b) => range(a, c) - range(b, c))[0] || T.pos;
    const r = range(c, st);
    if (r > KERO_HOLD[1]) goTo(c, st, KERO_HOLD[1]);
    else if (r < KERO_HOLD[0]) { const away = getDirection(c.x - st.x, c.y - st.y); if (away) c.move(away); }
  }
}
/** His creeps' fire, measured (kerobii.py, table `fire`): a mass attack whenever anything of ours is adjacent — a creep
 *  not under a rampart, or a structure (99 % of 83 267 ranged creep-ticks with something of ours adjacent) — else a
 *  single shot (95 % of 79 262) at our creep in three with the lowest share of its hits (the rule naming his target in 91 % of
 *  17 746 single shots with two or more candidates; armed first, then lowest hits: 80 %), else at our nearest structure
 *  in three; a swing at the adjacent creep with the lowest share of its hits (95 % of 782), else at an adjacent structure;
 *  a medic heals as `fight` does. */
function keroFire(c, oursC, oursS, friends) {
  const share = (o) => o.hits / o.hitsMax;
  const exposed = oursC.filter((o) => !onRampart(o));
  if (live(c, A) > 0) {
    const adj = oursC.filter((o) => range(c, o) <= 1).sort((a, b) => share(a) - share(b));
    const st = oursS.filter((o) => range(c, o) <= 1 && o.hits !== undefined).sort((a, b) => (a.kind === 'rampart') - (b.kind === 'rampart'));
    if (adj.length) c.attack(adj[0]); else if (st.length) c.attack(st[0]);
  }
  if (live(c, R) > 0) {
    const adjacent = exposed.some((o) => range(c, o) <= 1) || oursS.some((o) => range(c, o) <= 1 && o.hits !== undefined);
    const inR = exposed.filter((o) => range(c, o) <= 3).sort((a, b) => share(a) - share(b));
    if (adjacent) c.rangedMassAttack();
    else if (inR.length) c.rangedAttack(inR[0]);
    else { const st = oursS.filter((o) => range(c, o) <= 3 && o.hits !== undefined).sort((a, b) => range(c, a) - range(c, b))[0]; if (st) c.rangedAttack(st); }
  }
  if (live(c, H) > 0) fight(c, [], [], friends);
}
/** Our bases as he sees them (kerobii.py's own grouping, table `target`): what of ours stands within 3 of a
 *  source — spawn, tower, rampart, and any construction site — or inside a vault frame, one base a source or vault; its
 *  position is its spawn, else its spawn site, else its first object. The target is the base nearest (range) to the
 *  centroid of his spawns: 78 % of 5 008 samples where his largest group (3+) stood within 25 of one of our bases, over
 *  the 68 replays (our youngest base: 74 %, the base nearest to his first spawn: 76 %, nearest to any spawn of his: 64 %,
 *  our oldest: 25 %). `fed`: a tower of ours with energy for a shot within 20 of it. */
function kerobiiTarget() {
  const VB = [{ x0: 38, x1: 46, y0: 27, y1: 36 }, { x0: 53, x1: 61, y0: 63, y1: 72 }];
  const srcs = SOURCES();
  const m = new Map();
  for (const o of world.objects) {
    if (!o.exists || o.owner !== 0 || !(o.kind === 'site' || o.kind === 'spawn' || o.kind === 'tower' || o.kind === 'rampart' || o.kind === 'extension')) continue;
    const vi = VB.findIndex((v) => o.x > v.x0 && o.x < v.x1 && o.y > v.y0 && o.y < v.y1);
    let key = vi >= 0 ? 'V' + vi : null;
    if (key === null) { const s = srcs.slice().sort((a, b) => range(a, o) - range(b, o))[0]; if (!s || range(s, o) > 3) continue; key = s.id; }
    if (!m.has(key)) m.set(key, []);
    m.get(key).push(o);
  }
  if (!m.size) return null;
  const hs = world.objects.filter((o) => o.exists && o.owner === 1 && o.kind === 'spawn');
  const hc = hs.length ? { x: hs.reduce((a, o) => a + o.x, 0) / hs.length, y: hs.reduce((a, o) => a + o.y, 0) / hs.length } : theirs.start;
  const bases = [...m.values()].map((objs) => {
    const pos = objs.find((o) => o.kind === 'spawn') || objs.find((o) => o.kind === 'site' && o.proto && o.proto.name === 'StructureSpawn') || objs[0];
    return { pos, structs: objs.filter((o) => o.kind !== 'site'), sites: objs.filter((o) => o.kind === 'site') };
  });
  const T = bases.sort((a, b) => Math.max(Math.abs(a.pos.x - hc.x), Math.abs(a.pos.y - hc.y)) - Math.max(Math.abs(b.pos.x - hc.x), Math.abs(b.pos.y - hc.y)))[0];
  T.fed = world.objects.some((o) => o.exists && o.owner === 0 && o.kind === 'tower' && o.store.energy >= TOWER_ENERGY_COST && range(o, T.pos) <= 20);
  return T;
}
/** The stand-off band round a base under our fed tower: where his out creeps stood with a fed tower of ours within 20 —
 *  the range to our nearest structure, 651 764 creep-ticks over the 68 replays: 0-5 7 %, 6-8 6 %, 9-11 13 %, 12-14 26 %,
 *  15-17 24 %, 18-20 20 % — the two modal bins; outside it he steps in, inside it out. */
const KERO_HOLD = [12, 17];
/** His armed creeps' approach to our nearest armed creep at range 4-7, by R = the damage of his armed within 6 of him over
 *  that of ours within 6 of it (kerobii.py, table `strength`, 68 replays; closer minus away per tick, no fed tower of ours
 *  within 20 / one): R <= 1: -6 % and -3 % / -2 % and +2 %; 1-1.5: +7 % / +15 %; 1.5-2: +20 % / +19 %; 2-3: +15 % to
 *  +31 % / +15 % to +24 %; above 3: +39 % to +42 % / +25 % to +31 %. Below the step at 1.5 he holds out of reach, above it
 *  he closes in — his approach is a slope, the persona takes its steepest step for a threshold (6abc0b76: his first
 *  t4m8r3a1 and medic held 5 off our M5R5 from 588 to 940 and closed when the fourth fighter came, 240 against 100) */
const KERO_ENGAGE = 1.5;

// 'ricardo' — ricardo18informatica2020#1, replay 6abbba40 (left; won at 952). Measured:
//   - his founder raises a spawn at the source nearest his start by path — his home corner ((32,98) from the left) — by 241
//     and stays there harvesting into it (the spawn's energy goes up 9 a tick);
//   - orders: m5a5 (650) at 312, then a w4c2m6 (800) at 401, then m5a5 as the energy comes, two more w4c1m5 founders at 623
//     and 701 (a spawn at the central source (48,78) by 883, a site at (2,32) at 800);
//   - his first m5a5s break the frame of the vault on his half — the wall (56,72) fell at 463 (10000 at 150 a tick) — and
//     the w4c2m6 walks in and raises a spawn at (56,69) out of the containers by 550 (16.6 a tick: withdraw and build in
//     one tick); that spawn orders an m5a5 every 30 ticks off the vault's 10000;
//   - the m5a5s come for our bases in small groups (largest 3, tools/replay.py track), first hit on ours at 692, our first
//     base fell at 858.
//   INVENTED: the breach is the frame's wall nearest his spawn by path; a group leaves at three; each m5a5 goes for our
//   nearest creep within eight, else our nearest structure (a spawn before a rampart), and never retreats.
function vaultOf(from) {
  const vs = [{ cont: [[42, 31], [42, 32], [43, 31], [43, 32]], x0: 38, x1: 46, y0: 27, y1: 36 }, { cont: [[56, 67], [56, 68], [57, 67], [57, 68]], x0: 53, x1: 61, y0: 63, y1: 72 }];
  return vs.map((v) => [v, pathTicks(from, { x: v.cont[0][0], y: v.cont[0][1] })]).sort((a, b) => a[1] - b[1])[0][0];
}
function ricardoTick(t) {
  const mine = his1(), oursC = creeps().filter((c) => c.owner === 0 && !c.spawning);
  const oursS = world.objects.filter((o) => o.exists && o.owner === 0 && o.kind !== 'creep' && o.kind !== 'site');
  const S = pers.st;
  const f = mine.find((c) => pers.role.get(c.id) === 'founder' || (c === theirs.worker && !pers.role.has(c.id)));
  if (f && !pers.role.has(f.id)) pers.role.set(f.id, 'founder');
  // the home base: the source nearest his start by path; the founder builds it and stays delivering
  if (!S.home) {
    const s = SOURCES().map((q) => [q, pathTicks(theirs.start, q)]).sort((a, b) => a[1] - b[1])[0][0];
    S.home = planBaseAt(s, theirs.start);
    S.vault = vaultOf(theirs.start);
    S.q = 0;
  }
  const H = S.home, home = spawnAt(H.spawn);
  if (f && !f.spawning) {
    if (range(f, H.src) > 1 || (f.x !== H.slot.x || f.y !== H.slot.y)) goTo(f, H.slot, 0);
    if (range(f, H.src) <= 1) {
      if (!home) { let site = siteAt(H.spawn); if (!site) site = createConstructionSite(H.spawn.x, H.spawn.y, StructureSpawn).object; if (site) buildCycle(f, H.src, site); }
      else { if (f.store.energy > 0) f.transfer(home, 'energy'); if (H.src.energy > 0) f.harvest(H.src); }
    }
  }
  // the vault: breach cell (the frame wall nearest his home by path), then the builder's spawn inside
  const V = S.vault;
  if (!S.breach) {
    const walls = world.objects.filter((o) => o.exists && o.kind === 'wall' && o.x >= V.x0 && o.x <= V.x1 && o.y >= V.y0 && o.y <= V.y1);
    const outside = (w) => [[0, -1], [0, 1], [-1, 0], [1, 0]].map(([dx, dy]) => ({ x: w.x + dx, y: w.y + dy })).filter((p) => terrainAt(p.x, p.y) !== 1 && !(p.x > V.x0 && p.x < V.x1 && p.y > V.y0 && p.y < V.y1) && !walls.some((q) => q.x === p.x && q.y === p.y));
    const cand = walls.map((w) => [w, Math.min(...outside(w).map((p) => pathTicks(H.spawn, p)), 1e6)]).sort((a, b) => a[1] - b[1]);
    S.breach = cand[0] ? { x: cand[0][0].x, y: cand[0][0].y } : null;
    const inner = [];
    for (const [cx, cy] of V.cont) for (let dx = -1; dx <= 1; dx++) for (let dy = -1; dy <= 1; dy++) inner.push({ x: cx + dx, y: cy + dy });
    const cells = inner.filter((p) => walkable(p.x, p.y) && p.x > V.x0 && p.x < V.x1 && p.y > V.y0 && p.y < V.y1).sort((a, b) => range(a, S.breach) - range(b, S.breach));
    S.vspawn = cells[0];
    // where the builder stands: beside a container and in reach of the spawn cell, never on it (a spawn's site is not
    // raised under a creep — the first version stood on it and the vault spawn never rose)
    S.vstand = cells.find((p) => (p.x !== S.vspawn.x || p.y !== S.vspawn.y) && range(p, S.vspawn) <= 3);
  }
  const open = S.breach && !structAt(S.breach, 'wall');
  const vsp = S.vspawn && spawnAt(S.vspawn);
  // orders: m5a5, w4c2m6 (the vault builder), then m5a5; one extra founder at the central source (INVENTED as one)
  const LIST = ['m5a5', 'w4c2m6', 'm5a5', 'm5a5', 'w4c1m5'];
  for (const sp of [home, vsp]) {
    if (!sp || sp.owner !== 1 || sp.spawning) continue;
    const spec = sp === home && S.q < LIST.length ? LIST[S.q] : 'm5a5';
    if (sp.store.energy >= Creep.cost(PB(spec)) && pOrder(sp, spec, spec === 'w4c2m6' ? 'vaulter' : spec === 'w4c1m5' ? 'founder2' : 'raider')) { if (sp === home && S.q < LIST.length) S.q++; }
  }
  // the vault builder: in through the breach, a spawn beside the containers, then it keeps the spawn full from them
  for (const c of mine.filter((q) => pers.role.get(q.id) === 'vaulter' && !q.spawning)) {
    if (!open) { goTo(c, S.breach, 2); continue; }
    const conts = world.objects.filter((o) => o.exists && o.kind === 'container' && o.store.energy > 0 && V.cont.some(([x, y]) => o.x === x && o.y === y));
    const cont = conts.sort((a, b) => range(a, c) - range(b, c))[0];
    if (S.vstand && (c.x !== S.vstand.x || c.y !== S.vstand.y) && !(cont && range(c, cont) <= 1 && (c.x !== S.vspawn.x || c.y !== S.vspawn.y))) { goTo(c, S.vstand, 0); if (!cont || range(c, cont) > 1) continue; }
    if (cont && range(c, cont) > 1) { goTo(c, cont, 1); continue; }
    if (cont && c.store.free() > 0) c.withdraw(cont, 'energy');
    if (!vsp) { let site = siteAt(S.vspawn); if (!site) site = createConstructionSite(S.vspawn.x, S.vspawn.y, StructureSpawn).object; if (site && c.store.energy > 0) c.build(site); }
    else if (c.store.energy > 0 && range(c, vsp) <= 1) c.transfer(vsp, 'energy');
  }
  // a second founder: the central source nearest his home, a spawn there, then it delivers
  for (const c of mine.filter((q) => pers.role.get(q.id) === 'founder2' && !q.spawning)) {
    if (!S.home2) { const s = nextSource(c); S.home2 = s ? planBaseAt(s, c) : null; }
    const B = S.home2; if (!B) continue;
    if (range(c, B.src) > 1) { goTo(c, B.slot, 0); continue; }
    const sp2 = spawnAt(B.spawn);
    if (!sp2) { let site = siteAt(B.spawn); if (!site) site = createConstructionSite(B.spawn.x, B.spawn.y, StructureSpawn).object; if (site) buildCycle(c, B.src, site); }
    else { if (c.store.energy > 0 && range(c, sp2) <= 1) c.transfer(sp2, 'energy'); if (B.src.energy > 0) c.harvest(B.src); }
  }
  if (S.home2) { const sp2 = spawnAt(S.home2.spawn); if (sp2 && sp2.owner === 1 && !sp2.spawning && sp2.store.energy >= 650) pOrder(sp2, 'm5a5', 'raider'); }
  // raiders: break the vault first, then come for us in threes
  const raiders = mine.filter((q) => pers.role.get(q.id) === 'raider' && !q.spawning);
  const waiting = raiders.filter((c) => !S.gone || !S.gone.has(c.id));
  S.gone = S.gone || new Set();
  if (open && waiting.length >= 3) for (const c of waiting) S.gone.add(c.id);
  for (const c of raiders) {
    fight(c, oursC, oursS, mine);
    if (!open) { const w = structAt(S.breach, 'wall'); if (w && range(c, w) <= 1) c.attack(w); else goTo(c, S.breach, 1); continue; }
    if (!S.gone.has(c.id)) { goTo(c, vsp || H.spawn, 3); continue; }
    const near = oursC.filter((o) => range(o, c) <= 8).sort((a, b) => range(a, c) - range(b, c))[0];
    const st = oursS.filter((o) => o.kind === 'spawn').sort((a, b) => range(a, c) - range(b, c))[0] || oursS.sort((a, b) => range(a, c) - range(b, c))[0];
    const tg = near || st || oursC.sort((a, b) => range(a, c) - range(b, c))[0];
    if (tg) goTo(c, tg, 1);
  }
}

function enemyTick(t) {
  if (scenario === 'ghost') ghostTick(t);
  else if (scenario === 'kerobii' || scenario === 'kerobii22') kerobiiTick(t);
  else if (scenario === 'ricardo') ricardoTick(t);
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
// BOT2=self — a separate copy of the bundle this run plays (a Kotlin object is module state: one module graph per side)
let BOT2 = process.env.BOT2;
if (BOT2 === 'self') {
  const src = fileURLToPath(new URL(BOT.replace(/kotlin\/screeps-kotlin-arena-starter\/season4\/.*$/, '')));
  const dst = fileURLToPath(new URL(`out/bot2-${process.pid}/`, import.meta.url));
  cpSync(src, dst, { recursive: true });
  BOT2 = new URL(BUNDLE, 'file://' + dst).href;
}
if (HITS) world.hitLog = { from: HITS[0], to: HITS[1], list: [] };
// ---------- FIGHTS=1 | <path>: the combat trace (fights.py reads it; README "The combat instrument") ----------
// One JSON line per tick that changed anything a fight is made of: creeps out of their spawn and owned structures that
// appeared (`n`: id, owner, kind, x, y, hitsMax, body as part letters), creeps whose cell or hits changed and structures
// whose hits or energy changed (`c`: id, x, y, hits / `s`: id, hits, energy), every hit and heal the engine dealt this tick
// on a creep or an owned structure (`h`: source id, target id, how — a r R h H ta th —, amount, heal negative, the rampart
// that took it for what stood under it), and what is gone (`d`). The hits of tick t are dealt from the cells after t-1 —
// the engine resolves the fire before the movement. Off, the run is the same run: nothing here touches the world.
const FT = process.env.FIGHTS ? { path: null, fd: null, prev: new Map() } : null;
if (FT) {
  const outDir0 = fileURLToPath(new URL('out/', import.meta.url));
  mkdirSync(outDir0, { recursive: true });
  FT.path = process.env.FIGHTS !== '1' ? process.env.FIGHTS : `${outDir0}fights-${process.env.LOGTAG || ''}${BOT2 ? 'bot2' : scenario}.jsonl`;
  FT.fd = openSync(FT.path, 'w');
  world.combatSink = [];
  writeSync(FT.fd, JSON.stringify({ hdr: 1, map: mapName, we: weLeft ? 'left' : 'right', scenario: BOT2 ? 'bot2' : scenario, bot: BOT, bot2: BOT2 || null, ticks }) + '\n');
}
const FT_KINDS = new Set(['spawn', 'tower', 'rampart', 'extension']);
function fightTrace(t) {
  const n = [], c = [], s = [], d = [];
  const seen = new Set();
  for (const o of world.objects) {
    if (!o.exists) continue;
    if (o.kind === 'creep') {
      if (o.spawning) continue;
      seen.add(o.id);
      const key = `${o.x},${o.y},${o.hits}`;
      const prev = FT.prev.get(o.id);
      if (prev === undefined) n.push([o.id, o.owner, 'creep', o.x, o.y, o.hitsMax, o.body.map((p) => PART_CH[p.type]).join(''), o.hits]);
      else if (prev !== key) c.push([o.id, o.x, o.y, o.hits]);
      FT.prev.set(o.id, key);
    } else if (FT_KINDS.has(o.kind) && (o.owner === 0 || o.owner === 1)) {
      seen.add(o.id);
      const e = o.store ? o.store.energy : 0;
      const key = `${o.hits},${e}`;
      const prev = FT.prev.get(o.id);
      if (prev === undefined) n.push([o.id, o.owner, o.kind, o.x, o.y, o.hitsMax, '', o.hits, e]);
      else if (prev !== key) s.push([o.id, o.hits, e]);
      FT.prev.set(o.id, key);
    }
  }
  for (const id of [...FT.prev.keys()]) if (!seen.has(id)) { d.push(id); FT.prev.delete(id); }
  const h = [];
  for (const [src, tg, how, amt, rp] of world.combatSink) {
    if (!(tg.kind === 'creep' || (FT_KINDS.has(tg.kind) && (tg.owner === 0 || tg.owner === 1)))) continue;
    h.push([src.id, tg.id, how, Math.round(amt), rp]);
  }
  world.combatSink = [];
  if (n.length || c.length || s.length || d.length || h.length) writeSync(FT.fd, JSON.stringify({ t, n, c, s, h, d }) + '\n');
}
const bot = await import(BOT);
const bot2 = BOT2 ? await import(BOT2) : null;
origLog(`start: map=${mapName} we=${weLeft ? 'left' : 'right'} (${START0.x},${START0.y}) scenario=${BOT2 ? 'BOT2' : scenario} ticks=${ticks}`);
const t0 = Date.now();
let ended = '';
let cpuMax = 0, cpuMaxTick = 0, cpuSlow = 0, cpuOver = 0, cpuSum = 0;
const OWNED = (o) => o.exists && o.owner !== undefined && o.kind !== 'site';
const sum = (cs) => { const m = {}; for (const c of cs) { const s = c.summary(); m[s] = (m[s] || 0) + 1; } return Object.entries(m).map(([k, v]) => `${k}x${v}`).join(' '); };
const structs = (owner) => { const m = {}; for (const o of world.objects) if (OWNED(o) && o.owner === owner && o.kind !== 'creep') m[o.kind] = (m[o.kind] || 0) + 1; return Object.entries(m).map(([k, v]) => `${k}${v}`).join(','); };
for (let t = 1; t <= ticks; t++) {
  world.perspective = 0;
  world.tickStartNs = process.hrtime.bigint();
  const tLoop = performance.now();
  try { bot.loop(); } catch (e) { loopErrors++; lines.push('loop error (uncaught): ' + (e && e.stack || e)); }
  const msLoop = performance.now() - tLoop;
  cpuSum += msLoop;
  if (msLoop > cpuMax) { cpuMax = msLoop; cpuMaxTick = t; }
  if (t > 1 && msLoop > 50) cpuSlow++;
  if (t > 1 && msLoop > 100) cpuOver++;
  if (bot2) {
    world.perspective = 1;
    sink = lines2;
    world.tickStartNs = process.hrtime.bigint();
    try { bot2.loop(); } catch (e) { lines2.push('loop error (uncaught): ' + (e && e.stack || e)); }
    sink = lines;
    world.perspective = 0;
  } else {
    if (REPLAY) recTo(t);
    if (scenario !== 'none') { world.perspective = 1; enemyTick(t); world.perspective = 0; }
  }
  if (bot2 && REPLAY) recTo(t);
  step();
  const now = world.tick - 1;
  if (scenario === 'ghost' && !bot2) ghostAfter(now);
  if (FT) fightTrace(now);
  matchOurs(now);
  stateDiff(now);
  const c0 = creeps().filter((c) => c.owner === 0), c1 = creeps().filter((c) => c.owner === 1);
  if (HITS && now >= HITS[0] && now <= HITS[1]) {
    const rid = (o) => (o.kind === 'creep' ? (o.owner === 1 ? o.id : (ourMatch.get(o) || 's' + o.id)) : `${o.kind}@${o.x},${o.y}`);
    for (const c of [...c0, ...c1]) if (!c.spawning) origLog(`hitdump t=${now} ${rid(c)} ${c.owner} ${c.x} ${c.y} ${c.hits} ${c.summary()}`);
    for (const [k, src, tg, how, d] of world.hitLog.list) if (k === now) origLog(`hitlog t=${k} ${rid(src)} ${rid(tg)} ${how} ${Math.round(d)} ${src.x},${src.y}->${tg.x},${tg.y}`);
    // the dead of this tick keep their record id in the log above; their last hits are not dumped
  }
  if (TRACE && now >= TRACE[0] && now <= TRACE[1]) {
    origLog(`trace t=${now} ours ${c0.map((c) => `${c.summary()}@${c.x},${c.y}${c.fatigue ? '/' + c.fatigue : ''}${c.store.energy ? 'e' + c.store.energy : ''}`).join(' ')} | enemy ${c1.map((c) => `${c.summary()}@${c.x},${c.y}${c.fatigue ? '/' + c.fatigue : ''}`).join(' ')}`);
  }
  const has0 = world.objects.some((o) => OWNED(o) && o.owner === 0), has1 = world.objects.some((o) => OWNED(o) && o.owner === 1);
  if (!has0 && !has1) { ended = `DRAW: both sides gone at t=${now}`; break; }
  if (!has1) { ended = `WIN: every enemy object destroyed at t=${now}`; break; }
  if (!has0) { ended = `LOSS: every object of ours destroyed at t=${now}`; break; }
  if (now % 100 === 0) {
    origLog(`cpu t=${now}: max=${cpuMax.toFixed(1)}ms at t=${cpuMaxTick} avg=${(cpuSum / now).toFixed(2)}ms slow(>50ms)=${cpuSlow} over(>100ms)=${cpuOver}`);
    origLog(`t=${now} ours(${c0.length}): ${sum(c0)} [${structs(0)}] | enemy(${c1.length}): ${sum(c1)} [${structs(1)}] errors=${loopErrors}`);
  }
}
const outDir = fileURLToPath(new URL('out/', import.meta.url));
mkdirSync(outDir, { recursive: true });
const log = `${outDir}run-${process.env.LOGTAG || ''}${BOT2 ? 'bot2' : scenario}.log`;
writeFileSync(log, lines.join('\n') + '\n\n=== EVENTS ===\n' + world.events.join('\n') + '\n');
if (bot2) writeFileSync(log.replace(/\.log$/, '.enemy.log'), lines2.join('\n') + '\n');
if (process.env.BOT2 === 'self') rmSync(fileURLToPath(new URL(`out/bot2-${process.pid}/`, import.meta.url)), { recursive: true, force: true });
const n0 = creeps().filter((c) => c.owner === 0).length, n1 = creeps().filter((c) => c.owner === 1).length;
origLog(`cpu: max=${cpuMax.toFixed(1)}ms at t=${cpuMaxTick} avg=${(cpuSum / Math.max(1, world.tick - 1)).toFixed(2)}ms slow(>50ms)=${cpuSlow} over(>100ms)=${cpuOver}`);
if (FT) {
  writeSync(FT.fd, JSON.stringify({ end: world.tick - 1, outcome: ended || `DRAW: ${ticks} ticks`, log, enemyLog: bot2 ? log.replace(/\.log$/, '.enemy.log') : null }) + '\n');
  closeSync(FT.fd);
  writeFileSync(FT.path + '.gz', gzipSync(readFileSync(FT.path)));
  unlinkSync(FT.path);
  origLog(`fights: ${FT.path}.gz`);
}
origLog(`done: ${ended || `DRAW: ${ticks} ticks`} alive=${n0}/${n1} structures=[${structs(0)}]/[${structs(1)}] tw=${world.towerShots[0]}/${world.towerShots[1]} errors=${loopErrors} time=${((Date.now() - t0) / 1000).toFixed(1)}s log=${log}`);
// milestones — the same list calib.py reads off the replay of the match: first structures of each kind per side, the first
// hit on a creep and the first creep death of each side
const firsts = {};
for (const e of world.events) {
  const m = e.match(/^t=(\d+) (ours|enemy) built Structure(\w+) at \((\d+),(\d+)\)/);
  if (m) { const k = `${m[2]}.${m[3].toLowerCase()}`; (firsts[k] = firsts[k] || []).push(`${m[1]}@${m[4]},${m[5]}`); }
}
const ms = Object.entries(firsts).map(([k, v]) => `${k}=${v.slice(0, 8).join('/')}`).join(' ');
origLog(`milestones: ${ms} hit=${world.first.hit[0]}/${world.first.hit[1]} death=${world.first.death[0]}/${world.first.death[1]} deaths=${world.deaths[0]}/${world.deaths[1]}`);
if (REPLAY) {
  const w = REPLAY.meta.result || {};
  origLog(`record ${REPLAY.meta.gameId}: ${w.draw ? 'draw' : (w.winnerName || '?') + ' won'} in ${REPLAY.meta.ticks} ticks; we were the ${REC_US === 0 ? 'left' : 'right'} player`);
  origLog(`ourDev: our creeps off their recorded cell ${dev.off} of ${dev.on} creep-ticks, first at t=${dev.first} (${dev.where}); matched ${ourMatch.size} of ours; first hits apart: ours ${dev.hits0 || '-'}; his ${dev.hits1 || '-'}`);
  if (scenario === 'ghost') origLog(`ghost: born ${gh.stats.born}, not born ${gh.stats.missed}, sites lost ${gh.stats.siteLost}; his creeps off their recorded cell ${gh.stats.off} of ${gh.stats.on} creep-ticks; fire by the stub's rule (no recorded target) ${gh.stats.fallback} creep-ticks`);
}
const errs = lines.filter((l) => l.startsWith('loop error'));
if (errs.length) origLog('first error:\n' + errs.slice(0, 2).join('\n'));
