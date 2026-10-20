<template>
  <div class="moonlit-demo-page"><div class="moonlit-shell">
    <section class="moonlit-hero" aria-labelledby="draw-title"><p class="moonlit-kicker">A SINGLE CARD · UNDER THE MOON</p><h1 id="draw-title">月下抽卡</h1><p>110 款主题卡独立抽取，每个主题都有自己的寓意。每日签到也会带来其中一款今日牌。</p><div class="moonlit-actions"><router-link class="moonlit-button secondary" to="/moonlit-collection">查看收藏库</router-link><router-link class="moonlit-button quiet" to="/tarot-reading">前往月下占卜</router-link></div></section>
    <section class="moonlit-panel draw-stage" aria-labelledby="draw-stage-title" aria-live="polite">
      <template v-if="!result"><p class="moonlit-kicker">TONIGHT'S INVITATION</p><h2 id="draw-stage-title">让一张独立主题卡落到掌心</h2><div class="draw-card-back" aria-hidden="true"><b>✦</b><span>MOONLIT</span></div><div class="draw-methods single-method"><article class="draw-method"><h3>积分单抽</h3><p>当前余额 {{ auth.credits }}。110 款主题等概率；重复卡累计张数，每个主题可直接抽到。</p><button class="moonlit-button" :disabled="drawing || loading || auth.credits < price" @click="requestDraw">{{ drawing ? '正在抽取…' : `使用积分抽取 · ${price} 点` }}</button></article></div></template>
      <template v-else-if="pending"><p class="moonlit-kicker">THE CARD IS WAITING</p><h2 id="draw-stage-title">点一下，揭开牌面</h2><button class="draw-card-back reveal-button" aria-label="揭示抽到的主题卡" @click="revealDemoResult"><b>✦</b><span>点击揭示</span></button><button class="moonlit-button quiet" @click="revealDemoResult">跳过动画并揭示</button></template>
      <template v-else-if="resultCard"><p class="moonlit-kicker">{{ result.resultType === 'first' ? 'FIRST ENCOUNTER' : 'A FAMILIAR CARD' }}</p><h2 id="draw-stage-title">{{ result.resultType === 'first' ? '收藏了一张新主题卡' : '同一主题，再次相遇' }}</h2><MoonlitCardArtwork :card="resultCard" /><p class="result-badge">{{ resultCard.name }} · {{ resultCard.themeName }} · {{ result.count }} 张</p><p>{{ resultCard.meaning }}</p><div class="moonlit-actions" style="justify-content:center"><button class="moonlit-button" @click="clearDemoResult">再抽一次</button><router-link class="moonlit-button secondary" :to="`/moonlit-collection?card=${resultCard.id}`">去收藏库查看</router-link></div></template>
      <p v-if="error" class="draw-error" role="alert">{{ error }}</p>
    </section>
    <NModal v-model:show="confirmOpen" preset="dialog" class="moonlit-confirm-modal" title="确认积分抽取"><p>本次将扣除 {{ price }} 点积分，当前余额 {{ auth.credits }}。</p><template #action><button class="moonlit-button quiet" @click="confirmOpen=false">取消</button><button class="moonlit-button" :disabled="drawing" @click="startDraw">确认抽取</button></template></NModal>
  </div></div>
</template>
<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { NModal } from 'naive-ui'
import MoonlitCardArtwork from '@/components/matchmaking/MoonlitCardArtwork.vue'
import { clearDemoResult, moonlitDemo, cardForResult, revealDemoResult, syncMoonlitCollection } from '@/data/moonlitCollectionDemo'
import { drawMoonlitCard, getMoonlitState } from '@/api'
import { useAuthStore } from '@/stores/auth'
import '@/styles/moonlit-demo.scss'
const price=ref(3), auth=useAuthStore(), drawing=ref(false), loading=ref(true), error=ref(''), confirmOpen=ref(false)
const result=computed(()=>moonlitDemo.result), pending=computed(()=>result.value?.status==='pending'), resultCard=computed(()=>cardForResult(result.value))
let requestVersion = 0
async function load(){const version=++requestVersion;loading.value=true;error.value='';if(!auth.isLoggedIn){auth.requestLogin('/moonlit-draw');loading.value=false;return}try{const r=await getMoonlitState();if(version!==requestVersion)return;price.value=Number(r.data?.price??3);syncMoonlitCollection(r.data||{})}catch(e){if(version===requestVersion)error.value=e.message||'抽卡配置读取失败'}finally{if(version===requestVersion)loading.value=false}}
onMounted(load)
watch(()=>auth.user?.id,()=>{clearDemoResult();confirmOpen.value=false;moonlitDemo.collection={};load()})
function requestDraw(){if(!auth.isLoggedIn){auth.requestLogin('/moonlit-draw');return}confirmOpen.value=true}
async function startDraw(){if(drawing.value)return;confirmOpen.value=false;drawing.value=true;error.value='';const owner=auth.user?.id;try{const r=await drawMoonlitCard('credits',1);if(owner!==auth.user?.id)return;const item=r.data;if(!cardForResult(item))throw Error('抽取已完成，请到收藏库查看结果');moonlitDemo.result={...item,status:'pending'};moonlitDemo.collection[item.variantId]=Number(item.count);auth.updateCredits(item.credits)}catch(e){if(e.status===401||e.status===403)auth.requestLogin('/moonlit-draw');else error.value=e.message||'抽卡失败'}finally{drawing.value=false}}
</script>
<style scoped>.single-method{grid-template-columns:minmax(0,520px);justify-content:center}.reveal-button{cursor:pointer;color:#dfbf7d}</style>
