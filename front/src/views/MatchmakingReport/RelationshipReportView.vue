<template>
  <section class="relationship-report">
    <header class="report-header">
      <div>
        <router-link class="back-link" to="/matchmaking-report">← 返回会客厅</router-link>
        <p class="eyebrow">MOONLIT SALON · RELATIONSHIP EXPLORATION</p>
        <h1>{{ identity.titleId || identity.title || "关系探索报告" }}</h1>
        <p class="subtitle">{{ identity.headline || "给这段关系留一盏不急着下结论的灯。" }}</p>
        <p class="meta">{{ stageLabel }} · {{ formatDate(report.createdAt) }}</p>
      </div>
      <button v-if="report.viewerCanDelete" class="delete" type="button" @click="$emit('delete')">删除本报告</button>
    </header>

    <nav class="chapter-nav" aria-label="报告章节">
      <button v-for="chapter in chapters" :key="chapter.key" type="button" :class="{ active: activeChapter === chapter.key }" :aria-current="activeChapter === chapter.key ? 'page' : undefined" @click="activeChapter = chapter.key">
        <span>{{ chapter.number }}</span>{{ chapter.label }}
      </button>
    </nav>

    <section class="chapter" aria-live="polite">
      <template v-if="activeChapter === 'identity'">
        <div class="chapter-heading"><p class="eyebrow">01 · IDENTITY</p><h2>你正在怎样靠近一段关系</h2><p>{{ identity.introduction || "这不是一份判定书，而是一张把感受、偏好和当下处境摆到桌面上的地图。" }}</p></div>
        <div class="identity-grid">
          <article class="feature-card personality-card">
            <span class="card-kicker">关系人格倾向</span>
            <strong>{{ personality.label || personality.tendencyCode || "待观察" }}</strong>
            <p>{{ personality.explanation || "信息还不够形成稳定倾向，先从真实对话开始观察。" }}</p>
            <div v-if="personality.axes" class="axis-list"><span v-for="(value, key) in personality.axes" :key="key"><b>{{ key }}</b>{{ value.name }} · {{ value.strength }}{{ value.dominant === "X" ? "" : ` ${value.dominant}` }}</span></div>
            <small>基于本次自报或问卷，不代表官方 MBTI、诊断或预测。</small>
          </article>
          <article class="feature-card note-card"><span class="card-kicker">这次探索想带走</span><p>{{ intentLabel }}</p><span class="card-kicker">关系阶段</span><p>{{ stageLabel }}</p></article>
        </div>
        <div class="tarot-preview"><div class="section-title"><span class="card-kicker">THREE CARDS</span><h3>三张牌，三个留给自己的问题</h3></div><div class="mini-cards"><article v-for="card in cards" :key="card.cardId"><TarotHoloCard v-if="!failedImages[card.cardId]" class="mini-card-art" compact :src="cardImage(card.cardId)" :alt="`${card.name}牌面`" @image-error="markImageFailed(card.cardId)" /><div v-else class="mini-card-art failed-card" aria-hidden="true">✦</div><strong>{{ card.name }}</strong><small>{{ slotLabel(card.slot) }}</small></article></div><div class="tarot-reading-list"><article v-for="reading in tarotReadings" :key="reading.slot"><span class="card-kicker">{{ slotLabel(reading.slot) }}</span><p>{{ reading.interpretation }}</p></article></div></div>
      </template>

      <template v-else-if="activeChapter === 'manual'">
        <div class="chapter-heading"><p class="eyebrow">02 · MANUAL</p><h2>你的关系使用说明</h2><p>五个偏好没有标准答案，它们只是帮你在具体关系里更快辨认“对我重要的是什么”。</p></div>
        <div class="manual-grid"><article v-for="item in manual" :key="item.id" class="manual-card"><span>{{ item.id.toUpperCase() }}</span><h3>{{ item.name }}</h3><p class="choice"><b>{{ item.answerLabel || item.answerValue }}</b></p><p>{{ item.preference || item.explanation || item.question || "这是一处可以继续观察的偏好。" }}</p><p v-if="item.misunderstanding"><b>可能的误会：</b>{{ item.misunderstanding }}</p><p v-if="item.expression"><b>可以这样说：</b>{{ item.expression }}</p></article></div>
      </template>

      <template v-else-if="activeChapter === 'patterns'">
        <div class="chapter-heading"><p class="eyebrow">03 · PATTERNS</p><h2>反复出现的关系模式</h2><p>把报告里的关键词带回真实场景，看看哪些是稳定需要，哪些只是此刻的保护动作。</p></div>
        <div class="pattern-grid"><article v-for="(item, index) in patterns" :key="index" class="pattern-card"><span>0{{ index + 1 }}</span><p>{{ item }}</p></article></div>
      </template>

      <template v-else-if="activeChapter === 'attraction'">
        <figure v-if="report.partnerImage" class="partner-portrait">
          <img :src="'data:image/png;base64,' + report.partnerImage" alt="AI 生成的虚构伴侣形象示意" />
          <figcaption>AI 虚构形象，仅作审美与关系探索示意，不代表真实人物或未来伴侣。</figcaption>
        </figure>
        <div class="chapter-heading"><p class="eyebrow">04 · ATTRACTION</p><h2>你可能会被什么吸引</h2><p>吸引力可以被看见，也可以被重新选择。下面的文字只提供观察角度，不替你判断对错。</p></div>
        <div class="attraction-grid"><article><span class="card-kicker">容易被吸引</span><p>{{ attraction.pull || "先留意让你感到安心、好奇或被理解的瞬间。" }}</p></article><article><span class="card-kicker">需要留意</span><p>{{ attraction.watch || "当熟悉感很强时，也给自己留一点慢下来的时间。" }}</p></article><article><span class="card-kicker">可练习的选择</span><p>{{ attraction.practice || "把感受说出来，再观察对方是否愿意回应。" }}</p></article></div>
      </template>

      <template v-else-if="activeChapter === 'scripts'">
        <div class="chapter-heading"><p class="eyebrow">05 · NEXT STEPS</p><h2>下一步，不用一次做完</h2><p>{{ nextSteps.experiment || "从一次小而真实的表达开始，把关系从猜测带回交流。" }}</p></div>
        <div class="scripts-list"><article v-for="(script, index) in scripts" :key="index"><span>话术 0{{ index + 1 }}</span><p>{{ script.text }}</p><p v-if="script.scenario" class="script-context"><b>适用场景：</b>{{ script.scenario }}</p><p v-if="script.explanation" class="script-context"><b>为什么：</b>{{ script.explanation }}</p><button type="button" class="copy-button" @click="copyText(script.text, index)">{{ copiedIndex === index ? "已复制 ✓" : "复制这句" }}</button></article></div>
      </template>

      <template v-else>
        <div class="chapter-heading"><p class="eyebrow">06 · FIELD NOTES</p><h2>把生活底稿带进关系里</h2><p>只展示你主动提供的生活信息；没有填写的部分，不需要为了完整而补齐。</p></div>
        <dl v-if="Object.keys(lifeLedger).length" class="ledger"><div v-for="(value, key) in lifeLedger" :key="key"><dt>{{ ledgerLabel(key) }}</dt><dd>{{ value || "未填写" }}</dd></div></dl>
        <div v-else class="empty-card">这次没有填写生活底稿。关系也可以从一个具体问题开始。</div>
        <div class="share-card"><div><span class="card-kicker">LOCAL SHARE CARD</span><h3>带走一句今晚的提醒</h3><p>分享卡只在你的浏览器本地生成，不上传完整报告。</p></div><label><input v-model="shareIncludePersonality" type="checkbox" /> 包含关系人格倾向</label><button type="button" class="primary-button" @click="downloadShareCard">生成并下载分享卡</button><p v-if="shareMessage" class="share-message">{{ shareMessage }}</p></div>
      </template>
    </section>
    <p class="disclaimer">本报告用于自我探索与关系对话，不提供预测、诊断、撮合成功率或对他人的确定性判断。人格倾向来自本次自报或产品问卷，不代表官方 MBTI。</p>
  </section>
