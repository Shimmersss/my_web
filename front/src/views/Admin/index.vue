<template>
  <main class="admin-page">
    <div class="admin-shell">
      <header class="admin-header">
        <div class="admin-header__copy">
          <div class="admin-header__eyebrow">
            <span class="eyebrow">CONTROL CENTER / ROOT ONLY</span>
            <span class="admin-live"><i aria-hidden="true"></i>已连接</span>
          </div>
          <h1>运营管理中心</h1>
          <p>统一管理访问、能力配置、额度和内容审核。</p>
        </div>
        <div class="admin-header__actions">
          <span class="admin-role">ROOT · 管理模式</span>
          <button class="ghost" @click="loadDashboard">刷新数据</button>
        </div>
      </header>
      <n-alert v-if="!auth.isRoot" type="warning" title="需要 root 账户登录" />
      <n-alert
        v-if="errorMsg"
        type="error"
        :title="errorMsg"
        closable
        @close="errorMsg = ''"
      />
      <template v-if="auth.isRoot">
        <section id="overview" class="admin-overview">
          <div class="admin-overview__heading">
            <div>
              <span class="eyebrow">OVERVIEW</span>
              <h2>今日概览</h2>
            </div>
            <p>先看运行状态，再处理访问、模型和账本配置。</p>
          </div>
          <section class="stat-grid" aria-label="后台统计概览">
            <div v-for="item in statCards" :key="item.label" class="stat-card">
              <span>{{ item.label }}</span>
              <strong>{{ item.value }}</strong>
              <small>{{ item.hint }}</small>
            </div>
          </section>
        </section>

        <OperationsPanel />
        <div class="admin-workspace">
          <aside class="admin-index" aria-label="后台分区导航">
            <div class="admin-index__heading">
              <span class="eyebrow">CONTROL INDEX</span>
              <strong>管理目录</strong>
            </div>
            <nav>
              <a href="#overview">总览 <span>01</span></a>
              <p>访问与社区</p>
              <a href="#access-panel">节目权限 <span>02</span></a>
              <a href="#community-panel">留言审核 <span>03</span></a>
              <p>能力与运行</p>
              <a href="#providers-panel">通用 API <span>04</span></a>
              <a href="#presentation-panel">演示生成 <span>05</span></a>
              <a href="#image-panel">图片服务 <span>06</span></a>
              <a href="#storage-panel">任务保留 <span>07</span></a>
              <a href="#ranking-panel">GitHub 榜单 <span>08</span></a>
              <p>账号与账本</p>
              <a href="#billing-panel">计费规则 <span>09</span></a>
              <a href="#invites-panel">邀请码 <span>10</span></a>
              <a href="#matchmaking-trial-panel">婚恋内测码 <span>11</span></a>
              <a href="#users-panel">用户与流水 <span>12</span></a>
              <a href="#matchmaking-panel">婚恋模型 <span>13</span></a>
            </nav>
            <div class="admin-index__note">
              <span class="admin-index__note-dot" aria-hidden="true"></span>
              <p>修改后请点击对应面板的保存按钮，配置才会对新任务生效。</p>
            </div>
          </aside>

          <div class="admin-content">
        <section id="access-panel" class="admin-panel">
          <div class="panel-title">
            <div>
              <span class="eyebrow">PROGRAM VISIBILITY</span>
              <h2>节目可见范围</h2>
            </div>
            <button class="primary" @click="saveVisibility">
              保存可见范围
            </button>
          </div>
          <p class="panel-desc">
            修改后点击保存才会对新访问生效；进入后台仅加载当前配置。
          </p>
          <div class="visibility-grid">
            <label v-for="item in visibilityItems" :key="item.key"
              >{{ item.label
              }}<select v-model="visibilityForm[item.key]">
                <option v-if="item.key !== 'ImageGenerate'" value="PUBLIC">
                  所有人（含访客）
                </option>
                <option value="USER">登录用户</option>
                <option value="ROOT">仅 root</option>
              </select></label
            >
          </div>
        </section>
        <section id="community-panel" class="admin-panel">
          <div class="panel-title">
            <div>
              <span class="eyebrow">GUESTBOOK MODERATION</span>
              <h2>留言管理</h2>
            </div>
            <select v-model="guestbookType" @change="loadGuestbook(1)">
              <option value="all">全部内容</option>
              <option value="message">主留言</option>
              <option value="reply">回复</option>
            </select>
          </div>
          <p class="panel-desc">
            删除主留言会一并删除其回复、点赞和相关通知；点击“定位”可在公开留言板查看上下文。
          </p>
          <div
            class="table-wrap audit-table-wrap"
            tabindex="0"
            role="region"
            aria-label="留言管理，可上下滚动"
          >
            <table>
              <thead>
                <tr>
                  <th>类型</th>
                  <th>作者</th>
                  <th>内容</th>
                  <th>互动</th>
                  <th>时间</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="entry in guestbookEntries" :key="entry.id">
                  <td>{{ entry.parentId ? "回复" : "主留言" }}</td>
                  <td>{{ entry.username }}</td>
                  <td class="guestbook-content">{{ entry.content }}</td>
                  <td>
                    ♥ {{ entry.likeCount || 0 }} · ↳ {{ entry.replyCount || 0 }}
                  </td>
                  <td>{{ formatGuestbookDate(entry.createdAt) }}</td>
                  <td>
                    <button class="small" @click="openGuestbookEntry(entry)">
                      定位</button
                    ><button
                      class="small danger"
                      @click="deleteGuestbook(entry)"
                    >
                      删除
                    </button>
                  </td>
                </tr>
                <tr v-if="!guestbookEntries.length">
                  <td colspan="6" class="muted">暂无留言</td>
                </tr>
              </tbody>
            </table>
          </div>
          <div v-if="guestbookTotalPages > 1" class="pagination-controls">
            <button
              class="small"
              :disabled="guestbookPage <= 1"
              @click="loadGuestbook(guestbookPage - 1)"
            >
              上一页</button
            ><span>{{ guestbookPage }} / {{ guestbookTotalPages }}</span
            ><button
              class="small"
              :disabled="guestbookPage >= guestbookTotalPages"
              @click="loadGuestbook(guestbookPage + 1)"
            >
              下一页
            </button>
          </div>
        </section>
        <section id="providers-panel" class="admin-panel">
          <div class="panel-title">
            <div>
              <span class="eyebrow">RUNTIME CONFIGURATION</span>
              <h2>API 与模型配置</h2>
            </div>
            <button class="primary" @click="saveApiSettings">保存配置</button>
          </div>
          <p class="panel-desc">
            修改后立即对新任务生效。API Key
            只显示末四位；留空表示保留原密钥。可先测试当前表单配置，再决定是否保存。
          </p>
          <div class="provider-grid">
            <div
              v-for="provider in providerCards"
              :key="provider.key"
              class="provider-card"
            >
              <div class="provider-head">
                <div>
                  <h3>{{ provider.name }}</h3>
                  <span>{{ provider.description }}</span>
                </div>
                <n-tag
                  :type="
                    apiKeyHints[provider.key] !== '未配置'
                      ? 'success'
                      : 'warning'
                  "
                  size="small"
                  :bordered="false"
                  >{{ apiKeyHints[provider.key] }}</n-tag
                >
              </div>
              <n-input
                v-model:value="apiForm[provider.key].baseUrl"
                placeholder="Base URL"
              /><label
                v-if="provider.key === 'llm'"
                class="provider-protocol field-gap"
                >接口协议<select v-model="apiForm.llm.protocol">
                  <option value="auto">自动识别（推荐）</option>
                  <option value="openai">OpenAI Chat Completions</option>
                  <option value="claude">Claude Messages</option></select
                ><span
                  >OpenAI 使用 `/v1/chat/completions`；Claude 使用
                  `/v1/messages`。</span
                ></label
              ><n-input
                v-if="provider.key === 'llm' || provider.key === 'babeldoc'"
                v-model:value="apiForm[provider.key].model"
                class="field-gap"
                placeholder="模型名称"
              /><n-input
                v-if="provider.key === 'zotero'"
                v-model:value="apiForm.zotero.userId"
                class="field-gap"
                placeholder="Zotero User ID"
              /><n-input
                v-if="provider.key === 'research'"
                v-model:value="apiForm.research.maxSearches"
                class="field-gap"
                type="number"
                placeholder="单任务最大检索次数"
              /><n-input
                v-model:value="apiForm[provider.key].apiKey"
                class="field-gap"
                type="password"
                show-password-on="click"
                :placeholder="
                  apiKeyHints[provider.key] === '未配置'
                    ? '输入 Key'
                    : '留空保留当前 Key'
                "
              />
              <div class="provider-actions">
                <button
                  class="small"
                  :disabled="testingProvider === provider.key"
                  @click="testApiConnection(provider.key)"
                >
                  {{
                    testingProvider === provider.key ? "测试中…" : "测试连通性"
                  }}</button
                ><span
                  v-if="testResults[provider.key]"
                  class="test-result"
                  :class="testResults[provider.key].type"
                  >{{ testResults[provider.key].text
                  }}<em v-if="testResults[provider.key].latencyMs">
                    · {{ testResults[provider.key].latencyMs }} ms</em
                  ></span
                >
              </div>
            </div>
          </div>
        </section>
        <section id="presentation-panel" class="admin-panel">
          <div class="panel-title">
            <div>
              <span class="eyebrow">PRESENTATION ENGINE</span>
              <h2>Codex 演示生成</h2>
            </div>
            <button class="primary" @click="saveCodexPptSettings">
              保存 Codex 配置
            </button>
          </div>
          <p class="panel-desc">
            PPTX 与 HTML 统一使用锁定的 Codex CLI 0.147.0。PPTX 按 Skill 生成
            PPTD；HTML 只生成受限 JSON 计划，再由固定 reveal.js
            渲染器输出。未填写 Key 时会自动复用本机已登录 Codex CLI 的 CCSwitch
            活跃 Provider（仅认证与模型中转配置，绝不加载本机
            MCP、规则或插件）。
          </p>
          <div class="provider-card">
            <div class="provider-head">
              <div>
                <h3>OpenAI Codex CLI</h3>
                <span
                  >临时 CODEX_HOME · workspace-write sandbox · PPTD / HTML
                  JSON</span
                >
              </div>
              <n-tag
                :type="codexPptApiHint !== '未配置' ? 'success' : 'warning'"
                size="small"
                :bordered="false"
                >{{ codexPptApiHint }}</n-tag
              >
            </div>
            <div class="form-grid">
              <label
                >模型<select v-model="codexPptForm.model">
                  <option value="gpt-5.6-terra">gpt-5.6-terra（默认）</option>
                  <option value="gpt-5.6-sol">gpt-5.6-sol</option>
                  <option value="gpt-5.6-luna">gpt-5.6-luna</option>
                  <option value="gpt-5.4">gpt-5.4（CCSwitch）</option>
                </select></label
              ><label
                >Reasoning effort<select v-model="codexPptForm.reasoningEffort">
                  <option
                    v-for="effort in [
                      'low',
                      'medium',
                      'high',
                      'xhigh',
                      'max',
                      'ultra',
                    ]"
                    :key="effort"
                    :value="effort"
                  >
                    {{ effort }}
                  </option>
                </select></label
              >
            </div>
            <n-input
              v-model:value="codexPptForm.providerBaseUrl"
              class="field-gap"
              placeholder="CCSwitch Responses Base URL（例如 https://relay.example.com；留空走默认 OpenAI）"
            /><n-input
              v-model:value="codexPptForm.apiKey"
              type="password"
              show-password-on="click"
              :placeholder="
                codexPptLocalCli
                  ? '本机 Codex CLI 已登录；可留空'
                  : codexPptApiHint === '未配置'
                    ? '输入 OpenAI API Key'
                    : '留空保留当前 Key'
              "
            /><small class="field-help"
              >填写 Base URL 后，服务端临时配置会固定为
              <code>OpenAI / responses / requires_openai_auth=true</code
              >，并采用所选模型、审核模型、response storage/network、1M 上下文与
              900k compact 限制；不会加载本机 MCP、规则或插件。</small
            >
            <div class="provider-actions">
              <button
                class="small"
                :disabled="testingProvider === 'codexPpt'"
                @click="testCodexPptConnection"
              >
                {{
                  testingProvider === "codexPpt"
                    ? "测试中…"
                    : "测试 Codex CLI 延迟"
                }}</button
              ><span
                v-if="testResults.codexPpt"
                class="test-result"
                :class="testResults.codexPpt.type"
                >{{ testResults.codexPpt.text
                }}<em v-if="testResults.codexPpt.latencyMs">
                  · {{ testResults.codexPpt.latencyMs }} ms</em
                ></span
              >
            </div>
          </div>
        </section>
        <section id="image-panel" class="admin-panel">
          <div class="panel-title">
            <div>
              <span class="eyebrow">SHARED IMAGE API</span>
              <h2>GPT Image 2 生图</h2>
            </div>
            <button class="primary" @click="saveImageGenerationSettings">
              保存生图配置
            </button>
          </div>
          <p class="panel-desc">
            PPT 视觉素材与独立“GPT
            生图”工作台共享这套服务端配置。密钥不会下发浏览器，也不会读取或转发
            Codex CLI 登录态。
          </p>
          <div class="provider-card">
            <div class="provider-head">
              <div>
                <h3>OpenAI Images API</h3>
                <span>generations + edits · 仅接收 PNG base64 响应</span>
              </div>
              <n-tag
                :type="
                  imageGenerationApiHint !== '未配置' ? 'success' : 'warning'
                "
                size="small"
                :bordered="false"
                >{{ imageGenerationApiHint }}</n-tag
              >
            </div>
            <n-input
              v-model:value="imageGenerationForm.baseUrl"
              placeholder="https://api.openai.com/v1 或完整 /images/generations 地址"
            />
            <div class="form-grid">
              <label
                >模型<n-input
                  v-model:value="imageGenerationForm.model"
                  placeholder="gpt-image-2" /></label
              ><label
                >PPT 默认质量<select v-model="imageGenerationForm.quality">
                  <option value="low">low</option>
                  <option value="medium">medium（默认）</option>
                  <option value="high">high</option>
                </select></label
              ><label
                >PPT 每任务最多图片<n-input-number
                  v-model:value="imageGenerationForm.maxImages"
                  :min="1"
                  :max="10"
              /></label>
            </div>
            <n-input
              v-model:value="imageGenerationForm.apiKey"
              type="password"
              show-password-on="click"
              :placeholder="
                imageGenerationApiHint === '未配置'
                  ? '输入 Images API Key'
                  : '留空保留当前 Key'
              "
            /><small class="field-help"
              >可填写 Base URL 或完整 generations 地址，系统会自动推导 edits
              地址。实际任务只接受 <code>data[0].b64_json</code>。</small
            >
            <div class="provider-actions">
              <button
                class="small"
                :disabled="testingProvider === 'imageGeneration'"
                @click="testImageGenerationConnection"
              >
                {{
                  testingProvider === "imageGeneration"
                    ? "测试中…"
                    : "测试 Images API 延迟"
                }}</button
              ><span
                v-if="testResults.imageGeneration"
                class="test-result"
                :class="testResults.imageGeneration.type"
                >{{ testResults.imageGeneration.text
                }}<em v-if="testResults.imageGeneration.latencyMs">
                  · {{ testResults.imageGeneration.latencyMs }} ms</em
                ></span
              >
            </div>
          </div>
        </section>
        <section id="storage-panel" class="admin-panel">
          <div class="panel-title">
            <div>
              <span class="eyebrow">TASK STORAGE</span>
              <h2>任务文件保留</h2>
            </div>
            <button class="primary" @click="savePptRetention">
              保存保留配置
            </button>
          </div>
          <p class="panel-desc">
            PPT、文献翻译、GPT 生图、演示生图素材和婚恋报告分别按用户及全站总量清理；婚恋报告仍受 30 天期限约束。
          </p>
          <div class="retention-grid">
            <div>
              <h3>PPT 生成</h3>
              <label
                >每用户最多保留（条）<n-input-number
                  v-model:value="pptRetentionForm.maxPerUser"
                  :min="1"
                  :max="100" /></label
              ><label
                >全站最多保留（条）<n-input-number
                  v-model:value="pptRetentionForm.maxTotal"
                  :min="1"
                  :max="1000"
              /></label>
            </div>
            <div>
              <h3>文献翻译</h3>
              <label
                >每用户最多保留（条）<n-input-number
                  v-model:value="translationRetentionForm.maxPerUser"
                  :min="1"
                  :max="100" /></label
              ><label
                >全站最多保留（条）<n-input-number
                  v-model:value="translationRetentionForm.maxTotal"
                  :min="1"
                  :max="1000"
              /></label>
            </div>
            <div>
              <h3>GPT 生图</h3>
              <label
                >每用户最多保留（条）<n-input-number
                  v-model:value="imageRetentionForm.maxPerUser"
                  :min="1"
                  :max="100" /></label
              ><label
                >全站最多保留（条）<n-input-number
                  v-model:value="imageRetentionForm.maxTotal"
                  :min="1"
                  :max="1000"
              /></label>
            </div>
            <div>
              <h3>演示生图素材</h3>
              <label
                >每账号最多保留（张）<n-input-number
                  v-model:value="presentationImageRetentionForm.maxPerUser"
                  :min="1"
                  :max="100" /></label
              ><label
                >全站最多保留（张）<n-input-number
                  v-model:value="presentationImageRetentionForm.maxTotal"
                  :min="1"
                  :max="1000"
              /></label>
            </div>
            <div>
              <h3>婚恋报告</h3>
              <label
                >每用户最多保留（份）<n-input-number
                  v-model:value="matchmakingRetentionForm.maxPerUser"
                  :min="1"
                  :max="100" /></label
              ><label
                >全站最多保留（份）<n-input-number
                  v-model:value="matchmakingRetentionForm.maxTotal"
                  :min="1"
                  :max="1000"
              /></label>
            </div>
          </div>
        </section>
        <section id="ranking-panel" class="admin-panel">
          <div class="panel-title">
            <div>
              <span class="eyebrow">GITHUB RADAR</span>
              <h2>全站周榜/月榜任务</h2>
            </div>
            <div class="panel-actions">
              <button
                class="small"
                :disabled="rankingRefreshLoading"
                @click="refreshGithubRanking"
              >
                {{ rankingRefreshLoading ? "提交中…" : "手动刷新榜单" }}</button
              ><button class="primary" @click="saveGithubRankingSettings">
                保存榜单配置
              </button>
            </div>
          </div>
          <p class="panel-desc">
            默认每 24 小时抓取一次全 GitHub
            新建公开仓库榜单；后台任务每小时检查一次，但不会突破这里设置的间隔。手动刷新会在后台执行，并受冷却时间限制。
          </p>
          <div class="form-grid">
            <label
              >自动抓取<select v-model="rankingForm.enabled">
                <option :value="true">启用</option>
                <option :value="false">停用</option>
              </select></label
            ><label
              >自动刷新间隔（小时）<n-input-number
                v-model:value="rankingForm.refreshIntervalHours"
                :min="6"
                :max="168" /></label
            ><label
              >手动刷新冷却（分钟）<n-input-number
                v-model:value="rankingForm.manualCooldownMinutes"
                :min="15"
                :max="1440" /></label
            ><label
              >周榜项目数<n-input-number
                v-model:value="rankingForm.weeklyLimit"
                :min="1"
                :max="20" /></label
            ><label
              >月榜项目数<n-input-number
                v-model:value="rankingForm.monthlyLimit"
                :min="1"
                :max="20" /></label
            ><label
              >AI 项目总结<select v-model="rankingForm.aiSummaryEnabled">
                <option :value="true">启用 Mimo 总结</option>
                <option :value="false">只显示 GitHub 描述</option>
              </select></label
            >
          </div>
        </section>
        <div class="content-grid billing-grid">
          <section id="billing-panel" class="admin-panel">
            <div class="panel-title">
              <div>
                <span class="eyebrow">BILLING RULES</span>
                <h2>额度、计费与签到</h2>
              </div>
              <button class="primary" @click="saveSettings">保存</button>
            </div>
            <div class="form-grid">
              <label
                >PDF 翻译 / 页<n-input-number
                  v-model:value="settings.translationCreditPerPage"
                  :min="1" /></label
              ><label
                >PPT 生成 / 次<n-input-number
                  v-model:value="settings.pptCreditPerTask"
                  :min="1" /></label
              ><label
                >生图 low / 张<n-input-number
                  v-model:value="settings.imageLowCredits"
                  :min="1" /></label
              ><label
                >生图 medium / 张<n-input-number
                  v-model:value="settings.imageMediumCredits"
                  :min="1" /></label
              ><label
                >生图 high / 张<n-input-number
                  v-model:value="settings.imageHighCredits"
                  :min="1" /></label
              ><label
                >每日签到<select v-model="settings.dailyCheckinEnabled">
                  <option :value="true">开启</option>
                  <option :value="false">关闭</option>
                </select></label
              ><label
                >签到最低积分<n-input-number
                  v-model:value="settings.dailyCheckinMinCredits"
                  :min="1"
                  :max="1000"
                  :disabled="!settings.dailyCheckinEnabled" /></label
              ><label
                >签到最高积分<n-input-number
                  v-model:value="settings.dailyCheckinMaxCredits"
                  :min="1"
                  :max="1000"
                  :disabled="!settings.dailyCheckinEnabled"
              /></label>
            </div>
          </section>
          <section id="invite-create-panel" class="admin-panel">
            <div class="panel-title">
              <div>
                <span class="eyebrow">ACCESS CONTROL</span>
                <h2>生成邀请码</h2>
              </div>
            </div>
            <div class="form-grid">
              <label
                >自定义 code<n-input
                  v-model:value="inviteForm.code"
                  placeholder="留空自动生成" /></label
              ><label
                >赠送 credits<n-input-number
                  v-model:value="inviteForm.credits"
                  :min="0" /></label
              ><label
                >最大使用次数<n-input-number
                  v-model:value="inviteForm.maxUses"
                  :min="1" /></label
              ><label
                >过期时间<n-input
                  v-model:value="inviteForm.expiresAt"
                  type="datetime-local"
              /></label>
            </div>
            <button class="primary wide" @click="createInvite">
              生成并复制邀请码
            </button>
          </section>
        </div>
        <section id="invites-panel" class="admin-panel">
          <div class="panel-title">
            <div>
              <span class="eyebrow">INVITATION MANAGEMENT</span>
              <h2>邀请码管理</h2>
            </div>
            <span class="muted"
              >{{ invites.length }} 条记录 · 可上下拖动查看</span
            >
          </div>
          <div
            class="table-wrap audit-table-wrap"
            tabindex="0"
            role="region"
            aria-label="邀请码管理，可上下滚动"
          >
            <table>
              <thead>
                <tr>
                  <th>邀请码</th>
                  <th>额度</th>
                  <th>使用情况</th>
                  <th>状态</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="invite in invites" :key="invite.id">
                  <td>
                    <code>{{ invite.code }}</code>
                  </td>
                  <td>{{ invite.credits }}</td>
                  <td>{{ invite.used_count }} / {{ invite.max_uses }}</td>
                  <td>{{ inviteStatus(invite) }}</td>
                  <td>
                    <button class="small" @click="toggleInvite(invite)">
                      {{ invite.enabled ? "撤销" : "恢复" }}</button
                    ><button
                      v-if="invite.used_count === 0"
                      class="small danger"
                      @click="deleteInvite(invite)"
                    >
                      删除
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
        <section id="matchmaking-trial-panel" class="admin-panel">
          <div class="panel-title">
            <div><span class="eyebrow">MATCHMAKING PRIVATE BETA</span><h2>婚恋内测邀请码</h2></div>
            <span class="muted">{{ trialCodes.length }} 枚 · root 可随时查看使用情况与完整码</span>
          </div>
          <p class="panel-desc">访客无需注册即可免费生成一份报告；失败可重试，完成后只能恢复查看。默认首次兑换期限为 7 天。</p>
          <div class="trial-code-create">
            <label>首次兑换截止时间<n-input v-model:value="trialExpiresAt" type="datetime-local" /></label>
            <button class="primary" @click="createTrialCode">生成并复制内测码</button>
          </div>
          <div class="table-wrap audit-table-wrap" tabindex="0" role="region" aria-label="婚恋内测邀请码管理，可上下滚动">
            <table>
              <thead><tr><th>邀请码</th><th>状态</th><th>首次截止</th><th>兑换时间</th><th>完成时间</th><th>报告</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-for="code in trialCodes" :key="code.id">
                  <td class="trial-code-cell">
                    <code v-if="code.codeRecoverable">{{ code.code }}</code>
                    <span v-else class="muted">历史码不可恢复（****{{ code.codeSuffix }}）</span>
                    <button v-if="code.codeRecoverable" class="small" @click="copyTrialCode(code.code)">复制</button>
                  </td>
                  <td>{{ trialCodeStatus(code) }}</td>
                  <td>{{ formatDate(code.expiresAt) }}</td>
                  <td>{{ formatDate(code.redeemedAt) }}</td>
                  <td>{{ formatDate(code.completedAt) }}</td>
                  <td><a v-if="code.reportId" :href="`/matchmaking-report/${code.reportId}`">查看</a><span v-else>—</span></td>
                  <td><button class="small" @click="toggleTrialCode(code)">{{ code.enabled ? "撤销" : "恢复" }}</button></td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
        <div class="content-grid account-grid">
          <section id="users-panel" class="admin-panel">
            <div class="panel-title">
              <div>
                <span class="eyebrow">USER ACCESS</span>
                <h2>用户与额度</h2>
              </div>
              <span class="muted">{{ users.length }} 个注册用户 · 可上下拖动查看</span>
            </div>
            <div
              class="table-wrap audit-table-wrap user-table-wrap"
              tabindex="0"
              role="region"
              aria-label="注册用户与额度，可上下滚动"
            >
              <table>
                <thead>
                  <tr>
                    <th>用户</th>
                    <th>角色</th>
                    <th>余额</th>
                    <th>状态</th>
                    <th>调整</th>
                    <th>操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="user in users" :key="user.id">
                    <td>{{ user.username }}</td>
                    <td>{{ user.role }}</td>
                    <td>{{ user.credits }}</td>
                    <td>{{ user.enabled ? "正常" : "已停用" }}</td>
                    <td>
                      <n-input-number
                        v-model:value="adjustForms[user.id]"
                        size="small"
                      />
                    </td>
                    <td>
                      <button class="small" @click="adjustCredits(user.id)">
                        应用</button
                      ><button
                        v-if="user.role !== 'ROOT'"
                        class="small danger"
                        @click="toggleUser(user)"
                      >
                        {{ user.enabled ? "停用" : "启用" }}
                      </button>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>
          <section id="transactions-panel" class="admin-panel">
            <div class="panel-title">
              <div>
                <span class="eyebrow">AUDIT TRAIL</span>
                <h2>最近额度流水</h2>
              </div>
              <span class="muted"
                >{{ transactions.length }} 条记录 · 可上下拖动查看</span
              >
            </div>
            <div
              class="table-wrap audit-table-wrap"
              tabindex="0"
              role="region"
              aria-label="最近额度流水，可上下滚动"
            >
              <table>
                <thead>
                  <tr>
                    <th>用户</th>
                    <th>变动</th>
                    <th>余额</th>
                    <th>类型</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="tx in transactions" :key="tx.id">
                    <td>{{ tx.username }}</td>
                    <td>{{ tx.amount }}</td>
                    <td>{{ tx.balance_after }}</td>
                    <td>{{ tx.kind }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>
        </div>
      <section id="matchmaking-panel" class="admin-panel"><div class="panel-title"><div><span class="eyebrow">MATCHMAKING REPORT PROVIDER</span><h2>婚恋报告 / Mimo API</h2></div><button class="primary" @click="saveMatchmakingSettings">保存婚恋报告配置</button></div><p class="panel-desc">默认继承「LLM 通用模型」的 Mimo 配置。填写并保存后，只会覆盖婚恋报告节目；密钥不会返回浏览器。</p><div class="provider-card"><div class="provider-head"><div><h3>专属文本模型</h3><span>不联网检索，仅整理已校验的匿名化条件账本。</span></div><n-tag :type="matchmakingApiHint !== '未配置' ? 'success' : 'warning'" size="small" :bordered="false">{{ matchmakingApiHint }}</n-tag></div><n-input v-model:value="matchmakingForm.baseUrl" placeholder="Base URL" /><label class="provider-protocol field-gap">接口协议<select v-model="matchmakingForm.protocol"><option value="auto">自动识别（推荐）</option><option value="openai">OpenAI Chat Completions</option><option value="claude">Claude Messages</option></select></label><n-input v-model:value="matchmakingForm.model" class="field-gap" placeholder="模型名称" /><n-input v-model:value="matchmakingForm.apiKey" class="field-gap" type="password" show-password-on="click" :placeholder="matchmakingApiHint === '未配置' ? '输入 Key' : '留空保留当前 Key'" /><div class="provider-actions"><button class="small" :disabled="testingProvider === 'matchmaking'" @click="testMatchmakingConnection">{{ testingProvider === 'matchmaking' ? '测试中…' : '测试连通性' }}</button><span v-if="testResults.matchmaking" class="test-result" :class="testResults.matchmaking.type">{{ testResults.matchmaking.text }}<em v-if="testResults.matchmaking.latencyMs"> · {{ testResults.matchmaking.latencyMs }} ms</em></span></div></div></section>
      <section id="matchmaking-billing-panel" class="admin-panel"><div class="panel-title"><div><span class="eyebrow">MATCHMAKING BILLING</span><h2>婚恋报告积分</h2></div><button class="primary" @click="saveSettings">保存计费规则</button></div><p class="panel-desc">每份专属报告在模型成功生成后扣除一次积分；调用、结构校验或保存失败会自动退款。</p><div class="form-grid"><label>婚恋报告 / 份<n-input-number v-model:value="settings.matchmakingCreditPerReport" :min="1" /></label></div></section>
          </div>
        </div>
      </template>
      <n-modal v-model:show="trialCodeModalOpen" preset="dialog" title="婚恋内测邀请码已生成" :mask-closable="false">
        <p>邀请码已保存，可随时在本页查看使用情况、完整内容并再次复制。</p>
        <code class="trial-code-once">{{ createdTrialCode }}</code>
        <template #action><button class="primary" @click="copyCreatedTrialCode">复制邀请码</button><button class="small" @click="trialCodeModalOpen = false">我已保存</button></template>
      </n-modal>
    </div>
  </main>
</template>
<script setup>
import { useAccountManagement } from "./composables/useAccountManagement.js";
import { useProviderSettings } from "./composables/useProviderSettings.js";
import OperationsPanel from "./components/OperationsPanel.vue";
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useMessage } from "naive-ui";
import { deleteAdminGuestbookEntry, getAdminAccounts, getAdminGuestbookEntries, requestGithubRankingRefresh, testAdminApiSettings, updateAdminApiSettings, updateQuotaSettings } from "@/api";

