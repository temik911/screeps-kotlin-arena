// Play the CURRENT bot against a REAL opponent, on the REAL map, from a match that was already played.
//
//     tools/replay.py scenario <replay.json.gz> --out out/kerobi.json     # once per match
//     node --import ./register.mjs replayrun.mjs out/kerobi.json [ticks]  # as often as you like
//
// Why: the other twenty-three scenarios in this harness are opponents I invented. They catch regressions
// and they do not predict matches — v33 passed every one of them and lost 4-2-2 live, and the exchange
// veto (v27-v29) was never contradicted by the stub before it lost three first matches in a row. The
// replay knows what the opponent actually built and when, and what the map actually looked like; this
// runner puts that in front of the current build.
//
// What is faithful here: the terrain, the walls, the energy piles with their positions, sizes and the
// tick each one appeared, both spawn positions, and the enemy's spawn queue — every body he built, at
// the tick he started it. What is not: his economy (the recorded queue is granted to him, since what is
// being replayed is his army, not his hauling), and his tactics, which are the nearest of the four
// behaviours this harness already models, chosen by the numbers `replay.py scenario` measured off him.
// Ramparts are placed as objects but the stub's movement does not physically block on structures — see
// docs/spawn-and-swamp.md, the note on what the stub does not model.
//
// So a result here is evidence about THIS opponent on THIS map, not a verdict. Read it next to the live
// match it came from: if we lost live and the stub wins comfortably, the difference is his behaviour,
// and that is the next thing to model.
import * as C from 'game/constants';
import { world, endTick, StructureSpawn, StructureContainer, StructureWall, StructureRampart, StructureTower, Creep, range, terrainAt } from 'game/prototypes';
import { loop } from '../../../build/js/packages/screeps-kotlin-arena-starter/kotlin/screeps-kotlin-arena-starter/season4/spawnandswamp/SpawnAndSwamp.export.mjs';
import { readFileSync } from 'node:fs';

const path = process.argv[2];
if (!path) { console.log('usage: replayrun.mjs <scenario.json> [ticks]'); process.exit(2); }
const scen = JSON.parse(readFileSync(path, 'utf8'));
const TICKS = parseInt(process.argv[3] || String(scen.ticks || 2000));

// --- the map, exactly as it was ---------------------------------------------------------------------
// terrain is run-length encoded row-major ("p3w2s1"), the stub indexes it column-major (x*100+y)
{
  let i = 0;
  for (const [, ch, n] of scen.terrain.matchAll(/([pws])(\d+)/g)) {
    const code = ch === 'w' ? 1 : ch === 's' ? 2 : 0;
    for (let j = 0; j < +n; j++, i++) world.terrain[(i % scen.width) * 100 + Math.floor(i / scen.width)] = code;
  }
  if (i !== scen.width * scen.height) console.log('terrain: decoded', i, 'cells, expected', scen.width * scen.height);
}

const ourSide = scen.ourSide;
const mine = scen.spawns.find(s => s.side === ourSide);
const his = scen.spawns.find(s => s.side !== ourSide);
const my = new StructureSpawn(mine.x, mine.y, true, mine.energy);
const en = new StructureSpawn(his.x, his.y, false, his.energy);
for (const w of scen.walls) new StructureWall(w.x, w.y, w.hits);
const piles = scen.containers.slice().sort((a, b) => a.appears - b.appears);
let pileAt = 0;
const place = (t) => { while (pileAt < piles.length && piles[pileAt].appears <= t) { const p = piles[pileAt++]; new StructureContainer(p.x, p.y, p.energy, p.appears ? 99 : undefined); } };

