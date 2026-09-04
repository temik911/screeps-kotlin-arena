// Node loader hook: redirects the arena runtime modules (game/*, arena/*) to the stub implementations.
const base = new URL('./', import.meta.url);

export async function resolve(specifier, context, next) {
  if (specifier === 'game') return { url: new URL('game/index.mjs', base).href, shortCircuit: true };
  if (specifier.startsWith('game/')) return { url: new URL(specifier + '.mjs', base).href, shortCircuit: true };
  if (specifier.startsWith('arena/')) return { url: new URL(specifier + '.mjs', base).href, shortCircuit: true };
  return next(specifier, context);
}
