import { world, terrainAt, range, ConstructionSite, Creep, StructureSpawn, StructureTower, StructureExtension, StructureWall } from './prototypes/_world.mjs';
import * as C from './constants.mjs';
export function getObjectsByPrototype(proto){ return world.objects.filter(o=>o.exists && o instanceof proto); }
export function getObjects(){ return world.objects.filter(o=>o.exists); }
export function getObjectById(id){ return world.objects.find(o=>o.id===id); }
export function getTicks(){ return world.tick; }
export function getCpuTime(){ return 0; }
export function getHeapStatistics(){ return {}; }
export function getRange(a,b){ return range(a,b); }
export function getTerrainAt(p){ return terrainAt(p.x,p.y); }
export function getDirection(dx,dy){ const m={'0,-1':1,'1,-1':2,'1,0':3,'1,1':4,'0,1':5,'-1,1':6,'-1,0':7,'-1,-1':8}; return m[Math.sign(dx)+','+Math.sign(dy)] ?? 0; }
export function findClosestByRange(from,arr){ let best=null,bd=1e9; for(const o of arr){ const d=range(from,o); if(d<bd){bd=d;best=o;} } return best; }
export function findClosestByPath(from,arr){ return findClosestByRange(from,arr); }
export function findInRange(from,arr,r){ return arr.filter(o=>range(from,o)<=r); }
export function findPath(from,to){ return [{x:from.x+Math.sign(to.x-from.x), y:from.y+Math.sign(to.y-from.y)}]; }
/** Площадка на клетке: как в движке — не на стене, не на занятой структурой/крипом клетке, не поверх другой площадки.
 *  Стоимость дороги на болоте выше в CONSTRUCTION_COST_ROAD_SWAMP_RATIO раз. */
export function createConstructionSite(a, b, c){
  const pos = (c===undefined) ? a : {x:a, y:b}; const proto = (c===undefined) ? b : c;
  if(!proto || !proto.name) return {error:C.ERR_INVALID_ARGS};
  const x=pos.x, y=pos.y; if(x<1||y<1||x>98||y>98) return {error:C.ERR_INVALID_ARGS};
  if(terrainAt(x,y)===C.TERRAIN_WALL) return {error:C.ERR_INVALID_TARGET};
  let total = C.CONSTRUCTION_COST[proto.name]; if(!total) return {error:C.ERR_INVALID_ARGS};
  if(proto.name==='StructureRoad' && terrainAt(x,y)===C.TERRAIN_SWAMP) total *= C.CONSTRUCTION_COST_ROAD_SWAMP_RATIO;
  const busy = world.objects.some(o=>o.exists && o.x===x && o.y===y && (o instanceof ConstructionSite || o instanceof StructureSpawn || o instanceof StructureTower || o instanceof StructureExtension || o instanceof StructureWall || o instanceof Creep));
  if(busy) return {error:C.ERR_INVALID_TARGET};
  if(world.objects.filter(o=>o.exists && o instanceof ConstructionSite && o.my===true).length >= C.MAX_CONSTRUCTION_SITES) return {error:C.ERR_FULL};
  return {object:new ConstructionSite(x, y, true, total, proto)};
}
