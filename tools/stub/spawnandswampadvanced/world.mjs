// Simulation state and engine for the offline stub (Spawn and Swamp, ADVANCED) — a copy of
// tools/stub/escortrunadvanced/world.mjs (itself the escort-run copy of the Spawn and Swamp basic engine; copied, not
// shared, by the repository's rule) with what this level needs, each item after World's engine source (the Screeps World
// Steam client's server package, @screeps/engine/src/processor/intents/*) or the Arena's own docs:
//   - intents carry World's action names and World's priorities (creeps/intents.js): harvest yields to attack, build,
//     heal and rangedHeal; build to heal and rangedHeal; attack to build, heal, rangedHeal; rangedAttack to
//     rangedMassAttack, build, rangedHeal; rangedMassAttack to build, rangedHeal; rangedHeal to heal — the arena-docs list
//     the same pairs as "cannot be executed on the same tick". The escort-run copy executed harvest and build together;
//     the live opening is exactly that choice (docs/spawn-and-swamp-advanced.md: "one tick — one of harvest/build");
//   - harvest is processed BEFORE transfer, and a transfer moves the amount fixed at the call (the start-of-tick store):
//     measured live, "копка исполняется РАНЬШЕ сдачи, а сдаётся запас начала тика" (the same doc, rules); what a harvest
//     brings over the capacity drops on the harvester's cell (harvest.js; the replay 6abc0dc7: a W5C1 building every
//     fourth tick leaves +5 under itself each cycle), into a container on that cell if there is one (arena-docs);
//   - a pile decays ceil(amount / RESOURCE_DECAY) a tick (arena-docs; the replay's small piles: 1 a tick);
//   - a creep is born after the movement, on the first free cell round its spawn in the order TOP..TOP_LEFT (or the
//     spawn's directions) — a cell with an obstacle, an obstacle's construction site or a creep is taken; none free and a
//     hostile creep on one of them: that creep dies and the new one takes its cell (spawns/_born-creep.js). The replay
//     shows the order: our bodies at (67,3) came out at TOP, then TOP_RIGHT when a harvester stood at TOP;
//   - every spawn restores +1 a tick up to its capacity (the replay: +1 each tick, a built one too — it first shows 1);
//     sources +SOURCE_ENERGY_REGEN a tick up to their capacity (arena-docs: "Energy regeneration 10 energy per tick");
//   - a spawn that dies kills the creep inside it; a spawning creep takes no hit (docs, v29: "рождающийся крип
//     неуязвим, пока не выйдет");
//   - a creep that steps onto the other side's TOWER site erases it (the basic level's observation of 25.09.2026, kept as
//     the basic stub had it); a spawn or rampart site stays (this level's probes, v19-v22 and the `probe` lines of v58:
//     his spawn sites stood under our creeps for hundreds of ticks).
// Movement (simultaneous, swaps and chains, the engine's ranks for a contested cell, pull), fatigue (by part type, dead
// parts weigh, live MOVEs shed 2), damage front to back and healing from the tail, a hit on anything under a rampart
// going into the rampart, the mass attack's rampart rule and the towers are the escort-run copy's, unchanged.
import {
  TOWER_POWER_ATTACK, TOWER_POWER_HEAL, TOWER_OPTIMAL_RANGE, TOWER_FALLOFF_RANGE, TOWER_FALLOFF, TOWER_ENERGY_COST, TOWER_COOLDOWN,
  HARVEST_POWER, BUILD_POWER, SOURCE_ENERGY_REGEN, RESOURCE_DECAY, RAMPART_HITS, WALL_HITS, CONTAINER_CAPACITY,
} from './game/constants.mjs';

