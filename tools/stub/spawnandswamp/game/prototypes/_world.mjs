import * as C from '../constants.mjs';
import { searchPath as _searchPath } from '../path-finder.mjs';
export const world = { tick: 0, objects: [], terrain: new Uint8Array(10000), intents: [], nextId: 1 };
export function terrainAt(x,y){ if(x<0||y<0||x>99||y>99) return C.TERRAIN_WALL; return world.terrain[x*100+y]; }
export function range(a,b){ return Math.max(Math.abs(a.x-b.x), Math.abs(a.y-b.y)); }
export class Store { constructor(cap){ this._cap=cap; this.energy=0; }
  getCapacity(){ return this._cap; } getUsedCapacity(){ return this.energy; } getFreeCapacity(){ return this._cap-this.energy; } }
export class GameObject { constructor(x,y){ this.x=x; this.y=y; this.id=world.nextId++; this.exists=true; this.ticksToDecay=undefined; world.objects.push(this); }
  getRangeTo(p){ return range(this,p); }
  findClosestByRange(arr){ let best=null,bd=1e9; for(const o of arr){ const d=range(this,o); if(d<bd){bd=d;best=o;} } return best; }
  findClosestByPath(arr){ return this.findClosestByRange(arr); }
  findInRange(arr,r){ return arr.filter(o=>range(this,o)<=r); } }
export class Structure extends GameObject { constructor(x,y,hits){ super(x,y); this.hits=hits; this.hitsMax=hits; } }
export class OwnedStructure extends Structure { constructor(x,y,hits,my){ super(x,y,hits); this.my=my; } }
export class StructureSpawn extends OwnedStructure { constructor(x,y,my,energy){ super(x,y,3000,my); this.store=new Store(1000); this.store.energy=energy; this.spawning=null; this.directions=[1,2,3,4,5,6,7,8]; }
  setDirections(){ return 0; }
  spawnCreep(body){ if(this.spawning) return {error:C.ERR_BUSY}; const cost=body.reduce((s,p)=>s+C.BODYPART_COST[p],0);
    if(cost>this.store.energy) return {error:C.ERR_NOT_ENOUGH_ENERGY}; this.store.energy-=cost;
    const c=new Creep(this.x,this.y,this.my,body); c.spawning=true; this.spawning={needTime:body.length*3, remainingTime:body.length*3, creep:c, cancel(){return 0;}}; return {object:c}; } }
export class StructureContainer extends OwnedStructure { constructor(x,y,energy,decay){ super(x,y,300,undefined); this.store=new Store(2000); this.store.energy=energy; this.ticksToDecay=decay; } }
export class StructureExtension extends OwnedStructure {}
export class StructureTower extends OwnedStructure { constructor(x,y,my){ super(x,y,C.TOWER_HITS,my); this.store=new Store(C.TOWER_CAPACITY); this.cooldown=0; }
  attack(t){ if(this.cooldown>0) return C.ERR_TIRED; if(this.store.energy<C.TOWER_ENERGY_COST) return C.ERR_NOT_ENOUGH_ENERGY; const r=range(this,t); if(r>C.TOWER_RANGE) return C.ERR_NOT_IN_RANGE;
    t.hits-=Math.max(0, C.TOWER_POWER_ATTACK*(1-C.TOWER_FALLOFF*Math.max(0,r-C.TOWER_OPTIMAL_RANGE)/(C.TOWER_FALLOFF_RANGE-C.TOWER_OPTIMAL_RANGE))); this.store.energy-=C.TOWER_ENERGY_COST; this.cooldown=C.TOWER_COOLDOWN; return 0; } }
export class StructureWall extends Structure {} export class StructureRampart extends OwnedStructure {} export class StructureRoad extends Structure {}
export class ConstructionSite extends GameObject { constructor(x,y,my,total){ super(x,y); this.my=my; this.progress=0; this.progressTotal=total; this.structure=undefined; } } export class Flag extends GameObject {}
export class Source extends GameObject { constructor(x,y){ super(x,y); this.energy=1000; this.energyCapacity=1000; } }
export class Resource extends GameObject { constructor(x,y,amount){ super(x,y); this.amount=amount; this.resourceType='energy'; } }
export class Creep extends GameObject { constructor(x,y,my,body){ super(x,y); this.my=my; this.body=body.map(t=>({type:t,hits:100})); this.hits=body.length*100; this.hitsMax=this.hits;
    this.fatigue=0; this.spawning=false; this.store=new Store(body.filter(p=>p===C.CARRY).length*50); }
  parts(t){ return this.body.filter(p=>p.type===t&&p.hits>0).length; }
  move(dir){ if(this.fatigue>0) return -11; const d=[[0,0],[0,-1],[1,-1],[1,0],[1,1],[0,1],[-1,1],[-1,0],[-1,-1]][dir]; world.intents.push({creep:this,x:this.x+d[0],y:this.y+d[1]}); return 0; }
  moveTo(t){ const r=_searchPath(this,{pos:t,range:1}); const s=r.path[0]; if(s) world.intents.push({creep:this,x:s.x,y:s.y}); return 0; }
  attack(t){ if(range(this,t)>1) return C.ERR_NOT_IN_RANGE; t.hits-=30*this.parts(C.ATTACK); return 0; }
  rangedAttack(t){ if(range(this,t)>3) return C.ERR_NOT_IN_RANGE; t.hits-=10*this.parts(C.RANGED_ATTACK); return 0; }
  rangedMassAttack(){ for(const o of world.objects){ if(o.exists && o!==this && o.hits!==undefined && o.my!==this.my && range(this,o)<=3) o.hits-=10*this.parts(C.RANGED_ATTACK)*[1,1,0.4,0.1][range(this,o)]; } return 0; }
  heal(t){ if(range(this,t)>1) return C.ERR_NOT_IN_RANGE; t.hits=Math.min(t.hitsMax,t.hits+12*this.parts(C.HEAL)); return 0; }
  rangedHeal(t){ if(range(this,t)>3) return C.ERR_NOT_IN_RANGE; t.hits=Math.min(t.hitsMax,t.hits+4*this.parts(C.HEAL)); return 0; }
  withdraw(t){ if(range(this,t)>1) return C.ERR_NOT_IN_RANGE; const a=Math.min(this.store.getFreeCapacity(), t.store.energy); t.store.energy-=a; this.store.energy+=a; return a>0?0:C.ERR_NOT_ENOUGH_RESOURCES; }
  transfer(t){ if(range(this,t)>1) return C.ERR_NOT_IN_RANGE; const a=Math.min(this.store.energy, t.store.getFreeCapacity()); t.store.energy-=0; t.store.energy+=a; this.store.energy-=a; return a>0?0:C.ERR_FULL; }
  pickup(t){ if(range(this,t)>1) return C.ERR_NOT_IN_RANGE; const a=Math.min(this.store.getFreeCapacity(), t.amount); t.amount-=a; this.store.energy+=a; if(t.amount<=0) t.exists=false; return 0; }
  harvest(){ return 0; } build(){ return 0; } drop(){ return 0; } pull(){ return 0; } }
