// Node loader hook: the compiled bot imports `game`, `game/constants`, `game/prototypes/<x>`, ... — the Arena
// runtime modules. Here they resolve to the stub package in ./game/ next to this file, so the bundle runs
// under Node without the client. Loaded via `node --import ./register.mjs <runner>.mjs`.
const base = new URL('./game/', import.meta.url);
export async function resolve(specifier, context, next) {
  if (specifier === 'game') return { url: new URL('index.mjs', base).href, shortCircuit: true };
  if (specifier.startsWith('game/')) {
    const sub = specifier.slice('game/'.length);
    const file = sub === 'prototypes' ? 'prototypes/index.mjs' : sub + '.mjs';
    return { url: new URL(file, base).href, shortCircuit: true };
  }
  return next(specifier, context);
}
