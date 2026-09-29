import { GameObject } from './game-object.mjs';
import { world } from '../../world.mjs';

/** A construction site. `structure` is the structure it will become — the runtime shows it to the owner only: the live
 *  log of v58 prints his sites as `site=?` and ours as `site=StructureSpawn`; the bot reads the type through it. */
export class ConstructionSite extends GameObject {
  constructor(x, y, owner, progressTotal) { super(x, y); this.kind = 'site'; this.owner = owner; this.progress = 0; this.progressTotal = progressTotal; this.proto = null; }
  get structure() { return this.proto && this.owner === world.perspective ? Object.create(this.proto.prototype) : undefined; }
  remove() { if (this.owner !== world.perspective) return -1; this.exists = false; return 0; }
}
