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
    if (cost > this.store.energy) return { error: -6 };
    this.store.energy -= cost;
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
