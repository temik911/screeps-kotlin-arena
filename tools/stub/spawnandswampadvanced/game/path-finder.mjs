// Path search for the Spawn and Swamp ADVANCED stub — World's native pathfinder ported to JS (the Screeps World Steam
// client's @screeps/driver/native/src/pf.cc and pf.h: jump-point A* with `heuristicWeight` 1.2, the Chebyshev heuristic to
// the nearest goal minus its range — flee: the largest shortfall —, f = g + trunc(h x weight), the pf.h binary heap and its
// tie order, the path back through jump points filled cell by cell, `incomplete` ending at the explored cell of the least
// heuristic), on one 100x100 room (the borders are x/y 0 and 99). It replaces the escort-run copy's plain Dijkstra, and
// the live numbers decide it: v58 prints its searches' costs (`opening: ... (67,1)t=38+33 ... (1,32)t=126+75`,
// `expansion: candidates (1,32)us=112/them=37 ...`, `arrival=38`, `vault ... reach us=68 them=60`) — this port reproduces
// all 27 of those numbers on the match's terrain (6abc0dc7); of the 22 in the first two lines Dijkstra misses 9 (37 for 38,
// 122+71 for 126+75, 106 for 112 ...), plain A* with the same weight 9, the port with World's 50-cell rooms (PF_ROOM=50)
// 8. PF=astar | PF=dijkstra keep the other two for comparison.
import { terrainAt, inBounds, idx } from '../world.mjs';

export class CostMatrix {
  constructor() { this.bits = new Uint8Array(10000); }
  get(x, y) { return inBounds(x, y) ? this.bits[idx(x, y)] : 255; }
  set(x, y, v) { if (inBounds(x, y)) this.bits[idx(x, y)] = v; }
  clone() { const m = new CostMatrix(); m.bits = new Uint8Array(this.bits); return m; }
}

// World's heap_t (pf.h): 1-based, priorities by index, bubble up on <=, sift down on >= — the same tie order
class Heap {
  constructor() { this.heap = [0]; this.prio = new Float64Array(10000); this.size = 0; }
  insert(i, p) { this.prio[i] = p; this.size++; this.heap[this.size] = i; this.up(this.size); }
  update(i, p) { for (let k = this.size; k > 0; k--) if (this.heap[k] === i) { this.prio[i] = p; this.up(k); return; } }
  up(k) { const h = this.heap, pr = this.prio; while (k !== 1) { const q = k >> 1; if (pr[h[k]] <= pr[h[q]]) { const t = h[k]; h[k] = h[q]; h[q] = t; k = q; } else return; } }
  pop() {
    const h = this.heap, pr = this.prio;
    const top = h[1]; const p = pr[top];
    h[1] = h[this.size]; this.size--;
    let v = 1;
    for (;;) {
      const u = v;
      if ((u << 1) + 1 <= this.size) {
        if (pr[h[u]] >= pr[h[u << 1]]) v = u << 1;
        if (pr[h[v]] >= pr[h[(u << 1) + 1]]) v = (u << 1) + 1;
      } else if ((u << 1) <= this.size) {
        if (pr[h[u]] >= pr[h[u << 1]]) v = u << 1;
      }
      if (u !== v) { const t = h[u]; h[u] = h[v]; h[v] = t; } else break;
    }
    return [top, p];
  }
}

const OBSTACLE = 255;
function look(x, y, opts, plain, swamp) {
  if (!inBounds(x, y)) return OBSTACLE;
  const cm = opts.costMatrix;
  if (cm) { const c = cm.get(x, y); if (c !== 0) return c >= 255 ? OBSTACLE : c; }
  const t = terrainAt(x, y);
  return t === 1 ? OBSTACLE : t === 2 ? swamp : plain;
}

// TOP, TOP_RIGHT, RIGHT, BOTTOM_RIGHT, BOTTOM, BOTTOM_LEFT, LEFT, TOP_LEFT — World's expansion order
const DIR = [[0, -1], [1, -1], [1, 0], [1, 1], [0, 1], [-1, 1], [-1, 0], [-1, -1]];

export function goalsOf(goal) {
  return (Array.isArray(goal) ? goal : [goal]).map((g) => (g.pos ? { x: g.pos.x, y: g.pos.y, range: g.range ?? 0 } : { x: g.x, y: g.y, range: g.range ?? 0 }));
}

/** searchPath(origin, goal | goals, opts): {path, ops, cost, incomplete}. PF=jps (default) — World's jump-point A*;
 *  PF=astar — the same A* expanding all eight neighbours; PF=dijkstra — the optimal search (weight 0). */