</template>

<script setup>
import { computed, ref } from "vue";
import TarotHoloCard from "@/components/matchmaking/TarotHoloCard.vue";

defineEmits(["delete"]);
const props = defineProps({ report: { type: Object, required: true } });
const activeChapter = ref("identity");
const copiedIndex = ref(-1);
const shareIncludePersonality = ref(true);
const shareMessage = ref("");
const failedImages = ref({});
const chapters = [
  { key: "identity", number: "01", label: "关系身份" },
  { key: "manual", number: "02", label: "关系手册" },
  { key: "patterns", number: "03", label: "反复模式" },
  { key: "attraction", number: "04", label: "吸引与边界" },
  { key: "scripts", number: "05", label: "下一步话术" },
  { key: "field", number: "06", label: "生活底稿" },
];
const narrative = computed(() => props.report.narrative || {});
const identity = computed(() => narrative.value.identity || {});
const personality = computed(() => props.report.personality || {});
const stageLabel = computed(() => ({ single: "单身探索", gettingCloser: "正在靠近", inRelationship: "关系进行中", reflecting: "回顾一段关系" }[props.report.profile?.relationshipStage] || "关系探索"));
const intentLabel = computed(() => ({ understandSelf: "更了解自己", communicateBetter: "更好地沟通", understandAttraction: "理解自己的吸引模式", nextStep: "找到下一步" }[props.report.profile?.explorationIntent] || "更清楚地认识自己的关系需要"));
const cards = computed(() => props.report.tarot?.cards || []);
const tarotReadings = computed(() => props.report.tarotReadings || narrative.value.tarotReadings || []);
const manual = computed(() => {
  const generated = props.report.relationshipManual || narrative.value.relationshipManual || [];
  const preferences = props.report.relationshipPreferences || [];
  return generated.map((item, index) => ({
    ...item,
    id: item.id || item.dimensionId || preferences[index]?.id || `r0${index + 1}`,
    name: item.name || preferences[index]?.name || item.dimensionId,
    answerLabel: preferences[index]?.tendencyLabel,
    answerValue: preferences[index]?.answerValue,
  }));
});
const patterns = computed(() => (props.report.recurringPatterns || narrative.value.recurringPatterns || []).map((item) => typeof item === "string" ? item : [item.trigger, item.reaction, item.misunderstanding, item.alternative].filter(Boolean).join(" → ")));
const attraction = computed(() => {
  const value = props.report.attraction || narrative.value.attraction || {};
  return { pull: value.pull || value.spark, watch: value.watch || value.friction, practice: value.practice || value.sustainable };
});
const nextSteps = computed(() => props.report.nextSteps || narrative.value.nextSteps || {});
const scripts = computed(() => (nextSteps.value.scripts || []).map((item) => typeof item === "string" ? { text: item, scenario: "", explanation: "" } : { text: item.words || item.scenario || "", scenario: item.scenario || "", explanation: item.explanation || "" }));
const lifeLedger = computed(() => props.report.lifeLedger || {});

