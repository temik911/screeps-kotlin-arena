import { world, terrainAt, range as rangeOf, byId } from '../world.mjs';
import { searchPath } from './path-finder.mjs';
import { ConstructionSite } from './prototypes/construction-site.mjs';
import { CONSTRUCTION_COST } from './constants.mjs';

export function getObjectsByPrototype(proto) {
  return world.objects.filter((o) => o.exists && o instanceof proto);
}
export function getObjects() { return world.objects.filter((o) => o.exists); }
export function getObjectById(id) { return byId(id); }
export function getRange(a, b) { return rangeOf(a, b); }
export function getTerrainAt(pos) { return terrainAt(pos.x, pos.y); }
export function getTicks() { return world.tick; }
export function getCpuTime() { return 0; }
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
export function findClosestByPath(from, arr) { return findClosestByRange(from, arr); }
export function findPath(from, to, opts) { return searchPath(from, to, opts).path; }
// construction (docs/escort-run-redteam.md): prices of the runtime's CONSTRUCTION_COST; a site is the owner's, one per
// cell, never on terrain walls or on a blocking structure (a rampart may go over anything, even a creep)
const SITE_COST = CONSTRUCTION_COST; // one table: what the bot is told is what a site costs
export function createConstructionSite(a, b, c) {
  const pos = typeof a === 'number' ? { x: a, y: b } : a;
  const proto = typeof a === 'number' ? c : b;
  const cost = proto && SITE_COST[proto.name];
  if (!pos || cost === undefined) return { error: -10 };
  if (terrainAt(pos.x, pos.y) === 1) return { error: -10 };
  const here = world.objects.filter((o) => o.exists && o.x === pos.x && o.y === pos.y);
  if (here.some((o) => o.kind === 'site')) return { error: -8 };
  const structs = here.filter((o) => o.kind !== 'creep' && o.kind !== 'site' && o.kind !== 'resource' && o.kind !== 'flag');
  if (proto.name === 'StructureRampart' ? structs.some((o) => o.kind === 'rampart') : structs.some((o) => o.kind !== 'rampart')) return { error: -10 };
  const site = new ConstructionSite(pos.x, pos.y, world.perspective, cost);
  site.proto = proto;
  world.objects.push(site);
  return { object: site };
}
