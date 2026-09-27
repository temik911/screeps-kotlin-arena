// Node loader hook: redirects the arena runtime modules (game/*, arena/*) to the stub implementations.
const base = new URL('./', import.meta.url);

export async function resolve(specifier, context, next) {
  if (specifier === 'game') return { url: new URL('game/index.mjs', base).href, shortCircuit: true };
  if (specifier.startsWith('game/')) return { url: new URL(specifier + '.mjs', base).href, shortCircuit: true };
  if (specifier.startsWith('arena/')) return { url: new URL(specifier + '.mjs', base).href, shortCircuit: true };
  // scenario `mirror`: a bot module imported with `?mirror` hands the tag down to every relative import, so the second
  // bot gets a module graph of its own (its own singletons, its own stdlib) and shares only the runtime above
  if (context.parentURL && context.parentURL.includes('?mirror') && (specifier.startsWith('./') || specifier.startsWith('../'))) {
    const u = new URL(specifier, context.parentURL);
    u.search = '?mirror';
    return { url: u.href, shortCircuit: true };
  }
  return next(specifier, context);
}
