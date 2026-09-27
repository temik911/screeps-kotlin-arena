// Simulation state and engine for the offline stub (Pain and Gain ADVANCED), copied from the basic stub
// (tools/stub/painandgain/world.mjs) and extended with what the advanced arena adds, as measured live on 27.09.2026:
//  - towers: owned by whoever owns the linked flag, store 10, 10 energy and TOWER_COOLDOWN ticks per action, damage
//    and healing by range with the falloff (1000 at range 1, -50 a cell); the target's damage-taken modifier applies to
//    a tower's shot, the owner's own flag effects do not;
//  - two more flag effects: eff_fatigue_modifier (x2 / x4 on the fatigue a move adds) and eff_hits_loss (offset -1 / -2
//    hits a tick on every creep of the owner, taken like damage — front to back, healing that tick compensates);
//  - carried energy weighs: CARRY parts fill in body order, 50 each, and a filled one weighs like any other part, an
//    empty one does not.
// Everything else is the basic stub's: simultaneous movement with swaps and chains, fatigue by part type (dead parts
// weigh, live MOVEs shed 2 a tick), damage front to back with overkill kept until the heal of the same tick.
import { TOWER_POWER_ATTACK, TOWER_POWER_HEAL, TOWER_OPTIMAL_RANGE, TOWER_FALLOFF_RANGE, TOWER_FALLOFF, TOWER_ENERGY_COST, TOWER_COOLDOWN, CARRY_CAPACITY } from './game/constants.mjs';

export const world = {
  tick: 1,
  terrain: new Uint8Array(10000), // 0 plain, 1 wall, 2 swamp
  objects: [],
  nextId: 1,
  perspective: 0,
  score: [0, 0],
  intents: new Map(),       // creep id -> {move, melee, ranged, heal, transfer, withdraw, ...}
  towerIntents: new Map(),  // tower id -> {type: 'attack'|'heal', target}
  spawnRegen: [0, 0],
  ticksLimit: 5000,
  maxScorePerTick: 43,
  events: [],
  // counters the runner reports at the end: tower actions and what they did, the two new effects' cost
  stats: {
    towerShots: [0, 0], towerDamage: [0, 0], towerHeals: [0, 0], towerHealed: [0, 0], towerFed: [0, 0],
    hitsLoss: [0, 0],            // hits taken from eff_hits_loss, per side (before the heal of the tick)
    fatigueMoves: [0, 0],        // moves made under a fatigue multiplier > 1
    fatigueExtra: [0, 0],        // fatigue added by the multiplier above what the move would cost without it
    effectsMax: [{}, {}],        // per side: effect type -> the strongest value seen on a creep
    effectTicks: [{}, {}],       // per side: effect type -> ticks it was on the side's creeps
  },
};

export const idx = (x, y) => x * 100 + y;
export const inBounds = (x, y) => x >= 0 && y >= 0 && x < 100 && y < 100;
export const terrainAt = (x, y) => (inBounds(x, y) ? world.terrain[idx(x, y)] : 1);
export const range = (a, b) => Math.max(Math.abs(a.x - b.x), Math.abs(a.y - b.y));
export const byId = (id) => world.objects.find((o) => o.id === String(id));
export const live = (c, type) => c.body.reduce((n, p) => n + (p.type === type && p.hits > 0 ? 1 : 0), 0);
export const creeps = () => world.objects.filter((o) => o.exists && o.kind === 'creep');
export const alive = () => world.objects.filter((o) => o.exists);

const BLOCKING = new Set(['spawn', 'extension', 'tower', 'wall', 'source']);

export function blockedAt(x, y) {
  if (terrainAt(x, y) === 1) return true;
  return world.objects.some((o) => o.exists && o.x === x && o.y === y && BLOCKING.has(o.kind));
}

export function creepAt(x, y) {
  return world.objects.find((o) => o.exists && o.kind === 'creep' && !o.spawning && o.x === x && o.y === y);
}

