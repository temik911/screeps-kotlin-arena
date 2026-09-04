import { Structure } from './structure.mjs';

export class StructureWall extends Structure {
  constructor(x, y, hits = 10000) { super(x, y, hits); this.kind = 'wall'; }
}
