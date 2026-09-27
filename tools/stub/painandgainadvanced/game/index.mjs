import { world } from '../world.mjs';

// the live greeting of the advanced arena (27.09.2026): "4 - Pain and Gain level=2 ticksLimit=5000"
export const arenaInfo = {
  name: 'Pain and Gain',
  season: '4',
  level: 2,
  get ticksLimit() { return world.ticksLimit; },
  // engine units are nanoseconds (live arenaInfo: 100000000/1000000000)
  cpuTimeLimit: 100000000,
  cpuTimeLimitFirstTick: 1000000000,
};
