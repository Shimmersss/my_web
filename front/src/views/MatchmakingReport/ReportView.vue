<template>
  <main class="matchmaking-page">
    <section v-if="error" class="notice error">
      {{ error }}
      <router-link class="back-link" to="/matchmaking-report">返回报告列表</router-link>
    </section>
    <section v-if="loading" class="notice">正在加载报告…</section>
    <RelationshipReportView
      v-if="report && isRelationshipReport"
      :report="report"
      @delete="deleteReport"
    />
    <template v-else-if="report">
      <div class="report-layout">
        <aside class="report-cover">
          <div class="cover-inner">
            <router-link class="cover-back" to="/matchmaking-report"
              >返回报告列表</router-link
            >
            <p>MOONLIT SALON · MIMO REPORT</p>
            <h1>月下会客厅</h1>
            <strong class="cover-title">你的定位体检报告</strong>
            <span class="cover-meta"
              >{{ report.context.selection }} · 生成于<br />
              {{ formatDate(report.createdAt) }}</span
            >
            <nav v-if="report.scores" class="section-nav" aria-label="报告板块">
              <button
                v-for="section in sections"
                :key="section.key"
                :class="{ active: activeSection === section.key }"
                :aria-current="activeSection === section.key ? 'page' : undefined"
                @click="selectSection(section.key)"
              >
                <span>{{ section.label }}</span
                ><small v-if="section.badge">{{ section.badge }}</small>
              </button>
            </nav>
            <p class="cover-privacy">
              {{ report.viewerCanDelete ? "本人报告" : `root 全局查看 · ${report.ownerLabel || report.ownerUsername || "未知用户"}` }} · 保存 30 天
            </p>
          </div>
        </aside>
        <section class="report">
        <header>
          <div>
            <p>02 · 定位体检</p>
            <h2>你的市场定位</h2>
          </div>
          <button v-if="report.viewerCanDelete" class="delete" @click="deleteReport">删除本报告</button>
        </header>
        <section v-if="report.scores && activeSection === 'overview'" class="overview-section">
        <div class="score-overview">
          <div class="score-intro">
            <p>OVERALL POSITIONING</p>
            <span>定位体检总分</span>
            <div class="score-number">
              <strong>{{ report.scores.total }}</strong><small>/ 100</small>
            </div>
            <span class="level-badge">{{ report.scores.level }}</span>
            <small
              >五维公开规则确定性计算<template
                v-if="report.scores.personalityType"
              > · {{ report.scores.personalityType }}</template
            ></small>
          </div>
          <div class="score-chart-wrap">
            <svg class="radar" viewBox="0 0 300 280" role="img" aria-label="五维评分雷达图">
              <polygon
                v-for="level in [1, 0.75, 0.5, 0.25]"
                :key="level"
                :points="radarGridPoints(level)"
                class="radar-grid"
              />
              <line
                v-for="(point, index) in radarAxisPoints"
                :key="'axis-' + index"
                x1="150"
                y1="136"
                :x2="point.x"
                :y2="point.y"
                class="radar-axis"
              />
              <polygon :points="radarDataPoints" class="radar-area" />
              <circle
                v-for="(point, index) in radarDataPointList"
                :key="'point-' + index"
                :cx="point.x"
                :cy="point.y"
                r="5"
                class="radar-point"
              />
              <g v-for="(seg, index) in segments" :key="seg.name">
                <text
                  :x="radarLabelPoints[index]?.x"
                  :y="radarLabelPoints[index]?.y"
                  class="radar-label"
                  text-anchor="middle"
                >{{ seg.name }}</text>
                <text
                  :x="radarLabelPoints[index]?.x"
                  :y="(radarLabelPoints[index]?.y || 0) + 16"
                  class="radar-score"
                  text-anchor="middle"
                >{{ seg.score }} / {{ seg.max }}</text>
              </g>
            </svg>
          </div>
          <div class="legend">
            <button
              v-for="seg in segments"
              :key="seg.name"
              :class="{ active: selectedDim === seg.name }"
              @click="selectedDim = selectedDim === seg.name ? '' : seg.name"
            >
              <span class="dot" :style="{ background: seg.color }"></span>
              <span class="legend-name">{{ seg.name }}</span>
              <span class="legend-score">{{ seg.score }} / {{ seg.max }}</span>
            </button>
          </div>
          <div v-if="selectedDetail" class="dim-detail">
            <h3>
              {{ selectedDetail.name }}<span class="dim-badge">{{ selectedDetail.score }} / {{ selectedDetail.max }}</span>
            </h3>
            <p class="basis">依据：{{ selectedDetail.basis }}</p>
            <p>{{ selectedDetail.analysis }}</p>
            <p class="actions"><strong>怎么做：</strong>{{ selectedDetail.actions }}</p>
          </div>
          <p v-else class="dim-hint">选择任一维度，查看依据、分析与行动建议。</p>
        </div>

        <div class="metrics">
          <article>
            <span>估算月现金流</span
            ><strong>{{ money(report.ledger.monthlyFreeCashflow) }}</strong
            ><small>{{ report.ledger.incomeBasis }}</small>
          </article>
          <article>
            <span>住房+债务压力</span
            ><strong>{{ report.ledger.housingDebtRatio }}%</strong
            ><small>占估算月收入</small>
          </article>
          <article>
            <span>储蓄覆盖</span
            ><strong>{{ report.ledger.savingsCoverageMonths }} 月</strong
            ><small>按固定支出估算</small>
          </article>
        </div>
        <div class="signal">
          <strong>账本提示</strong>
          <p>{{ report.ledger.budgetSignal }}</p>
        </div>
        </section>

        <div v-else-if="report.scores" class="panel">
          <template v-if="activeSection === 'intro'">
            <h3>自我介绍</h3>
            <div class="quote-card">
              <span class="quote-mark" aria-hidden="true">「</span>
              <p>{{ report.narrative.selfIntro }}</p>
            </div>
            <button class="copy" @click="copyIntro">
              {{ copied ? "已复制 ✓" : "复制话术" }}
            </button>
          </template>
          <template v-else-if="activeSection === 'market'">
            <h3>本地市场怎么读你的条件</h3>
            <dl class="market-grid">
              <div v-for="entry in marketEntries" :key="entry.label">
                <dt>{{ entry.label }}</dt>
                <dd>{{ entry.value }}</dd>
              </div>
            </dl>
          </template>
          <template v-else-if="activeSection === 'faq'">
            <h3>介绍人与对方父母高频问题</h3>
            <ul class="faq">
              <li v-for="(item, index) in report.narrative.faqPrep" :key="index">
                <p class="q"><span class="who">问</span>{{ item.question }}</p>
                <p class="a"><span class="who">答</span>{{ item.answer }}</p>
              </li>
            </ul>
          </template>
          <template v-else-if="activeSection === 'partner'">
            <h3>推荐的伴侣画像</h3>
            <div v-if="report.partnerImage" class="portrait-frame">
              <img
                class="portrait-img"
                :src="'data:image/png;base64,' + report.partnerImage"
                alt="AI 生成的虚构伴侣人像照片（仅作示意）"
              />
            </div>
            <p>{{ report.narrative.partnerPortrait?.portrait }}</p>
            <p class="actions">
              <strong>匹配与磨合：</strong
              >{{ report.narrative.partnerPortrait?.whyMatch }}
            </p>
          </template>
          <template v-else-if="activeSection === 'channel'">
            <h3>渠道打法</h3>
            <div class="channel-block">
              <dt>主攻渠道</dt>
              <dd>{{ channel.mainChannels || "—" }}</dd>
            </div>
            <div class="channel-block">
              <dt>避坑提醒</dt>
              <dd>{{ channel.avoidPitfalls || "—" }}</dd>
            </div>
          </template>
          <template v-else>
            <h3>边界与数据局限</h3>
            <p>{{ report.narrative.limitations }}</p>
          </template>
        </div>
        <div v-else class="panel legacy-panel">
          <h3>旧版报告</h3>
          <p class="legacy-hint">
            这份报告生成于旧版结构，以下按原始章节展示；如需五维评分与星盘分析，请重新生成一份报告。
          </p>
          <section
            v-for="section in legacySections"
            :key="section.key"
            class="legacy-section"
          >
            <h4>{{ section.label }}</h4>
            <p v-if="section.kind === 'text'" class="legacy-text">{{ section.value }}</p>
            <ol v-else-if="section.kind === 'list'" class="legacy-list">
              <li v-for="(item, index) in section.value" :key="index">
                <template v-if="item.qa">
                  <strong>{{ item.qa[0] }}</strong><span>{{ item.qa[1] }}</span>
                </template>
                <template v-else>{{ item.text }}</template>
              </li>
            </ol>
            <dl v-else class="legacy-map">
              <div v-for="(value, key) in section.value" :key="key">
                <dt>{{ key }}</dt><dd>{{ value }}</dd>
              </div>
            </dl>
          </section>
        </div>
        <p class="disclaimer">
          评分与市场规则描述 ≠ 个人价值评判：分数由服务器按公开规则确定性计算（年龄、身高的市场通行曲线按性别区分），只描述相亲市场供需参考；本报告不提供成功率或撮合建议。伴侣人像为
          AI 虚构，不对应任何真实人物。
        </p>
        </section>
      </div>
    </template>
  </main>
