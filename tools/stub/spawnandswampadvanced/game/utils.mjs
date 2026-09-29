// game/utils for the Spawn and Swamp ADVANCED stub — a copy of tools/stub/escortrunadvanced/game/utils.mjs with the
// runtime's documented findPath (arena-docs, game/utils): "Unlike searchPath, findPath avoid all obstacles by default
// (unless costMatrix is specified)" — creeps, the OBSTACLE_OBJECT_TYPES structures and the other side's ramparts, minus
// `opts.ignore`. The escort-run copy walked findPath/moveTo through walls and creeps (terrain only), which here would
// march a builder into the StructureWall frame of a vault. The bot relies on the documented split: its own CostMatrix
// "switches off the avoidance" (docs/spawn-and-swamp-advanced.md, v28), so it marks creeps and structures itself.
import { writeFileSync } from 'node:fs';
import { world, terrainAt, range as rangeOf, byId, inBounds } from '../world.mjs';
import { searchPath, CostMatrix } from './path-finder.mjs';
import { ConstructionSite } from './prototypes/construction-site.mjs';
import { CONSTRUCTION_COST, MAX_CONSTRUCTION_SITES, OBSTACLE_OBJECT_TYPES } from './constants.mjs';

export function getObjectsByPrototype(proto) {
  return world.objects.filter((o) => o.exists && o instanceof proto);
}
export function getObjects() { return world.objects.filter((o) => o.exists); }
export function getObjectById(id) { return byId(id); }
export function getRange(a, b) { return rangeOf(a, b); }
export function getTerrainAt(pos) { return terrainAt(pos.x, pos.y); }
export function getTicks() { return world.tick; }
// CPUCLOCK=1 — the bot's own cpu readings are real time since its loop() started this tick (nanoseconds, as live); off by
// default, and then 0, so logs stay deterministic (the basic stub's rule)
export function getCpuTime() { return (process.env.CPUCLOCK && world.tickStartNs) ? Number(process.hrtime.bigint() - world.tickStartNs) : 0; }
export function getHeapStatistics() { return {}; }
export function getDirection(dx, dy) {
  dx = Math.sign(dx); dy = Math.sign(dy);
  if (dx === 0 && dy === -1) return 1;
  if (dx === 1 && dy === -1) return 2;
  if (dx === 1 && dy === 0) return 3;
  if (dx === 1 && dy === 1) return 4;
  if (dx === 0 && dy === 1) return 5;
  if (dx === -1 && dy === 1) return 6;
  if (dx === -1 && dy === 0) return 7;
  if (dx === -1 && dy === -1) return 8;
  return 0;
}
export function findInRange(from, arr, r) { return arr.filter((o) => rangeOf(from, o) <= r); }
export function findClosestByRange(from, arr) { let best = null, bd = Infinity; for (const o of arr) { const d = rangeOf(from, o); if (d < bd) { bd = d; best = o; } } return best; }
export function findClosestByPath(from, arr, opts) {
  let best = null, bc = Infinity;
  for (const o of arr) { const r = findPathResult(from, o, opts); if (!r.incomplete && r.cost < bc) { bc = r.cost; best = o; } }
  return best;
}

const OBSTACLE_KIND = new Set(['creep', 'tower', 'wall', 'spawn', 'extension']);
// the matrix is the same for every mover of one side within one side's turn (nothing moves until the tick is processed):
// built once per (tick, turn, owner) and cloned per call, with the ignored objects' cells put back to the terrain
let obsCache = { key: '', cm: null };
/** The matrix of what findPath avoids for a mover of `owner`: obstacles and the other side's ramparts. */
export function obstacleMatrix(owner, ignore) {
  const key = `${world.tick}:${world.perspective}:${owner}:${world.objects.length}`;
  if (obsCache.key !== key) {
    const cm = new CostMatrix();
    for (const o of world.objects) {
      if (!o.exists || !inBounds(o.x, o.y)) continue;
      if (o.kind === 'creep' && o.spawning) continue;
      if (OBSTACLE_KIND.has(o.kind) || (o.kind === 'rampart' && o.owner !== owner)) cm.set(o.x, o.y, 255);
    }
    obsCache = { key, cm };
  }
  const cm = obsCache.cm.clone();
  for (const o of ignore || []) {
    if (!o || !inBounds(o.x, o.y)) continue;
    // put the cell back unless another obstacle stands there too
    const other = world.objects.some((q) => q !== o && q.exists && q.x === o.x && q.y === o.y && !(q.kind === 'creep' && q.spawning) &&
      (OBSTACLE_KIND.has(q.kind) || (q.kind === 'rampart' && q.owner !== owner)));
    if (!other) cm.set(o.x, o.y, 0);
  }
  return cm;
}
/** findPath as World's Room.findPath (game/rooms.js _findPath2) — the Arena's does the same: the search goes to RANGE 1 of
 *  the target (or opts.range), and without opts.range the target cell itself is appended when the path ends beside it.
 *  Measured: our starting worker's recorded steps to its slot in 45 stored matches — 1632 of 1632 steps agree with this,
 *  1584 of 1632 with a search to the target cell itself (the difference is in the last steps, where the range-1 goal lets
 *  the heap's tie order pick a diagonal cell — live `(66,4) -> (65,3) -> (66,2)`, not `(66,3)`). */
