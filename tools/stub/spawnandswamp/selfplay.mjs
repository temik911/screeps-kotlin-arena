// Play the CURRENT bot against ITSELF, on a mirror-symmetric map.
//
//     node --import ./register.mjs selfplay.mjs [ticks] [seed]
//
// Why this exists, and it is the point of the whole file: every other opponent in this harness is a
// FIXED one. Twenty-six hand-written scenarios and a recorded keroby both model what our bot does to
// something that does not answer, and on 08.09.2026 six builds in a row were better on both of those
// and worse in the arena — the most reliable fact the bot has about itself. The RTS literature names
// the failure (overfitting to a fixed opponent) and the remedy (a population: double oracle, PSRO, the
// AlphaStar league); self-play is the smallest instance of that remedy, and the only opponent available
// here that answers back is the bot itself.
//
// How two bots share one world. The stub's world is global and every object carries `my`. The bot only
// ever asks "is this mine", so the trick is to run side A with the world as stored, FLIP every `my`,
// run side B — which now sees itself as `my=true` and A as the enemy — and flip back. Nothing in the
// stub or in the bot needs a notion of "player two": `createConstructionSite` hard-codes `my:true`,
// `spawnCreep` takes `this.my`, `rangedMassAttack` compares `o.my !== this.my`, and all of it is
// correct for whoever is running, because whoever is running is always `true`.
//
// How two bots keep separate memories. `SpawnAndSwamp.mjs` is a Kotlin object: its fields are module
// state, and importing the same URL twice returns the SAME module. So side B is imported from a COPY
// of the compiled package placed as a sibling directory at the same depth — sibling so that the
// `../../../kotlin-kotlin-stdlib/...` imports still resolve, and a copy so that `./InfluenceMap.mjs`,
// `./DistanceMap.mjs` and `./TrafficManager.mjs` are separate modules too. The `game/*` imports go
// through the loader hook to one URL, which is exactly right: one world, two bots.
//
// Fairness, and it is not a formality — the first run of this file found the bot's opening to be decided
// by it. The map is generated for one half and mirrored point-wise (x -> 99-x, y -> 99-y), so the sides
// face identical terrain, identical piles and identical distances. But actions in this stub apply at
// once, so whoever runs first within a tick is seen by the other: on seed 7 the second mover watched the
// first buy a breacher, read it as an armed enemy, went "fighter first" with the opening thousand and
// never built a hauler — one creep against eight by t=300. The order is therefore FIXED for a whole
// match and every seed is played twice. Tying it to tick parity was worse than useless: the decisive
// opening tick always fell to the same side and the first mover won 8 matches of 8. It is now drawn per
// tick from the match's own seeded stream, and FIRST=A|B only picks which stream — so a seed is played
// under two different sequences of who-moves-first. With one build on both sides the tally over enough
// seeds should sit near even, and that is the null a change is measured against.
import * as C from 'game/constants';
import { world, endTick, StructureSpawn, StructureContainer, StructureWall, Creep, range, terrainAt } from 'game/prototypes';
import { cpSync, rmSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const TICKS = parseInt(process.argv[2] || '2000');
const SEED = parseInt(process.argv[3] || '7');
const FIRST = (process.env.FIRST || 'A').toUpperCase() === 'B' ? 'B' : 'A';
// the order stream: same map, two different draws of who-acts-first, so a seed is played both ways
let orderSeed = SEED * 7919 + (FIRST === 'B' ? 104729 : 0) + 1;
const ornd = () => { orderSeed = (orderSeed * 1103515245 + 12345) & 0x7fffffff; return orderSeed / 0x7fffffff; };

// --- two independent instances of the same build ----------------------------------------------------
const pkgA = new URL('../../../build/js/packages/screeps-kotlin-arena-starter/kotlin/screeps-kotlin-arena-starter/season4/spawnandswamp/', import.meta.url);
const pkgB = new URL('../../../build/js/packages/screeps-kotlin-arena-starter/kotlin/screeps-kotlin-arena-starter/season4/spawnandswamp__selfplay/', import.meta.url);
// OPP=<name> plays side B from a SAVED build under opponents/<name>/ instead of the current one — the
// smallest population there is: the candidate against what it means to replace. Without it both sides
// are the current build and the tally is the null.
const opp = process.env.OPP;
const oppDir = opp ? fileURLToPath(new URL('opponents/' + opp + '/', import.meta.url)) : null;
rmSync(fileURLToPath(pkgB), { recursive: true, force: true });
cpSync(oppDir || fileURLToPath(pkgA), fileURLToPath(pkgB), { recursive: true });
const loopA = (await import(new URL('SpawnAndSwamp.export.mjs', pkgA).href)).loop;
const loopB = (await import(new URL('SpawnAndSwamp.export.mjs', pkgB).href)).loop;

// --- the map: one half generated, the other mirrored ------------------------------------------------
let seed = SEED;
const rnd = () => { seed = (seed * 1103515245 + 12345) & 0x7fffffff; return seed / 0x7fffffff; };
const put = (x, y, v) => { world.terrain[x * 100 + y] = v; world.terrain[(99 - x) * 100 + (99 - y)] = v; };
for (let x = 0; x < 50; x++) for (let y = 0; y < 100; y++) {
  const r = rnd();
  put(x, y, (x == 0 || y == 0 || y == 99) ? 1 : r < 0.05 ? 1 : r < 0.40 ? 2 : 0);
}
// the wall block that makes each spawn sit in a pocket, as in the live map
for (let x = 13; x <= 18; x++) for (let y = 20; y <= 83; y++) put(x, y, 1);
// spawn clearings and the plain border columns
for (let dx = -6; dx <= 6; dx++) for (let dy = -6; dy <= 6; dy++) {
  const x = 5 + dx, y = 50 + dy; if (x > 0 && x < 99 && y > 0 && y < 99) put(x, y, 0);
}
for (let y = 1; y < 99; y++) put(1, y, 0);

const spawnA = new StructureSpawn(94, 49, true, 1000);
const spawnB = new StructureSpawn(5, 50, false, 1000);
// the walled 5000 container next to each spawn, mirrored
for (const [cx, cy, my] of [[11, 50, false], [88, 49, true]]) {
  new StructureContainer(cx, cy, 5000);
  for (let dx = -1; dx <= 1; dx++) for (let dy = -1; dy <= 1; dy++) if (dx || dy) new StructureWall(cx + dx, cy + dy, 10000);
}
for (const [x, y] of [[1, 1], [98, 98], [1, 98], [98, 1]]) new StructureContainer(x, y, 2500);

// temporary piles, in mirrored pairs so neither side is favoured
let dropSeed = SEED * 31 + 1;
const drnd = () => { dropSeed = (dropSeed * 1103515245 + 12345) & 0x7fffffff; return dropSeed / 0x7fffffff; };
function drops(t) {
  if (t % 50 !== 0 || t === 0) return;
  for (let tries = 0; tries < 40; tries++) {
    const x = 2 + Math.floor(drnd() * 46), y = 2 + Math.floor(drnd() * 96);
    if (terrainAt(x, y) === C.TERRAIN_WALL || terrainAt(99 - x, 99 - y) === C.TERRAIN_WALL) continue;
    if (world.objects.some(o => o.exists && o.x === x && o.y === y)) continue;
    const a = new StructureContainer(x, y, 2000); a.ticksToDecay = 99;
    const b = new StructureContainer(99 - x, 99 - y, 2000); b.ticksToDecay = 99;
    return;
  }
}

// --- running the two sides --------------------------------------------------------------------------
const flip = () => { for (const o of world.objects) if (o.my !== undefined) o.my = !o.my; };
const origLog = console.log;
const lines = [];
let errors = 0;
let side = 'A';
// the bot prints through Kotlin's println, which writes to the stream and not through console.log —
// so the stream is what has to be caught if a line is to be labelled with the side that printed it
const realWrite = process.stdout.write.bind(process.stdout);
let pending = '';
process.stdout.write = (chunk, enc, cb) => {
  pending += String(chunk);
  let i;
  while ((i = pending.indexOf('\n')) >= 0) {
    const s = side + '| ' + pending.slice(0, i);
    pending = pending.slice(i + 1);
    lines.push(s);
    if (/loop error|Error|exception/i.test(s)) errors++;
  }
  if (typeof enc === 'function') enc(); else if (typeof cb === 'function') cb();
  return true;
};
console.log = (...a) => process.stdout.write(a.join(' ') + '\n');
function run(which) {
  side = which;
  const t0 = performance.now();
  try { (which === 'A' ? loopA : loopB)(); } catch (e) { errors++; lines.push(which + '| threw ' + e); }
  const dt = performance.now() - t0;
  if (dt > 300) lines.push(which + '| slow tick ' + world.tick + ': ' + dt.toFixed(0) + 'ms');
}

const spawnsOf = (mine) => world.objects.filter(o => o.exists && o instanceof StructureSpawn && o.my === mine);
let result = null;
for (let t = 0; t < TICKS; t++) {
  drops(t);
  // who acts first is drawn per tick from the match's own seeded stream, not tied to tick parity:
  // with parity the decisive opening tick always fell to the same side and the first mover won 8 of 8
  if (ornd() < 0.5) { run('A'); flip(); run('B'); flip(); }
  else { flip(); run('B'); flip(); run('A'); }
  // the spawn regenerates one a tick, as in the game and as every other runner here already models it
  // (run2.mjs:292, run3.mjs:87, replayrun.mjs:177). Leaving it out of THIS runner was my omission, and
  // it mattered: a side that spent its opening thousand could never recover, which made the opening
  // look more fatal than it is
  for (const o of world.objects) if (o.exists && o instanceof StructureSpawn && o.store.energy < 1000) o.store.energy++;
  endTick();
  const a = spawnsOf(true).length, b = spawnsOf(false).length;
  if (a === 0 || b === 0) { result = a === 0 && b === 0 ? 'both' : a === 0 ? 'B' : 'A'; break; }
}

const creeps = (mine) => world.objects.filter(o => o.exists && o instanceof Creep && !o.spawning && o.my === mine);
const armed = (mine) => creeps(mine).filter(c => c.body.some(p => (p.type === C.RANGED_ATTACK || p.type === C.ATTACK) && p.hits > 0)).length;
process.stdout.write = realWrite;
origLog(lines.join('\n'));
origLog(`--- selfplay: seed=${SEED} first=${FIRST} opp=${opp || 'self'} ticks=${world.tick} winner=${result || 'draw'} errors=${errors}`);
origLog(`--- A: spawns=${spawnsOf(true).length} creeps=${creeps(true).length} armed=${armed(true)}` +
  `   B: spawns=${spawnsOf(false).length} creeps=${creeps(false).length} armed=${armed(false)}`);
