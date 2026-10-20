<template>
  <div class="tarot-page">
    <CelestialBackdrop :aura="flow.aura.value" />
    <div class="tarot-shell">
      <section class="tarot-hero" aria-labelledby="tarot-title">
        <div class="hero-copy">
          <p class="tarot-eyebrow">MOONLIT DIVINATION · MAJOR ARCANA</p>
          <h1 id="tarot-title">月下占卜</h1>
          <p class="hero-lead">静心提问，洗牌、切牌，再从二十二张大阿卡纳中亲手抽出你的牌。正位与逆位，都是月光给出的回声。</p>
          <ul class="hero-tags">
            <li>22 张大阿卡纳</li>
            <li>正位 · 逆位</li>
            <li>单张 · 圣三角 · 二择一</li>
          </ul>
        </div>
        <div class="hero-sigil">
          <AstrolabeSigil :active="flow.phase.value === 'shuffling'" />
          <div class="hero-cards" aria-hidden="true">
            <span v-for="(card, index) in heroCards" :key="card.variantId" class="hero-card" :style="{ '--i': index, '--aura': themeFamily(card.themeId).aura }">
              <img :src="card.image" alt="" decoding="async" />
            </span>
          </div>
        </div>
      </section>

      <TrialGate v-if="!auth.isLoggedIn" :redeeming="redeeming" :error="gateError" @login="auth.requestLogin('/tarot-reading')" @redeem="redeemTrial" />

      <template v-else>
        <p v-if="tarotApiMode === 'mock'" class="tarot-notice">开发模拟模式：抽牌与 AI 解读均为浏览器内的假数据，不会请求后端。</p>
        <p v-if="pageError" class="tarot-notice error" role="alert">{{ pageError }}</p>
        <ReadingProgress v-if="activeTask" :task="activeTask" :poll-error="pollError" />

        <section v-if="trialFinished" class="tarot-panel trial-finished">
          <p class="tarot-eyebrow">PRIVATE BETA</p>
          <h2>你的内测解读已经完成</h2>
          <p>这枚邀请码的免费解读已使用，可以随时回来阅读这份报告。</p>
          <router-link class="tarot-button primary" :to="`/tarot-reading/report/${auth.user?.trialAccess?.reportId}`">打开占卜报告</router-link>
        </section>

        <section v-else class="ritual tarot-panel" aria-labelledby="ritual-title">
          <ol class="ritual-steps" aria-label="占卜步骤">
            <li v-for="(step, index) in RITUAL_STEPS" :key="step.key" :class="{ done: index < flow.stepIndex.value, current: index === flow.stepIndex.value }" :aria-current="index === flow.stepIndex.value ? 'step' : undefined">
              <span class="step-star" aria-hidden="true"></span><span class="step-label">{{ step.label }}</span>
            </li>
          </ol>

          <header class="ritual-heading">
            <p class="tarot-eyebrow">{{ currentStep.eyebrow }}</p>
            <h2 id="ritual-title" ref="ritualTitle" tabindex="-1">{{ currentStep.title }}</h2>
            <p>{{ currentStep.hint }}</p>
          </header>

          <p v-if="flow.error.value" class="tarot-notice error ritual-error" role="alert">{{ flow.error.value }}</p>
          <p v-if="loading" class="ritual-loading" role="status">正在点亮星盘…</p>

          <QuestionOrb
            v-else-if="flow.phase.value === 'question'"
            v-model:question="flow.form.question"
            v-model:topic-id="flow.form.topicId"
            :catalogue="catalogue"
            :problem="flow.problem.value"
            @next="flow.toSpread"
          />
          <SpreadPicker
            v-else-if="flow.phase.value === 'spread'"
            v-model:spread-id="flow.form.spreadId"
            v-model:option-a="flow.form.optionA"
            v-model:option-b="flow.form.optionB"
            :catalogue="catalogue"
            :problem="flow.problem.value"
            :busy="flow.busy.value"
            @back="flow.backToQuestion"
            @next="flow.beginShuffle"
          />
          <ShuffleRitual v-else-if="flow.phase.value === 'shuffling'" :ready="flow.drawReady.value" @done="flow.finishShuffle" />
          <div v-else-if="flow.spread.value" class="ritual-table">
            <blockquote class="ritual-question">
              「{{ flow.question.value }}」
              <small>{{ flow.spread.value.name }} · {{ flow.topic.value.label }}</small>
            </blockquote>
            <SpreadBoard
              ref="spreadBoard"
              :spread="flow.spread.value"
              :cards="flow.boardCards.value"
              :filled="flow.picks.value.length"
              :revealed="flow.revealed.value"
              :interactive="flow.phase.value !== 'picking'"
              :fly-from="flow.flyFrom.value"
              :complete="flow.phase.value === 'reading'"
              @reveal="flow.reveal"
            />
            <Transition name="tarot-fade">
              <CardFan
                v-if="flow.phase.value === 'picking'"
                :picks-required="flow.spread.value.cardCount"
                :picked="flow.picks.value"
                :disabled="flow.busy.value"
                @pick="flow.pick"
              />
            </Transition>
            <p v-if="flow.phase.value === 'picking' && flow.busy.value" class="ritual-loading" role="status">牌阵已定，正在取回你的牌…</p>
            <div v-if="flow.phase.value === 'revealing'" class="stage-actions centered">
              <button class="tarot-button primary" type="button" @click="spreadBoard?.revealNext()">翻开下一张</button>
              <button class="tarot-button quiet" type="button" @click="spreadBoard?.revealAll()">全部翻开</button>
            </div>
            <InstantReading
              v-if="flow.phase.value === 'reading' && flow.reading.value"
              :reading="flow.reading.value"
              :status="status"
              :disclaimer="catalogue.disclaimer"
              :trial="auth.isMatchmakingTrial"
              :busy="creating"
              :locked="aiLocked"
              @request-ai="requestAiReading"
              @restart="flow.restart"
            />
          </div>
        </section>

        <ReadingArchive
          :archive="archive"
          :expanded="archiveExpanded"
          :is-root="auth.isRoot"
          :can-delete-all="!auth.isMatchmakingTrial && archive.total > 0"
          @toggle="archiveExpanded = !archiveExpanded"
          @filter="applyArchiveFilter"
          @page="changeArchivePage"
          @delete-all="deleteAllData"
        />
      </template>

      <TarotDeckGallery :catalogue="catalogue" />
    </div>

    <NModal v-model:show="confirmOpen" preset="dialog" class="tarot-confirm-modal" title="确认生成 AI 深度解读">
      <p>本次将扣除 {{ status.creditCost }} 积分，当前余额 {{ auth.credits }}。生成失败会自动退还。</p>
      <template #action>
        <button class="tarot-confirm-button quiet" type="button" @click="confirmOpen = false">再想想</button>
        <button class="tarot-confirm-button" type="button" :disabled="creating" @click="submitAiReading">确认生成</button>
      </template>
    </NModal>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import { NModal } from "naive-ui";
