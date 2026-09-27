import { OwnedStructure } from './owned-structure.mjs';
import { Store } from './store.mjs';
import { Creep } from './creep.mjs';
import { world } from '../../world.mjs';

export class StructureSpawn extends OwnedStructure {
  constructor(x, y, owner, energy = 1000) {
    super(x, y, 3000, owner);
    this.kind = 'spawn';
    this.store = new Store(1000, energy);
    this.spawning = null;
    this.directions = [];
  }
  setDirections() { return 0; }
  spawnCreep(body) {
    if (this.spawning) return { error: -4 };
    if (!body || body.length === 0 || body.length > 50) return { error: -10 };
    const cost = Creep.cost(body);
    // the spawn pays from its own store and from every extension of its owner — measured live 27.09.2026 (v15 wip vs
    // System, 6ab9487d, t=457: an M18 of 900 took the spawn's 576 and three extensions' 100+100+22 at once)
    const exts = world.objects.filter((o) => o.kind === 'extension' && o.owner === this.owner && o.store && o.store.energy > 0);
    const have = this.store.energy + exts.reduce((s, e) => s + e.store.energy, 0);
    if (cost > have) return { error: -6 };
    let left = cost;
    const fromSpawn = Math.min(this.store.energy, left);
    this.store.energy -= fromSpawn; left -= fromSpawn;
    for (const e of exts) { if (left <= 0) break; const take = Math.min(e.store.energy, left); e.store.energy -= take; left -= take; }
    const c = new Creep(this.x, this.y, this.owner, body);
    c.spawning = true;
    world.objects.push(c);
    const needTime = body.length * 3;
    this.spawning = { needTime, remainingTime: needTime, creepObj: c, creep: { id: c.id, body: c.body, hitsMax: c.hitsMax, get my() { return c.my; } } };
    world.events.push(`t=${world.tick} spawn ${this.owner === 0 ? 'ours' : 'enemy'} orders ${c.summary()} (${cost})`);
    return { object: c };
  }
}

export class Spawning {}
