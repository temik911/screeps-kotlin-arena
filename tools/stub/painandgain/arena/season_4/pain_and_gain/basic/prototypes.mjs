import { Flag } from '../../../../game/prototypes/flag.mjs';

/** An instantly captured flag that scores points at the cost of a global debuff. */
export class ScoreFlag extends Flag {
  constructor(x, y, effectType, scorePerTick) { super(x, y); this.effectType = effectType; this.scorePerTick = scorePerTick; }
}
