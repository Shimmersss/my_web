// App-side binding of the moonlit variant list; keeps utils/tarotThemes.js free of JSON imports.
import variants from "./moonlitVariants.json";
import { createThemeResolver } from "@/utils/tarotThemes";

export const moonlitVariantList = variants.cards;
export const resolveTheme = createThemeResolver(variants.cards);

/** A few distinct cards in random themes, for decorative use only. */
export function sampleThemedCards(count = 3) {
  const cardIds = [...new Set(variants.cards.map(card => card.cardId))].sort(() => Math.random() - 0.5).slice(0, count);
  return cardIds.map(cardId => {
    const options = variants.cards.filter(card => card.cardId === cardId);
    return options[Math.floor(Math.random() * options.length)];
  });
}

/** Warms the cover and line art of picked cards so the reveal never waits on the network. */
export function preloadThemes(themes) {
  if (typeof Image === "undefined") return;
  themes.filter(Boolean).forEach(theme => {
    [theme.cover, theme.lineart].filter(Boolean).forEach(src => {
      const image = new Image();
      image.decoding = "async";
      image.src = src;
    });
  });
}
