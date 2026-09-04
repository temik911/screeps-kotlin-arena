import { OwnedStructure } from './owned-structure.mjs';
import { Store } from './store.mjs';

export class StructureTower extends OwnedStructure {
  constructor(x, y, owner) { super(x, y, 3000, owner); this.kind = 'tower'; this.store = new Store(10, 0); this.cooldown = 0; }
  attack() { return 0; }
  heal() { return 0; }
}
