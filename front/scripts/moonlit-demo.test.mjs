import assert from 'node:assert/strict'
import {readFileSync,existsSync} from 'node:fs'
import {moonlitCards,moonlitCardGroups,cardForResult,moonlitDemo,syncMoonlitCollection,clearDemoResult} from '../src/data/moonlitCollectionDemo.js'
const backend=JSON.parse(readFileSync(new URL('../../backen/src/main/resources/moonlit-variants-v1.json',import.meta.url)))
assert.equal(moonlitCards.length,110)
assert.equal(moonlitCardGroups.length,22)
assert.deepEqual(moonlitCardGroups.flatMap(group=>group.themes.map(card=>card.id)),moonlitCards.map(card=>card.id))
for(const group of moonlitCardGroups){assert.equal(group.themes.length,5);assert.ok(group.themes.every(card=>card.cardId===group.id))}
assert.equal(new Set(moonlitCards.map(c=>c.id)).size,110)
assert.equal(new Set(moonlitCards.map(c=>c.dailyGuidanceZh)).size,110)
assert.deepEqual(moonlitCards.map(({id,number,meaning,...c})=>c),backend.cards)
for(const c of moonlitCards){assert.ok(existsSync(new URL('../public'+c.image,import.meta.url)));assert.ok(c.viewerUrl.includes(c.cardId+'/'+c.themeId));assert.equal(cardForResult({variantId:c.id}).id,c.id)}
assert.equal(cardForResult({cardId:'major-18'}).themeId,'theme-01') // old daily snapshot keeps its existing guidance
assert.equal(cardForResult({variantId:'invalid'}),null)
syncMoonlitCollection({collection:[{variantId:'major-18-theme-05',count:1}]})
assert.equal(moonlitDemo.collection['major-18-theme-05'],1)
assert.equal(moonlitDemo.collection['major-18-theme-01'],undefined) // one theme does not unlock another
moonlitDemo.result={variantId:'major-18-theme-05'};clearDemoResult();assert.equal(moonlitDemo.result,null)
console.log('PASS: 110 unique independent cards/guidance, exact backend parity, assets, legacy lookup, ownership isolation')
