<template>
  <main class="matchmaking-page">
    <section class="hero">
      <p>MOONLIT SALON · MIMO REPORT</p>
      <h1>月下会客厅<br /><em>把自己的牌，慢慢说清楚。</em></h1>
      <p>
        这是相亲市场定位体检：收入等条件用区间选择，性格用 8
        题快测；服务器按公开规则对五个维度确定性打分，Mimo
        据此生成板块式报告——市场解读、详细自我介绍、高频问答、各维度行动建议、推荐伴侣画像（可配
        AI 虚构人像照片）与渠道打法。不做撮合、不预测成功率。
      </p>
      <div class="meta">
        <span>{{ !auth.isLoggedIn || auth.isMatchmakingTrial ? "邀请码内测" : "登录后使用" }}</span><span>仅本人可见 · 最长保存 30 天</span
        ><span>{{ !auth.isLoggedIn || auth.isMatchmakingTrial ? "本次免费" : "按份扣积分" }}</span>
      </div>
    </section>
    <section v-if="!auth.isLoggedIn" class="trial-gate">
      <p>PRIVATE BETA · ONE REPORT</p>
      <h2>输入婚恋内测邀请码</h2>
      <p>无需注册账号。每枚邀请码可免费生成一份完整报告，生成失败可重试；成功后可凭原邀请码跨设备恢复查看。</p>
      <form @submit.prevent="redeemTrial">
        <input v-model.trim="trialCode" autocomplete="off" spellcheck="false" placeholder="MM-…" aria-label="婚恋内测邀请码" />
        <button class="primary" :disabled="redeeming || !trialCode">{{ redeeming ? "正在验证…" : "进入内测" }}</button>
      </form>
      <p class="trial-note">邀请码默认须在 7 天内首次兑换；报告最长保留 30 天。root 可在后台查看邀请码及使用状态。</p>
    </section>
    <template v-else>
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
          <li>伴侣画像照片由 AI 生成的虚构人像，仅作示意，不对应任何真实人物。</li>
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
        <p v-if="pollError" class="task-hint" role="status">{{ pollError }}</p>
        <router-link
          v-if="activeTask.status === 'done' && activeTask.reportId"
          class="primary task-link"
          :to="`/matchmaking-report/${activeTask.reportId}`"
          >查看报告 →</router-link
        >
      </section>

      <section v-if="auth.isMatchmakingTrial && trialWorkspace === 'unavailable'" class="notice error">
        报告已过期或已删除，内测次数不会恢复。
      </section>

      <div v-if="!auth.isMatchmakingTrial || trialWorkspace === 'questionnaire'" class="questionnaire-layout">
        <aside class="questionnaire-rail">
          <p>QUESTIONNAIRE · 06 CHAPTERS</p>
          <h2>把愿意说的，<br />一章章写下来。</h2>
          <ol>
            <li><button type="button" @click="jumpToGroup('question-group-basic')"><span>01</span>基础信息</button></li>
            <li><button type="button" @click="jumpToGroup('question-group-career')"><span>02</span>学历与工作</button></li>
            <li><button type="button" @click="jumpToGroup('question-group-finance')"><span>03</span>经济条件</button></li>
            <li><button type="button" @click="jumpToGroup('question-group-family')"><span>04</span>家庭情况</button></li>
            <li><button type="button" @click="jumpToGroup('question-group-personality')"><span>05</span>性格与相处</button></li>
            <li><button type="button" @click="jumpToGroup('question-group-partner')"><span>06</span>理想伴侣</button></li>
          </ol>
          <small>只填写你愿意提供的资料</small>
        </aside>
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

        <div id="question-group-basic" class="group">
          <h3><span>01</span>基础信息</h3>
          <p class="hint">
            带 * 为必填；其余全部自愿，留空的部分报告会如实说明。
          </p>
          <div class="grid">
            <label
              >意向城市或省份 *<select id="match-city" v-model="form.city">
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
            ><label
              >吸烟情况（自愿）<select v-model="form.smokingHabit">
                <option value="">不愿透露</option>
                <option>不吸烟</option>
                <option>偶尔吸烟</option>
                <option>经常吸烟</option>
              </select></label
            ><label
              >饮酒情况（自愿）<select v-model="form.drinkingHabit">
                <option value="">不愿透露</option>
                <option>不饮酒</option>
                <option>偶尔饮酒</option>
                <option>经常饮酒</option>
              </select></label
            >
          </div>
        </div>

        <div id="question-group-career" class="group">
          <h3><span>02</span>学历与工作</h3>
          <div class="grid">
            <label
              >最高学历/当前阶段 *<select id="match-education" v-model="form.education">
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
              >行业方向 *<input id="match-industry"
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

        <div id="question-group-finance" class="group">
          <h3><span>03</span>经济条件</h3>
          <p class="hint">收入只需选区间，精确金额不会传给模型。</p>
          <div class="grid finance">
            <label
              >税后月收入区间 *<select id="match-income" v-model="form.incomeBand">
                <option value="">请选择</option>
                <option>3千以下</option>
                <option>3千-5千</option>
                <option>5千-8千</option>
                <option>8千-1万</option>
                <option>1万-1万5</option>
                <option>1万5-2万</option>
                <option>2万-3万</option>
                <option>3万-5万</option>
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

        <div id="question-group-family" class="group">
          <h3><span>04</span>家庭情况</h3>
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
              >婚后居住期望（自愿）<select v-model="form.cohabitationExpectation">
                <option value="">不愿透露</option>
                <option>婚后与父母同住</option>
                <option>婚后分开住</option>
                <option>同小区就近住</option>
                <option>未想好</option>
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

        <div id="question-group-personality" class="group">
          <h3><span>05</span>性格与相处（8 题快测）</h3>
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

        <label class="image-toggle image-toggle-lead">
          <input type="checkbox" v-model="form.includePartnerImage" />
          <span><strong>生成推荐伴侣画像照片</strong>（+{{ status.imageMediumCredits }} 积分，竖版中档 AI 虚构人像，仅作示意）</span>
        </label>

        <div id="question-group-partner" class="group">
          <h3><span>06</span>理想伴侣问卷（用于伴侣推荐与 AI 生图）</h3>
          <p class="hint">
            这些选项会同时用于报告里的伴侣推荐和 AI 人像生成；留空则由报告根据你的条件自动推断。
          </p>
          <div class="grid">
            <label
              >伴侣性别<select v-model="form.portraitGender">
                <option value="">不指定</option>
                <option>女</option>
                <option>男</option>
              </select></label
            ><label
              >年龄段<select v-model="form.portraitAgeBand">
                <option value="">不指定</option>
                <option>22-26岁</option>
                <option>27-31岁</option>
                <option>32-36岁</option>
                <option>36岁以上</option>
              </select></label
            ><label
              >气质风格<select v-model="form.portraitStyle">
                <option value="">不指定</option>
                <option>温柔亲切</option>
                <option>干练知性</option>
                <option>阳光活力</option>
                <option>沉稳安静</option>
              </select></label
            ><label
              >发型<select v-model="form.portraitHair">
                <option value="">不指定</option>
                <option>长发</option>
                <option>短发</option>
                <option>扎发或盘发</option>
              </select></label
            ><label
              >拍摄场景<select v-model="form.portraitScene">
                <option value="">不指定</option>
                <option>日常休闲</option>
                <option>职业装</option>
                <option>咖啡馆约会</option>
                <option>户外自然</option>
              </select></label
            >
          </div>
        </div>
        <button class="primary" :disabled="creating || taskRunning" @click="generate">
          {{
            creating
              ? "正在提交任务…"
              : taskRunning
                ? "已有任务在生成中"
                : auth.isMatchmakingTrial
                  ? "免费生成本次内测报告"
                  : `提交生成任务（${status.creditCost}${form.includePartnerImage ? " + " + status.imageMediumCredits : ""} 积分）`
          }}
        </button>
        <p class="submit-hint">
          提交后任务在后台运行，可随时离开本页，回到“婚恋定位体检”查看进度与结果。
        </p>
        </section>
      </div>

      <section class="reports-list">
        <header>
          <div>
            <p>02 · 报告管理</p>
            <h2>{{ auth.isRoot ? "全站报告" : "历史报告" }}</h2>
          </div>
          <button v-if="!auth.isMatchmakingTrial" class="delete" @click="deleteData">
            {{ auth.isRoot ? "删除我的全部资料与报告" : "删除全部资料与报告" }}
          </button>
        </header>
        <p v-if="!reports.length" class="hint">
          {{ auth.isRoot ? "全站还没有有效报告。" : "还没有报告。填写上方资料，提交第一份定位体检任务。" }}
        </p>
        <div v-else class="report-rows">
          <button
            v-for="item in reports"
            :key="item.id"
            class="report-row"
            @click="openReport(item.id)"
          >
            <span v-if="auth.isRoot" class="row-owner">{{ item.ownerLabel || item.ownerUsername || "未知用户" }}</span>
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

      <NModal v-model:show="validationOpen" preset="dialog" title="请补充必填信息">
        <p class="validation-copy">提交前还需要填写：{{ missingRequiredLabels.join('、') }}。</p>
        <template #action>
          <NButton type="primary" @click="focusFirstMissing">返回填写</NButton>
        </template>
      </NModal>
    </template>
    </template>
  </main>