import { useAuthStore } from "@/stores/auth";
const visibilityForm = reactive({
  Publications: "PUBLIC",
  Translate: "USER",
  Contact: "USER",
  ImageGenerate: "USER",
  Matchmaking: "USER",
  News: "PUBLIC",
  Guestbook: "PUBLIC",
});
const visibilityItems = [
  { key: "Publications", label: "文献库" },
  { key: "Translate", label: "论文翻译" },
  { key: "Contact", label: "PPT 生成" },
  { key: "ImageGenerate", label: "GPT 生图" },
  { key: "Matchmaking", label: "婚恋条件报告" },
  { key: "News", label: "GitHub 项目" },
  { key: "Guestbook", label: "留言板" },
];
const auth = useAuthStore();
const message = useMessage();
const errorMsg = ref("");

const rankingRefreshLoading = ref(false);

const guestbookEntries = ref([]);
const guestbookType = ref("all");
const guestbookPage = ref(1);
const guestbookTotalPages = ref(1);
const stats = reactive({
  users: 0,
  activeUsers: 0,
  activeInvites: 0,
  creditsIssued: 0,
  creditsSpent: 0,
});

const settings = reactive({
  translationCreditPerPage: 1,
  pptCreditPerTask: 10,
  matchmakingCreditPerReport: 2,
  imageLowCredits: 2,
  imageMediumCredits: 4,
  imageHighCredits: 8,
  dailyCheckinEnabled: true,
  dailyCheckinCredits: 2,
});


