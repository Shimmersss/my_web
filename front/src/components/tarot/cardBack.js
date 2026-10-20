// One original card-back illustration, rendered once as a data URI so 22 fan cards stay cheap.

const rays = Array.from({ length: 24 }, (_, index) => {
  const long = index % 2 === 0;
  return `<line x1="0" y1="-49" x2="0" y2="${long ? -64 : -56}" transform="rotate(${index * 15})"/>`;
}).join("");

const phase = (x, y, kind) => {
  const base = `<circle cx="${x}" cy="${y}" r="6" fill="none" stroke="#e3c27c" stroke-width=".9"/>`;
  if (kind === "new") return base;
  if (kind === "full") return `<circle cx="${x}" cy="${y}" r="6" fill="#e9cd8b"/>`;
  if (kind === "half") return `${base}<path d="M${x} ${y - 6} a6 6 0 0 1 0 12 z" fill="#e9cd8b"/>`;
  if (kind === "half-left") return `${base}<path d="M${x} ${y - 6} a6 6 0 0 0 0 12 z" fill="#e9cd8b"/>`;
  if (kind === "crescent") return `${base}<path d="M${x} ${y - 6} a6 6 0 0 1 0 12 a3.6 6 0 0 0 0 -12 z" fill="#e9cd8b"/>`;
  return `${base}<path d="M${x} ${y - 6} a6 6 0 0 0 0 12 a3.6 6 0 0 1 0 -12 z" fill="#e9cd8b"/>`;
};
const phases = y => ["crescent", "half", "full", "half-left", "crescent-left"]
  .map((kind, index) => phase(60 + index * 20, y, kind)).join("");

const star = (x, y, size) =>
  `<path transform="translate(${x} ${y}) scale(${size})" d="M0 -6 L1.4 -1.4 6 0 1.4 1.4 0 6 -1.4 1.4 -6 0 -1.4 -1.4Z" fill="#f3dca2"/>`;

const dust = [[34, 74, .55], [162, 90, .45], [48, 214, .5], [156, 226, .6], [100, 58, .35], [100, 244, .35], [28, 150, .4], [172, 150, .4]]
  .map(([x, y, s]) => star(x, y, s)).join("");

const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 300">
<defs>
<radialGradient id="bg" cx="50%" cy="46%" r="72%"><stop offset="0" stop-color="#33406f"/><stop offset=".55" stop-color="#18203e"/><stop offset="1" stop-color="#0a0f20"/></radialGradient>
<linearGradient id="gold" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#f8e2a8"/><stop offset=".5" stop-color="#c39650"/><stop offset="1" stop-color="#f1d28e"/></linearGradient>
<radialGradient id="sun" cx="40%" cy="38%" r="70%"><stop offset="0" stop-color="#fff5d6"/><stop offset=".6" stop-color="#e3c27c"/><stop offset="1" stop-color="#9c7536"/></radialGradient>
</defs>
<rect width="200" height="300" rx="14" fill="url(#bg)"/>
<rect x="7" y="7" width="186" height="286" rx="10" fill="none" stroke="url(#gold)" stroke-width="2.2"/>
<rect x="14" y="14" width="172" height="272" rx="7" fill="none" stroke="#d9b56f" stroke-opacity=".5" stroke-width=".8"/>
<path d="M14 40 Q100 22 186 40 M14 260 Q100 278 186 260" fill="none" stroke="#d9b56f" stroke-opacity=".35" stroke-width=".8"/>
${dust}
<g fill="none" stroke="#e3c27c" stroke-width="1">${phases(30)}${phases(270)}</g>
<g transform="translate(100 150)" fill="none" stroke="url(#gold)" stroke-linecap="round">
<circle r="68" stroke-opacity=".35" stroke-dasharray="1.5 4"/>
<g stroke-width="1.3">${rays}</g>
<circle r="46" stroke-width="1.4"/>
<circle r="39" stroke-opacity=".55" stroke-dasharray="2 3"/>
<polygon points="0,-34 29.4,17 -29.4,17" stroke-opacity=".7"/>
<polygon points="0,34 29.4,-17 -29.4,-17" stroke-opacity=".7"/>
<path d="M0 -20 A20 20 0 1 0 0 20 A15 16 0 1 1 0 -20 Z" fill="url(#sun)" stroke="none"/>
</g>
${star(100, 150, 1.1)}
${star(20, 20, .7)}${star(180, 20, .7)}${star(20, 280, .7)}${star(180, 280, .7)}
</svg>`;

export const CARD_BACK_URL = `url("data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}")`;
