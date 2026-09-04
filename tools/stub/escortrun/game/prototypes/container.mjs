import { OwnedStructure } from './owned-structure.mjs';
import { Store } from './store.mjs';

export class StructureContainer extends OwnedStructure {
  constructor(x, y, energy, capacity = 2000, decay) {
    super(x, y, 300, undefined);
    this.kind = 'container';
    this.store = new Store(Math.max(capacity, energy), energy);
    this.ticksToDecay = decay;
  }
}
