import { OwnedStructure } from './owned-structure.mjs';
import { Store } from './store.mjs';

export class StructureExtension extends OwnedStructure {
  constructor(x, y, owner) { super(x, y, 100, owner); this.kind = 'extension'; this.store = new Store(100, 0); }
}
