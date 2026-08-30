<template>
  <main class="matchmaking-page">
    <section class="hero">
      <p>MARKET POSITIONING · MIMO REPORT</p>
      <h1>看清市场规则，<br /><em>把自己的牌打明白。</em></h1>
      <p>
        这是相亲市场定位体检：收入等条件用区间选择，性格用 8
        题快测；服务器按公开规则对五个维度确定性打分，Mimo
        据此生成板块式报告——市场解读、详细自我介绍、高频问答、各维度行动建议、推荐伴侣画像（可配
        AI 插画）与渠道打法。不做撮合、不预测成功率。
      </p>
      <div class="meta">
        <span>登录后使用</span><span>仅本人账户可见 · 保存 30 天</span
        ><span>按份扣积分</span>
      </div>
    </section>
    <section v-if="error" class="notice error">{{ error }}</section>
    <section v-if="loading" class="notice">正在加载你的报告空间…</section>
    <template v-else
      ><section class="disclaimer-top">
        <strong>免责声明</strong>
        <ul>
          <li>
            评分由服务器按公开规则确定性计算（年龄、身高的市场通行曲线按性别区分），仅描述相亲市场供需参考，不代表、也不衡量任何人的个人价值。
          </li>
          <li>
            统计背景来自国家统计局 2025 年收入数据与民政部 2025 年婚姻登记数据，均为公开聚合数据，不是个人基准。
          </li>
          <li>
            报告基于你自愿填写的资料与算法分析生成，仅供参考，不构成任何承诺；不做撮合、不预测成功率、不推荐具体对象。
          </li>
          <li>
            不收集姓名、住址、单位名称、联系人；资料仅本人可见，保存 30 天自动删除，可随时手动删除。
          </li>
          <li>伴侣画像插画由 AI 生成，纯属示意，与任何真实人物无关。</li>
        </ul>
      </section>

      <section v-if="activeTask" ref="taskCard" class="task-card">
        <header>
          <div>
            <p>生成任务</p>
            <h2 :class="{ done: activeTask.status === 'done', failed: activeTask.status === 'error' }">
              {{ stageText }}
            </h2>
          </div>
          <small>#{{ activeTask.id?.slice(0, 8) }}</small>
        </header>
        <div class="task-track">
          <div
            class="task-fill"
            :class="{ done: activeTask.status === 'done', failed: activeTask.status === 'error' }"
            :style="{ width: taskProgress + '%' }"
          ></div>
        </div>
        <p class="task-hint">
          <template v-if="activeTask.status === 'queued' || activeTask.status === 'running'"
            >生成在服务器后台进行，切换到其他页面不会中断，稍后回到本页即可继续查看进度。</template
          ><template v-else-if="activeTask.status === 'done'"
            >报告已完成并保存 30 天。</template
          ><template v-else>{{ activeTask.error || "生成失败，积分已退还。" }}</template>
        </p>
        <router-link
          v-if="activeTask.status === 'done' && activeTask.reportId"
          class="primary task-link"
          :to="`/matchmaking-report/${activeTask.reportId}`"
          >查看报告 →</router-link
        >
      </section>

      <section class="form-card">
        <header>
          <div>
            <p>01 · 自愿档案</p>
            <h2>定位资料</h2>
          </div>
          <small
            >每份 {{ status.creditCost }} 积分 · 当前
            {{ status.credits }} 积分</small
          >
        </header>

        <div class="group">
          <h3>基础信息</h3>
          <p class="hint">
            带 * 为必填；其余全部自愿，留空的部分报告会如实说明。
          </p>
          <div class="grid">
            <label
              >意向城市或省份 *<select v-model="form.city">
                <option value="">请选择</option>
                <optgroup label="重点城市">
                  <option v-for="city in catalogue.cities" :key="city">
                    {{ city }}
                  </option>
                </optgroup>
                <optgroup label="省级基准">
                  <option v-for="province in catalogue.provinces" :key="province">
                    {{ province }}
                  </option>
                </optgroup>
              </select></label
            ><label
              >性别（自愿）<select v-model="form.gender">
                <option value="">不愿透露</option>
                <option>男</option>
                <option>女</option>
              </select></label
            ><label
              >年龄<input v-model.number="form.age" type="number" min="16" max="70"
            /></label>
            <label
              >身高（cm）<input
                v-model.number="form.heightCm"
                type="number"
                min="100"
                max="250" /></label
            ><label
              >外貌自评（自愿）<select v-model="form.appearanceSelf">
                <option value="">不评价</option>
                <option>一般</option>
                <option>中上</option>
                <option>出众</option>
              </select></label
            ><label
              >婚姻状况（自愿）<select v-model="form.maritalStatus">
                <option value="">不愿透露</option>
                <option>未婚</option>
                <option>离异无子女</option>
                <option>离异有子女</option>
                <option>丧偶</option>
                <option>其他</option>
              </select></label
            ><label
              >户籍（自愿）<input
                v-model.trim="form.hukou"
                maxlength="24"
                placeholder="例如：浙江宁波" /></label
            ><label
              >是否独生子女（自愿）<select v-model="form.onlyChild">
                <option value="">不愿透露</option>
                <option>是</option>
                <option>否</option>
              </select></label
            >
          </div>
        </div>

        <div class="group">
          <h3>学历与工作</h3>
          <div class="grid">
            <label
              >最高学历/当前阶段 *<select v-model="form.education">
                <option value="">请选择</option>
                <option>大专</option>
                <option>本科</option>
                <option>硕士</option>
                <option>博士及以上</option>
              </select></label
            ><label
              >在读或毕业状态<input
                v-model.trim="form.studyStatus"
                maxlength="40"
                placeholder="例如：硕士在读 / 已毕业" /></label
            ><label
              >行业方向 *<input
                v-model.trim="form.industry"
                maxlength="60"
                placeholder="例如：制造业、教育、互联网" /></label
            ><label
              >单位性质（自愿）<select v-model="form.jobType">
                <option value="">不填写</option>
                <option>公务员</option>
                <option>事业编</option>
                <option>国企</option>
                <option>外企</option>
                <option>民营大厂</option>
                <option>民营中小企业</option>
                <option>自由职业</option>
                <option>个体经营</option>
                <option>其他</option>
              </select></label
            ><label
              >工作年限<input
                v-model.number="form.workYears"
                type="number"
                min="0"
                max="50" /></label
            ><label
              >工作强度（自愿）<select v-model="form.workIntensity">
                <option value="">不填写</option>
                <option>965</option>
                <option>996</option>
                <option>大小周</option>
                <option>倒班</option>
                <option>经常出差</option>
                <option>自由安排</option>
              </select></label
            >
          </div>
        </div>

        <div class="group">
          <h3>经济条件</h3>
          <p class="hint">收入只需选区间，精确金额不会传给模型。</p>
          <div class="grid finance">
            <label
              >税后月收入区间 *<select v-model="form.incomeBand">
                <option value="">请选择</option>
                <option>5千以下</option>
                <option>5千-1万</option>
                <option>1-2万</option>
                <option>2-3万</option>
                <option>3-5万</option>
                <option>5万以上</option>
                <option>不愿透露</option>
              </select></label
            ><label
              >收入构成（自愿）<input
                v-model.trim="form.incomeComposition"
                maxlength="60"
                placeholder="例如：工资+年终奖" /></label
            ><label
              >可用储蓄（元，自愿）<input
                v-model.number="form.savings"
                type="number"
                min="0" /></label
            ><label
              >房租/月供（元）<input
                v-model.number="form.housingCost"
                type="number"
                min="0" /></label
            ><label
              >每月债务还款（元）<input
                v-model.number="form.debtPayment"
                type="number"
                min="0" /></label
            ><label
              >每月家庭支持（元）<input
                v-model.number="form.familySupport"
                type="number"
                min="0" /></label
            ><label
              >住房状态（自愿）<select v-model="form.housingStatus">
                <option value="">不填写</option>
                <option>租住</option>
                <option>自有房有月供</option>
                <option>自有房无贷款</option>
                <option>与父母同住</option>
                <option>其他</option>
              </select></label
            ><label
              >车辆情况（自愿）<select v-model="form.hasCar">
                <option value="">不填写</option>
                <option>无</option>
                <option>有</option>
                <option>有贷款</option>
              </select></label
            >
          </div>
        </div>

        <div class="group">
          <h3>家庭情况</h3>
          <p class="hint">
            家庭情况在国内相亲市场影响很大，如实选择能让定位与问题准备更贴近实际。
          </p>
          <div class="grid">
            <label
              >父母退休金（自愿）<select v-model="form.parentsPension">
                <option value="">不愿透露</option>
                <option>有稳定退休金</option>
                <option>有部分</option>
                <option>无</option>
              </select></label
            ><label
              >父母健康状况（自愿）<select v-model="form.parentsHealth">
                <option value="">不愿透露</option>
                <option>健康</option>
                <option>一般</option>
                <option>需要照顾</option>
              </select></label
            ><label
              >家庭可提供的支持（自愿）<select v-model="form.parentsSupport">
                <option value="">不愿透露</option>
                <option>资助购房+帮带娃均可</option>
                <option>可资助购房</option>
                <option>可帮带娃</option>
                <option>暂无支持</option>
              </select></label
            ><label
              >彩礼/嫁妆想法（自愿）<input
                v-model.trim="form.bridePriceView"
                maxlength="80"
                placeholder="例如：按当地习俗协商" /></label
            ><label class="wide"
              >择偶期望（自愿）<textarea
                v-model.trim="form.partnerExpectations"
                maxlength="200"
                rows="2"
                placeholder="对对方的硬性要求与可让步之处，例如：希望工作稳定、能沟通；身高不限"
            /></label>
          </div>
        </div>

        <div class="group">
          <h3>性格与相处（8 题快测）</h3>
          <p class="hint">
            全部自愿；至少完成前 4
            题即可生成性格类型，选项分值反映的是相亲市场的第一印象偏好，不是性格优劣。
          </p>
          <div class="grid">
            <label
              v-for="question in personalityQuestions"
              :key="question.key"
              >{{ question.prompt }}<select v-model="form.personality[question.key]">
                <option value="">不回答</option>
                <option v-for="option in question.options" :key="option">
                  {{ option }}
                </option>
              </select></label
            >
          </div>
        </div>

        <label class="image-toggle">
          <input type="checkbox" v-model="form.includePartnerImage" />
          生成推荐伴侣画像插画（+{{ status.imageLowCredits }} 积分，AI
          生成仅作示意）
        </label>
        <button class="primary" :disabled="creating || taskRunning" @click="generate">
          {{
            creating
              ? "正在提交任务…"
              : taskRunning
                ? "已有任务在生成中"
                : `提交生成任务（${status.creditCost}${form.includePartnerImage ? " + " + status.imageLowCredits : ""} 积分）`
          }}
        </button>
        <p class="submit-hint">
          提交后任务在后台运行，可随时离开本页，回到“婚恋定位体检”查看进度与结果。
        </p>
      </section>

      <section class="reports-list">
        <header>
          <div>
            <p>02 · 报告管理</p>
            <h2>历史报告</h2>
          </div>
          <button class="delete" @click="deleteData">删除全部资料与报告</button>
        </header>
        <p v-if="!reports.length" class="hint">
          还没有报告。填写上方资料，提交第一份定位体检任务。
        </p>
        <div v-else class="report-rows">
          <button
            v-for="item in reports"
            :key="item.id"
            class="report-row"
            @click="openReport(item.id)"
          >
            <span class="row-city">{{ item.city || "—" }}</span>
            <span v-if="item.total != null" class="row-score"
              >{{ Number(item.total) }} 分 · {{ item.level }}</span
            >
            <span v-if="item.hasImage" class="row-img">含插画</span>
            <span class="row-date">{{ formatDate(item.createdAt) }}</span>
            <span class="row-arrow">→</span>
          </button>
        </div>
      </section>
    </template>
  </main>
