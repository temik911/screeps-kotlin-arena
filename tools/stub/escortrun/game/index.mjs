import { world } from '../world.mjs';

export const arenaInfo = {
  name: 'Escort Run',
  season: 'season_4',
  level: 1,
  get ticksLimit() { return world.ticksLimit; },
  cpuTimeLimit: 50,
  cpuTimeLimitFirstTick: 1000,
};
