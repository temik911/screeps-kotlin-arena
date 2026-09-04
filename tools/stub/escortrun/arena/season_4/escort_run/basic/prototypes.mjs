import { Creep } from '../../../../game/prototypes/creep.mjs';

/** A creep that is present on the map from the start and must be escorted to the goal (client typings). */
export class EscortCreep extends Creep {
  constructor(x, y, owner, body) { super(x, y, owner, body); this.escort = true; }
}
