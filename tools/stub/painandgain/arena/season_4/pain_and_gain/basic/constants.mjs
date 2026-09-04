export const TICKS_LIMIT = 2000;
export const MAX_SCORE_PER_TICK = 25;
export const FLAG_TYPES = {
  vulnerability: { scorePerTick: 5, effectType: 'eff_damage_taken_modifier' },
  healReduction: { scorePerTick: 4, effectType: 'eff_heal_modifier' },
  attackReduction: { scorePerTick: 3, effectType: 'eff_attack_modifier' },
  rangedAttackReduction: { scorePerTick: 3, effectType: 'eff_ranged_attack_modifier' },
};
