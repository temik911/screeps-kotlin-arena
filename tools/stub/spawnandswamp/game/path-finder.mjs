import { terrainAt, range } from './prototypes/_world.mjs';
export class CostMatrix { constructor(){ this._d=new Uint8Array(10000);} get(x,y){return this._d[x*100+y];} set(x,y,c){this._d[x*100+y]=c;} clone(){const m=new CostMatrix(); m._d=this._d.slice(); return m;} }
function cellCost(x,y,cm,plain,swamp){ const t=terrainAt(x,y); if(t===1) return Infinity; const c=cm?cm.get(x,y):0; if(c>=255) return Infinity; if(c>0) return c; return t===2?swamp:plain; }
export function searchPath(origin, goal, opts){
  const goals=Array.isArray(goal)?goal:[goal]; const flee=!!(opts&&opts.flee); const cm=opts&&opts.costMatrix; const plain=(opts&&opts.plainCost)||2, swamp=(opts&&opts.swampCost)||10;
  const inGoal=(x,y)=>goals.some(g=>range({x,y},g.pos)<=(g.range??0));
  if(!flee && inGoal(origin.x,origin.y)) return {path:[],ops:0,cost:0,incomplete:false};
  const dist=new Float64Array(10000).fill(Infinity); const prev=new Int32Array(10000).fill(-1); const start=origin.x*100+origin.y; dist[start]=0;
  // простая очередь с приоритетом через корзины (цены небольшие целые)
  const buckets=new Map(); const push=(d,i)=>{ const k=Math.floor(d); if(!buckets.has(k)) buckets.set(k,[]); buckets.get(k).push(i); };
  push(0,start); let found=-1; let ops=0;
  const keys=()=>[...buckets.keys()].sort((a,b)=>a-b);
  outer: while(buckets.size){ const k=keys()[0]; const q=buckets.get(k); buckets.delete(k);
    for(const i of q){ if(dist[i]<k) continue; const x=Math.floor(i/100), y=i%100; ops++;
      const done = flee ? !inGoal(x,y) : inGoal(x,y);
      if(done && i!==start){ found=i; break outer; }
      if(done && i===start && flee){ found=i; break outer; }
      for(let dx=-1;dx<=1;dx++) for(let dy=-1;dy<=1;dy++){ if(!dx&&!dy) continue; const nx=x+dx, ny=y+dy; if(nx<0||ny<0||nx>99||ny>99) continue;
        const c=cellCost(nx,ny,cm,plain,swamp); if(c===Infinity) continue; const ni=nx*100+ny; const nd=dist[i]+c; if(nd<dist[ni]){ dist[ni]=nd; prev[ni]=i; push(nd,ni); } } }
    if(ops>50000) break; }
  if(found<0||found===start) return {path:[],ops,cost:0,incomplete:true};
  const path=[]; let cur=found; while(cur!==start){ path.push({x:Math.floor(cur/100), y:cur%100}); cur=prev[cur]; } path.reverse();
  return {path,ops,cost:dist[found],incomplete:false};
}
