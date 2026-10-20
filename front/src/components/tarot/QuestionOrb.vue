<template>
  <form class="question-orb" @submit.prevent="$emit('next')">
    <div class="orb-column" aria-hidden="true">
      <CrystalOrb :topic-id="topicId" :charge="charge" />
      <p class="orb-caption">{{ orbCaption }}</p>
      <p class="breath-hint">{{ breathText }}</p>
    </div>

    <div class="question-fields">
      <fieldset class="topic-field">
        <legend>想问哪个方向？</legend>
        <div class="topic-chips">
          <label v-for="topic in catalogue.topics" :key="topic.id" :class="{ selected: topicId === topic.id }">
            <input type="radio" name="tarot-topic" :value="topic.id" :checked="topicId === topic.id" @change="$emit('update:topicId', topic.id)" />
            <span>{{ topic.label }}</span>
            <small>{{ topic.hint }}</small>
          </label>
        </div>
      </fieldset>

      <section class="question-examples" aria-labelledby="question-examples-title">
        <div class="examples-heading">
          <h3 id="question-examples-title">{{ selectedTopic.label }} · 提问灵感</h3>
          <button class="refresh-examples" type="button" @click="refreshExamples">换一组 <span aria-hidden="true">↻</span></button>
        </div>
        <div class="example-list" aria-live="polite" aria-relevant="additions text">
          <button v-for="example in examples" :key="example" type="button" @click="emit('update:question', example)">{{ example }}<span aria-hidden="true">↗</span></button>
        </div>
      </section>

      <label class="question-field">
        <span class="field-label">把问题交给月光</span>
        <textarea
          :value="question"
          rows="3"
          :maxlength="catalogue.limits.questionMax"
          :placeholder="`例如：${examples[0] || '我可以怎样更清楚地看待眼前的事情？'}`"
          @input="$emit('update:question', $event.target.value)"
        ></textarea>
        <span class="field-meta"><span>请勿填写联系方式、证件号等个人信息</span><span>{{ length }} / {{ catalogue.limits.questionMax }}</span></span>
      </label>

      <details class="tips">
        <summary>如何问一个好问题</summary>
        <ul><li v-for="tip in catalogue.questionTips" :key="tip">{{ tip }}</li></ul>
      </details>

      <p v-if="problem" class="field-problem" role="status">{{ problem }}</p>
      <div class="stage-actions">
        <button class="tarot-button primary" type="submit">选择牌阵 <span aria-hidden="true">→</span></button>
      </div>
    </div>
  </form>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import CrystalOrb from "./CrystalOrb.vue";
import { findTopic, recommendedQuestions } from "@/utils/tarotReading";

const props = defineProps({
  catalogue: { type: Object, required: true },
  question: { type: String, default: "" },
  topicId: { type: String, default: "" },
  problem: { type: String, default: "" },
});
const emit = defineEmits(["update:question", "update:topicId", "next"]);
const examples = ref([]);
const selectedTopic = computed(() => findTopic(props.catalogue, props.topicId));
const orbCaption = computed(() => ({ general: "星雾流转", career: "拾光向上", wealth: "金色流光", relations: "星点相连", romance: "心光与花瓣", growth: "新芽与涟漪" })[props.topicId] || "星雾流转");
function refreshExamples() { examples.value = recommendedQuestions(props.catalogue, props.topicId, examples.value); }
watch(() => props.topicId, () => { examples.value = recommendedQuestions(props.catalogue, props.topicId); }, { immediate: true });

const length = computed(() => [...props.question.trim()].length);
const charge = computed(() => Math.min(1, length.value / 36).toFixed(2));
const breathCycle = ["吸气 · 想着你的问题", "停留 · 让它变得清晰", "呼气 · 放下预设的答案"];
const breathIndex = ref(0);
const breathText = computed(() => breathCycle[breathIndex.value]);
let timer = 0;

onMounted(() => {
  if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
  // Matches the 6s orb keyframes: two seconds per phrase.
  timer = window.setInterval(() => { breathIndex.value = (breathIndex.value + 1) % breathCycle.length; }, 2000);
});
onBeforeUnmount(() => window.clearInterval(timer));
</script>

