import { GameObject } from './game-object.mjs';

export class ConstructionSite extends GameObject {
  constructor(x, y, owner, progressTotal) { super(x, y); this.kind = 'site'; this.owner = owner; this.progress = 0; this.progressTotal = progressTotal; this.structure = undefined; }
  remove() { this.exists = false; return 0; }
}
