<template>
  <section class="deck-gallery tarot-panel" aria-labelledby="tarot-deck-title">
    <header class="deck-gallery-heading">
      <div>
        <p class="tarot-eyebrow">THE MAJOR ARCANA · 22 CARDS</p>
        <h2 id="tarot-deck-title">月下二十二张牌</h2>
      </div>
      <div class="deck-entry-actions">
        <router-link class="tarot-button quiet" to="/moonlit-collection">进入收藏库</router-link>
        <router-link class="tarot-button quiet" to="/moonlit-draw">单张抽卡</router-link>
        <button class="tarot-text-button" type="button" :aria-expanded="expanded" aria-controls="tarot-deck-grid" @click="expanded = !expanded">{{ expanded ? "收起牌义" : "浏览 22 张牌义" }}</button>
      </div>
    </header>
    <div v-if="expanded" id="tarot-deck-grid" class="deck-grid">
      <button v-for="card in catalogue.cards" :key="card.cardId" type="button" class="deck-card" :aria-label="`查看${card.name}的正逆位牌义`" @click="selected = card">
        <TarotHoloCard class="deck-card-art" compact :src="cardImage(card.cardId)" :alt="`${card.name}牌面`" />
        <strong>{{ card.numeral }} · {{ card.name }}</strong>
        <small>{{ card.upright.keywords.slice(0, 3).join(" · ") }}</small>
      </button>
    </div>

    <NModal :show="Boolean(selected)" preset="card" class="tarot-deck-modal" :title="selected ? `${selected.numeral} · ${selected.name}` : '牌义'" :bordered="false" closable @update:show="value => { if (!value) selected = null; }">
      <div v-if="selected" class="deck-modal-content">
        <TarotHoloCard class="deck-modal-card" :src="cardImage(selected.cardId)" :alt="`${selected.name}牌面`" :title="selected.name" :subtitle="selected.nameEn" />
        <div>
          <p class="deck-modal-meta">{{ selected.nameEn }} · 对应 {{ selected.correspondence }}</p>
          <article v-for="orientation in ['upright', 'reversed']" :key="orientation" class="deck-meaning" :class="orientation">
            <h3>{{ orientationLabel(orientation) }} · {{ selected[orientation].keywords.join(" · ") }}</h3>
            <p>{{ selected[orientation].meaning }}</p>
            <p class="deck-advice"><strong>建议</strong>{{ selected[orientation].advice }}</p>
          </article>
          <section class="deck-themes" aria-label="五个月下主题">
            <h3>五个月下主题 · 占卜时随机显现</h3>
            <ul>
              <li v-for="theme in themesFor(selected.cardId)" :key="theme.variantId" :style="{ '--aura': themeFamily(theme.themeId).aura }">
                <b>{{ themeFamily(theme.themeId).label }} · {{ theme.themeName }}</b>
                <span>{{ theme.meaningZh }}</span>
              </li>
            </ul>
          </section>
        </div>
      </div>
    </NModal>
  </section>
</template>

<script setup>
import { ref } from "vue";
import { NModal } from "naive-ui";
import TarotHoloCard from "@/components/matchmaking/TarotHoloCard.vue";
import { cardImage, orientationLabel } from "@/utils/tarotReading";
import { moonlitVariantList } from "@/data/tarotThemeLibrary";
import { themeFamily } from "@/utils/tarotThemes";

defineProps({ catalogue: { type: Object, required: true } });
const expanded = ref(false);
const selected = ref(null);
const themesFor = cardId => moonlitVariantList.filter(variant => variant.cardId === cardId);
</script>

<style scoped>
.deck-gallery-heading { display: flex; justify-content: space-between; align-items: end; gap: 22px; }
.deck-gallery-heading h2 { margin: 0; color: #fff4dc; font: 500 28px/1.3 "Moonlit Serif", serif; }
.deck-entry-actions { display: flex; justify-content: flex-end; align-items: center; gap: 9px; flex-wrap: wrap; }
.deck-grid { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 18px 12px; margin-top: 22px; }
.deck-card { min-width: 0; padding: 0; border: 0; background: transparent; color: #fff8ed; cursor: pointer; text-align: center; }
.deck-card:focus-visible { outline: 3px solid #e4bf7d; outline-offset: 5px; border-radius: 12px; }
.deck-card-art { aspect-ratio: 2 / 3; transition: transform .25s ease; }
.deck-card:hover .deck-card-art { transform: translateY(-4px); }
.deck-card > strong, .deck-card > small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.deck-card > strong { margin-top: 9px; font: 500 14px/1.35 "Moonlit Serif", serif; }
.deck-card > small { margin-top: 3px; color: #aaaec1; font-size: 11px; }

@media (max-width: 900px) { .deck-grid { grid-template-columns: repeat(4, minmax(0, 1fr)); } }
@media (max-width: 760px) {
  .deck-gallery-heading { flex-direction: column; align-items: flex-start; }
  .deck-entry-actions { justify-content: flex-start; }
  .deck-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px 8px; }
  .deck-card > small { display: none; }
}
@media (max-width: 340px) { .deck-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (prefers-reduced-motion: reduce) { .deck-card-art { transition: none; } }
</style>

<style>
/* The modal is teleported out of the page, so it carries its own night palette. */
.tarot-deck-modal.n-card { width: min(860px, calc(100vw - 24px)); background: #141c31; color: #fff8e8; border: 1px solid #967846; border-radius: 16px; }
.tarot-deck-modal .n-card-header__main, .tarot-deck-modal .n-base-close { color: #fff8e8; }
.tarot-deck-modal .deck-modal-content { display: grid; grid-template-columns: minmax(200px, 290px) 1fr; gap: 26px; align-items: start; }
.tarot-deck-modal .deck-modal-card { aspect-ratio: 2 / 3; height: auto; }
.tarot-deck-modal .deck-modal-meta { margin: 0 0 12px; color: #bcc4d8; font-size: 13px; }
.tarot-deck-modal .deck-meaning { margin-top: 12px; padding: 14px 16px; border-radius: 12px; background: #ffffff0a; }
.tarot-deck-modal .deck-meaning h3 { margin: 0 0 8px; color: #f6dc9c; font: 500 17px/1.4 "Moonlit Serif", serif; }
.tarot-deck-modal .deck-meaning.reversed h3 { color: #d6ccff; }
.tarot-deck-modal .deck-meaning p { margin: 6px 0 0; color: #d4d6e0; line-height: 1.8; }
.tarot-deck-modal .deck-advice strong { margin-right: 8px; color: #e0be78; }
.tarot-deck-modal .deck-themes { margin-top: 16px; }
.tarot-deck-modal .deck-themes h3 { margin: 0 0 8px; color: #e9dcc0; font: 600 13px/1.4 system-ui, sans-serif; letter-spacing: .08em; }
.tarot-deck-modal .deck-themes ul { display: grid; gap: 8px; margin: 0; padding: 0; list-style: none; }
.tarot-deck-modal .deck-themes li { display: grid; gap: 3px; padding: 9px 12px; border-left: 2px solid var(--aura); border-radius: 0 10px 10px 0; background: #ffffff08; }
.tarot-deck-modal .deck-themes b { color: var(--aura); font-weight: 600; font-size: 13px; }
.tarot-deck-modal .deck-themes span { color: #c9cddb; font-size: 13px; line-height: 1.7; }
@media (max-width: 600px) {
  .tarot-deck-modal .deck-modal-content { grid-template-columns: 1fr; }
  .tarot-deck-modal .deck-modal-card { width: min(200px, 64vw); margin: 0 auto; }
}
</style>
