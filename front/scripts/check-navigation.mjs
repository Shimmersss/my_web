import assert from 'node:assert/strict'
import { buildDesktopMenuOptions, resolveMenuPath } from '../src/utils/navigation.js'

const items = [
  { key: 'Publications', label: '文献' },
  { key: 'Translate', label: '翻译' },
  { key: 'Contact', label: 'PPT 生成' },
  { key: 'ImageGenerate', label: 'GPT 生图' },
  { key: 'Matchmaking', label: '婚恋报告' },
  { key: 'News', label: 'GitHub 项目' },
  { key: 'Guestbook', label: '留言板' },
  { key: 'Admin', label: '后台' },
]

const desktop = buildDesktopMenuOptions(items)
assert.deepEqual(desktop.map(item => item.key), [
  'Translate', 'ImageGenerate', 'Matchmaking', 'News', 'Guestbook', 'Admin', 'More'
])
assert.deepEqual(desktop.at(-1).children.map(item => item.key), ['Publications', 'Contact'])
assert.equal(desktop.find(item => item.key === 'News').label, 'GitHub 项目')
assert.equal(desktop.find(item => item.key === 'Guestbook').label, '留言板')
assert.equal(resolveMenuPath('Publications'), '/publications')
assert.equal(resolveMenuPath('Contact'), '/contact')
assert.equal(resolveMenuPath('News'), '/news')
assert.equal(resolveMenuPath('More'), '')

console.log('[navigation-check] desktop primary and More groups verified')
