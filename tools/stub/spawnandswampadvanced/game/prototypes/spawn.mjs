import { OwnedStructure } from './owned-structure.mjs';
import { Store } from './store.mjs';
import { Creep } from './creep.mjs';
import { world, range } from '../../world.mjs';
import { SPAWN_RANGE, SPAWN_HITS, SPAWN_ENERGY_CAPACITY, MAX_CREEP_SIZE } from '../constants.mjs';

/**
 * A spawn. spawnCreep takes energy from the owner's spawns and extensions within SPAWN_RANGE (arena-docs; World's
 * _charge-energy.js order: spawns by distance from this one — itself first — then extensions by distance). Measured live
 * (docs/spawn-and-swamp-advanced.md, v29): `M10H6` and `M10R10` of 2000 were born at 1000 in the spawn itself with a twin
 * beside it (`pool=2000/2`). The body appears on the spawn's cell at once, spawning, and comes out 3 ticks a part later on
 * the first free cell in the order TOP..TOP_LEFT (world.mjs, births; the replay: `n` at the order's tick, out at +3n-1).
 */
export class StructureSpawn extends OwnedStructure {
  constructor(x, y, owner, energy = 0) {
    super(x, y, SPAWN_HITS, owner);
    this.kind = 'spawn';
    this.store = new Store(SPAWN_ENERGY_CAPACITY, energy);
    this.spawning = null;
    this.directions = null;
  }
  setDirections(d) { this.directions = d; return 0; }
  /** The energy structures this spawn may draw from, in World's order. */
  pool() {
    const by = (a, b) => range(this, a) - range(this, b);
    const spawns = world.objects.filter((o) => o.exists && o.kind === 'spawn' && o.owner === this.owner && range(this, o) <= SPAWN_RANGE).sort(by);
    const exts = world.objects.filter((o) => o.exists && o.kind === 'extension' && o.owner === this.owner && range(this, o) <= SPAWN_RANGE).sort(by);
    return [...spawns, ...exts];
  }
  spawnCreep(body) {
    if (this.owner !== world.perspective) return { error: -1 };
    if (this.spawning) return { error: -4 };
    if (!body || body.length === 0 || body.length > MAX_CREEP_SIZE) return { error: -10 };
    const cost = Creep.cost(body);
    const pool = this.pool();
    if (cost > pool.reduce((s, o) => s + o.store.energy, 0)) return { error: -6 };
    let left = cost;
    for (const o of pool) { if (left <= 0) break; const take = Math.min(o.store.energy, left); o.store.energy -= take; left -= take; }
    return { object: this.begin(body, cost) };
  }
  /** Put a body into the spawn (a scripted enemy's granted order goes through here too, without the charge). */
  begin(body, cost = Creep.cost(body), id, directions) {
    const c = new Creep(this.x, this.y, this.owner, body);
    if (id !== undefined) c.id = String(id);
    c.spawning = true;
    c.bornAt = world.tick; // the tick of the order — the replay's `n` tick of the same creep (run.mjs matches them)
    world.objects.push(c);
    const needTime = body.length * 3;
    const sp = this;
    this.spawning = { needTime, remainingTime: needTime, creepObj: c, creep: c, directions: directions || this.directions, cancel() { if (sp.owner !== world.perspective) return -1; c.exists = false; sp.spawning = null; return 0; } };
    world.events.push(`t=${world.tick} spawn ${this.owner === 0 ? 'ours' : 'enemy'} (${this.x},${this.y}) orders ${c.summary()} (${cost})`);
    return c;
  }
}

export class Spawning {}
