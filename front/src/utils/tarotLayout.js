// Geometry for the card fan and spread board; pure so the node test can check every width.

const clamp = (value, min, max) => Math.max(min, Math.min(max, value));

export function fanRows(width) {
  if (width >= 760) return 1;
  if (width >= 420) return 2;
  return 4;
}

/**
 * Lays `count` cards on one or more shallow arcs inside a container `width` px wide.
 * Returns card centre coordinates (px), rotation (deg) and the exposed width of each card.
 */
export function fanLayout(count = 22, width = 1000) {
  const rows = fanRows(width);
  // Balanced rows (22 → 6/6/5/5) so the last row is never a sparse stub.
  const rowSizes = Array.from({ length: rows }, (_, row) => Math.floor(count / rows) + (row < count % rows ? 1 : 0));
  const rowStarts = rowSizes.map((_, row) => rowSizes.slice(0, row).reduce((sum, size) => sum + size, 0));
  const divisor = rows === 1 ? 13 : rows === 2 ? 8.5 : 6.2;
  const cardWidth = Math.round(clamp(width / divisor, 44, 96));
  const cardHeight = Math.round(cardWidth * 1.5);
  const inset = 6;
  const usable = Math.max(0, width - cardWidth - inset * 2);
  const arcDepth = Math.round(cardHeight * (rows === 1 ? 0.3 : 0.16));
  const maxAngle = rows === 1 ? 24 : 14;
  const rowGap = Math.round(cardHeight * (rows === 1 ? 0 : 0.66));
  const items = [];
  let exposed = Infinity;
  for (let index = 0; index < count; index += 1) {
    const row = rowStarts.findLastIndex(start => index >= start);
    const column = index - rowStarts[row];
    const inRow = rowSizes[row];
    const t = inRow === 1 ? 0 : (column / (inRow - 1)) * 2 - 1;
    const step = inRow === 1 ? cardWidth : usable / (inRow - 1);
    exposed = Math.min(exposed, step);
    items.push({
      index,
      row,
      x: Math.round(inset + cardWidth / 2 + (inRow === 1 ? usable / 2 : column * step)),
      y: Math.round(row * rowGap + cardHeight / 2 + 10 + arcDepth * t * t),
      rotate: Number((t * maxAngle).toFixed(2)),
    });
  }
  const height = Math.round((rows - 1) * rowGap + cardHeight + arcDepth + 24);
  return { rows, cardWidth, cardHeight, height, exposed: Number.isFinite(exposed) ? exposed : cardWidth, items };
}

/** Larger cards with explicit row spacing; position identity and branch order stay unchanged. */
export function spreadSlotLayout(spread, width) {
  const inset = 8;
  const wideChoice = spread.id === "choice" && width >= 700;
  const cardWidth = Math.round(wideChoice ? Math.min(210, (width - 48) / 3) : spread.id === "single"
    ? Math.min(280, width * .68)
    : Math.min(210, (width - inset * 2) * .42));
  const cardHeight = Math.round(cardWidth * 1.5);
  const rowStep = cardHeight + 54;
  const rows = spread.id === "single" ? 1 : spread.id === "triangle" || wideChoice ? 2 : 3;
  const height = inset * 2 + cardHeight + (rows - 1) * rowStep;
  const slots = spread.positions.map((position, order) => {
    const center = spread.id === "single" || position.id === "future" || position.id === "now";
    const right = position.id === "present" || position.id.startsWith("optionB");
    const row = spread.id === "single" ? 0 : spread.id === "triangle"
      ? (position.id === "future" ? 1 : 0)
      : (position.id === "now" ? 2 : position.id.endsWith("future") ? 0 : 1);
    let left = center ? (width - cardWidth) / 2 : right ? width - inset - cardWidth : inset;
    let top = inset + row * rowStep;
    if (wideChoice) {
      const column = position.id === "now" ? 0 : position.id.endsWith("future") ? 2 : 1;
      left = inset + column * (width - inset * 2 - cardWidth) / 2;
      top = position.id === "now" ? (height - cardHeight) / 2 : inset + (right ? rowStep : 0);
    }
    return { order, id: position.id, label: position.label,
      left: Math.round(left), top, centerX: Math.round(left + cardWidth / 2), centerY: top + cardHeight / 2 };
  });
  return { width, height, cardWidth, cardHeight, slots };
}