const matchmakingForm = reactive({
  baseUrl: "",
  model: "",
  apiKey: "",
  protocol: "auto",
});
const matchmakingApiHint = ref("未配置");
const pptRetentionForm = reactive({ maxPerUser: 5, maxTotal: 20 });
const translationRetentionForm = reactive({ maxPerUser: 5, maxTotal: 20 });
const imageRetentionForm = reactive({ maxPerUser: 5, maxTotal: 20 });
const presentationImageRetentionForm = reactive({
  maxPerUser: 20,
  maxTotal: 100,
});
const matchmakingRetentionForm = reactive({ maxPerUser: 20, maxTotal: 200 });
const rankingForm = reactive({
  enabled: true,
  refreshIntervalHours: 24,
  manualCooldownMinutes: 30,
  weeklyLimit: 10,
  monthlyLimit: 10,
  aiSummaryEnabled: true,
});

const {
  users, invites, trialCodes, trialExpiresAt, createdTrialCode, trialCodeModalOpen,
  transactions, adjustForms, inviteForm, createInvite, createTrialCode,
  copyCreatedTrialCode, copyTrialCode, toggleTrialCode, trialCodeStatus,
  defaultTrialExpiry, toggleInvite, deleteInvite, toggleUser, adjustCredits, inviteStatus,
} = useAccountManagement({ message, errorMsg, loadDashboard });