// --- his army: the recorded queue, driven by the nearest behaviour this harness models ---------------
const PART = { m: C.MOVE, w: C.WORK, c: C.CARRY, a: C.ATTACK, r: C.RANGED_ATTACK, t: C.TOUGH, h: C.HEAL };
function body(rle) { const out = []; for (const [, ch, n] of rle.matchAll(/([a-z])(\d+)/g)) for (let i = 0; i < +n; i++) out.push(PART[ch]); return out; }
const BEHAVIOUR = {
  farmer:   { kite: false, group: 1, home: 10 },   // stays on his own energy and lets us come
  stream:   { kite: false, group: 1, home: null }, // walks at us one by one as they come out
  pairs:    { kite: false, group: 2, home: null }, // comes in twos and stands to trade damage for heal
  healball: { kite: true,  group: 4, home: null }, // gathers, closes, and backs off a gun at two
};
const style = BEHAVIOUR[scen.behaviour.class] || BEHAVIOUR.stream;
const queue = scen.enemyQueue.slice();
const structs = scen.enemyStructures.slice();
const towers = [];
// его спавны: исходный плюс те, что он построил по ходу матча. Производство идёт из ЛЮБОГО свободного,
// победа — только когда снесены все
const enSpawns = [en];
const enFree = () => enSpawns.filter(s => s.exists && !s.spawning)[0] || null;
let spawned = 0;

let errors = 0;
const origLog = console.log;
const lines = [];
console.log = (...a) => { const s = a.join(' '); lines.push(s); if (/loop error|Error|exception/i.test(s)) errors++; };

const R = (a, b) => Math.max(Math.abs(a.x - b.x), Math.abs(a.y - b.y));
const alive = () => world.objects.filter(o => o instanceof Creep && o.exists && !o.spawning);

