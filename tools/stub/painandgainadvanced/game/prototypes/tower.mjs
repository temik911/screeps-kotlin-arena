import { OwnedStructure } from './owned-structure.mjs';
import { Store } from './store.mjs';
import { world, range } from '../../world.mjs';
import { OK, ERR_NOT_OWNER, ERR_INVALID_TARGET, ERR_NOT_ENOUGH_ENERGY, ERR_TIRED, ERR_NOT_IN_RANGE, TOWER_CAPACITY, TOWER_ENERGY_COST, TOWER_RANGE, TOWER_HITS } from '../constants.mjs';

/**
 * A tower of the advanced arena: neutral at the start (`my` undefined), owned by whoever owns the flag it stands by
 * (world.mjs keeps `owner` in step with the linked flag), store capacity 10, 10 energy and TOWER_COOLDOWN ticks per
 * action. attack/heal check what the engine checks and return its codes; a valid call is an intent that world.mjs
 * resolves with the creeps' combat (damage by range with the falloff, the target's damage-taken modifier applies,
 * the owner's flag effects do not).
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
