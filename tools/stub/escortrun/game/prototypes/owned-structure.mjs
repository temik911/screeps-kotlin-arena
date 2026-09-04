import { Structure } from './structure.mjs';

export class OwnedStructure extends Structure {
  constructor(x, y, hits, owner) { super(x, y, hits); this.owner = owner; }
}