for (let t = 0; t < TICKS; t++) {
  place(t);
  for (const s of structs.filter(s => s.t <= t)) {
    if (s.kind === 'tower') { const w = new StructureTower(s.x, s.y, false); w.store.energy = C.TOWER_CAPACITY; towers.push(w); }
    else if (s.kind === 'rampart') new StructureRampart(s.x, s.y, C.RAMPART_HITS, false);
    else if (s.kind === 'constructedWall') new StructureWall(s.x, s.y, C.WALL_HITS);
    else if (s.kind === 'spawn') { enSpawns.push(new StructureSpawn(s.x, s.y, false, 1000)); }
    else if (s.kind === 'extension') { /* an extension only holds energy for him; his economy is granted */ }
  }
  for (let i = structs.length - 1; i >= 0; i--) if (structs[i].t <= t) structs.splice(i, 1);
  // his economy is not what is being replayed: he is granted what he actually spent, on his own schedule
  while (queue.length && queue[0].t <= t) {
    const s = enFree();
    if (!s) break;
    s.store.energy = Math.max(s.store.energy, queue[0].cost);
    const r = s.spawnCreep(body(queue[0].body));
    if (r.error !== undefined) break;
    r.object.foe = true;
    queue.shift();
    spawned++;
  }

  { const t0 = performance.now(); loop(); const dt = performance.now() - t0; if (dt > 200) lines.push('slow tick ' + t + ': ' + dt.toFixed(0) + 'ms'); }

  // --- his tactics ---------------------------------------------------------------------------------
  const ours = alive().filter(c => c.my === true);
  const guns = ours.filter(c => c.body.some(p => (p.type === C.RANGED_ATTACK || p.type === C.ATTACK) && p.hits > 0));
  const foes = alive().filter(c => c.my === false && c.foe);
  for (const o of foes) {
    if (o.parts(C.HEAL) > 0) {
      const hurt = foes.filter(m => m.hits < m.hitsMax && R(m, o) <= 3).sort((a, b) => (b.hitsMax - b.hits) - (a.hitsMax - a.hits))[0];
      if (hurt) { if (R(hurt, o) <= 1) o.heal(hurt); else o.rangedHeal(hurt); }
    }
    if (o.parts(C.RANGED_ATTACK) > 0) {
      const inR = ours.filter(c => R(c, o) <= 3);
      if (inR.length) { if (inR.filter(c => R(c, o) <= 1).length >= 2) o.rangedMassAttack(); else o.rangedAttack(inR.sort((a, b) => a.hits - b.hits)[0]); }
      else if (R(o, my) <= 3) o.rangedAttack(my);
    }
    if (o.parts(C.ATTACK) > 0) { const adj = ours.find(c => R(c, o) <= 1); if (adj) o.attack(adj); else if (R(o, my) <= 1) o.attack(my); }
    // a carrier of his ferries between the nearest pile and his spawn — that is what his haulers did
    if (o.parts(C.CARRY) > 0 && o.parts(C.RANGED_ATTACK) === 0 && o.parts(C.ATTACK) === 0) {
      if (o.store.energy >= o.store.getCapacity()) { if (R(o, en) <= 1) { en.store.energy = Math.min(1000, en.store.energy + o.store.energy); o.store.energy = 0; } else o.moveTo(en); }
      else { const p = world.objects.filter(q => q.exists && q instanceof StructureContainer && q.store.energy > 0).sort((a, b) => R(a, o) - R(b, o))[0];
        if (p) { if (R(p, o) <= 1) { const take = Math.min(o.store.getFreeCapacity(), p.store.energy); p.store.energy -= take; o.store.energy += take; } else o.moveTo(p); } }
      continue;
    }
    const near = guns.filter(f => R(f, o) <= 4).sort((a, b) => R(a, o) - R(b, o));
    if (style.kite && near.length && R(near[0], o) <= 2) {
      let best = null, bd = -1;
      for (let dx = -1; dx <= 1; dx++) for (let dy = -1; dy <= 1; dy++) {
        const nx = o.x + dx, ny = o.y + dy;
        if (terrainAt(nx, ny) === C.TERRAIN_WALL) continue;
        if (world.objects.some(q => q.exists && q !== o && q.x === nx && q.y === ny && (q instanceof Creep || q instanceof StructureSpawn))) continue;
        const d = Math.min(...near.map(f => R({ x: nx, y: ny }, f)));
        if (d > bd) { bd = d; best = { x: nx, y: ny }; }
      }
      if (best && (best.x !== o.x || best.y !== o.y)) world.intents.push({ creep: o, x: best.x, y: best.y });
      continue;
    }
    if (style.home !== null) { if (R(o, en) > style.home) o.moveTo(en); continue; }
    // gathering happens at home; once he has set off, a straggler walks on rather than turning back —
    // a regroup rule without that bound sends a spread-out army back and forth and neuters it
    if (style.group > 1 && R(o, en) <= 20) {
      const band = foes.filter(m => m !== o && m.parts(C.MOVE) > 0 && R(m, o) <= 8);
      if (band.length < style.group - 1) { if (R(o, en) > 4) o.moveTo(en); continue; }
    }
    if (R(o, my) > 3) o.moveTo(my);
  }
  // his tower is fed, as it was in the match he built it in
  for (const w of towers) {
    if (!w.exists) continue;
    w.store.energy = C.TOWER_CAPACITY;
    if (w.cooldown === 0) { const tg = ours.filter(c => R(c, w) <= C.TOWER_RANGE).sort((a, b) => R(a, w) - R(b, w))[0]; if (tg) w.attack(tg); }
  }

  my.store.energy = Math.min(1000, my.store.energy + 1);
  if (t % 200 === 0) lines.push(`REPLAY t=${t}: his creeps ${foes.length} (${spawned} of ${scen.enemyQueue.length} built), towers ${towers.filter(w => w.exists).length}, our spawn ${my.hits}, his spawns ${enSpawns.map(s => s.exists ? s.hits : 'dead').join('/')}`);
  endTick();
  if (!my.exists) { origLog('MY SPAWN DESTROYED at', t); break; }
  if (!enSpawns.some(s => s.exists)) { origLog('ENEMY SPAWN DESTROYED at', t, 'spawns:', enSpawns.length); break; }
}
console.log = origLog;
const skip = /^\d\d:|=== MAP|=== END MAP/;
for (const l of lines) if (!skip.test(l)) origLog(l);
origLog(`--- scenario: ${scen.source} vs ${scen.opponent} (${scen.result} live), behaviour ${scen.behaviour.class}`);
origLog('--- ticks run:', world.tick, 'errors:', errors, 'spawnE:', my.store.energy,
        'his spawns:', enSpawns.map(s => (s.exists ? s.hits : 'dead')).join('/'),
        'his creeps built:', spawned, 'of', scen.enemyQueue.length);
