import { GameObject } from './game-object.mjs';

export class Structure extends GameObject {
  constructor(x, y, hits) { super(x, y); this.hits = hits; this.hitsMax = hits; }
}
