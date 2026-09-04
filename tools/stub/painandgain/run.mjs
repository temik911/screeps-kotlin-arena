// Offline runner for Pain and Gain (fixed armies, no spawns): a map (synthetic, or MAP=map-matchN.txt dumped from a
// match log) + a scripted enemy. Usage (see README.md and docs/pain-and-gain.md):
//   node --import ./register.mjs run.mjs <ticks> none|scouts|grab|rush|greedy|army|hunter|kite|sleeper|nine|roost|farm
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
  for (const [x, y, body] of (swap ? MATCH1_OURS : MATCH1_ENEMY)) world.objects.push(new Creep(x, y, 1, body));
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
function isRunner(c) { return c.body.every((p) => p.type === M); }
// 'nine' (match 9 opponent): our healers first, then the lowest hits — two of ours lost every HEAL part by tick 140
const healerOf = (o) => live(o, H) > 0 && live(o, A) === 0 && live(o, R) === 0;
// 'twelve' hunts the way 'nine' does (healers first, single-target fire, its healers behind), after a roam
// 'fourteen' is 'nine' plus rotation: a fighter below half hits steps back to its healers and returns healed
const NINE = has('nine') || has('twelve') || has('fourteen') || has('block') || has('wing');
const ROTATE_OUT = 0.5, ROTATE_IN = 0.9;
const rotating = new Set();
const targetKey = (o) => (NINE && healerOf(o) ? 0 : 1) * 100000 + o.hits;
function fireAt(c, ours) {
  const inRange = ours.filter((o) => range(c, o) <= 3);
  if (live(c, R) > 0 && inRange.length) {
    const close = inRange.filter((o) => range(c, o) <= 2);
    if (close.length >= 2 && !NINE) c.rangedMassAttack();
    else c.rangedAttack(inRange.sort((a, b) => targetKey(a) - targetKey(b))[0]);
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
function blockMove(c, plan, fighters, ours) {
  const { anchor, dir, isH, isR } = plan;
  const healers = fighters.filter((o) => o !== c && live(o, H) > 0);
  if (!isH(c) && rotate(c, fighters, ours)) return;
  const ourF = ours.filter((o) => !isRunner(o));
  const nearest = ourF.sort((a, b) => range(c, a) - range(c, b))[0];
  if (isH(c)) {
    const mate = fighters.filter((o) => o !== c && live(o, H) === 0 && o.hits < o.hitsMax).sort((a, b) => (a.hits / a.hitsMax) - (b.hits / b.hitsMax))[0];
    const threat = ourF.filter((o) => live(o, A) + live(o, R) > 0 && range(c, o) <= 3);
    const slot = { x: anchor.x - 3 * dir.x, y: anchor.y - 3 * dir.y };
    if (threat.length) stepAway(c, threat);
    else if (mate && range(mate, anchor) > 1 && range(c, mate) > 1) stepToward(c, mate, 1);
    else if (range(c, slot) > 1) stepToward(c, slot, 1);
    return;
  }
  const wing = has('wing');
  if (isR(c)) {
    const armedNear = ourF.some((o) => live(o, A) + live(o, R) > 0 && range(c, o) <= 2);
    const back = wing && !armedNear ? 0 : 2;
    const slot = { x: anchor.x - back * dir.x, y: anchor.y - back * dir.y };
    const close = ourF.filter((o) => live(o, A) > 0 && range(c, o) <= 1);
    if (close.length) stepAway(c, close);
    else if (range(c, slot) > (back === 0 ? 0 : 1)) stepToward(c, slot, back === 0 ? 0 : 1);
    return;
  }
  // melee: hold the front row — attack what is adjacent, otherwise advance with the block (never more than two from it);
  // the wing's line stops three from our nearest creep and its melee only step to what is within two
  if (nearest && range(c, nearest) <= 1) return;
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
  const flags = world.objects.filter((o) => o.exists && o.kind === 'flag');
  const mine = creeps().filter((c) => c.owner === 1);
  const ours = creeps().filter((c) => c.owner === 0);
  const runners = mine.filter(isRunner);
  const fighters = mine.filter((c) => !isRunner(c));
  // runners: nearest flag not theirs (sticky), then sit
  for (const [id] of runnerFlag) if (!mine.some((c) => c.id === id)) runnerFlag.delete(id);
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
  if ((has('army') || has('hunter') || has('nine') || has('twelve') || has('fourteen') || has('block') || has('wing')) && fighters.length) {
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
    if (has('nine') || has('fourteen') || has('block') || has('wing')) { armyState.waypoints = [ourCentroid]; armyState.phase = 0; if (range(cen, ourCentroid) <= 6) armyState.hunting = true; }
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
  const blockPlan = (has('block') || has('wing')) && armyMode === 'fight' ? planBlock(fighters, ours, ourCentroid) : null;
  for (const c of fighters) {
    fireAt(c, ours);
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
    if (has('rush') || has('sleeper')) {
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
    } else if (has('spread')) {
      // 'spread' (match 19): every creep takes a flag of its own — the i-th creep the i-th flag, two per flag — sits on
      // it, steps away from our armed creeps within 6 and returns when they leave; it never fights as an army
      const post = flags[fighters.indexOf(c) % flags.length];
      const threat = ours.filter((o) => live(o, A) + live(o, R) > 0 && range(c, o) <= 6);
      if (threat.length) stepAway(c, threat);
      else stepToward(c, post, 0);
    } else if (has('farm')) {
      // 'farm' (live match 26): the army moves as ONE blob to the flag nearest the blob that it does not already own,
      // and never engages — a creep with one of our armed creeps within six steps away and comes back after. Its two
      // runners each sit on a flag of their own. That match ended with both armies at full strength — 902 hits of
      // damage in 1500 ticks and not one death — and it won on points 23408:12721 while our army chased the one
      // catchable straggler around the middle and let everything it captured be walked back onto
      const threat = ours.filter((o) => live(o, A) + live(o, R) > 0 && range(c, o) <= 6);
      if (threat.length) stepAway(c, threat);
      else if (isRunner(c)) {
        const free = flags.filter((f) => f.owner !== 1).sort((a, b) => range(c, a) - range(c, b));
        const post = free[Math.min(runners.indexOf(c), free.length - 1)];
        if (post) stepToward(c, post, 0);
      } else if (fighters.length) {
        const blob = { x: Math.round(fighters.reduce((s, f) => s + f.x, 0) / fighters.length),
                       y: Math.round(fighters.reduce((s, f) => s + f.y, 0) / fighters.length) };
        const post = flags.filter((f) => f.owner !== 1).sort((a, b) => range(blob, a) - range(blob, b))[0];
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
const origWrite = process.stdout.write.bind(process.stdout);
let buf = '';
process.stdout.write = (chunk) => {
  buf += typeof chunk === 'string' ? chunk : chunk.toString();
  let i;
  while ((i = buf.indexOf('\n')) >= 0) {
    const s = buf.slice(0, i);
    buf = buf.slice(i + 1);
    lines.push(s);
    if (s.startsWith('loop error')) loopErrors++;
  }
  return true;
};
const origLog = (...args) => origWrite(args.join(' ') + '\n');
console.log = (...args) => { const s = args.join(' '); lines.push(s); if (s.startsWith('loop error')) loopErrors++; };
const bot = await import(BOT);
const t0 = Date.now();
let ended = '';
let cpuMax = 0, cpuMaxTick = 0, cpuSlow = 0;
for (let t = 1; t <= ticks; t++) {
  world.perspective = 0;
  const tLoop = performance.now();
  try { bot.loop(); } catch (e) { loopErrors++; lines.push('loop error (uncaught): ' + (e && e.stack || e)); }
  const msLoop = performance.now() - tLoop;
  if (msLoop > cpuMax) { cpuMax = msLoop; cpuMaxTick = t; }
  if (msLoop > 50) cpuSlow++;
  enemyTick();
  step(Resource);
  const c0 = creeps().filter((c) => c.owner === 0), c1 = creeps().filter((c) => c.owner === 1);
  if (TRACE && t >= TRACE[0] && t <= TRACE[1]) {
    // per-tick positions: ours as x,y[/fatigue], the nearest enemy's range and position — for reading a chase
    const near = (c) => c1.reduce((b, e) => (range(c, e) < range(c, b) ? e : b), c1[0]);
    origLog(`trace t=${t} ours ${c0.map((c) => `${c.summary().replace(/\s.*/, '')}@${c.x},${c.y}${c.fatigue ? '/' + c.fatigue : ''}`).join(' ')} | enemy ${c1.map((c) => `${c.x},${c.y}`).join(' ')} | gap ${c0.length && c1.length ? Math.min(...c0.map((c) => range(c, near(c)))) : '-'}`);
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
const errs = lines.filter((l) => l.startsWith('loop error'));
if (errs.length) origLog('first error:\n' + errs.slice(0, 2).join('\n'));