import { useRouter } from "vue-router";
import { useAuthStore } from "@/stores/auth";
import { createTaskPoller } from "@/utils/taskPoller";
import { trialWorkspaceState } from "@/utils/matchmakingTrial";
import {
  createTarotReadingReport, deleteMatchmakingData, getMatchmakingReportArchive, getMatchmakingStatus,
  getMatchmakingTask, getMatchmakingTasks, tarotApiMode,
} from "@/api/tarotReading";
import { RITUAL_STEPS, useReadingFlow } from "./useReadingFlow";
import { sampleThemedCards } from "@/data/tarotThemeLibrary";
import { themeFamily } from "@/utils/tarotThemes";
import CelestialBackdrop from "@/components/tarot/CelestialBackdrop.vue";
import AstrolabeSigil from "@/components/tarot/AstrolabeSigil.vue";
import QuestionOrb from "@/components/tarot/QuestionOrb.vue";
import SpreadPicker from "@/components/tarot/SpreadPicker.vue";
import ShuffleRitual from "@/components/tarot/ShuffleRitual.vue";
import SpreadBoard from "@/components/tarot/SpreadBoard.vue";
import CardFan from "@/components/tarot/CardFan.vue";
import InstantReading from "@/components/tarot/InstantReading.vue";
import ReadingProgress from "@/components/tarot/ReadingProgress.vue";
import ReadingArchive from "@/components/tarot/ReadingArchive.vue";
import TarotDeckGallery from "@/components/tarot/TarotDeckGallery.vue";
import TrialGate from "@/components/tarot/TrialGate.vue";
import "@/styles/tarot-reading.scss";

const auth = useAuthStore();
const router = useRouter();
const flow = useReadingFlow();
const catalogue = flow.catalogue;

