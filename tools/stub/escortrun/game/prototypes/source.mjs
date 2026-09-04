import { GameObject } from './game-object.mjs';

export class Source extends GameObject {
  constructor(x, y, energy = 1000, capacity = 1000) { super(x, y); this.kind = 'source'; this.energy = energy; this.energyCapacity = capacity; }
}
