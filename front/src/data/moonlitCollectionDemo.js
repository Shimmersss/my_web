import { reactive } from 'vue'
import catalogue from './moonlitVariants.json' with { type: 'json' }

export const moonlitCards = catalogue.cards.map(card => ({ ...card, id: card.variantId, number: `${String(card.sourceId).padStart(2, '0')} · ${card.themeId.slice(-2)}`, meaning: card.meaningZh }))
export const moonlitCardGroups = [...new Set(moonlitCards.map(card => card.cardId))].map(id => {
  const themes = moonlitCards.filter(card => card.cardId === id)
  return { id, name: themes[0].name, number: String(themes[0].sourceId).padStart(2, '0'), themes }
})
export const moonlitDemo = reactive({ collection: {}, result: null, unread: {} })
export function syncMoonlitCollection(data) {
  moonlitDemo.collection = Object.fromEntries((data.collection || []).map(item => [item.variantId, Number(item.count || 0)]))
}
export function clearDemoResult() { moonlitDemo.result = null }
export function revealDemoResult() { if (moonlitDemo.result) moonlitDemo.result.status = 'revealed' }
export function markCardRead(id) { delete moonlitDemo.unread[id] }
export function cardForResult(result) {
  const id = result?.variantId || `${result?.cardId}-theme-01`
  return moonlitCards.find(card => card.id === id) || null
}
