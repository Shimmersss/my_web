<template>
  <main class="matchmaking-page">
    <section v-if="error" class="notice error">
      {{ error }}
      <router-link class="back-link" to="/matchmaking-report">返回报告列表</router-link>
    </section>
    <section v-if="loading" class="notice">正在加载报告…</section>
    <section v-if="report" class="report">
      <header>
        <div>
          <p>02 · 定位体检</p>
          <h2>你的市场定位</h2>
        </div>
        <button class="delete" @click="deleteReport">删除本报告</button>
      </header>
      <p class="expiry">
        生成于
        {{ formatDate(report.createdAt) }}，保存至
        {{ formatDate(report.expiresAt)
        }}；仅本人可读取，精确金额不传给模型、不出现在报告文案中。
      </p>
      <div class="context">
        <strong
          >{{ report.context.selection }} ·
          {{ report.context.granularityLabel }}</strong
        ><template v-if="isLegacy"
          ><span
            >{{ report.context.statYear }} 居民人均可支配收入
            {{ report.context.disposableIncome?.toLocaleString() }} 元 ·
            结婚登记
            {{ report.context.marriageRegistrationsWan }} 万对</span
          ><a
            v-if="report.context.sourceUrl"
            :href="report.context.sourceUrl"
            target="_blank"
            rel="noreferrer"
            >{{ report.context.source }}</a
          ></template
        ><template v-else
          ><span
            >{{ report.context.incomeStatYear }} 居民人均可支配收入
            {{ report.context.disposableIncome?.toLocaleString() }} 元（全国
            {{ report.context.nationalDisposableIncome?.toLocaleString() }} 元）</span
          ><span
            >全国 {{ report.context.nationalMarriageYear }} 年结婚登记
            {{ report.context.nationalMarriageRegistrationsWan }} 万对 ·
            本省口径为 {{ report.context.marriageRegistrationsYear }} 年鉴
            {{ report.context.marriageRegistrationsWan }} 万对</span
          ><a
            :href="report.context.incomeSourceUrl"
            target="_blank"
            rel="noreferrer"
            >收入来源：{{ report.context.incomeSource }}</a
          ><a
            :href="report.context.marriageSourceUrl"
            target="_blank"
            rel="noreferrer"
            >结婚登记来源：{{ report.context.marriageSource }}</a
          ></template
        >
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

      <div v-if="report.scores" class="score-overview">
        <div class="score-top">
          <strong class="score-total">{{ report.scores.total }}</strong>
          <span class="level-badge">{{ report.scores.level }}</span>
          <small
            >五维总分（满分 100 · 公开规则确定性计算）<template
              v-if="report.scores.personalityType"
            >
              · 性格类型：{{ report.scores.personalityType }}</template
            ></small
          >
        </div>
        <div class="score-visual">
          <svg
            class="pie"
            viewBox="0 0 260 200"
            role="img"
            aria-label="五维评分饼图"
          >
            <path
              v-for="seg in wallSegments"
              :key="'w' + seg.name"
              :d="wallPath(seg)"
              :fill="darker(seg.color)"
            />
            <path
              v-for="seg in faceSegments"
              :key="'f' + seg.name"
              :d="facePath(seg)"
              :fill="seg.color"
              :class="['seg', { selected: selectedDim === seg.name }]"
              @click="selectedDim = selectedDim === seg.name ? '' : seg.name"
            >
              <title>{{ seg.name }} {{ seg.score }} / {{ seg.max }}</title>
            </path>
            <text
              v-for="seg in faceSegments.filter((s) => s.percent >= 6)"
              :key="'t' + seg.name"
              :x="labelPos(seg).x"
              :y="labelPos(seg).y"
              class="seg-label"
            >
              {{ seg.percent }}%
            </text>
          </svg>
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
        </div>
        <div v-if="selectedDetail" class="dim-detail">
          <h3>
            {{ selectedDetail.name
            }}<span class="dim-badge"
              >{{ selectedDetail.score }} / {{ selectedDetail.max }}</span
            >
          </h3>
          <p class="basis">依据：{{ selectedDetail.basis }}</p>
          <p>{{ selectedDetail.analysis }}</p>
          <p class="actions">
            <strong>怎么做：</strong>{{ selectedDetail.actions }}
          </p>
        </div>
        <p v-else class="dim-hint">
          点击饼图扇区或图例，查看该维度的详细说明与行动建议。
        </p>
      </div>

      <nav
        v-if="report.scores"
        class="section-nav"
        aria-label="报告板块"
      >
        <button
          v-for="section in sections"
          :key="section.key"
          :class="{ active: activeSection === section.key }"
          @click="activeSection = section.key"
        >
          {{ section.label
          }}<small v-if="section.badge">{{ section.badge }}</small>
        </button>
      </nav>
      <div v-if="report.scores" class="panel">
        <template v-if="activeSection === 'intro'">
          <h3>自我介绍</h3>
          <p>{{ report.narrative.selfIntro }}</p>
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
              <p class="q">{{ item.question }}</p>
              <p class="a">{{ item.answer }}</p>
            </li>
          </ul>
        </template>
        <template v-else-if="activeSection === 'partner'">
          <h3>推荐的伴侣画像</h3>
          <img
            v-if="report.partnerImage"
            class="portrait-img"
            :src="'data:image/png;base64,' + report.partnerImage"
            alt="AI 生成的推荐伴侣画像插画（仅作示意）"
          />
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
      <div v-else class="panel">
        <h3>旧版报告</h3>
        <p class="legacy-hint">
          这份报告生成于旧版结构，以下按原始章节展示；如需五维评分与板块导航，请重新生成一份报告。
        </p>
        <section
          v-for="section in legacySections"
          :key="section.key"
          class="legacy-section"
        >
          <h4>{{ section.label }}</h4>
          <p v-if="section.kind === 'text'" class="legacy-text">
            {{ section.value }}
          </p>
          <ol v-else-if="section.kind === 'list'" class="legacy-list">
            <li v-for="(item, index) in section.value" :key="index">
              <template v-if="item.qa">
                <strong>{{ item.qa[0] }}</strong>
                <span>{{ item.qa[1] }}</span>
              </template>
              <template v-else>{{ item.text }}</template>
            </li>
          </ol>
          <dl v-else class="legacy-map">
            <div v-for="(value, key) in section.value" :key="key">
              <dt>{{ key }}</dt>
              <dd>{{ value }}</dd>
            </div>
          </dl>
        </section>
      </div>
      <p class="disclaimer">
        评分与市场规则描述 ≠ 个人价值评判：分数由服务器按公开规则确定性计算（年龄、身高的市场通行曲线按性别区分），只描述相亲市场供需参考；本报告不提供成功率或撮合建议。
      </p>
    </section>
  </main>
