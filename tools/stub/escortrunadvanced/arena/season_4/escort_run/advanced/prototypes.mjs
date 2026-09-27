import { Creep } from '../../../../game/prototypes/creep.mjs';

/**
 * A creep that is present on the map from the start and must be escorted to the goal (client typings). The ADVANCED
 * level has three a side (M10T40, M5T40, M3T42). The bot finds them by `constructor.name === 'EscortCreep'` and never
 * imports this module; it is here so a bot that does import it loads.
 */
export class EscortCreep extends Creep {
  constructor(x, y, owner, body) { super(x, y, owner, body); this.escort = true; }
}
