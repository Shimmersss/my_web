<template>
  <main class="matchmaking-page">
    <section class="hero">
      <p>PERSONAL LIFE LEDGER · MIMO REPORT</p>
      <h1>把条件写清楚，<br /><em>把生活算明白。</em></h1>
      <p>
        这是个人生活条件账本，不是人的标价。Agent
        只据你自愿填写的资料、确定性预算计算和公开统计写报告，不做撮合或成功率预测。
      </p>
      <div class="meta">
        <span>登录后使用</span><span>仅本人账户可见 · 保存 30 天</span
        ><span>按份扣积分</span>
      </div>
    </section>
    <section v-if="error" class="notice error">{{ error }}</section>
    <section v-if="loading" class="notice">正在加载你的报告空间…</section>
    <template v-else
      ><section class="privacy">
        <strong>资料边界</strong
        ><span
          >不填姓名、住址、单位或联系人；金额默认可填区间，精确金额仅在你主动选择时填写。资料按登录账户
          ID 隔离，可随时删除全部资料。</span
        >
      </section>
      <section class="form-card">
        <header>
          <div>
            <p>01 · 自愿档案</p>
            <h2>基础条件与生活计划</h2>
          </div>
          <small
            >每份 {{ status.creditCost }} 积分 · 当前
            {{ status.credits }} 积分</small
          >
        </header>
        <div class="grid">
          <label
            >意向城市或省份<select v-model="form.city">
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
            >最高学历/当前阶段<select v-model="form.education">
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
            >行业方向<input
              v-model.trim="form.industry"
              maxlength="60"
              placeholder="例如：制造业、教育、互联网" /></label
          ><label
            >工作年限<input
              v-model.number="form.workYears"
              type="number"
              min="0"
              max="50" /></label
          ><label
            >住房状态<input
              v-model.trim="form.housingStatus"
              maxlength="40"
              placeholder="例如：租住 / 自有房有月供"
          /></label>
        </div>
        <div class="mode">
          <strong>收入填写方式</strong
          ><button
            :class="{ active: form.incomeMode === 'range' }"
            @click="form.incomeMode = 'range'"
          >
            金额区间</button
          ><button
            :class="{ active: form.incomeMode === 'exact' }"
            @click="form.incomeMode = 'exact'"
          >
            精确金额
          </button>
        </div>
        <div class="grid finance">
          <template v-if="form.incomeMode === 'range'"
            ><label
              >月收入下限（元）<input
                v-model.number="form.incomeBandLow"
                type="number"
                min="0" /></label
            ><label
              >月收入上限（元）<input
                v-model.number="form.incomeBandHigh"
                type="number"
                min="0" /></label></template
          ><label v-else
            >税后月收入（元）<input
              v-model.number="form.monthlyIncome"
              type="number"
              min="0" /></label
          ><label
            >可用储蓄（元）<input
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
            >计划目标预算（元）<input
              v-model.number="form.targetBudget"
              type="number"
              min="0" /></label
          ><label
            >计划时间线（月）<input
              v-model.number="form.timelineMonths"
              type="number"
              min="1"
              max="240"
          /></label>
        </div>
        <button class="primary" :disabled="creating" @click="generate">
          {{
            creating
              ? "Mimo 正在整理报告…"
              : `生成个人专属报告（${status.creditCost} 积分）`
          }}
        </button>
      </section>
      <section v-if="report" class="report">
        <header>
          <div>
            <p>02 · 专属报告</p>
            <h2>你的条件账本</h2>
          </div>
          <button class="delete" @click="deleteData">删除全部资料与报告</button>
        </header>
        <p class="expiry">
          保存至
          {{
            formatDate(report.expiresAt)
          }}；仅当前登录账户可读取，原始财务资料不会展示在 Agent 文案中。
        </p>
        <div class="context">
          <strong
            >{{ report.context.selection }} ·
            {{ report.context.granularityLabel }}</strong
          ><span
            >2024 居民人均可支配收入
            {{ report.context.disposableIncome?.toLocaleString() }} 元 ·
            结婚登记 {{ report.context.marriageRegistrationsWan }} 万对</span
          ><a
            :href="report.context.sourceUrl"
            target="_blank"
            rel="noreferrer"
            >{{ report.context.source }}</a
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
          <article>
            <span>目标每月补足</span
            ><strong>{{ money(report.ledger.targetMonthlyFunding) }}</strong
            ><small>按当前时间线</small>
          </article>
        </div>
        <div class="signal">
          <strong>账本提示</strong>
          <p>{{ report.ledger.budgetSignal }}</p>
        </div>
        <div class="narrative">
          <article>
            <h3>Agent 概览</h3>
            <p>{{ report.narrative.summary }}</p>
          </article>
          <article>
            <h3>学历与职业表达</h3>
            <p>{{ report.narrative.educationCareerAdvice }}</p>
          </article>
          <article>
            <h3>下一步行动</h3>
            <ol>
              <li v-for="item in report.narrative.nextActions" :key="item">
                {{ item }}
              </li>
            </ol>
          </article>
          <article>
            <h3>数据局限</h3>
            <p>{{ report.narrative.limitations }}</p>
          </article>
        </div>
      </section>
    </template>
  </main>