const statCards = computed(() => [
  {
    label: "用户总数",
    value: formatAdminNumber(stats.users),
    hint: `${formatAdminNumber(stats.activeUsers)} 个账户正常`,
  },
  {
    label: "可用邀请码",
    value: formatAdminNumber(stats.activeInvites),
    hint: "未撤销、未用尽且未过期",
  },
  {
    label: "已发放额度",
    value: formatAdminNumber(stats.creditsIssued),
    hint: "邀请码和管理员调整",
  },
  {
    label: "已消耗额度",
    value: formatAdminNumber(stats.creditsSpent),
    hint: "翻译、PPT 与生图任务",
  },
]);

const {
  testingProvider, testResults, apiKeyHints, apiForm, providerCards,
  saveApiSettings, testApiConnection, applyProviderSettings,
} = useProviderSettings({ message, errorMsg, rankingForm, applyApiSettings });

function formatAdminNumber(value) {
  return new Intl.NumberFormat("zh-CN").format(Number(value || 0));
}

onMounted(async () => {
  await auth.refresh().catch(() => {});
  if (auth.isRoot) {
    await loadDashboard();
    await loadGuestbook(1);
  }
});
async function loadDashboard() {
  try {
    const res = await getAdminAccounts();
    const d = res.data || {};
    users.value = d.users || [];
    invites.value = d.invites || [];
    trialCodes.value = d.matchmakingTrialCodes || [];
    transactions.value = d.transactions || [];
    Object.assign(settings, d.settings || {});
    Object.assign(stats, d.stats || {});
    applyApiSettings(d.apiSettings);
  } catch (e) {
    errorMsg.value = e.message || "后台数据加载失败";
  }
}
function applyApiSettings(d = {}) {
  applyProviderSettings(d);
  applyMatchmakingSettings(d.matchmaking);
  if (d.githubRanking) Object.assign(rankingForm, d.githubRanking);
  if (d.pptRetention) Object.assign(pptRetentionForm, d.pptRetention);
  if (d.translationRetention)
    Object.assign(translationRetentionForm, d.translationRetention);
  if (d.imageRetention) Object.assign(imageRetentionForm, d.imageRetention);
  if (d.presentationImageRetention)
    Object.assign(presentationImageRetentionForm, d.presentationImageRetention);
  if (d.matchmakingRetention)
    Object.assign(matchmakingRetentionForm, d.matchmakingRetention);
  applyCodexPptSettings(d.codexPpt);
  applyImageGenerationSettings(d.imageGeneration);
}
function applyMatchmakingSettings(setting) {
  if (!setting) return;
  matchmakingForm.baseUrl = setting.baseUrl || "";
  matchmakingForm.model = setting.model || "";
  matchmakingForm.protocol = String(setting.protocol || "auto").toLowerCase();
  matchmakingForm.apiKey = "";
  matchmakingApiHint.value = setting.apiKeyHint || "未配置";
}
async function saveMatchmakingSettings() {
  try {
    const result = await updateAdminApiSettings({ matchmaking: matchmakingForm });
    applyMatchmakingSettings(result.data?.matchmaking);
    message.success("婚恋报告 API 配置已保存");
  } catch (e) {
    errorMsg.value = e.message || "婚恋报告 API 配置保存失败";
  }
}
async function testMatchmakingConnection() {
  testingProvider.value = "matchmaking";
  testResults.matchmaking = { type: "info", text: "正在测试…" };
  try {
    const result =
      (await testAdminApiSettings("matchmaking", matchmakingForm)).data || {};
    testResults.matchmaking = { type: "success", text: result.message || "连接成功", latencyMs: result.latencyMs };
  } catch (e) {
    testResults.matchmaking = { type: "error", text: e.message || "连接失败" };
  } finally {
    testingProvider.value = "";
  }
}

