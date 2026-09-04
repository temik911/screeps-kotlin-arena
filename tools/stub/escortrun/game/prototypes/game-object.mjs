import { world, range } from '../../world.mjs';
import { searchPath } from '../path-finder.mjs';

export class GameObject {
  constructor(x, y) {
    this.id = String(world.nextId++);
    this.x = x;
    this.y = y;
    this.exists = true;
    this.ticksToDecay = undefined;
    this.effects = undefined;
    this.owner = undefined;
  }
  get my() { return this.owner === undefined ? undefined : this.owner === world.perspective; }
  getRangeTo(p) { return range(this, p); }
  findInRange(arr, r) { return arr.filter((o) => range(this, o) <= r); }
  findClosestByRange(arr) { let best = null, bd = Infinity; for (const o of arr) { const d = range(this, o); if (d < bd) { bd = d; best = o; } } return best; }
  findClosestByPath(arr) { return this.findClosestByRange(arr); }
  findPathTo(pos, opts) { return searchPath(this, pos, opts).path; }
}

export class Effect {}
