import { Structure } from './structure.mjs';
import { Store } from './store.mjs';

/** The advanced arena's containers are neutral and indestructible: the live probe printed `hits=undefined` for all
 *  eight, the replay `hits: 0, hitsMax: 0`. Walkable (not in world.mjs BLOCKING). */
export class StructureContainer extends Structure {
  constructor(x, y, energy, capacity = 2000, decay) {
    super(x, y, undefined);
    this.kind = 'container';
    this.store = new Store(Math.max(capacity, energy), energy);
    this.ticksToDecay = decay;
  }
}
