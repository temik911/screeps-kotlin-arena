// ticksLimit — из окружения: с укороченным лимитом тот же мир проверяет решения, у которых есть СРОК
// (выход волны, удержание кромки), не подгоняя под них противника
export const arenaInfo={ name:'Spawn and Swamp', season:'season_4', level:1, ticksLimit:Number(process.env.TICKS_LIMIT||2000), cpuTimeLimit:50, cpuTimeLimitFirstTick:1000 };