function markImageFailed(id) { failedImages.value = { ...failedImages.value, [id]: true }; }
function cardImage(id) { return failedImages.value[id] ? "" : `/tarot/${id}.webp`; }
function slotLabel(slot) { return ({ self: "看见自己", dynamic: "看见关系", present: "此刻的你", shadow: "关系中的暗线", next: "下一步" }[slot] || "牌面提示"); }
function ledgerLabel(key) { return ({ city: "所在城市", workRhythm: "工作节奏", livingExpectation: "生活期待", missing: "备注" }[key] || key); }
function formatDate(value) { return value ? new Intl.DateTimeFormat("zh-CN", { dateStyle: "long", timeStyle: "short" }).format(new Date(value)) : "—"; }
async function copyText(value, index) {
  try { await navigator.clipboard.writeText(value || ""); copiedIndex.value = index; setTimeout(() => (copiedIndex.value = -1), 2000); }
  catch { shareMessage.value = "复制失败，请手动选择文本。"; }
}
function wrapText(ctx, text, maxWidth) {
  const chars = Array.from(String(text || "")); const lines = []; let line = "";
  chars.forEach((char) => { const next = line + char; if (ctx.measureText(next).width > maxWidth && line) { lines.push(line); line = char; } else line = next; });
  if (line) lines.push(line); return lines;
}
function downloadShareCard() {
  const canvas = document.createElement("canvas"); canvas.width = 1080; canvas.height = 1440;
  const ctx = canvas.getContext("2d"); const gradient = ctx.createLinearGradient(0, 0, 1080, 1440);
  gradient.addColorStop(0, "#101b38"); gradient.addColorStop(1, "#46345d"); ctx.fillStyle = gradient; ctx.fillRect(0, 0, 1080, 1440);
  ctx.fillStyle = "#f3dfad"; ctx.font = "700 28px sans-serif"; ctx.fillText("MOONLIT SALON", 90, 120);
  ctx.fillStyle = "#fff8ed"; ctx.font = "700 68px sans-serif"; wrapText(ctx, identity.value.titleId || identity.value.title || "关系探索报告", 900).slice(0, 2).forEach((line, i) => ctx.fillText(line, 90, 270 + i * 88));
  ctx.fillStyle = "#d7cbe4"; ctx.font = "36px sans-serif"; const reminder = identity.value.headline || nextSteps.value.experiment || "给关系留一盏不急着下结论的灯。";
  wrapText(ctx, reminder, 900).slice(0, 5).forEach((line, i) => ctx.fillText(line, 90, 520 + i * 58));
  if (shareIncludePersonality.value && (personality.value.label || personality.value.tendencyCode)) { ctx.fillStyle = "#f3dfad"; ctx.font = "700 30px sans-serif"; ctx.fillText("关系人格倾向", 90, 900); ctx.fillStyle = "#fff8ed"; ctx.font = "700 54px sans-serif"; ctx.fillText(personality.value.label || personality.value.tendencyCode, 90, 980); }
  ctx.fillStyle = "#d7cbe4"; ctx.font = "28px sans-serif"; ctx.fillText("仅供自我探索与关系对话", 90, 1300); ctx.fillText("不代表诊断、预测或官方 MBTI", 90, 1350);
  canvas.toBlob((blob) => { if (!blob) { shareMessage.value = "分享卡生成失败，请稍后重试。"; return; } const url = URL.createObjectURL(blob); const link = document.createElement("a"); link.href = url; link.download = "moonlit-relationship-share.png"; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000); shareMessage.value = "分享卡已生成并请求下载，请在浏览器或系统下载列表确认。"; }, "image/png");
}
</script>