export const world = {
  tick: 1,
  terrain: new Uint8Array(10000), // 0 plain, 1 wall, 2 swamp
  objects: [],
  nextId: 10000, // objects made during a match; the fixed layout keeps the live ids (run.mjs)
  perspective: 0,
  intents: new Map(), // creep -> {actionName: data}
  towerIntents: new Map(), // tower -> {type: 'attack'|'heal', target}
  ticksLimit: 5000,
  events: [],
  towerShots: [0, 0], towerDamage: [0, 0], // per owner, for the runner's done line
  // milestones for the calibration against a record (run.mjs prints them; calib.py reads the same off the replay): the
  // first tick a hit landed on a creep of each side, the first death of each side's creep, deaths per side
  first: { hit: [null, null], death: [null, null] }, deaths: [0, 0],
  hitLog: null, // {from, to, list} — run.mjs HITS=a-b: every hit and heal of those ticks, [t, source, target, how, amount]
  api: null, // game/utils puts findPath/findClosestByPath here (game-object.mjs)
  tickStartNs: null,
  classes: null, // run.mjs: {Resource, StructureSpawn, StructureRampart, StructureTower, StructureExtension, StructureWall, StructureContainer, StructureRoad}
};

/** Tower power at a range (World's tower.js): full up to TOWER_OPTIMAL_RANGE, then linear down to (1 - FALLOFF) at
 *  TOWER_FALLOFF_RANGE and flat beyond. */
export function towerPower(power, r) {
  if (r <= TOWER_OPTIMAL_RANGE) return power;
  const rr = Math.min(r, TOWER_FALLOFF_RANGE);
  return Math.round(power * (1 - TOWER_FALLOFF * (rr - TOWER_OPTIMAL_RANGE) / (TOWER_FALLOFF_RANGE - TOWER_OPTIMAL_RANGE)));
}

export const idx = (x, y) => x * 100 + y;
export const inBounds = (x, y) => x >= 0 && y >= 0 && x < 100 && y < 100;
export const terrainAt = (x, y) => (inBounds(x, y) ? world.terrain[idx(x, y)] : 1);
export const range = (a, b) => Math.max(Math.abs(a.x - b.x), Math.abs(a.y - b.y));
export const byId = (id) => world.objects.find((o) => o.id === String(id) && o.exists) || null;
export const live = (c, type) => c.body.reduce((n, p) => n + (p.type === type && p.hits > 0 ? 1 : 0), 0);
export const creeps = () => world.objects.filter((o) => o.exists && o.kind === 'creep');
export const alive = () => world.objects.filter((o) => o.exists);

const BLOCKING = new Set(['spawn', 'extension', 'tower', 'wall']);
const OBSTACLE_SITE = new Set(['StructureSpawn', 'StructureExtension', 'StructureTower', 'StructureWall']);

export function blockedAt(x, y, owner) {
  if (terrainAt(x, y) === 1) return true;
  // a rampart lets through its owner only (movement.js checkObstacleAtXY: `rampart && !isPublic && user != object.user`)
  return world.objects.some((o) => o.exists && o.x === x && o.y === y && (BLOCKING.has(o.kind) || (o.kind === 'rampart' && owner !== undefined && o.owner !== owner)));
}

export function creepAt(x, y) {
  return world.objects.find((o) => o.exists && o.kind === 'creep' && !o.spawning && o.x === x && o.y === y);
}

export function intent(creep, name, data) {
  let m = world.intents.get(creep);
  if (!m) { m = {}; world.intents.set(creep, m); }
  m[name] = data;
}

// World's creeps/intents.js: an action is dropped when any action listed for it is present in the same tick
const PRIORITIES = {
  rangedHeal: ['heal'],
  build: ['rangedHeal', 'heal'],
  attack: ['build', 'rangedHeal', 'heal'],
  harvest: ['attack', 'build', 'rangedHeal', 'heal'],
  rangedMassAttack: ['build', 'rangedHeal'],
  rangedAttack: ['rangedMassAttack', 'build', 'rangedHeal'],
};
function allowed(m, name) { return !!m[name] && !(PRIORITIES[name] || []).some((o) => m[o]); }