async function saveSettings() {
  try {
    Object.assign(settings, (await updateQuotaSettings(settings)).data || {});
    message.success("计费规则已保存");
  } catch (e) {
    errorMsg.value = e.message || "保存失败";
  }
}

function formatDate(value) {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "—" : new Intl.DateTimeFormat("zh-CN", { dateStyle: "short", timeStyle: "short" }).format(date);
}

async function loadGuestbook(nextPage = 1) {
  try {
    const data =
      (await getAdminGuestbookEntries(guestbookType.value, nextPage)).data ||
      {};
    guestbookEntries.value = data.items || [];
    guestbookPage.value = Number(data.page || nextPage);
    guestbookTotalPages.value = Number(data.totalPages || 1);
  } catch (e) {
    errorMsg.value = e.message || "留言加载失败";
  }
}
async function deleteGuestbook(entry) {
  if (
    !window.confirm(
      entry.parentId
        ? "确定删除这条回复吗？"
        : "确定删除这条留言及其全部回复、点赞和通知吗？此操作不可恢复。",
    )
  )
    return;
  try {
    await deleteAdminGuestbookEntry(entry.id);
    message.success("留言已删除");
    await loadGuestbook(
      guestbookEntries.value.length === 1
        ? Math.max(1, guestbookPage.value - 1)
        : guestbookPage.value,
    );
  } catch (e) {
    errorMsg.value = e.message || "留言删除失败";
  }
}
function openGuestbookEntry(entry) {
  window.open(
    `/guestbook?entry=${encodeURIComponent(entry.id)}`,
    "_blank",
    "noopener",
  );
}
function formatGuestbookDate(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? "—"
    : new Intl.DateTimeFormat("zh-CN", {
        dateStyle: "short",
        timeStyle: "short",
      }).format(date);
}

