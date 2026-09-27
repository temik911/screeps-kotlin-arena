export class Store {
  constructor(capacity, energy = 0) { this.capacity = capacity; this.energy = energy; }
  getCapacity() { return this.capacity; }
  getUsedCapacity() { return this.energy; }
  getFreeCapacity() { return this.capacity - this.energy; }
  free() { return this.capacity - this.energy; }
}
