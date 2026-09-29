/** A store with energy only (the arena has no other resource); `store.energy` is also what `store[RESOURCE_ENERGY]` reads. */
export class Store {
  // a creep without CARRY answers null for its capacity (the live log of v58: his M5R5 printed `e=0/null`)
  constructor(capacity, energy = 0, nullIfNone = false) { this.capacity = capacity; this.energy = energy; this.nullIfNone = nullIfNone; }
  getCapacity(r) { return (r === undefined || r === 'energy') && !(this.nullIfNone && this.capacity === 0) ? this.capacity : null; }
  getUsedCapacity(r) { return r === undefined || r === 'energy' ? this.energy : 0; }
  getFreeCapacity(r) { return r === undefined || r === 'energy' ? this.capacity - this.energy : 0; }
  free() { return this.capacity - this.energy; }
}
