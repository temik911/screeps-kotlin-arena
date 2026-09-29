import { world, range } from '../../world.mjs';

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
  findClosestByPath(arr, opts) { return world.api.findClosestByPath(this, arr, opts); }
  // game/utils findPath (obstacles avoided unless a costMatrix is given); utils.mjs puts itself on world.api — a static
  // import here would close the cycle game-object -> utils -> construction-site -> game-object
  findPathTo(pos, opts) { return world.api.findPath(this, pos, opts); }
}

export class Effect {}
