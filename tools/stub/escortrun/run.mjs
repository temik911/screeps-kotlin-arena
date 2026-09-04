// Offline runner for Escort Run (basic): a map (synthetic, or MAP=map-matchN.txt dumped from a match log) + a scripted
// enemy. Usage (see README.md and docs/escort-run.md):
//   node --import ./register.mjs run.mjs <ticks> none|race|rush|melee|guard|hunt|train[+harvest][+pull]
//   env: MAP=<file> START=match2 (we are player 2) LOGTAG=<prefix> BOT=<bundle url> PULL_MODEL=engine|off TRACE=from-to
// Win: our escort stands on our flag, or the enemy escort dies. Loss: the mirror. Draw: 2000 ticks.
import { writeFileSync, mkdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { world, process as step, idx, inBounds, range, creeps, live, creepAt, terrainAt } from './world.mjs';
import { Creep } from './game/prototypes/creep.mjs';
import { Resource } from './game/prototypes/resource.mjs';
import { Flag } from './game/prototypes/flag.mjs';
import { Source } from './game/prototypes/source.mjs';
import { StructureSpawn } from './game/prototypes/spawn.mjs';
import { StructureContainer } from './game/prototypes/container.mjs';
import { EscortCreep } from './arena/season_4/escort_run/basic/prototypes.mjs';
import { CostMatrix, searchPath } from './game/path-finder.mjs';
import { getDirection } from './game/utils.mjs';

// the bundle of THIS worktree's build (see the parallel-sessions rules: the stub tests what the worktree built)
const BOT = process.env.BOT || new URL('../../../build/js/packages/screeps-kotlin-arena-starter/kotlin/screeps-kotlin-arena-starter/season4/escortrun/EscortRun.export.mjs', import.meta.url).href;
const MAP = process.env.MAP; // path to a 100-row DEBUG_MAP dump: '#' wall, '~' swamp, anything else plain
const ticks = parseInt(process.argv[2] || '2000', 10);
const TRACE = process.env.TRACE ? process.env.TRACE.split('-').map((v) => parseInt(v, 10)) : null;
const scenario = (process.argv[3] || 'none').split('+');
const has = (s) => scenario.includes(s);

const M = 'move', A = 'attack', R = 'ranged_attack', H = 'heal', T = 'tough', W = 'work', C = 'carry';
// measured in match 1 (04.09.2026): ten blocks of MOVE + four TOUGH — weight 40, ten MOVE, so four ticks a cell on
// plain and twenty on swamp, exactly as the lobby says; the MOVEs are spread through the body, not front-loaded
const ESCORT_BODY = [].concat(...Array.from({ length: 10 }, () => [M, T, T, T, T]));

// ---------- synthetic map ----------
// A guess at the lobby picture: an X of open ground joining four corner areas; both bases on the LEFT edge (top-left
// and bottom-left), both flags on the RIGHT edge; "secret passes" along the edges — one from each base to its flag
// (bottom edge for us, top edge for the enemy) and one along the left edge between the bases; swamp patches inside.
let seed = 11; const rnd = () => { seed = (seed * 1103515245 + 12345) & 0x7fffffff; return seed / 0x7fffffff; };
function rect(x0, y0, x1, y1, v) { for (let x = x0; x <= x1; x++) for (let y = y0; y <= y1; y++) if (inBounds(x, y)) world.terrain[idx(x, y)] = v; }
function buildMap() {
  world.terrain.fill(1);
  for (let t = 0; t < 100; t++) { rect(t - 8, t - 8, t + 8, t + 8, 0); rect(t - 8, 99 - t - 8, t + 8, 99 - t + 8, 0); }
  rect(36, 36, 63, 63, 0);
  rect(2, 2, 15, 15, 0); rect(2, 84, 15, 97, 0); rect(84, 2, 97, 15, 0); rect(84, 84, 97, 97, 0);
  rect(15, 96, 84, 97, 0); rect(15, 2, 84, 3, 0); rect(2, 15, 3, 84, 0); rect(96, 15, 97, 84, 0); // edge passes
  // swamp: a ring around the centre, patches in the lobes, half of every pass
  for (let x = 30; x <= 69; x++) for (let y = 30; y <= 69; y++) { const d = Math.max(Math.abs(x - 49.5), Math.abs(y - 49.5)); if (d > 13 && d < 19 && world.terrain[idx(x, y)] === 0) world.terrain[idx(x, y)] = 2; }
  for (let x = 0; x < 100; x++) for (let y = 0; y < 100; y++) if (world.terrain[idx(x, y)] === 0 && rnd() < 0.22 && !(x < 16 && (y < 16 || y > 83)) && !(x > 83 && (y < 16 || y > 83))) world.terrain[idx(x, y)] = 2;
  for (let x = 15; x <= 84; x += 2) { world.terrain[idx(x, 97)] = 2; world.terrain[idx(x, 2)] = 2; }
  for (let y = 15; y <= 84; y += 2) { world.terrain[idx(2, y)] = 2; world.terrain[idx(97, y)] = 2; }
  // border is wall
  rect(0, 0, 99, 0, 1); rect(0, 99, 99, 99, 1); rect(0, 0, 0, 99, 1); rect(99, 0, 99, 99, 1);
}
// match 1 (04.09.2026): the live layout — a source in each base corner, two more plus two 2500-containers on the far
// right edge, the flags in the far corners, spawns starting at 500 energy
const OURS = MAP ? { spawn: [9, 90], escort: [7, 92], source: [2, 97], flag: [95, 95] } : { spawn: [6, 93], escort: [7, 92], source: [9, 95], flag: [93, 93] };
const ENEMY = MAP ? { spawn: [9, 9], escort: [7, 7], source: [2, 2], flag: [95, 4] } : { spawn: [6, 6], escort: [7, 7], source: [9, 4], flag: [93, 6] };
const SPAWN_START = MAP ? 500 : 1000;
function place(side, owner) {
  const sp = new StructureSpawn(side.spawn[0], side.spawn[1], owner, SPAWN_START); world.objects.push(sp);
  const src = new Source(side.source[0], side.source[1], 1000, 1000); world.objects.push(src);
  const esc = new EscortCreep(side.escort[0], side.escort[1], owner, ESCORT_BODY); world.objects.push(esc);
  const flag = new Flag(side.flag[0], side.flag[1]); flag.owner = owner; world.objects.push(flag);
  world.terrain[idx(side.spawn[0], side.spawn[1])] = 0; world.terrain[idx(side.source[0], side.source[1])] = 0; world.terrain[idx(side.escort[0], side.escort[1])] = 0; world.terrain[idx(side.flag[0], side.flag[1])] = 0;
  return { sp, src, esc, flag };
}
function buildLiveMap(path) {
  const rows = readFileSync(path, 'utf8').split('\n').filter((r) => r.length > 0);
  if (rows.length !== 100) throw new Error(`map must have 100 rows, got ${rows.length}`);
  rows.forEach((r, y) => { if (r.length !== 100) throw new Error(`row ${y} has ${r.length} chars`); for (let x = 0; x < 100; x++) world.terrain[idx(x, y)] = r[x] === '#' ? 1 : r[x] === '~' ? 2 : 0; });
}
if (MAP) buildLiveMap(MAP); else buildMap();
const swap = process.env.START === 'match2';
const ours = place(swap ? ENEMY : OURS, 0);
const theirs = place(swap ? OURS : ENEMY, 1);
// far-side energy: two sources and two 2500-containers on the right edge (match 1 coordinates when a live map is used)
if (MAP) {
  world.objects.push(new Source(96, 24, 1000, 1000)); world.objects.push(new Source(96, 75, 1000, 1000));
  world.objects.push(new StructureContainer(92, 49, 2500, 2500)); world.objects.push(new StructureContainer(92, 50, 2500, 2500));
} else {
  world.objects.push(new Source(95, 45, 1000, 1000)); world.objects.push(new Source(95, 54, 1000, 1000));
  world.objects.push(new StructureContainer(92, 49, 2500, 2500)); world.objects.push(new StructureContainer(92, 50, 2500, 2500));
}
world.spawnRegen = [1, has('harvest') ? 11 : 1]; // 'harvest': the enemy economy as if it had a W5 harvester from tick 1

// ---------- enemy AI ----------
// the stub's searchPath knows terrain only: structures (spawns, sources, walls) go into the cost matrix — the first
// enemy melee stood at its spawn's side for 240 ticks wanting to step onto the spawn cell
const STRUCT = new Set(['spawn', 'extension', 'tower', 'wall', 'source']);
function structMatrix() { const cm = new CostMatrix(); for (const o of world.objects) if (o.exists && STRUCT.has(o.kind)) cm.set(o.x, o.y, 255); return cm; }
function pathStepTo(c, target, stop) {
  if (range(c, target) <= stop) return null;
  const cm = structMatrix();
  for (const o of creeps()) if (!o.spawning && o !== c && !(o.x === target.x && o.y === target.y)) cm.set(o.x, o.y, 255);
  const r = searchPath(c, { pos: target, range: stop }, { costMatrix: cm });
  return r.path[0] || null;
}
function stepToward(c, target, stop) { const s = pathStepTo(c, target, stop); if (s) c.move(getDirection(s.x - c.x, s.y - c.y)); }
function stepAway(c, from) {
  const cm = structMatrix();
  for (const o of creeps()) if (!o.spawning && o !== c) cm.set(o.x, o.y, 255);
  const r = searchPath(c, from.map((f) => ({ pos: f, range: 4 })), { costMatrix: cm, flee: true });
  const s = r.path[0];
  if (s) c.move(getDirection(s.x - c.x, s.y - c.y));
}
const isPuller = (c) => !c.escort && c.body.every((p) => p.type === M);
function fireAt(c, targets) {
  const inRange = targets.filter((o) => range(c, o) <= 3);
  if (live(c, R) > 0 && inRange.length) {
    const esc = inRange.find((o) => o.escort);
    const armed = inRange.filter((o) => live(o, R) + live(o, A) > 0);
    c.rangedAttack(armed.length ? armed.sort((a, b) => a.hits - b.hits)[0] : esc || inRange.sort((a, b) => a.hits - b.hits)[0]);
  }
  if (live(c, A) > 0) {
    const adj = inRange.filter((o) => range(c, o) <= 1);
    if (adj.length) { const esc = adj.find((o) => o.escort); c.attack(esc || adj.sort((a, b) => a.hits - b.hits)[0]); }
  }
}
let enemyQueue = [];
const FIGHTER = [M, M, M, M, M, R, R, R, R, R];
const MELEE = [M, M, M, M, M, M, M, A, A, A, A, A, A, A];
const PULLER = Array(10).fill(M);
function enemyTick() {
  const mine = creeps().filter((c) => c.owner === 1);
  const oursC = creeps().filter((c) => c.owner === 0);
  const esc = theirs.esc;
  const ourEsc = ours.esc;
  // orders: 'rush'/'hunt' — ranged fighters whenever affordable; 'melee' — melee; 'guard' — fighters that escort;
  // 'train' — a 10-MOVE puller first, then fighters; 'none'/'race' — nothing
  if (!theirs.sp.spawning) {
    let body = null;
    const e = theirs.sp.store.energy;
    if (has('train') && !mine.some(isPuller) && e >= 500) body = PULLER;
    else if ((has('rush') || has('hunt') || has('guard')) && e >= 1000) body = FIGHTER;
    else if (has('melee') && e >= 910) body = MELEE;
    if (body) { const r = theirs.sp.spawnCreep(body); if (r.object) world.events.push(`t=${world.tick} enemy orders ${r.object.summary()}`); }
  }
  // the escort walks to its flag ('none': it does not move at all); with a puller: the puller stands on the escort's
  // next cell, pulls, the escort steps into it (the World scheme)
  if (!has('none') && esc && esc.exists) {
    const pullers = mine.filter(isPuller);
    if (has('train') && pullers.length) {
      // the World scheme: the puller stands on the escort's next cell (path computed with the puller NOT an obstacle),
      // pulls every tick, and both move when the puller is rested; the escort waits for a puller within 3 cells
      const p = pullers[0];
      const cm = structMatrix();
      for (const o of creeps()) if (!o.spawning && o !== esc && o !== p) cm.set(o.x, o.y, 255);
      const next = searchPath(esc, { pos: theirs.flag, range: 0 }, { costMatrix: cm }).path[0];
      if (!next) { /* no path: the escort stands */ }
      else if (p.x === next.x && p.y === next.y) {
        p.pull(esc);
        if (esc.fatigue === 0 && p.fatigue === 0) {
          const pn = searchPath(p, { pos: theirs.flag, range: 0 }, { costMatrix: cm }).path[0];
          if (pn) { p.move(getDirection(pn.x - p.x, pn.y - p.y)); esc.move(getDirection(p.x - esc.x, p.y - esc.y)); }
        }
      } else {
        stepToward(p, next, 0);
        if (range(p, esc) > 3) esc.move(getDirection(next.x - esc.x, next.y - esc.y));
      }
    } else {
      const next = pathStepTo(esc, theirs.flag, 0);
      if (next) esc.move(getDirection(next.x - esc.x, next.y - esc.y));
    }
  }
  for (const c of mine) {
    if (c.escort || isPuller(c)) continue;
    fireAt(c, oursC);
    if (has('guard')) {
      // stay within 2 of the escort, on the side of our nearest creep
      const threat = oursC.filter((o) => live(o, R) + live(o, A) > 0).sort((a, b) => range(esc, a) - range(esc, b))[0];
      if (range(c, esc) > 2) stepToward(c, esc, 2);
      else if (threat && range(c, threat) > 3 && range(c, esc) <= 1) stepToward(c, threat, 3);
      continue;
    }
    // rush / hunt / melee: go for our escort; ranged kite from our armed creeps within 2
    if (!ourEsc || !ourEsc.exists) { const t = oursC.sort((a, b) => range(c, a) - range(c, b))[0]; if (t) stepToward(c, t, live(c, A) > 0 ? 1 : 3); continue; }
    if (live(c, R) > 0) {
      const close = oursC.filter((o) => live(o, R) + live(o, A) > 0 && range(c, o) <= 2);
      if (close.length) stepAway(c, close);
      else if (range(c, ourEsc) > 3) stepToward(c, ourEsc, 3);
    } else stepToward(c, ourEsc, 1);
  }
}

// ---------- run ----------
const lines = [];
let loopErrors = 0;
const origWrite = process.stdout.write.bind(process.stdout);
let buf = '';
process.stdout.write = (chunk) => {
  buf += typeof chunk === 'string' ? chunk : chunk.toString();
  let i;
  while ((i = buf.indexOf('\n')) >= 0) {
    const s = buf.slice(0, i);
    buf = buf.slice(i + 1);
    lines.push(s);
    if (s.startsWith('loop error')) loopErrors++;
  }
  return true;
};
const origLog = (...args) => origWrite(args.join(' ') + '\n');
console.log = (...args) => { const s = args.join(' '); lines.push(s); if (s.startsWith('loop error')) loopErrors++; };
const bot = await import(BOT);
const t0 = Date.now();
let ended = '';
let cpuMax = 0, cpuMaxTick = 0, cpuSlow = 0;
for (let t = 1; t <= ticks; t++) {
  world.perspective = 0;
  const tLoop = performance.now();
  try { bot.loop(); } catch (e) { loopErrors++; lines.push('loop error (uncaught): ' + (e && e.stack || e)); }
  const msLoop = performance.now() - tLoop;
  if (msLoop > cpuMax) { cpuMax = msLoop; cpuMaxTick = t; }
  if (msLoop > 50) cpuSlow++;
  enemyTick();
  step(Resource);
  const c0 = creeps().filter((c) => c.owner === 0), c1 = creeps().filter((c) => c.owner === 1);
  if (TRACE && t >= TRACE[0] && t <= TRACE[1]) {
    origLog(`trace t=${t} ours ${c0.map((c) => `${c.summary()}@${c.x},${c.y}${c.fatigue ? '/' + c.fatigue : ''}`).join(' ')} | enemy ${c1.map((c) => `${c.summary()}@${c.x},${c.y}${c.fatigue ? '/' + c.fatigue : ''}`).join(' ')}`);
  }
  const ourAt = ours.esc.exists && ours.esc.x === ours.flag.x && ours.esc.y === ours.flag.y;
  const theirAt = theirs.esc.exists && theirs.esc.x === theirs.flag.x && theirs.esc.y === theirs.flag.y;
  if (!ours.esc.exists && !theirs.esc.exists) { ended = `DRAW: both escorts died at t=${world.tick - 1}`; break; }
  if (ourAt && theirAt) { ended = `DRAW: both escorts reached their flags at t=${world.tick - 1}`; break; }
  if (!ours.esc.exists) { ended = `LOSS: our escort died at t=${world.tick - 1}`; break; }
  if (!theirs.esc.exists) { ended = `WIN: enemy escort killed at t=${world.tick - 1}`; break; }
  if (ourAt) { ended = `WIN: our escort reached the flag at t=${world.tick - 1}`; break; }
  if (theirAt) { ended = `LOSS: enemy escort reached its flag at t=${world.tick - 1}`; break; }
  if (t % 100 === 0) {
    origLog(`cpu t=${t}: max=${cpuMax.toFixed(1)}ms at t=${cpuMaxTick} slow(>50ms)=${cpuSlow}`);
    const sum = (cs) => { const m = {}; for (const c of cs) { const s = c.summary(); m[s] = (m[s] || 0) + 1; } return Object.entries(m).map(([k, v]) => `${k}x${v}`).join(' '); };
    origLog(`t=${t} escort=(${ours.esc.x},${ours.esc.y})h${ours.esc.hits} enemyEscort=(${theirs.esc.x},${theirs.esc.y})h${theirs.esc.hits} spawnE=${ours.sp.store.energy}/${theirs.sp.store.energy} ours(${c0.length}): ${sum(c0)} | enemy(${c1.length}): ${sum(c1)} errors=${loopErrors}`);
  }
}
const outDir = fileURLToPath(new URL('out/', import.meta.url));
mkdirSync(outDir, { recursive: true });
const log = `${outDir}run-${process.env.LOGTAG || ''}${scenario.join('+')}.log`;
writeFileSync(log, lines.join('\n') + '\n\n=== EVENTS ===\n' + world.events.join('\n') + '\n');
const c0 = creeps().filter((c) => c.owner === 0).length, c1 = creeps().filter((c) => c.owner === 1).length;
origLog(`done: ${ended || `DRAW: ${ticks} ticks`} alive=${c0}/${c1} escort=${ours.esc.exists ? ours.esc.hits : 0}/${theirs.esc.exists ? theirs.esc.hits : 0} errors=${loopErrors} time=${((Date.now() - t0) / 1000).toFixed(1)}s log=${log}`);
const errs = lines.filter((l) => l.startsWith('loop error'));
if (errs.length) origLog('first error:\n' + errs.slice(0, 2).join('\n'));
