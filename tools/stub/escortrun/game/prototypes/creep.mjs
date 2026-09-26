import { GameObject } from './game-object.mjs';
import { Store } from './store.mjs';
import { intent, world } from '../../world.mjs';
import { searchPath } from '../path-finder.mjs';
import { getDirection } from '../utils.mjs';

const COST = { move: 50, work: 100, carry: 50, attack: 80, ranged_attack: 150, heal: 250, tough: 10 };

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
    this.store = new Store(body.filter((t) => t === 'carry').length * 50);
    this.effects = [];
  }
  get my() { return this.owner === world.perspective; }
  static cost(body) { return body.reduce((s, t) => s + (COST[t] || 0), 0); }
  summary() {
    const order = [['tough', 'T'], ['move', 'M'], ['ranged_attack', 'R'], ['attack', 'A'], ['heal', 'H'], ['carry', 'C'], ['work', 'W']];
    let s = '';
    for (const [t, ch] of order) { const n = this.body.filter((p) => p.type === t && p.hits > 0).length; if (n > 0) s += ch + n; }
    return s || 'dead';
  }
  // the API refuses the intent of a tired or motorless creep (ERR_TIRED -11 / ERR_NO_BODYPART -12) — the engine's
  // move(creepObject) is World's pull form (game/creeps.js:135-142): the API sets the intent toward that creep and
  // returns OK WITHOUT the fatigue and MOVE checks; the processor then moves the creep only if it is pulled this tick
  // (world.mjs movePhase). Measured live 27.09.2026: escort.move(puller) with fatigue 60 answers 0 in the Arena too
  move(dir) {
    if (dir && typeof dir === 'object') {
      const dx = Math.sign(dir.x - this.x), dy = Math.sign(dir.y - this.y);
      const d = { '0,-1': 1, '1,-1': 2, '1,0': 3, '1,1': 4, '0,1': 5, '-1,1': 6, '-1,0': 7, '-1,-1': 8 }[`${dx},${dy}`];
      if (!d) return -10;
      intent(this, 'move', { dir: d }); return 0;
    }
    if (this.fatigue > 0) return -11; if (!this.body.some((p) => p.type === 'move' && p.hits > 0)) return -12; if (!(dir >= 1 && dir <= 8)) return -10; intent(this, 'move', { dir }); return 0; }
  moveTo(target, opts) { if (this.fatigue > 0) return -11; if (!this.body.some((p) => p.type === 'move' && p.hits > 0)) return -12; const r = searchPath(this, target, opts); const s = r.path[0]; if (!s) return -2; return this.move(getDirection(s.x - this.x, s.y - this.y)); }
  attack(target) { intent(this, 'melee', { target }); return 0; }
  rangedAttack(target) { intent(this, 'ranged', { type: 'attack', target }); return 0; }
  rangedMassAttack() { intent(this, 'ranged', { type: 'mass' }); return 0; }
  heal(target) { intent(this, 'heal', { target }); return 0; }
  rangedHeal(target) { intent(this, 'ranged', { type: 'heal', target }); return 0; }
  transfer(target, res, amount) { intent(this, 'transfer', { target, amount }); return 0; }
  withdraw(target, res, amount) { intent(this, 'withdraw', { target, amount }); return 0; }
  pickup(target) { intent(this, 'pickup', { target }); return 0; }
  drop(res, amount) { intent(this, 'drop', { amount }); return 0; }
  harvest(target) { intent(this, 'harvest', { target }); return 0; }
  build() { return -10; }
  /** Engine pull.js: only owner, spawning, target validity and adjacency are checked — no MOVE, no fatigue test. */
  pull(target) { if (!target || !target.exists || target.kind !== 'creep' || target === this) return -7; if (target.owner !== this.owner) return -7; if (Math.max(Math.abs(target.x - this.x), Math.abs(target.y - this.y)) > 1) return -9; intent(this, 'pull', { target }); return 0; }
}
