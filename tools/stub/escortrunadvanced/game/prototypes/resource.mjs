import { GameObject } from './game-object.mjs';

export class Resource extends GameObject {
  constructor(x, y, amount) { super(x, y); this.kind = 'resource'; this.resourceType = 'energy'; this.amount = amount; }
  get ticksToDecay() { return this.amount; }
  set ticksToDecay(v) {}
}