const loading = ref(true), pageError = ref(""), pollError = ref(""), gateError = ref("");
const redeeming = ref(false), creating = ref(false), confirmOpen = ref(false), archiveExpanded = ref(false);
const activeTask = ref(null), ritualTitle = ref(null), spreadBoard = ref(null);
const status = reactive({ creditCost: 2, credits: 0, retentionDays: 30, tarotAiEnabled: false });
const archive = reactive({ items: [], page: 1, pageSize: 10, total: 0, totalPages: 0, owner: "" });
let disposed = false, workspaceVersion = 0;

// Three cards from the 110-theme library drift beside the astrolabe; a new trio on every visit.
const heroCards = sampleThemedCards(3);
const currentStep = computed(() => RITUAL_STEPS[Math.max(0, flow.stepIndex.value)]);
const trialFinished = computed(() => auth.isMatchmakingTrial && trialWorkspaceState(auth.user?.trialAccess || {}) === "report");
const aiLocked = computed(() => flow.draw.value?.state === "consumed" || ["queued", "running"].includes(activeTask.value?.status));

const poller = createTaskPoller({
  fetchTask: getMatchmakingTask,
  onError: text => { pollError.value = text; },
  onTask: async task => {
    activeTask.value = task;
    if (task.status === "done") await afterTaskSettled(task);
    else if (task.status === "error" && !task.compensationPending) await afterTaskSettled(task);
  },
});

async function afterTaskSettled(task) {
  if (auth.isMatchmakingTrial && task.status === "done" && task.reportId) {
    await router.replace(`/tarot-reading/report/${task.reportId}`);
    return;
  }
  // A failed reading restores the draw on the server, so the ritual can offer the AI button again.
  if (task.status === "error") await flow.restore().catch(() => {});
  else if (flow.draw.value?.state === "consumed") flow.restart();
  await Promise.all([loadStatus(), loadArchive(1)]).catch(() => {});
}

async function loadStatus() {
  const response = await getMatchmakingStatus();
  Object.assign(status, response.data || {});
  if (!auth.isMatchmakingTrial && response.data?.credits != null) auth.updateCredits(response.data.credits);
}

async function loadArchive(page = archive.page) {
  const response = await getMatchmakingReportArchive(page, archive.pageSize, archive.owner);
  Object.assign(archive, response.data || {});
}

async function loadWorkspace() {
  const version = ++workspaceVersion;
  loading.value = true;
  pageError.value = "";
  poller.stop();
  activeTask.value = null;
  flow.restart();
  try {
    const [, tasks] = await Promise.all([loadStatus(), getMatchmakingTasks(), flow.restore(), loadArchive(1)]);
    if (disposed || version !== workspaceVersion) return;
    const running = (tasks.data || []).find(task => ["queued", "running"].includes(task.status) || task.compensationPending);
    if (running) { activeTask.value = running; poller.start(running.id); }
  } catch (e) {
    if (version === workspaceVersion) pageError.value = e.message || "星盘暂时没有回应，请稍后刷新";
  } finally {
    if (version === workspaceVersion) loading.value = false;
  }
}

function requestAiReading() {
  if (auth.isMatchmakingTrial) submitAiReading();
  else confirmOpen.value = true;
}

async function submitAiReading() {
  if (creating.value || !flow.draw.value) return;
  confirmOpen.value = false;
  creating.value = true;
  pageError.value = "";
  try {
    const response = await createTarotReadingReport(flow.draw.value.drawId);
    flow.markConsumed();
    activeTask.value = { id: response.data.taskId, status: "queued", stage: "queued", reportVersion: response.data.reportVersion };
    if (!auth.isMatchmakingTrial && response.data?.credits != null) auth.updateCredits(response.data.credits);
    poller.start(response.data.taskId);
  } catch (e) {
    pageError.value = e.message || "解读提交失败，请稍后重试";
  } finally {
    creating.value = false;
  }
}

async function applyArchiveFilter(owner) {
  archive.owner = owner;
  try { await loadArchive(1); } catch (e) { pageError.value = e.message || "记录筛选失败"; }
}

async function changeArchivePage(page) {
  try { await loadArchive(page); } catch (e) { pageError.value = e.message || "记录分页加载失败"; }
}