<style scoped>
.partner-portrait { margin: 0 0 24px; max-width: 360px; }
.partner-portrait img { display: block; width: 100%; border-radius: 18px; }
.partner-portrait figcaption { margin-top: 10px; font-size: 12px; line-height: 1.7; }
.relationship-report { --navy: #101b38; --ink: #29304a; --muted: #69718a; --cream: #fffaf1; --gold: #b88e4a; --lavender: #eee8f6; max-width: 1120px; margin: 0 auto; padding: 34px 20px 80px; color: var(--ink); }
.report-header { display: flex; justify-content: space-between; gap: 24px; padding: 46px 48px; color: #fff8ed; border-radius: 28px; background: radial-gradient(circle at 82% 16%, #d8b66555 0 72px, transparent 74px), linear-gradient(135deg, #101b38, #312850 62%, #62486a); box-shadow: 0 18px 50px #17204022; }
.back-link { color: #d7cbe4; text-decoration: none; }.eyebrow,.card-kicker { display: block; margin: 22px 0 8px; color: var(--gold); font-size: 11px; font-weight: 800; letter-spacing: .16em; }.report-header .eyebrow { color: #f3dfad; }.report-header h1 { margin: 8px 0; font-size: clamp(32px, 5vw, 58px); line-height: 1.1; }.subtitle { max-width: 620px; margin: 16px 0; color: #e7dff0; font-size: 18px; line-height: 1.7; }.meta { color: #c8bedb; font-size: 13px; }.delete { align-self: start; border: 1px solid #ffffff55; border-radius: 999px; padding: 9px 15px; color: #fff; background: transparent; cursor: pointer; }
.chapter-nav { display: grid; grid-template-columns: repeat(6, 1fr); gap: 8px; margin: 18px 0; }.chapter-nav button { min-height: 56px; border: 1px solid #e5dfeb; border-radius: 14px; color: var(--muted); background: #fff; cursor: pointer; }.chapter-nav button span { display: block; margin-bottom: 3px; color: var(--gold); font-size: 11px; }.chapter-nav button.active { color: #fff; border-color: var(--navy); background: var(--navy); }
.chapter { padding: 32px; border: 1px solid #eee7dc; border-radius: 22px; background: var(--cream); }.chapter-heading { max-width: 780px; }.chapter-heading .eyebrow { margin-top: 0; }.chapter-heading h2 { margin: 0 0 12px; font-size: 32px; color: var(--navy); }.chapter-heading p { line-height: 1.85; color: var(--muted); }.identity-grid,.attraction-grid,.manual-grid,.pattern-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 14px; margin-top: 24px; }.feature-card,.manual-card,.pattern-card,.attraction-grid article,.share-card,.empty-card { padding: 24px; border: 1px solid #eee3d3; border-radius: 18px; background: #fff; }.feature-card strong { display: block; margin: 10px 0; color: var(--navy); font-size: 27px; }.feature-card p,.manual-card p,.pattern-card p,.attraction-grid p,.share-card p { line-height: 1.8; }.feature-card small { display: block; margin-top: 18px; color: var(--muted); line-height: 1.6; }.note-card { display: grid; align-content: center; }.note-card .card-kicker:not(:first-child) { margin-top: 22px; }.note-card p { margin: 0; font-size: 19px; }.axis-list { display: flex; flex-wrap: wrap; gap: 8px; }.axis-list span { padding: 6px 9px; border-radius: 8px; color: #5f5275; background: var(--lavender); font-size: 13px; }.axis-list b { margin-right: 5px; color: var(--navy); }
.tarot-preview { margin-top: 28px; padding-top: 22px; border-top: 1px solid #eadfcd; }.section-title h3 { margin: 0; color: var(--navy); }.mini-cards { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; margin-top: 16px; }.mini-cards article { text-align: center; }.mini-card-art { aspect-ratio: 2 / 3; }.failed-card { display: grid; place-items: center; border-radius: 13px; background: linear-gradient(145deg, #182444, #805a73); color: #f3dfad; font-size: 40px; }.mini-cards strong,.mini-cards small { display: block; margin-top: 8px; }.mini-cards small { color: var(--muted); }.tarot-reading-list { display: grid; gap: 10px; margin-top: 18px; }.tarot-reading-list article { padding: 16px 18px; border-radius: 14px; background: #fff; border: 1px solid #eee3d3; }.tarot-reading-list .card-kicker { margin: 0 0 6px; }.tarot-reading-list p { margin: 0; line-height: 1.8; }
.manual-grid { grid-template-columns: repeat(3, 1fr); }.manual-card > span,.pattern-card > span,.scripts-list article > span { color: var(--gold); font-size: 12px; font-weight: 800; letter-spacing: .12em; }.manual-card h3 { margin: 10px 0; color: var(--navy); }.manual-card p + p { margin-top: 8px; }.choice { color: var(--navy); font-weight: 700; }.pattern-grid { grid-template-columns: repeat(3, 1fr); }.pattern-card p { margin-bottom: 0; }.attraction-grid { grid-template-columns: repeat(3, 1fr); }.scripts-list { display: grid; gap: 12px; margin-top: 24px; }.scripts-list article { padding: 20px 24px; border-radius: 16px; background: #fff; border: 1px solid #eee3d3; }.scripts-list p { max-width: 760px; margin: 10px 0; line-height: 1.8; }.scripts-list .script-context { color: var(--muted); font-size: 14px; }.copy-button,.primary-button { border: 0; border-radius: 999px; padding: 9px 14px; cursor: pointer; }.copy-button { color: var(--navy); background: var(--lavender); }.primary-button { color: #fff; background: var(--navy); }.ledger { display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px; margin: 24px 0; }.ledger div { padding: 18px; border-radius: 14px; background: #fff; }.ledger dt { color: var(--muted); font-size: 13px; }.ledger dd { margin: 8px 0 0; line-height: 1.6; }.share-card { display: grid; gap: 12px; margin-top: 30px; background: linear-gradient(135deg, #f4edff, #fff8e8); }.share-card h3 { margin: 0; color: var(--navy); }.share-card label { color: var(--muted); font-size: 14px; }.share-message { margin: 0; color: var(--gold); }.disclaimer { margin: 18px 4px; color: var(--muted); font-size: 12px; line-height: 1.7; }
@media (max-width: 760px) { .relationship-report { padding: 18px 12px 48px; }.report-header { display: block; padding: 28px 24px; }.report-header .delete { margin-top: 20px; }.chapter-nav { display: flex; overflow-x: auto; padding-bottom: 3px; }.chapter-nav button { min-width: 112px; }.chapter { padding: 22px 16px; }.identity-grid,.manual-grid,.pattern-grid,.attraction-grid,.ledger { grid-template-columns: 1fr; }.mini-cards { gap: 8px; }.mini-cards strong { font-size: 14px; }.mini-card-art { border-radius: 9px; }.chapter-heading h2 { font-size: 27px; } }
@media (max-width: 340px) { .relationship-report { padding-inline: 8px; }.report-header { padding-inline: 16px; border-radius: 18px; }.chapter { padding-inline: 12px; }.mini-cards { gap: 5px; }.mini-cards small { font-size: 10px; }.feature-card,.manual-card,.pattern-card,.attraction-grid article,.share-card { padding: 17px; } }
</style>
