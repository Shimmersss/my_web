<template>
  <div class="moonlit-demo-page"><div class="moonlit-shell">
    <section class="moonlit-hero" aria-labelledby="collection-title">
      <p class="moonlit-kicker">MOONLIT ARCHIVE · 22 CARDS</p><h1 id="collection-title">月下收藏库</h1>
      <p>收集月下的 22 张塔罗牌，点开一张，探索它的五个主题与寓意。</p>
      <div class="moonlit-actions"><router-link class="moonlit-button" to="/moonlit-draw">去抽一张</router-link><router-link class="moonlit-button secondary" to="/tarot-reading">前往月下占卜</router-link></div>
    </section>
    <section class="moonlit-panel" aria-labelledby="archive-heading" :aria-busy="loading">
      <header class="panel-heading"><div><p class="moonlit-kicker">YOUR CONSTELLATION</p><h2 id="archive-heading">已收藏 {{ ownedGroupCount }} / 22</h2><div class="progress-track" aria-hidden="true"><span :style="{ width: `${ownedCount / 110 * 100}%` }" /></div></div><p>主题收藏 {{ ownedCount }} / 110</p></header>
      <nav class="filter-row" aria-label="收藏筛选"><button v-for="item in filters" :key="item.key" class="moonlit-tab" :class="{ active: filter === item.key }" :aria-pressed="filter === item.key" @click="filter = item.key">{{ item.label }}</button></nav>
      <p v-if="error" class="draw-error" role="alert">{{ error }} <button class="moonlit-button quiet" @click="load">重试</button></p><p v-else-if="loading" role="status">正在读取收藏…</p>
      <div v-else class="collection-grid"><MoonlitCardTile v-for="card in visibleCards" :key="card.id" :card="card" :count="card.count" @select="openCard" /><p v-if="!visibleCards.length" class="empty-filter">还没有这类收藏，去抽一张吧。</p></div>
    </section>
    <NModal v-model:show="detailOpen" preset="card" class="moonlit-detail-modal grouped-card-modal" :title="selectedGroup?.name || '塔罗牌'" :bordered="false" closable>
      <div v-if="selected && selectedGroup" class="detail-layout">
        <div><MoonlitCardArtwork v-if="countFor(selected.id)" :card="selected" /><div v-else class="detail-locked"><b>✦</b><span>这个主题尚未收藏</span></div></div>
        <div class="detail-copy"><p>NO. {{ selectedGroup.number }} · 已收藏 {{ countFor(selected.id) }} 张</p><h2>{{ selected.themeName }}</h2><p>{{ selected.meaning }}</p>
          <div class="theme-list" role="group" aria-label="五个主题">
            <button v-for="(theme, index) in selectedGroup.themes" :key="theme.id" class="theme-button" :class="{ selected: selected.id === theme.id }" :aria-pressed="selected.id === theme.id" @click="selected = theme"><b>{{ index + 1 }}</b><span>{{ theme.themeName }}</span><small>{{ countFor(theme.id) ? `${countFor(theme.id)} 张` : '未收藏' }}</small></button>
          </div>
          <router-link v-if="!countFor(selected.id)" class="moonlit-button" style="margin-top:18px" to="/moonlit-draw">前往抽卡</router-link>
        </div>
      </div>
    </NModal>
  </div></div>
</template>
<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { NModal } from 'naive-ui'
import { useRoute } from 'vue-router'
import MoonlitCardTile from '@/components/matchmaking/MoonlitCardTile.vue'
import MoonlitCardArtwork from '@/components/matchmaking/MoonlitCardArtwork.vue'
import { moonlitCards, moonlitCardGroups, moonlitDemo, syncMoonlitCollection } from '@/data/moonlitCollectionDemo'
import { getMoonlitState } from '@/api'
import { useAuthStore } from '@/stores/auth'
import '@/styles/moonlit-demo.scss'
const route = useRoute(), auth = useAuthStore(), filter = ref('all'), detailOpen = ref(false), selected = ref(null), selectedGroup = ref(null), loading = ref(true), error = ref('')
const filters = [{key:'all',label:'全部 22'},{key:'owned',label:'已收藏'},{key:'missing',label:'未集齐'}]
const countFor = id => Number(moonlitDemo.collection[id] || 0)
const ownedCount = computed(() => moonlitCards.filter(card => countFor(card.id)>0).length)
const groups = computed(() => moonlitCardGroups.map(group => ({ ...group, ownedThemes: group.themes.filter(theme => countFor(theme.id)>0).length, count: group.themes.reduce((sum, theme) => sum + countFor(theme.id), 0), image: (group.themes.find(theme => countFor(theme.id)>0) || group.themes[0]).image })))
const ownedGroupCount = computed(() => groups.value.filter(group => group.ownedThemes>0).length)
const visibleCards = computed(() => groups.value.filter(group => filter.value==='all'||(filter.value==='owned'&&group.ownedThemes>0)||(filter.value==='missing'&&group.ownedThemes<5)))
function openCard(group, theme){selectedGroup.value=group;selected.value=theme||group.themes.find(card=>countFor(card.id)>0)||group.themes[0];detailOpen.value=true}
function openRequestedCard(){const theme=moonlitCards.find(card=>card.id===route.query.card);const group=groups.value.find(group=>group.id===(theme?.cardId||route.query.card));if(group)openCard(group,theme)}
let requestVersion = 0
async function load(){if(auth.loading&&!auth.isLoggedIn)return;const version=++requestVersion;loading.value=true;error.value='';if(!auth.isLoggedIn){moonlitDemo.collection={};auth.requestLogin(route.fullPath);loading.value=false;return}try{const response=await getMoonlitState();if(version!==requestVersion)return;syncMoonlitCollection(response.data||{});openRequestedCard()}catch(e){if(version===requestVersion)error.value=e.message||'收藏读取失败'}finally{if(version===requestVersion)loading.value=false}}
onMounted(load)
watch(()=>auth.loading,loading=>{if(!loading&&!auth.isLoggedIn)load()})
watch(()=>route.query.card,openRequestedCard)
watch(()=>auth.user?.id,()=>{detailOpen.value=false;selected.value=null;selectedGroup.value=null;moonlitDemo.collection={};load()})
watch(()=>auth.dailyCheckin?.claimed,(claimed)=>{if(claimed)load()})
</script>
<style scoped>
:global(.grouped-card-modal.n-card){width:min(850px,calc(100vw - 24px))}.detail-locked{display:grid;place-content:center;gap:12px;aspect-ratio:2/3;border:1px solid #b99455;border-radius:13px;color:#d9bb78;text-align:center}.detail-locked b{font-size:42px}
</style>
