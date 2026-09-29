import { OwnedStructure } from './owned-structure.mjs';
import { Store } from './store.mjs';
import { EXTENSION_HITS, EXTENSION_ENERGY_CAPACITY } from '../constants.mjs';

export class StructureExtension extends OwnedStructure {
  constructor(x, y, owner) { super(x, y, EXTENSION_HITS, owner); this.kind = 'extension'; this.store = new Store(EXTENSION_ENERGY_CAPACITY, 0); }
}