</template>
<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import {
  createMatchmakingReport,
  deleteMatchmakingData,
  getMatchmakingCatalogue,
  getMatchmakingReports,
  getMatchmakingStatus,
  getMatchmakingTask,
  getMatchmakingTasks,
} from "@/api";
import { useAuthStore } from "@/stores/auth";
const auth = useAuthStore();
const router = useRouter();
const loading = ref(true),
  creating = ref(false),
  error = ref(""),
  activeTask = ref(null),
  reports = ref([]),
  taskCard = ref(null),
  catalogue = reactive({ cities: [], provinces: [] }),
  status = reactive({
    creditCost: 2,
    credits: 0,
    retentionDays: 30,
    imageLowCredits: 2,
  });
let pollTimer = null;
let pollFailures = 0;
const personalityQuestions = [
  { key: "q1", prompt: "周末通常怎么过？", options: ["聚会活动不断", "和少数好友小聚", "多数时间独处", "完全看心情"] },
  { key: "q2", prompt: "遇到矛盾通常怎么做？", options: ["当时就说开", "冷静后再谈", "先憋着以后再说", "看对方态度"] },
  { key: "q3", prompt: "消费习惯更接近？", options: ["记账储蓄优先", "有计划地消费", "比较随性", "花钱比较大方"] },
  { key: "q4", prompt: "作息规律吗？", options: ["规律早起", "规律晚睡", "不太规律"] },
  { key: "q5", prompt: "情绪状态如何？", options: ["很稳定", "偶尔波动", "波动比较大"] },
  { key: "q6", prompt: "对伴侣的家人？", options: ["主动亲近", "保持礼貌和边界", "容易觉得有压力"] },
  { key: "q7", prompt: "对婚姻承诺的态度？", options: ["期待稳定的承诺", "顺其自然", "想到就有点压力"] },
  { key: "q8", prompt: "家务分工的看法？", options: ["共同承担", "希望对方多承担", "各管各的"] },
];
const form = reactive({
  city: "",
  gender: "",
  age: null,
  heightCm: null,
  appearanceSelf: "",
  maritalStatus: "",
  hukou: "",
  education: "",
  studyStatus: "",
  industry: "",
  jobType: "",
  workYears: 0,
  workIntensity: "",
  incomeBand: "",
  incomeComposition: "",
  savings: 0,
  housingCost: 0,
  debtPayment: 0,
  familySupport: 0,
  housingStatus: "",
  hasCar: "",
  onlyChild: "",
  parentsPension: "",
  parentsHealth: "",
  parentsSupport: "",
  bridePriceView: "",
  partnerExpectations: "",
  includePartnerImage: true,
  personality: Object.fromEntries(personalityQuestions.map((q) => [q.key, ""])),
});
const STAGE_TEXT = {
  queued: "排队等待中",
  scoring: "正在计算五维评分与市场信号",
  writing: "Mimo 正在撰写报告（约 1–2 分钟）",
  illustrating: "正在生成伴侣画像插画",
  saving: "正在保存报告",
  done: "报告已生成",
  error: "生成失败",
};
const STAGE_PERCENT = {
  queued: 8, scoring: 22, writing: 55, illustrating: 82, saving: 94, done: 100, error: 100,
};
const stageText = computed(() => STAGE_TEXT[activeTask.value?.stage] || "…");
const taskProgress = computed(() => STAGE_PERCENT[activeTask.value?.stage] ?? 0);
const taskRunning = computed(
  () => activeTask.value?.status === "queued" || activeTask.value?.status === "running"
);
function stopPolling() {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
}
function startPolling() {
  stopPolling();
  pollFailures = 0;
  pollTimer = setInterval(async () => {
    if (!activeTask.value) return stopPolling();
    try {
      const res = await getMatchmakingTask(activeTask.value.id);
      pollFailures = 0;
      activeTask.value = res.data;
      if (res.data.status === "done") { stopPolling(); await refreshAfterDone(); }
      else if (res.data.status === "error") stopPolling();
    } catch {
      pollFailures += 1;
      if (pollFailures >= 3) stopPolling();
    }
  }, 2000);
}
async function refreshAfterDone() {
  try {
    const [r, s] = await Promise.all([getMatchmakingReports(), getMatchmakingStatus()]);
    reports.value = r.data || [];
    Object.assign(status, s.data || {});
    auth.updateCredits(status.credits);
  } catch { /* 保留当前列表 */ }
}
onMounted(async () => {
  try {
    const [c, s, r, t] = await Promise.all([
      getMatchmakingCatalogue(),
      getMatchmakingStatus(),
      getMatchmakingReports(),
      getMatchmakingTasks(),
    ]);
    Object.assign(catalogue, c.data || {});
    Object.assign(status, s.data || {});
    reports.value = r.data || [];
    const running = (t.data || []).find(
      (task) => task.status === "queued" || task.status === "running"
    );
    if (running) { activeTask.value = running; startPolling(); }
  } catch (e) {
    error.value = e.message || "加载婚恋报告失败";
  } finally {
    loading.value = false;
  }
});
onUnmounted(stopPolling);
async function generate() {
  error.value = "";
  if (!form.city || !form.education || !form.industry || !form.incomeBand) {
    error.value = "请先填写带 * 的必填项：城市、学历、行业、收入区间";
    return;
  }
  creating.value = true;
  try {
    const res = await createMatchmakingReport({ ...form });
    activeTask.value = { id: res.data.taskId, status: "queued", stage: "queued" };
    status.credits = Number(res.data?.credits ?? status.credits);
    auth.updateCredits(status.credits);
    startPolling();
    await Promise.resolve();
    taskCard.value?.scrollIntoView({ behavior: "smooth", block: "start" });
  } catch (e) {
    error.value = e.message || "任务提交失败";
  } finally {
    creating.value = false;
  }
}
function openReport(reportId) {
  router.push(`/matchmaking-report/${reportId}`);
}
async function deleteData() {
  if (!window.confirm("确定删除已保存的全部个人档案、任务与报告吗？此操作不可恢复。"))
    return;
  try {
    await deleteMatchmakingData();
    reports.value = [];
    activeTask.value = null;
    stopPolling();
    error.value = "";
  } catch (e) {
    error.value = e.message || "删除失败";
  }
}
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
.hero {
  padding: 54px 58px;
  color: #fff;
  background: linear-gradient(120deg, #25373d, #6a493b);
  border-radius: 24px;
}
.hero p {
  max-width: 650px;
  line-height: 1.75;
  color: #e1e8e7;
}
.hero > p:first-child,
.form-card header p,
.task-card header p,
.reports-list header p {
  font-size: 11px;
  letter-spacing: 0.12em;
  font-weight: 700;
  color: #b7d5c9;
}
.task-card header p,
.reports-list header p {
  color: #6a8f7f;
}
.hero h1 {
  font-size: 48px;
  line-height: 1.1;
  margin: 12px 0;
}
.hero em {
  color: #e2bf87;
  font-style: normal;
}
.meta {
  display: flex;
  gap: 9px;
  flex-wrap: wrap;
  margin-top: 22px;
}
.meta span,
.form-card small,
.task-card small {
  padding: 6px 9px;
  border-radius: 99px;
  background: #ffffff1b;
  font-size: 12px;
}
.notice,
.disclaimer-top,
.task-card,
.form-card,
.reports-list {
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
.disclaimer-top {
  background: #f7f3ec;
  border-color: #e8dcc8;
}
.disclaimer-top strong {
  font-size: 15px;
}
.disclaimer-top ul {
  margin: 10px 0 0;
  padding-left: 20px;
  display: grid;
  gap: 6px;
}
.disclaimer-top li {
  font-size: 13px;
  line-height: 1.7;
  color: #5c4a33;
}
.task-card {
  background: #f0f6f3;
  border-color: #cfe3da;
}
.task-card header,
.form-card header,
.reports-list header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: start;
}
.task-card h2,
.form-card h2,
.reports-list h2 {
  margin: 3px 0 0;
  font-size: 22px;
}
.task-card h2.done {
  color: #274e4b;
}
.task-card h2.failed {
  color: #a43932;
}
.task-card small {
  background: #ffffff8f;
  color: #52616a;
}
.task-track {
  height: 10px;
  border-radius: 99px;
  background: #dfe7e8;
  margin-top: 16px;
  overflow: hidden;
}
.task-fill {
  height: 100%;
  border-radius: 99px;
  background: linear-gradient(90deg, #274e4b, #6a8f7f);
  transition: width 0.6s ease;
}
.task-fill.done {
  background: #274e4b;
}
.task-fill.failed {
  background: #b94e3d;
}
.task-hint {
  margin: 10px 0 0;
  font-size: 13px;
  line-height: 1.7;
  color: #52616a;
}
.task-link {
  display: inline-block;
  margin-top: 12px;
  padding: 10px 16px;
  border-radius: 8px;
  background: #b94e3d;
  color: #fff;
  font-weight: 700;
  text-decoration: none;
}
.form-card h2 {
  font-size: 25px;
}
.group {
  margin: 22px 0 0;
  padding-top: 18px;
  border-top: 1px dashed #dfe7e8;
}
.group h3 {
  margin: 0;
  font-size: 16px;
}
.group .hint,
.submit-hint {
  margin: 6px 0 0;
  font-size: 12px;
  color: #687780;
}
.grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px;
  margin: 16px 0 0;
}
.grid label {
  display: grid;
  gap: 7px;
  font-size: 13px;
  font-weight: 650;
  color: #52616a;
}
.grid label.wide {
  grid-column: 1 / -1;
}
.grid input,
.grid select,
.grid textarea {
  padding: 11px;
  border: 1px solid #cfdadd;
  border-radius: 8px;
  background: #fff;
  font: inherit;
  color: #1d2730;
}
.grid textarea {
  resize: vertical;
}
.image-toggle {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-top: 20px;
  font-size: 13px;
  font-weight: 650;
  color: #52616a;
  cursor: pointer;
}
.image-toggle input {
  width: 16px;
  height: 16px;
  accent-color: #b94e3d;
}
.primary {
  margin-top: 12px;
  padding: 12px 18px;
  border: 0;
  border-radius: 8px;
  background: #b94e3d;
  color: #fff;
  font-weight: 700;
  cursor: pointer;
  font-size: 15px;
}
.primary:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.submit-hint {
  margin-top: 10px;
}
.delete {
  border: 0;
  background: transparent;
  color: #a43c33;
  cursor: pointer;
}
.reports-list .hint {
  margin: 12px 0 0;
  font-size: 13px;
  color: #687780;
}
.report-rows {
  margin-top: 14px;
  display: grid;
  gap: 8px;
}
.report-row {
  display: flex;
  gap: 12px;
  align-items: center;
  width: 100%;
  padding: 13px 15px;
  border: 1px solid #e3ebec;
  border-radius: 10px;
  background: #fff;
  cursor: pointer;
  font: inherit;
  text-align: left;
  transition: border-color 0.15s ease;
}
.report-row:hover {
  border-color: #274e4b;
}
.row-city {
  font-weight: 700;
  font-size: 14px;
}
.row-score {
  padding: 2px 10px;
  border-radius: 99px;
  background: #e6f0ed;
  color: #274e4b;
  font-size: 12px;
  font-weight: 650;
}
.row-img {
  padding: 2px 8px;
  border-radius: 99px;
  background: #f7f3ec;
  color: #8a6d3b;
  font-size: 12px;
}
.row-date {
  flex: 1;
  text-align: right;
  color: #687780;
  font-size: 12px;
}
.row-arrow {
  color: #274e4b;
  font-weight: 700;
}
@media (max-width: 760px) {
  .matchmaking-page {
    padding: 18px 12px 55px;
  }
  .hero {
    padding: 34px 25px;
  }
  .hero h1 {
    font-size: 37px;
  }
  .disclaimer-top,
  .task-card,
  .form-card,
  .reports-list {
    padding: 18px;
  }
  .grid {
    grid-template-columns: 1fr;
  }
  .form-card header,
  .task-card header,
  .reports-list header {
    display: block;
  }
  .form-card small,
  .task-card small {
    display: inline-block;
    margin-top: 10px;
  }
  .delete {
    margin-top: 12px;
    padding: 0;
  }
  .finance {
    margin-top: 12px;
  }
  .row-date {
    display: none;
  }
}
</style>