</template>
<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from "vue";
import { createTaskPoller } from "@/utils/taskPoller";
import { useRouter } from "vue-router";
import { NButton, NModal } from "naive-ui";
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
import { trialWorkspaceState } from "@/utils/matchmakingTrial";
const auth = useAuthStore();
const router = useRouter();
const loading = ref(true),
  creating = ref(false),
  error = ref(""),
  validationOpen = ref(false),
  activeTask = ref(null),
  reports = ref([]),
  taskCard = ref(null),
  trialCode = ref(""),
  redeeming = ref(false),
  catalogue = reactive({ cities: [], provinces: [] }),
  status = reactive({
    creditCost: 2,
    credits: 0,
    retentionDays: 30,
    imageLowCredits: 2,
    imageMediumCredits: 4,
  });
const pollError = ref("");
let disposed = false;
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
  smokingHabit: "",
  drinkingHabit: "",
  cohabitationExpectation: "",
  portraitGender: "",
  portraitAgeBand: "",
  portraitStyle: "",
  portraitHair: "",
  portraitScene: "",
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
const trialWorkspace = computed(() => trialWorkspaceState(auth.user?.trialAccess || {}));
const requiredFields = computed(() => [
  { value: form.city, label: "意向城市或省份", selector: "#match-city" },
  { value: form.education, label: "最高学历/当前阶段", selector: "#match-education" },
  { value: form.industry, label: "行业方向", selector: "#match-industry" },
  { value: form.incomeBand, label: "税后月收入区间", selector: "#match-income" },
]);
const missingRequired = computed(() =>
  requiredFields.value.filter((field) => !String(field.value || "").trim())
);
const missingRequiredLabels = computed(() => missingRequired.value.map((field) => field.label));
const taskPoller = createTaskPoller({
  fetchTask: getMatchmakingTask,
  onError: (text) => { pollError.value = text; },
  onTask: async (task) => {
    activeTask.value = task;
    if (task.status === "done") await refreshAfterDone();
    else if (task.status === "error" && !task.compensationPending) {
      if (auth.isMatchmakingTrial) await auth.refresh();
      await refreshAfterDone();
    }
  },
});
function stopPolling() { taskPoller.stop(); }
function startPolling() {
  pollError.value = "";
  if (!disposed && activeTask.value) taskPoller.start(activeTask.value.id);
}
async function refreshAfterDone() {
  if (auth.isMatchmakingTrial && activeTask.value?.reportId) {
    await router.replace(`/matchmaking-report/${activeTask.value.reportId}`);
    return;
  }
  try {
    const [r, s] = await Promise.all([getMatchmakingReports(), getMatchmakingStatus()]);
    reports.value = r.data || [];
    Object.assign(status, s.data || {});
    auth.updateCredits(status.credits);
  } catch { /* 保留当前列表 */ }
}
async function loadWorkspace() {
  loading.value = true;
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
      (task) => task.status === "queued" || task.status === "running" || task.compensationPending
    );
    if (running) { activeTask.value = running; startPolling(); }
  } catch (e) {
    error.value = e.message || "加载婚恋报告失败";
  } finally {
    loading.value = false;
  }
}
async function redeemTrial() {
  error.value = "";
  redeeming.value = true;
  try {
    const user = await auth.redeemTrial(trialCode.value);
    const access = user?.trialAccess || {};
    if (access.status === "COMPLETED" && access.reportId && access.reportAvailable !== false) {
      await router.replace(`/matchmaking-report/${access.reportId}`);
      return;
    }
    await loadWorkspace();
  } catch (e) {
    error.value = e.message || "邀请码验证失败";
  } finally {
    redeeming.value = false;
  }
}
onMounted(async () => {
  if (!auth.isLoggedIn) {
    loading.value = false;
    return;
  }
  const access = auth.user?.trialAccess || {};
  if (auth.isMatchmakingTrial && access.status === "COMPLETED" && access.reportId && access.reportAvailable !== false) {
    await router.replace(`/matchmaking-report/${access.reportId}`);
    return;
  }
  await loadWorkspace();
});
onUnmounted(() => { disposed = true; stopPolling(); });
async function generate() {
  error.value = "";
  if (missingRequired.value.length) {
    validationOpen.value = true;
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
function jumpToGroup(id) {
  document.getElementById(id)?.scrollIntoView({ behavior: "smooth", block: "start" });
}
function focusFirstMissing() {
  validationOpen.value = false;
  requestAnimationFrame(() => {
    const target = document.querySelector(missingRequired.value[0]?.selector || "");
    target?.scrollIntoView({ behavior: "smooth", block: "center" });
    target?.focus({ preventScroll: true });
  });
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
    await refreshAfterDone();
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
  background: linear-gradient(120deg, #5c3a4a, #a04a63);
  border-radius: 24px;
}
.trial-gate {
  max-width: 720px;
  margin: 28px auto 0;
  padding: 34px;
  border: 1px solid #d4c3b0;
  background: #fffaf0;
  box-shadow: 0 18px 50px rgba(52, 32, 40, 0.09);
}
.trial-gate > p:first-child {
  margin: 0 0 8px;
  color: #8e3a52;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.14em;
}
.trial-gate h2 { margin: 0 0 10px; font-size: 28px; }
.trial-gate form { display: flex; gap: 10px; margin-top: 20px; }
.trial-gate input {
  min-width: 0;
  flex: 1;
  height: 46px;
  padding: 0 14px;
  border: 1px solid #bcae9e;
  background: #fff;
  font: inherit;
  letter-spacing: 0.05em;
}
.trial-gate .trial-note { margin-bottom: 0; color: #776d63; font-size: 13px; }
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
  color: #f3d7dd;
}
.task-card header p,
.reports-list header p {
  color: #b98497;
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
.row-owner {
  min-width: 76px;
  color: #d8c6b7;
  font-size: 12px;
  font-weight: 700;
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

/* Moonlit Salon: a private visual identity scoped to matchmaking. */
.matchmaking-page {
  --salon-night: #17121d;
  --salon-plum: #6f3045;
  --salon-plum-bright: #91425b;
  --salon-paper: #fffdf9;
  --salon-parchment: #f8f2e9;
  --salon-brass: #c5a36f;
  --salon-line: #dfd1c2;
  --salon-ink: #352b31;
  --salon-muted: #756a6f;
  max-width: 1180px;
  color: var(--salon-ink);
  font-family: "PingFang SC", "Microsoft YaHei", sans-serif;
}
.hero {
  position: relative;
  overflow: hidden;
  min-height: 390px;
  padding: 58px 58px 50px;
  border: 1px solid #35283a;
  border-radius: 26px;
  background-color: var(--salon-night);
  background-image: url("@/assets/images/matchmaking-moonlit-salon.jpg");
  background-position: 88% 42%;
  background-size: cover;
  background-blend-mode: luminosity;
  box-shadow: 0 24px 60px rgba(28, 18, 29, 0.2);
}
.hero > * {
  position: relative;
  z-index: 1;
}
.hero > p:first-child {
  color: #d9b982;
}
.hero h1 {
  max-width: 650px;
  margin: 18px 0 20px;
  font-family: "Songti SC", "STSong", "Noto Serif SC", serif;
  font-size: 54px;
  font-weight: 600;
  letter-spacing: 0.04em;
}
.hero em {
  display: inline-block;
  margin-top: 8px;
  color: #e7c99b;
  font-size: 30px;
  letter-spacing: 0.02em;
}
.hero p {
  max-width: 680px;
  color: #e0d5dc;
}
.meta span {
  border: 1px solid rgba(210, 185, 146, 0.32);
  background: rgba(24, 16, 25, 0.48);
  color: #eee4da;
}
.notice,
.disclaimer-top,
.task-card,
.form-card,
.reports-list {
  border-color: var(--salon-line);
  border-radius: 18px;
  background: var(--salon-paper);
}
.disclaimer-top {
  padding: 22px 26px;
  border-left: 3px solid var(--salon-brass);
  border-radius: 3px 16px 16px 3px;
  background: var(--salon-parchment);
}
.disclaimer-top li {
  color: #61564f;
}
.task-card {
  border-color: #453648;
  background-color: var(--salon-night);
  background-image: url("@/assets/images/matchmaking-moonlit-salon.jpg");
  background-position: 72% 70%;
  background-size: cover;
  background-blend-mode: luminosity;
  color: #fff8ef;
}
.task-card header p,
.task-card h2,
.task-card h2.done,
.task-card .task-hint {
  color: #f1e5db;
}
.task-card small {
  background: rgba(255, 255, 255, 0.08);
  color: #dfd2c8;
}
.task-track {
  background: rgba(255, 255, 255, 0.13);
}
.task-fill,
.task-fill.done {
  background: #c9a56e;
}
.task-fill.failed {
  background: #b85a63;
}
.task-link {
  border: 1px solid #d5bd94;
  border-radius: 8px;
  background: #f4e6cf;
  color: #4b2c39;
}
.form-card {
  padding: 32px 34px 36px;
  border-radius: 22px;
  box-shadow: 0 16px 42px rgba(45, 30, 42, 0.07);
}
.form-card header,
.reports-list header {
  padding-bottom: 18px;
  border-bottom: 1px solid var(--salon-line);
}
.form-card header p,
.reports-list header p {
  color: var(--salon-plum-bright);
}
.form-card h2,
.reports-list h2,
.group h3 {
  font-family: "Songti SC", "STSong", "Noto Serif SC", serif;
  color: var(--salon-ink);
}
.form-card h2 {
  font-size: 30px;
}
.form-card small {
  border: 1px solid #e0d2c2;
  background: var(--salon-parchment);
  color: #725d62;
}
.group {
  margin-top: 28px;
  padding-top: 24px;
  border-top: 1px solid var(--salon-line);
}
.group h3 {
  font-size: 19px;
  font-weight: 600;
}
.group .hint,
.submit-hint {
  color: var(--salon-muted);
}
.grid {
  gap: 17px 15px;
}
.grid label {
  color: #66575d;
}
.grid input,
.grid select,
.grid textarea {
  box-sizing: border-box;
  width: 100%;
  min-height: 44px;
  border-color: #d8ccc0;
  border-radius: 8px;
  background: #fffcf8;
  color: var(--salon-ink);
  transition: border-color 0.16s ease, box-shadow 0.16s ease;
}
.grid input:focus,
.grid select:focus,
.grid textarea:focus {
  outline: none;
  border-color: var(--salon-plum-bright);
  box-shadow: 0 0 0 3px rgba(145, 66, 91, 0.12);
}
.image-toggle {
  color: #66575d;
}
.image-toggle input {
  accent-color: var(--salon-plum-bright);
}
.primary {
  padding: 13px 21px;
  border: 1px solid #7d3c52;
  border-radius: 8px;
  background: var(--salon-plum);
  color: #fffaf4;
  box-shadow: 0 8px 18px rgba(111, 48, 69, 0.16);
}
.primary:hover:not(:disabled) {
  background: var(--salon-plum-bright);
}
.reports-list {
  padding: 30px 34px;
  background: var(--salon-parchment);
}
.report-rows {
  gap: 0;
}
.report-row {
  padding: 17px 4px;
  border: 0;
  border-bottom: 1px solid var(--salon-line);
  border-radius: 0;
  background: transparent;
}
.report-row:hover {
  border-color: var(--salon-plum-bright);
  color: var(--salon-plum);
}
.row-score {
  border: 1px solid #d9c5cc;
  background: #f4e6ea;
  color: var(--salon-plum);
}
.row-img {
  border: 1px solid #e0d1b9;
  background: #f5ead8;
  color: #7a5e38;
}
.row-arrow {
  color: var(--salon-plum);
}
@media (max-width: 760px) {
  .hero {
    min-height: 0;
    padding: 38px 25px 34px;
    background-position: 70% 48%;
  }
  .hero h1 {
    font-size: 39px;
  }
  .hero em {
    font-size: 23px;
  }
  .form-card,
  .reports-list {
    padding: 22px 18px 26px;
  }
}

/* Celestial Portrait: the complete questionnaire, progress and archive system. */
.matchmaking-page {
  --night: #0d1520;
  --night-raised: #172333;
  --ivory: #f6f0e6;
  --rose: #b96178;
  --gold: #d7b170;
  --ink: #2f2b30;
  max-width: 1240px;
  padding-bottom: 76px;
}
.hero {
  min-height: 470px;
  padding: 70px 68px 56px;
  border-color: #304153;
  border-radius: 12px;
  background-color: var(--night);
  background-image: url("@/assets/images/matchmaking-questionnaire-night.jpg");
  background-position: 74% 47%;
  background-blend-mode: normal;
  box-shadow: 0 28px 70px rgba(10, 17, 27, 0.26);
}
.hero::after {
  content: "";
  position: absolute;
  inset: 0;
  background: rgba(7, 13, 21, 0.16);
  pointer-events: none;
}
.hero h1,
.hero em,
.form-card h2,
.reports-list h2,
.group h3,
.questionnaire-rail h2 {
  font-family: "Noto Serif SC", "Songti SC", serif;
  font-weight: 600;
}
.hero h1 {
  max-width: 620px;
  font-size: clamp(46px, 5.2vw, 70px);
  line-height: 1.06;
  letter-spacing: 0.055em;
}
.hero em {
  font-size: clamp(24px, 2.6vw, 34px);
  color: #e8c991;
}
.hero > p:nth-of-type(2) {
  max-width: 620px;
  color: #e8e0d8;
  font-size: 15px;
  line-height: 1.9;
}
.disclaimer-top {
  margin: 22px 0;
  border: 1px solid #d7c8b3;
  border-left: 4px solid var(--gold);
  background: #f1e9dd;
}
.task-card {
  min-height: 240px;
  padding: 40px 42px;
  border-color: #304153;
  border-radius: 12px;
  background-image: url("@/assets/images/matchmaking-questionnaire-night.jpg");
  background-position: 65% 54%;
  background-blend-mode: normal;
  box-shadow: 0 20px 48px rgba(12, 21, 32, 0.2);
}
.task-card::after {
  content: "";
  position: absolute;
  inset: 0;
  border-radius: inherit;
  background: rgba(8, 16, 26, 0.38);
  pointer-events: none;
}
.task-card > * {
  position: relative;
  z-index: 1;
}
.questionnaire-layout {
  display: grid;
  grid-template-columns: 286px minmax(0, 1fr);
  margin-top: 22px;
  overflow: hidden;
  border: 1px solid #cdbda8;
  border-radius: 12px;
  background: var(--ivory);
  box-shadow: 0 24px 64px rgba(23, 25, 30, 0.12);
}
.questionnaire-rail {
  position: relative;
  min-height: 100%;
  padding: 42px 30px;
  background-color: var(--night);
  background-image: url("@/assets/images/matchmaking-questionnaire-night.jpg");
  background-position: 76% center;
  background-size: cover;
  color: #fff9ef;
}
.questionnaire-rail::after {
  content: "";
  position: absolute;
  inset: 0;
  background: rgba(8, 15, 24, 0.61);
  pointer-events: none;
}
.questionnaire-rail > * {
  position: relative;
  z-index: 1;
}
.questionnaire-rail > p {
  color: var(--gold);
  font-size: 11px;
  letter-spacing: 0.18em;
}
.questionnaire-rail h2 {
  margin: 18px 0 30px;
  font-size: 32px;
  line-height: 1.35;
  letter-spacing: 0.04em;
}
.questionnaire-rail ol {
  display: grid;
  gap: 2px;
  margin: 0;
  padding: 0;
  list-style: none;
}
.questionnaire-rail li {
  border-bottom: 1px solid rgba(255, 255, 255, 0.13);
}
.questionnaire-rail li button {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 13px;
  padding: 11px 0;
  border: 0;
  background: transparent;
  color: #eee5dc;
  font: inherit;
  font-size: 14px;
  text-align: left;
  cursor: pointer;
}
.questionnaire-rail li button:hover,
.questionnaire-rail li button:focus-visible {
  color: #f3d8a7;
}
.questionnaire-rail li span {
  color: var(--gold);
  font-family: Georgia, serif;
  font-size: 12px;
}
.questionnaire-rail small {
  display: block;
  margin-top: 34px;
  color: #bdb5b2;
}
.form-card {
  margin: 0;
  padding: 42px 48px 48px;
  border: 0;
  border-radius: 0;
  background-color: var(--ivory);
  background-image: url("@/assets/images/matchmaking-report-paper.jpg");
  background-size: 720px auto;
  box-shadow: none;
}
.form-card header {
  align-items: flex-end;
}
.form-card h2 {
  font-size: 38px;
  letter-spacing: 0.06em;
}
.group {
  margin-top: 34px;
  padding-top: 30px;
  scroll-margin-top: 96px;
}
.group h3 {
  display: flex;
  align-items: baseline;
  gap: 14px;
  font-size: 25px;
  letter-spacing: 0.035em;
}
.group h3 > span {
  color: var(--rose);
  font-family: Georgia, serif;
  font-size: 14px;
  letter-spacing: 0.08em;
}
.grid input,
.grid select,
.grid textarea {
  box-sizing: border-box;
  width: 100%;
  border-color: #cabdac;
  border-radius: 5px;
  background: rgba(255, 252, 246, 0.78);
}
.grid input,
.grid select {
  height: 46px;
  min-height: 46px;
}
.image-toggle-lead {
  margin: 34px 0 -8px;
  padding: 18px 20px;
  border: 1px solid #d7c4a7;
  border-left: 4px solid var(--rose);
  background: rgba(255, 250, 241, 0.72);
  line-height: 1.65;
}
.image-toggle-lead strong {
  color: var(--ink);
}
.validation-copy {
  margin: 0;
  color: #5f5660;
  line-height: 1.75;
}
.grid input:focus,
.grid select:focus,
.grid textarea:focus {
  border-color: var(--rose);
  box-shadow: 0 0 0 3px rgba(185, 97, 120, 0.13);
}
.reports-list {
  position: relative;
  overflow: hidden;
  margin-top: 22px;
  padding: 38px 42px;
  border-color: #34475b;
  border-radius: 12px;
  background-color: var(--night);
  background-image: url("@/assets/images/matchmaking-archive-night.jpg");
  background-position: center 56%;
  background-size: cover;
  color: #f6eee6;
}
.reports-list::after {
  content: "";
  position: absolute;
  inset: 0;
  background: rgba(8, 15, 24, 0.5);
  pointer-events: none;
}
.reports-list > * {
  position: relative;
  z-index: 1;
}
.reports-list header {
  border-color: rgba(255, 255, 255, 0.2);
}
.reports-list h2,
.reports-list .hint,
.reports-list .row-main strong,
.reports-list .row-date {
  color: #fff8ef;
}
.reports-list .row-sub {
  color: #c8c0bc;
}
.reports-list .delete {
  color: #e6c690;
}
.report-row {
  border-color: rgba(255, 255, 255, 0.17);
  color: #f5ece5;
}
.report-row:hover {
  border-color: #d6b171;
  color: #fff;
}

@media (max-width: 900px) {
  .questionnaire-layout {
    grid-template-columns: 230px minmax(0, 1fr);
  }
  .questionnaire-rail {
    padding: 36px 24px;
  }
  .form-card {
    padding: 36px 30px 42px;
  }
}
@media (max-width: 760px) {
  .hero {
    min-height: 440px;
    padding: 40px 24px 32px;
    background-image: url("@/assets/images/matchmaking-moonlit-salon.jpg");
    background-position: center 34%;
  }
  .hero::after {
    background: rgba(7, 13, 21, 0.24);
  }
  .questionnaire-layout {
    display: block;
  }
  .questionnaire-rail {
    min-height: 360px;
    padding: 32px 22px;
    background-position: 72% 45%;
  }
  .questionnaire-rail ol {
    grid-template-columns: 1fr 1fr;
    column-gap: 18px;
  }
  .questionnaire-rail h2 {
    font-size: 29px;
  }
  .form-card {
    padding: 28px 18px 34px;
  }
  .form-card header {
    align-items: flex-start;
  }
  .form-card h2 {
    font-size: 32px;
  }
  .group h3 {
    align-items: flex-start;
    font-size: 22px;
  }
  .task-card,
  .reports-list {
    padding: 28px 22px;
  }
}
</style>