<style scoped>
.question-orb {
  display: grid;
  grid-template-columns: minmax(180px, 250px) minmax(0, 1fr);
  gap: 36px;
  align-items: start;
}

.orb-column { display: grid; justify-items: center; gap: 14px; padding-top: 12px; }
.orb-caption { margin: -3px 0 0; color: #e6d0a7; font-size: 13px; letter-spacing: .12em; }
.breath-hint { min-height: 1.5em; margin: 0; color: #cdbf9f; font-size: 13px; letter-spacing: .08em; text-align: center; }

.question-fields { display: grid; gap: 20px; min-width: 0; }
.topic-field { margin: 0; padding: 0; border: 0; min-width: 0; }
.topic-field legend, .field-label { margin-bottom: 11px; color: #f6e7c4; font-weight: 700; }
.topic-chips { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 9px; }
.topic-chips label {
  position: relative;
  display: grid;
  gap: 4px;
  padding: 12px 14px;
  border: 1px solid #8d7a5866;
  border-radius: 12px;
  background: #ffffff07;
  cursor: pointer;
  transition: border-color .2s ease, background-color .2s ease, transform .2s ease;
}
.topic-chips label:hover { transform: translateY(-2px); }
.topic-chips label.selected { border-color: #e2c07b; background: #e2c07b1c; }
.topic-chips label:focus-within { outline: 2px solid #f1d18b; outline-offset: 2px; }
.topic-chips input { position: absolute; opacity: 0; pointer-events: none; }
.topic-chips span { color: #fff4dc; font-weight: 700; }
.topic-chips small { color: #aeb3c6; font-size: 12px; line-height: 1.5; }

.question-examples { min-width: 0; }
.examples-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 9px; }
.examples-heading h3 { margin: 0; color: #d9cbaa; font-size: 13px; font-weight: 600; }
.refresh-examples { flex-shrink: 0; display: flex; align-items: center; gap: 5px; padding: 5px 7px; color: #dfc48b; background: transparent; border: 0; font: inherit; font-size: 12px; cursor: pointer; }
.refresh-examples:hover { color: #fff0c3; }
.example-list { display: grid; gap: 7px; }
.example-list button { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 10px 12px; text-align: left; color: #bac3d6; border: 1px solid #727d9b40; border-radius: 9px; background: #0a10284d; font: inherit; font-size: 13px; line-height: 1.6; cursor: pointer; }
.example-list button span { flex-shrink: 0; color: #cbb783; }
.example-list button:hover { color: #fff3da; border-color: #b59a6c88; background: #cfb9890a; }
.example-list button:focus-visible, .refresh-examples:focus-visible { outline: 2px solid #f1d18b; outline-offset: 2px; }

.question-field { display: grid; gap: 0; }
textarea {
  width: 100%;
  box-sizing: border-box;
  min-height: 96px;
  padding: 14px 15px;
  border: 1px solid #b8975c88;
  border-radius: 12px;
  background: #0b1124cc;
  color: #fff8e8;
  font: inherit;
  line-height: 1.7;
  resize: vertical;
}
textarea::placeholder { color: #8d93a8; }
textarea:focus { outline: 2px solid #f1d18b; outline-offset: 1px; border-color: transparent; }
.field-meta { display: flex; justify-content: space-between; gap: 12px; margin-top: 7px; color: #9ca2b6; font-size: 12px; }

.tips { color: #c7cbd8; font-size: 13px; }
.tips summary { width: fit-content; color: #e7c886; cursor: pointer; }
.tips ul { margin: 10px 0 0; padding-left: 20px; line-height: 1.8; }
.field-problem { margin: 0; color: #f2b9a8; font-size: 13px; }

@media (max-width: 760px) {
  .question-orb { grid-template-columns: 1fr; gap: 18px; }
  .field-meta { flex-direction: column; gap: 2px; }
  .topic-chips { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (prefers-reduced-motion: reduce) {
  .topic-chips label { transition: none; }
}
</style>