</template>
<script setup>
import { computed, nextTick, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  deleteMatchmakingReport,
  getMatchmakingReport,
} from "@/api";
import { useAuthStore } from "@/stores/auth";
import { trialDeleteWarning } from "@/utils/matchmakingTrial";
import RelationshipReportView from "./RelationshipReportView.vue";
const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const loading = ref(true),
  error = ref(""),
  report = ref(null),
  copied = ref(false),
  selectedDim = ref(""),
  activeSection = ref("overview");
const isRelationshipReport = computed(
  () => report.value?.reportVersion === "relationship-exploration-v1",
);
const DIM_COLORS = ["#e58ca4", "#d9aa67", "#9fc3b0", "#88a9ca", "#b29acb"];
const segments = computed(() => {
  const dimensions = report.value?.scores?.dimensions || [];
  return dimensions.map((dimension, index) => ({
    ...dimension,
    color: DIM_COLORS[index % DIM_COLORS.length],
  }));
});
const radarPoint = (index, radius) => {
  const count = Math.max(segments.value.length, 5);
  const angle = -Math.PI / 2 + (Math.PI * 2 * index) / count;
  return { x: 150 + Math.cos(angle) * radius, y: 136 + Math.sin(angle) * radius };
};
const radarGridPoints = (level) =>
  Array.from({ length: Math.max(segments.value.length, 5) }, (_, index) => radarPoint(index, 86 * level))
    .map((point) => `${point.x},${point.y}`)
    .join(" ");