async function refreshGithubRanking() {
  rankingRefreshLoading.value = true;
  try {
    const res = await requestGithubRankingRefresh();
    const data = res.data || {};
    message.info(data.message || res.message || "排行榜刷新请求已提交");
  } catch (e) {
    errorMsg.value = e.message || "排行榜刷新请求失败";
  } finally {
    rankingRefreshLoading.value = false;
  }
}
watch(
  () => auth.visibility,
  (value) => {
    Object.assign(visibilityForm, value || {});
  },
  { deep: true },
);
async function saveVisibility() {
  try {
    await updateAdminApiSettings({ visibility: visibilityForm });
    auth.visibility = { ...auth.visibility, ...visibilityForm };
    message.success("节目可见范围已保存");
  } catch (e) {
    errorMsg.value = e.message || "可见范围保存失败";
  }
}
async function saveGithubRankingSettings() {
  try {
    applyApiSettings(
      (await updateAdminApiSettings({ githubRanking: rankingForm })).data,
    );
    message.success("排行榜配置已保存");
  } catch (e) {
    errorMsg.value = e.message || "排行榜配置保存失败";
  }
}
async function savePptRetention() {
  try {
    const result = await updateAdminApiSettings({
      pptRetention: pptRetentionForm,
      translationRetention: translationRetentionForm,
      imageRetention: imageRetentionForm,
      presentationImageRetention: presentationImageRetentionForm,
      matchmakingRetention: matchmakingRetentionForm,
    });
    if (result.data?.pptRetention)
      Object.assign(pptRetentionForm, result.data.pptRetention);
    if (result.data?.translationRetention)
      Object.assign(translationRetentionForm, result.data.translationRetention);
    if (result.data?.imageRetention)
      Object.assign(imageRetentionForm, result.data.imageRetention);
    if (result.data?.presentationImageRetention)
      Object.assign(
        presentationImageRetentionForm,
        result.data.presentationImageRetention,
      );
    if (result.data?.matchmakingRetention)
      Object.assign(matchmakingRetentionForm, result.data.matchmakingRetention);
    message.success("任务文件保留配置已保存");
  } catch (e) {
    errorMsg.value = e.message || "任务文件保留配置保存失败";
  }
}
const codexPptForm = reactive({
  model: "gpt-5.6-terra",
  reasoningEffort: "high",
  providerBaseUrl: "",
  apiKey: "",
});
const codexPptApiHint = ref("未配置");
const codexPptLocalCli = ref(false);
function applyCodexPptSettings(setting) {
  if (!setting) return;
  codexPptForm.model = setting.model || "gpt-5.6-terra";
  codexPptForm.reasoningEffort = setting.reasoningEffort || "high";
  codexPptForm.providerBaseUrl = setting.providerBaseUrl || "";
  codexPptForm.apiKey = "";
  codexPptLocalCli.value = Boolean(setting.localCli?.available);
  codexPptApiHint.value = codexPptLocalCli.value
    ? "本机 CLI 已登录"
    : setting.apiKeyHint || "未配置";
}
async function testCodexPptConnection() {
  testingProvider.value = "codexPpt";
  testResults.codexPpt = { type: "info", text: "正在启动临时 CLI…" };
  try {
    const result =
      (await testAdminApiSettings("codexPpt", codexPptForm)).data || {};
    testResults.codexPpt = {
      type: "success",
      text: result.message || "连接成功",
      latencyMs: result.latencyMs,
    };
  } catch (e) {
    testResults.codexPpt = { type: "error", text: e.message || "连接失败" };
  } finally {
    testingProvider.value = "";
  }
}
async function saveCodexPptSettings() {
  try {
    const result = await updateAdminApiSettings({ codexPpt: codexPptForm });
    applyCodexPptSettings(result.data?.codexPpt);
    message.success("Codex 演示配置已保存");
  } catch (e) {
    errorMsg.value = e.message || "Codex 演示配置保存失败";
  }
}
const imageGenerationForm = reactive({
  baseUrl: "https://api.openai.com/v1",
  model: "gpt-image-2",
  quality: "medium",
  maxImages: 3,
  apiKey: "",
});
const imageGenerationApiHint = ref("未配置");
function applyImageGenerationSettings(setting) {
  if (!setting) return;
  imageGenerationForm.baseUrl = setting.baseUrl || imageGenerationForm.baseUrl;
  imageGenerationForm.model = setting.model || "gpt-image-2";
  imageGenerationForm.quality = ["low", "medium", "high"].includes(
    setting.quality,
  )
    ? setting.quality
    : "medium";
  imageGenerationForm.maxImages = Math.min(
    10,
    Math.max(1, Number(setting.maxImages || 3)),
  );
  imageGenerationForm.apiKey = "";
  imageGenerationApiHint.value = setting.apiKeyHint || "未配置";
}
async function testImageGenerationConnection() {
  testingProvider.value = "imageGeneration";
  testResults.imageGeneration = { type: "info", text: "正在测试…" };
  try {
    const result =
      (await testAdminApiSettings("imageGeneration", imageGenerationForm))
        .data || {};
    testResults.imageGeneration = {
      type: "success",
      text: result.message || "连接成功",
      latencyMs: result.latencyMs,
    };
  } catch (e) {
    testResults.imageGeneration = {
      type: "error",
      text: e.message || "连接失败",
    };
  } finally {
    testingProvider.value = "";
  }
}
async function saveImageGenerationSettings() {
  try {
    const result = await updateAdminApiSettings({
      imageGeneration: imageGenerationForm,
    });
    applyImageGenerationSettings(result.data?.imageGeneration);
    message.success("GPT Image 2 配置已保存");
  } catch (e) {
    errorMsg.value = e.message || "GPT Image 2 配置保存失败";
  }
}
</script>
<style scoped lang="scss">
.admin-page {
  min-height: 100vh;
  padding: 40px 20px 72px;
  background: #f3f5f7;
  color: #17212b;
}
.admin-shell {
  max-width: 1280px;
  margin: auto;
  display: grid;
  gap: 18px;
}
.admin-header,
.admin-panel,
.stat-card {
  background: #fff;
  border: 1px solid #e1e7ec;
  border-radius: 14px;
  box-shadow: 0 6px 22px #1626330b;
}
.admin-header {
  padding: 28px 30px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
}
.admin-header h1 {
  margin: 5px 0 8px;
  font-size: 30px;
}
.admin-header p,
.panel-desc,
.muted {
  color: #72808c;
}
.eyebrow {
  color: #7b8b99;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.13em;
}
.admin-panel {
  padding: 22px;
  min-width: 0;
}
.panel-title,
.provider-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
}
.panel-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.panel-title h2 {
  margin: 3px 0 0;
  font-size: 18px;
}
.panel-desc {
  margin: 10px 0 18px;
  font-size: 13px;
  line-height: 1.65;
}
.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
}
.stat-card {
  padding: 17px 19px;
  min-width: 0;
}
.stat-card span,
.stat-card small {
  display: block;
  color: #71808c;
  font-size: 13px;
  overflow-wrap: anywhere;
}
.stat-card strong {
  display: block;
  margin: 6px 0 3px;
  font-size: 27px;
}
.provider-grid,
.content-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}
.content-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}
.provider-card {
  padding: 16px;
  border: 1px solid #e5eaee;
  border-radius: 11px;
  background: #fbfcfd;
  min-width: 0;
}
.provider-head {
  align-items: flex-start;
  margin-bottom: 14px;
}
.provider-head h3 {
  margin: 0 0 4px;
  font-size: 15px;
}
.provider-head span {
  color: #7b8791;
  font-size: 12px;
  line-height: 1.55;
}
.field-gap {
  margin-top: 9px;
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 13px;
  margin: 14px 0 16px;
}
.form-grid label {
  display: grid;
  gap: 6px;
  color: #53616c;
  font-size: 12px;
  font-weight: 600;
  min-width: 0;
}
.table-wrap {
  overflow-x: auto;
  -webkit-overflow-scrolling: touch;
  border: 1px solid #edf0f2;
  border-radius: 8px;
}
table {
  width: 100%;
  min-width: 560px;
  border-collapse: collapse;
  font-size: 13px;
}
th,
td {
  padding: 12px 10px;
  border-bottom: 1px solid #edf0f2;
  text-align: left;
  white-space: nowrap;
}
th {
  color: #7a8791;
  font-size: 11px;
}
code {
  padding: 4px 7px;
  border-radius: 5px;
  color: #315e78;
  background: #edf5f8;
}
.primary,
.ghost,
.small {
  border: 0;
  border-radius: 7px;
  padding: 9px 14px;
  cursor: pointer;
  min-height: 38px;
}
.primary {
  color: #fff;
  background: #b83126;
}
.ghost {
  background: #edf1f4;
  color: #3a4a55;
}
.small {
  padding: 5px 9px;
  background: #edf1f4;
  margin-right: 5px;
}
.small:disabled {
  cursor: wait;
  opacity: 0.6;
}
.danger {
  color: #a12d2d;
}
.wide {
  width: 100%;
}
@media (max-width: 900px) {
  .stat-grid,
  .provider-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .content-grid {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 600px) {
  .admin-page {
    padding: 16px 10px 48px;
  }
  .admin-shell {
    gap: 12px;
  }
  .admin-header {
    align-items: stretch;
    flex-direction: column;
    padding: 20px 18px;
  }
  .admin-header h1 {
    font-size: 25px;
    line-height: 1.25;
  }
  .admin-header .ghost {
    width: 100%;
  }
  .panel-title {
    align-items: stretch;
    flex-direction: column;
  }
  .panel-title > .primary {
    width: 100%;
  }
  .panel-actions {
    display: grid;
    grid-template-columns: 1fr;
  }
  .panel-actions button {
    width: 100%;
    margin: 0;
  }
  .muted {
    font-size: 12px;
  }
  .stat-grid,
  .provider-grid,
  .form-grid {
    grid-template-columns: 1fr;
  }
  .admin-panel {
    padding: 16px;
  }
  .provider-card {
    padding: 14px;
  }
  .provider-head {
    gap: 10px;
  }
  .provider-head .n-tag {
    flex-shrink: 0;
  }
  .primary,
  .ghost {
    min-height: 44px;
  }
  .small {
    min-height: 38px;
  }
  .table-wrap {
    margin: 0 -2px;
  }
  .table-wrap::after {
    content: "左右滑动查看完整数据";
    display: block;
    padding: 7px 10px;
    color: #7b8791;
    font-size: 11px;
    background: #fafbfc;
  }
  .visibility-grid {
    grid-template-columns: 1fr;
  }
  .retention-grid {
    grid-template-columns: 1fr;
  }
  .provider-actions {
    align-items: stretch;
    flex-direction: column;
  }
  .provider-actions .small {
    width: 100%;
    margin: 0;
  }
  .test-result {
    overflow-wrap: anywhere;
  }
  .form-grid :deep(.n-input-number) {
    width: 100%;
  }
}
.visibility-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  margin-top: 14px;
}
.visibility-grid label {
  display: grid;
  gap: 6px;
  color: #53616c;
  font-size: 12px;
  font-weight: 600;
}
.visibility-grid select {
  padding: 9px;
  border: 1px solid #d9e1e6;
  border-radius: 6px;
  background: #fff;
  color: #34434d;
}
.provider-protocol {
  display: grid;
  gap: 6px;
  color: #53616c;
  font-size: 12px;
  font-weight: 600;
}
.provider-protocol select {
  padding: 9px;
  border: 1px solid #d9e1e6;
  border-radius: 6px;
  background: #fff;
  color: #34434d;
}
.provider-protocol span {
  color: #87939c;
  font-size: 11px;
  font-weight: 400;
}
.retention-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}
.retention-grid > div {
  display: grid;
  gap: 12px;
  padding: 15px;
  border: 1px solid #e5eaee;
  border-radius: 11px;
  background: #fbfcfd;
}
.retention-grid h3 {
  margin: 0;
  font-size: 15px;
}
.retention-grid label {
  display: grid;
  gap: 6px;
  color: #53616c;
  font-size: 12px;
  font-weight: 600;
}
.provider-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 28px;
  margin-top: 10px;
}
.provider-actions .small {
  margin-right: 0;
}
.small:disabled {
  cursor: wait;
  opacity: 0.6;
}
.test-result {
  font-size: 12px;
}
.test-result.success {
  color: #328464;
}
.test-result.error {
  color: #b34a4a;
}
.test-result.info {
  color: #72808c;
}
.test-result em {
  font-style: normal;
  color: #87939c;
}
.audit-table-wrap {
  max-height: 360px;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
  scrollbar-color: #98a8b5 #edf1f4;
}
.audit-table-wrap:focus-visible {
  outline: 3px solid #b9d1e3;
  outline-offset: 2px;
}
.audit-table-wrap thead th {
  position: sticky;
  top: 0;
  z-index: 1;
  background: #fbfcfd;
  box-shadow: 0 1px 0 #dfe6eb;
}
.audit-table-wrap::-webkit-scrollbar {
  width: 12px;
}
.audit-table-wrap::-webkit-scrollbar-track {
  background: #edf1f4;
  border-left: 1px solid #e0e6ea;
}
.audit-table-wrap::-webkit-scrollbar-thumb {
  background: #98a8b5;
  border: 3px solid #edf1f4;
  border-radius: 999px;
}
.audit-table-wrap::-webkit-scrollbar-thumb:hover {
  background: #718391;
}
@media (max-width: 600px) {
  .retention-grid {
    grid-template-columns: 1fr;
  }
  .audit-table-wrap {
    max-height: 320px;
  }
}
.retention-grid {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}
@media (max-width: 900px) {
  .retention-grid {
    grid-template-columns: 1fr;
  }
}
.guestbook-content {
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
}
.pagination-controls {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 14px;
  font-size: 13px;
}
@media (max-width: 600px) {
  .guestbook-content {
    max-width: 180px;
  }
  .pagination-controls {
    justify-content: center;
  }
  .pagination-controls .small {
    min-height: 44px;
  }
}
/* Desktop control-center pass: keep the existing controls, but give them a clear
   reading order and a stable navigation rail. */