export function searchPath(origin, goal, opts = {}) {
  const mode = process.env.PF || 'jps';
  if (mode === 'jps') return searchJps(origin, goal, opts || {});
  return searchAstar(origin, goal, opts);
}

// ---------- World's native pathfinder, ported (pf.cc, Marcel Laverdet) ----------
// The Arena map is one 100x100 room: a "border" is x or y = 0 / 99 and "near the border" within two of it (pf.cc's
// is_border_pos / is_near_border_pos with the room size 50 replaced by 100; PF_ROOM=50 keeps World's 50 — the Arena as
// four World rooms — for the comparison in README, "Calibration").
const ROOM = parseInt(process.env.PF_ROOM || '100', 10);
const isBorder = (v) => (v + 1) % ROOM < 2;
const nearBorder = (v) => (v + 2) % ROOM < 4;
function searchJps(origin, goal, opts) {
  const goals = goalsOf(goal);
  const flee = !!opts.flee;
  const plain = opts.plainCost ?? 2, swamp = opts.swampCost ?? 10;
  const weight = Math.min(9, Math.max(1, opts.heuristicWeight || 1.2));
  const maxOps = opts.maxOps ?? 50000, maxCost = opts.maxCost ?? Infinity;
  const L = (x, y) => look(x, y, opts, plain, swamp);
  const H = (x, y) => {
    if (flee) {
      let r = 0;
      for (const g of goals) { const d = Math.max(Math.abs(g.x - x), Math.abs(g.y - y)); if (d < g.range) r = Math.max(r, g.range - d); }
      return r;
    }
    let r = Infinity;
    for (const g of goals) { const d = Math.max(Math.abs(g.x - x), Math.abs(g.y - y)); r = Math.min(r, d > g.range ? d - g.range : 0); }
    return r;
  };
  if (H(origin.x, origin.y) === 0) return { path: [], ops: 0, cost: 0, incomplete: false };
  const state = new Uint8Array(10000); // 0 new, 1 open, 2 closed
  const parent = new Int32Array(10000).fill(-1);
  const heap = new Heap();
  const push = (from, x, y, g) => {
    const i = idx(x, y);
    if (state[i] === 2) return;
    const f = g + Math.trunc(H(x, y) * weight);
    if (state[i] === 1) { if (heap.prio[i] > f) { heap.update(i, f); parent[i] = from; } }
    else { heap.insert(i, f); state[i] = 1; parent[i] = from; }
  };
  const NULL = null;
  const jumpX = (cost, x, y, dx) => {
    let pu = L(x, y - 1), pd = L(x, y + 1);
    for (;;) {
      if (H(x, y) === 0 || nearBorder(x)) break;
      const cu = L(x + dx, y - 1), cd = L(x + dx, y + 1);
      if ((cu !== OBSTACLE && pu !== cost) || (cd !== OBSTACLE && pd !== cost)) break;
      pu = cu; pd = cd;
      x += dx;
      const jc = L(x, y);
      if (jc === OBSTACLE) return NULL;
      if (jc !== cost) break;
    }
    return [x, y];
  };
  const jumpY = (cost, x, y, dy) => {
    let pl = L(x - 1, y), pr = L(x + 1, y);
    for (;;) {
      if (H(x, y) === 0 || nearBorder(y)) break;
      const cl = L(x - 1, y + dy), cr = L(x + 1, y + dy);
      if ((cl !== OBSTACLE && pl !== cost) || (cr !== OBSTACLE && pr !== cost)) break;
      pl = cl; pr = cr;
      y += dy;
      const jc = L(x, y);
      if (jc === OBSTACLE) return NULL;
      if (jc !== cost) break;
    }
    return [x, y];
  };
  const jumpXY = (cost, x, y, dx, dy) => {
    let px = L(x - dx, y), py = L(x, y - dy);
    for (;;) {
      if (H(x, y) === 0 || nearBorder(x) || nearBorder(y)) break;
      if ((L(x - dx, y + dy) !== OBSTACLE && px !== cost) || (L(x + dx, y - dy) !== OBSTACLE && py !== cost)) break;
      px = L(x, y + dy); py = L(x + dx, y);
      if ((py !== OBSTACLE && jumpX(cost, x + dx, y, dx) !== NULL) || (px !== OBSTACLE && jumpY(cost, x, y + dy, dy) !== NULL)) break;
      x += dx; y += dy;
      const jc = L(x, y);
      if (jc === OBSTACLE) return NULL;
      if (jc !== cost) break;
    }
    return [x, y];
  };
  const jump = (cost, x, y, dx, dy) => (dx !== 0 ? (dy !== 0 ? jumpXY(cost, x, y, dx, dy) : jumpX(cost, x, y, dx)) : jumpY(cost, x, y, dy));
  const jumpNeighbor = (px, py, i, nx, ny, g, cost, nCost) => {
    if (nCost !== cost || isBorder(nx) || isBorder(ny)) {
      if (nCost === OBSTACLE) return;
      g += nCost;
    } else {
      const j = jump(nCost, nx, ny, nx - px, ny - py);
      if (j === NULL) return;
      [nx, ny] = j;
      g += nCost * (Math.max(Math.abs(nx - px), Math.abs(ny - py)) - 1) + L(nx, ny);
    }
    push(i, nx, ny, g);
  };
  const astar = (i, x, y, g) => {
    for (const [dx, dy] of DIR) {
      const nx = x + dx, ny = y + dy;
      if (x % ROOM === 0) { if ((nx % ROOM === ROOM - 1 && y !== ny) || x === nx) continue; }
      else if (x % ROOM === ROOM - 1) { if ((nx % ROOM === 0 && y !== ny) || x === nx) continue; }
      else if (y % ROOM === 0) { if ((ny % ROOM === ROOM - 1 && x !== nx) || y === ny) continue; }
      else if (y % ROOM === ROOM - 1) { if ((ny % ROOM === 0 && x !== nx) || y === ny) continue; }
      const c = L(nx, ny);
      if (c === OBSTACLE) continue;
      push(i, nx, ny, g + c);
    }
  };
  const jps = (i, x, y, g) => {
    const p = parent[i];
    const ppx = (p / 100) | 0, ppy = p % 100;
    const dx = x > ppx ? 1 : x < ppx ? -1 : 0, dy = y > ppy ? 1 : y < ppy ? -1 : 0;
    let nb = null;
    if (x % ROOM === 0) { if (dx === -1) nb = [[x - 1, y]]; else if (dx === 1) nb = [[x + 1, y - 1], [x + 1, y], [x + 1, y + 1]]; }
    else if (x % ROOM === ROOM - 1) { if (dx === 1) nb = [[x + 1, y]]; else if (dx === -1) nb = [[x - 1, y - 1], [x - 1, y], [x - 1, y + 1]]; }
    else if (y % ROOM === 0) { if (dy === -1) nb = [[x, y - 1]]; else if (dy === 1) nb = [[x - 1, y + 1], [x, y + 1], [x + 1, y + 1]]; }
    else if (y % ROOM === ROOM - 1) { if (dy === 1) nb = [[x, y + 1]]; else if (dy === -1) nb = [[x - 1, y - 1], [x, y - 1], [x + 1, y - 1]]; }
    if (nb) {
      for (const [nx, ny] of nb) { const c = L(nx, ny); if (c === OBSTACLE) continue; push(i, nx, ny, g + c); }
      return;
    }
    const bdx = x % ROOM === 1 ? -1 : x % ROOM === ROOM - 2 ? 1 : 0;
    const bdy = y % ROOM === 1 ? -1 : y % ROOM === ROOM - 2 ? 1 : 0;
    const cost = L(x, y);
    if (dx !== 0) {
      const c = L(x + dx, y);
      if (c !== OBSTACLE) { if (bdy === 0) jumpNeighbor(x, y, i, x + dx, y, g, cost, c); else push(i, x + dx, y, g + c); }
    }
    if (dy !== 0) {
      const c = L(x, y + dy);
      if (c !== OBSTACLE) { if (bdx === 0) jumpNeighbor(x, y, i, x, y + dy, g, cost, c); else push(i, x, y + dy, g + c); }
    }
    if (dx !== 0) {
      if (dy !== 0) {
        const c = L(x + dx, y + dy);
        if (c !== OBSTACLE) jumpNeighbor(x, y, i, x + dx, y + dy, g, cost, c);
        if (L(x - dx, y) !== cost) jumpNeighbor(x, y, i, x - dx, y + dy, g, cost, L(x - dx, y + dy));
        if (L(x, y - dy) !== cost) jumpNeighbor(x, y, i, x + dx, y - dy, g, cost, L(x + dx, y - dy));
      } else {
        if (bdy === 1 || L(x, y + 1) !== cost) jumpNeighbor(x, y, i, x + dx, y + 1, g, cost, L(x + dx, y + 1));
        if (bdy === -1 || L(x, y - 1) !== cost) jumpNeighbor(x, y, i, x + dx, y - 1, g, cost, L(x + dx, y - 1));
      }
    } else {
      if (bdx === 1 || L(x + 1, y) !== cost) jumpNeighbor(x, y, i, x + 1, y + dy, g, cost, L(x + 1, y + dy));
      if (bdx === -1 || L(x - 1, y) !== cost) jumpNeighbor(x, y, i, x - 1, y + dy, g, cost, L(x - 1, y + dy));
    }
  };
  const start = idx(origin.x, origin.y);
  let ops = maxOps, best = start, bestH = Infinity, bestG = Infinity;
  astar(start, origin.x, origin.y, 0);
  while (heap.size > 0 && ops > 0) {
    const [i, f] = heap.pop();
    state[i] = 2;
    const x = (i / 100) | 0, y = i % 100;
    const h = H(x, y);
    const g = f - Math.trunc(h * weight);
    if (h === 0) { best = i; bestH = 0; bestG = g; break; }
    if (h < bestH) { best = i; bestH = h; bestG = g; }
    if (g + h > maxCost) break;
    jps(i, x, y, g);
    ops--;
  }
  // the path back from the best node; a jump is filled in cell by cell toward its parent (pf.cc search())
  const path = [];
  let cur = best;
  let cx = (cur / 100) | 0, cy = cur % 100;
  let guard = 0;
  while (cur !== start && guard++ < 20000) {
    path.push({ x: cx, y: cy });
    const p = parent[cur];
    if (p < 0) break;
    const nx = (p / 100) | 0, ny = p % 100;
    if (Math.max(Math.abs(nx - cx), Math.abs(ny - cy)) > 1) {
      const sx = Math.sign(nx - cx), sy = Math.sign(ny - cy);
      do { cx += sx; cy += sy; path.push({ x: cx, y: cy }); } while (Math.max(Math.abs(nx - cx), Math.abs(ny - cy)) > 1);
    }
    cur = p; cx = nx; cy = ny;
  }
  path.reverse();
  return { path, ops: maxOps - ops, cost: bestG === Infinity ? 0 : bestG, incomplete: bestH !== 0 };
}