function findPathResult(from, to, opts = {}) {
  opts = opts || {};
  if (from.x === to.x && from.y === to.y) return { path: [], ops: 0, cost: 0, incomplete: false };
  const owner = from.owner !== undefined ? from.owner : world.perspective;
  const cm = opts.costMatrix || obstacleMatrix(owner, [from, ...(opts.ignore || [])]);
  const r = searchPath(from, { pos: { x: to.x, y: to.y }, range: Math.max(1, opts.range || 0) }, { ...opts, costMatrix: cm });
  const near = (p) => Math.max(Math.abs(p.x - to.x), Math.abs(p.y - to.y)) <= 1;
  const last = r.path[r.path.length - 1];
  if (!opts.range && ((last && near(last) && !(last.x === to.x && last.y === to.y)) || (!last && near(from)))) r.path.push({ x: to.x, y: to.y });
  return r;
}
export function findPath(from, to, opts) { return findPathResult(from, to, opts).path; }
export { findPathResult };
world.api = { findPath, findClosestByPath, dump: (f, o) => writeFileSync(f, JSON.stringify(o)) }; // dump: the MOVEDBG/MOVEDUMP probe

// construction: prices of the runtime's CONSTRUCTION_COST; a site belongs to the side that runs; one site per cell; never on
// a terrain wall; a rampart goes over anything but a rampart, another structure only on a cell without an obstacle
// structure; MAX_CONSTRUCTION_SITES a side (World's createConstructionSite checks; ERR_INVALID_TARGET -7, ERR_FULL -8,
// ERR_INVALID_ARGS -10). A site may go under a creep — only its building waits (build.js: an obstacle is not raised over a
// creep; the bot's v6-v7 note: "пока наш крип стоит на клетке, площадка не достраивается")
export function createConstructionSite(a, b, c) {
  const pos = typeof a === 'number' ? { x: a, y: b } : a;
  const proto = typeof a === 'number' ? c : b;
  const cost = proto && CONSTRUCTION_COST[proto.name];
  if (!pos || cost === undefined || !inBounds(pos.x, pos.y)) return { error: -10 };
  if (terrainAt(pos.x, pos.y) === 1) return { error: -7 };
  const here = world.objects.filter((o) => o.exists && o.x === pos.x && o.y === pos.y);
  // the check sees the cell as it was at the start of the tick: live, v58 placed a rampart site and a tower site on (48,20)
  // in the same tick 757 (6abc107c) and both stood there — the tower's was built first (762-979), the rampart's after it
  if (here.some((o) => o.kind === 'site' && o.placedAt !== world.tick)) return { error: -7 };
  const structs = here.filter((o) => o.kind !== 'creep' && o.kind !== 'site' && o.kind !== 'resource' && o.kind !== 'flag');
  if (proto.name === 'StructureRampart' ? structs.some((o) => o.kind === 'rampart') : structs.some((o) => o.kind !== 'rampart')) return { error: -7 };
  if (world.objects.filter((o) => o.exists && o.kind === 'site' && o.owner === world.perspective).length >= MAX_CONSTRUCTION_SITES) return { error: -8 };
  const site = new ConstructionSite(pos.x, pos.y, world.perspective, cost);
  site.proto = proto;
  site.placedAt = world.tick;
  world.objects.push(site);
  world.events.push(`t=${world.tick} ${world.perspective === 0 ? 'ours' : 'enemy'} site ${proto.name} at (${pos.x},${pos.y})`);
  return { object: site };
}