async function deleteAllData() {
  if (!window.confirm("确定删除全部占卜记录、进行中的牌阵与解读吗？此操作不可恢复。")) return;
  try {
    await deleteMatchmakingData();
    poller.stop();
    activeTask.value = null;
    flow.restart();
    await Promise.all([loadStatus(), loadArchive(1)]);
  } catch (e) {
    pageError.value = e.message || "删除失败";
  }
}

async function redeemTrial(code) {
  redeeming.value = true;
  gateError.value = "";
  try {
    const user = await auth.redeemTrial(code);
    const access = user?.trialAccess || {};
    if (access.status === "COMPLETED" && access.reportId && access.reportAvailable !== false) {
      await router.replace(`/tarot-reading/report/${access.reportId}`);
    }
  } catch (e) {
    gateError.value = e.message || "邀请码验证失败";
  } finally {
    redeeming.value = false;
  }
}

// Move focus to the new step heading so keyboard and screen-reader users follow the ritual.
watch(() => flow.phase.value, async (now, before) => {
  if (!before || loading.value) return;
  await nextTick();
  ritualTitle.value?.focus({ preventScroll: now === "reading" });
});

watch(() => auth.user?.id, (now, before) => {
  if (now === before) return;
  if (now) loadWorkspace();
  else { poller.stop(); activeTask.value = null; flow.restart(); }
});

onMounted(() => {
  if (auth.isLoggedIn && !trialFinished.value) loadWorkspace();
  else loading.value = false;
});
onBeforeUnmount(() => { disposed = true; poller.stop(); });
</script>