const radarAxisPoints = computed(() =>
  Array.from({ length: Math.max(segments.value.length, 5) }, (_, index) => radarPoint(index, 86))
);
const radarDataPointList = computed(() =>
  segments.value.map((seg, index) => radarPoint(index, 86 * Math.max(0, Math.min(1, Number(seg.score || 0) / Number(seg.max || 1)))))
);
const radarDataPoints = computed(() =>
  radarDataPointList.value.map((point) => `${point.x},${point.y}`).join(" ")
);
const radarLabelPoints = computed(() =>
  segments.value.map((_, index) => radarPoint(index, 115))
);
const dimensionPairs = computed(() => {
  const dimensions = report.value?.scores?.dimensions || [];
  const analysis = report.value?.narrative?.dimensionAnalysis || [];
  return dimensions.map((dimension, index) => ({
    ...dimension,
    analysis: analysis[index]?.analysis || "",
    actions: analysis[index]?.actions || "",
  }));
});
const selectedDetail = computed(() => {
  if (!selectedDim.value) return null;
  return dimensionPairs.value.find((pair) => pair.name === selectedDim.value) || null;
});
const sections = computed(() => [
  { key: "overview", label: "定位总览" },
  { key: "intro", label: "自我介绍" },
  { key: "market", label: "市场解读" },
  { key: "faq", label: "高频问答", badge: `${report.value?.narrative?.faqPrep?.length || 0} 问` },
  { key: "partner", label: "伴侣画像" },
  { key: "channel", label: "渠道打法" },
  { key: "limits", label: "边界局限" },
]);
const marketEntries = computed(() => {
  const market = report.value?.narrative?.marketReading || {};
  return [
    { label: "市场硬通货", value: market.hardAssets },
    { label: "你的亮点", value: market.highlights },
    { label: "待补短板", value: market.gaps },
    { label: "市场敏感点", value: market.sensitivities },
  ].filter((entry) => entry.value);
});
const channel = computed(() => {
  const value = report.value?.narrative?.channelStrategy;
  if (!value) return { mainChannels: "", avoidPitfalls: "" };
  if (typeof value === "string") {
    const match = value.match(/^\{mainChannels=(.*), avoidPitfalls=(.*)\}$/s);
    if (match) return { mainChannels: match[1], avoidPitfalls: match[2] };
    return { mainChannels: value, avoidPitfalls: "" };
  }
  return { mainChannels: value.mainChannels || "", avoidPitfalls: value.avoidPitfalls || "" };
});
const LEGACY_LABELS = {
  summary: "Agent 概览",
  educationCareerAdvice: "学历与职业表达",
  nextActions: "下一步行动",
  selfIntro: "自我介绍",
  marketReading: "本地市场读条件",
  faqPrep: "高频问答",
  partnerPortrait: "群体画像",
  fixPlan: "短板修复计划",
  channelStrategy: "渠道打法",
  limitations: "数据局限",
};
const flat = (value) => {
  if (value == null) return "";
  if (typeof value === "string") return value;
  if (typeof value !== "object") return String(value);
  if (Array.isArray(value)) return value.map(flat).filter(Boolean).join("；");
  return Object.entries(value).map(([key, item]) => `${key}：${flat(item)}`).join("\n");
};
const legacySections = computed(() => {
  const narrative = report.value?.narrative;
  if (!narrative || typeof narrative !== "object") return [];
  return Object.entries(narrative)
    .map(([key, value]) => ({ key, value, label: LEGACY_LABELS[key] || key }))
    .filter(({ value }) => Array.isArray(value)
      ? value.length > 0
      : value && typeof value === "object"
        ? Object.keys(value).length > 0
        : value != null && value !== "")
    .map(({ key, value, label }) => {
      if (Array.isArray(value)) {
        const items = value.map((item) => item && typeof item === "object" && item.question != null
          ? { qa: [flat(item.question), flat(item.answer ?? "")] }
          : { text: flat(item) })
          .filter((item) => item.qa ? item.qa[0] || item.qa[1] : item.text);
        return { key, label, kind: "list", value: items };
      }
      if (value && typeof value === "object") {
        return { key, label, kind: "map", value: Object.fromEntries(Object.entries(value).map(([mapKey, item]) => [mapKey, flat(item)])) };
      }
      return { key, label, kind: "text", value: flat(value) };
    });
});
async function selectSection(key) {
  activeSection.value = key;
  await nextTick();
  document.querySelector(key === "overview" ? ".overview-section" : ".panel")
    ?.scrollIntoView({ behavior: "smooth", block: "start" });
}
onMounted(async () => {
  try {
    const res = await getMatchmakingReport(route.params.reportId);
    report.value = res.data;
  } catch (e) {
    error.value = e.message || "报告加载失败";
  } finally {
    loading.value = false;
  }
});
async function copyIntro() {
  try {
    await navigator.clipboard.writeText(report.value?.narrative?.selfIntro || "");
    copied.value = true;
    setTimeout(() => (copied.value = false), 2000);
  } catch {
    error.value = "复制失败，请手动选择文本复制";
  }
}
async function deleteReport() {
  if (!window.confirm(trialDeleteWarning(auth.isMatchmakingTrial))) return;
  try {
    await deleteMatchmakingReport(route.params.reportId);
    router.push("/matchmaking-report");
  } catch (e) {
    error.value = e.message || "删除失败";
  }
}
const money = (value) =>
  new Intl.NumberFormat("zh-CN", {
    style: "currency",
    currency: "CNY",
    maximumFractionDigits: 0,
  }).format(Number(value || 0));
const formatDate = (value) =>
  value
    ? new Intl.DateTimeFormat("zh-CN", {
        dateStyle: "long",
        timeStyle: "short",
      }).format(new Date(value))
    : "—";