export function effectMul(obj, type) {
  const e = obj.effects && obj.effects.find((x) => x.effectType === type);
  if (!e) return 1;
  return (e.data.multiplier ?? 1);
}

const DIRS = { 1: [0, -1], 2: [1, -1], 3: [1, 0], 4: [1, 1], 5: [0, 1], 6: [-1, 1], 7: [-1, 0], 8: [-1, -1] };

/** Fatigue weight: non-MOVE, non-CARRY parts by type (dead ones included) plus loaded CARRY parts. */
export function weight(c) {
  const parts = c.body.reduce((n, p) => n + (p.type !== 'move' && p.type !== 'carry' ? 1 : 0), 0);
  const carried = c.store ? c.store.energy : 0;
  return parts + Math.ceil(carried / 50);
}

/** A creep's hits after this tick's damage and heal (World's creeps/tick.js): hits - damage + heal, capped at hitsMax; alive,
 *  its parts are recomputed from the total the way _recalc-body.js does — full from the tail, so the front parts are the
 *  damaged ones and a heal restores the frontmost last. Returns false when the creep dies. */
function settleCreep(c, dmg, heal) {
  const old = c.hits;
  c.hits = Math.min(c.hitsMax, c.hits - dmg + heal);
  if (c.hits <= 0) return false;
  if (c.hits !== old) {
    let h = c.hits;
    for (let i = c.body.length - 1; i >= 0; i--) { c.body[i].hits = Math.max(0, Math.min(100, h)); h -= 100; }
    // the store's capacity follows the live CARRY parts, and what a damaged creep holds over it drops on its cell (World's
    // _recalc-body.js and _drop-resources-without-space.js, called from creeps/tick.js when the hits fell). The replay
    // 6abc0dc7 t=857: our founder's CARRY part dies under his fire (550 -> 500 hits) and a pile of 7 appears under it —
    // what it carried
    if (c.store) {
      c.store.capacity = 50 * live(c, 'carry');
      if (c.hits < old && c.store.energy > c.store.capacity) { const over = c.store.energy - c.store.capacity; c.store.energy = c.store.capacity; dropEnergy(c.x, c.y, over, true); }
    }
  }
  return true;
}

/** Energy put on a cell: into a container standing there (arena-docs: "All dropped resources automatically goes to the
 *  container at the same tile"), the rest into the pile there. */
export function dropEnergy(x, y, amount, fresh = false) {
  if (amount <= 0) return;
  const cont = world.objects.find((o) => o.exists && o.kind === 'container' && o.x === x && o.y === y);
  if (cont) { const a = Math.min(amount, cont.store.free()); cont.store.energy += a; amount -= a; if (amount <= 0) return; }
  const existing = world.objects.find((o) => o.exists && o.kind === 'resource' && o.x === x && o.y === y);
  if (existing) { existing.amount += amount; return; }
  const r = new world.classes.Resource(x, y, amount);
  // a pile made in the creeps' own tick (a creep's excess after its CARRY died, a dead creep's store) is not decayed on
  // that tick — World inserts it while the objects' ticks already run; a pile made by an intent (a harvest's excess, a
  // drop) is. The replay 6abc0dc7: the harvest excess of 5 at t=310 shows 4, the founder's 7 dropped at t=857 shows 7
  if (fresh) r.freshAt = world.tick;
  world.objects.push(r);
}

const who = (o) => (o.owner === 0 ? 'ours' : o.owner === 1 ? 'enemy' : 'neutral');

