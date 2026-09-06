// Offline runner for Pain and Gain (fixed armies, no spawns): a map (synthetic, or MAP=map-matchN.txt dumped from a
// match log) + a scripted enemy. Usage (see README.md and docs/pain-and-gain.md):
//   node --import ./register.mjs run.mjs <ticks> none|scouts|grab|rush|brawl|greedy|army|hunter|kite|sleeper|nine|roost|farm|scatter|camp (+shy: the parked blob steps aside from our armed creeps and comes back)|screen (+focus: the line keeps three from our most forward creep; +flagless: the enemy's runners idle; +weak: a remnant of eight; +fast: the screen without its formation gate)
//   env: MAP=<file> START=match2 (we are player 2) LOGTAG=<prefix> SLEEP=<tick> BOT=<bundle url>; logs go to ./out/
import { writeFileSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { world, process as step, idx, inBounds, range, creeps, live, creepAt } from './world.mjs';
import { Creep } from './game/prototypes/creep.mjs';
import { Resource } from './game/prototypes/resource.mjs';
import { ScoreFlag } from './arena/season_4/pain_and_gain/basic/prototypes.mjs';
import { CostMatrix, searchPath } from './game/path-finder.mjs';
import { getDirection } from './game/utils.mjs';

import { readFileSync } from 'node:fs';
// the bundle of THIS worktree's build (see the parallel-sessions rules: the stub tests what the worktree built)
const BOT = process.env.BOT || new URL('../../../build/js/packages/screeps-kotlin-arena-starter/kotlin/screeps-kotlin-arena-starter/season4/painandgain/PainAndGain.export.mjs', import.meta.url).href;
const MAP = process.env.MAP; // path to a 100-row DEBUG_MAP dump: '#' wall, '~' swamp, anything else plain
const ticks = parseInt(process.argv[2] || '2000', 10);
// TRACE=from-to prints every creep's position each tick in that range (see the loop below)
const TRACE = process.env.TRACE ? process.env.TRACE.split('-').map((v) => parseInt(v, 10)) : null;
const scenario = (process.argv[3] || 'none').split('+');
const has = (s) => scenario.includes(s);

// ---------- map (point-symmetric: (x,y) <-> (99-x, 99-y)) ----------
function rect(x0, y0, x1, y1, v) { for (let x = x0; x <= x1; x++) for (let y = y0; y <= y1; y++) if (inBounds(x, y)) world.terrain[idx(x, y)] = v; }
function mirrorRect(x0, y0, x1, y1, v) { rect(x0, y0, x1, y1, v); rect(99 - x1, 99 - y1, 99 - x0, 99 - y0, v); }
function buildMap() {
  mirrorRect(38, 20, 42, 45, 1);       // wall bars beside the centre
  mirrorRect(20, 44, 30, 46, 1);       // pockets near the side flags
  mirrorRect(44, 44, 55, 55, 2);       // swamp around the centre flag
  world.terrain[idx(49, 50)] = 0; world.terrain[idx(50, 49)] = 0;
  mirrorRect(10, 10, 30, 14, 2);       // swamp strips
  mirrorRect(55, 5, 60, 30, 2);
  mirrorRect(30, 60, 36, 90, 1);       // long walls with gaps
  world.terrain[idx(33, 75)] = 0; world.terrain[idx(66, 24)] = 0;
  const V = 'eff_damage_taken_modifier', H = 'eff_heal_modifier', A = 'eff_attack_modifier', R = 'eff_ranged_attack_modifier';
  world.objects.push(new ScoreFlag(49, 50, V, 5));
  world.objects.push(new ScoreFlag(25, 30, H, 4));
  world.objects.push(new ScoreFlag(74, 69, H, 4));
  world.objects.push(new ScoreFlag(25, 70, A, 3));
  world.objects.push(new ScoreFlag(74, 29, A, 3));
  world.objects.push(new ScoreFlag(49, 15, R, 3));
  world.objects.push(new ScoreFlag(50, 84, R, 3));
}
const M = 'move', A = 'attack', R = 'ranged_attack', H = 'heal', T = 'tough';
const ARMY = [
  { x: 10, y: 20, body: [M, M, M, M] },
  { x: 10, y: 80, body: [M, M, M, M] },
  { x: 14, y: 36, body: [T, T, M, M, M, M, M, A, A, A] },
  { x: 15, y: 36, body: [T, T, M, M, M, M, M, A, A, A] },
  { x: 14, y: 38, body: [M, M, M, M, R, R, R, R] },
  { x: 15, y: 38, body: [M, M, M, M, R, R, R, R] },
  { x: 14, y: 40, body: [M, M, M, H, H, H] },
  { x: 16, y: 40, body: [M, M, M, M, M, R, R] },
  { x: 14, y: 60, body: [T, T, M, M, M, M, M, A, A, A] },
  { x: 15, y: 60, body: [T, T, M, M, M, M, M, A, A, A] },
  { x: 14, y: 62, body: [M, M, M, M, R, R, R, R] },
  { x: 15, y: 62, body: [M, M, M, M, R, R, R, R] },
  { x: 14, y: 64, body: [M, M, M, H, H, H] },
  { x: 16, y: 64, body: [M, M, M, M, M, R, R] },
];
function placeArmies() {
  for (const u of ARMY) {
    world.objects.push(new Creep(u.x, u.y, 0, u.body));
    world.objects.push(new Creep(99 - u.x, 99 - u.y, 1, u.body));
  }
}
// ---------- live map of match 1 (04.09.2026): real terrain, flags, bodies and start positions ----------
const MELEE = [A, A, A, A, A, A, A, A, M, M, M, M, M, M, M, M];
const RANGED = [R, R, R, R, R, R, M, M, M, M, M, M];
const HEALER = [H, H, H, H, H, H, M, M, M, M, M, M];
const MATCH1_OURS = [[12, 9, [M]], [9, 6, [M]], [15, 7, MELEE], [10, 11, MELEE], [10, 12, MELEE], [9, 12, MELEE], [15, 6, RANGED], [14, 7, RANGED], [15, 11, RANGED], [14, 12, RANGED], [15, 12, RANGED], [9, 11, HEALER], [14, 6, HEALER], [14, 11, HEALER]];
const MATCH1_ENEMY = [[86, 89, [M]], [89, 92, [M]], [83, 91, MELEE], [88, 87, MELEE], [88, 86, MELEE], [89, 86, MELEE], [84, 91, RANGED], [83, 92, RANGED], [83, 87, RANGED], [84, 86, RANGED], [83, 86, RANGED], [89, 87, HEALER], [84, 92, HEALER], [84, 87, HEALER]];
function buildLiveMap(path) {
  const rows = readFileSync(path, 'utf8').split('\n').filter((r) => r.length > 0);
  if (rows.length !== 100) throw new Error(`map must have 100 rows, got ${rows.length}`);
  rows.forEach((r, y) => { if (r.length !== 100) throw new Error(`row ${y} has ${r.length} chars`); for (let x = 0; x < 100; x++) world.terrain[idx(x, y)] = r[x] === '#' ? 1 : r[x] === '~' ? 2 : 0; });
  const V = 'eff_damage_taken_modifier', Hm = 'eff_heal_modifier', Am = 'eff_attack_modifier', Rm = 'eff_ranged_attack_modifier';
  world.objects.push(new ScoreFlag(49, 49, V, 5));
  world.objects.push(new ScoreFlag(90, 8, Hm, 4));
  world.objects.push(new ScoreFlag(8, 90, Hm, 4));
  world.objects.push(new ScoreFlag(67, 31, Am, 3));
  world.objects.push(new ScoreFlag(31, 67, Am, 3));
  world.objects.push(new ScoreFlag(13, 49, Rm, 3));
  world.objects.push(new ScoreFlag(85, 49, Rm, 3));
  // START=match2: we were player 2 (bottom-right), the enemy player 1 — the same two position sets swapped
  const swap = process.env.START === 'match2';
  for (const [x, y, body] of (swap ? MATCH1_ENEMY : MATCH1_OURS)) world.objects.push(new Creep(x, y, 0, body));
  // +weak: the enemy fields a remnant — two scouts, one melee, three ranged, two healers — the shape the live opponent of
  // matches 28 and 33 was left with after our hunt (and then farmed the flags behind our back for a points win)
  const WEAK = (process.argv[3] || '').includes('weak');
  let nM = 0, nR = 0, nH = 0;
  for (const [x, y, body] of (swap ? MATCH1_OURS : MATCH1_ENEMY)) {
    if (WEAK) {
      if (body === MELEE && ++nM > 1) continue;
      if (body === RANGED && ++nR > 3) continue;
      if (body === HEALER && ++nH > 2) continue;
    }
    world.objects.push(new Creep(x, y, 1, body));
  }
}
if (MAP) buildLiveMap(MAP); else { buildMap(); placeArmies(); }
world.spawnRegen = [0, 0];

// ---------- enemy AI ----------
const runnerFlag = new Map();
const armyState = {};
function pathStepTo(c, target, stop) {
  if (range(c, target) <= stop) return null;
  const cm = new CostMatrix();
  for (const o of creeps()) if (!o.spawning && o !== c && !(o.x === target.x && o.y === target.y)) cm.set(o.x, o.y, 255);
  const r = searchPath(c, { pos: target, range: stop }, { costMatrix: cm });
  return r.path[0] || null;
}
function stepToward(c, target, stop) { const s = pathStepTo(c, target, stop); if (s) c.move(getDirection(s.x - c.x, s.y - c.y)); }
function stepAway(c, from) {
  const cm = new CostMatrix();
  for (const o of creeps()) if (!o.spawning && o !== c) cm.set(o.x, o.y, 255);
  const r = searchPath(c, from.map((f) => ({ pos: f, range: 4 })), { costMatrix: cm, flee: true });
  const s = r.path[0];
  if (s) c.move(getDirection(s.x - c.x, s.y - c.y));
}
// one greedy step back: the free neighbour cell farthest from the nearest of `from` (a flee path with every creep at 255
// finds nothing inside a formed line, and the screen's ranged stood at one or two from ours in 80–100 % of contact samples)
function stepBack(c, from) {
  let best = null, bd = Math.min(...from.map((f) => range(c, f)));
  const taken = new Set(creeps().filter((o) => !o.spawning && o !== c).map((o) => o.x * 100 + o.y));
  for (const dx of [-1, 0, 1]) for (const dy of [-1, 0, 1]) {
    if (!dx && !dy) continue;
    const x = c.x + dx, y = c.y + dy;
    if (!inBounds(x, y) || taken.has(x * 100 + y) || world.terrain[idx(x, y)] === 1) continue;
    const d = Math.min(...from.map((f) => Math.max(Math.abs(x - f.x), Math.abs(y - f.y))));
    if (d > bd) { bd = d; best = { x, y }; }
  }
  if (best) c.move(getDirection(best.x - c.x, best.y - c.y));
  return !!best;
}
function isRunner(c) { return c.body.every((p) => p.type === M); }
// 'nine' (match 9 opponent): our healers first, then the lowest hits — two of ours lost every HEAL part by tick 140
const healerOf = (o) => live(o, H) > 0 && live(o, A) === 0 && live(o, R) === 0;
// 'twelve' hunts the way 'nine' does (healers first, single-target fire, its healers behind), after a roam
// 'fourteen' is 'nine' plus rotation: a fighter below half hits steps back to its healers and returns healed
const NINE = has('nine') || has('twelve') || has('fourteen') || has('block') || has('wing');
const ROTATE_OUT = 0.5, ROTATE_IN = 0.9;
const rotating = new Set();
// 'screen' (match 29): the live block that beat v28 in an even fight — healers ONE cell behind the front and never
// stepping away, ranged one behind too (mass attack from one to two cells), melee never advancing without a healer
// within two, no rotation (a melee is healed back in place, M8A5 -> M8A8 in two ticks), and every gun on our MOST
// FORWARD creep — the one our healers have not caught up with — then the lowest hits
let focusAnchor = null;
// brawl: an armed ranged of ours first (r->ranged 152 of 367 in match 249 — our ranged were disarmed 113 creep-ticks against
// his 42), then the lowest hits
const targetKey = (o) => has('screen') && focusAnchor ? range(o, focusAnchor) * 100000 + o.hits : has('brawl') ? (live(o, R) > 0 ? 0 : 1) * 100000 + o.hits : (NINE && healerOf(o) ? 0 : 1) * 100000 + o.hits;
// the enemy's fire concentration, as tools/replay.py's conc counts it live: per tick the largest number of its single-target
// shots on one of ours; reported at the end as a histogram (matches 140–179: the live lines put four or more on one creep in
// 11–28 % of their firing ticks, the bot 0–3 %)
const eShots = new Map();
const eConc = { ticks: 0, hist: [0, 0, 0, 0, 0, 0] };
function eConcTick() {
  if (eShots.size) { let m = 0; for (const v of eShots.values()) if (v > m) m = v; eConc.ticks++; eConc.hist[Math.min(m, 5)]++; eShots.clear(); }
}
function fireAt(c, ours) {
  const inRange = ours.filter((o) => range(c, o) <= 3);
  if (live(c, R) > 0 && inRange.length) {
    const close = inRange.filter((o) => range(c, o) <= 2);
    if (close.length >= 2 && !NINE && !has('brawl')) c.rangedMassAttack();   // brawl: single-target fire (mass 6 of 412 shots in match 249)
    else { const t = inRange.sort((a, b) => targetKey(a) - targetKey(b))[0]; c.rangedAttack(t); eShots.set(t.id, (eShots.get(t.id) || 0) + 1); }
  }
  if (live(c, A) > 0) {
    const adj = inRange.filter((o) => range(c, o) <= 1);
    if (adj.length) c.attack(adj.sort((a, b) => targetKey(a) - targetKey(b))[0]);
  }
}
// match 14: a damaged fighter (below ROTATE_OUT of its hits) walks to its nearest healer, stays out of our armed
// creeps' reach until healed above ROTATE_IN, then returns to the line; the live opponent's melee went M8A1 -> M8A5
// behind its healers in eight ticks and came back while ours died in place
function rotate(c, fighters, ours) {
  const healers = fighters.filter((o) => o !== c && live(o, H) > 0);
  if (!healers.length) { rotating.delete(c.id); return false; }
  if (rotating.has(c.id)) { if (c.hits >= c.hitsMax * ROTATE_IN) rotating.delete(c.id); }
  else if (c.hits < c.hitsMax * ROTATE_OUT) rotating.add(c.id);
  if (!rotating.has(c.id)) return false;
  const h = healers.sort((a, b) => range(c, a) - range(c, b))[0];
  const near = ours.filter((o) => !isRunner(o) && live(o, A) + live(o, R) > 0 && range(c, o) <= 3);
  if (near.length && range(c, h) <= 1) stepAway(c, near); else stepToward(c, h, 1);
  return true;
}
function sgn(v) { return v > 0 ? 1 : v < 0 ? -1 : 0; }
function planBlock(fighters, ours, ourCentroid) {
  const isH = (c) => c.body.some((p) => p.type === H);
  const isR = (c) => c.body.some((p) => p.type === R);
  const front = fighters.filter((c) => !isH(c) && !isR(c) && !rotating.has(c.id));
  const base = front.length ? front : fighters.filter((c) => !isH(c));
  const anchor = { x: Math.round(base.reduce((s, c) => s + c.x, 0) / base.length), y: Math.round(base.reduce((s, c) => s + c.y, 0) / base.length) };
  const nearestOur = ours.filter((o) => !isRunner(o)).sort((a, b) => range(anchor, a) - range(anchor, b))[0] || ourCentroid;
  const dir = { x: sgn(nearestOur.x - anchor.x), y: sgn(nearestOur.y - anchor.y) };
  return { anchor, dir, nearestOur, isH, isR };
}
// 'screen' moves as ONE block in every mode: melee and ranged within one of the anchor (the melee centroid, see
// planBlock), healers one behind, and the front advances only while every member is within two of the anchor. The
// live block arrived at our line already formed with its healers adjacent (match 30, t=68); the stub's marched as a
// column and formed under fire — its healers were eight to twelve cells behind the first creep we hit
function screenMove(c, plan, fighters, ours) {
  const { anchor, dir, isH, isR } = plan;
  const ourF = ours.filter((o) => !isRunner(o));
  const armed = ourF.filter((o) => live(o, A) + live(o, R) > 0);
  const nearestArmed = armed.slice().sort((a, b) => range(c, a) - range(c, b))[0];
  const armedClose = armed.filter((o) => range(c, o) <= 2);
  // two rows, like the live block (match 29, t=100: R-M-R at x=73, the three healers at x=72 directly behind):
  // the front row across the axis to our nearest — melee at its middle, ranged at its ends — and the healers one
  // behind; each creep takes the nearest free cell of its row, so a healer always finds a cell adjacent to the front
  const px = -dir.y, py = dir.x;
  const order = [0, -1, 1, -2, 2, -3, 3, -4, 4];
  const rowCells = (back) => order.map((k) => ({ x: anchor.x - back * dir.x + k * px, y: anchor.y - back * dir.y + k * py }));
  const front = fighters.filter((o) => !isH(o)).sort((a, b) => (isR(a) ? 1 : 0) - (isR(b) ? 1 : 0));
  const rear = fighters.filter((o) => isH(o));
  const slotOf = new Map();
  for (const [row, cells] of [[front, rowCells(0)], [rear, rowCells(1)]]) {
    const free = cells.slice();
    for (const o of row) {
      let best = null, bd = 99;
      for (const cell of free) { const d = range(o, cell); if (d < bd) { bd = d; best = cell; } }
      if (best) { slotOf.set(o.id, best); free.splice(free.indexOf(best), 1); }
    }
  }
  const slot = slotOf.get(c.id) || anchor;
  const formed = fighters.filter((o) => !isRunner(o)).every((o) => range(o, slotOf.get(o.id) || anchor) <= 2);
  if (isH(c)) {
    const mate = fighters.filter((o) => o !== c && live(o, H) === 0 && o.hits < o.hitsMax).sort((a, b) => (a.hits / a.hitsMax) - (b.hits / b.hitsMax))[0];
    if (mate) { if (range(c, mate) > 1) stepToward(c, mate, 1); return; }
    if (range(c, slot) > 0) stepToward(c, slot, 0);
    return;
  }
  if (!isR(c)) {
    const prey = ourF.filter((o) => live(o, A) === 0 && range(c, o) <= 3).sort((a, b) => range(c, a) - range(c, b))[0];
    if (prey) { stepToward(c, prey, 1); return; }
  }
  if (armedClose.length) { if (!stepBack(c, armedClose)) stepAway(c, armedClose); return; }
  // '+focus' (matches 140–179, 05–06.09.2026): the live line — Coldkimchi's and けろびー's fighting build — stands at three from
  // our MOST FORWARD creep, the one every gun of its goes to (targetKey), so all five reach the same target: four or more shots
  // on one creep in 11–28 % of its firing ticks against 0–3 % of ours. The plain screen keeps three from each ranged's OWN
  // nearest armed creep and spreads its fire over five targets — which is why it loses to the block (m30 at 199, m31 at 318)
  // while the live line wins nine of ten. With +focus a ranged of the screen keeps exactly three from that one creep
  if (has('focus')) {
    const focus = ourF.slice().sort((a, b) => range(anchor, a) - range(anchor, b) || a.hits - b.hits)[0];
    if (focus) {
      if (isR(c)) {
        // the live ranged keep EXACTLY three from the nearest of ours and step back when one of ours steps to two (the
        // 'keeps its distance' lines of every standing fight): matches 191/195/198 put his ranged at 3–4 from our nearest
        // in 800 of 1200 creep-ticks and never at one or two, while this screen's ranged, standing still at three or
        // closer, were at one or two in 80–100 % of contact samples and dead by t=160–200 (06.09.2026, item 5)
        // ...and out of OUR ranged's reach: the live line's ranged stand at three from our nearest MELEE (whom they shoot)
        // and at four from our nearest RANGED (who therefore never shoot them: 0 of his ranged disarmed in 191/195/198
        // against 45–79 of ours) — a ranged of ours within three is stepped away from, our melee at three is the target
        const nearest = ourF.slice().sort((a, b) => range(c, a) - range(c, b))[0];
        const ourRanged = ourF.filter((o) => live(o, R) > 0).sort((a, b) => range(c, a) - range(c, b))[0];
        if (ourRanged && range(c, ourRanged) <= 3) { if (!stepBack(c, [ourRanged])) stepAway(c, [ourRanged]); return; }
        if (nearest && range(c, nearest) < 3) { if (!stepBack(c, [nearest])) stepAway(c, [nearest]); return; }
        if (range(c, focus) <= 3) return;
        if (!formed && !has('fast') && range(c, slot) > 1) { stepToward(c, slot, 0); return; }
        stepToward(c, focus, 3); return;
      }
      // the live line's melee stand at TWO from our most forward creep, in front of their ranged — the poke of matches 73 and
      // 140–179 (adjacent to ours 54 % of their creep-ticks in 140, at two or three 72 % in 145): they hold our melee off the
      // ranged behind them and hit what steps in; a melee of ours adjacent gets the swing (fireAt) and then a step back to two
      const adjOurs = ourF.filter((o) => range(c, o) <= 1);
      if (adjOurs.length && range(c, focus) <= 1) { if (!stepBack(c, adjOurs)) stepAway(c, adjOurs); return; }
      if (range(c, focus) > 2) { stepToward(c, focus, 2); return; }
      // the POKE (06.09.2026): from two the live melee step in on our most forward creep, swing and step back the same tick
      // (fireAt runs before the move) — 33–63 swings a fight against our 5–9, which stand behind our ranged and never
      // answer; at two the stub's melee only waited for someone to walk into them
      if (range(c, focus) === 2) { stepToward(c, focus, 1); return; }
      return;
    }
  }
  if (nearestArmed && range(c, nearestArmed) <= 3) return;
  if (range(c, slot) > 1) { stepToward(c, slot, 0); return; }
  // '+fast' (match 78, Coldkimchi): the live line never waits to form — it walks at our army at full speed and forms on
  // arrival; the formation gate is what let our evasion outrun the stub's screen (screen+flagless 5241:0 on five maps).
  // Even fast, the stub's screen loses to the block (m30 destroyed at 199, m31 at 318) while Coldkimchi's line wins 3 of 3
  // live — the model gap is open (his melee poke and step back, four guns on one target in 11 % of ticks)
  if (!formed && !has('fast')) { if (range(c, slot) > 0) stepToward(c, slot, 0); return; }
  if (nearestArmed) stepToward(c, nearestArmed, 3); else stepToward(c, plan.nearestOur, 3);
}
function blockMove(c, plan, fighters, ours) {
  if (has('screen')) return screenMove(c, plan, fighters, ours);
  const { anchor, dir, isH, isR } = plan;
  const healers = fighters.filter((o) => o !== c && live(o, H) > 0);
  const screen = has('screen');
  if (!screen && !isH(c) && rotate(c, fighters, ours)) return;
  const ourF = ours.filter((o) => !isRunner(o));
  const nearest = ourF.sort((a, b) => range(c, a) - range(c, b))[0];
  if (isH(c)) {
    const mate = fighters.filter((o) => o !== c && live(o, H) === 0 && o.hits < o.hitsMax).sort((a, b) => (a.hits / a.hitsMax) - (b.hits / b.hitsMax))[0];
    const threat = ourF.filter((o) => live(o, A) + live(o, R) > 0 && range(c, o) <= 3);
    const slot = { x: anchor.x - (screen ? 1 : 3) * dir.x, y: anchor.y - (screen ? 1 : 3) * dir.y };
    if (!screen && threat.length) stepAway(c, threat);
    // screen: a healer stands ADJACENT to whichever fighter is being hit — the live block healed its front melee at
    // 216 a tick (M8A5 -> M8A8 in two ticks); a healer in a slot by the block's centre reaches it only at range
    else if (screen && mate) { if (range(c, mate) > 1) stepToward(c, mate, 1); }
    else if (mate && range(mate, anchor) > 1 && range(c, mate) > 1) stepToward(c, mate, 1);
    else if (range(c, slot) > (screen ? 0 : 1)) stepToward(c, slot, screen ? 0 : 1);
    return;
  }
  const wing = has('wing');
  if (isR(c)) {
    const armedNear = ourF.some((o) => live(o, A) + live(o, R) > 0 && range(c, o) <= 2);
    const back = (wing && !armedNear) || screen ? 0 : 2;
    const slot = { x: anchor.x - back * dir.x, y: anchor.y - back * dir.y };
    const close = ourF.filter((o) => live(o, A) > 0 && range(c, o) <= 1);
    if (screen) {
      // the live line kept EXACTLY three from our nearest armed creep — every distance at t=80 of match 30 was 3 or 4:
      // it fires from three and gives ground to a melee that steps to two, so our melee never reach a target
      const armedClose = ourF.filter((o) => live(o, A) + live(o, R) > 0 && range(c, o) <= 2);
      const nearestArmed = ourF.filter((o) => live(o, A) + live(o, R) > 0).sort((a, b) => range(c, a) - range(c, b))[0];
      if (armedClose.length) stepAway(c, armedClose);
      else if (nearestArmed && range(c, nearestArmed) > 3) stepToward(c, nearestArmed, 3);
      return;
    }
    if (close.length) stepAway(c, close);
    else if (range(c, slot) > (back === 0 ? 0 : 1)) stepToward(c, slot, back === 0 ? 0 : 1);
    return;
  }
  // melee: hold the front row — attack what is adjacent, otherwise advance with the block (never more than two from it);
  // the wing's line stops three from our nearest creep and its melee only step to what is within two
  if (nearest && range(c, nearest) <= 1) return;
  // the live block STOOD: once any of ours was within three of its front it waited, and our melee came into it one by
  // one (match 29, t=67-200: their fighting eight never moved off (70-73, 63-65) while four of our melee died there)
  if (screen) {
    // its melee engage only what its ranged have already DISARMED (match 30: they stood at three from our melee for
    // fifteen ticks while five ranged took ten attack parts off ours, and stepped in at t=84 on the hulks)
    const prey = ourF.filter((o) => live(o, A) === 0 && range(c, o) <= 3).sort((a, b) => range(c, a) - range(c, b))[0];
    if (prey) { stepToward(c, prey, 1); return; }
    const armedClose = ourF.filter((o) => live(o, A) + live(o, R) > 0 && range(c, o) <= 2);
    const nearestArmed = ourF.filter((o) => live(o, A) + live(o, R) > 0).sort((a, b) => range(c, a) - range(c, b))[0];
    if (armedClose.length) { stepAway(c, armedClose); return; }
    if (nearestArmed && range(c, nearestArmed) <= 3) return;
    if (nearestArmed) { stepToward(c, nearestArmed, 3); return; }
    if (!healers.some((h) => range(c, h) <= 2)) { if (range(c, anchor) > 1) stepToward(c, anchor, 1); return; }
  }
  if (wing) {
    if (nearest && range(c, nearest) <= 2) { stepToward(c, nearest, 1); return; }
    if (nearest && ourF.some((o) => range(anchor, o) <= 3)) return;
  }
  if (range(c, anchor) > 2) stepToward(c, anchor, 1);
  else if (nearest) stepToward(c, nearest, 1);
}
function healAt(c, mine) {
  if (live(c, H) === 0) return;
  const hurt = mine.filter((o) => o.hits < o.hitsMax && range(c, o) <= 3).sort((a, b) => (b.hitsMax - b.hits) - (a.hitsMax - a.hits))[0];
  if (!hurt) return;
  if (range(c, hurt) <= 1) c.heal(hurt); else c.rangedHeal(hurt);
}
function enemyTick() {
  eConcTick();
  const flags = world.objects.filter((o) => o.exists && o.kind === 'flag');
  const mine = creeps().filter((c) => c.owner === 1);
  const ours = creeps().filter((c) => c.owner === 0);
  // NORUNNERS=1: the enemy's runners never move — the live opponent of matches 30 and 31 took no flag at all until our
  // army was dead, so a rush-type script with idle runners is the closest model of it
  const runners = (process.env.NORUNNERS || has('flagless')) ? [] : mine.filter(isRunner);
  const fighters = mine.filter((c) => !isRunner(c));
  // runners: nearest flag not theirs (sticky), then sit
  for (const [id] of runnerFlag) if (!mine.some((c) => c.id === id)) runnerFlag.delete(id);
  // 'scatter': the two runners go for the two H4 corners from the first tick (live match 240: taken at t=78 and 84)
  if (has('scatter') && !armyState.runnersSeeded) {
    const h4s = flags.filter((f) => f.effectType === 'eff_heal_modifier');
    runners.forEach((r, i) => { if (h4s[i]) runnerFlag.set(r.id, h4s[i].id); });
    armyState.runnersSeeded = true;
  }
  if (!has('none')) for (const r of runners) {
    let f = runnerFlag.get(r.id) ? flags.find((x) => x.id === runnerFlag.get(r.id)) : null;
    if (f && r.x === f.x && r.y === f.y && f.owner === 1) continue;
    if (!f || (f.owner === 1 && creepAt(f.x, f.y) && creepAt(f.x, f.y) !== r)) {
      const taken = new Set(runnerFlag.values());
      const cands = flags.filter((x) => x.owner !== 1 && !taken.has(x.id) && !(creepAt(x.x, x.y) && creepAt(x.x, x.y).owner === 0));
      f = cands.sort((a, b) => range(r, a) - range(r, b))[0];
      if (!f) continue;
      runnerFlag.set(r.id, f.id);
    }
    const near = ours.filter((o) => !isRunner(o) && range(r, o) <= 4);
    if (near.length) stepAway(r, near); else stepToward(r, f, 0);
  }
  const ourCentroid = ours.length ? { x: Math.round(ours.reduce((s, c) => s + c.x, 0) / ours.length), y: Math.round(ours.reduce((s, c) => s + c.y, 0) / ours.length) } : { x: 15, y: 50 };
  // 'army' (match 3 opponent): the whole army marches D5 -> its A3 -> a hover point near its H4 corner, attacks
  // any of our fighters within 12 of its centroid (melee adjacent, ranged at 2, healers adjacent to the most
  // damaged mate, focus on the lowest hits), returns to the route when nobody is within 16; with our army dead
  // it sweeps the flags and hunts runners
  let armyMode = null;
  // 'hunter' (match 4 opponent): D5 with the whole army, then straight at our army wherever it is
  if ((has('army') || has('hunter') || has('nine') || has('twelve') || has('fourteen') || has('block') || has('wing') || has('screen')) && fighters.length) {
    const cen = { x: Math.round(fighters.reduce((s, c) => s + c.x, 0) / fighters.length), y: Math.round(fighters.reduce((s, c) => s + c.y, 0) / fighters.length) };
    if (!armyState.waypoints) {
      const south = cen.y > 50;
      // match 12: D5, then a loop through our half (78,59) up to its H4 corner (85,15), then the match-9 hunt at 1.0
      const wp = (has('hunter') ? [[49, 49]] : has('twelve') ? [[49, 49], [78, 59], [85, 15]] : [[49, 49], [31, 67], [17, 85]]).map(([x, y]) => (south ? { x: 99 - x, y: 99 - y } : { x, y }));
      armyState.waypoints = wp; armyState.phase = 0; armyState.engaged = false;
    }
    const ourFighters = ours.filter((o) => !isRunner(o));
    // match 3: the enemy left its hover point when our army came within ~20 cells of it
    const near = ourFighters.filter((o) => range(o, cen) <= 20 || fighters.some((f) => range(f, o) <= 8));
    if (near.length && !NINE) armyState.engaged = true;
    else if (!ourFighters.some((o) => range(o, cen) <= 26)) armyState.engaged = false;
    if (has('hunter') && range(cen, armyState.waypoints[0]) <= 2) armyState.hunting = true;
    if (has('twelve') && armyState.phase === armyState.waypoints.length - 1 && range(cen, armyState.waypoints[armyState.phase]) <= 2) armyState.hunting = true;
    // match 9: the whole army walked straight at ours as one blob, no flag on the way; it charges from six cells
    if (has('nine') || has('fourteen') || has('block') || has('wing') || has('screen')) { armyState.waypoints = [ourCentroid]; armyState.phase = 0; if (range(cen, ourCentroid) <= 6) armyState.hunting = true; }
    if (armyState.hunting) armyState.engaged = true;
    if (ourFighters.length === 0) armyMode = 'sweep';
    else if (armyState.engaged) armyMode = 'fight';
    else {
      const wp = armyState.waypoints[armyState.phase];
      if (range(cen, wp) <= 2 && armyState.phase < armyState.waypoints.length - 1) armyState.phase++;
      armyMode = 'march';
    }
  }
  // 'block' (matches 14-16): once hunting, the army moves as one block toward our centroid — melee in the front row,
  // ranged two cells behind the melee anchor, healers three behind — fires at whatever is in range (focus: healers first,
  // then the lowest hits), and rotates a fighter below half hits back to its healers; melee keep within two of the anchor
  // 'wing' (match 17): the block's ranged walk in the FRONT row and the line stops three cells from our nearest creep —
  // the ranged shoot our front for free while the melee hold the line and only hit what steps within two; a ranged
  // with one of our armed creeps within two backs off two rows; healers two behind
  const blockPlan = ((has('block') || has('wing') || has('screen')) && armyMode === 'fight') || (has('screen') && armyMode === 'march') ? planBlock(fighters, ours, ourCentroid) : null;
  focusAnchor = blockPlan ? blockPlan.anchor : null;
  for (const c of fighters) {
    if (!has('shy') && !has('scatter')) fireAt(c, ours);   // +shy, scatter: the live camper/scatterer never fired (matches 133, 152, 159, 240)
    healAt(c, mine);
    if (has('none') || has('scouts')) continue; // 'scouts': only the enemy runners act, its army idles (match 2)
    if (blockPlan) { blockMove(c, blockPlan, fighters, ours); continue; }
    const nearestOur = ours.filter((o) => !isRunner(o)).sort((a, b) => range(c, a) - range(c, b))[0];
    if (armyMode) {
      const isHealer = live(c, H) > 0 && live(c, A) === 0 && live(c, R) === 0;
      if (armyMode === 'fight') {
        if (isHealer) {
          const mate = fighters.filter((o) => o !== c && live(o, H) === 0).sort((a, b) => (a.hits / a.hitsMax) - (b.hits / b.hitsMax))[0];
          // match 9: the enemy healers stayed two to four cells behind their line and were never touched
          const threat = NINE ? ours.filter((o) => !isRunner(o) && live(o, A) + live(o, R) > 0 && range(c, o) <= 2) : [];
          if (threat.length) stepAway(c, threat);
          else if (mate) stepToward(c, mate, has('fourteen') ? 1 : NINE ? 2 : 1);
        } else if (has('fourteen') && rotate(c, fighters, ours)) {
          // rotating: handled inside rotate()
        } else if (nearestOur) stepToward(c, nearestOur, live(c, A) > 0 ? 1 : 2);
      } else if (armyMode === 'march') {
        const wp = armyState.waypoints[armyState.phase];
        stepToward(c, wp, fighters.indexOf(c) === 0 ? 0 : 2);
      } else {
        const target = flags.filter((f) => f.owner !== 1).sort((a, b) => range(c, a) - range(c, b))[0];
        const victim = ours.sort((a, b) => range(c, a) - range(c, b))[0];
        if (victim && range(c, victim) <= 10) stepToward(c, victim, live(c, A) > 0 ? 1 : 2);
        else if (target) stepToward(c, target, fighters.indexOf(c) === 0 ? 0 : 2);
      }
      continue;
    }
    // 'kite' (match 5 opponent): the melee plus one healer are bait — they charge, fight ~40 ticks, then run for the
    // corner farthest from our army; the ranged plus the other healers kite: back off from any of our fighters
    // within 2, close to range 3 otherwise, focus the lowest hits; with nobody of ours within 12 they sweep flags
    if (has('kite')) {
      if (!armyState.roles) {
        armyState.roles = new Map();
        let baitHealer = false;
        for (const f of fighters) {
          const isMelee = f.body.some((p) => p.type === A);
          const isHealer = !isMelee && f.body.some((p) => p.type === H);
          if (isMelee || (isHealer && !baitHealer)) { armyState.roles.set(f.id, 'bait'); if (isHealer) baitHealer = true; }
          else armyState.roles.set(f.id, 'main');
        }
        armyState.contactTick = -1;
      }
      const role = armyState.roles.get(c.id) || 'main';
      const ourF = ours.filter((o) => !isRunner(o));
      const nearest = ourF.sort((a, b) => range(c, a) - range(c, b))[0];
      const isHealer = live(c, H) > 0 && live(c, A) === 0 && live(c, R) === 0;
      if (role === 'bait') {
        if (nearest && range(c, nearest) <= 3 && armyState.contactTick < 0) armyState.contactTick = world.tick;
        const fleeing = armyState.contactTick >= 0 && world.tick - armyState.contactTick > 40;
        if (fleeing) {
          const corners = [{ x: 2, y: 2 }, { x: 2, y: 97 }, { x: 97, y: 2 }, { x: 97, y: 97 }];
          const far = corners.sort((p, q) => range(q, ourCentroid) - range(p, ourCentroid))[0];
          if (nearest && range(c, nearest) <= 6) stepAway(c, ourF.filter((o) => range(c, o) <= 6)); else stepToward(c, far, 1);
        } else if (isHealer) {
          const mate = fighters.filter((o) => o !== c && armyState.roles.get(o.id) === 'bait' && live(o, H) === 0).sort((a, b) => (a.hits / a.hitsMax) - (b.hits / b.hitsMax))[0];
          if (mate) stepToward(c, mate, 1);
        } else if (nearest) stepToward(c, nearest, 1);
        else stepToward(c, ourCentroid, 1);
        continue;
      }
      // main: kiting ranged and their healers
      const main = fighters.filter((o) => armyState.roles.get(o.id) === 'main');
      if (isHealer) {
        const mate = main.filter((o) => o !== c && live(o, H) === 0).sort((a, b) => (a.hits / a.hitsMax) - (b.hits / b.hitsMax))[0];
        if (mate) stepToward(c, mate, 1);
        continue;
      }
      if (nearest && range(c, nearest) <= 2) stepAway(c, ourF.filter((o) => range(c, o) <= 4));
      else if (nearest && range(c, nearest) <= 12) { if (range(c, nearest) > 3) stepToward(c, nearest, 3); }
      else {
        const target = flags.filter((f) => f.owner !== 1).sort((a, b) => range(c, a) - range(c, b))[0];
        if (target) stepToward(c, target, main.indexOf(c) === 0 ? 0 : 2);
      }
      continue;
    }
    // 'sleeper': the army stands still (a camper the passive floor mistakes for a dead bot) until t=500, then rushes
    if (has('sleeper') && world.tick < (parseInt(process.env.SLEEP || '500', 10))) continue;
    if (has('brawl')) {
      // 'brawl' (live matches 238 and 249, けろびー's blob at parity — both lost by annihilation, three of his melee killed at
      // most): the whole army walks at ours as one blob and fights the way the replays count it. A melee goes for the nearest
      // of our RANGED or HEALERS within five (a->ranged 21, a->healer 15 against a->melee 10 in 249), else the nearest creep,
      // and swings whenever adjacent (51 swings in 59 adjacent creep-ticks). A ranged holds exactly three from our nearest
      // creep (2:115 3:286 4:141 of 580 creep-ticks), steps back from anything within two, and fires single shots at the
      // lowest hits in range. A healer stands adjacent to the most wounded mate (235 adjacent heals against 85 ranged) and
      // never steps away from our armed creeps; with nobody wounded it walks one behind the nearest melee. No rotation.
      const ourF = ours.filter((o) => !isRunner(o));
      const nearest = (ourF.length ? ourF : ours).slice().sort((a, b) => range(c, a) - range(c, b))[0];
      const isHealer = live(c, H) > 0 && live(c, A) === 0 && live(c, R) === 0;
      // the blob keeps its edge (v3 of the form): the stub's blob walked THROUGH our line — melee diving to soft targets,
      // ranged following to three from whatever was nearest and ending adjacent to the rest of ours, healers behind them —
      // and our mass fire took twelve creeps in thirty ticks (m31: deaths t=94–124), where the live blob held one side of
      // us for a hundred ticks and lost three. Here the blob advances only formed (nobody more than three behind its front),
      // a melee holds the front and swings at what is adjacent (a soft target within TWO draws it, not five), a ranged
      // stands where it is three from our nearest armed creep and at least three from every other, a healer one behind
      // the nearest ranged unless a mate at three or more from our armed is wounded
      const ourArmed = ourF.filter((o) => live(o, A) + live(o, R) > 0);
      const minOur = (x, y) => ourArmed.length ? Math.min(...ourArmed.map((o) => Math.max(Math.abs(x - o.x), Math.abs(y - o.y)))) : 99;
      // the feint (match 249, t=44–49): at first contact the whole blob steps back a cell for three ticks and then comes on —
      // live it marked all twelve of his creeps as 'keeping their distance' for twenty ticks and our melee stood
      if (armyState.feint === undefined && ourArmed.some((o) => fighters.some((f) => range(f, o) <= 3))) armyState.feint = world.tick;
      if (armyState.feint !== undefined && world.tick - armyState.feint < 3) { const near = ourArmed.filter((o) => range(c, o) <= 4); if (near.length && !stepBack(c, near)) stepAway(c, near); continue; }
      const blobC = { x: Math.round(fighters.reduce((s, f) => s + f.x, 0) / fighters.length), y: Math.round(fighters.reduce((s, f) => s + f.y, 0) / fighters.length) };
      const frontD = Math.min(...fighters.map((f) => minOur(f.x, f.y)));
      const formed = fighters.every((f) => minOur(f.x, f.y) <= frontD + 3);
      if (isHealer) {
        const mate = fighters.filter((o) => o !== c && live(o, H) === 0 && o.hits < o.hitsMax && minOur(o.x, o.y) >= 3).sort((a, b) => (a.hits / a.hitsMax) - (b.hits / b.hitsMax))[0];
        const rng = fighters.filter((o) => live(o, R) > 0 && live(o, A) === 0).sort((a, b) => range(c, a) - range(c, b))[0];
        if (mate && range(c, mate) > 1) stepToward(c, mate, 1);
        else if (!mate && rng && (range(c, rng) > 1 || minOur(c.x, c.y) <= 3)) { if (minOur(c.x, c.y) <= 3) stepBack(c, ourArmed.filter((o) => range(c, o) <= 4)); else stepToward(c, rng, 1); }
      } else if (live(c, A) > 0) {
        const soft = ourF.filter((o) => live(o, A) === 0 && range(c, o) <= 2).sort((a, b) => range(c, a) - range(c, b))[0];
        const tgt = soft || nearest;
        if (tgt && range(c, tgt) > 1 && (formed || minOur(c.x, c.y) > frontD)) stepToward(c, tgt, 1);
      } else if (nearest) {
        const d = minOur(c.x, c.y);
        if (d <= 2) { if (!stepBack(c, ourArmed.filter((o) => range(c, o) <= 3))) stepAway(c, ourArmed.filter((o) => range(c, o) <= 3)); }
        else if (d > 3 && !formed && d > frontD + 1) stepToward(c, nearest, 3);   // a laggard paths up to the front (a greedy step stalls behind terrain — m28/m31 froze without a shot)
        else if (d > 3 && (formed || d > frontD)) {
          // the neighbour cell at three from our nearest armed creep and no closer to any other, nearest to the blob's front
          let best = null, bs = 1e9;
          for (const dx of [-1, 0, 1]) for (const dy of [-1, 0, 1]) {
            const x = c.x + dx, y = c.y + dy;
            if (!inBounds(x, y) || world.terrain[idx(x, y)] === 1 || (creepAt(x, y) && creepAt(x, y) !== c)) continue;
            const m = minOur(x, y);
            if (m < 3) continue;
            const sc = (m - 3) * 10 + Math.max(Math.abs(x - blobC.x), Math.abs(y - blobC.y)) * 0.1;
            if (sc < bs) { bs = sc; best = { x, y }; }
          }
          if (best && (best.x !== c.x || best.y !== c.y)) c.move(getDirection(best.x - c.x, best.y - c.y));
        }
      }
    } else if (has('rush') || has('sleeper')) {
      // whole army marches at our army; melee closes, ranged keeps 2, healers two cells behind the most damaged mate
      // and away from our armed creeps within 2 — as the live opponents keep theirs (matches 3, 9); a healer glued
      // to the front made the stub's rush stronger than any live army and rewarded front-row healers on our side
      if (live(c, H) > 0 && live(c, A) === 0 && live(c, R) === 0) {
        const mate = fighters.filter((o) => o !== c && live(o, H) === 0).sort((a, b) => (a.hits / a.hitsMax) - (b.hits / b.hitsMax))[0];
        const threat = ours.filter((o) => !isRunner(o) && live(o, A) + live(o, R) > 0 && range(c, o) <= 2);
        if (threat.length) stepAway(c, threat);
        else if (mate) stepToward(c, mate, 2);
      } else if (nearestOur) stepToward(c, nearestOur, live(c, A) > 0 ? 1 : 2);
      else stepToward(c, ourCentroid, 2);
    } else if (has('greedy')) {
      // army blob sweeps flags one by one; fights whatever it meets on the way
      const target = flags.filter((f) => f.owner !== 1).sort((a, b) => range(c, a) - range(c, b))[0];
      const intruder = ours.filter((o) => range(c, o) <= 5).sort((a, b) => range(c, a) - range(c, b))[0];
      if (intruder && !isRunner(c)) stepToward(c, intruder, live(c, A) > 0 ? 1 : 2);
      else if (target) stepToward(c, target, c === fighters[0] || fighters.indexOf(c) % 4 === 0 ? 0 : 2);
    } else if (has('scatter')) {
      // 'scatter' (live match 240, ricardo18informatica2020, 17355:23708 with both armies whole): from the first tick every
      // creep walks to a post of its own — by the replay: a melee and a ranged garrison his R3 (t=39), a ranged and a healer
      // the A3 nearer to us (53), a melee and a ranged the other A3 (60); a trio (melee, ranged, healer) takes D5 (37) and
      // then tours A3 -> A3 -> R3 -> D5 with three hundred ticks at each; the last melee and ranged sit at the nearer A3
      // until t=400 and then join the R3 garrison (his largest group four of nine for half the match); the runners take the
      // two H4 corners (78, 84) and sit. Six flags by t=86 against our one. Nobody fires (the ledger stayed 0); a creep steps
      // away from our armed creeps within six and walks back when they leave — our army took the far A3 at t=419 from a
      // garrison that had stepped aside, and the flag went back the moment it left
      if (!armyState.posts) {
        const home = { x: Math.round(mine.reduce((s, c) => s + c.x, 0) / mine.length), y: Math.round(mine.reduce((s, c) => s + c.y, 0) / mine.length) };
        const d5 = flags.find((f) => f.effectType === 'eff_damage_taken_modifier') || flags[0];
        const r3s = flags.filter((f) => f.effectType === 'eff_ranged_attack_modifier').sort((a, b) => range(home, a) - range(home, b));
        const a3s = flags.filter((f) => f.effectType === 'eff_attack_modifier').sort((a, b) => range(ourCentroid, a) - range(ourCentroid, b));
        const hisR3 = r3s[0] || d5, nearA3 = a3s[0] || d5, farA3 = a3s[1] || nearA3;
        const melee = fighters.filter((f) => live(f, A) > 0), ranged = fighters.filter((f) => live(f, R) > 0 && live(f, A) === 0);
        const healers = fighters.filter((f) => live(f, H) > 0 && live(f, A) === 0 && live(f, R) === 0);
        armyState.posts = new Map();
        const set = (f, tour) => { if (f) armyState.posts.set(f.id, tour); };
        set(melee[0], [hisR3]); set(ranged[0], [hisR3]);
        set(ranged[1], [nearA3]); set(healers[0], [nearA3]);
        set(melee[1], [farA3]); set(ranged[2], [farA3]);
        const tour = [d5, farA3, nearA3, hisR3];
        set(melee[2], tour); set(ranged[3], tour); set(healers[1], tour);
        set(melee[3], [nearA3, hisR3]); set(ranged[4], [nearA3, hisR3]); set(healers[2], [nearA3]);
      }
      const tour = armyState.posts.get(c.id) || [flags[0]];
      const post = tour.length === 4 ? tour[Math.floor((world.tick - 1) / 300) % 4] : tour.length === 2 ? (world.tick < 400 ? tour[0] : tour[1]) : tour[0];
      const threat = ours.filter((o) => live(o, A) + live(o, R) > 0 && range(c, o) <= 6);
      if (threat.length) stepAway(c, threat);
      else { const occ = creepAt(post.x, post.y); stepToward(c, post, !occ || occ === c ? 0 : 1); }
    } else if (has('spread')) {
      // 'spread' (match 19): every creep takes a flag of its own — the i-th creep the i-th flag, two per flag — sits on
      // it, steps away from our armed creeps within 6 and returns when they leave; it never fights as an army
      const post = flags[fighters.indexOf(c) % flags.length];
      const threat = ours.filter((o) => live(o, A) + live(o, R) > 0 && range(c, o) <= 6);
      if (threat.length) stepAway(c, threat);
      else stepToward(c, post, 0);
    } else if (has('farm') || has('camp')) {
      // 'farm' (live match 26): the army moves as ONE blob to the flag nearest the blob that it does not already own,
      // and never engages — a creep with one of our armed creeps within six steps away and comes back after. Its two
      // runners each sit on a flag of their own. That match ended with both armies at full strength — 902 hits of
      // damage in 1500 ticks and not one death — and it won on points 23408:12721 while our army chased the one
      // catchable straggler around the middle and let everything it captured be walked back onto
      // 'camp' (live match 70, けろびー v5 again): the same farmer until every flag it can take is its own — then the whole
      // blob parks on the centre flag and never moves again, not even away from our armed creeps. Live it sat there with
      // twelve at 0.6 of its power (2954 against our 4179) for a thousand ticks while our army stood ten to thirteen cells
      // away: the intercept's "a farmer is not chased" had nothing left to intercept, and the push flickered with the
      // eight-cell reach boundary — 3174:22934 without a shot from either side
      const takeable = flags.filter((f) => f.owner !== 1 && !ours.some((o) => o.x === f.x && o.y === f.y));
      const camping = has('camp') && takeable.length === 0;
      // '+shy' (live matches 133, 152, 159 — けろびー's camper of 05–06.09.2026): the parked blob steps aside when our armed creeps
      // come within six and walks back onto the flag when they are gone; live it sat on D5 at 0.6 of its power, our army
      // at 1.3 flickered ANNIHILATE/HOLD and walked to the post forty cells away on every HOLD, and it retook D5 each time
      // (12009:24099). The plain camp never moves — the stub's push destroys it at t=400–700, the live one was never reached
      const threat = ours.filter((o) => live(o, A) + live(o, R) > 0 && range(c, o) <= 6);
      if (threat.length && (!camping || has('shy'))) stepAway(c, threat);
      else if (isRunner(c)) {
        const free = flags.filter((f) => f.owner !== 1).sort((a, b) => range(c, a) - range(c, b));
        const post = free[Math.min(runners.indexOf(c), free.length - 1)];
        if (post) stepToward(c, post, 0);
      } else if (fighters.length) {
        const blob = { x: Math.round(fighters.reduce((s, f) => s + f.x, 0) / fighters.length),
                       y: Math.round(fighters.reduce((s, f) => s + f.y, 0) / fighters.length) };
        const centre = flags.slice().sort((a, b) => range(a, { x: 49, y: 49 }) - range(b, { x: 49, y: 49 }))[0];
        // the nearest TAKEABLE flag (06.09.2026): the blob used to head for the nearest flag that was not its own even with one
        // of our creeps standing on it, and with our army posted next to its nearest flag (v100, the opening at the post) it
        // danced at that flag for the whole match instead of farming the rest and parking (gate m30/m31 camp lost on points)
        const post = camping ? centre : takeable.slice().sort((a, b) => range(blob, a) - range(blob, b))[0];
        if (post) stepToward(c, post, fighters.indexOf(c) === 0 ? 0 : 2);
      }
    } else if (has('roost')) {
      // 'roost' (match 25): like 'spread', but the creep never leaves the flag — it does not even step away from ours.
      // The live opponent of match 25 held all seven flags by t=80 and never moved again (our own log reported
      // `passive=true`, i.e. every combat enemy stationary), and our army then froze eleven cells short of the
      // nearest of them for a thousand ticks
      const post = flags[fighters.indexOf(c) % flags.length];
      stepToward(c, post, 0);
    } else if (has('grab')) {
      // guard own-side flags (x > 60); chase intruders within 6
      const post = flags.filter((f) => f.x > 60).sort((a, b) => range(c, a) - range(c, b))[0] || flags[0];
      const intruder = ours.filter((o) => range(o, post) <= 6).sort((a, b) => range(c, a) - range(c, b))[0];
      if (intruder) stepToward(c, intruder, live(c, A) > 0 ? 1 : 2);
      else stepToward(c, post, fighters.indexOf(c) === 0 ? 0 : 1);
    }
  }
}

// ---------- run ----------
const lines = [];
let loopErrors = 0;
// a crash inside loop() is printed by the bot's runWithSourceMapSupport as the mapped stack trace under an (often empty)
// message line, so it never starts with 'loop error' — the first frame of every trace counts as one error. Before this
// the stand reported errors=0 on runs that threw every tick for 1500 ticks (m19 nine, 05.09.2026)
const isLoopError = (s) => s.startsWith('loop error') || /^\s+at captureStack \(/.test(s);
const origWrite = process.stdout.write.bind(process.stdout);
let buf = '';
process.stdout.write = (chunk) => {
  buf += typeof chunk === 'string' ? chunk : chunk.toString();
  let i;
  while ((i = buf.indexOf('\n')) >= 0) {
    const s = buf.slice(0, i);
    buf = buf.slice(i + 1);
    lines.push(s);
    if (isLoopError(s)) loopErrors++;
  }
  return true;
};
const origLog = (...args) => origWrite(args.join(' ') + '\n');
console.log = (...args) => { const s = args.join(' '); lines.push(s); if (isLoopError(s)) loopErrors++; };
const bot = await import(BOT);
const t0 = Date.now();
// ours act: the bot's own uptime, counted the way tools/replay.py counts it live — healer creep-ticks with a wounded mate
// within one and heals given, melee creep-ticks with an enemy within one and swings, ranged creep-ticks with an enemy within
// three and shots (matches 238/249 live: healers 81–94 %, melee 47–83 % of only 18–23 creep-ticks against his 59–75, ranged
// 70–76 %; his 100 % / 86–97 % / 89–100 %). The melee adjacency itself — how often our melee ARE adjacent — is the gap
const oAct = { h_can: 0, h_did: 0, m_can: 0, m_did: 0, m_ticks: 0, r_can: 0, r_did: 0, r_ticks: 0 };
function oursAct() {
  const c0 = creeps().filter((c) => c.owner === 0 && !c.spawning), c1 = creeps().filter((c) => c.owner === 1 && !c.spawning);
  if (!c1.some((e) => c0.some((c) => range(c, e) <= 8))) return;   // in contact only
  for (const c of c0) {
    const m = world.intents.get(c.id) || {};
    const isH = live(c, H) > 0 && live(c, A) === 0 && live(c, R) === 0;
    if (isH) { if (c0.some((o) => o !== c && o.hits < o.hitsMax && range(c, o) <= 1)) { oAct.h_can++; if (m.heal && range(c, m.heal.target) <= 1) oAct.h_did++; } }
    else if (live(c, A) > 0) { oAct.m_ticks++; if (c1.some((e) => range(c, e) <= 1)) { oAct.m_can++; if (m.melee) oAct.m_did++; } }
    else if (live(c, R) > 0) { oAct.r_ticks++; if (c1.some((e) => range(c, e) <= 3)) { oAct.r_can++; if (m.ranged) oAct.r_did++; } }
  }
}
let ended = '';
let cpuMax = 0, cpuMaxTick = 0, cpuSlow = 0;
for (let t = 1; t <= ticks; t++) {
  world.perspective = 0;
  const tLoop = performance.now();
  try { bot.loop(); } catch (e) { loopErrors++; lines.push('loop error (uncaught): ' + (e && e.stack || e)); }
  const msLoop = performance.now() - tLoop;
  oursAct();
  if (msLoop > cpuMax) { cpuMax = msLoop; cpuMaxTick = t; }
  if (msLoop > 50) cpuSlow++;
  enemyTick();
  step(Resource);
  const c0 = creeps().filter((c) => c.owner === 0), c1 = creeps().filter((c) => c.owner === 1);
  if (TRACE && t >= TRACE[0] && t <= TRACE[1]) {
    // per-tick positions: ours as x,y[/fatigue], the nearest enemy's range and position — for reading a chase
    const near = (c) => c1.reduce((b, e) => (range(c, e) < range(c, b) ? e : b), c1[0]);
    origLog(`trace t=${t} ours ${c0.map((c) => `${c.summary().replace(/\s.*/, '')}@${c.x},${c.y}${c.fatigue ? '/' + c.fatigue : ''}`).join(' ')} | enemy ${c1.map((c) => `${c.summary().replace(/\s.*/, '')}@${c.x},${c.y}`).join(' ')} | gap ${c0.length && c1.length ? Math.min(...c0.map((c) => range(c, near(c)))) : '-'}`);
  }
  if (c0.length === 0) { ended = `our army destroyed at t=${world.tick - 1}`; break; }
  if (c1.length === 0) { ended = `enemy army destroyed at t=${world.tick - 1}`; break; }
  const remaining = world.ticksLimit - (world.tick - 1);
  if (Math.abs(world.score[0] - world.score[1]) > world.maxScorePerTick * remaining) { ended = `unreachable lead at t=${world.tick - 1}`; break; }
  if (t % 100 === 0) {
    const flags = world.objects.filter((o) => o.exists && o.kind === 'flag').map((f) => (f.owner === 0 ? '+' : f.owner === 1 ? '-' : '0')).join('');
    origLog(`cpu t=${t}: max=${cpuMax.toFixed(1)}ms at t=${cpuMaxTick} slow(>50ms)=${cpuSlow}`);
    const sum = (cs) => { const m = {}; for (const c of cs) { const s = c.summary(); m[s] = (m[s] || 0) + 1; } return Object.entries(m).map(([k, v]) => `${k}x${v}`).join(' '); };
    origLog(`t=${t} score=${world.score[0]}/${world.score[1]} flags=${flags} ours(${c0.length}): ${sum(c0)} | enemy(${c1.length}): ${sum(c1)} errors=${loopErrors}`);
  }
}
const outDir = fileURLToPath(new URL('out/', import.meta.url));
mkdirSync(outDir, { recursive: true });
const log = `${outDir}run-${process.env.LOGTAG || ""}${scenario.join('+')}${process.env.SLEEP ? '-' + process.env.SLEEP : ''}.log`;
writeFileSync(log, lines.join('\n') + '\n\n=== EVENTS ===\n' + world.events.join('\n') + '\n');
const c0 = creeps().filter((c) => c.owner === 0).length, c1 = creeps().filter((c) => c.owner === 1).length;
origLog(`done: ${ended || `${ticks} ticks`} score=${world.score[0]}/${world.score[1]} alive=${c0}/${c1} errors=${loopErrors} time=${((Date.now() - t0) / 1000).toFixed(1)}s log=${log}`);
const pc = (a, b) => `${a}/${b} (${b ? Math.round(100 * a / b) : 0}%)`;
origLog(`ours act: healers adjacent-to-wounded ${pc(oAct.h_did, oAct.h_can)}, melee adjacent ${pc(oAct.m_did, oAct.m_can)} of ${oAct.m_ticks} melee creep-ticks in contact (${oAct.m_ticks ? Math.round(100 * oAct.m_can / oAct.m_ticks) : 0}% adjacent), ranged with target in 3 ${pc(oAct.r_did, oAct.r_can)} of ${oAct.r_ticks} (${oAct.r_ticks ? Math.round(100 * oAct.r_can / oAct.r_ticks) : 0}% in reach)`);
origLog(`enemy conc: ticks with shots ${eConc.ticks}; most shots on one target per tick 1:${eConc.hist[1]} 2:${eConc.hist[2]} 3:${eConc.hist[3]} 4:${eConc.hist[4]} 5+:${eConc.hist[5]}; 4+ in ${eConc.ticks ? Math.round(100 * (eConc.hist[4] + eConc.hist[5]) / eConc.ticks) : 0} %`);
const errs = lines.filter((l) => l.startsWith('loop error'));
if (errs.length) origLog('first error:\n' + errs.slice(0, 2).join('\n'));
