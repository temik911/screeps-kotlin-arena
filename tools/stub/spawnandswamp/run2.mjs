import * as C from 'game/constants';
import { world, endTick, StructureSpawn, StructureContainer, StructureWall, StructureTower, ConstructionSite, Creep, Resource, range, terrainAt } from 'game/prototypes';
import { loop } from '../../../build/js/packages/screeps-kotlin-arena-starter/kotlin/screeps-kotlin-arena-starter/season4/spawnandswamp/SpawnAndSwamp.export.mjs';
// мир как в живом матче: рамка стен, треть болота, наш спавн справа (94,49) в кармане за стенным блоком x=81..86 (y=20..83),
// выходы из кармана сверху (y<=10) и снизу (y>=89); угловые контейнеры 2500; временные 2000/99 тиков каждые 50 тиков
let seed=7; const rnd=()=>{ seed=(seed*1103515245+12345)&0x7fffffff; return seed/0x7fffffff; };
for(let x=0;x<100;x++) for(let y=0;y<100;y++){ const r=rnd(); world.terrain[x*100+y]= (x==0||y==0||x==99||y==99)?1 : r<0.05?1 : r<0.40?2 : 0; }
for(let x=81;x<=86;x++) for(let y=20;y<=83;y++) world.terrain[x*100+y]=1;
for(let x=13;x<=18;x++) for(let y=20;y<=83;y++) world.terrain[x*100+y]=1;
for(const [sx,sy] of [[94,49],[5,50]]) for(let x=sx-6;x<=sx+6;x++) for(let y=sy-6;y<=sy+6;y++) if(x>0&&x<99&&y>0&&y<99) world.terrain[x*100+y]=0;
for(let y=1;y<99;y++){ world.terrain[98*100+y]=0; world.terrain[1*100+y]=0; } // бордюрные колонны — равнина, как в матче
const my=new StructureSpawn(94,49,true,1000); const en=new StructureSpawn(5,50,false,1000);
// WALLED: запертый контейнер у каждого спавна (как в живой карте), кольцо структурных стен по 10000
for(const [cx,cy] of [[88,49],[11,50]]){ new StructureContainer(cx,cy,5000); for(let dx=-1;dx<=1;dx++) for(let dy=-1;dy<=1;dy++){ if(dx||dy) new StructureWall(cx+dx,cy+dy,10000); } }
new StructureContainer(98,1,2500); new StructureContainer(98,98,2500); new StructureContainer(1,1,2500); new StructureContainer(1,98,2500);
const TICKS=parseInt(process.argv[2]||'900'); const MODES=(process.argv[3]||'none').split('+');
const ENEMY=MODES.includes('enemy'); const SWARM=MODES.includes('swarm'); const BALL=MODES.includes('ball'); const RAIDER=MODES.includes('raider'); const TOWER=MODES.includes('tower'); const HARASS=MODES.includes('harass'); const TOWERSITE=MODES.includes('towersite'); const HEALBALL=MODES.includes('healball'); const HOVER=MODES.includes('hover'); const RUSH=MODES.includes('rush'); const CAMP=MODES.includes('camp'); const STREAM=MODES.includes('stream'); const FORTRESS=MODES.includes('fortress');
// STREAM: противник матча 15 — с 280-го тика попеременно M3R3 и M4H2 каждые 40 тиков, каждый идёт к нашему спавну сразу,
// без сбора в четвёрки (правила движения и стрельбы — как у HEALBALL); подкрепление тянется потоком за первыми
// RUSH: противник матча 14 — два M5R1 с первого тика через свой спавн, третий на 200-м; идут к нашему спавну, встают в трёх
// клетках и НЕ кайтят: бьют самого битого нашего в трёх клетках, иначе спавн. CAMP: те же двое ставятся в трёх клетках от
// нашего спавна на 60-м тике (у нас в этот момент только бурильщик): проверка, что мили бьёт стоящего стрелка
let rushQueue = RUSH ? [[C.MOVE,C.MOVE,C.MOVE,C.MOVE,C.MOVE,C.RANGED_ATTACK],[C.MOVE,C.MOVE,C.MOVE,C.MOVE,C.MOVE,C.RANGED_ATTACK]] : [];
// HARASS: противник матча 12 — два M5R1 (полный ход по болоту) с первого тика через свой спавн (видны как spawning),
// третий M3R1 на 220-м; охотятся на хаулеров, кайтят от бойцов, без целей бьют наш спавн с трёх клеток
let harassQueue = HARASS ? [[C.MOVE,C.MOVE,C.MOVE,C.MOVE,C.MOVE,C.RANGED_ATTACK],[C.MOVE,C.MOVE,C.MOVE,C.MOVE,C.MOVE,C.RANGED_ATTACK]] : [];
// TOWER: башня врага вплотную к его спавну и четыре носильщика M1C1, берущие энергию из спавна врага (матч 11:
// одна кормленная башня положила четыре M8R4 двух волн, бот её не видел). Башня бьёт ближайшего нашего в 20 клетках.
// FORTRESS: ДВЕ кормленные башни у чужого спавна и восемь носильщиков — осада по симуляции долго
// «проиграна», и бот без срока выхода досиживает матч дома с растущей армией (ничья матча 18).
// Поток идёт до 1150-го и иссякает — как в матче 18: дальше враг не приходит, наша армия растёт дома,
// а осада по симуляции всё ещё «проиграна» из-за башен. Версия без срока выхода досиживает до лимита
const towers=[]; if(TOWER){ towers.push(new StructureTower(7,50,false)); }
if(FORTRESS){ towers.push(new StructureTower(7,50,false)); towers.push(new StructureTower(6,52,false)); towers.push(new StructureTower(6,48,false)); }
for(const t of towers) t.store.energy=C.TOWER_CAPACITY;
let tw=towers[0]||null;
// носильщики стоят там же, где стояли в TOWER (6,47..50) — сдвиг базы менял мир старых сценариев;
// крепости добавляются свои, ниже по столбцу
if(TOWER||TOWERSITE||FORTRESS){ for(let i=0;i<4;i++){ const f=new Creep(6,47+i,false,[C.MOVE,C.CARRY]); f.feeder=true; } }
if(FORTRESS){ for(let i=0;i<4;i++){ const f=new Creep(5,47+i,false,[C.MOVE,C.CARRY]); f.feeder=true; } }
// TOWERSITE: площадка башни у спавна врага со 150-го тика, строится по 5 в тик одним M1C1W1 (матч 13: волна ушла
// при 945/1250 и была отозвана, когда башня встала). Готовая башня — как в TOWER.
let site=null, builder=null; if(TOWERSITE){ builder=new Creep(6,50,false,[C.MOVE,C.CARRY,C.WORK]); builder.builder=true; }
// HEALBALL: противник матча 13 — с 280-го тика через свой спавн попеременно M3R3 и M4H2 каждые 60 тиков; четвёрками
// собираются у своего спавна и идут шаром к нашему: держат 6 клеток от спавна, стреляют по ближайшему нашему в 3,
// отходят от бойца в двух клетках, лечат самого битого, гоняют хаулеров в восьми клетках
let hbQueue=[]; let hbCount=0;
function hbBody(i){ return i%2===0 ? [C.MOVE,C.MOVE,C.MOVE,C.RANGED_ATTACK,C.RANGED_ATTACK,C.RANGED_ATTACK] : [C.MOVE,C.MOVE,C.MOVE,C.MOVE,C.HEAL,C.HEAL]; }
let errors=0; let loopTotal=0, loopMax=0; /* LOOPTIME */ const origLog=console.log; const lines=[]; console.log=(...a)=>{ const s=a.join(' '); lines.push(s); if(/loop error|Error|exception/i.test(s)) errors++; };
function freeCell(){ for(let i=0;i<100;i++){ const x=2+Math.floor(rnd()*96), y=2+Math.floor(rnd()*96); if(world.terrain[x*100+y]!==1) return [x,y]; } return [50,50]; }
for(let t=0;t<TICKS;t++){
  if(t>0 && t%50===0){ const [x1,y1]=freeCell(); const [x2,y2]=freeCell(); new StructureContainer(x1,y1,2000,99); new StructureContainer(x2,y2,2000,99); }
  if(ENEMY && t===150){ for(let i=0;i<2;i++){ const r=new Creep(6,50+i,false,[C.MOVE,C.MOVE,C.MOVE,C.RANGED_ATTACK,C.RANGED_ATTACK,C.HEAL]); r.rusher=true; } }
  // BALL: шар из пяти мили-лекарей [M×5,A,H] каждые 200 тиков идёт к нашему спавну (матч 9: третий противник)
  // RAIDER: одиночный M5A3 с 30-го тика идёт бить наш спавн (матч 10: бурильщик не подошёл вплотную)
  if(RAIDER && (t===30 || t===400)){ const r=new Creep(6,50,false,[C.MOVE,C.MOVE,C.MOVE,C.MOVE,C.MOVE,C.ATTACK,C.ATTACK,C.ATTACK]); r.baller=true; }
  if(BALL && t>=300 && (t-300)%200===0){ for(let i=0;i<5;i++){ const r=new Creep(6,48+i,false,[C.MOVE,C.MOVE,C.MOVE,C.MOVE,C.MOVE,C.ATTACK,C.HEAL]); r.baller=true; } }
  if(SWARM && t>=140 && (t-140)%45===0){ const r=new Creep(6,50+((t/45)|0)%3,false,[C.MOVE,C.RANGED_ATTACK]); r.rusher=true; } // SWARM
  if(HARASS && t===220) harassQueue.push([C.MOVE,C.MOVE,C.MOVE,C.RANGED_ATTACK]);
  if(RUSH && t===200) rushQueue.push([C.MOVE,C.MOVE,C.MOVE,C.MOVE,C.MOVE,C.RANGED_ATTACK]);
  if(RUSH && rushQueue.length && !en.spawning && t>=1){ const r=en.spawnCreep(rushQueue[0]); if(r.object){ r.object.camper=true; rushQueue.shift(); } }
  if(CAMP && t===60){ for(let i=0;i<2;i++){ const r=new Creep(93,47+4*i,false,[C.MOVE,C.MOVE,C.MOVE,C.MOVE,C.MOVE,C.RANGED_ATTACK]); r.camper=true; } }
  if(CAMP && t>=60 && t<=90){ lines.push('CAMP t='+t+': '+world.objects.filter(o=>o instanceof Creep&&!o.my&&o.exists).map(c=>'('+c.x+','+c.y+')h='+c.hits).join(' ')+' | mine: '+world.objects.filter(o=>o instanceof Creep&&o.my&&o.exists&&!o.spawning).map(c=>c.id+'('+c.x+','+c.y+')h='+c.hits).join(' ')); }
  if(TOWERSITE && t===150){ site=new ConstructionSite(7,50,false,C.CONSTRUCTION_COST.StructureTower); }
  if(site && site.exists){ if(builder&&builder.exists&&range(builder,site)<=3){ site.progress+=C.BUILD_POWER; } if(site.progress>=site.progressTotal){ site.exists=false; tw=new StructureTower(7,50,false); tw.store.energy=C.TOWER_CAPACITY; } }
  if(HEALBALL && t>=280 && (t-280)%60===0){ hbQueue.push(hbBody(hbCount)); }
  if(STREAM && t>=280 && (t-280)%40===0){ hbQueue.push(hbBody(hbCount)); }
  if(FORTRESS && t>=280 && t<=Number(process.env.FORTRESS_UNTIL||1150) && (t-280)%40===0){ hbQueue.push(hbBody(hbCount)); }
  // HOVER: шар матча 13 у наших ворот — четыре M3R3 и четыре M4H2 ставятся в восьми клетках от нашего спавна на 500-м тике
  // и ходят по правилам HEALBALL (6 клеток от спавна, отход от бойца в двух, выстрел по ближайшему в трёх, лечение)
  if(HOVER && t===500){ for(let i=0;i<8;i++){ const r=new Creep(86+(i%4),40+Math.floor(i/4),false,hbBody(i)); r.hb=99; } }
  if((HEALBALL||STREAM||FORTRESS) && hbQueue.length && !en.spawning){ const r=en.spawnCreep(hbQueue[0]); if(r.object){ r.object.hb=(STREAM||FORTRESS)?hbCount:Math.floor(hbCount/4); hbCount++; hbQueue.shift(); } }
  if(HARASS && harassQueue.length && !en.spawning && t>=1){ const r=en.spawnCreep(harassQueue[0]); if(r.object){ r.object.harasser=true; harassQueue.shift(); } }
  { const t0=performance.now(); loop(); const dt=performance.now()-t0; loopTotal+=dt; if(dt>loopMax) loopMax=dt; }
  if(HARASS){ const mine=world.objects.filter(q=>q.exists&&q.my===true&&q instanceof Creep&&!q.spawning);
    const fighters=mine.filter(c=>c.body.some(p=>(p.type===C.RANGED_ATTACK||p.type===C.ATTACK)&&p.hits>0));
    for(const o of world.objects){ if(!(o instanceof Creep) || o.my || !o.harasser || !o.exists || o.spawning) continue;
      const tgt=mine.filter(c=>range(c,o)<=3).sort((a,b)=>a.hits-b.hits)[0];
      if(tgt) o.rangedAttack(tgt); else if(range(o,my)<=3) o.rangedAttack(my);
      const near=fighters.filter(f=>range(f,o)<=4);
      if(near.length){ let best=null,bd=-1; for(let dx=-1;dx<=1;dx++) for(let dy=-1;dy<=1;dy++){ const nx=o.x+dx,ny=o.y+dy; if(terrainAt(nx,ny)===C.TERRAIN_WALL) continue;
          if(world.objects.some(q=>q.exists&&q!==o&&q.x===nx&&q.y===ny&&(q instanceof Creep||q instanceof StructureSpawn))) continue;
          const d=Math.min(...near.map(f=>range({x:nx,y:ny},f))); if(d>bd){bd=d;best={x:nx,y:ny};} }
        if(best&&(best.x!==o.x||best.y!==o.y)) world.intents.push({creep:o,x:best.x,y:best.y}); }
      else { const haul=mine.filter(c=>c.body.some(p=>p.type===C.CARRY)).sort((a,b)=>range(a,o)-range(b,o))[0];
        if(haul){ if(range(haul,o)>3) o.moveTo(haul); } else if(range(o,my)>3) o.moveTo(my); } } }
  { const live=towers.filter(t=>t&&t.exists);
    for(const t of live){
      if(t.cooldown===0 && t.store.energy>=C.TOWER_ENERGY_COST){ const tg=world.objects.filter(q=>q.exists&&q.my===true&&q instanceof Creep&&!q.spawning&&range(q,t)<=C.TOWER_RANGE).sort((a,b)=>range(a,t)-range(b,t))[0]; if(tg) t.attack(tg); } }
    if(live.length) for(const o of world.objects){ if(o instanceof Creep && !o.my && o.feeder && o.exists){
      const need=live.filter(t=>t.store.energy<C.TOWER_CAPACITY).sort((a,b)=>a.store.energy-b.store.energy)[0]||live[0];
      if(o.store.energy<C.TOWER_ENERGY_COST){ if(range(o,en)<=1){ const a=Math.min(o.store.getFreeCapacity(), en.store.energy); en.store.energy-=a; o.store.energy+=a; } else o.moveTo(en); }
      else { if(range(o,need)<=1){ if(need.store.energy<C.TOWER_CAPACITY) o.transfer(need); } else o.moveTo(need); } } } }
  if(HEALBALL||HOVER||STREAM||FORTRESS){ const mine=world.objects.filter(q=>q.exists&&q.my===true&&q instanceof Creep&&!q.spawning);
    const fighters=mine.filter(c=>c.body.some(p=>(p.type===C.RANGED_ATTACK||p.type===C.ATTACK)&&p.hits>0));
    const members=world.objects.filter(q=>q instanceof Creep&&!q.my&&q.hb!==undefined&&q.exists&&!q.spawning);
    const R=(a,b)=>Math.max(Math.abs(a.x-b.x),Math.abs(a.y-b.y));
    for(const o of members){ const grp=members.filter(m=>m.hb===o.hb); const complete=STREAM || FORTRESS || grp.length>=4 || o.hb<Math.floor(hbCount/4);
      if(o.parts(C.HEAL)>0){ const hurt=grp.filter(m=>m.hits<m.hitsMax&&R(m,o)<=3).sort((a,b)=>(a.hitsMax-a.hits)-(b.hitsMax-b.hits)).reverse()[0];
        if(hurt){ if(R(hurt,o)<=1) o.heal(hurt); else o.rangedHeal(hurt); } }
      if(o.parts(C.RANGED_ATTACK)>0){ const inR=mine.filter(c=>R(c,o)<=3); if(inR.length){ if(inR.filter(c=>R(c,o)<=1).length>=2) o.rangedMassAttack(); else o.rangedAttack(inR.sort((a,b)=>a.hits-b.hits)[0]); } else if(R(o,my)<=3) o.rangedAttack(my); }
      const rally={x:12,y:50};
      if(!complete){ if(R(o,rally)>2) o.moveTo(rally); continue; }
      const leader=grp.slice().sort((a,b)=>a.id-b.id)[0];
      const near=fighters.filter(f=>R(f,o)<=4).sort((a,b)=>R(a,o)-R(b,o));
      if(near.length && R(near[0],o)<=2){ let best=null,bd=-1; for(let dx=-1;dx<=1;dx++) for(let dy=-1;dy<=1;dy++){ const nx=o.x+dx,ny=o.y+dy; if(terrainAt(nx,ny)===C.TERRAIN_WALL) continue;
          if(world.objects.some(q=>q.exists&&q!==o&&q.x===nx&&q.y===ny&&(q instanceof Creep||q instanceof StructureSpawn||q instanceof StructureWall))) continue;
          const d=Math.min(...near.map(f=>R({x:nx,y:ny},f))); if(d>bd){bd=d;best={x:nx,y:ny};} }
        if(best&&(best.x!==o.x||best.y!==o.y)) world.intents.push({creep:o,x:best.x,y:best.y}); continue; }
      if(o!==leader){ if(R(o,leader)>1) o.moveTo(leader); continue; }
      if(near.length){ if(R(near[0],o)>=4) o.moveTo(near[0]); continue; }
      const haul=mine.filter(c=>c.body.some(p=>p.type===C.CARRY)&&R(c,o)<=8).sort((a,b)=>R(a,o)-R(b,o))[0];
      if(haul){ if(R(haul,o)>3) o.moveTo(haul); continue; }
      if(R(o,my)>6) o.moveTo(my); } }
  for(const o of world.objects){ if(!(o instanceof Creep) || o.my || !o.camper || !o.exists || o.spawning) continue;
    const mine=world.objects.filter(q=>q.exists&&q.my===true&&q instanceof Creep&&!q.spawning&&range(q,o)<=3).sort((a,b)=>a.hits-b.hits);
    if(mine.length) o.rangedAttack(mine[0]); else if(range(o,my)<=3) o.rangedAttack(my);
    if(range(o,my)>3) o.moveTo(my); }
  for(const o of world.objects){ if(o instanceof Creep && !o.my && o.baller && o.exists){
      if(o.hits<o.hitsMax) o.heal(o);
      const adj=world.objects.find(q=>q.exists&&q.my===true&&(q instanceof Creep&&!q.spawning)&&Math.max(Math.abs(q.x-o.x),Math.abs(q.y-o.y))<=1);
      if(adj) o.attack(adj); else if(Math.max(Math.abs(my.x-o.x),Math.abs(my.y-o.y))<=1) o.attack(my); else o.moveTo(my); } }
  for(const o of world.objects){ if(o instanceof Creep && !o.my && o.rusher){ const tgt=world.objects.find(q=>q.exists&&q.my===true&&(q instanceof Creep&&!q.spawning)&&Math.max(Math.abs(q.x-o.x),Math.abs(q.y-o.y))<=3)||my;
      if(o.rangedAttack(tgt)!==0){ const dx=Math.sign(my.x-o.x), dy=Math.sign(my.y-o.y); const nx=o.x+dx, ny=o.y+dy; if(world.terrain[nx*100+ny]!==1) o.moveTo(my); else o.moveTo({x:o.x, y:o.y+(dy||1)}); } } }
  if(t%50===0) lines.push('ENEMIES t='+t+': '+(tw?'TOWER h='+tw.hits+' e='+tw.store.energy+' cd='+tw.cooldown+' exists='+tw.exists+' | ':'')+(site&&site.exists?'SITE '+site.progress+'/'+site.progressTotal+' | ':'')+world.objects.filter(o=>o instanceof Creep&&!o.my).map(c=>'('+c.x+','+c.y+')h='+c.hits).join(' '));
  if(t>=286&&t<=298){ lines.push('INTENTS t='+t+': '+world.intents.map(i=>'h'+i.creep.id+'('+i.creep.x+','+i.creep.y+')->('+i.x+','+i.y+')').join(' ')+' | creeps: '+world.objects.filter(o=>o instanceof Creep&&o.my).map(c=>c.id+'('+c.x+','+c.y+')f='+c.fatigue).join(' ')); }
  my.store.energy=Math.min(1000,my.store.energy+1); en.store.energy=Math.min(1000,en.store.energy+((HEALBALL||STREAM||FORTRESS)?15:1)); // HEALBALL: у врага матча 13 десять хаулеров кормили спавн и башню
  endTick();
  if(!my.exists){ origLog('MY SPAWN DESTROYED at',t); break; }
  if(!en.exists){ origLog('ENEMY SPAWN DESTROYED at',t); break; }
}
console.log=origLog;
const skip=/^\d\d:|=== MAP|=== END MAP/;
for(const l of lines) if(!skip.test(l)) origLog(l);
origLog('--- loop ms: avg', (loopTotal/world.tick).toFixed(2), 'max', loopMax.toFixed(1));
origLog('--- ticks run:', world.tick, 'errors:', errors, 'my creeps:', world.objects.filter(o=>o instanceof Creep&&o.my).map(c=>c.body.map(p=>p.type[0]).join('')).join(' '), 'spawnE:', my.store.energy, 'enemySpawnHits:', en.hits);
origLog('WALLS:', world.objects.filter(o=>o instanceof StructureWall).map(w=>'('+w.x+','+w.y+')h='+w.hits).join(' '));
