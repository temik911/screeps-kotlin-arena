import { GameObject } from './game-object.mjs';

export class Flag extends GameObject {
  constructor(x, y) { super(x, y); this.kind = 'flag'; }
}
