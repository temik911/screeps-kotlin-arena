// Simulation state and engine for the offline stub (Pain and Gain, basic).
// Movement: simultaneous with swaps/chains; fatigue by part TYPE (dead parts weigh), live MOVEs shed 2/tick.
// Damage removes body parts front to back. Flags: a creep standing on a flag captures it; owner scores per tick;
// score-flag effects are applied to every creep of the owner (stacking table from the client docs).

export const world = {
  tick: 1,
  terrain: new Uint8Array(10000), // 0 plain, 1 wall, 2 swamp
  objects: [],
  nextId: 1,
  perspective: 0,
  score: [0, 0],
  intents: new Map(),
  spawnRegen: [1, 1],
  ticksLimit: 2000,
  maxScorePerTick: 25,
  events: [],
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

export function effectMul(obj, type) {
  const e = obj.effects && obj.effects.find((x) => x.effectType === type);
  if (!e) return 1;
  return (e.data.multiplier ?? 1);
}

const DIRS = { 1: [0, -1], 2: [1, -1], 3: [1, 0], 4: [1, 1], 5: [0, 1], 6: [-1, 1], 7: [-1, 0], 8: [-1, -1] };

export function weight(c) {
  const parts = c.body.reduce((n, p) => n + (p.type !== 'move' && p.type !== 'carry' ? 1 : 0), 0);
  const carried = c.store ? c.store.energy : 0;
  return parts + Math.ceil(carried / 50);
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
    // overkill is not lost: the engine sums damage and heal before checking death (6 - 99 + 12 < 0 dies),
    // a per-part clamp made a 6-hit healer immortal under three melee
    t.overkill = (t.overkill || 0) + left;
    t.hits = t.body.reduce((s, p) => s + p.hits, 0);
  } else {
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

function freeAdjacent(x, y) {
  for (let dx = -1; dx <= 1; dx++) for (let dy = -1; dy <= 1; dy++) {
    if (dx === 0 && dy === 0) continue;
    const nx = x + dx, ny = y + dy;
    if (!inBounds(nx, ny) || blockedAt(nx, ny) || creepAt(nx, ny)) continue;
    return { x: nx, y: ny };
  }
  return null;
}

export function dropEnergy(x, y, amount, ResourceClass) {
  if (amount <= 0) return;
  const existing = world.objects.find((o) => o.exists && o.kind === 'resource' && o.x === x && o.y === y);
  if (existing) { existing.amount += amount; return; }
  world.objects.push(new ResourceClass(x, y, amount));
}

const STACK = {
  eff_attack_modifier: [1, 0.8, 0.6, 0.4],
  eff_ranged_attack_modifier: [1, 0.8, 0.6, 0.4],
  eff_heal_modifier: [1, 0.75, 0.5, 0.25],
  eff_damage_taken_modifier: [1, 1.1, 1.2, 1.3],
};

export function process(ResourceClass) {
  const t = world.tick;
  // 1. spawns finish
  for (const s of world.objects) {
    if (s.kind !== 'spawn' || !s.exists || !s.spawning) continue;
    s.spawning.remainingTime--;
    if (s.spawning.remainingTime <= 0) {
      const cell = freeAdjacent(s.x, s.y);
      if (cell) {
        const c = s.spawning.creepObj;
        c.x = cell.x; c.y = cell.y; c.spawning = false;
        s.spawning = null;
      } else s.spawning.remainingTime = 1;
    }
  }
  // 2. combat
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
          if (!tg.exists || tg === c || tg.owner === undefined || tg.owner === c.owner) continue;
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
  for (const [tg, d] of damage) applyDamage(tg, d);
  for (const [tg, h] of heals) if (tg.exists && tg.kind === 'creep') applyHeal(tg, h);
  for (const o of world.objects) {
    if (!o.exists || o.hits === undefined) continue;
    if (o.hits <= 0) {
      o.exists = false;
      if (o.kind === 'creep') {
        world.events.push(`t=${t} creep ${o.owner === 0 ? 'ours' : 'enemy'} ${o.summary()} died at (${o.x},${o.y})`);
        if (o.store && o.store.energy > 0) dropEnergy(o.x, o.y, o.store.energy, ResourceClass);
      } else {
        world.events.push(`t=${t} ${o.kind} ${o.owner === 0 ? 'ours' : o.owner === 1 ? 'enemy' : ''} destroyed at (${o.x},${o.y})`);
      }
    }
  }
  // 3. resources
  for (const [id, m] of world.intents) {
    const c = byId(id);
    if (!c || !c.exists || c.spawning) continue;
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
    if (m.harvest) {
      const s = m.harvest.target;
      if (s && s.exists && range(c, s) <= 1) {
        const amount = Math.min(s.energy, c.store.free(), live(c, 'work') * 2);
        if (amount > 0) { s.energy -= amount; c.store.energy += amount; }
      }
    }
    if (m.drop) {
      const amount = Math.min(c.store.energy, m.drop.amount ?? Infinity);
      if (amount > 0) { c.store.energy -= amount; dropEnergy(c.x, c.y, amount, ResourceClass); }
    }
  }
  // 4. movement (simultaneous, swaps and chains legal)
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
    m.c.fatigue += weight(m.c) * (terrainAt(m.tx, m.ty) === 2 ? 10 : 2);
    m.c.moved = t;
  }
  // 5. fatigue decay
  for (const c of creeps()) c.fatigue = Math.max(0, c.fatigue - 2 * live(c, 'move'));
  // 6. flags, score, effects
  const flags = world.objects.filter((o) => o.exists && o.kind === 'flag');
  for (const f of flags) {
    const c = creepAt(f.x, f.y);
    if (c && f.owner !== c.owner) {
      world.events.push(`t=${t} flag (${f.x},${f.y}) ${f.effectType}/${f.scorePerTick}: ${f.owner === undefined ? 'none' : f.owner === 0 ? 'ours' : 'enemy'} -> ${c.owner === 0 ? 'ours' : 'enemy'} by ${c.summary()}`);
      f.owner = c.owner;
    }
  }
  for (const p of [0, 1]) {
    const mine = flags.filter((f) => f.owner === p);
    world.score[p] += Math.min(world.maxScorePerTick, mine.reduce((s, f) => s + f.scorePerTick, 0));
    const counts = {};
    for (const f of mine) counts[f.effectType] = (counts[f.effectType] || 0) + 1;
    const effects = [];
    for (const type in counts) {
      const table = STACK[type] || [1];
      effects.push({ effectType: type, data: { multiplier: table[Math.min(counts[type], table.length - 1)] } });
    }
    for (const c of creeps()) if (c.owner === p) c.effects = effects.map((e) => ({ effectType: e.effectType, data: { ...e.data } }));
  }
  // 7. regen and decay
  for (const o of world.objects) {
    if (!o.exists) continue;
    if (o.kind === 'spawn') o.store.energy = Math.min(o.store.capacity, o.store.energy + (world.spawnRegen[o.owner] || 0));
    if (o.kind === 'source' && o.energy < o.energyCapacity) o.energy = Math.min(o.energyCapacity, o.energy + 10);
    if (o.kind === 'resource') { o.amount -= 1; if (o.amount <= 0) o.exists = false; }
    if (o.kind === 'container' && o.ticksToDecay !== undefined) { o.ticksToDecay--; if (o.ticksToDecay <= 0) o.exists = false; }
  }
  world.intents.clear();
  world.tick++;
}
