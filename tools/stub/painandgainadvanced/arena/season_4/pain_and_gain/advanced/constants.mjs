// arena/season_4/pain_and_gain/advanced/constants, as the live runtime printed them (27.09.2026, v1 probe)
export const TICKS_LIMIT = 5000;
export const MAX_SCORE_PER_TICK = 43;
export const FLAG_TYPES = {
  vulnerability: { scorePerTick: 5, effectType: 'eff_damage_taken_modifier' },
  heal_reduction: { scorePerTick: 4, effectType: 'eff_heal_modifier' },
  attack_reduction: { scorePerTick: 3, effectType: 'eff_attack_modifier' },
  ranged_attack_reduction: { scorePerTick: 3, effectType: 'eff_ranged_attack_modifier' },
  hits_loss: { scorePerTick: 4, effectType: 'eff_hits_loss' },
  fatigue_multiplier: { scorePerTick: 5, effectType: 'eff_fatigue_modifier' },
};
