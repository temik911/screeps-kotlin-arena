import * as C from 'game/constants';
import { world, endTick, StructureSpawn, StructureContainer, StructureWall, Creep, Resource, range, terrainAt } from 'game/prototypes';
import { readFileSync } from 'node:fs';
import { loop } from '../../../build/js/packages/screeps-kotlin-arena-starter/kotlin/screeps-kotlin-arena-starter/season4/spawnandswamp/SpawnAndSwamp.export.mjs';
// живая карта матча 5 (02.09.2026): наш спавн (5,50), враг (94,49)
const rows=readFileSync(new URL('./map5.txt', import.meta.url),'utf8').split('\n').filter(l=>l.length===100);
for(let y=0;y<100;y++) for(let x=0;x<100;x++){ const ch=rows[y][x]; world.terrain[x*100+y]= ch==='#'?1 : ch==='~'?2 : 0; }
world.terrain[13*100+49]=0; world.terrain[87*100+50]=0; // структурные стены пролома стоят на равнине
const my=new StructureSpawn(5,50,true,1000); const en=new StructureSpawn(94,49,false,1000);
new StructureContainer(14,49,5000); new StructureWall(13,49,10000);
new StructureContainer(86,50,5000); new StructureWall(87,50,10000);
for(const [x,y] of [[1,1],[1,98],[98,1],[98,98]]) new StructureContainer(x,y,2500);
const TICKS=parseInt(process.argv[2]||'900'); const SCEN=process.argv[3]||'freeze';
let seed=7; const rnd=()=>{ seed=(seed*1103515245+12345)&0x7fffffff; return seed/0x7fffffff; };
function freeCell(){ for(let i=0;i<100;i++){ const x=2+Math.floor(rnd()*96), y=2+Math.floor(rnd()*96); if(world.terrain[x*100+y]!==1) return [x,y]; } return [50,50]; }
const guards=[];
function guard(x,y,body){ const g=new Creep(x,y,false,body||[C.MOVE,C.MOVE,C.MOVE,C.RANGED_ATTACK,C.RANGED_ATTACK,C.RANGED_ATTACK]); g.guard=true; guards.push(g); return g; }
function mine(x,y,body,energy){ const c=new Creep(x,y,true,body); if(energy) c.store.energy=energy; return c; }
const watch=[];
if(SCEN==='freeze'){ guard(14,89); guard(84,90); }
// CAMPED19: состояние, в котором проигран матч 19 (05.09.2026) — пять стрелков врага вплотную к нашему
// спавну, своих крипов нет, в спавне 900 энергии и притока нет. Проверяется одно: строит ли спавн
// хоть что-то, пока его сносят, или копит на полное тело до самой смерти
if(SCEN==='camped19'){
  const R3=[C.MOVE,C.MOVE,C.MOVE,C.RANGED_ATTACK,C.RANGED_ATTACK,C.RANGED_ATTACK];
  // двое: 60 урона в тик, спавну жить ~50 тиков — тело из наличных 900 (39 тиков) успевает родиться,
  // а ожидание последней сотни при потоке 1/тик (100 тиков) — нет
  for(const [x,y] of [[8,50],[8,52]]){ const e=new Creep(x,y,false,R3); e.camper=true; }
  my.store.energy=900;
}
// RUSH: повтор матча 14 на живой карте — два M5R1 с первого тика через спавн врага, третий на 200-м; идут к нашему спавну,
// встают в трёх клетках и не кайтят: бьют самого битого нашего в трёх клетках, иначе спавн
const M5R1=[C.MOVE,C.MOVE,C.MOVE,C.MOVE,C.MOVE,C.RANGED_ATTACK]; let rushQueue = SCEN==='rush' ? [M5R1,M5R1] : SCEN==='stream17' ? [M5R1] : [];
// STREAM17: повтор матча 17 (04.09.2026) — один M5R1 с первого тика (лагерник), затем по расписанию матча M2R2 (205), M4H2 (285),
// M3R3 (360, 440, 580), M4H2 (665), M3R3 (745, 825), дальше попеременно каждые 80 тиков; враг кормится десятью M1C1 (+6/тик).
// Ходят ПАРАМИ (сбор у своего спавна), по правилам HEALBALL: держат 6 клеток от нашего спавна, стреляют по ближайшему нашему
// в 3, отходят от бойца в двух, лечат самого битого, гоняют хаулеров в восьми клетках
const M2R2=[C.MOVE,C.MOVE,C.RANGED_ATTACK,C.RANGED_ATTACK], M3R3=[C.MOVE,C.MOVE,C.MOVE,C.RANGED_ATTACK,C.RANGED_ATTACK,C.RANGED_ATTACK], M4H2=[C.MOVE,C.MOVE,C.MOVE,C.MOVE,C.HEAL,C.HEAL];
const sched17=[[205,M2R2],[285,M4H2],[360,M3R3],[440,M3R3],[580,M3R3],[665,M4H2],[745,M3R3],[825,M3R3]];
let s17Queue=[]; let s17Count=0;
if(SCEN==='jam'){
  const f11=mine(14,89,[C.MOVE,C.MOVE,C.MOVE,C.MOVE,C.MOVE,C.MOVE,C.ATTACK,C.ATTACK,C.ATTACK,C.ATTACK,C.ATTACK,C.ATTACK]); f11.hits=510; for(let i=0;i<12;i++) f11.body[i].hits=Math.max(0,Math.min(100,510-100*(11-i)));
  const R5=[C.MOVE,C.RANGED_ATTACK,C.MOVE,C.RANGED_ATTACK,C.MOVE,C.RANGED_ATTACK,C.MOVE,C.RANGED_ATTACK,C.MOVE,C.RANGED_ATTACK];
  const H5=[C.CARRY,C.CARRY,C.CARRY,C.CARRY,C.CARRY,C.MOVE,C.MOVE,C.MOVE,C.MOVE,C.MOVE];
  watch.push(f11, mine(11,87,R5), mine(10,86,R5), mine(14,90,H5,250), mine(13,89,H5,250), mine(12,88,H5,250), mine(12,89,H5,250));
  guard(18,90); guard(17,89);
  my.store.energy=300;
}
let errors=0; const origLog=console.log; const lines=[]; console.log=(...a)=>{ const s=a.join(' '); lines.push(s); if(/loop error|Error|exception/i.test(s)) errors++; };
for(let t=0;t<TICKS;t++){
  if(t>0 && t%50===0){ const [x1,y1]=freeCell(); const [x2,y2]=freeCell(); new StructureContainer(x1,y1,2000,99); new StructureContainer(x2,y2,2000,99); }
  if(SCEN==='rush' && t===200) rushQueue.push(M5R1);
  if(rushQueue.length && !en.spawning && t>=1){ const r=en.spawnCreep(rushQueue[0]); if(r.object){ r.object.camper=true; rushQueue.shift(); } }
  if(SCEN==='stream17'){ for(const [st,b] of sched17) if(t===st) s17Queue.push(b); if(t>825 && (t-825)%80===0) s17Queue.push(((t-825)/80)%2===1?M4H2:M3R3); }
  if(SCEN==='stream17' && s17Queue.length && !en.spawning){ const r=en.spawnCreep(s17Queue[0]); if(r.object){ r.object.hb=Math.floor(s17Count/2); s17Count++; s17Queue.shift(); } }
  loop();
  for(const o of world.objects){ if(!(o instanceof Creep) || o.my || !o.camper || !o.exists || o.spawning) continue;
    const R=(a,b)=>Math.max(Math.abs(a.x-b.x),Math.abs(a.y-b.y));
    const near=world.objects.filter(q=>q.exists&&q.my===true&&q instanceof Creep&&!q.spawning&&R(q,o)<=3).sort((a,b)=>a.hits-b.hits);
    if(near.length) o.rangedAttack(near[0]); else if(R(o,my)<=3) o.rangedAttack(my);
    if(R(o,my)>3) o.moveTo(my); }
  if(SCEN==='stream17'){ const mineAll=world.objects.filter(q=>q.exists&&q.my===true&&q instanceof Creep&&!q.spawning);
    const fighters=mineAll.filter(c=>c.body.some(p=>(p.type===C.RANGED_ATTACK||p.type===C.ATTACK)&&p.hits>0));
    const members=world.objects.filter(q=>q instanceof Creep&&!q.my&&q.hb!==undefined&&q.exists&&!q.spawning);
    const R=(a,b)=>Math.max(Math.abs(a.x-b.x),Math.abs(a.y-b.y));
    for(const o of members){ const grp=members.filter(m=>m.hb===o.hb); const complete=grp.length>=2 || o.hb<Math.floor(s17Count/2);
      if(o.parts(C.HEAL)>0){ const hurt=grp.filter(m=>m.hits<m.hitsMax&&R(m,o)<=3).sort((a,b)=>(a.hitsMax-a.hits)-(b.hitsMax-b.hits)).reverse()[0];
        if(hurt){ if(R(hurt,o)<=1) o.heal(hurt); else o.rangedHeal(hurt); } }
      if(o.parts(C.RANGED_ATTACK)>0){ const inR=mineAll.filter(c=>R(c,o)<=3); if(inR.length){ if(inR.filter(c=>R(c,o)<=1).length>=2) o.rangedMassAttack(); else o.rangedAttack(inR.sort((a,b)=>a.hits-b.hits)[0]); } else if(R(o,my)<=3) o.rangedAttack(my); }
      const rally={x:90,y:45};
      if(!complete){ if(R(o,rally)>2) o.moveTo(rally); continue; }
      const leader=grp.slice().sort((a,b)=>a.id-b.id)[0];
      const near=fighters.filter(f=>R(f,o)<=4).sort((a,b)=>R(a,o)-R(b,o));
      if(near.length && R(near[0],o)<=2){ let best=null,bd=-1; for(let dx=-1;dx<=1;dx++) for(let dy=-1;dy<=1;dy++){ const nx=o.x+dx,ny=o.y+dy; if(terrainAt(nx,ny)===C.TERRAIN_WALL) continue;
          if(world.objects.some(q=>q.exists&&q!==o&&q.x===nx&&q.y===ny&&(q instanceof Creep||q instanceof StructureSpawn||q instanceof StructureWall))) continue;
          const d=Math.min(...near.map(f=>R({x:nx,y:ny},f))); if(d>bd){bd=d;best={x:nx,y:ny};} }
        if(best&&(best.x!==o.x||best.y!==o.y)) world.intents.push({creep:o,x:best.x,y:best.y}); continue; }
      if(o!==leader && R(o,leader)>1){ o.moveTo(leader); continue; }
      if(near.length){ if(R(near[0],o)>=4) o.moveTo(near[0]); continue; }
      const haul=mineAll.filter(c=>c.body.some(p=>p.type===C.CARRY)&&R(c,o)<=8).sort((a,b)=>R(a,o)-R(b,o))[0];
      if(haul){ if(R(haul,o)>3) o.moveTo(haul); continue; }
      if(R(o,my)>6) o.moveTo(my); } }
  if((SCEN==='rush'||SCEN==='stream17') && t%50===0) lines.push('ENEMIES t='+t+': '+world.objects.filter(o=>o instanceof Creep&&!o.my&&o.exists).map(c=>'('+c.x+','+c.y+')'+c.body.map(p=>p.type[0]).join('')+'h='+c.hits+(c.spawning?'s':'')).join(' ')+' | mySpawn h='+my.hits+' e='+my.store.energy);
  for(const g of guards){ if(!g.exists) continue; const tg=world.objects.filter(q=>q.exists&&q.my===true&&(q instanceof Creep&&!q.spawning)&&Math.max(Math.abs(q.x-g.x),Math.abs(q.y-g.y))<=3);
    if(tg.length>1) g.rangedMassAttack(); else if(tg.length===1) g.rangedAttack(tg[0]); }
  if(SCEN==='jam' && t%5===0) lines.push('JAM t='+t+': '+watch.map(c=>(c.exists?'('+c.x+','+c.y+')f='+c.fatigue+'h='+c.hits:'dead')).join(' '));
  my.store.energy=Math.min(1000,my.store.energy+1); en.store.energy=Math.min(1000,en.store.energy+(SCEN==='stream17'?7:1));
  endTick();
  if(!my.exists){ origLog('MY SPAWN DESTROYED at',t); break; }
  if(!en.exists){ origLog('ENEMY SPAWN DESTROYED at',t); break; }
}
console.log=origLog;
const skip=/^\d\d:|=== MAP|=== END MAP|^  h\d/;
for(const l of lines) if(!skip.test(l)) origLog(l);
origLog('--- ticks run:', world.tick, 'errors:', errors, 'my creeps:', world.objects.filter(o=>o instanceof Creep&&o.my).map(c=>c.body.map(p=>p.type[0]).join('')).join(' '), 'spawnE:', my.store.energy, 'enemySpawnHits:', en.hits, 'guards:', guards.map(g=>g.exists?'('+g.x+','+g.y+')h='+g.hits:'dead').join(' '));