/** A finished construction site becomes its structure with the runtime's hits (RAMPART_HITS / WALL_HITS 10000). */
function finishSite(site, tick = world.tick) {
  const K = world.classes;
  const name = site.proto && site.proto.name;
  site.exists = false;
  const made = name === 'StructureSpawn' ? new K.StructureSpawn(site.x, site.y, site.owner, 0)
    : name === 'StructureRampart' ? new K.StructureRampart(site.x, site.y, site.owner, RAMPART_HITS)
    : name === 'StructureTower' ? new K.StructureTower(site.x, site.y, site.owner)
    : name === 'StructureExtension' ? new K.StructureExtension(site.x, site.y, site.owner)
    : name === 'StructureWall' ? new K.StructureWall(site.x, site.y, WALL_HITS)
    : name === 'StructureContainer' ? new K.StructureContainer(site.x, site.y, 0, CONTAINER_CAPACITY)
    : name === 'StructureRoad' ? new K.StructureRoad(site.x, site.y) : null;
  if (!made) return null;
  world.objects.push(made);
  world.events.push(`t=${tick} ${who(site)} built ${name} at (${site.x},${site.y})`);
  return made;
}
export { finishSite };

export function process() {
  const t = world.tick;
  const acts = new Map(); // creep -> resolved intents
  for (const [c, m] of world.intents) {
    if (!c.exists || c.spawning) continue;
    const r = {};
    for (const name of Object.keys(m)) if (allowed(m, name)) r[name] = m[name];
    acts.set(c, r);
  }
  const movesBefore = new Map();
  for (const c of creeps()) movesBefore.set(c, live(c, 'move'));
  // 1. combat
  const damage = new Map();
  const heals = new Map();
  // HITS=a-b (run.mjs) keeps every hit and heal of those ticks with its source: world.hitLog
  const hl = world.hitLog && t >= world.hitLog.from && t <= world.hitLog.to ? world.hitLog.list : null;
  const addD = (target, d, src, how) => { if (d > 0) { damage.set(target, (damage.get(target) || 0) + d); if (hl) hl.push([t, src, target, how, d]); } };
  const addH = (target, h, src, how) => { if (h > 0) { heals.set(target, (heals.get(target) || 0) + h); if (hl) hl.push([t, src, target, how, -h]); } };
  const taken = (target) => (target.kind === 'creep' ? effectMul(target, 'eff_damage_taken_modifier') : 1);
  const hittable = (tg) => tg && tg.exists && typeof tg.hits === 'number' && !(tg.kind === 'creep' && tg.spawning);
  for (const [c, m] of acts) {
    if (m.attack) {
      const tg = m.attack.target;
      if (hittable(tg) && range(c, tg) <= 1 && tg.owner !== c.owner) addD(tg, live(c, 'attack') * 30 * effectMul(c, 'eff_attack_modifier') * taken(tg), c, 'a');
    }
    if (m.rangedAttack) {
      const tg = m.rangedAttack.target;
      if (hittable(tg) && range(c, tg) <= 3 && tg.owner !== c.owner) addD(tg, live(c, 'ranged_attack') * 10 * effectMul(c, 'eff_ranged_attack_modifier') * taken(tg), c, 'r');
    }
    if (m.rangedMassAttack) {
      const parts = live(c, 'ranged_attack');
      for (const tg of world.objects) {
        if (!tg.exists || tg === c || tg.owner === undefined || tg.owner === c.owner) continue;
        if (!hittable(tg) || !tg.hits) continue; // World: `if(!target.hits) continue`
        // anything under a rampart is skipped — the rampart is a target of its own and takes the hit once
        if (tg.kind !== 'rampart' && world.objects.some((r) => r.exists && r.kind === 'rampart' && r.x === tg.x && r.y === tg.y)) continue;
        const d = range(c, tg);
        if (d > 3) continue;
        const rate = d <= 1 ? 1 : d === 2 ? 0.4 : 0.1;
        addD(tg, parts * 10 * rate * effectMul(c, 'eff_ranged_attack_modifier') * taken(tg), c, 'R');
      }
    }
    if (m.heal) {
      const tg = m.heal.target;
      if (tg && tg.exists && tg.kind === 'creep' && !tg.spawning && range(c, tg) <= 1) addH(tg, live(c, 'heal') * 12 * effectMul(c, 'eff_heal_modifier'), c, 'h');
    }
    if (m.rangedHeal) {
      const tg = m.rangedHeal.target;
      if (tg && tg.exists && tg.kind === 'creep' && !tg.spawning && range(c, tg) <= 3) addH(tg, live(c, 'heal') * 4 * effectMul(c, 'eff_heal_modifier'), c, 'H');
    }
  }
  // towers: attack()/heal() checked owner, energy, cooldown and range at the call; energy and cooldown again here
  for (const [tw, m] of world.towerIntents) {
    if (!tw || !tw.exists || tw.store.energy < TOWER_ENERGY_COST || tw.cooldown > 0) continue;
    const tg = m.target;
    if (!hittable(tg)) continue;
    const r = range(tw, tg);
    tw.store.energy -= TOWER_ENERGY_COST;
    tw.cooldown = TOWER_COOLDOWN;
    if (m.type === 'attack') {
      const d = towerPower(TOWER_POWER_ATTACK, r) * taken(tg);
      addD(tg, d, tw, 'ta');
      world.towerShots[tw.owner]++; world.towerDamage[tw.owner] += d;
      world.events.push(`t=${t} tower ${who(tw)} (${tw.x},${tw.y}) shoots ${tg.summary ? tg.summary() : tg.kind} at (${tg.x},${tg.y}) r=${r} for ${Math.round(d)}`);
    } else if (tg.kind === 'creep') addH(tg, towerPower(TOWER_POWER_HEAL, r), tw, 'th');
  }
  // a hit on anything standing under a rampart goes into the rampart (attack.js / rangedAttack.js: the target is swapped).
  // A structure takes it now; a creep's damage and heal wait for the creep's own tick, after the movement (World's
  // creeps/tick.js: movement.execute, the fatigue shed by the MOVEs alive BEFORE this damage, then _damageToApply and
  // _healToApply, then _recalc-body) — the replay shows it: a breacher losing its first MOVE sheds 14 that tick, not 12
  const creepDamage = new Map();
  for (const [tg, d] of damage) {
    const ramp = tg.kind !== 'rampart' ? world.objects.find((o) => o.exists && o.kind === 'rampart' && o.x === tg.x && o.y === tg.y) : null;
    const hit = ramp || tg;
    if (hit.kind === 'creep') creepDamage.set(hit, (creepDamage.get(hit) || 0) + d);
    else hit.hits -= d;
    if (!ramp && tg.kind === 'creep' && world.first.hit[tg.owner] === null) world.first.hit[tg.owner] = t;
  }
  for (const o of world.objects) {
    if (!o.exists || o.hits === undefined || o.kind === 'creep') continue;
    if (o.hits <= 0) {
      o.exists = false;
      world.events.push(`t=${t} ${o.kind} ${who(o)} destroyed at (${o.x},${o.y})`);
      if (o.kind === 'spawn' && o.spawning) { o.spawning.creepObj.exists = false; o.spawning = null; }
    }
  }
  // 2. harvest — before the transfers (live: the harvest is applied first, the transfer moves the start-of-tick store)
  for (const [c, m] of acts) {
    if (!c.exists || !m.harvest) continue;
    const s = m.harvest.target;
    if (!s || !s.exists || range(c, s) > 1 || s.energy <= 0) continue;
    const amount = Math.min(s.energy, live(c, 'work') * HARVEST_POWER);
    if (amount <= 0) continue;
    s.energy -= amount;
    c.store.energy += amount;
    if (c.store.energy > c.store.capacity) { const over = c.store.energy - c.store.capacity; c.store.energy = c.store.capacity; dropEnergy(c.x, c.y, over); }
  }
  // 3. the other resource actions in World's order: drop, transfer, withdraw, pickup
  for (const [c, m] of acts) {
    if (!c.exists) continue;
    if (m.drop) {
      const amount = Math.min(c.store.energy, m.drop.amount ?? Infinity);
      if (amount > 0) { c.store.energy -= amount; dropEnergy(c.x, c.y, amount); }
    }
    if (m.transfer) {
      const tg = m.transfer.target;
      if (tg && tg.exists && tg.store && range(c, tg) <= 1) {
        const amount = Math.min(c.store.energy, tg.store.free(), m.transfer.amount ?? Infinity);
        if (amount > 0) { c.store.energy -= amount; tg.store.energy += amount; }
      }
    }
    if (m.withdraw) {
      const tg = m.withdraw.target;
      if (tg && tg.exists && tg.store && range(c, tg) <= 1) {
        const amount = Math.min(tg.store.energy, c.store.free(), m.withdraw.amount ?? Infinity);
        if (amount > 0) { tg.store.energy -= amount; c.store.energy += amount; }
      }
    }
    if (m.pickup) {
      const r = m.pickup.target;
      if (r && r.exists && range(c, r) <= 1) {
        const amount = Math.min(r.amount, c.store.free());
        if (amount > 0) { r.amount -= amount; c.store.energy += amount; if (r.amount <= 0) r.exists = false; }
      }
    }
  }
  // 4. construction: BUILD_POWER a live WORK, energy spent one for one; an obstacle (wall, spawn, extension, tower) is not
  // raised over a creep or another obstacle (build.js), a rampart is
  for (const [c, m] of acts) {
    if (!c.exists || !m.build) continue;
    const site = m.build.target;
    if (!site || !site.exists || range(c, site) > 3 || c.store.energy <= 0) continue;
    const name = site.proto && site.proto.name;
    if (OBSTACLE_SITE.has(name) && world.objects.some((o) => o.exists && o.x === site.x && o.y === site.y && ((o.kind === 'creep' && !o.spawning) || BLOCKING.has(o.kind)))) continue;
    const add = Math.min(live(c, 'work') * BUILD_POWER, c.store.energy, site.progressTotal - site.progress);
    if (add <= 0) continue;
    site.progress += add; c.store.energy -= add;
    if (site.progress >= site.progressTotal) finishSite(site);
  }
  // 5. movement (simultaneous, swaps and chains legal), with pull: see movePhase
  const pulledBy = movePhase(t, acts);
  // 6. births, after the movement (World's spawn tick runs after the intents)
  for (const s of world.objects) {
    if (s.kind !== 'spawn' || !s.exists || !s.spawning) continue;
    s.spawning.remainingTime--;
    if (s.spawning.remainingTime > 0) continue;
    const c = s.spawning.creepObj;
    if (!c.exists) { s.spawning = null; continue; }
    const cell = birthCell(s, c);
    if (cell) { c.x = cell.x; c.y = cell.y; c.spawning = false; s.spawning = null; }
    else s.spawning.remainingTime = 0;
  }
  // 7. fatigue decay (tick.js:105-108 through _add-fatigue.js): live MOVEs — counted before this tick's damage — rest their
  // own creep first, the excess of a pulled creep goes up the chain to the head puller
  for (const c of creeps()) {
    let d = 2 * (movesBefore.get(c) ?? live(c, 'move'));
    const resting = Math.min(c.fatigue, d);
    c.fatigue -= resting; d -= resting;
    if (d > 0) { const h = headOf(c, pulledBy); if (h !== c) h.fatigue = Math.max(0, h.fatigue - d); }
  }
  // 7'. the creeps' damage and heal of this tick (tick.js), deaths, the parts recomputed
  for (const c of creeps()) {
    const d = creepDamage.get(c) || 0, h = heals.get(c) || 0;
    if (!d && !h) continue;
    if (settleCreep(c, d, h)) continue;
    c.exists = false;
    world.events.push(`t=${t} creep ${who(c)} ${c.summary()} died at (${c.x},${c.y})`);
    if (c.owner === 0 || c.owner === 1) { world.deaths[c.owner]++; if (world.first.death[c.owner] === null) world.first.death[c.owner] = t; }
    if (c.store && c.store.energy > 0) dropEnergy(c.x, c.y, c.store.energy, true);
  }
  // 8. regen, decay, cooldowns
  for (const o of world.objects) {
    if (!o.exists) continue;
    if (o.kind === 'spawn') o.store.energy = Math.min(o.store.capacity, o.store.energy + 1);
    else if (o.kind === 'source' && o.energy < o.energyCapacity) o.energy = Math.min(o.energyCapacity, o.energy + SOURCE_ENERGY_REGEN);
    else if (o.kind === 'resource' && o.freshAt !== t) { o.amount -= Math.ceil(o.amount / RESOURCE_DECAY); if (o.amount <= 0) o.exists = false; }
    else if (o.kind === 'tower' && o.cooldown > 0) o.cooldown--; // a shot on tick t: ready again on t + TOWER_COOLDOWN
  }
  world.objects = world.objects.filter((o) => o.exists);
  world.intents.clear();
  world.towerIntents.clear();
  world.tick++;
}

