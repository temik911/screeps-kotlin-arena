import { OwnedStructure } from './owned-structure.mjs';
import { Store } from './store.mjs';
import { world, range } from '../../world.mjs';
import { OK, ERR_NOT_OWNER, ERR_INVALID_TARGET, ERR_NOT_ENOUGH_ENERGY, ERR_TIRED, ERR_NOT_IN_RANGE, TOWER_CAPACITY, TOWER_ENERGY_COST, TOWER_RANGE, TOWER_HITS } from '../constants.mjs';

/**
 * A tower (the Escort Run advanced bot builds one from v7): store capacity 10, 10 energy and TOWER_COOLDOWN ticks per
 * action. attack/heal check what the engine checks and return its codes; a valid call is an intent that world.mjs
 * resolves with the creeps' combat (power by range with the falloff; a hit on a creep under a rampart goes into the
 * rampart). The model is the one of tools/stub/painandgainadvanced/game/prototypes/tower.mjs, copied without its flag
 * ownership — here a tower belongs to whoever built it. The basic stub's tower did nothing.
 */
export class StructureTower extends OwnedStructure {
  constructor(x, y, owner) { super(x, y, TOWER_HITS, owner); this.kind = 'tower'; this.store = new Store(TOWER_CAPACITY, 0); this.cooldown = 0; }
  act(type, target) {
    if (!this.exists) return ERR_INVALID_TARGET;
    if (this.owner === undefined || this.owner !== world.perspective) return ERR_NOT_OWNER;
    if (!target || !target.exists || typeof target.hits !== 'number' || (type === 'heal' && target.kind !== 'creep')) return ERR_INVALID_TARGET;
    if (this.store.energy < TOWER_ENERGY_COST) return ERR_NOT_ENOUGH_ENERGY;
    if (this.cooldown > 0) return ERR_TIRED;
    if (range(this, target) > TOWER_RANGE) return ERR_NOT_IN_RANGE;
    world.towerIntents.set(this.id, { type, target });
    return OK;
  }
  attack(target) { return this.act('attack', target); }
  heal(target) { return this.act('heal', target); }
}