.admin-page {
  min-height: calc(100vh - 80px);
  padding: 30px clamp(18px, 3vw, 48px) 80px;
  background:
    linear-gradient(180deg, #f6f7f8 0%, #f1f3f4 100%);
  color: #1f2b33;
}

.admin-shell {
  max-width: 1480px;
  gap: 22px;
}

.admin-shell > *,
.admin-workspace,
.admin-content,
.admin-index,
.admin-panel {
  min-width: 0;
}

.admin-header,
.admin-overview,
.admin-panel,
.stat-card,
.admin-index {
  border-color: #dfe5e9;
}

.admin-header {
  position: relative;
  overflow: hidden;
  min-width: 0;
  padding: 30px 34px;
  border-radius: 18px;
  box-shadow: 0 12px 32px rgba(31, 43, 51, 0.06);
}

.admin-header::before {
  position: absolute;
  inset: 0 auto 0 0;
  width: 5px;
  background: #b83126;
  content: "";
}

.admin-header__copy,
.admin-header__actions {
  position: relative;
  min-width: 0;
  z-index: 1;
}

.admin-header__eyebrow,
.admin-header__actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.admin-header__eyebrow .eyebrow {
  color: #6c7e89;
}

.admin-live,
.admin-role {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #587365;
  font-size: 12px;
  white-space: nowrap;
}

.admin-live i,
.admin-index__note-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #5c9a73;
  box-shadow: 0 0 0 4px rgba(92, 154, 115, 0.12);
}

.admin-role {
  padding: 8px 11px;
  border: 1px solid #e3e8eb;
  border-radius: 999px;
  color: #63737d;
  background: #f7f9fa;
  font-size: 11px;
  letter-spacing: 0.04em;
}

.admin-header h1 {
  margin: 10px 0 7px;
  color: #19252d;
  font-size: clamp(28px, 3vw, 38px);
  font-weight: 700;
  letter-spacing: -0.045em;
}

.admin-header p,
.panel-desc,
.muted {
  color: #72818b;
}

.admin-header p {
  margin: 0;
  font-size: 14px;
}

.admin-overview {
  padding: 2px 2px 0;
}

.admin-overview__heading {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 20px;
  margin: 0 2px 12px;
}

.admin-overview__heading h2 {
  margin: 4px 0 0;
  color: #27343d;
  font-size: 19px;
}

.admin-overview__heading p {
  margin: 0 0 2px;
  color: #84919a;
  font-size: 12px;
}

.admin-workspace {
  display: grid;
  grid-template-columns: 224px minmax(0, 1fr);
  align-items: start;
  gap: 22px;
}

.admin-index {
  position: sticky;
  top: 104px;
  display: grid;
  gap: 18px;
  padding: 18px 14px;
  border: 1px solid;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.76);
  box-shadow: 0 8px 24px rgba(31, 43, 51, 0.045);
}

