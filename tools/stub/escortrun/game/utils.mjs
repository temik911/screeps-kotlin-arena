import { world, terrainAt, range as rangeOf, byId } from '../world.mjs';
import { searchPath } from './path-finder.mjs';

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
export function createConstructionSite() { return { error: -10 }; }
