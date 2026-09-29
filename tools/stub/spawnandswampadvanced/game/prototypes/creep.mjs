import { GameObject } from './game-object.mjs';
import { Store } from './store.mjs';
import { intent, world, range } from '../../world.mjs';
import { findPathResult, getDirection } from '../utils.mjs';

const COST = { move: 50, work: 100, carry: 50, attack: 80, ranged_attack: 150, heal: 250, tough: 10 };
const OBSTACLE_SITES = new Set(['StructureSpawn', 'StructureExtension', 'StructureTower', 'StructureWall']);

/**
 * A creep of the Spawn and Swamp ADVANCED stub — a copy of tools/stub/escortrunadvanced/game/prototypes/creep.mjs. Every
 * action is an INTENT named as World's (processor/intents/creeps/intents.js), resolved by world.mjs after both sides have
 * run: the API checks what the runtime documents (owner, spawning, body part, range, energy) and answers its codes, the
 * processor applies World's priorities — "harvest ... cannot be executed on the same tick with attack, build, heal,
 * rangedHeal", "build ... with harvest, attack, heal, rangedHeal, rangedAttack, rangedMassAttack" (arena-docs; the same
 * pairs World's priority table resolves). A later call of the same action in a tick replaces the earlier one.
 */
export class Creep extends GameObject {
  constructor(x, y, owner, body) {
    super(x, y);
    this.kind = 'creep';
    this.owner = owner;
    this.body = body.map((t) => ({ type: t, hits: 100 }));
    this.hitsMax = body.length * 100;
    this.hits = this.hitsMax;
    this.fatigue = 0;
    this.spawning = false;
    this.store = new Store(body.filter((t) => t === 'carry').length * 50, 0, true);
    this.effects = [];
  }
  get my() { return this.owner === world.perspective; }
  static cost(body) { return body.reduce((s, t) => s + (COST[t] || 0), 0); }
  parts(type) { let n = 0; for (const p of this.body) if (p.type === type && p.hits > 0) n++; return n; }
  summary() {
    const order = [['tough', 'T'], ['move', 'M'], ['ranged_attack', 'R'], ['attack', 'A'], ['heal', 'H'], ['carry', 'C'], ['work', 'W']];
    let s = '';
    for (const [t, ch] of order) { const n = this.body.filter((p) => p.type === t && p.hits > 0).length; if (n > 0) s += ch + n; }
    return s || 'dead';
  }
  // common refusals: not ours (ERR_NOT_OWNER -1), still spawning (ERR_BUSY -4)
  refuse() { if (this.owner !== world.perspective) return -1; if (this.spawning) return -4; return 0; }
  // the API refuses the intent of a tired or motorless creep (ERR_TIRED -11 / ERR_NO_BODYPART -12); move(creep) is World's
  // pull form (game/creeps.js:135-142): OK without those checks, the processor moves it only when pulled
  move(dir) {
    const r = this.refuse(); if (r) return r;
    if (dir && typeof dir === 'object') {
      const dx = Math.sign(dir.x - this.x), dy = Math.sign(dir.y - this.y);
      const d = getDirection(dx, dy);
      if (!d) return -10;
      intent(this, 'move', { dir: d }); return 0;
    }
    if (this.fatigue > 0) return -11;
    if (this.parts('move') === 0) return -12;
    if (!(dir >= 1 && dir <= 8)) return -10;
    intent(this, 'move', { dir }); return 0;
  }
  moveTo(target, opts) {
    const r = this.refuse(); if (r) return r;
    if (this.fatigue > 0) return -11;
    if (this.parts('move') === 0) return -12;
    if (!target) return -10;
    if (target.x === this.x && target.y === this.y) return 0;
    // a search every call: World's moveTo keeps its path for `reusePath` ticks (game/creeps.js:241-266), and the stub
    // tried that on the record of 6abc0dc7 — our creeps left their recorded cells at t=667 with it and at t=751 without,
    // so the Arena's moveTo searches afresh each tick
    const res = findPathResult(this, target, opts);
    const s = res.path[0];
    // MOVEDBG=<from>-<to> prints every moveTo of our side in those ticks (target, whether a CostMatrix came, the path head)
    if (process.env.MOVEDBG && this.owner === 0 && process.env.MOVEDUMP && opts && opts.costMatrix) { const [a, b] = process.env.MOVEDBG.split('-').map(Number); if (world.tick >= a && world.tick <= b) world.api.dump(`${process.env.MOVEDUMP}-${world.tick}-${this.id}.json`, { from: { x: this.x, y: this.y }, to: { x: target.x, y: target.y }, cm: Array.from(opts.costMatrix.bits), terrain: Array.from(world.terrain) }); }
    if (process.env.MOVEDBG && this.owner === 0) { const [a, b] = process.env.MOVEDBG.split('-').map(Number); if (world.tick >= a && world.tick <= b) process.stderr.write(`movedbg t=${world.tick} ${this.summary()} ${this.id} (${this.x},${this.y}) -> (${target.x},${target.y}) cm=${!!(opts && opts.costMatrix)} cost=${res.cost} inc=${res.incomplete} path=${res.path.slice(0, 4).map((p) => p.x + ',' + p.y).join(' ')}\n`); }
    if (!s) return -2;
    return this.move(getDirection(s.x - this.x, s.y - this.y));
  }
  attack(target) {
    const r = this.refuse(); if (r) return r;
    if (this.parts('attack') === 0) return -12;
    if (!target || !target.exists || typeof target.hits !== 'number') return -7;
    if (range(this, target) > 1) return -9;
    intent(this, 'attack', { target }); return 0;
  }
  rangedAttack(target) {
    const r = this.refuse(); if (r) return r;
    if (this.parts('ranged_attack') === 0) return -12;
    if (!target || !target.exists || typeof target.hits !== 'number') return -7;
    if (range(this, target) > 3) return -9;
    intent(this, 'rangedAttack', { target }); return 0;
  }
  rangedMassAttack() {
    const r = this.refuse(); if (r) return r;
    if (this.parts('ranged_attack') === 0) return -12;
    intent(this, 'rangedMassAttack', {}); return 0;
  }
  heal(target) {
    const r = this.refuse(); if (r) return r;
    if (this.parts('heal') === 0) return -12;
    if (!target || !target.exists || target.kind !== 'creep') return -7;
    if (range(this, target) > 1) return -9;
    intent(this, 'heal', { target }); return 0;
  }
  rangedHeal(target) {
    const r = this.refuse(); if (r) return r;
    if (this.parts('heal') === 0) return -12;
    if (!target || !target.exists || target.kind !== 'creep') return -7;
    if (range(this, target) > 3) return -9;
    intent(this, 'rangedHeal', { target }); return 0;
  }
  // the amount is fixed when the call is made — "сдаётся запас начала тика" (docs/spawn-and-swamp-advanced.md, rules)
  transfer(target, res, amount) {
    const r = this.refuse(); if (r) return r;
    if (!target || !target.exists || !target.store) return -7;
    if (range(this, target) > 1) return -9;
    const a = amount ?? this.store.energy;
    if (a <= 0 || this.store.energy < a) return -6;
    if (target.store.free() <= 0) return -8;
    intent(this, 'transfer', { target, amount: a }); return 0;
  }
  withdraw(target, res, amount) {
    const r = this.refuse(); if (r) return r;
    if (!target || !target.exists || !target.store || target.kind === 'creep') return -7;
    // "your creeps can withdraw resources from hostile structures as well, in case if there is no hostile rampart on top"
    if (target.owner !== undefined && target.owner !== this.owner && world.objects.some((o) => o.exists && o.kind === 'rampart' && o.owner !== this.owner && o.x === target.x && o.y === target.y)) return -1;
    if (range(this, target) > 1) return -9;
    if (target.store.energy <= 0) return -6;
    if (this.store.free() <= 0) return -8;
    intent(this, 'withdraw', { target, amount }); return 0;
  }
  pickup(target) {
    const r = this.refuse(); if (r) return r;
    if (!target || !target.exists || target.kind !== 'resource') return -7;
    if (range(this, target) > 1) return -9;
    if (this.store.free() <= 0) return -8;
    intent(this, 'pickup', { target }); return 0;
  }
  drop(res, amount) {
    const r = this.refuse(); if (r) return r;
    if (this.store.energy <= 0 || (amount !== undefined && this.store.energy < amount)) return -6;
    intent(this, 'drop', { amount }); return 0;
  }
  harvest(target) {
    const r = this.refuse(); if (r) return r;
    if (this.parts('work') === 0) return -12;
    if (!target || !target.exists || target.kind !== 'source') return -7;
    if (range(this, target) > 1) return -9;
    if (target.energy <= 0) return -6;
    intent(this, 'harvest', { target }); return 0;
  }
  // "ERR_INVALID_TARGET ... or the structure cannot be built here (probably because of an obstacle at the same square)"
  build(target) {
    const r = this.refuse(); if (r) return r;
    if (!target || !target.exists || target.kind !== 'site') return -7;
    if (this.parts('work') === 0) return -12;
    if (!this.store || this.store.energy <= 0) return -6;
    if (range(this, target) > 3) return -9;
    if (target.proto && OBSTACLE_SITES.has(target.proto.name) && world.objects.some((o) => o.exists && o.kind === 'creep' && !o.spawning && o.x === target.x && o.y === target.y)) return -7;
    intent(this, 'build', { target });
    return 0;
  }
  /** Engine pull.js: only owner, spawning, target validity and adjacency are checked — no MOVE, no fatigue test. */
  pull(target) {
    const r = this.refuse(); if (r) return r;
    if (!target || !target.exists || target.kind !== 'creep' || target === this) return -7;
    if (target.owner !== this.owner) return -7;
    if (range(this, target) > 1) return -9;
    intent(this, 'pull', { target }); return 0;
  }
}