</template>
<script setup>
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  deleteMatchmakingReport,
  getMatchmakingReport,
} from "@/api";
const route = useRoute();
const router = useRouter();
const loading = ref(true),
  error = ref(""),
  report = ref(null),
  copied = ref(false),
  selectedDim = ref(""),
  activeSection = ref("intro");
const DIM_COLORS = ["#b94e3d", "#274e4b", "#c6925a", "#5b7a8c", "#7a9e7e"];
const DARK_COLORS = {
  "#b94e3d": "#8f3b2e",
  "#274e4b": "#1d3b39",
  "#c6925a": "#9a7244",
  "#5b7a8c": "#45606c",
  "#7a9e7e": "#5d7a60",
};
const CX = 130, CY = 92, RX = 100, RY = 55, DEPTH = 26;
const darker = (color) => DARK_COLORS[color] || color;
const segments = computed(() => {
  const dimensions = report.value?.scores?.dimensions || [];
  const totalScore = dimensions.reduce((sum, d) => sum + Number(d.score || 0), 0);
  let angle = -Math.PI / 2;
  return dimensions.map((dimension, index) => {
    const sweep = totalScore > 0 ? (Number(dimension.score || 0) / totalScore) * Math.PI * 2 : 0;
    const seg = {
      ...dimension,
      start: angle,
      end: angle + sweep,
      percent: totalScore > 0 ? Math.round((Number(dimension.score || 0) / totalScore) * 100) : 0,
      color: DIM_COLORS[index % DIM_COLORS.length],
    };
    angle += sweep;
    return seg;
  });
});
const faceSegments = computed(() => segments.value.filter((seg) => seg.end - seg.start > 0.02));
const wallSegments = computed(() =>
  faceSegments.value.filter((seg) => Math.sin((seg.start + seg.end) / 2) > -0.05)
);
const facePath = (seg) => {
  const x1 = CX + RX * Math.cos(seg.start), y1 = CY + RY * Math.sin(seg.start);
  const x2 = CX + RX * Math.cos(seg.end), y2 = CY + RY * Math.sin(seg.end);
  const large = seg.end - seg.start > Math.PI ? 1 : 0;
  return `M ${CX} ${CY} L ${x1} ${y1} A ${RX} ${RY} 0 ${large} 1 ${x2} ${y2} Z`;
};
const wallPath = (seg) => {
  const x1 = CX + RX * Math.cos(seg.start), y1 = CY + RY * Math.sin(seg.start);
  const x2 = CX + RX * Math.cos(seg.end), y2 = CY + RY * Math.sin(seg.end);
  const large = seg.end - seg.start > Math.PI ? 1 : 0;
  return `M ${x1} ${y1} L ${x1} ${y1 + DEPTH} A ${RX} ${RY} 0 ${large} 1 ${x2} ${y2 + DEPTH} L ${x2} ${y2} A ${RX} ${RY} 0 ${large} 0 ${x1} ${y1} Z`;
};
const labelPos = (seg) => {
  const mid = (seg.start + seg.end) / 2;
  return { x: CX + RX * 0.58 * Math.cos(mid), y: CY + RY * 0.58 * Math.sin(mid) + 4 };
};
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
const isLegacy = computed(() => !report.value?.scores);
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
  return Object.entries(value)
    .map(([key, item]) => `${key}：${flat(item)}`)
    .join("\n");
};
const legacySections = computed(() => {
  const narrative = report.value?.narrative;
  if (!narrative || typeof narrative !== "object") return [];
  return Object.entries(narrative)
    .map(([key, value]) => ({ key, value, label: LEGACY_LABELS[key] || key }))
    .filter(({ value }) => {
      if (Array.isArray(value)) return value.length > 0;
      if (value && typeof value === "object") return Object.keys(value).length > 0;
      return value != null && value !== "";
    })
    .map(({ key, value, label }) => {
      if (Array.isArray(value)) {
        const items = value
          .map((item) => {
            if (item && typeof item === "object" && item.question != null) {
              return { qa: [flat(item.question), flat(item.answer ?? "")] };
            }
            return { text: flat(item) };
          })
          .filter((item) => (item.qa ? item.qa[0] || item.qa[1] : item.text));
        return { key, label, kind: "list", value: items };
      }
      if (value && typeof value === "object") {
        const entries = Object.entries(value).map(([mapKey, item]) => [mapKey, flat(item)]);
        return { key, label, kind: "map", value: Object.fromEntries(entries) };
      }
      return { key, label, kind: "text", value: flat(value) };
    });
});
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
  if (!window.confirm("确定删除这份报告吗？此操作不可恢复。")) return;
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
  max-width: 1120px;
  margin: auto;
  padding: 42px 20px 80px;
  color: #1d2730;
}
.notice,
.report {
  margin-top: 18px;
  padding: 24px;
  border: 1px solid #dde5e6;
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
  color: #274e4b;
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
  color: #b7d5c9;
}
.report h2 {
  margin: 3px 0 0;
  font-size: 25px;
}
.delete {
  border: 0;
  background: transparent;
  color: #a43c33;
  cursor: pointer;
}
.expiry {
  color: #687780;
  font-size: 13px;
}
.context {
  display: grid;
  gap: 5px;
  padding: 17px;
  border-left: 3px solid #c6925a;
  background: #fbf7f0;
}
.context span,
.context a {
  font-size: 13px;
  color: #596b73;
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
  background: #f4f7f7;
}
.metrics span,
.metrics small {
  display: block;
  color: #61717a;
  font-size: 12px;
}
.metrics strong {
  display: block;
  font-size: 25px;
  margin: 9px 0;
}
.signal {
  padding: 18px;
  border-radius: 10px;
  background: #e6f0ed;
}
.signal p {
  margin: 8px 0 0;
}
.score-overview {
  margin-top: 12px;
  border-radius: 10px;
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
  color: #b94e3d;
  line-height: 1;
}
.level-badge {
  padding: 4px 12px;
  border-radius: 99px;
  background: #274e4b;
  color: #fff;
  font-size: 13px;
}
.score-top small {
  color: #61717a;
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
}
.seg {
  cursor: pointer;
  transition: opacity 0.15s ease;
}
.seg:hover {
  opacity: 0.82;
}
.seg.selected {
  stroke: #1d2730;
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
  padding: 8px 12px;
  border: 1px solid #dde5e6;
  border-radius: 8px;
  background: #fff;
  cursor: pointer;
  font: inherit;
  text-align: left;
}
.legend button.active {
  border-color: #274e4b;
  background: #e6f0ed;
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
  color: #52616a;
}
.dim-detail {
  margin-top: 14px;
  padding: 15px;
  border: 1px solid #dde5e6;
  border-radius: 8px;
  background: #fff;
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
  line-height: 1.7;
  color: #42525b;
  font-size: 14px;
}
.dim-detail .basis {
  font-size: 12px;
  color: #687780;
}
.dim-badge {
  padding: 2px 10px;
  border-radius: 99px;
  background: #274e4b;
  color: #fff;
  font-size: 12px;
  font-weight: 400;
}
.dim-hint {
  margin: 14px 0 0;
  font-size: 13px;
  color: #687780;
}
.section-nav {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 14px;
}
.section-nav button {
  padding: 8px 14px;
  border: 1px solid #cfdadd;
  border-radius: 99px;
  background: #fff;
  cursor: pointer;
  font: inherit;
  font-size: 13px;
  font-weight: 650;
  color: #52616a;
  display: flex;
  gap: 6px;
  align-items: center;
}
.section-nav button.active {
  background: #274e4b;
  border-color: #274e4b;
  color: #fff;
}
.section-nav small {
  font-weight: 400;
  opacity: 0.85;
}
.panel {
  margin-top: 10px;
  border-radius: 10px;
}
.panel h3 {
  margin: 0 0 10px;
  font-size: 16px;
}
.panel p {
  line-height: 1.75;
  color: #42525b;
  font-size: 14px;
  margin: 0 0 8px;
}
.panel .actions {
  margin: 8px 0 0;
}
.copy {
  padding: 6px 12px;
  border: 1px solid #cfdadd;
  border-radius: 99px;
  background: #fff;
  font-size: 12px;
  cursor: pointer;
}
.market-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
  margin: 0;
}
.market-grid dt {
  font-size: 13px;
  font-weight: 700;
  color: #274e4b;
  margin-bottom: 4px;
}
.market-grid dd {
  margin: 0;
  line-height: 1.7;
  color: #42525b;
  font-size: 14px;
}
.faq {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 10px;
}
.faq li {
  padding: 12px 14px;
  border: 1px solid #e3ebec;
  border-radius: 8px;
  background: #fff;
}
.faq .q {
  margin: 0;
  font-weight: 700;
}
.faq .a {
  margin: 6px 0 0;
  line-height: 1.7;
  color: #42525b;
  font-size: 14px;
}
.portrait-img {
  display: block;
  max-width: 380px;
  width: 100%;
  border-radius: 12px;
  margin-bottom: 12px;
}
.channel-block {
  padding: 12px 14px;
  border: 1px solid #e3ebec;
  border-radius: 8px;
  background: #fff;
}
.channel-block + .channel-block {
  margin-top: 10px;
}
.channel-block dt {
  font-size: 13px;
  font-weight: 700;
  color: #274e4b;
  margin-bottom: 4px;
}
.channel-block dd {
  margin: 0;
  line-height: 1.7;
  color: #42525b;
  font-size: 14px;
}
.legacy-hint {
  margin: 0 0 14px;
  font-size: 13px;
  color: #687780;
}
.legacy-section {
  padding: 14px 0 0;
  margin-top: 14px;
  border-top: 1px dashed #dfe7e8;
}
.legacy-section h4 {
  margin: 0 0 8px;
  font-size: 15px;
}
.legacy-text {
  margin: 0;
  line-height: 1.75;
  color: #42525b;
  font-size: 14px;
  white-space: pre-line;
}
.legacy-list {
  margin: 0;
  padding-left: 20px;
  display: grid;
  gap: 10px;
  line-height: 1.7;
  color: #42525b;
  font-size: 14px;
}
.legacy-list strong {
  display: block;
}
.legacy-map {
  margin: 0;
  display: grid;
  gap: 12px;
}
.legacy-map dt {
  font-size: 13px;
  font-weight: 700;
  color: #274e4b;
  margin-bottom: 4px;
}
.legacy-map dd {
  margin: 0;
  line-height: 1.7;
  color: #42525b;
  font-size: 14px;
  white-space: pre-line;
}
.disclaimer {
  margin: 14px 0 0;
  padding: 14px 16px;
  border-left: 3px solid #c6925a;
  background: #f7f3ec;
  font-size: 13px;
  line-height: 1.7;
  color: #5c4a33;
}
@media (max-width: 760px) {
  .matchmaking-page {
    padding: 18px 12px 55px;
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
  .portrait-img {
    max-width: 100%;
  }
}
</style>