export function intent(creep, cat, data) {
  let m = world.intents.get(creep.id);
  if (!m) { m = {}; world.intents.set(creep.id, m); }
  m[cat] = data;
}

function effectOf(obj, type) { return obj.effects ? obj.effects.find((x) => x.effectType === type) : undefined; }
export function effectMul(obj, type) {
  const e = effectOf(obj, type);
  return e ? (e.data.multiplier ?? 1) : 1;
}
export function effectOffset(obj, type) {
  const e = effectOf(obj, type);
  return e ? (e.data.offset ?? 0) : 0;
}

const DIRS = { 1: [0, -1], 2: [1, -1], 3: [1, 0], 4: [1, 1], 5: [0, 1], 6: [-1, 1], 7: [-1, 0], 8: [-1, -1] };

/** Fatigue weight: every part but MOVE (dead ones included), a CARRY only while energy fills it — in body order, 50 each. */
export function weight(c) {
  let w = 0, carried = c.store ? c.store.energy : 0;
  for (const p of c.body) {
    if (p.type === 'move') continue;
    if (p.type === 'carry') { if (carried > 0) { w++; carried -= CARRY_CAPACITY; } continue; }
    w++;
  }
  return w;
}

/** Tower power at a range: full up to TOWER_OPTIMAL_RANGE, then linear down to (1 - FALLOFF) at TOWER_FALLOFF_RANGE. */
export function towerPower(power, r) {
  if (r <= TOWER_OPTIMAL_RANGE) return power;
  const rr = Math.min(r, TOWER_FALLOFF_RANGE);
  return Math.round(power * (1 - TOWER_FALLOFF * (rr - TOWER_OPTIMAL_RANGE) / (TOWER_FALLOFF_RANGE - TOWER_OPTIMAL_RANGE)));   // 50.00000000000004 at 20 otherwise
}

function applyDamage(t, dmg) {
  if (t.kind === 'creep') {
    let left = dmg;
    for (const p of t.body) {
      if (left <= 0) break;
      const take = Math.min(p.hits, left);
      p.hits -= take;
      left -= take;
    }
    // overkill is not lost: the engine sums damage and heal before checking death (6 - 99 + 12 < 0 dies)
    t.overkill = (t.overkill || 0) + left;
    t.hits = t.body.reduce((s, p) => s + p.hits, 0);
  } else if (typeof t.hits === 'number') {
    t.hits -= dmg;
  }
}

function applyHeal(t, heal) {
  let left = heal - (t.overkill || 0);
  t.overkill = 0;
  if (left <= 0) return;
  for (let i = t.body.length - 1; i >= 0; i--) {
    const p = t.body[i];
    if (p.hits >= 100) continue;
    const add = Math.min(100 - p.hits, left);
    p.hits += add;
    left -= add;
    if (left <= 0) break;
  }
  t.hits = t.body.reduce((s, p) => s + p.hits, 0);
}

export function dropEnergy(x, y, amount, ResourceClass) {
  if (amount <= 0) return;
  const existing = world.objects.find((o) => o.exists && o.kind === 'resource' && o.x === x && o.y === y);
  if (existing) { existing.amount += amount; return; }
  world.objects.push(new ResourceClass(x, y, amount));
}

// same-type flags stack (live, 27.09.2026): the weapons x0.8 / x0.6, healing x0.75 / x0.5, damage taken x1.1 (one flag of
// the type), fatigue x2 / x4, hits loss -1 / -2 (an OFFSET, data.offset, not a multiplier)
const STACK = {
  eff_attack_modifier: [1, 0.8, 0.6],
  eff_ranged_attack_modifier: [1, 0.8, 0.6],
  eff_heal_modifier: [1, 0.75, 0.5],
  eff_damage_taken_modifier: [1, 1.1],
  eff_fatigue_modifier: [1, 2, 4],
};
const OFFSET = { eff_hits_loss: [0, -1, -2] };
// the order the live runtime listed them in a creep's `effects` (v1 probe, t=500)
const ORDER = ['eff_attack_modifier', 'eff_ranged_attack_modifier', 'eff_heal_modifier', 'eff_damage_taken_modifier', 'eff_fatigue_modifier', 'eff_hits_loss'];
const side = (o) => (o === 0 ? 'ours' : o === 1 ? 'enemy' : 'none');

