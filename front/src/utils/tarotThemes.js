// Moonlit theme library (22 cards × 5 themes) as it appears inside 月下占卜. Pure; no JSON imports.

/** The five theme slots read as loose series across all 22 cards. */
export const THEME_FAMILIES = Object.freeze([
  { themeId: "theme-01", label: "本源", aura: "#e9c983", symbol: "回到牌的本义，事情的核心在月光下清晰可见。" },
  { themeId: "theme-02", label: "潮汐", aura: "#6fd6e8", symbol: "情绪与流动，提醒你顺着潮水的节律前进。" },
  { themeId: "theme-03", label: "雪沙", aura: "#f2b48a", symbol: "沉静与磨砺，在寒冷或荒芜里守住内在的火。" },
  { themeId: "theme-04", label: "秘园", aura: "#7fe0b0", symbol: "滋养与转化，在隐秘处培育新的可能。" },
  { themeId: "theme-05", label: "星穹", aura: "#b39bff", symbol: "远见与连结，把目光放到更大的格局里。" },
]);

const FAMILY_BY_ID = new Map(THEME_FAMILIES.map(family => [family.themeId, family]));

export function themeFamily(themeId) {
  return FAMILY_BY_ID.get(themeId) || THEME_FAMILIES[0];
}

/** Stable fallback when a draw arrives without a server-chosen theme (older payloads). */
export function fallbackThemeId(seed = "", position = "") {
  let hash = 2166136261;
  for (const char of `${seed}:${position}`) {
    hash ^= char.codePointAt(0);
    hash = Math.imul(hash, 16777619) >>> 0;
  }
  return THEME_FAMILIES[hash % THEME_FAMILIES.length].themeId;
}

export function lineartUrl(variant) {
  if (!variant?.viewerUrl) return "";
  return `${variant.viewerUrl.split("?")[0]}assets/lineart.png`;
}

/**
 * Binds the moonlit variant list once; the returned resolver maps a drawn card
 * ({ cardId, themeId?, position? }) to its themed face.
 */
export function createThemeResolver(variants) {
  const byId = new Map(variants.map(variant => [variant.variantId, variant]));
  return function resolveTheme(card, seed = "") {
    if (!card?.cardId) return null;
    const requested = FAMILY_BY_ID.has(card.themeId) ? card.themeId : fallbackThemeId(seed, card.position || card.cardId);
    const variant = byId.get(`${card.cardId}-${requested}`) || byId.get(`${card.cardId}-theme-01`);
    if (!variant) return null;
    const family = themeFamily(variant.themeId);
    return {
      themeId: variant.themeId,
      variantId: variant.variantId,
      themeName: card.themeName || variant.themeName,
      meaning: card.themeMeaning || variant.meaningZh,
      cover: variant.image,
      lineart: lineartUrl(variant),
      family,
      aura: family.aura,
    };
  };
}

/** The series that shows up at least twice in a spread, if any. */
export function themeResonance(themes) {
  const counts = new Map();
  themes.filter(Boolean).forEach(theme => counts.set(theme.family.themeId, (counts.get(theme.family.themeId) || 0) + 1));
  const [themeId, count] = [...counts.entries()].sort((a, b) => b[1] - a[1])[0] || [];
  if (!themeId || count < 2) return null;
  const family = themeFamily(themeId);
  return { family, count, text: `${count} 张牌以「${family.label}」系列显现：${family.symbol}` };
}
