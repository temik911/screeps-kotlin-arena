export class Visual {
  constructor(layer, persistent) { this.layer = layer; this.persistent = persistent; }
  rect() { return this; }
  text() { return this; }
  line() { return this; }
  circle() { return this; }
  poly() { return this; }
  clear() { return this; }
  size() { return 0; }
}