.admin-index__heading {
  display: grid;
  gap: 5px;
  padding: 2px 8px 12px;
  border-bottom: 1px solid #edf0f2;
}

.admin-index__heading strong {
  color: #26343d;
  font-size: 15px;
}

.admin-index nav {
  display: grid;
  gap: 2px;
}

.admin-index nav p {
  margin: 12px 8px 4px;
  color: #95a0a7;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.09em;
}

.admin-index nav p:first-child {
  margin-top: 0;
}

.admin-index nav a {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 36px;
  padding: 7px 9px;
  border-radius: 8px;
  color: #576873;
  font-size: 13px;
  text-decoration: none;
  transition: color 0.2s ease, background 0.2s ease;
}

.admin-index nav a:hover,
.admin-index nav a:focus-visible {
  color: #a12e25;
  background: #fff4f1;
  outline: none;
}

.admin-index nav a span {
  color: #a7b0b5;
  font: 10px/1 Roboto, sans-serif;
}

.admin-index__note {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  padding: 11px 9px 2px;
  border-top: 1px solid #edf0f2;
}

.admin-index__note-dot {
  flex: 0 0 auto;
  margin-top: 5px;
  width: 6px;
  height: 6px;
  box-shadow: none;
}

.admin-index__note p {
  margin: 0;
  color: #8a969d;
  font-size: 11px;
  line-height: 1.55;
}

.admin-content {
  display: grid;
  gap: 18px;
  min-width: 0;
}

.admin-panel,
.stat-card {
  border-radius: 14px;
  box-shadow: 0 7px 22px rgba(31, 43, 51, 0.045);
}

.admin-panel {
  padding: 24px;
  scroll-margin-top: 104px;
}

.panel-title {
  align-items: flex-start;
}

.panel-title h2 {
  color: #26343d;
  font-size: 19px;
  letter-spacing: -0.02em;
}

.panel-desc {
  max-width: 850px;
  margin: 10px 0 20px;
  font-size: 13px;
  line-height: 1.7;
}

.stat-grid {
  gap: 12px;
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.stat-card {
  position: relative;
  overflow: hidden;
  padding: 18px 20px;
  background: #fff;
}

.stat-card::after {
  position: absolute;
  right: 0;
  bottom: 0;
  width: 38px;
  height: 3px;
  background: #b83126;
  content: "";
  opacity: 0.7;
}

.stat-card span,
.stat-card small {
  color: #788790;
}

.stat-card strong {
  margin: 7px 0 4px;
  color: #22313a;
  font-size: 28px;
  letter-spacing: -0.035em;
}

.provider-grid,
.content-grid {
  gap: 16px;
}

.provider-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.provider-card,
.retention-grid > div {
  border-color: #e4eaed;
  border-radius: 12px;
  background: #f8fafb;
}

.provider-card {
  padding: 18px;
}

.provider-head {
  margin-bottom: 16px;
}

.provider-head h3,
.retention-grid h3 {
  color: #2d3b44;
}

.provider-head span,
.field-help,
.provider-protocol span {
  color: #829099;
}

.field-gap {
  margin-top: 10px;
}

.form-grid {
  gap: 14px;
  margin: 16px 0 18px;
}

.form-grid label,
.visibility-grid label,
.retention-grid label,
.provider-protocol {
  color: #62727c;
}

.visibility-grid {
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  margin-top: 18px;
}

.visibility-grid select,
.provider-protocol select,
.form-grid select {
  min-height: 40px;
  padding: 8px 10px;
  border-color: #d7e0e4;
  border-radius: 8px;
  color: #33434d;
  background: #fbfcfd;
}

.retention-grid {
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 14px;
}

.retention-grid > div {
  padding: 16px;
}

.retention-grid label {
  font-size: 12px;
}

.table-wrap {
  border-color: #e4eaed;
  border-radius: 10px;
  background: #fff;
}

table {
  font-size: 13px;
}

th,
td {
  padding: 13px 12px;
  border-bottom-color: #edf1f3;
}

th {
  color: #81909a;
  background: #fbfcfd;
  font-size: 11px;
  letter-spacing: 0.04em;
}

td {
  color: #43545e;
}

code {
  border: 1px solid #e0e9ed;
  border-radius: 6px;
  color: #315e78;
  background: #f1f7f9;
}

.primary,
.ghost,
.small {
  border-radius: 8px;
  transition: transform 0.18s ease, box-shadow 0.18s ease, background 0.18s ease;
}

.primary {
  background: #b83126;
  box-shadow: 0 5px 12px rgba(184, 49, 38, 0.16);
}

.primary:hover,
.primary:focus-visible {
  background: #9f2a20;
  box-shadow: 0 7px 16px rgba(184, 49, 38, 0.2);
  transform: translateY(-1px);
}

.ghost,
.small {
  border: 1px solid #dfe6ea;
  background: #f4f7f8;
  color: #4b5d67;
}

.ghost:hover,
.small:hover {
  border-color: #cdd9de;
  background: #eaf0f2;
}

.small {
  min-height: 36px;
  padding: 6px 10px;
}

.danger {
  color: #a12d2d;
}

.provider-actions {
  min-height: 32px;
  margin-top: 12px;
}

.test-result {
  line-height: 1.45;
}

.billing-grid > .admin-panel,
.account-grid > .admin-panel {
  min-width: 0;
}

.guestbook-content {
  max-width: 360px;
  white-space: normal;
  line-height: 1.5;
}

.user-table-wrap {
  max-height: 430px;
}

.pagination-controls {
  margin-top: 16px;
}

@media (max-width: 1180px) {
  .admin-workspace {
    grid-template-columns: 196px minmax(0, 1fr);
    gap: 16px;
  }

  .visibility-grid,
  .retention-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 1000px) {
  .admin-workspace {
    grid-template-columns: 1fr;
  }

  .admin-index {
    position: static;
    display: block;
    padding: 14px;
  }

  .admin-index__heading,
  .admin-index__note,
  .admin-index nav p {
    display: none;
  }

  .admin-index nav {
    display: flex;
    gap: 4px;
    overflow-x: auto;
    scrollbar-width: thin;
  }

  .admin-index nav a {
    flex: 0 0 auto;
    white-space: nowrap;
  }

  .admin-index nav a span {
    display: none;
  }
}

@media (max-width: 860px) {
  .stat-grid,
  .provider-grid,
  .visibility-grid,
  .retention-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .content-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 600px) {
  .admin-page {
    padding: 16px 10px 52px;
  }

  .admin-shell {
    gap: 14px;
  }

  .admin-header {
    align-items: stretch;
    flex-direction: column;
    padding: 22px 20px;
  }

  .admin-header__copy,
  .admin-header__actions {
    width: 100%;
  }

  .admin-header__actions {
    flex-wrap: wrap;
    justify-content: space-between;
  }

  .admin-header__actions .ghost {
    flex: 1 1 140px;
    min-height: 40px;
    width: auto;
  }

  .admin-overview__heading {
    align-items: flex-start;
    flex-direction: column;
    gap: 4px;
  }

  .admin-panel {
    padding: 17px;
  }

  .panel-title,
  .admin-overview__heading {
    align-items: stretch;
  }

  .panel-title {
    flex-direction: column;
  }

  .panel-title > .primary,
  .panel-actions,
  .panel-actions button {
    width: 100%;
  }

  .panel-actions {
    display: grid;
    grid-template-columns: 1fr;
  }

  .stat-grid,
  .provider-grid,
  .visibility-grid,
  .retention-grid,
  .form-grid {
    grid-template-columns: 1fr;
  }

  .provider-card,
  .retention-grid > div {
    padding: 14px;
  }

  .table-wrap {
    margin: 0 -2px;
  }

  .table-wrap::after {
    display: block;
    padding: 7px 10px;
    color: #7b8791;
    background: #fafbfc;
    content: "左右滑动查看完整数据";
    font-size: 11px;
  }

  .audit-table-wrap {
    max-height: 320px;
  }

  .provider-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .provider-actions .small {
    width: 100%;
  }
}
</style>