export function endTick(){
  // спавн: доводим рождение, ставим крипа на свободную соседнюю клетку
  for(const o of world.objects){ if(o instanceof StructureSpawn && o.spawning){ o.spawning.remainingTime--; if(o.spawning.remainingTime<=0){ const c=o.spawning.creep;
      outer: for(let dx=-1;dx<=1;dx++) for(let dy=-1;dy<=1;dy++){ if(dx==0&&dy==0) continue; const x=o.x+dx,y=o.y+dy; if(terrainAt(x,y)===C.TERRAIN_WALL) continue;
        if(world.objects.some(q=>q.exists&&q!==c&&q.x===x&&q.y===y&&(q instanceof Creep||q instanceof StructureSpawn))) continue; c.x=x;c.y=y; break outer; }
      c.spawning=false; o.spawning=null; } } }
  // движение одновременное, как в движке: ход разрешён, если клетка свободна или её занимающий сам уходит
  // (в том числе обмен местами и цепочки); на одну клетку — первый по списку
  const movers=new Map();
  for(const i of world.intents){ if(!i.creep.exists||i.creep.spawning||i.creep.fatigue>0) continue; if(terrainAt(i.x,i.y)===C.TERRAIN_WALL) continue; if(!movers.has(i.creep)) movers.set(i.creep,{x:i.x,y:i.y}); }
  const byCell=new Map(); for(const o of world.objects){ if(o.exists&&(o instanceof Creep&&!o.spawning||o instanceof StructureSpawn)) byCell.set(o.x*100+o.y,o); }
  const targetTaken=new Set();
  for(const [c,t] of [...movers]){ const k=t.x*100+t.y; if(targetTaken.has(k)) movers.delete(c); else targetTaken.add(k); }
  let changed=true;
  while(changed){ changed=false; for(const [c,t] of [...movers]){ const occ=byCell.get(t.x*100+t.y); if(occ && occ!==c && !(occ instanceof Creep && movers.has(occ))){ movers.delete(c); changed=true; } } }
  for(const [c,t] of movers){ if(c.fatigue>0) continue; c.x=t.x; c.y=t.y;
    // усталость как в движке (movement.js:237): вес — части кроме MOVE и CARRY ПО ТИПУ (мёртвые весят)
    // плюс гружёные CARRY с хвоста (по 50); снимают её только живые MOVE (tick.js:105)
    const used=Math.ceil((c.store.energy||0)/50); let n=0;
    for(const p of c.body){ if(p.type===C.MOVE||p.type===C.CARRY) continue; n++; }
    n+=Math.min(used, c.body.filter(p=>p.type===C.CARRY).length);
    c.fatigue += n * (terrainAt(c.x,c.y)===2 ? 10 : 2); }
  for(const o of world.objects){ if(o instanceof Creep){ o.fatigue=Math.max(0, o.fatigue - 2*o.parts(C.MOVE)); } }
  for(const o of world.objects){ if(o instanceof StructureTower && o.cooldown>0) o.cooldown--; }
  world.intents=[];
  // урон снимает части спереди: пересчитываем hits частей от общего hits
  for(const o of world.objects){ if(o instanceof Creep && o.hits<o.hitsMax){ const n=o.body.length; for(let i=0;i<n;i++){ o.body[i].hits=Math.max(0,Math.min(100,o.hits-100*(n-1-i))); } } }
  for(const o of world.objects){ if(o.hits!==undefined&&o.hits<=0) o.exists=false; if(o.ticksToDecay!==undefined){ o.ticksToDecay--; if(o.ticksToDecay<=0) o.exists=false; } }
  world.objects=world.objects.filter(o=>o.exists);
  world.tick++;
}