/** The cell a finished body steps out to (spawns/_born-creep.js), or null; a hostile creep in the way is crushed when
 *  every cell round the spawn is taken. */
function birthCell(s, c) {
  const dirs = s.spawning.directions;
  const order = (dirs && dirs.length) ? dirs : [1, 2, 3, 4, 5, 6, 7, 8];
  const taken = (x, y) => terrainAt(x, y) === 1 || world.objects.some((o) => o.exists && o.x === x && o.y === y && (
    BLOCKING.has(o.kind) || (o.kind === 'site' && o.proto && OBSTACLE_SITE.has(o.proto.name)) || (o.kind === 'creep' && !o.spawning && o !== c)));
  let hostile = null;
  for (const d of order) {
    const [dx, dy] = DIRS[d];
    const x = s.x + dx, y = s.y + dy;
    if (!taken(x, y)) return { x, y };
    if (!hostile) hostile = world.objects.find((o) => o.exists && o.kind === 'creep' && !o.spawning && o.x === x && o.y === y && o.owner !== s.owner) || null;
  }
  if (!hostile) return null;
  for (let d = 1; d <= 8; d++) {
    if (order.includes(d)) continue;
    const [dx, dy] = DIRS[d];
    if (!taken(s.x + dx, s.y + dy)) return null;
  }
  hostile.exists = false;
  world.events.push(`t=${world.tick} spawn ${who(s)} (${s.x},${s.y}) crushes ${hostile.summary()} at (${hostile.x},${hostile.y})`);
  return { x: hostile.x, y: hostile.y };
}

