import { OwnedStructure } from './owned-structure.mjs';

export class StructureRampart extends OwnedStructure {
  constructor(x, y, owner, hits = 10000) { super(x, y, hits, owner); this.kind = 'rampart'; }
}
