import { world } from '../world.mjs';

// the values the live arena answers — the greeting of v58 (29.09.2026): "arena: name=Spawn and Swamp level=2 season=4
// ticksLimit=5000 cpu=100000000 cpuFirst=1000000000" (the CPU limits are nanoseconds there)
export const arenaInfo = {
  name: 'Spawn and Swamp',
  season: '4',
  level: 2,
  get ticksLimit() { return world.ticksLimit; },
  cpuTimeLimit: 100000000,
  cpuTimeLimitFirstTick: 1000000000,
};