/**
 * Movement as in the World engine (processor/intents/movement.js): a creep with fatigue > 0 or without a live MOVE does
 * not move — the API already refuses its intent (creep.mjs); a cell is an obstacle only if its creep is not moving this
 * tick (swaps and chains are legal); the fatigue of a move is weight × 2 (plain) / × 10 (swamp) by the DESTINATION cell.
 * Pull (creeps/pull.js, movement.js:69-75/176-181, _add-fatigue.js): see tools/stub/escortrunadvanced/world.mjs.
 * PULL_MODEL: 'engine' (the World code) or 'off'.
 */
export let PULL_MODEL = globalThis.process.env.PULL_MODEL || 'engine';

/** Head of the pull chain the creep hangs on (itself when not pulled). */
export function headOf(c, pulledBy) {
  let o = c;
  const seen = new Set();
  while (pulledBy.has(o.id) && !seen.has(o.id)) { seen.add(o.id); o = pulledBy.get(o.id); }
  return o;
}

function movePhase(t, acts) {
  const movers = new Map();
  const pulledBy = new Map();
  if (PULL_MODEL !== 'off') {
    for (const [p, m] of acts) {
      if (!m.pull) continue;
      const tg = m.pull.target;
      if (!p.exists || !tg || !tg.exists || tg.kind !== 'creep' || tg.owner !== p.owner || tg.spawning) continue;
      if (range(p, tg) > 1) continue;
      pulledBy.set(tg.id, p);
    }
    for (const [tid, p] of [...pulledBy]) { let o = p; let n = 0; while (pulledBy.has(o.id) && n++ < 100) { o = pulledBy.get(o.id); if (o.id === tid) { pulledBy.delete(tid); break; } } }
    for (const [tid, p] of [...pulledBy]) {
      const tg = byId(tid);
      const tm = tg && acts.get(tg);
      if (!tm || !tm.move) continue;
      const d = DIRS[tm.move.dir];
      if (!d || tg.x + d[0] !== p.x || tg.y + d[1] !== p.y) pulledBy.delete(tid);
    }
  }
  // candidates per target cell, then ONE winner per cell by the engine's ranks (movement.js check()): rate1 — how many
  // creeps want the mover's own cell (100 if one of them comes FROM the target cell: a swap), rate2 — the mover is pulled,
  // rate3 — it pulls, rate4 — MOVE per weight
  const cand = new Map();
  const wantsCell = new Map();
  for (const [c, m] of acts) {
    if (!m.move || !c.exists) continue;
    const pulled = pulledBy.get(c.id);
    if (!pulled && (c.fatigue > 0 || live(c, 'move') === 0)) continue;
    const d = DIRS[m.move.dir];
    if (!d) continue;
    const tx = c.x + d[0], ty = c.y + d[1];
    if (!inBounds(tx, ty) || blockedAt(tx, ty, c.owner)) continue;
    const key = idx(tx, ty);
    if (!cand.has(key)) cand.set(key, []);
    cand.get(key).push({ c, tx, ty, pulled });
    wantsCell.set(key, (wantsCell.get(key) || 0) + 1);
  }
  const pulling = new Set([...pulledBy.values()].map((p) => p.id));
  for (const [, list] of cand) {
    let best = list[0];
    if (list.length > 1) {
      const rank = (m) => {
        const own = idx(m.c.x, m.c.y);
        let r1 = wantsCell.get(own) || 0;
        if ((cand.get(own) || []).some((o) => o.c.x === m.tx && o.c.y === m.ty)) r1 = 100;
        const w = weight(m.c) || 1;
        return [r1, m.pulled ? 1 : 0, pulling.has(m.c.id) ? 1 : 0, live(m.c, 'move') / w];
      };
      const cmp = (a, b) => { const ra = rank(a), rb = rank(b); for (let i = 0; i < 4; i++) if (ra[i] !== rb[i]) return rb[i] - ra[i]; return 0; };
      best = [...list].sort(cmp)[0];
    }
    movers.set(best.c.id, best);
  }
  const occ = new Map();
  for (const c of creeps()) if (!c.spawning) occ.set(idx(c.x, c.y), c);
  let changed = true;
  while (changed) {
    changed = false;
    for (const m of [...movers.values()]) {
      const o = occ.get(idx(m.tx, m.ty));
      if (o && o.id !== m.c.id && !movers.has(o.id)) { movers.delete(m.c.id); changed = true; }
      if (m.pulled && !movers.has(m.pulled.id)) { movers.delete(m.c.id); changed = true; }
    }
  }
  for (const m of movers.values()) {
    m.c.x = m.tx; m.c.y = m.ty;
    const f = weight(m.c) * (terrainAt(m.tx, m.ty) === 2 ? 10 : 2);
    headOf(m.c, pulledBy).fatigue += f;
    m.c.moved = t;
    // the other side's TOWER site under the step is erased (the basic level's live observation; spawn and rampart sites
    // stay — this level's probes)
    for (const o of world.objects) {
      if (o.exists && o.kind === 'site' && o.owner !== m.c.owner && o.x === m.tx && o.y === m.ty && o.proto && o.proto.name === 'StructureTower') {
        o.exists = false;
        world.events.push(`t=${t} ${who(m.c)} ${m.c.summary()} steps on and erases the ${who(o)} tower site at (${o.x},${o.y}) ${o.progress}/${o.progressTotal}`);
      }
    }
  }
  return pulledBy;
}
