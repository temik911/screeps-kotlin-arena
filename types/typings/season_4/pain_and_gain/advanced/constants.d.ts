declare module "arena/season_4/pain_and_gain/advanced/constants" {
    import {
        EFF_ATTACK_MODIFIER,
        EFF_DAMAGE_TAKEN_MODIFIER,
        EFF_FATIGUE_MODIFIER,
        EFF_HEAL_MODIFIER,
        EFF_HITS_LOSS,
        EFF_RANGED_ATTACK_MODIFIER
    } from "game/constants";

    export const TICKS_LIMIT = 5000;
    export const MAX_SCORE_PER_TICK = 43;
    export type ScoreFlagEffectType =
        typeof EFF_ATTACK_MODIFIER |
        typeof EFF_DAMAGE_TAKEN_MODIFIER |
        typeof EFF_FATIGUE_MODIFIER |
        typeof EFF_HEAL_MODIFIER |
        typeof EFF_RANGED_ATTACK_MODIFIER |
        typeof EFF_HITS_LOSS;
    export const FLAG_TYPES: Record<string, {
        readonly scorePerTick: number;
        readonly effectType: ScoreFlagEffectType;
    }>;
}