export function process(ResourceClass) {
  const t = world.tick;
  const st = world.stats;
  // 0. flags and towers change hands BEFORE this tick's moves: a creep that steps onto a flag in tick t takes it in tick
  // t+1, and the flag's effect acts on its side's moves from tick t+2. Measured on replay 6ab9349f: our puller stood on
  // the fatigue flag after tick 64, the flag and its tower changed owner in tick 65 (the record's `w`), our heavies
  // still moved at x1 fatigue in tick 65 and at x2 from tick 66. Until 27.09.2026 the stand took the flag in the tick of
  // the step and laid the effect a tick later — two ticks early, and a `ghost` run left our recorded cells at t=66
  const flags = world.objects.filter((o) => o.exists && o.kind === 'flag');
  for (const f of flags) {
    const c = creepAt(f.x, f.y);
    if (c && f.owner !== c.owner) {
      world.events.push(`t=${t} flag ${f.id} (${f.x},${f.y}) ${f.effectType}/${f.scorePerTick}: ${side(f.owner)} -> ${side(c.owner)} by ${c.id} ${c.summary()}`);
      f.owner = c.owner;
    }
  }
  for (const tw of world.objects) {
    if (!tw.exists || tw.kind !== 'tower' || !tw.flagId) continue;
    const f = flags.find((x) => x.id === tw.flagId);
    const owner = f ? f.owner : undefined;
    if (owner !== tw.owner) { world.events.push(`t=${t} tower (${tw.x},${tw.y}) ${side(tw.owner)} -> ${side(owner)} with ${tw.flagId}`); tw.owner = owner; }
  }
  // 1. combat: creeps, towers, hits loss — summed per target, then damage, then heal
  const damage = new Map();
  const heals = new Map();
  const addD = (target, d) => { if (d > 0) damage.set(target, (damage.get(target) || 0) + d); };
  const addH = (target, h) => { if (h > 0) heals.set(target, (heals.get(target) || 0) + h); };
  const taken = (target) => (target.kind === 'creep' ? effectMul(target, 'eff_damage_taken_modifier') : 1);
  for (const [id, m] of world.intents) {
    const c = byId(id);
    if (!c || !c.exists || c.spawning) continue;
    if (m.melee) {
      const tg = m.melee.target;
      if (tg && tg.exists && range(c, tg) <= 1 && tg.owner !== c.owner) addD(tg, live(c, 'attack') * 30 * effectMul(c, 'eff_attack_modifier') * taken(tg));
    }
    if (m.ranged) {
      if (m.ranged.type === 'attack') {
        const tg = m.ranged.target;
        if (tg && tg.exists && range(c, tg) <= 3 && tg.owner !== c.owner) addD(tg, live(c, 'ranged_attack') * 10 * effectMul(c, 'eff_ranged_attack_modifier') * taken(tg));
      } else if (m.ranged.type === 'mass') {
        const parts = live(c, 'ranged_attack');
        for (const tg of world.objects) {
          if (!tg.exists || tg === c || tg.owner === undefined || tg.owner === c.owner || typeof tg.hits !== 'number') continue;
          if (tg.kind === 'creep' && tg.spawning) continue;
          const d = range(c, tg);
          if (d > 3) continue;
          const rate = d <= 1 ? 1 : d === 2 ? 0.4 : 0.1;
          addD(tg, parts * 10 * rate * effectMul(c, 'eff_ranged_attack_modifier') * taken(tg));
        }
      } else if (m.ranged.type === 'heal') {
        const tg = m.ranged.target;
        if (tg && tg.exists && tg.kind === 'creep' && range(c, tg) <= 3 && tg.owner === c.owner) addH(tg, live(c, 'heal') * 4 * effectMul(c, 'eff_heal_modifier'));
      }
    }
    if (m.heal) {
      const tg = m.heal.target;
      if (tg && tg.exists && tg.kind === 'creep' && range(c, tg) <= 1 && tg.owner === c.owner) addH(tg, live(c, 'heal') * 12 * effectMul(c, 'eff_heal_modifier'));
    }
  }
  for (const [id, m] of world.towerIntents) {
    const tw = byId(id);
    // the tower may have changed hands between the call and the resolution only through a flag capture, which comes
    // later in the tick — so the checks of attack()/heal() still hold; energy and cooldown are re-checked anyway
    if (!tw || !tw.exists || tw.owner === undefined || tw.store.energy < TOWER_ENERGY_COST || tw.cooldown > 0) continue;
    const tg = m.target;
    if (!tg || !tg.exists) continue;
    const r = range(tw, tg);
    tw.store.energy -= TOWER_ENERGY_COST;
    tw.cooldown = TOWER_COOLDOWN;
    if (m.type === 'attack') {
      const d = towerPower(TOWER_POWER_ATTACK, r) * taken(tg);
      addD(tg, d);
      st.towerShots[tw.owner]++; st.towerDamage[tw.owner] += d;
      world.events.push(`t=${t} tower ${side(tw.owner)} (${tw.x},${tw.y}) shoots ${side(tg.owner)} ${tg.summary ? tg.summary() : tg.kind} at (${tg.x},${tg.y}) r=${r} for ${Math.round(d)}`);
    } else {
      const h = towerPower(TOWER_POWER_HEAL, r);
      if (tg.kind === 'creep') addH(tg, h);
      st.towerHeals[tw.owner]++; st.towerHealed[tw.owner] += h;
    }
  }
  // hits loss: every creep of a side holding hits_loss flags loses |offset| hits this tick, like damage
  for (const c of creeps()) {
    if (c.spawning) continue;
    const off = effectOffset(c, 'eff_hits_loss');
    if (off < 0) { addD(c, -off); if (c.owner === 0 || c.owner === 1) st.hitsLoss[c.owner] += -off; }
  }
  for (const [tg, d] of damage) applyDamage(tg, d);
  for (const [tg, h] of heals) if (tg.exists && tg.kind === 'creep') applyHeal(tg, h);
  for (const o of world.objects) {
    if (!o.exists || typeof o.hits !== 'number') continue;
    if (o.kind === 'creep') o.overkill = 0;
    if (o.hits <= 0) {
      o.exists = false;
      if (o.kind === 'creep') {
        world.events.push(`t=${t} creep ${side(o.owner)} ${o.id} ${o.summary()} died at (${o.x},${o.y})`);
        if (o.store && o.store.energy > 0) dropEnergy(o.x, o.y, o.store.energy, ResourceClass);
      } else {
        world.events.push(`t=${t} ${o.kind} ${side(o.owner)} destroyed at (${o.x},${o.y})`);
      }
    }
  }
  // 2. resources: transfer, withdraw, pickup, drop
  for (const [id, m] of world.intents) {
    const c = byId(id);
    if (!c || !c.exists || c.spawning) continue;
    if (m.transfer) {
      const tg = m.transfer.target;
      if (tg && tg.exists && tg.store && range(c, tg) <= 1) {
        const amount = Math.min(c.store.energy, tg.store.free(), m.transfer.amount ?? Infinity);
        if (amount > 0) {
          c.store.energy -= amount; tg.store.energy += amount;
          if (tg.kind === 'tower' && (c.owner === 0 || c.owner === 1)) st.towerFed[c.owner] += amount;
        }
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
    if (m.drop) {
      const amount = Math.min(c.store.energy, m.drop.amount ?? Infinity);
      if (amount > 0) { c.store.energy -= amount; dropEnergy(c.x, c.y, amount, ResourceClass); }
    }
  }
  // 3. movement (simultaneous, swaps and chains legal); the fatigue of a move is weight x 2 (plain) / x 10 (swamp) by
  // the destination cell, times the owner's fatigue multiplier
  const movers = new Map();
  const targetTaken = new Map();
  for (const [id, m] of world.intents) {
    if (!m.move) continue;
    const c = byId(id);
    if (!c || !c.exists || c.spawning || c.fatigue > 0 || live(c, 'move') === 0) continue;
    const d = DIRS[m.move.dir];
    if (!d) continue;
    const tx = c.x + d[0], ty = c.y + d[1];
    if (!inBounds(tx, ty) || blockedAt(tx, ty)) continue;
    const key = idx(tx, ty);
    if (targetTaken.has(key)) continue;
    targetTaken.set(key, c);
    movers.set(c.id, { c, tx, ty });
  }
  const occ = new Map();
  for (const c of creeps()) if (!c.spawning) occ.set(idx(c.x, c.y), c);
  let changed = true;
  while (changed) {
    changed = false;
    for (const m of [...movers.values()]) {
      const o = occ.get(idx(m.tx, m.ty));
      if (o && o.id !== m.c.id && !movers.has(o.id)) { movers.delete(m.c.id); changed = true; }
    }
  }
  for (const m of movers.values()) {
    m.c.x = m.tx; m.c.y = m.ty;
    const base = weight(m.c) * (terrainAt(m.tx, m.ty) === 2 ? 10 : 2);
    const mul = effectMul(m.c, 'eff_fatigue_modifier');
    m.c.fatigue += base * mul;
    if (mul > 1 && (m.c.owner === 0 || m.c.owner === 1)) { st.fatigueMoves[m.c.owner]++; st.fatigueExtra[m.c.owner] += base * (mul - 1); }
    m.c.moved = t;
  }
  // 4. fatigue decay
  for (const c of creeps()) c.fatigue = Math.max(0, c.fatigue - 2 * live(c, 'move'));
  // 5. score, effects — from the ownership the tick began with plus this tick's captures (step 0); the effects laid
  // here act from the NEXT tick
  for (const p of [0, 1]) {
    const mine = flags.filter((f) => f.owner === p);
    world.score[p] += Math.min(world.maxScorePerTick, mine.reduce((s, f) => s + f.scorePerTick, 0));
    const counts = {};
    for (const f of mine) counts[f.effectType] = (counts[f.effectType] || 0) + 1;
    const effects = [];
    for (const type of ORDER) {
      const n = counts[type];
      if (!n) continue;
      const data = OFFSET[type] ? { offset: OFFSET[type][Math.min(n, OFFSET[type].length - 1)] } : { multiplier: (STACK[type] || [1])[Math.min(n, (STACK[type] || [1]).length - 1)] };
      effects.push({ effectType: type, endTime: world.ticksLimit + 10, data });
      const v = data.offset ?? data.multiplier;
      const mx = st.effectsMax[p];
      if (mx[type] === undefined || Math.abs(v - (OFFSET[type] ? 0 : 1)) > Math.abs(mx[type] - (OFFSET[type] ? 0 : 1))) mx[type] = v;
      st.effectTicks[p][type] = (st.effectTicks[p][type] || 0) + 1;
    }
    for (const c of creeps()) if (c.owner === p) c.effects = effects.map((e) => ({ effectType: e.effectType, endTime: e.endTime, data: { ...e.data } }));
  }
  // 6. cooldowns, decay
  for (const o of world.objects) {
    if (!o.exists) continue;
    if (o.kind === 'tower' && o.cooldown > 0) o.cooldown--;
    if (o.kind === 'resource') { o.amount -= 1; if (o.amount <= 0) o.exists = false; }
  }
  world.intents.clear();
  world.towerIntents.clear();
  world.tick++;
}
