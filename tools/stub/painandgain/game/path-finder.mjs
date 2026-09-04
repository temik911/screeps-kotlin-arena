import { terrainAt, inBounds, idx, world } from '../world.mjs';

export class CostMatrix {
  constructor() { this.bits = new Uint8Array(10000); }
  get(x, y) { return inBounds(x, y) ? this.bits[idx(x, y)] : 255; }
  set(x, y, v) { if (inBounds(x, y)) this.bits[idx(x, y)] = v; }
  clone() { const m = new CostMatrix(); m.bits = new Uint8Array(this.bits); return m; }
}

class Heap {
  constructor() { this.a = []; }
  push(k, v) { const a = this.a; a.push([k, v]); let i = a.length - 1; while (i > 0) { const p = (i - 1) >> 1; if (a[p][0] <= a[i][0]) break; [a[p], a[i]] = [a[i], a[p]]; i = p; } }
  pop() { const a = this.a; const top = a[0]; const last = a.pop(); if (a.length) { a[0] = last; let i = 0; for (;;) { const l = 2 * i + 1, r = l + 1; let m = i; if (l < a.length && a[l][0] < a[m][0]) m = l; if (r < a.length && a[r][0] < a[m][0]) m = r; if (m === i) break; [a[m], a[i]] = [a[i], a[m]]; i = m; } } return top; }
  get size() { return this.a.length; }
}

function cellCost(x, y, opts) {
  const t = terrainAt(x, y);
  if (t === 1) return 255;
  const cm = opts.costMatrix ? opts.costMatrix.get(x, y) : 0;
  if (cm === 255) return 255;
  if (cm > 0) return cm;
  return t === 2 ? (opts.swampCost ?? 10) : (opts.plainCost ?? 2);
}

/** Dijkstra from origin; goals = [{pos, range}]; flee = path to the nearest cell outside every goal's range. */
export function searchPath(origin, goal, opts = {}) {
  const goals = (Array.isArray(goal) ? goal : [goal]).map((g) => (g.pos ? { x: g.pos.x, y: g.pos.y, range: g.range ?? 0 } : { x: g.x, y: g.y, range: g.range ?? 0 }));
  const flee = !!opts.flee;
  const inGoal = (x, y) => goals.some((g) => Math.max(Math.abs(g.x - x), Math.abs(g.y - y)) <= g.range);
  const done = (x, y) => (flee ? !inGoal(x, y) : inGoal(x, y));
  const start = idx(origin.x, origin.y);
  if (done(origin.x, origin.y)) return { path: [], ops: 0, cost: 0, incomplete: false };
  const dist = new Int32Array(10000).fill(-1);
  const prev = new Int32Array(10000).fill(-1);
  const heap = new Heap();
  dist[start] = 0;
  heap.push(0, start);
  let ops = 0;
  let found = -1;
  const maxOps = opts.maxOps ?? 50000;
  while (heap.size && ops < maxOps) {
    const [d, cell] = heap.pop();
    if (d !== dist[cell]) continue;
    ops++;
    const cx = (cell / 100) | 0, cy = cell % 100;
    if (done(cx, cy)) { found = cell; break; }
    for (let dx = -1; dx <= 1; dx++) for (let dy = -1; dy <= 1; dy++) {
      if (dx === 0 && dy === 0) continue;
      const nx = cx + dx, ny = cy + dy;
      if (!inBounds(nx, ny)) continue;
      const c = cellCost(nx, ny, opts);
      if (c >= 255) continue;
      const ni = idx(nx, ny);
      const nd = d + c;
      if (dist[ni] < 0 || nd < dist[ni]) { dist[ni] = nd; prev[ni] = cell; heap.push(nd, ni); }
    }
  }
  if (found < 0) {
    // incomplete: head toward the closest explored cell to the first goal (like the real pathfinder)
    let best = -1, bd = Infinity;
    for (let i = 0; i < 10000; i++) {
      if (dist[i] < 0 || i === start) continue;
      const g = goals[0];
      const dd = Math.max(Math.abs(((i / 100) | 0) - g.x), Math.abs((i % 100) - g.y));
      const score = flee ? -dd : dd;
      if (score < bd) { bd = score; best = i; }
    }
    if (best < 0) return { path: [], ops, cost: 0, incomplete: true };
    found = best;
  }
  const path = [];
  let cell = found;
  while (cell !== start && cell >= 0) { path.push({ x: (cell / 100) | 0, y: cell % 100 }); cell = prev[cell]; }
  path.reverse();
  return { path, ops, cost: dist[found], incomplete: false };
}
