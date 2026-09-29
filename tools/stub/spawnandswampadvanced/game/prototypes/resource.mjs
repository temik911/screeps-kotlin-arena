import { GameObject } from './game-object.mjs';

/** A pile on the ground; it decays ceil(amount / RESOURCE_DECAY) a tick (arena-docs; the replay's small piles lose 1 a tick). */
export class Resource extends GameObject {
  constructor(x, y, amount) { super(x, y); this.kind = 'resource'; this.resourceType = 'energy'; this.amount = amount; }
  get ticksToDecay() { return this.amount; }
  set ticksToDecay(v) {}
}