</template>
<script setup>
import { onMounted, reactive, ref } from "vue";
import {
  createMatchmakingReport,
  deleteMatchmakingData,
  getLatestMatchmakingReport,
  getMatchmakingCatalogue,
  getMatchmakingStatus,
} from "@/api";
import { useAuthStore } from "@/stores/auth";
const auth = useAuthStore();
const loading = ref(true),
  creating = ref(false),
  error = ref(""),
  report = ref(null),
  catalogue = reactive({ cities: [], provinces: [] }),
  status = reactive({ creditCost: 2, credits: 0, retentionDays: 30 });
const form = reactive({
  city: "",
  education: "",
  studyStatus: "",
  industry: "",
  workYears: 0,
  housingStatus: "",
  incomeMode: "range",
  monthlyIncome: 0,
  incomeBandLow: 0,
  incomeBandHigh: 0,
  savings: 0,
  housingCost: 0,
  debtPayment: 0,
  familySupport: 0,
  targetBudget: 0,
  timelineMonths: 24,
});
onMounted(async () => {
  try {
    const [c, s, r] = await Promise.all([
      getMatchmakingCatalogue(),
      getMatchmakingStatus(),
      getLatestMatchmakingReport(),
    ]);
    Object.assign(catalogue, c.data || {});
    Object.assign(status, s.data || {});
    report.value = r.data?.report || null;
  } catch (e) {
    error.value = e.message || "加载婚恋报告失败";
  } finally {
    loading.value = false;
  }
});
async function generate() {
  error.value = "";
  creating.value = true;
  try {
    const res = await createMatchmakingReport({ ...form });
    report.value = res.data;
    status.credits = Number(res.data?.credits ?? status.credits);
    auth.updateCredits(status.credits);
  } catch (e) {
    error.value = e.message || "报告生成失败";
  } finally {
    creating.value = false;
  }
}
async function deleteData() {
  if (!window.confirm("确定删除已保存的个人档案和报告吗？此操作不可恢复。"))
    return;
  try {
    await deleteMatchmakingData();
    report.value = null;
    error.value = "";
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
.report header p {
  font-size: 11px;
  letter-spacing: 0.12em;
  font-weight: 700;
  color: #b7d5c9;
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
.form-card small {
  padding: 6px 9px;
  border-radius: 99px;
  background: #ffffff1b;
  font-size: 12px;
}
.notice,
.privacy,
.form-card,
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
.privacy {
  display: flex;
  gap: 14px;
  line-height: 1.65;
  background: #f4f8f6;
}
.privacy strong {
  white-space: nowrap;
}
.form-card header,
.report header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: start;
}
.form-card h2,
.report h2 {
  margin: 3px 0 0;
  font-size: 25px;
}
.grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px;
  margin: 24px 0;
}
.grid label {
  display: grid;
  gap: 7px;
  font-size: 13px;
  font-weight: 650;
  color: #52616a;
}
.grid input,
.grid select {
  padding: 11px;
  border: 1px solid #cfdadd;
  border-radius: 8px;
  background: #fff;
  font: inherit;
  color: #1d2730;
}
.mode {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}
.mode button {
  border: 1px solid #cfdadd;
  border-radius: 99px;
  background: #fff;
  padding: 7px 12px;
  cursor: pointer;
}
.mode button.active {
  background: #274e4b;
  color: #fff;
  border-color: #274e4b;
}
.primary {
  margin-top: 6px;
  padding: 12px 18px;
  border: 0;
  border-radius: 8px;
  background: #b94e3d;
  color: #fff;
  font-weight: 700;
  cursor: pointer;
}
.primary:disabled {
  opacity: 0.55;
  cursor: not-allowed;
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
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
  margin: 16px 0;
}
.metrics article,
.narrative article {
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
.narrative {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 10px;
  margin-top: 12px;
}
.narrative h3 {
  margin: 0 0 8px;
  font-size: 15px;
}
.narrative p,
.narrative li {
  line-height: 1.7;
  color: #42525b;
  font-size: 14px;
}
.narrative ol {
  margin: 0;
  padding-left: 20px;
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
  .privacy,
  .form-card,
  .report {
    padding: 18px;
  }
  .privacy {
    display: block;
  }
  .privacy span {
    display: block;
    margin-top: 8px;
  }
  .grid,
  .metrics,
  .narrative {
    grid-template-columns: 1fr;
  }
  .form-card header,
  .report header {
    display: block;
  }
  .form-card small {
    display: inline-block;
    margin-top: 10px;
  }
  .delete {
    margin-top: 12px;
    padding: 0;
  }
  .finance {
    margin-top: 16px;
  }
}
</style>