function searchAstar(origin, goal, opts = {}) {
  opts = opts || {};
  const goals = goalsOf(goal);
  const flee = !!opts.flee;
  const plain = opts.plainCost ?? 2, swamp = opts.swampCost ?? 10;
  const weight = (process.env.PF === 'dijkstra') ? 0 : (opts.heuristicWeight ?? 1.2);
  const maxOps = opts.maxOps ?? 50000, maxCost = opts.maxCost ?? Infinity;
  const heuristic = (x, y) => {
    if (flee) {
      let r = 0;
      for (const g of goals) { const d = Math.max(Math.abs(g.x - x), Math.abs(g.y - y)); if (d < g.range) r = Math.max(r, g.range - d); }
      return r;
    }
    let r = Infinity;
    for (const g of goals) { const d = Math.max(Math.abs(g.x - x), Math.abs(g.y - y)); r = Math.min(r, d > g.range ? d - g.range : 0); }
    return r;
  };
  const start = idx(origin.x, origin.y);
  if (heuristic(origin.x, origin.y) === 0) return { path: [], ops: 0, cost: 0, incomplete: false };
  const state = new Uint8Array(10000); // 0 new, 1 open, 2 closed
  const parent = new Int32Array(10000).fill(-1);
  const heap = new Heap();
  const push = (from, x, y, g) => {
    const i = idx(x, y);
    if (state[i] === 2) return;
    const f = g + Math.trunc(heuristic(x, y) * weight);
    if (state[i] === 1) { if (heap.prio[i] > f) { heap.update(i, f); parent[i] = from; } }
    else { heap.insert(i, f); state[i] = 1; parent[i] = from; }
  };
  const expand = (i, x, y, g) => {
    for (const [dx, dy] of DIR) {
      const nx = x + dx, ny = y + dy;
      const c = look(nx, ny, opts, plain, swamp);
      if (c === OBSTACLE) continue;
      push(i, nx, ny, g + c);
    }
  };
  state[start] = 2;
  expand(start, origin.x, origin.y, 0);
  let ops = maxOps, best = start, bestH = Infinity, bestG = Infinity;
  while (heap.size > 0 && ops > 0) {
    const [i, f] = heap.pop();
    state[i] = 2;
    const x = (i / 100) | 0, y = i % 100;
    const h = heuristic(x, y);
    const g = f - Math.trunc(h * weight);
    if (h === 0) { best = i; bestH = 0; bestG = g; break; }
    if (h < bestH) { best = i; bestH = h; bestG = g; }
    if (g + h > maxCost) break;
    expand(i, x, y, g);
    ops--;
  }
  const path = [];
  let cur = best;
  while (cur !== start && cur >= 0) { path.push({ x: (cur / 100) | 0, y: cur % 100 }); cur = parent[cur]; }
  path.reverse();
  return { path, ops: maxOps - ops, cost: bestG === Infinity ? 0 : bestG, incomplete: bestH !== 0 };
}
