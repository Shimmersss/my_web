const DESKTOP_PRIMARY_KEYS = new Set([
  'Translate',
  'ImageGenerate',
  'Matchmaking',
  'News',
  'Guestbook'
])

const MENU_PATHS = Object.freeze({
  Publications: '/publications',
  Translate: '/translate',
  Contact: '/contact',
  ImageGenerate: '/image-generate',
  Matchmaking: '/matchmaking-report',
  News: '/news',
  Guestbook: '/guestbook',
  DownloadApp: '/download-app',
  Admin: '/admin'
})

export function resolveMenuPath(key) {
  return MENU_PATHS[key] || ''
}

export function buildDesktopMenuOptions(items) {
  const primary = items.filter(item => DESKTOP_PRIMARY_KEYS.has(item.key))
  const admin = items.filter(item => item.key === 'Admin')
  const secondary = items.filter(item => !DESKTOP_PRIMARY_KEYS.has(item.key) && item.key !== 'Admin')
  return secondary.length
    ? [...primary, ...admin, { label: '更多', key: 'More', children: secondary }]
    : [...primary, ...admin]
}
