import { world, terrainAt, range } from './prototypes/_world.mjs';
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
export function createConstructionSite(){ return {error:-10}; }
