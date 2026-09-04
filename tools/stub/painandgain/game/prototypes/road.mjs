import { Structure } from './structure.mjs';

export class StructureRoad extends Structure {
  constructor(x, y) { super(x, y, 5000); this.kind = 'road'; }
}