<style scoped lang="scss">
.tarot-hero {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(220px, 330px);
  gap: 30px;
  align-items: center;
  padding: 48px 54px;
  border: 1px solid #d2ae6655;
  border-radius: 28px;
  background: radial-gradient(circle at 80% 30%, #6e5aa63d, transparent 45%), linear-gradient(125deg, #0f1631e6, #2a2142e6);
  box-shadow: 0 26px 80px #0007;
  overflow: clip;
}
.hero-copy h1 {
  margin: 0;
  color: #fff6e0;
  font: 500 clamp(40px, 6vw, 64px)/1.1 "Moonlit Serif", serif;
  letter-spacing: .08em;
  background: linear-gradient(100deg, #fff6e0 30%, #f6d58b 45%, #fff6e0 60%);
  background-size: 250% 100%;
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  animation: title-shimmer 9s ease-in-out infinite;
}
@keyframes title-shimmer { 0%, 100% { background-position: 100% 0; } 50% { background-position: 0 0; } }
.hero-lead { max-width: 560px; margin: 18px 0 0; color: #cfd2df; font-size: 16px; line-height: 1.9; }
.hero-tags { display: flex; flex-wrap: wrap; gap: 8px; margin: 24px 0 0; padding: 0; list-style: none; }
.hero-tags li { padding: 6px 12px; border: 1px solid #dcb87855; border-radius: 99px; color: #f3dfb7; font-size: 12px; }
.hero-sigil { position: relative; width: 100%; max-width: 330px; justify-self: end; }
.hero-cards { position: absolute; left: 50%; bottom: -6%; width: 0; height: 0; }
.hero-card {
  --tilt: calc((var(--i) - 1) * 18deg);
  position: absolute;
  left: -44px;
  bottom: 0;
  width: 88px;
  aspect-ratio: 2 / 3;
  overflow: hidden;
  border: 1px solid #f2d79a99;
  border-radius: 8px;
  background: #10172b;
  box-shadow: 0 0 22px var(--aura), 0 10px 24px #0009;
  transform-origin: 50% 160%;
  transform: rotate(var(--tilt));
  animation: hero-card-float 7s ease-in-out infinite;
  animation-delay: calc(var(--i) * -2.3s);
}
.hero-card img { display: block; width: 100%; height: 100%; object-fit: cover; transform: scale(1.08); }
@keyframes hero-card-float {
  0%, 100% { transform: rotate(var(--tilt)) translateY(0); }
  50% { transform: rotate(calc(var(--tilt) * 1.15)) translateY(-10px); }
}

.trial-finished h2 { margin: 0 0 8px; font: 500 26px/1.3 "Moonlit Serif", serif; }
.trial-finished p:not(.tarot-eyebrow) { color: #c7cbd8; line-height: 1.8; }

.ritual { display: grid; gap: 22px; overflow: clip; }
.ritual-steps { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); margin: 0; padding: 0; list-style: none; }
.ritual-steps li { position: relative; display: grid; justify-items: center; gap: 8px; color: #7d8398; font-size: 12px; letter-spacing: .1em; }
.ritual-steps li::before {
  content: "";
  position: absolute;
  top: 7px;
  left: calc(-50% + 9px);
  width: calc(100% - 18px);
  height: 1px;
  background: linear-gradient(90deg, #e0be7833, #e0be7833);
}
.ritual-steps li:first-child::before { display: none; }
.ritual-steps li.done::before, .ritual-steps li.current::before { background: linear-gradient(90deg, #e0be78, #e0be78aa); }
.step-star {
  width: 15px;
  height: 15px;
  clip-path: polygon(50% 0, 62% 38%, 100% 50%, 62% 62%, 50% 100%, 38% 62%, 0 50%, 38% 38%);
  background: #4a4f66;
  transition: background-color .4s ease, transform .4s ease;
}
.ritual-steps li.done { color: #c9b385; }
.ritual-steps li.done .step-star { background: #c9a35c; }
.ritual-steps li.current { color: #fff1cc; }
.ritual-steps li.current .step-star { background: #fff1cc; transform: scale(1.35); animation: step-pulse 2.4s ease-in-out infinite; }
@keyframes step-pulse { 50% { transform: scale(1.1); opacity: .7; } }

.ritual-heading { text-align: center; }
.ritual-heading h2 { margin: 0; color: #fff4dc; font: 500 clamp(24px, 3.4vw, 32px)/1.3 "Moonlit Serif", serif; }
.ritual-heading h2:focus { outline: none; }
.ritual-heading h2:focus-visible { outline: 2px solid #f1d18b; outline-offset: 6px; border-radius: 4px; }
.ritual-heading p:not(.tarot-eyebrow) { margin: 8px auto 0; max-width: 560px; color: #aab0c4; font-size: 14px; line-height: 1.7; }
.ritual-error { text-align: center; }
.ritual-loading { margin: 0; color: #e7d3a5; text-align: center; letter-spacing: .08em; }
.ritual-table { display: grid; gap: 26px; }
.ritual-question {
  margin: 0 auto;
  max-width: 680px;
  color: #f1e3c2;
  font: 500 18px/1.7 "Moonlit Serif", serif;
  text-align: center;
}
.ritual-question small { display: block; margin-top: 4px; color: #9ea4b8; font: 600 12px/1.4 system-ui, sans-serif; letter-spacing: .12em; }

.tarot-fade-enter-active, .tarot-fade-leave-active { transition: opacity .45s ease, transform .45s ease; }
.tarot-fade-enter-from, .tarot-fade-leave-to { opacity: 0; transform: translateY(18px); }

@media (max-width: 860px) {
  .tarot-hero { grid-template-columns: 1fr; padding: 34px 26px; }
  .hero-sigil { position: absolute; right: -70px; top: -50px; width: 240px; opacity: .45; pointer-events: none; }
  .hero-copy { position: relative; z-index: 1; }
  .hero-cards { display: none; }
}
@media (max-width: 600px) {
  .tarot-hero { padding: 28px 20px; border-radius: 20px; }
  .hero-lead { font-size: 14px; }
  .ritual-steps li { font-size: 11px; letter-spacing: .02em; }
  .ritual-question { font-size: 16px; }
}
@media (max-width: 360px) {
  .step-label { font-size: 10px; }
  .hero-sigil { width: 190px; right: -60px; }
}
@media (prefers-reduced-motion: reduce) {
  .hero-copy h1, .ritual-steps li.current .step-star, .hero-card { animation: none; }
  .tarot-fade-enter-active, .tarot-fade-leave-active { transition: none; }
}
</style>

<style>
.tarot-confirm-modal.n-dialog { background: #141c31; color: #fff8e8; border: 1px solid #967846; border-radius: 16px; }
.tarot-confirm-modal .n-dialog__title, .tarot-confirm-modal .n-base-close { color: #fff8e8; }
.tarot-confirm-button { padding: 9px 16px; border: 1px solid transparent; border-radius: 99px; background: linear-gradient(120deg, #f6dc9c, #d6a95c); color: #1b1428; font-weight: 700; cursor: pointer; }
.tarot-confirm-button.quiet { border-color: #c7a45d88; background: transparent; color: #f1dfb6; }
.tarot-confirm-button:focus-visible { outline: 2px solid #f1d18b; outline-offset: 2px; }
</style>
