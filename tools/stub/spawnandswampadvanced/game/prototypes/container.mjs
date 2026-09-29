import { OwnedStructure } from './owned-structure.mjs';
import { Store } from './store.mjs';

/** A container. The map's eight (two vaults of four, 2500 each) carry no hits in the replay's object list (hits 0, never
 *  in a series) and nobody's fire reaches them: `hits` is left undefined, so no attack takes them. */
export class StructureContainer extends OwnedStructure {
  constructor(x, y, energy, capacity = 2000) {
    super(x, y, undefined, undefined);
    delete this.hits; delete this.hitsMax;
    this.kind = 'container';
    this.store = new Store(Math.max(capacity, energy), energy);
  }
}
