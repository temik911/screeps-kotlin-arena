import { world } from '../world.mjs';

export const arenaInfo = {
  name: 'Pain and Gain',
  season: 'season_4',
  level: 1,
  get ticksLimit() { return world.ticksLimit; },
  // единицы движка — наносекунды (живой arenaInfo: 100000000/1000000000); прежние 50/1000 бот делил на 1e6 и получал
  // лимит 0,00005 мс — бюджет перебора командира (v262) на стенде резал всё (pain-and-gain v262)
  cpuTimeLimit: 100000000,
  cpuTimeLimitFirstTick: 1000000000,
};
