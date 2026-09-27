import { world } from '../world.mjs';

// the values the live arena answers (the greeting of v1-v4, 27.09.2026: "4 - Escort Run level=2 ticksLimit=5000
// cpu=100000000/1000000000") — the CPU limits are nanoseconds there
export const arenaInfo = {
  name: 'Escort Run',
  season: '4',
  level: 2,
  get ticksLimit() { return world.ticksLimit; },
  cpuTimeLimit: 100000000,
  cpuTimeLimitFirstTick: 1000000000,
};
