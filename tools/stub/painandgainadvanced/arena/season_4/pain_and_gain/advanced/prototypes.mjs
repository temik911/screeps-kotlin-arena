import { Flag } from '../../../../game/prototypes/flag.mjs';

/** An instantly captured flag that scores points at the cost of a global debuff. `my` is undefined while neutral
 *  (GameObject.my), true/false once a creep has stood on it; ownership stays after the creep leaves. */
export class ScoreFlag extends Flag {
  constructor(x, y, effectType, scorePerTick) { super(x, y); this.effectType = effectType; this.scorePerTick = scorePerTick; }
}
