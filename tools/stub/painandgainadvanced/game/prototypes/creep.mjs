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
  move(dir) { intent(this, 'move', { dir }); return 0; }
  moveTo(target, opts) { const r = searchPath(this, target, opts); const s = r.path[0]; if (!s) return -2; return this.move(getDirection(s.x - this.x, s.y - this.y)); }
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
  pull() { return -10; }
}