</script>
<style scoped lang="scss">
.matchmaking-page {
  --rose: #c25b72;
  --rose-deep: #8e3a52;
  --rose-soft: #fdf1f3;
  --cream: #fffbf7;
  --gold: #c6925a;
  --ink: #43313b;
  --ink-soft: #6d5a62;
  max-width: 1120px;
  margin: auto;
  padding: 42px 20px 80px;
  color: var(--ink);
}
.notice {
  margin-top: 18px;
  padding: 24px;
  border: 1px solid #f0d9dd;
  border-radius: 16px;
  background: #fff;
}
.notice.error {
  color: #a43932;
  border-color: #edcac7;
}
.back-link {
  display: inline-block;
  margin-left: 12px;
  color: var(--rose);
}
.report-cover {
  position: relative;
  overflow: hidden;
  margin-top: 18px;
  padding: 46px 44px 40px;
  color: #fff;
  background: linear-gradient(118deg, #6d3a4a 0%, #a04a63 55%, #c25b72 100%);
  border-radius: 24px;
}
.report-cover::after {
  content: "";
  position: absolute;
  inset: 0;
  background:
    radial-gradient(circle at 85% 20%, #ffffff2e 0 90px, transparent 91px),
    radial-gradient(circle at 8% 90%, #ffffff1c 0 120px, transparent 121px);
}
.report-cover p {
  position: relative;
  font-size: 11px;
  letter-spacing: 0.14em;
  font-weight: 700;
  color: #f3d7dd;
}
.report-cover h1 {
  position: relative;
  margin: 10px 0 8px;
  font-size: 40px;
  line-height: 1.15;
  letter-spacing: 0.02em;
}
.cover-meta {
  position: relative;
  font-size: 13px;
  color: #f3d7dd;
}
.cover-hearts span {
  position: absolute;
  color: #ffffff2b;
}
.cover-hearts .h1 { left: 6%; top: 18%; font-size: 26px; }
.cover-hearts .h2 { left: 22%; top: 68%; font-size: 15px; }
.cover-hearts .h3 { left: 48%; top: 12%; font-size: 14px; }
.cover-hearts .h4 { right: 24%; top: 30%; font-size: 20px; }
.cover-hearts .h5 { right: 9%; bottom: 16%; font-size: 28px; }
.cover-hearts .h6 { left: 68%; bottom: 10%; font-size: 13px; }
.cover-hearts .h7 { right: 42%; top: 70%; font-size: 16px; }
.report {
  margin-top: 16px;
  padding: 26px;
  border: 1px solid #f0dde1;
  border-radius: 20px;
  background: var(--cream);
  box-shadow: 0 10px 30px #a04a6312;
}
.report header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: start;
}
.report header p {
  font-size: 11px;
  letter-spacing: 0.12em;
  font-weight: 700;
  color: var(--rose);
}
.report h2 {
  margin: 3px 0 0;
  font-size: 25px;
}
.delete {
  border: 0;
  background: transparent;
  color: #a43932;
  cursor: pointer;
}
.metrics {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
  margin: 16px 0;
}
.metrics article,
.score-overview,
.panel {
  padding: 18px;
  background: #fff;
  border: 1px solid #f3e3e6;
  border-radius: 14px;
}
.metrics span,
.metrics small {
  display: block;
  color: var(--ink-soft);
  font-size: 12px;
}
.metrics strong {
  display: block;
  font-size: 25px;
  margin: 9px 0;
  color: var(--rose-deep);
}
.signal {
  padding: 16px 18px;
  border-radius: 14px;
  background: var(--rose-soft);
  border: 1px dashed #ecccd3;
}
.signal strong {
  color: var(--rose-deep);
}
.signal p {
  margin: 8px 0 0;
  line-height: 1.7;
}
.score-overview {
  margin-top: 12px;
}
.score-top {
  display: flex;
  align-items: baseline;
  gap: 12px;
  flex-wrap: wrap;
}
.score-total {
  font-size: 46px;
  font-weight: 800;
  color: var(--rose);
  line-height: 1;
}
.level-badge {
  padding: 5px 14px;
  border-radius: 99px;
  background: linear-gradient(120deg, var(--rose-deep), var(--rose));
  color: #fff;
  font-size: 13px;
  letter-spacing: 0.06em;
}
.score-top small {
  color: var(--ink-soft);
}
.score-visual {
  display: flex;
  gap: 20px;
  align-items: center;
  margin-top: 14px;
  flex-wrap: wrap;
}
.pie {
  width: 260px;
  flex-shrink: 0;
  filter: drop-shadow(0 6px 14px #a04a6322);
}
.seg {
  cursor: pointer;
  transition: opacity 0.15s ease;
}
.seg:hover {
  opacity: 0.82;
}
.seg.selected {
  stroke: #43313b;
  stroke-width: 2;
}
.seg-label {
  fill: #fff;
  font-size: 11px;
  font-weight: 700;
  text-anchor: middle;
  pointer-events: none;
}
.legend {
  display: grid;
  gap: 8px;
  flex: 1;
  min-width: 210px;
}
.legend button {
  display: flex;
  gap: 9px;
  align-items: center;
  padding: 9px 12px;
  border: 1px solid #f0dde1;
  border-radius: 10px;
  background: #fff;
  cursor: pointer;
  font: inherit;
  text-align: left;
  transition: border-color 0.15s ease, background 0.15s ease;
}
.legend button.active {
  border-color: var(--rose);
  background: var(--rose-soft);
}
.legend .dot {
  width: 11px;
  height: 11px;
  border-radius: 3px;
  flex-shrink: 0;
}
.legend-name {
  flex: 1;
  font-weight: 650;
  font-size: 13px;
}
.legend-score {
  font-size: 13px;
  color: var(--ink-soft);
}
.dim-detail {
  margin-top: 14px;
  padding: 15px;
  border: 1px solid #f0dde1;
  border-radius: 12px;
  background: var(--rose-soft);
}
.dim-detail h3 {
  margin: 0;
  font-size: 15px;
  display: flex;
  align-items: center;
  gap: 10px;
}
.dim-detail p {
  margin: 9px 0 0;
  line-height: 1.75;
  color: #5a4650;
  font-size: 14px;
}
.dim-detail .basis {
  font-size: 12px;
  color: var(--ink-soft);
}
.dim-badge {
  padding: 2px 10px;
  border-radius: 99px;
  background: var(--rose);
  color: #fff;
  font-size: 12px;
  font-weight: 400;
}
.dim-hint {
  margin: 14px 0 0;
  font-size: 13px;
  color: var(--ink-soft);
}
.section-nav {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 14px;
}
.section-nav button {
  padding: 8px 15px;
  border: 1px solid #ecd3d8;
  border-radius: 99px;
  background: #fff;
  cursor: pointer;
  font: inherit;
  font-size: 13px;
  font-weight: 650;
  color: var(--ink-soft);
  display: flex;
  gap: 6px;
  align-items: center;
  transition: all 0.15s ease;
}
.section-nav button.active {
  background: linear-gradient(120deg, var(--rose-deep), var(--rose));
  border-color: var(--rose);
  color: #fff;
}
.section-nav small {
  font-weight: 400;
  opacity: 0.85;
}
.panel {
  margin-top: 10px;
}
.panel h3 {
  margin: 0 0 12px;
  font-size: 16px;
  color: var(--rose-deep);
}
.panel h3::before {
  content: "♥ ";
  color: var(--rose);
  font-size: 13px;
}
.panel p {
  line-height: 1.8;
  color: #55424b;
  font-size: 14px;
  margin: 0 0 8px;
}
.panel .actions {
  margin: 8px 0 0;
}
.quote-card {
  position: relative;
  padding: 16px 18px 14px 44px;
  border-radius: 12px;
  background: linear-gradient(120deg, #fff5f6, #fdf1f3);
  border: 1px solid #f0dde1;
}
.quote-mark {
  position: absolute;
  left: 12px;
  top: 6px;
  font-size: 30px;
  font-weight: 800;
  color: var(--rose);
  opacity: 0.6;
  line-height: 1;
}
.quote-card p {
  margin: 0;
}
.copy {
  margin-top: 10px;
  padding: 7px 14px;
  border: 1px solid #ecd3d8;
  border-radius: 99px;
  background: #fff;
  font-size: 12px;
  cursor: pointer;
  color: var(--rose-deep);
}
.copy:hover {
  border-color: var(--rose);
}
.market-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
  margin: 0;
}
.market-grid > div {
  padding: 13px 15px;
  border-radius: 12px;
  background: #fff;
  border: 1px solid #f3e3e6;
}
.market-grid dt {
  font-size: 13px;
  font-weight: 700;
  color: var(--rose);
  margin-bottom: 5px;
}
.market-grid dt::before {
  content: "● ";
  font-size: 9px;
  vertical-align: 2px;
}
.market-grid dd {
  margin: 0;
  line-height: 1.7;
  color: #55424b;
  font-size: 14px;
}
.faq {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 12px;
}
.faq li {
  display: grid;
  gap: 7px;
}
.faq .q,
.faq .a {
  position: relative;
  margin: 0;
  padding: 10px 14px;
  border-radius: 14px;
  line-height: 1.7;
  font-size: 14px;
  max-width: 92%;
}
.faq .q {
  background: var(--rose-soft);
  border: 1px solid #f0ccd4;
  color: var(--ink);
  border-bottom-left-radius: 4px;
}
.faq .a {
  background: #fff;
  border: 1px solid #f3e3e6;
  color: #55424b;
  justify-self: end;
  border-bottom-right-radius: 4px;
}
.faq .who {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: 99px;
  background: var(--rose);
  color: #fff;
  font-size: 11px;
  font-weight: 700;
  margin-right: 8px;
  flex-shrink: 0;
}
.faq .a .who {
  background: var(--gold);
}
.portrait-frame {
  position: relative;
  display: inline-block;
  padding: 10px;
  border-radius: 18px;
  background: linear-gradient(135deg, #fff, var(--rose-soft));
  border: 1px solid #f0ccd4;
  margin-bottom: 14px;
}
.portrait-img {
  display: block;
  max-width: 340px;
  width: 100%;
  border-radius: 12px;
}
.portrait-heart {
  position: absolute;
  right: -10px;
  top: -10px;
  width: 34px;
  height: 34px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 99px;
  background: linear-gradient(135deg, var(--rose-deep), var(--rose));
  color: #fff;
  font-size: 15px;
  box-shadow: 0 4px 10px #a04a6333;
}
.channel-block {
  padding: 13px 15px;
  border: 1px solid #f3e3e6;
  border-radius: 12px;
  background: #fff;
}
.channel-block + .channel-block {
  margin-top: 10px;
}
.channel-block dt {
  font-size: 13px;
  font-weight: 700;
  color: var(--rose);
  margin-bottom: 4px;
}
.channel-block dd {
  margin: 0;
  line-height: 1.7;
  color: #55424b;
  font-size: 14px;
}
.disclaimer {
  margin: 14px 0 0;
  padding: 14px 16px;
  border-left: 3px solid var(--gold);
  background: #fbf5ec;
  border-radius: 0 12px 12px 0;
  font-size: 13px;
  line-height: 1.7;
  color: #6d5a3f;
}
@media (max-width: 760px) {
  .matchmaking-page {
    padding: 18px 12px 55px;
  }
  .report-cover {
    padding: 32px 24px 30px;
    border-radius: 18px;
  }
  .report-cover h1 {
    font-size: 30px;
  }
  .report {
    padding: 18px;
  }
  .report header {
    display: block;
  }
  .delete {
    margin-top: 12px;
    padding: 0;
  }
  .metrics,
  .market-grid {
    grid-template-columns: 1fr;
  }
  .score-total {
    font-size: 38px;
  }
  .score-visual {
    gap: 12px;
  }
  .pie {
    width: 230px;
    margin: auto;
  }
  .legend {
    width: 100%;
  }
  .section-nav {
    flex-wrap: nowrap;
    overflow-x: auto;
    padding-bottom: 6px;
    -webkit-overflow-scrolling: touch;
  }
  .section-nav button {
    flex-shrink: 0;
  }
  .faq .q,
  .faq .a {
    max-width: 100%;
  }
  .portrait-img {
    max-width: 100%;
  }
}

/* Moonlit Salon: this visual system is intentionally scoped to matchmaking. */
.matchmaking-page {
  --night: #17121d;
  --night-soft: #2a1f2d;
  --plum: #6f3045;
  --plum-bright: #91425b;
  --parchment: #fbf7f0;
  --paper: #fffdf9;
  --brass: #c5a36f;
  --line: #dfd1c2;
  --ink: #352b31;
  --ink-soft: #756a6f;
  max-width: 1280px;
  padding-top: 34px;
  font-family: "PingFang SC", "Microsoft YaHei", sans-serif;
}
.report-layout {
  display: grid;
  grid-template-columns: 330px minmax(0, 1fr);
  align-items: stretch;
  overflow: hidden;
  border: 1px solid #2f2631;
  border-radius: 26px;
  background: var(--paper);
  box-shadow: 0 28px 70px rgba(28, 18, 29, 0.18);
}
.report-cover {
  min-height: 980px;
  margin: 0;
  padding: 0;
  border-radius: 0;
  background-color: var(--night);
  background-image: url("@/assets/images/matchmaking-moonlit-salon.jpg");
  background-position: top center;
  background-repeat: no-repeat;
  background-size: 100% auto;
  background-blend-mode: luminosity;
}
.report-cover::after {
  content: none;
  display: none;
}
.cover-inner {
  position: sticky;
  top: 86px;
  z-index: 1;
  display: flex;
  min-height: calc(100vh - 120px);
  padding: 44px 34px 32px;
  flex-direction: column;
  color: #f9f1e6;
}
.cover-back {
  width: fit-content;
  margin-bottom: 42px;
  color: #d9c6b0;
  font-size: 12px;
  text-decoration: none;
}
.cover-back:hover {
  color: #fff;
}
.report-cover p {
  color: #d6bb99;
}
.report-cover h1 {
  margin: 9px 0 14px;
  font-family: "Songti SC", "STSong", "Noto Serif SC", serif;
  font-size: 38px;
  font-weight: 600;
  letter-spacing: 0.08em;
}
.cover-title {
  font-family: "Songti SC", "STSong", "Noto Serif SC", serif;
  font-size: 20px;
  font-weight: 500;
  color: #fffaf3;
}
.cover-meta {
  display: block;
  margin-top: 9px;
  color: #cdbfb4;
  line-height: 1.75;
}
.section-nav {
  display: grid;
  gap: 5px;
  margin: 0;
}
.section-nav button {
  display: flex;
  width: 100%;
  justify-content: space-between;
  padding: 12px 14px;
  border: 1px solid transparent;
  border-radius: 9px;
  background: rgba(255, 255, 255, 0.03);
  color: #d8cfd4;
  font-size: 14px;
  font-weight: 500;
  text-align: left;
}
.section-nav button:hover {
  border-color: rgba(197, 163, 111, 0.3);
  color: #fff;
}
.section-nav button.active {
  border-color: rgba(197, 163, 111, 0.46);
  background: rgba(145, 66, 91, 0.56);
  color: #fff8ef;
}
.section-nav small {
  color: #d7bd9d;
}
.cover-privacy {
  margin-top: auto;
  padding-top: 28px;
  font-size: 12px;
  line-height: 1.7;
}
.report {
  display: flex;
  min-width: 0;
  margin: 0;
  padding: 48px 52px 42px;
  flex-direction: column;
  border: 0;
  border-radius: 0;
  background: var(--parchment);
  box-shadow: none;
}
.report > header {
  order: 1;
}
.report > .overview-section {
  order: 3;
}
.report > .panel {
  order: 3;
}
.report > .metrics {
  order: 4;
}
.report > .signal {
  order: 5;
}
.report > .score-overview {
  order: 6;
}
.report > .disclaimer {
  order: 7;
}
.report header {
  padding-bottom: 18px;
  border-bottom: 1px solid var(--line);
}
.report header p {
  color: var(--plum-bright);
}
.report h2,
.panel h3 {
  font-family: "Songti SC", "STSong", "Noto Serif SC", serif;
}
.report h2 {
  font-size: 31px;
  font-weight: 600;
  letter-spacing: 0.03em;
}
.delete {
  color: #875366;
}
.metrics {
  gap: 0;
  margin: 24px 0 0;
  padding: 17px 0;
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
}
.metrics article {
  padding: 2px 22px;
  border: 0;
  border-right: 1px solid var(--line);
  border-radius: 0;
  background: transparent;
}
.metrics article:first-child {
  padding-left: 0;
}
.metrics article:last-child {
  border-right: 0;
}
.metrics strong {
  font-family: Georgia, "Times New Roman", serif;
  font-size: 28px;
  font-weight: 500;
  color: var(--plum);
}
.signal {
  margin-top: 18px;
  padding: 15px 18px;
  border: 0;
  border-left: 3px solid var(--brass);
  border-radius: 2px;
  background: #f2ebe1;
}
.score-overview {
  margin-top: 22px;
  padding: 24px;
  border-color: var(--line);
  border-radius: 16px;
  background: rgba(255, 253, 249, 0.78);
}
.score-total {
  font-family: Georgia, "Times New Roman", serif;
  color: var(--plum);
}
.level-badge {
  border: 1px solid #d9bdc5;
  background: #f5e7eb;
  color: var(--plum);
}
.pie {
  filter: none;
}
.pie > path:not(.seg) {
  display: none;
}
.legend button {
  border-color: #e3d8cb;
  border-radius: 7px;
  background: transparent;
}
.legend button.active {
  border-color: var(--plum-bright);
  background: #f5e7eb;
}
.dim-detail {
  border-color: #dfc9cf;
  background: #f7edef;
}
.panel {
  margin-top: 22px;
  padding: 30px 32px;
  border-color: var(--line);
  border-radius: 16px;
  background: var(--paper);
}
.panel h3 {
  margin-bottom: 18px;
  font-size: 23px;
  font-weight: 600;
  color: var(--plum);
}
.panel h3::before {
  content: none;
}
.panel p {
  color: #51484c;
  line-height: 1.95;
}
.quote-card {
  padding: 26px 28px 24px 58px;
  border-color: #ddcfc0;
  border-radius: 4px;
  background: #fcf8f2;
}
.quote-mark {
  left: 20px;
  top: 14px;
  color: var(--brass);
}
.copy {
  margin-top: 16px;
  padding: 10px 18px;
  border-color: var(--plum);
  border-radius: 8px;
  background: var(--plum);
  color: #fff;
}
.copy:hover {
  border-color: var(--plum-bright);
  background: var(--plum-bright);
}
.market-grid {
  gap: 0;
  border-top: 1px solid var(--line);
}
.market-grid > div {
  padding: 18px 14px;
  border: 0;
  border-bottom: 1px solid var(--line);
  border-radius: 0;
  background: transparent;
}
.market-grid > div:nth-child(odd) {
  border-right: 1px solid var(--line);
}
.market-grid dt {
  color: var(--plum-bright);
}
.market-grid dt::before {
  content: none;
}
.faq {
  gap: 18px;
}
.faq li {
  padding-bottom: 18px;
  border-bottom: 1px solid var(--line);
}
.faq .q,
.faq .a {
  max-width: 100%;
  padding: 0;
  border: 0;
  border-radius: 0;
  background: transparent;
}
.faq .q {
  color: var(--plum);
  font-weight: 650;
}
.faq .a {
  justify-self: stretch;
  padding-left: 30px;
}
.faq .who,
.faq .a .who {
  background: var(--plum);
}
.portrait-frame {
  display: block;
  width: min(340px, 100%);
  margin: 0 auto 22px;
  padding: 10px;
  border: 1px solid #b89c73;
  border-radius: 2px;
  background: #e4d4bd;
}
.portrait-img {
  border-radius: 0;
}
.channel-block {
  border-color: var(--line);
  border-radius: 4px;
  background: #fcf8f2;
}
.disclaimer {
  border-left-color: var(--brass);
  background: #f2ebe1;
}
@media (max-width: 980px) {
  .report-layout {
    grid-template-columns: 280px minmax(0, 1fr);
  }
  .cover-inner {
    padding: 34px 24px 28px;
  }
  .report {
    padding: 38px 30px;
  }
}
@media (max-width: 760px) {
  .matchmaking-page {
    padding-top: 18px;
  }
  .report-layout {
    display: block;
    overflow: visible;
    border-radius: 18px;
  }
  .report-cover {
    min-height: 0;
    border-radius: 17px 17px 0 0;
    background-position: 58% 44%;
    background-size: cover;
  }
  .cover-inner {
    position: static;
    min-height: 0;
    padding: 28px 22px 22px;
  }
  .cover-back {
    margin-bottom: 28px;
  }
  .report-cover h1 {
    font-size: 32px;
  }
  .section-nav {
    display: flex;
    flex-wrap: nowrap;
    overflow-x: auto;
    padding-bottom: 5px;
  }
  .section-nav button {
    width: auto;
    flex: 0 0 auto;
  }
  .cover-privacy {
    display: none;
  }
  .report {
    padding: 26px 20px 28px;
  }
  .metrics {
    display: grid;
    gap: 16px;
  }
  .metrics article,
  .metrics article:first-child {
    padding: 0 0 15px;
    border-right: 0;
    border-bottom: 1px solid var(--line);
  }
  .metrics article:last-child {
    padding-bottom: 0;
    border-bottom: 0;
  }
  .panel {
    padding: 24px 20px;
  }
  .market-grid > div:nth-child(odd) {
    border-right: 0;
  }
}

/* Celestial Portrait: one continuous report with a standalone score stage. */
.matchmaking-page {
  --celestial-night: #0d1520;
  --celestial-raised: #172434;
  --celestial-line: #35475a;
  --celestial-ivory: #f7f0e6;
  --celestial-gold: #d8b16e;
  --celestial-rose: #d47892;
  max-width: 1240px;
  color: var(--celestial-ivory);
  font-family: "PingFang SC", "Microsoft YaHei", sans-serif;
}
.report-layout {
  grid-template-columns: 292px minmax(0, 1fr);
  border-color: #2e4052;
  border-radius: 12px;
  background: var(--celestial-night);
  box-shadow: 0 30px 80px rgba(8, 14, 22, 0.28);
}
.report-cover {
  min-height: 1180px;
  background-color: #111a25;
  background-image: url("@/assets/images/matchmaking-archive-night.jpg");
  background-position: 61% top;
  background-size: auto 100%;
  background-blend-mode: normal;
}
.report-cover::before {
  content: "";
  position: absolute;
  inset: 0;
  background: rgba(7, 14, 22, 0.63);
  pointer-events: none;
}
.cover-inner {
  min-height: calc(100vh - 112px);
  padding: 42px 30px 30px;
}
.report-cover h1,
.cover-title,
.report h2,
.panel h3,
.score-intro .score-number strong {
  font-family: "Noto Serif SC", "Songti SC", serif;
  font-weight: 400;
}
.report-cover h1 {
  font-size: 42px;
  letter-spacing: 0.1em;
}
.cover-title {
  font-size: 21px;
  letter-spacing: 0.05em;
}
.section-nav {
  margin-top: 42px;
}
.section-nav button {
  border-bottom: 1px solid rgba(255, 255, 255, 0.13);
  border-radius: 0;
  background: rgba(12, 22, 33, 0.34);
}
.section-nav button.active {
  border-color: rgba(216, 177, 110, 0.56);
  background: rgba(157, 72, 95, 0.6);
}
.report {
  padding: 46px 48px 44px;
  background: var(--celestial-night);
  color: var(--celestial-ivory);
}
.report > header { order: 1; }
.report > .overview-section,
.report > .panel { order: 3; }
.report > .disclaimer { order: 4; }
.overview-section {
  display: block;
  scroll-margin-top: 96px;
}
.report header {
  border-color: var(--celestial-line);
}
.report header p {
  color: var(--celestial-gold);
}
.report h2 {
  color: #fff9ef;
  font-size: 38px;
  letter-spacing: 0.055em;
}
.report .delete {
  color: #dc9caf;
}
.score-overview {
  position: relative;
  display: grid;
  grid-template-columns: 0.8fr 1.2fr;
  gap: 10px 22px;
  min-height: 530px;
  margin-top: 28px;
  padding: 48px 42px 32px;
  overflow: hidden;
  border: 1px solid #52677c;
  border-radius: 10px;
  background-color: #101a27;
  background-image: url("@/assets/images/matchmaking-celestial-score.jpg");
  background-position: 58% center;
  background-size: cover;
  color: #fff9ef;
  box-shadow: 0 24px 62px rgba(2, 8, 16, 0.35);
}
.score-overview::after {
  content: "";
  position: absolute;
  inset: 0;
  background: rgba(6, 13, 22, 0.32);
  pointer-events: none;
}
.score-overview > * {
  position: relative;
  z-index: 1;
}
.score-intro {
  display: flex;
  align-self: center;
  flex-direction: column;
  align-items: flex-start;
  padding: 10px 0 20px;
}
.score-intro > p {
  margin: 0 0 28px;
  color: var(--celestial-gold);
  font-size: 11px;
  letter-spacing: 0.18em;
}
.score-intro > span:not(.level-badge) {
  color: #d8dfe6;
  font-size: 14px;
  letter-spacing: 0.08em;
}
.score-number {
  display: flex;
  align-items: flex-end;
  margin: 8px 0 12px;
}
.score-intro .score-number strong {
  color: #fff7e9;
  font-size: clamp(86px, 10vw, 132px);
  line-height: 0.84;
  text-shadow: 0 0 34px rgba(231, 197, 137, 0.3);
}
.score-number small {
  margin: 0 0 11px 8px;
  color: #bfc9d2;
  font-family: Georgia, serif;
  font-size: 15px;
}
.score-intro .level-badge {
  margin: 4px 0 18px;
  border: 1px solid rgba(235, 196, 134, 0.52);
  border-radius: 4px;
  background: rgba(157, 72, 95, 0.54);
  color: #fff5e7;
}
.score-intro > small {
  max-width: 270px;
  color: #b9c3cd;
  line-height: 1.7;
}
.score-chart-wrap {
  display: grid;
  place-items: center;
  min-width: 0;
}
.radar {
  width: min(100%, 390px);
  overflow: visible;
  filter: drop-shadow(0 0 16px rgba(140, 185, 211, 0.16));
}
.radar-grid {
  fill: rgba(17, 31, 46, 0.18);
  stroke: rgba(215, 226, 235, 0.3);
  stroke-width: 1;
}
.radar-axis {
  stroke: rgba(215, 226, 235, 0.24);
  stroke-width: 1;
}
.radar-area {
  fill: rgba(212, 120, 146, 0.3);
  stroke: #e7a0b4;
  stroke-width: 2.5;
}
.radar-point {
  fill: #f0cf93;
  stroke: #fff7e7;
  stroke-width: 1.5;
}
.radar-label {
  fill: #f9efe6;
  font-family: "Noto Serif SC", "Songti SC", serif;
  font-size: 14px;
  letter-spacing: 0.04em;
}
.radar-score {
  fill: #d3bd9b;
  font-family: Georgia, serif;
  font-size: 10px;
}
.score-overview .legend {
  grid-column: 1 / -1;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 7px;
  min-width: 0;
  margin-top: 2px;
}
.score-overview .legend button {
  min-width: 0;
  padding: 10px 9px;
  border-color: rgba(255, 255, 255, 0.18);
  border-radius: 5px;
  background: rgba(10, 21, 33, 0.62);
  color: #f4eee9;
}
.score-overview .legend button.active {
  border-color: #df9eb0;
  background: rgba(157, 72, 95, 0.58);
}
.score-overview .legend .dot {
  border-radius: 50%;
}
.score-overview .legend-score {
  display: none;
}
.score-overview .dim-detail,
.score-overview .dim-hint {
  grid-column: 1 / -1;
}
.score-overview .dim-detail {
  margin-top: 6px;
  border-color: rgba(255, 255, 255, 0.2);
  border-radius: 5px;
  background: rgba(12, 23, 36, 0.76);
}
.score-overview .dim-detail h3,
.score-overview .dim-detail p,
.score-overview .dim-detail .basis,
.score-overview .dim-hint {
  color: #e8e3df;
}
.metrics {
  margin-top: 28px;
  border-color: var(--celestial-line);
}
.metrics article {
  border-color: var(--celestial-line);
}
.metrics span,
.metrics small {
  color: #9fadb9;
}
.metrics strong {
  color: #f0d39f;
  font-family: "Noto Serif SC", Georgia, serif;
  font-size: 31px;
}
.signal {
  border-left-color: var(--celestial-gold);
  background: #1b2a39;
}
.signal strong {
  color: #e2bd7d;
}
.signal p {
  color: #d0d7dd;
}
.panel {
  min-height: 430px;
  padding: 38px 40px;
  border: 1px solid #3a4d60;
  border-radius: 9px;
  background-color: var(--celestial-raised);
  background-image: url("@/assets/images/matchmaking-archive-night.jpg");
  background-position: 85% center;
  background-size: cover;
  background-blend-mode: soft-light;
  scroll-margin-top: 96px;
}
.panel h3 {
  color: #f2d49b;
  font-size: 30px;
  letter-spacing: 0.055em;
}
.panel p,
.panel dd {
  color: #e1e4e7;
}
.quote-card,
.channel-block {
  border-color: rgba(255, 255, 255, 0.18);
  background: rgba(9, 18, 29, 0.72);
}
.quote-mark {
  color: var(--celestial-gold);
}
.market-grid {
  border-color: var(--celestial-line);
}
.market-grid > div {
  border-color: var(--celestial-line);
  background: rgba(10, 20, 31, 0.52);
}
.market-grid dt,
.channel-block dt {
  color: #e2bd7d;
}
.faq li {
  border-color: var(--celestial-line);
}
.faq .q,
.faq .a {
  color: #e5e4e2;
}
.faq .q {
  color: #f0c9d4;
}
.faq .who,
.faq .a .who {
  background: #9d485f;
}
.copy {
  border-color: #b96178;
  background: #9d485f;
}
.portrait-frame {
  border-color: #cba86d;
  background: #26384a;
}
.legacy-hint {
  padding: 14px 16px;
  border-left: 3px solid var(--celestial-gold);
  background: rgba(9, 18, 29, 0.66);
}
.legacy-section {
  padding: 22px 0;
  border-bottom: 1px solid var(--celestial-line);
}
.legacy-section:last-child {
  border-bottom: 0;
}
.legacy-section h4 {
  margin: 0 0 12px;
  color: #e2bd7d;
  font-family: "Noto Serif SC", "Songti SC", serif;
  font-size: 21px;
  font-weight: 400;
}
.legacy-list,
.legacy-map {
  display: grid;
  gap: 12px;
  margin: 0;
  padding-left: 22px;
  color: #e1e4e7;
}
.legacy-list li,
.legacy-map > div {
  line-height: 1.8;
}
.legacy-list strong,
.legacy-list span,
.legacy-map dt,
.legacy-map dd {
  display: block;
  margin: 0;
}
.legacy-list strong,
.legacy-map dt {
  color: #f0c9d4;
}
.disclaimer {
  border-left-color: var(--celestial-gold);
  background: #192735;
  color: #aeb8c1;
}

@media (max-width: 980px) {
  .report-layout {
    grid-template-columns: 250px minmax(0, 1fr);
  }
  .report {
    padding: 38px 28px;
  }
  .score-overview {
    padding: 40px 28px 28px;
  }
  .score-overview .legend {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
@media (max-width: 760px) {
  .report-cover {
    min-height: 0;
    background-position: center 45%;
    background-size: cover;
  }
  .cover-inner {
    min-height: 0;
    padding: 26px 20px 22px;
  }
  .section-nav {
    margin-top: 28px;
  }
  .report {
    padding: 27px 16px 30px;
  }
  .report h2 {
    font-size: 32px;
  }
  .score-overview {
    grid-template-columns: 1fr;
    min-height: 0;
    padding: 34px 18px 24px;
    background-position: 57% center;
  }
  .score-intro {
    align-items: center;
    text-align: center;
  }
  .score-intro > p {
    margin-bottom: 20px;
  }
  .score-intro .score-number strong {
    font-size: 92px;
  }
  .score-chart-wrap {
    margin-top: -8px;
  }
  .score-overview .legend {
    grid-template-columns: 1fr 1fr;
  }
  .panel {
    min-height: 0;
    padding: 28px 20px;
  }
  .panel h3 {
    font-size: 25px;
  }
}
</style>
