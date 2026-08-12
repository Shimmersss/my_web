<template>
  <div class="ppt-page tool-page">
    <div class="container">
      <div class="tool-page__header ppt-page-header">
        <div>
          <h1>PPT 生成</h1>
          <p>输入一句需求或上传一份资料，AI 会自动理解内容并生成 PPTX 或 HTML 演示文稿。</p>
          <p class="quota-line">余额：{{ auth.isLoggedIn ? `${auth.credits} credits` : '未登录' }} · 本次预计 {{ pptEstimatedCredits }} credits</p>
        </div>
        <n-tag type="info">公开入口</n-tag>
      </div>
      <section class="workspace">
        <div class="workspace-main">
          <section v-if="step === 'form'" class="panel">
            <div class="panel-header">
              <ol class="flow-steps" aria-label="PPT 生成流程">
                <li><strong>1</strong><span>输入需求或资料</span></li>
                <li><strong>2</strong><span>自动提取重点</span></li>
                <li><strong>3</strong><span>生成 PPTX / HTML</span></li>
              </ol>
            </div>

            <div class="field-block output-format-block">
              <div class="field-label">输出格式</div>
              <div class="output-format-picker" role="radiogroup" aria-label="输出格式">
                <button type="button" role="radio" :class="['output-format-card', { active: outputFormat === 'pptx' }]" :aria-checked="outputFormat === 'pptx'" @click="outputFormat = 'pptx'">
                  <strong>PPTX</strong>
                  <span>可在 PowerPoint 中继续编辑</span>
                </button>
                <button type="button" role="radio" :class="['output-format-card', { active: outputFormat === 'html' }]" :aria-checked="outputFormat === 'html'" @click="outputFormat = 'html'">
                  <strong>HTML</strong>
                  <span>单文件网页演示，可直接分享</span>
                </button>
              </div>
            </div>

            <div class="field-block template-field-block">
              <details ref="templateSelector" class="template-selector">
                <summary>
                  <span class="template-selector__title">
                    <small>{{ outputFormat === 'html' ? 'HTML 交互主题' : 'PPTX 通用模板' }}</small>
                    <strong>{{ selectedTemplate?.name || '选择模板' }}</strong>
                  </span>
                  <span class="template-selector__selection">
                    <span class="swatches" aria-label="当前模板配色">
                      <i v-for="color in (selectedTemplate?.palette || []).slice(0, 5)" :key="color" :style="{ backgroundColor: `#${color}` }"></i>
                    </span>
                    <small>{{ formatTemplates.length }} 套可选 · 展开预览</small>
                  </span>
                </summary>
                <div class="template-selector__body">
                  <div class="template-selector__intro">
                    <p>模板默认收起，展开后可浏览缩略图与 5 页样稿；选择后会自动收起，保持工作区紧凑。</p>
                    <span>当前：{{ selectedTemplate?.categoryLabel || '通用风格' }}</span>
                  </div>
                  <div class="template-picker">
                <div class="template-groups">
                  <section v-for="group in templateGroups" :key="group.key" class="template-group">
                    <div class="template-group__header">
                      <span>
                        <strong>{{ group.label }}</strong>
                        <small>从 {{ group.items.length }} 套样式中选择</small>
                      </span>
                      <span class="template-group__meta">点击缩略图预览</span>
                    </div>
                    <div class="template-grid">
                      <button
                        v-for="template in group.items"
                        :key="template.key"
                        type="button"
                        :class="['template-card', { active: templateKey === template.key }]"
                        :aria-pressed="templateKey === template.key"
                        @click="selectTemplate(template)"
                      >
                        <div class="template-card__cover" :style="templatePreviewStyle(buildTemplatePreviewSlides(template)[0], template)">
                          <img
                            v-if="templatePreviewImageUrl(0, template)"
                            :src="templatePreviewImageUrl(0, template)"
                            :alt="`${template.name}封面预览`"
                            loading="lazy"
                            @error="markTemplatePreviewImageError(0, template.key)"
                          />
                          <div v-else class="template-card__fallback" :class="templatePreviewClass(template)">
                            <span>{{ templatePreviewModeLabel(template) }}</span>
                            <strong>{{ template.name }}</strong>
                          </div>
                          <span v-if="templateKey === template.key" class="template-card__selected">已选择</span>
                        </div>
                        <span class="template-card__body">
                          <strong>{{ template.name }}</strong>
                          <small>{{ template.description }}</small>
                          <span class="swatches" aria-label="模板配色">
                            <i v-for="color in template.palette.slice(0, 5)" :key="color" :style="{ backgroundColor: `#${color}` }"></i>
                          </span>
                        </span>
                      </button>
                    </div>
                  </section>
                </div>

                <section class="template-showcase" aria-label="模板样式预览">
                  <div class="template-showcase__heading">
                    <div>
                      <span class="template-showcase__eyebrow">{{ templatePreviewImageUrl(templatePreviewIndex) ? 'REAL RENDER · 5 PAGES' : 'DESIGN LANGUAGE · 5 FRAMES' }}</span>
                      <h3>{{ selectedTemplate?.name || '学术蓝' }}</h3>
                      <p>{{ selectedTemplate?.description || '选择模板后查看页面节奏和配色示意。' }}</p>
                      <div class="template-showcase__facts">
                        <span>{{ selectedTemplate?.complexity === 'rich' ? '复杂布局 · 图文/数据槽位' : '通用布局' }}</span>
                        <span v-if="selectedTemplate?.recommendedFor?.length">适合：{{ selectedTemplate.recommendedFor.join(' · ') }}</span>
                        <a v-if="selectedTemplate?.source" :href="selectedTemplate.sourceUrl" target="_blank" rel="noreferrer">{{ selectedTemplate.source }} · {{ selectedTemplate.license || '开源' }}</a>
                      </div>
                    </div>
                    <n-tag size="small" type="success">当前选择</n-tag>
                  </div>
                  <div v-if="templatePreviewSlides.length" class="template-showcase__stage">
                    <div class="template-showcase__main" :style="templatePreviewStyle(templatePreviewSlides[templatePreviewIndex])">
                      <img
                        v-if="templatePreviewImageUrl(templatePreviewIndex)"
                        :src="templatePreviewImageUrl(templatePreviewIndex)"
                        :alt="`${selectedTemplate?.name || '通用模板'}第 ${templatePreviewIndex + 1} 页真实预览`"
                        class="template-preview-image"
                        loading="eager"
                        @error="markTemplatePreviewImageError(templatePreviewIndex, selectedTemplate?.key)"
                      />
                      <div v-else class="template-preview-slide" :class="[`template-preview-slide--${templatePreviewSlides[templatePreviewIndex].kind}`, templatePreviewClass()]">
                        <span class="template-preview-kicker">{{ templatePreviewSlides[templatePreviewIndex].eyebrow }}</span>
                        <span v-if="templatePreviewSlides[templatePreviewIndex].index" class="template-preview-chapter">{{ templatePreviewSlides[templatePreviewIndex].index }}</span>
                        <h4>{{ templatePreviewSlides[templatePreviewIndex].title }}</h4>
                        <p v-if="templatePreviewSlides[templatePreviewIndex].subtitle" class="template-preview-subtitle">{{ templatePreviewSlides[templatePreviewIndex].subtitle }}</p>
                        <ul v-if="templatePreviewSlides[templatePreviewIndex].bullets?.length" class="template-preview-bullets">
                          <li v-for="bullet in templatePreviewSlides[templatePreviewIndex].bullets" :key="bullet">{{ bullet }}</li>
                        </ul>
                        <div v-if="templatePreviewSlides[templatePreviewIndex].metric" class="template-preview-metric">
                          <strong>{{ templatePreviewSlides[templatePreviewIndex].metric.value }}</strong>
                          <span>{{ templatePreviewSlides[templatePreviewIndex].metric.label }}</span>
                        </div>
                        <div class="template-preview-footer">{{ String(templatePreviewIndex + 1).padStart(2, '0') }} / 05</div>
                      </div>
                    </div>
                    <div class="template-showcase__thumbs">
                      <button
                        v-for="(slide, index) in templatePreviewSlides"
                        :key="`${selectedTemplate?.key}-${slide.kind}`"
                        type="button"
                        :class="['template-preview-thumb', { active: templatePreviewIndex === index }]"
                        :aria-label="`预览第 ${index + 1} 页`"
                        @click="templatePreviewIndex = index"
                      >
                        <div class="template-preview-thumb__canvas" :class="templatePreviewClass()" :style="templatePreviewStyle(slide)">
                          <img
                            v-if="templatePreviewImageUrl(index)"
                            :src="templatePreviewImageUrl(index)"
                            :alt="`${selectedTemplate?.name || '通用模板'}第 ${index + 1} 页缩略图`"
                            class="template-preview-thumb__image"
                            loading="lazy"
                            @error="markTemplatePreviewImageError(index, selectedTemplate?.key)"
                          />
                          <template v-else>
                            <span>{{ slide.index || (index + 1).toString().padStart(2, '0') }}</span>
                            <strong>{{ slide.title }}</strong>
                          </template>
                        </div>
                        <small>{{ index + 1 }} · {{ slide.label }}</small>
                      </button>
                    </div>
                  </div>
                  <p class="template-showcase__note">
                    {{ selectedTemplate?.category === 'pptd'
                      ? '这是所选 PPTD 设计系统的风格化信息预览；它展示配色、版式节奏和信息密度，不把示意文字承诺为最终内容。Codex 会在隔离工作区生成可编辑 PPTD，再由服务器固定导出器生成 PPTX。'
                      : selectedTemplate?.category === 'github'
                      ? '这里展示的是来自 GitHub 成品 PPTX 的真实 5 页样稿；生成时会保留该模板的构图语言并用可编辑内容替换示例文字。'
                      : outputFormat === 'html'
                        ? '卡片展示的是 reveal.js 在动效结束后的 5 页真实截图；生成完成后，结果页顶部会提供可点击、可翻页的动态交互演示。'
                      : '这里展示的是所选源模板真实渲染出的 5 页样稿；Agent 会逐页选择源版式并在原位编辑。' }}
                  </p>
                  <p v-if="selectedTemplate?.usageNote" class="template-showcase__license-note">授权提示：{{ selectedTemplate.usageNote }}</p>
                </section>
              </div>
                </div>
              </details>
            </div>

            <div class="field-block resource-field-block">
              <div class="field-label">资料与视觉参考 <span class="optional-label">（可选）</span></div>
              <div class="upload-grid">
              <label v-if="outputFormat === 'pptx'" class="file-box">
                <input type="file" accept=".pptx" aria-label="上传自定义 PPT 模板" @change="handleTemplateSelect" />
                <n-icon size="34"><EaselOutline /></n-icon>
                <strong>{{ templateFile ? templateFile.name : '上传自定义 PPT 模板' }}</strong>
                <span>可选；作为 Codex 的视觉参考，不会覆盖当前设计系统</span>
              </label>
              <label class="file-box">
                <input type="file" accept=".pdf,.docx,.pptx,.xlsx,.txt,.md,.csv,.html,.htm" aria-label="上传资料文件" @change="handleSourceSelect" />
                <n-icon size="34"><DocumentTextOutline /></n-icon>
                <strong>{{ sourceFile ? sourceFile.name : '上传资料文件' }}</strong>
                <span>可选；支持 PDF、Word、PPT、Excel、TXT、Markdown、CSV、网页，最大 30MB</span>
              </label>
              </div>
            </div>

            <div class="field-block">
              <div class="field-label">补充要求 <span class="optional-label">（可选）</span></div>
              <n-input
                v-model:value="prompt"
                type="textarea"
                aria-label="PPT 生成提示词"
                :autosize="{ minRows: 8, maxRows: 14 }"
                maxlength="8000"
                show-count
                placeholder="例如：做成 10 页产品发布会 PPT，面向企业客户，突出核心价值、使用场景、关键数据和下一步行动。不上传资料时，也可以直接用提示词生成。"
              />
              <p class="field-hint">提示词和资料至少提供一个；只上传资料时，系统会自动提炼主题、结构、重点数据和适合的视觉素材。</p>
            </div>

            <details class="generation-options">
              <summary><span>生成偏好</span><small>{{ preferenceSummary }}</small></summary>
              <div class="generation-options__body">
                <div class="font-family-block">
                  <div class="field-label">生成字体</div>
                  <label class="font-family-control">
                    <span class="font-family-control__label">应用于自动填充的文字与 HTML 页面</span>
                    <select v-model="fontFamily" aria-label="生成字体">
                      <option v-for="font in fontOptions" :key="font.value" :value="font.value">{{ font.label }}</option>
                    </select>
                  </label>
                  <p class="field-hint">PPTX 会把这个选择写入全部生成文字；标题过长会先缩写或换行，不会再压住正文。</p>
                </div>
                <div class="generation-options__grid">
                  <div>
                    <div class="field-label">联网研究</div>
                    <div class="output-format-picker" role="radiogroup" aria-label="联网研究">
                      <button type="button" role="radio" :class="['output-format-card', { active: researchMode === 'auto' }]" :aria-checked="researchMode === 'auto'" @click="researchMode = 'auto'">
                        <strong>自动研究</strong><span>检索可信来源并核验事实</span>
                      </button>
                      <button type="button" role="radio" :class="['output-format-card', { active: researchMode === 'off' }]" :aria-checked="researchMode === 'off'" @click="researchMode = 'off'">
                        <strong>关闭联网</strong><span>仅使用提示词与上传资料</span>
                      </button>
                    </div>
                  </div>
                  <div>
                    <div class="field-label">网络配图</div>
                    <div class="output-format-picker" role="radiogroup" aria-label="网络配图">
                      <button type="button" role="radio" :class="['output-format-card', { active: visualMode === 'best_effort' }]" :aria-checked="visualMode === 'best_effort'" @click="visualMode = 'best_effort'">
                        <strong>尽力配图</strong><span>可靠素材优先；没有合适图片时保留清晰版式</span>
                      </button>
                      <button type="button" role="radio" :class="['output-format-card', { active: visualMode === 'strict' }]" :aria-checked="visualMode === 'strict'" @click="visualMode = 'strict'">
                        <strong>严格图文并茂</strong><span>要求内容页有贴题图片；无可用素材会明确失败</span>
                      </button>
                    </div>
                  </div>
                  <div v-if="outputFormat === 'html'">
                    <div class="field-label">演示动效</div>
                    <div class="output-format-picker motion-mode-picker" role="radiogroup" aria-label="HTML 演示动效">
                      <button type="button" role="radio" :class="['output-format-card', { active: motionMode === 'auto' }]" :aria-checked="motionMode === 'auto'" @click="motionMode = 'auto'">
                        <strong>自动编排</strong><span>按叙事和主题选择克制动效</span>
                      </button>
                      <button type="button" role="radio" :class="['output-format-card', { active: motionMode === 'subtle' }]" :aria-checked="motionMode === 'subtle'" @click="motionMode = 'subtle'">
                        <strong>克制</strong><span>短淡入与少量分步呈现</span>
                      </button>
                      <button type="button" role="radio" :class="['output-format-card', { active: motionMode === 'expressive' }]" :aria-checked="motionMode === 'expressive'" @click="motionMode = 'expressive'">
                        <strong>强调</strong><span>更鲜明的节奏与元素编排</span>
                      </button>
                      <button type="button" role="radio" :class="['output-format-card', { active: motionMode === 'off' }]" :aria-checked="motionMode === 'off'" @click="motionMode = 'off'">
                        <strong>关闭</strong><span>全部内容直接呈现</span>
                      </button>
                    </div>
                    <p class="field-hint">系统“减少动态效果”设置始终优先；下载文件也支持按 L 切换低功耗静态模式。</p>
                  </div>
                  <div v-if="outputFormat === 'pptx'">
                    <div class="field-label">AI 生图（GPT Image 2）</div>
                    <div class="output-format-picker" role="radiogroup" aria-label="AI 生图">
                      <button type="button" role="radio" :class="['output-format-card', { active: imageGenerationMode === 'off' }]" :aria-checked="imageGenerationMode === 'off'" @click="imageGenerationMode = 'off'">
                        <strong>关闭</strong><span>只使用资料、研究和可追溯网络素材</span>
                      </button>
                      <button type="button" role="radio" :class="['output-format-card', { active: imageGenerationMode === 'supplement' }]" :aria-checked="imageGenerationMode === 'supplement'" @click="imageGenerationMode = 'supplement'">
                        <strong>补充生图</strong><span>生成少量无文字视觉素材，优先补齐缺图页面</span>
                      </button>
                      <button type="button" role="radio" :class="['output-format-card', { active: imageGenerationMode === 'prefer' }]" :aria-checked="imageGenerationMode === 'prefer'" @click="imageGenerationMode = 'prefer'">
                        <strong>优先 AI 生图</strong><span>优先采用生成图，但资料原图与可信来源仍可覆盖</span>
                      </button>
                    </div>
                    <p class="field-hint">与“Codex 生图”共用同一余额和 low / medium / high 单价；当前会预扣 {{ pptImageCredits }} credits（{{ pptImageCount }} 张 {{ imageGenerationQuality }}），任务失败会连同 PPT 基础额度一并退回。</p>
                  </div>
                </div>
              </div>
            </details>

            <div class="actions">
              <n-button type="primary" size="large" :loading="isSubmitting" @click="submitTask">
                <template #icon><n-icon><SparklesOutline /></n-icon></template>
                生成 {{ outputFormat === 'html' ? 'HTML' : 'PPTX' }}
              </n-button>
              <n-button size="large" :disabled="isSubmitting" @click="resetForm">
                <template #icon><n-icon><RefreshOutline /></n-icon></template>
                清空
              </n-button>
            </div>

            <n-alert v-if="errorMsg" type="error" :title="errorMsg" closable @close="errorMsg = ''" />
          </section>

          <section v-else-if="step === 'running' && taskFailed" class="panel ppt-failure-panel" role="alert" aria-live="assertive">
            <div class="failure-hero">
              <div class="failure-icon" aria-hidden="true">
                <n-icon size="46"><AlertCircleOutline /></n-icon>
              </div>
              <div>
                <span class="failure-eyebrow">GENERATION FAILED · 生成失败</span>
                <h2>这份 {{ outputFormatLabel(activeTask) }} 没有生成完成</h2>
                <p>{{ runningTitle }}</p>
              </div>
            </div>

            <div class="failure-reason">
              <strong>失败原因</strong>
              <p>{{ errorMsg || activeTask?.errorMessage || '后台任务未能完成，请重新提交。' }}</p>
            </div>

            <div class="failure-actions">
              <n-button type="primary" size="large" @click="backToForm">
                <template #icon><n-icon><RefreshOutline /></n-icon></template>
                重新生成
              </n-button>
              <n-button size="large" @click="resetAndBackToForm">重新选择资料</n-button>
            </div>
            <p class="failure-hint">返回生成界面后可以检查提示词、模板和资料，再次提交任务。</p>
          </section>

          <section v-else-if="step === 'running'" class="panel progress-panel">
            <div class="progress-title" role="status" aria-live="polite">
              <n-icon size="32"><TimeOutline /></n-icon>
              <div>
                <h2>{{ runningTitle }}</h2>
                <p v-if="queuePosition > 0">正在排队，第 {{ queuePosition }} 位</p>
                <p v-else>{{ progressStageLabel }}</p>
              </div>
            </div>
            <n-progress type="line" :percentage="Math.round(progress)" :processing="progress < 100" />
            <div class="stage-grid">
              <div v-for="item in stageItems" :key="item.key" :class="['stage-item', { active: item.key === progressStage }]">
                <n-icon><component :is="item.icon" /></n-icon>
                <span>{{ item.label }}</span>
              </div>
            </div>
            <div class="actions">
              <n-button @click="backToForm">返回表单</n-button>
            </div>
          </section>

          <section v-else class="panel result-panel result-panel--wide">
            <div class="result-toolbar">
              <div class="result-heading">
                <div class="result-mark">
                  <n-icon size="34"><EaselOutline /></n-icon>
                </div>
                <div>
                  <h2>{{ outputFormatLabel(activeTask) }} 已生成，可直接预览</h2>
                  <p>{{ activeTask?.outputFileName || `网页预览与 ${outputFormatLabel(activeTask)} 下载均已准备好。` }}</p>
                </div>
              </div>
              <div class="result-toolbar-actions">
                <n-button size="small" :loading="previewLoading" @click="loadPreview">刷新预览</n-button>
              </div>
            </div>

            <n-alert
              v-if="activeTask && !activeTask.qaValid"
              type="warning"
              title="PPT 已交付，但质量审查发现可改进项"
              class="preview-alert"
            >已保留可下载的成品和真实预览。建议查看缩略图后，按下方“继续修改”修复文字截断、版式或素材问题。</n-alert>
            <n-alert v-if="previewError" type="warning" :title="previewError" class="preview-alert" />
            <div v-if="htmlPreviewUrl" class="html-preview-stage">
              <iframe :src="htmlPreviewUrl" title="reveal.js HTML 演示预览" class="html-preview-frame" loading="lazy" referrerpolicy="no-referrer" sandbox="allow-scripts allow-presentation"></iframe>
              <div class="html-preview-stage__caption"><strong>动态交互预览（优先）</strong><span>点击画面后按 ← / → 或空格翻页，查看入场、分步呈现与转场；按 L 可切换低功耗静态模式。</span></div>
            </div>
            <div v-if="previewLoading && !previewSlides.length" class="preview-empty">正在生成网页预览…</div>
            <div v-else-if="previewSlides.length" class="preview-workbench">
              <div class="preview-rail" aria-label="幻灯片缩略图">
                <button
                  v-for="(slide, index) in previewSlides"
                  :key="`${taskId}-${index}`"
                  type="button"
                  :class="['preview-thumb', { active: previewSelectedIndex === index }]"
                  :aria-label="`查看第 ${index + 1} 页`"
                  @click="previewSelectedIndex = index"
                >
                  <img :src="previewImageUrls[slide.imageFile]" class="preview-thumb__canvas" :alt="`第 ${index + 1} 页真实预览`" />
                  <span>{{ index + 1 }}</span>
                </button>
              </div>
              <div class="preview-stage">
                <div class="preview-stage__canvas-wrap">
                  <img :src="previewImageUrls[selectedPreviewSlide?.imageFile]" class="preview-canvas" :alt="`第 ${previewSelectedIndex + 1} 页真实渲染预览`" />
                </div>
                <div class="preview-stage__caption">
                  <div>
                    <strong>{{ selectedPreviewSlide?.title || 'PPT 页面预览' }}</strong>
                    <span>{{ previewSelectedIndex + 1 }} / {{ previewSlides.length }} · {{ selectedPreviewSlide?.width || 1600 }}×{{ selectedPreviewSlide?.height || 900 }}</span>
                  </div>
                  <span class="preview-stage__hint">LibreOffice / Chromium 真实全页渲染</span>
                </div>
              </div>
            </div>
            <div v-else class="preview-empty">此任务暂未生成可用的网页预览，但仍可下载 {{ outputFormatLabel(activeTask) }}。</div>

            <section v-if="activeTask?.editorAvailable" class="revision-box">
              <div class="revision-box__heading"><div><h3>PPTD 项目编辑</h3><p>在画布中直接改文字和版式；点击编辑器内的保存会创建新版本，不调用 Codex、不扣 LLM credits。</p></div><n-tag size="small" type="success">v{{ activeTask.version || 1 }}</n-tag></div>
              <div class="pptd-editor-guide" aria-label="PPTD 编辑步骤">
                <span><b>1</b> 选中页面或元素</span><span><b>2</b> 在画布和右侧面板调整</span><span><b>3</b> 保存为新版本</span>
              </div>
              <div class="actions"><n-button type="primary" @click="toggleEditor">{{ editorVisible ? '收起编辑器' : '在画布中编辑' }}</n-button><n-button v-if="editorVisible" @click="editorExpanded = !editorExpanded">{{ editorExpanded ? '退出专注模式' : '专注编辑' }}</n-button><n-button @click="downloadPptdProject">下载完整 PPTD 项目</n-button></div>
              <p v-if="editorVisible" class="pptd-editor-state" aria-live="polite">{{ editorStatus }}</p>
              <iframe v-if="editorVisible" :src="editorUrl" title="PPTD 项目编辑器" :class="['pptd-editor-frame', { 'pptd-editor-frame--expanded': editorExpanded }]" sandbox="allow-scripts allow-same-origin" referrerpolicy="same-origin"></iframe>
            </section>

            <section v-if="previewData?.sources?.length" class="revision-box">
              <div class="revision-box__heading"><div><h3>研究来源</h3><p>共 {{ previewData.sources.length }} 项，已按幻灯片建立引用映射。</p></div><n-tag size="small" type="success">可追溯</n-tag></div>
              <ul>
                <li v-for="source in previewData.sources" :key="source.id"><a :href="source.url" target="_blank" rel="noreferrer">{{ source.title }}</a><span v-if="source.year"> · {{ source.year }}</span></li>
              </ul>
            </section>

            <section class="revision-box">
              <div class="revision-box__heading">
                <div>
                  <h3>继续修改这份 PPT</h3>
                  <p>用自然语言描述要修改的页面、内容与视觉方向；Agent 会精确修改工程并重新完成全页质检。</p>
                </div>
                <n-tag size="small" type="info">会生成新版本</n-tag>
              </div>
              <n-input v-model:value="revisionPrompt" type="textarea" :autosize="{ minRows: 3, maxRows: 6 }" maxlength="4000" placeholder="例如：把第 2 页改成客户痛点，第 5 页增加一个竞品对比，整体语气更像产品发布会。" />
              <div class="actions">
                <n-button type="primary" :loading="revisionSubmitting" @click="submitRevision">
                  <template #icon><n-icon><SparklesOutline /></n-icon></template>
                  按提示词生成新版本
                </n-button>
                <n-button size="large" @click="downloadCurrent">
                  <template #icon><n-icon><DownloadOutline /></n-icon></template>
                  下载 {{ outputFormatLabel(activeTask) }}
                </n-button>
                <n-button size="large" @click="backToForm">再生成一份</n-button>
              </div>
            </section>
          </section>
        </div>

        <aside class="recent-panel">
          <div class="recent-header">
            <div>
              <h2>最近任务</h2>
              <p>保留最近 5 条生成记录</p>
            </div>
            <n-button size="small" :loading="isLoadingRecent" @click="loadRecent">刷新</n-button>
          </div>
          <n-alert v-if="recentError" type="warning" :title="recentError" class="compact-alert" />
          <div v-if="recentTasks.length" class="recent-list">
            <button v-for="item in recentTasks" :key="item.taskId" type="button" class="recent-item" @click="openRecent(item)">
              <strong>{{ recentTitle(item) }}</strong>
              <span>{{ formatTime(item.createdAt) }}</span>
              <n-tag size="small" :type="tagType(item.status)">{{ statusLabel(item) }}</n-tag>
            </button>
          </div>
          <n-empty v-else-if="!isLoadingRecent" description="暂无 PPT 生成记录" />
        </aside>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useMessage } from 'naive-ui'
import { NAlert, NButton, NEmpty, NIcon, NInput, NProgress, NTag } from 'naive-ui'
import {
  ColorPaletteOutline,
  DocumentTextOutline,
  DownloadOutline,
  EaselOutline,
  AlertCircleOutline,
  RefreshOutline,
  SparklesOutline,
  TimeOutline
} from '@vicons/ionicons5'
import {
  createPptGenerationTask,
  downloadGeneratedPpt,
  getQuotaSettings,
  getPptHtmlPreview,
  getPptPreview,
  getPptPreviewImage,
  getPptGenerationStatus,
  getPptTemplates,
  getRecentPptGenerations,
  revisePptGenerationTask
} from '@/api'
import { apiUrl, BASE_URL } from '@/utils/request'
import { useAuthStore } from '@/stores/auth'

const message = useMessage()
const auth = useAuthStore()
const step = ref('form')
const prompt = ref('')
const templateKey = ref('pptd-navy-cyan-technology')
const outputFormat = ref('pptx')
const researchMode = ref('auto')
const visualMode = ref('best_effort')
const motionMode = ref('auto')
const imageGenerationMode = ref('off')
const fontFamily = ref('Microsoft YaHei')
const templateFile = ref(null)
const sourceFile = ref(null)
const templates = ref(defaultTemplates())
const isSubmitting = ref(false)
const isLoadingRecent = ref(false)
const recentTasks = ref([])
const recentError = ref('')
const errorMsg = ref('')
const activeTask = ref(null)
const previewData = ref(null)
const previewImageUrls = ref({})
const htmlPreviewUrl = ref('')
const previewLoading = ref(false)
const previewError = ref('')
const revisionPrompt = ref('')
const revisionSubmitting = ref(false)
const editorVisible = ref(false)
const editorExpanded = ref(false)
const editorStatus = ref('正在加载项目编辑器…')
const taskFailed = ref(false)
const templateSelector = ref(null)
const templatePreviewIndex = ref(0)
const templatePreviewImageErrors = ref(new Set())
const previewSelectedIndex = ref(0)
const taskId = ref('')
const taskAccessToken = ref('')
const progress = ref(0)
const progressStage = ref('queued')
const progressStageLabel = ref('等待后台生成')
const queuePosition = ref(0)
let eventSource = null
let pollTimer = null
let streamGeneration = 0
let previewGeneration = 0
let previewAbortController = null
let authWatchReady = false
let pendingIdempotencyKey = ''
const PPT_TASK_TOKENS_KEY = 'ppt-generation-task-tokens-v2'
const PPT_ACTIVE_TASK_KEY = 'ppt-generation-active-task-v2'
const pptCreditPerTask = ref(10)
const imageGenerationQuality = ref('medium')
const imageGenerationMaxImages = ref(3)
const imageCredits = ref({ low: 2, medium: 4, high: 8 })
const pptImageCount = computed(() => {
  if (outputFormat.value !== 'pptx') return 0
  if (imageGenerationMode.value === 'prefer') return imageGenerationMaxImages.value
  return imageGenerationMode.value === 'supplement' ? Math.min(2, imageGenerationMaxImages.value) : 0
})
const pptImageCredits = computed(() => pptImageCount.value * (imageCredits.value[imageGenerationQuality.value] || imageCredits.value.medium))
const pptEstimatedCredits = computed(() => pptCreditPerTask.value + pptImageCredits.value)
const fontOptions = [
  { value: 'Microsoft YaHei', label: '微软雅黑（默认）' },
  { value: 'Noto Sans CJK SC', label: 'Noto Sans CJK SC' },
  { value: 'PingFang SC', label: '苹方' },
  { value: 'Source Han Sans SC', label: '思源黑体' },
  { value: 'SimSun', label: '宋体' }
]
const previewSlides = computed(() => previewData.value?.slides || [])
const formatTemplates = computed(() => templates.value.filter(item => !Array.isArray(item.formats) || item.formats.includes(outputFormat.value)))
const selectedTemplate = computed(() => formatTemplates.value.find(item => item.key === templateKey.value) || formatTemplates.value[0] || null)
const selectedPreviewSlide = computed(() => previewSlides.value[previewSelectedIndex.value] || null)
const motionModeLabels = { auto: '自动动效', subtle: '克制动效', expressive: '强调动效', off: '无动效' }
const preferenceSummary = computed(() => {
  const parts = [
    fontOptions.find(item => item.value === fontFamily.value)?.label || fontFamily.value,
    researchMode.value === 'auto' ? '自动研究' : '仅本地资料',
    visualMode.value === 'strict' ? '严格配图' : '尽力配图'
  ]
  if (outputFormat.value === 'html') parts.push(motionModeLabels[motionMode.value] || motionModeLabels.auto)
  else parts.push(imageGenerationMode.value === 'prefer' ? '优先 AI 生图' : imageGenerationMode.value === 'supplement' ? '补充 AI 生图' : '不开启 AI 生图')
  return parts.join(' · ')
})
// Vite dev server only serves the vendored editor reliably through its explicit static file.
// A trailing directory route falls back to the app SPA and used to show the site home page.
const editorUrl = computed(() => taskId.value ? `/pptd-editor/upstream/index.html?taskId=${encodeURIComponent(taskId.value)}` : '')
const templatePreviewSlides = computed(() => buildTemplatePreviewSlides(selectedTemplate.value))
const templateGroups = computed(() => {
  const groups = new Map()
  formatTemplates.value.forEach(template => {
    const key = templateCategoryKey(template)
    if (!groups.has(key)) groups.set(key, {
      key,
      label: template.categoryLabel || templateCategoryLabel(key),
      items: []
    })
    groups.get(key).items.push(template)
  })
  return [...groups.values()]
})

const stageItems = [
  { key: 'queued', label: '排队', icon: TimeOutline },
  { key: 'researching', label: '联网研究', icon: DocumentTextOutline },
  { key: 'planning', label: '叙事规划', icon: ColorPaletteOutline },
  { key: 'authoring', label: '自主创作', icon: SparklesOutline },
  { key: 'rendering', label: '真实渲染', icon: EaselOutline },
  { key: 'reviewing', label: '质量审查', icon: EaselOutline }
]

const runningTitle = computed(() => activeTask.value?.sourceFileName || activeTask.value?.paperFileName || activeTask.value?.templateFileName || 'PPT 生成任务')

onMounted(async () => {
  sessionStorage.removeItem('ppt-generation-task-tokens')
  sessionStorage.removeItem('ppt-generation-active-task')
  await auth.refresh().catch(() => {})
  await Promise.all([loadTemplates(), loadRecent(), loadQuotaSettings()])
  authWatchReady = true
  await restoreActiveTask()
  window.addEventListener('message', handleEditorMessage)
})

watch(outputFormat, () => {
  if (outputFormat.value === 'html') templateFile.value = null
  if (outputFormat.value === 'html') imageGenerationMode.value = 'off'
  const first = formatTemplates.value[0]
  if (first && !formatTemplates.value.some(item => item.key === templateKey.value)) templateKey.value = first.key
  templatePreviewIndex.value = 0
})

onBeforeUnmount(() => {
  closeStream()
  stopPolling()
  clearPreview()
  window.removeEventListener('message', handleEditorMessage)
})

watch(() => auth.user?.id || null, (nextId, previousId) => {
  if (authWatchReady && previousId !== undefined && nextId !== previousId) {
    backToForm()
    clearActiveTask()
  }
})

function handleTemplateSelect(event) {
  const file = event.target.files?.[0] || null
  if (file && (!file.name.toLowerCase().endsWith('.pptx') || file.size > 30 * 1024 * 1024)) {
    errorMsg.value = file.size > 30 * 1024 * 1024 ? 'PPT 模板不能超过 30MB' : '仅支持 .pptx 模板文件'
    event.target.value = ''
    templateFile.value = null
    return
  }
  errorMsg.value = ''
  templateFile.value = file
}

function handleSourceSelect(event) {
  const file = event.target.files?.[0] || null
  const lower = file?.name?.toLowerCase() || ''
  if (file && (!isSupportedSource(lower) || file.size > 30 * 1024 * 1024)) {
    errorMsg.value = file.size > 30 * 1024 * 1024 ? '资料文件不能超过 30MB' : '资料支持 PDF、Word、PPT、Excel、TXT、Markdown、CSV 或网页'
    event.target.value = ''
    sourceFile.value = null
    return
  }
  errorMsg.value = ''
  sourceFile.value = file
}

async function submitTask() {
  errorMsg.value = ''
  taskFailed.value = false
  if (!prompt.value.trim() && !sourceFile.value) {
    errorMsg.value = '请输入提示词，或上传一份资料'
    return
  }
  if (!auth.isLoggedIn) {
    errorMsg.value = '请先登录账号'
    return
  }
  if (!auth.isRoot && auth.credits < pptEstimatedCredits.value) {
    errorMsg.value = `额度不足，预计需要 ${pptEstimatedCredits.value} credits`
    return
  }
  if (templateFile.value && (templateFile.value.size > 30 * 1024 * 1024 || !templateFile.value.name.toLowerCase().endsWith('.pptx'))) {
    errorMsg.value = 'PPT 模板无效或超过 30MB'
    return
  }
  if (sourceFile.value && (sourceFile.value.size > 30 * 1024 * 1024 || !isSupportedSource(sourceFile.value.name.toLowerCase()))) {
    errorMsg.value = '资料文件无效或超过 30MB'
    return
  }
  isSubmitting.value = true
  if (!pendingIdempotencyKey) {
    pendingIdempotencyKey = typeof globalThis.crypto?.randomUUID === 'function'
      ? globalThis.crypto.randomUUID()
      : `ppt-${Date.now()}-${Math.random().toString(36).slice(2)}`
  }
  try {
    const res = await createPptGenerationTask({
      prompt: prompt.value.trim(),
      templateKey: templateKey.value,
      outputFormat: outputFormat.value,
      researchMode: researchMode.value,
      visualMode: visualMode.value,
      motionMode: motionMode.value,
      imageGenerationMode: imageGenerationMode.value,
      fontFamily: fontFamily.value,
      templateFile: templateFile.value,
      sourceFile: sourceFile.value,
      idempotencyKey: pendingIdempotencyKey
    })
    pendingIdempotencyKey = ''
    rememberTaskToken(res.data.taskId, res.data.accessToken)
    if (typeof res.data.credits !== 'undefined') auth.updateCredits(res.data.credits)
    clearPreview()
    setActiveTask(res.data)
    step.value = 'running'
    openStream(taskId.value)
    await loadRecent()
  } catch (error) {
    errorMsg.value = error.message || 'PPT 生成任务提交失败'
  } finally {
    isSubmitting.value = false
  }
}

function openStream(id) {
  closeStream()
  const generation = ++streamGeneration
  const streamTaskId = id
  // Tasks are created behind the authenticated HttpOnly session; keep task tokens out of URLs/logs.
  eventSource = new EventSource(apiUrl(`/ppt-generate/stream/${encodeURIComponent(id)}`), {
    withCredentials: /^https?:\/\//i.test(BASE_URL)
  })
  eventSource.addEventListener('queued', event => {
    if (generation !== streamGeneration) return
    const data = parseEvent(event)
    queuePosition.value = data.queuePosition || 0
    progressStage.value = 'queued'
    progressStageLabel.value = data.message || '任务正在等待后台处理'
  })
  eventSource.addEventListener('progress', event => {
    if (generation !== streamGeneration) return
    const data = parseEvent(event)
    progress.value = Number(data.progress || progress.value || 0)
    progressStage.value = data.stage || progressStage.value
    progressStageLabel.value = data.stageLabel || data.message || progressStageLabel.value
    queuePosition.value = 0
  })
  eventSource.addEventListener('done', async () => {
    if (generation !== streamGeneration || taskId.value !== streamTaskId) return
    progress.value = 100
    closeStream()
    await refreshStatus()
    if (generation !== streamGeneration || taskId.value !== streamTaskId) return
    await loadRecent()
    if (generation !== streamGeneration || taskId.value !== streamTaskId) return
    step.value = 'result'
    await loadPreview()
    if (generation !== streamGeneration || taskId.value !== streamTaskId) return
    message[activeTask.value?.qaValid === false ? 'warning' : 'success'](
      activeTask.value?.qaValid === false
        ? `${outputFormatLabel(activeTask.value)} 已交付，质量审查有提示`
        : `${outputFormatLabel(activeTask.value)} 已生成`
    )
  })
  eventSource.addEventListener('task-error', async event => {
    if (generation !== streamGeneration || taskId.value !== streamTaskId) return
    const data = parseEvent(event)
    errorMsg.value = data.message || 'PPT 生成失败'
    taskFailed.value = true
    closeStream()
    await refreshStatus()
    if (generation !== streamGeneration || taskId.value !== streamTaskId) return
    await loadRecent()
  })
  eventSource.onerror = () => {
    if (generation !== streamGeneration) return
    closeStream()
    startPolling()
  }
}

async function openRecent(item) {
  closeStream()
  stopPolling()
  setActiveTask(item)
  errorMsg.value = item.errorMessage || ''
  taskFailed.value = item.status === 'error'
  if (item.status === 'completed') {
    step.value = 'result'
    await loadPreview()
  } else {
    step.value = 'running'
    if (item.status !== 'error') openStream(item.taskId)
  }
}

async function refreshStatus() {
  if (!taskId.value) return
  const requestedTaskId = taskId.value
  const requestedToken = taskAccessToken.value
  try {
    const res = await getPptGenerationStatus(requestedTaskId, requestedToken)
    if (taskId.value !== requestedTaskId || taskAccessToken.value !== requestedToken) return
    setActiveTask(res.data)
    if (res.data.status === 'completed') {
      taskFailed.value = false
      stopPolling()
      step.value = 'result'
      await loadPreview()
    } else if (res.data.status === 'error') {
      taskFailed.value = true
      stopPolling()
      errorMsg.value = res.data.errorMessage || 'PPT 生成失败'
    }
  } catch (error) {
    if (taskId.value !== requestedTaskId || taskAccessToken.value !== requestedToken) return
    errorMsg.value = error.message || '任务状态恢复失败'
  }
}

async function loadTemplates() {
  try {
    const res = await getPptTemplates()
    if (Array.isArray(res.data) && res.data.length) templates.value = res.data
    if (!formatTemplates.value.some(item => item.key === templateKey.value)) templateKey.value = formatTemplates.value[0]?.key || 'pptd-navy-cyan-technology'
  } catch {
    templates.value = defaultTemplates()
  }
}

function selectTemplate(template) {
  if (!template?.key) return
  templateKey.value = template.key
  templatePreviewIndex.value = 0
  templateSelector.value?.removeAttribute('open')
}

function templatePreviewAssetKey(index, template = selectedTemplate.value) {
  return `${template?.key || 'unknown'}:${index}`
}

function templatePreviewImageUrl(index, template = selectedTemplate.value) {
  if (!template?.key || templatePreviewImageErrors.value.has(templatePreviewAssetKey(index, template))) return ''
  const previewRoot = outputFormat.value === 'html' ? '/html-template-previews' : '/ppt-template-previews'
  return `${previewRoot}/${encodeURIComponent(template.key)}/slide-${index + 1}.png`
}

function markTemplatePreviewImageError(index, templateKeyValue = selectedTemplate.value?.key) {
  const next = new Set(templatePreviewImageErrors.value)
  next.add(templatePreviewAssetKey(index, { key: templateKeyValue }))
  templatePreviewImageErrors.value = next
}

async function loadPreview() {
  clearPreview()
  previewError.value = ''
  if (!taskId.value || activeTask.value?.status !== 'completed') return
  const requestedTaskId = taskId.value
  const requestedToken = taskAccessToken.value
  const generation = ++previewGeneration
  const controller = new AbortController()
  previewAbortController = controller
  previewLoading.value = true
  try {
    const res = await getPptPreview(requestedTaskId, requestedToken, { signal: controller.signal })
    if (generation !== previewGeneration || taskId.value !== requestedTaskId || taskAccessToken.value !== requestedToken) return
    const data = res.data || {}
    data.slides = Array.isArray(data.slides) ? data.slides : []
    previewData.value = data
    previewSelectedIndex.value = Math.min(previewSelectedIndex.value, Math.max(0, data.slides.length - 1))
    const imageFiles = [...new Set(data.slides.map(slide => slide.imageFile).filter(Boolean))].slice(0, 24)
    const loaded = await Promise.all(imageFiles.map(async fileName => {
      try {
        const url = await getPptPreviewImage(requestedTaskId, fileName, requestedToken, { signal: controller.signal })
        if (generation !== previewGeneration || taskId.value !== requestedTaskId || taskAccessToken.value !== requestedToken) {
          URL.revokeObjectURL(url)
          return null
        }
        return [fileName, url]
      } catch {
        return null
      }
    }))
    if (generation !== previewGeneration || taskId.value !== requestedTaskId || taskAccessToken.value !== requestedToken) return
    previewImageUrls.value = Object.fromEntries(loaded.filter(Boolean))
    if (outputFormatLabel(activeTask.value) === 'HTML') {
      const url = await getPptHtmlPreview(requestedTaskId, requestedToken, { signal: controller.signal })
      if (generation !== previewGeneration || taskId.value !== requestedTaskId || taskAccessToken.value !== requestedToken) {
        URL.revokeObjectURL(url)
        return
      }
      if (htmlPreviewUrl.value) URL.revokeObjectURL(htmlPreviewUrl.value)
      htmlPreviewUrl.value = url
    }
  } catch (error) {
    if (error?.name === 'AbortError') return
    if (generation !== previewGeneration || taskId.value !== requestedTaskId) return
    previewError.value = error.message || '网页预览加载失败'
  } finally {
    if (generation === previewGeneration) {
      previewLoading.value = false
      previewAbortController = null
    }
  }
}

function clearPreview() {
  previewGeneration += 1
  previewLoading.value = false
  if (previewAbortController) {
    previewAbortController.abort()
    previewAbortController = null
  }
  Object.values(previewImageUrls.value || {}).forEach(url => URL.revokeObjectURL(url))
  previewImageUrls.value = {}
  if (htmlPreviewUrl.value) URL.revokeObjectURL(htmlPreviewUrl.value)
  htmlPreviewUrl.value = ''
  previewData.value = null
  previewError.value = ''
  previewSelectedIndex.value = 0
}

function buildTemplatePreviewSlides(template) {
  const name = template?.name || '学术蓝'
  const design = template?.design || 'academic'
  return [
    { kind: 'cover', label: '封面', eyebrow: name.toUpperCase(), title: '你的主题标题', subtitle: '一句话说明这份 PPT 要解决什么问题', index: '' },
    { kind: 'agenda', label: '目录', eyebrow: 'CONTENTS', title: '内容结构', subtitle: '清晰的章节节奏，让听众知道接下来会发生什么', bullets: ['背景与目标', '关键洞察', '方案与证据', '行动计划'], index: '' },
    { kind: 'section', label: '章节页', eyebrow: 'CHAPTER 01', title: '章节标题', subtitle: '用大字号和留白建立节奏', index: '01' },
    { kind: 'content', label: '内容页', eyebrow: 'KEY MESSAGE', title: '一句明确的结论先行', subtitle: '正文、要点和关键数据会根据你的资料自动替换', bullets: ['高价值信息优先', '图表与素材匹配内容', '相邻页面保持节奏变化'], metric: { value: design === 'dark-tech' ? 'AI' : '68%', label: '示意指标' }, index: '' },
    { kind: 'closing', label: '结尾页', eyebrow: 'NEXT STEP', title: '谢谢观看', subtitle: '把结论落到下一步行动', index: '' }
  ]
}

function templatePreviewStyle(slide, template = selectedTemplate.value) {
  const palette = (template?.palette || ['005BAC', '063A78', 'D9A441', 'EFF6FF', '1F2937']).map(color => `#${String(color).replace('#', '')}`)
  const dark = ['cover', 'section', 'closing'].includes(slide?.kind)
  return {
    '--template-accent': palette[0],
    '--template-deep': palette[1],
    '--template-highlight': palette[2],
    '--template-bg': dark ? palette[1] : palette[3],
    '--template-text': dark ? '#ffffff' : palette[4]
  }
}

function templatePreviewClass(template = selectedTemplate.value) {
  const key = `${template?.design || ''} ${template?.key || ''}`.toLowerCase()
  if (/(dark|neon|cyber|black)/.test(key)) return 'template-preview-design--dark-tech'
  if (/(collage|brand|peach|pink|silk|fresh)/.test(key)) return 'template-preview-design--poster'
  if (/(data|analytics|engineering|due|quarterly|medical)/.test(key)) return 'template-preview-design--grid'
  if (/(paper|academic|course|education|training)/.test(key)) return 'template-preview-design--paper'
  if (/(orange|red|gold|yellow|journal)/.test(key)) return 'template-preview-design--editorial'
  return 'template-preview-design--soft'
}

function templatePreviewModeLabel(template = selectedTemplate.value) {
  return {
    'template-preview-design--dark-tech': 'DARK / TECH',
    'template-preview-design--poster': 'POSTER / STORY',
    'template-preview-design--grid': 'GRID / DATA',
    'template-preview-design--paper': 'PAPER / STUDY',
    'template-preview-design--editorial': 'EDITORIAL'
  }[templatePreviewClass(template)] || 'CLEAN / WORK'
}

async function submitRevision() {
  const promptText = revisionPrompt.value.trim()
  if (!promptText) {
    errorMsg.value = '请输入自然语言修改要求'
    return
  }
  revisionSubmitting.value = true
  errorMsg.value = ''
  taskFailed.value = false
  const idempotencyKey = typeof globalThis.crypto?.randomUUID === 'function'
    ? globalThis.crypto.randomUUID()
    : `ppt-revise-${Date.now()}-${Math.random().toString(36).slice(2)}`
  try {
    const res = await revisePptGenerationTask(taskId.value, taskAccessToken.value, {
      prompt: promptText,
      idempotencyKey
    })
    rememberTaskToken(res.data.taskId, res.data.accessToken)
    if (typeof res.data.credits !== 'undefined') auth.updateCredits(res.data.credits)
    clearPreview()
    setActiveTask(res.data)
    revisionPrompt.value = ''
    step.value = 'running'
    openStream(taskId.value)
    await loadRecent()
  } catch (error) {
    errorMsg.value = error.message || 'PPT 二次修改提交失败'
  } finally {
    revisionSubmitting.value = false
  }
}

async function loadQuotaSettings() {
  try {
    const res = await getQuotaSettings()
    pptCreditPerTask.value = Number(res.data?.pptCreditPerTask || 10)
    imageGenerationQuality.value = ['low', 'medium', 'high'].includes(res.data?.pptImageGenerationQuality) ? res.data.pptImageGenerationQuality : 'medium'
    imageGenerationMaxImages.value = Math.min(4, Math.max(1, Number(res.data?.pptImageGenerationMaxImages || 3)))
    imageCredits.value = {
      low: Number(res.data?.imageLowCredits || 2),
      medium: Number(res.data?.imageMediumCredits || 4),
      high: Number(res.data?.imageHighCredits || 8)
    }
  } catch {
    pptCreditPerTask.value = 10
    imageGenerationQuality.value = 'medium'
    imageGenerationMaxImages.value = 3
    imageCredits.value = { low: 2, medium: 4, high: 8 }
  }
}

async function loadRecent() {
  isLoadingRecent.value = true
  recentError.value = ''
  try {
    const res = await getRecentPptGenerations(Object.values(readTaskTokens()))
    recentTasks.value = Array.isArray(res.data) ? res.data : []
  } catch (error) {
    recentError.value = error.message || '最近任务加载失败'
  } finally {
    isLoadingRecent.value = false
  }
}

function setActiveTask(data) {
  if (!data?.taskId) return
  activeTask.value = data
  taskFailed.value = data.status === 'error'
  taskId.value = data.taskId
  taskAccessToken.value = data.accessToken || tokenForTask(data.taskId)
  persistActiveTask()
  templateKey.value = data.templateKey || templateKey.value
  outputFormat.value = normalizeOutputFormat(data.outputFormat || outputFormat.value)
  visualMode.value = data.visualMode === 'strict' ? 'strict' : 'best_effort'
  motionMode.value = ['subtle', 'expressive', 'off'].includes(data.motionMode) ? data.motionMode : 'auto'
  imageGenerationMode.value = ['supplement', 'prefer'].includes(data.imageGenerationMode) ? data.imageGenerationMode : 'off'
  fontFamily.value = data.fontFamily || fontFamily.value
  progress.value = Number(data.progress || 0)
  progressStage.value = data.progressStage || 'queued'
  progressStageLabel.value = data.progressStageLabel || statusLabel(data)
  queuePosition.value = data.queuePosition || 0
}

async function downloadCurrent() {
  if (activeTask.value?.status !== 'completed' || !taskId.value) return
  try {
    await downloadGeneratedPpt(taskId.value, taskAccessToken.value, activeTask.value?.outputFormat)
  } catch (error) {
    errorMsg.value = error.message || `${outputFormatLabel(activeTask.value)} 下载失败`
  }
}

async function downloadPptdProject() {
  if (!activeTask.value?.pptdAvailable) return
  try { await downloadGeneratedPpt(taskId.value, taskAccessToken.value, 'pptx', 'pptd') }
  catch (error) { errorMsg.value = error.message || 'PPTD 项目下载失败' }
}

function handleEditorMessage(event) {
  if (event.origin !== window.location.origin) return
  if (event.data?.type === 'pptd-editor-ready') {
    editorStatus.value = `项目已加载：v${event.data?.version || activeTask.value?.version || 1}。可直接在画布中编辑，保存会创建新版本。`
    return
  }
  if (event.data?.type === 'pptd-editor-close') {
    editorVisible.value = false
    editorExpanded.value = false
    return
  }
  if (event.data?.type !== 'pptd-version-created') return
  const task = event.data.task
  if (!task?.taskId) return
  rememberTaskToken(task.taskId, task.accessToken)
  editorVisible.value = false
  editorExpanded.value = false
  clearPreview()
  setActiveTask(task)
  step.value = 'running'
  openStream(task.taskId)
  loadRecent()
}

function toggleEditor() {
  editorVisible.value = !editorVisible.value
  if (editorVisible.value) editorStatus.value = '正在加载项目编辑器…'
  else editorExpanded.value = false
}

function resetForm() {
  prompt.value = ''
  researchMode.value = 'auto'
  visualMode.value = 'best_effort'
  motionMode.value = 'auto'
  imageGenerationMode.value = 'off'
  fontFamily.value = 'Microsoft YaHei'
  templateFile.value = null
  sourceFile.value = null
  errorMsg.value = ''
}

function resetAndBackToForm() {
  resetForm()
  backToForm()
}

function backToForm() {
  closeStream()
  stopPolling()
  clearActiveTask()
  step.value = 'form'
  activeTask.value = null
  taskFailed.value = false
  errorMsg.value = ''
  taskId.value = ''
  taskAccessToken.value = ''
  progress.value = 0
  queuePosition.value = 0
  revisionPrompt.value = ''
  clearPreview()
}

async function restoreActiveTask() {
  const saved = readActiveTask()
  if (!saved?.taskId) return
  taskId.value = saved.taskId
  taskAccessToken.value = saved.accessToken || tokenForTask(saved.taskId)
  try {
    const res = await getPptGenerationStatus(taskId.value, taskAccessToken.value)
    setActiveTask(res.data)
    errorMsg.value = res.data.errorMessage || ''
    if (res.data.status === 'completed') {
      step.value = 'result'
      await loadPreview()
    } else if (res.data.status === 'error') {
      step.value = 'running'
    } else {
      step.value = 'running'
      openStream(taskId.value)
    }
  } catch {
    clearActiveTask()
  }
}

function startPolling() {
  stopPolling()
  pollTimer = window.setInterval(refreshStatus, 2500)
}

function stopPolling() {
  if (pollTimer) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
}

function closeStream() {
  streamGeneration += 1
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }
}

function parseEvent(event) {
  try {
    return JSON.parse(event.data)
  } catch {
    return {}
  }
}

function readTaskTokens() {
  try {
    return JSON.parse(sessionStorage.getItem(PPT_TASK_TOKENS_KEY) || '{}')
  } catch {
    return {}
  }
}

function rememberTaskToken(id, accessToken) {
  if (!id || !accessToken) return
  const tokens = readTaskTokens()
  delete tokens[id]
  tokens[id] = accessToken
  const recentEntries = Object.entries(tokens).slice(-20)
  try {
    sessionStorage.setItem(PPT_TASK_TOKENS_KEY, JSON.stringify(Object.fromEntries(recentEntries)))
  } catch {
    // Private browsing / quota restrictions must not turn a successful task into a submit error.
  }
}

function tokenForTask(id) {
  return readTaskTokens()[id] || ''
}

function readActiveTask() {
  try {
    return JSON.parse(sessionStorage.getItem(PPT_ACTIVE_TASK_KEY) || 'null')
  } catch {
    return null
  }
}

function persistActiveTask() {
  if (!taskId.value) return
  try {
    sessionStorage.setItem(PPT_ACTIVE_TASK_KEY, JSON.stringify({
      taskId: taskId.value,
      accessToken: taskAccessToken.value || ''
    }))
  } catch {
    // Continue with in-memory state when sessionStorage is unavailable.
  }
}

function clearActiveTask() {
  try { sessionStorage.removeItem(PPT_ACTIVE_TASK_KEY) } catch {}
}

function defaultTemplates() {
  return [
    { key: 'pptd-navy-cyan-technology', name: 'Navy Cyan Technology', description: 'PPTD 技术设计系统；Codex 在隔离工作区生成可编辑工程', palette: ['2563EB', 'EFF6FF', 'F59E0B', 'FFFFFF', '0F172A'], source: 'open-kimi-ppt-skill 1.3.0', license: 'MIT + separately authorized editor assets', sourceUrl: 'https://github.com/Binaryify/open-kimi-ppt-skill', design: 'navy-cyan-technology', category: 'pptd', categoryLabel: 'PPTD 设计系统', complexity: 'rich', recommendedFor: ['技术汇报', '产品方案', '研究展示'], usageNote: 'PPTX 权限由后台“PPT 生成”节目范围控制；可上传自定义 PPTX 作为视觉参考', formats: ['pptx'] },
    { key: 'github-bjtu-green', name: 'BJTU 青绿影像', description: '北京交通大学开源成品模板，强调照片、圆形构图和校园叙事', palette: ['2A807D', '5D948F', 'D7B95D', 'F1F4F0', '173B3A'], source: 'Allenpandas/BJTU-Slides-Template', license: 'Apache-2.0', sourceUrl: 'https://github.com/Allenpandas/BJTU-Slides-Template', design: 'bjtu-green', category: 'github', categoryLabel: 'GitHub 成品模板', complexity: 'rich', recommendedFor: ['校园叙事', '品牌故事', '图片汇报'], usageNote: '来源仓库 LICENSE 标注 Apache-2.0，但 README 另有仅供学习、禁止商业使用声明；商用前需确认授权' },
    { key: 'github-bjtu-yellow', name: 'BJTU 金色分栏', description: '北京交通大学开源成品模板，左侧图片带与右侧正文分栏', palette: ['F5B400', 'E29A2E', '0F172A', 'FFFDF6', '111827'], source: 'Allenpandas/BJTU-Slides-Template', license: 'Apache-2.0', sourceUrl: 'https://github.com/Allenpandas/BJTU-Slides-Template', design: 'bjtu-yellow', category: 'github', categoryLabel: 'GitHub 成品模板', complexity: 'rich', recommendedFor: ['课程', '项目介绍', '图文报告'], usageNote: '来源仓库 LICENSE 标注 Apache-2.0，但 README 另有仅供学习、禁止商业使用声明；商用前需确认授权' },
    { key: 'github-bjtu-red-2024', name: 'BJTU 红色舞台', description: '北京交通大学开源成品模板，大面积红色舞台与强标题层级', palette: ['EF4444', '58151C', 'FFFFFF', 'FFF1F2', 'FFFFFF'], source: 'Allenpandas/BJTU-Slides-Template', license: 'Apache-2.0', sourceUrl: 'https://github.com/Allenpandas/BJTU-Slides-Template', design: 'bjtu-red', category: 'github', categoryLabel: 'GitHub 成品模板', complexity: 'rich', recommendedFor: ['发布会', '正式汇报', '主题演讲'], usageNote: '来源仓库 LICENSE 标注 Apache-2.0，但 README 另有仅供学习、禁止商业使用声明；商用前需确认授权' },
    { key: 'github-bjtu-red-2023', name: 'BJTU 红色拱门', description: '北京交通大学开源成品模板，拱门线稿与年份叙事适合正式汇报', palette: ['EF4444', '4A2029', 'FFFFFF', 'FFF1F2', 'FFFFFF'], source: 'Allenpandas/BJTU-Slides-Template', license: 'Apache-2.0', sourceUrl: 'https://github.com/Allenpandas/BJTU-Slides-Template', design: 'bjtu-2023-red', category: 'github', categoryLabel: 'GitHub 成品模板', complexity: 'rich', recommendedFor: ['年度汇报', '答辩', '正式演讲'], usageNote: '来源仓库 LICENSE 标注 Apache-2.0，但 README 另有仅供学习、禁止商业使用声明；商用前需确认授权' },
    { key: 'github-bjtu-handdrawn', name: 'BJTU 手绘波形', description: '北京交通大学开源成品模板，柔和波形、插画和手绘感版式', palette: ['7CBFC3', '819FB3', 'E3C6BA', 'EAF5F5', '203B4A'], source: 'Allenpandas/BJTU-Slides-Template', license: 'Apache-2.0', sourceUrl: 'https://github.com/Allenpandas/BJTU-Slides-Template', design: 'bjtu-handdrawn', category: 'github', categoryLabel: 'GitHub 成品模板', complexity: 'rich', recommendedFor: ['创意提案', '活动', '教育内容'], usageNote: '来源仓库 LICENSE 标注 Apache-2.0，但 README 另有仅供学习、禁止商业使用声明；商用前需确认授权' },
    { key: 'html-reveal-black', name: 'Reveal Black', description: '纯黑演讲主题，适合现场演示、发布和强叙事内容', palette: ['111111', '000000', 'D9A441', '111111', 'F8FAFC'], source: 'reveal.js official theme', license: 'MIT', sourceUrl: 'https://github.com/hakimel/reveal.js', design: 'reveal-black', category: 'html', categoryLabel: 'HTML 交互主题', complexity: 'rich', formats: ['html'] },
    { key: 'html-reveal-white', name: 'Reveal White', description: '白底高可读主题，适合文档、课程和知识分享', palette: ['1D4ED8', 'FFFFFF', 'D97706', 'F8FAFC', '1F2937'], source: 'reveal.js official theme', license: 'MIT', sourceUrl: 'https://github.com/hakimel/reveal.js', design: 'reveal-white', category: 'html', categoryLabel: 'HTML 交互主题', complexity: 'rich', formats: ['html'] },
    { key: 'html-reveal-beige', name: 'Reveal Beige', description: '暖米色纸张感，适合品牌故事、案例和长内容', palette: ['8C3B1F', 'F7F1E3', 'B7791F', 'FFFDF7', '3D2B1F'], source: 'reveal.js official theme', license: 'MIT', sourceUrl: 'https://github.com/hakimel/reveal.js', design: 'reveal-beige', category: 'html', categoryLabel: 'HTML 交互主题', complexity: 'rich', formats: ['html'] },
    { key: 'html-reveal-sky', name: 'Reveal Sky', description: '蓝色渐变舞台感，适合产品发布和工作坊', palette: ['0EA5E9', '075985', 'FDE047', 'E0F2FE', '0F172A'], source: 'reveal.js official theme', license: 'MIT', sourceUrl: 'https://github.com/hakimel/reveal.js', design: 'reveal-sky', category: 'html', categoryLabel: 'HTML 交互主题', complexity: 'rich', formats: ['html'] },
    { key: 'html-reveal-league', name: 'Reveal League', description: '高对比杂志式主题，适合观点、趋势和演讲', palette: ['E11D48', '1E293B', 'FACC15', '0F172A', 'F8FAFC'], source: 'reveal.js official theme', license: 'MIT', sourceUrl: 'https://github.com/hakimel/reveal.js', design: 'reveal-league', category: 'html', categoryLabel: 'HTML 交互主题', complexity: 'rich', formats: ['html'] },
    { key: 'html-reveal-night', name: 'Reveal Night', description: '夜间舞台主题，适合 AI、技术和现场演示', palette: ['60A5FA', '0B1120', 'A78BFA', '111827', 'E2E8F0'], source: 'reveal.js official theme', license: 'MIT', sourceUrl: 'https://github.com/hakimel/reveal.js', design: 'reveal-night', category: 'html', categoryLabel: 'HTML 交互主题', complexity: 'rich', formats: ['html'] },
    { key: 'html-reveal-solarized', name: 'Reveal Solarized', description: '柔和护眼的代码与数据主题，适合技术报告', palette: ['268BD2', '002B36', 'B58900', 'FDF6E3', '586E75'], source: 'reveal.js official theme', license: 'MIT', sourceUrl: 'https://github.com/hakimel/reveal.js', design: 'reveal-solarized', category: 'html', categoryLabel: 'HTML 交互主题', complexity: 'rich', formats: ['html'] },
    { key: 'html-reveal-gradient', name: 'Reveal Gradient', description: '独立渐变卡片布局，适合商业计划和产品策略', palette: ['8B5CF6', '312E81', '22D3EE', 'F5F3FF', '1F2937'], source: 'reveal.js custom theme', license: 'MIT', sourceUrl: 'https://github.com/hakimel/reveal.js', design: 'reveal-gradient', category: 'html', categoryLabel: 'HTML 交互主题', complexity: 'rich', formats: ['html'] },
    { key: 'html-editorial-ink', name: 'Editorial Ink', description: '纸刊留白、衬线标题与编辑部式红色批注，适合洞察和品牌故事', palette: ['D9482B', 'F5F1E8', 'A16207', 'DED6C8', '171717'], source: 'Agent HTML theme asset', license: 'MIT', sourceUrl: 'https://github.com/hakimel/reveal.js', design: 'editorial-ink', category: 'html', categoryLabel: 'HTML 交互主题', complexity: 'rich', formats: ['html'] },
    { key: 'html-neon-grid', name: 'Neon Grid', description: '深色网格与青色霓虹界面，适合 AI、数据产品和技术发布', palette: ['2DD4BF', '070A13', '8B5CF6', '172033', 'ECFEFF'], source: 'Agent HTML theme asset', license: 'MIT', sourceUrl: 'https://github.com/hakimel/reveal.js', design: 'neon-grid', category: 'html', categoryLabel: 'HTML 交互主题', complexity: 'rich', formats: ['html'] },
    { key: 'html-terminal-green', name: 'Terminal Green', description: '终端式等宽字体与命令行节奏，适合开发者、架构和开源项目', palette: ['4ADE80', '07120D', 'FACC15', '10261A', 'D1FAE5'], source: 'Agent HTML theme asset', license: 'MIT', sourceUrl: 'https://github.com/hakimel/reveal.js', design: 'terminal-green', category: 'html', categoryLabel: 'HTML 交互主题', complexity: 'rich', formats: ['html'] },
    { key: 'html-gallery-cream', name: 'Gallery Cream', description: '画廊米白、酒红强调与高雅衬线排版，适合文化、设计和高端品牌', palette: ['9F1239', 'F4EFE5', 'C08457', 'E7DAC9', '3B2524'], source: 'Agent HTML theme asset', license: 'MIT', sourceUrl: 'https://github.com/hakimel/reveal.js', design: 'gallery-cream', category: 'html', categoryLabel: 'HTML 交互主题', complexity: 'rich', formats: ['html'] },
    { key: 'html-roman-forum', name: 'Roman Forum', description: '古罗马石刻、赤陶红与柱式秩序，适合历史、文化、制度和经典叙事', palette: ['A4432F', 'F2EAD8', 'B99352', 'D8C6A6', '2F2923'], source: 'Agent HTML theme asset', license: 'MIT', sourceUrl: 'https://github.com/hakimel/reveal.js', design: 'roman-forum', category: 'html', categoryLabel: 'HTML 交互主题', complexity: 'rich', recommendedFor: ['历史叙事', '文化研究', '制度与经典'], motionModes: ['auto', 'subtle', 'expressive', 'off'], formats: ['html'] }
  ].filter(template => template.category === 'pptd' || template.category === 'html')
    .map(template => ({ formats: template.category === 'html' ? ['html'] : ['pptx'], ...template }))
}

function normalizeOutputFormat(value) {
  return String(value || 'pptx').toLowerCase() === 'html' ? 'html' : 'pptx'
}

function outputFormatLabel(task) {
  return normalizeOutputFormat(task?.outputFormat || outputFormat.value) === 'html' ? 'HTML' : 'PPTX'
}

function templateCategoryKey(template) {
  if (template?.category) return template.category
  const design = template?.design || template?.key || ''
  if (['editorial', 'memphis'].some(value => design.includes(value))) return 'editorial'
  if (['report', 'data', 'swiss', 'primer-report'].some(value => design.includes(value))) return 'data'
  if (['pitch', 'product', 'glass', 'apple'].some(value => design.includes(value))) return 'product'
  if (['training'].some(value => design.includes(value))) return 'training'
  return 'core'
}

function templateCategoryLabel(key) {
  return {
    core: '基础风格',
    editorial: '杂志与创意',
    data: '数据与咨询',
    product: '产品与 SaaS',
    corporate: '企业与开源',
    training: '课程与培训',
    github: 'GitHub 成品模板',
    pptd: 'PPTD 设计系统',
    html: 'HTML 交互主题'
  }[key] || '其他风格'
}

function previewSlideStyle(slide, index) {
  const palette = (previewData.value?.palette || ['005BAC', '063A78', 'D9A441', 'EFF6FF', '1F2937']).map(color => `#${String(color).replace('#', '')}`)
  const dark = ['section', 'thanks'].includes(slide.type) || index === 0
  return {
    '--preview-accent': palette[0],
    '--preview-deep': palette[1],
    '--preview-highlight': palette[2],
    '--preview-bg': dark ? palette[1] : palette[3],
    '--preview-text': dark ? '#ffffff' : palette[4]
  }
}

function recentTitle(item) {
  return item.sourceFileName || item.paperFileName || item.templateFileName || trimPrompt(item.prompt)
}

function isSupportedSource(name) {
  return /\.(pdf|docx|pptx|xlsx|txt|md|csv|html|htm)$/i.test(name || '')
}

function trimPrompt(value) {
  const text = value || '仅提示词生成'
  return text.length > 28 ? `${text.slice(0, 28)}...` : text
}

function statusLabel(item) {
  if (item.status === 'completed') return '已完成'
  if (item.status === 'error') return '失败'
  if (item.status === 'generating') return item.progressStageLabel || '生成中'
  return item.queuePosition > 0 ? `排队第 ${item.queuePosition} 位` : '排队中'
}

function tagType(status) {
  if (status === 'completed') return 'success'
  if (status === 'error') return 'error'
  if (status === 'generating') return 'info'
  return 'warning'
}

function formatTime(ts) {
  if (!ts) return ''
  return new Date(ts).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
}
</script>

<style scoped lang="scss">
.ppt-page {
  background: #eee9df;
}

.quota-line {
  margin-top: 6px;
  color: #8f2a22;
  font-size: 14px;
}

.container {
  width: min(1180px, calc(100% - 32px));
  margin: 0 auto;
}

.workspace {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 330px;
  gap: 20px;
  align-items: start;
}

.ppt-page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.panel,
.recent-panel {
  background: #fbf9f3;
  border: 1px solid #d2cabc;
  border-radius: 2px;
  box-shadow: 2px 3px 0 rgba(95, 86, 65, 0.1);
}

.panel {
  padding: 24px;
}

.panel-header,
.recent-header,
.progress-title {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.flow-steps {
  width: 100%;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.flow-steps li {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #475569;
  font-size: 14px;
}

.flow-steps strong {
  width: 24px;
  height: 24px;
  border-radius: 999px;
  display: grid;
  place-items: center;
  color: #b83126;
  background: #f0e5d9;
  font-size: 12px;
}

h1,
h2,
h3 {
  margin: 0;
  color: #0f172a;
}

p {
  margin: 6px 0 0;
  color: #64748b;
}

.field-block {
  margin-top: 22px;
}

.resource-field-block {
  padding: 18px;
  border: 1px solid #ded6c7;
  border-radius: 12px;
  background: #f8f4ed;
}

.field-label {
  margin-bottom: 10px;
  font-weight: 700;
  color: #334155;
}

.font-family-control {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 13px 15px;
  border: 1px solid #d2cabc;
  background: #fbf9f3;
}

.font-family-control__label {
  color: #64748b;
  font-size: 13px;
}

.font-family-control select {
  min-width: 190px;
  padding: 9px 32px 9px 11px;
  border: 1px solid #cbd5e1;
  border-radius: 2px;
  background: #fff;
  color: #0f172a;
  font: inherit;
}

.generation-options {
  margin-top: 22px;
  border: 1px solid #d2cabc;
  border-radius: 10px;
  background: #f8f4ed;
}

.generation-options summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-height: 56px;
  padding: 0 16px;
  color: #0f172a;
  cursor: pointer;
  list-style: none;
}

.generation-options summary::-webkit-details-marker {
  display: none;
}

.generation-options summary::after {
  content: '＋';
  order: 3;
  color: #8f2a22;
  font-size: 20px;
  font-weight: 400;
}

.generation-options[open] summary {
  border-bottom: 1px solid #ded6c7;
}

.generation-options[open] summary::after {
  content: '−';
}

.generation-options summary > span {
  font-weight: 800;
}

.generation-options summary > small {
  flex: 1;
  overflow: hidden;
  color: #64748b;
  font-size: 12px;
  text-align: right;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.generation-options__body {
  display: grid;
  gap: 18px;
  padding: 16px;
}

.generation-options__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px;
}

.output-format-picker {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.output-format-card {
  display: grid;
  gap: 5px;
  padding: 14px 16px;
  border: 1px solid #d2cabc;
  border-radius: 2px;
  background: #fbf9f3;
  color: #334155;
  text-align: left;
  cursor: pointer;
  transition: border-color .18s ease, box-shadow .18s ease, background .18s ease;
}

.output-format-card strong {
  color: #0f172a;
  letter-spacing: .04em;
}

.output-format-card span {
  color: #64748b;
  font-size: 12px;
}

.output-format-card.active {
  border-color: #b83126;
  background: #f3eadf;
  box-shadow: 0 0 0 3px rgba(184, 49, 38, .1);
}

.output-format-card:focus-visible,
.template-card:focus-visible {
  outline: 3px solid rgba(37, 99, 235, .28);
  outline-offset: 3px;
}

.template-grid,
.upload-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}

.template-field-block {
  margin-top: 18px;
}

.template-selector {
  border: 1px solid #d2cabc;
  border-radius: 12px;
  background: #f8f4ed;
}

.template-selector summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  min-height: 70px;
  padding: 12px 16px;
  cursor: pointer;
  list-style: none;
}

.template-selector summary::-webkit-details-marker {
  display: none;
}

.template-selector summary::after {
  content: '＋';
  order: 3;
  color: #8f2a22;
  font-size: 20px;
}

.template-selector[open] summary {
  border-bottom: 1px solid #ded6c7;
}

.template-selector[open] summary::after {
  content: '−';
}

.template-selector__title {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.template-selector__title small,
.template-selector__selection > small,
.template-selector__intro p {
  color: #64748b;
  font-size: 12px;
}

.template-selector__title strong {
  overflow: hidden;
  color: #0f172a;
  font-size: 15px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.template-selector__selection {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  min-width: 0;
}

.template-selector__selection > small {
  white-space: nowrap;
}

.template-selector__selection .swatches {
  flex: 0 0 auto;
}

.template-selector__body {
  display: grid;
  gap: 14px;
  padding: 16px;
}

.template-selector__intro {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 0 2px;
}

.template-selector__intro p {
  margin: 0;
}

.template-selector__intro > span {
  flex: 0 0 auto;
  color: #8f2a22;
  font-size: 12px;
  font-weight: 700;
}

.template-picker {
  display: grid;
  grid-template-columns: minmax(410px, 1.12fr) minmax(360px, .88fr);
  gap: 22px;
  align-items: start;
}

.template-groups {
  display: grid;
  gap: 18px;
  min-width: 0;
  max-height: min(68vh, 700px);
  overflow-y: auto;
  padding-right: 8px;
  scrollbar-color: #bda88f transparent;
}

.template-group {
  min-width: 0;
}

.template-group__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 0 0 10px;
  border-bottom: 1px solid #ded6c7;
  color: #334155;
  text-align: left;
}

.template-group__header > span:first-child {
  display: grid;
  gap: 3px;
}

.template-group__header small,
.template-group__meta {
  color: #64748b;
  font-size: 11px;
}

.template-group__meta {
  flex: 0 0 auto;
  white-space: nowrap;
}

.template-showcase {
  min-width: 0;
  padding: 16px;
  border: 1px solid #d2cabc;
  border-radius: 12px;
  background: linear-gradient(145deg, #f8f3eb, #f1eadf);
  box-shadow: 0 12px 28px rgba(95, 55, 31, .08);
  position: sticky;
  top: 16px;
}

.template-showcase__heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.template-showcase__heading h3 {
  margin-top: 4px;
  font-size: 18px;
}

.template-showcase__heading p {
  max-width: 330px;
  font-size: 12px;
  line-height: 1.45;
}

.template-showcase__facts {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}

.template-showcase__facts span,
.template-showcase__facts a {
  padding: 4px 7px;
  border: 1px solid rgba(126, 105, 76, .18);
  border-radius: 999px;
  color: #6c5d4c;
  background: rgba(255, 255, 255, .56);
  font-size: 10px;
  line-height: 1.2;
  text-decoration: none;
}

.template-showcase__facts a {
  color: #9d3329;
}

.template-showcase__facts a:hover {
  border-color: #b83126;
}

.template-showcase__eyebrow {
  color: #8f2a22;
  font-size: 10px;
  font-weight: 800;
  letter-spacing: .12em;
}

.template-showcase__stage {
  display: grid;
  gap: 10px;
}

.template-showcase__main {
  aspect-ratio: 16 / 9;
  overflow: hidden;
  background: var(--template-bg);
  box-shadow: 0 8px 18px rgba(55, 45, 30, .16);
}

.template-preview-image {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
  background: #f8fafc;
}

.template-preview-slide {
  position: relative;
  height: 100%;
  overflow: hidden;
  padding: 8% 9%;
  color: var(--template-text);
  background: var(--template-bg);
}

.template-preview-design--editorial {
  font-family: Georgia, "Songti SC", serif;
}

.template-preview-design--editorial h4 {
  letter-spacing: -.04em;
}

.template-preview-design--dark-tech {
  color: #ecfeff;
  background-color: #08111f;
  background-image: linear-gradient(rgba(45, 212, 191, .12) 1px, transparent 1px), linear-gradient(90deg, rgba(45, 212, 191, .12) 1px, transparent 1px), radial-gradient(circle at 88% 12%, rgba(139, 92, 246, .5), transparent 28%);
  background-size: 12% 18%, 12% 18%, auto;
}

.template-preview-design--poster {
  font-family: Georgia, "Songti SC", serif;
  background-image: linear-gradient(132deg, transparent 0 43%, color-mix(in srgb, var(--template-accent) 75%, white) 43% 61%, transparent 61%), linear-gradient(25deg, transparent 0 70%, color-mix(in srgb, var(--template-highlight) 54%, transparent) 70% 88%, transparent 88%);
}

.template-preview-design--grid {
  background-image: linear-gradient(to right, color-mix(in srgb, var(--template-deep) 13%, transparent) 1px, transparent 1px), linear-gradient(to bottom, color-mix(in srgb, var(--template-deep) 13%, transparent) 1px, transparent 1px);
  background-size: 16.66% 100%, 100% 25%;
}

.template-preview-design--paper {
  font-family: Georgia, "Songti SC", serif;
  background-image: linear-gradient(90deg, color-mix(in srgb, var(--template-accent) 65%, transparent) 0 4px, transparent 4px), radial-gradient(circle at 84% 18%, color-mix(in srgb, var(--template-highlight) 44%, transparent) 0 5%, transparent 5.5%);
}

.template-preview-design--soft {
  background-image: radial-gradient(circle at 88% 16%, color-mix(in srgb, var(--template-accent) 38%, transparent), transparent 26%), linear-gradient(135deg, transparent 30%, color-mix(in srgb, var(--template-highlight) 17%, transparent));
}

.template-preview-design--memphis {
  background-image: radial-gradient(circle at 88% 12%, color-mix(in srgb, var(--template-accent) 48%, transparent) 0 8%, transparent 8.5%), linear-gradient(135deg, transparent 0 72%, color-mix(in srgb, var(--template-highlight) 24%, transparent) 72% 82%, transparent 82%);
}

.template-preview-design--swiss-grid {
  background-image: linear-gradient(to right, rgba(15, 23, 42, .08) 1px, transparent 1px), linear-gradient(to bottom, rgba(15, 23, 42, .08) 1px, transparent 1px);
  background-size: 20% 100%, 100% 25%;
}

.template-preview-design--glass-saas,
.template-preview-design--gradient-pitch,
.template-preview-design--primer {
  background-image: radial-gradient(circle at 90% 5%, color-mix(in srgb, var(--template-accent) 42%, transparent), transparent 34%), linear-gradient(135deg, transparent 20%, color-mix(in srgb, var(--template-highlight) 12%, transparent));
}

.template-preview-design--primer,
.template-preview-design--primer-report,
.template-preview-design--primer-open-source {
  border-radius: 3px;
}

.template-preview-slide::before {
  content: '';
  position: absolute;
  inset: 0 auto 0 0;
  width: 5px;
  background: var(--template-accent);
}

.template-preview-slide--cover,
.template-preview-slide--section,
.template-preview-slide--closing {
  background: var(--template-deep);
}

.template-preview-slide--cover::after,
.template-preview-slide--section::after {
  content: '';
  position: absolute;
  right: -12%;
  bottom: -28%;
  width: 62%;
  height: 86%;
  border-radius: 50%;
  background: color-mix(in srgb, var(--template-accent) 32%, transparent);
}

.template-preview-kicker {
  position: relative;
  z-index: 1;
  color: var(--template-highlight);
  font-size: clamp(7px, .75vw, 11px);
  font-weight: 800;
  letter-spacing: .12em;
}

.template-preview-slide h4 {
  position: relative;
  z-index: 1;
  max-width: 88%;
  margin: 12% 0 0;
  color: var(--template-text);
  font-size: clamp(20px, 3.1vw, 44px);
  line-height: 1.08;
}

.template-preview-slide--section h4 {
  margin-top: 15%;
  font-size: clamp(32px, 5vw, 68px);
}

.template-preview-subtitle {
  position: relative;
  z-index: 1;
  max-width: 78%;
  margin-top: 12px;
  color: color-mix(in srgb, var(--template-text) 76%, transparent);
  font-size: clamp(9px, 1vw, 14px);
  line-height: 1.45;
}

.template-preview-bullets {
  position: relative;
  z-index: 1;
  display: grid;
  gap: 7px;
  max-width: 84%;
  margin: 18px 0 0;
  padding-left: 16px;
  color: var(--template-text);
  font-size: clamp(9px, 1vw, 14px);
  line-height: 1.35;
}

.template-preview-chapter {
  position: absolute;
  right: 9%;
  bottom: 13%;
  z-index: 1;
  color: var(--template-highlight);
  font-size: clamp(30px, 5vw, 70px);
  font-weight: 800;
  letter-spacing: -.08em;
}

.template-preview-metric {
  position: absolute;
  right: 9%;
  bottom: 14%;
  z-index: 1;
  display: grid;
  gap: 3px;
  padding: 12px 16px;
  border-left: 3px solid var(--template-accent);
  color: var(--template-text);
  background: color-mix(in srgb, var(--template-text) 8%, transparent);
}

.template-preview-metric strong {
  color: var(--template-accent);
  font-size: clamp(24px, 3.8vw, 52px);
  line-height: 1;
}

.template-preview-metric span {
  font-size: 10px;
}

.template-preview-footer {
  position: absolute;
  right: 9%;
  bottom: 7%;
  z-index: 1;
  color: color-mix(in srgb, var(--template-text) 58%, transparent);
  font-size: 9px;
  letter-spacing: .1em;
}

.template-showcase__thumbs {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 6px;
}

.template-preview-thumb {
  min-width: 0;
  padding: 0;
  border: 0;
  background: transparent;
  color: #64748b;
  text-align: left;
  cursor: pointer;
}

.template-preview-thumb__canvas {
  position: relative;
  display: block;
  aspect-ratio: 16 / 9;
  overflow: hidden;
  padding: 8px;
  border: 2px solid transparent;
  background: var(--template-bg);
  color: var(--template-text);
}

.template-preview-thumb__image {
  display: block;
  width: calc(100% + 16px);
  height: calc(100% + 16px);
  margin: -8px;
  object-fit: cover;
}

.template-preview-thumb__canvas::before {
  content: '';
  position: absolute;
  inset: 0 auto 0 0;
  width: 3px;
  background: var(--template-accent);
}

.template-preview-thumb__canvas span {
  display: block;
  font-size: 8px;
  font-weight: 800;
  opacity: .7;
}

.template-preview-thumb__canvas strong {
  display: block;
  max-height: 30px;
  margin-top: 8px;
  overflow: hidden;
  font-size: 9px;
  line-height: 1.2;
}

.template-preview-thumb small {
  display: block;
  margin-top: 4px;
  overflow: hidden;
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.template-preview-thumb.active .template-preview-thumb__canvas {
  border-color: #b83126;
  box-shadow: 0 0 0 2px rgba(184, 49, 38, .12);
}

.template-showcase__note {
  margin-top: 10px;
  font-size: 11px;
  line-height: 1.45;
}

.template-showcase__license-note {
  margin-top: 8px;
  padding: 8px 9px;
  border-left: 3px solid #c48d30;
  color: #7a5c30;
  background: rgba(255, 248, 226, .72);
  font-size: 11px;
  line-height: 1.45;
}

.template-card,
.file-box {
  border: 1px solid #d2cabc;
  border-radius: 10px;
  background: #fbf9f3;
  padding: 0;
  text-align: left;
  cursor: pointer;
  font: inherit;
  transition: transform 0.18s ease, border-color 0.18s ease, box-shadow 0.18s ease, background .18s ease;
  min-width: 0;
}

.template-card {
  min-height: 182px;
}

.template-card.active {
  border-color: #b83126;
  background: #fffaf5;
  box-shadow: 0 0 0 3px rgba(184, 49, 38, .11), 0 12px 24px rgba(95, 55, 31, .12);
}

.template-card:hover {
  transform: translateY(-2px);
  border-color: #bda88f;
  box-shadow: 0 10px 20px rgba(95, 55, 31, .1);
}

.template-card__cover {
  position: relative;
  aspect-ratio: 16 / 9;
  overflow: hidden;
  border-radius: 9px 9px 0 0;
  background: var(--template-bg);
}

.template-card__cover img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.template-card__fallback {
  position: relative;
  display: grid;
  align-content: end;
  height: 100%;
  padding: 13px;
  overflow: hidden;
  color: var(--template-text);
  background-color: var(--template-bg);
}

.template-card__fallback.template-preview-design--dark-tech,
.template-card__fallback.template-preview-design--dark-tech strong {
  color: #ecfeff;
}

.template-card__fallback::before {
  content: '';
  position: absolute;
  inset: 0 auto 0 0;
  width: 4px;
  background: var(--template-accent);
}

.template-card__fallback span {
  position: absolute;
  top: 12px;
  right: 12px;
  color: var(--template-highlight);
  font-size: 11px;
  font-weight: 800;
}

.template-card__fallback strong {
  position: relative;
  z-index: 1;
  color: inherit;
  font-size: 16px;
  line-height: 1.15;
}

.template-card__selected {
  position: absolute;
  top: 9px;
  left: 9px;
  padding: 4px 8px;
  border-radius: 999px;
  color: #fff;
  background: #b83126;
  box-shadow: 0 2px 8px rgba(78, 20, 15, .28);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: .04em;
}

.template-card__body {
  display: grid;
  gap: 5px;
  padding: 11px 12px 12px;
}

.template-card__body > strong,
.file-box strong {
  display: block;
  color: #0f172a;
  overflow-wrap: anywhere;
}

.template-card__body > small {
  display: block;
  color: #64748b;
  font-size: 11px;
  line-height: 1.35;
  display: -webkit-box;
  min-height: 30px;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.file-box span,
.field-hint {
  display: block;
  margin-top: 6px;
  color: #64748b;
  font-size: 13px;
  line-height: 1.5;
}

.swatches {
  display: flex;
  gap: 4px;
  margin-top: 2px;
}

.swatches i {
  width: 13px;
  height: 13px;
  border-radius: 999px;
  border: 1px solid rgba(15, 23, 42, 0.08);
}

.file-box {
  position: relative;
  min-height: 138px;
  padding: 16px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: flex-start;
}

.template-selector summary:focus-visible,
.file-box:focus-within {
  outline: 3px solid rgba(37, 99, 235, .28);
  outline-offset: 3px;
}

.file-box strong {
  margin-top: 8px;
}

.file-box input {
  position: absolute;
  inset: 0;
  opacity: 0;
  cursor: pointer;
}

.actions {
  display: flex;
  gap: 12px;
  margin-top: 24px;
  flex-wrap: wrap;
}

.actions :deep(.n-button) {
  min-width: 0;
}

.actions.centered {
  justify-content: center;
}

.progress-panel,
.result-panel {
  min-height: 420px;
}

.ppt-failure-panel {
  min-height: 420px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  border: 2px solid #d84a3a;
  background: linear-gradient(135deg, #fff7f5 0%, #fbf9f3 76%);
  box-shadow: 4px 4px 0 rgba(184, 49, 38, 0.1), 0 12px 28px rgba(184, 49, 38, 0.12);
}

.failure-hero {
  display: flex;
  align-items: center;
  gap: 18px;
}

.failure-icon {
  flex: 0 0 auto;
  width: 78px;
  height: 78px;
  display: grid;
  place-items: center;
  border-radius: 999px;
  color: #b83126;
  background: #ffe1dc;
  box-shadow: inset 0 0 0 8px rgba(255, 255, 255, 0.66);
}

.failure-eyebrow {
  color: #b83126;
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0.08em;
}

.failure-hero h2 {
  margin: 5px 0 0;
  color: #7d211b;
  font-size: 25px;
}

.failure-hero p {
  margin-top: 6px;
  color: #7b645f;
  font-size: 14px;
  overflow-wrap: anywhere;
}

.failure-reason {
  margin-top: 26px;
  padding: 16px 18px;
  border-left: 4px solid #d84a3a;
  background: rgba(255, 255, 255, 0.78);
}

.failure-reason strong {
  color: #7d211b;
  font-size: 13px;
}

.failure-reason p {
  margin: 7px 0 0;
  color: #5f4843;
  font-size: 14px;
  line-height: 1.65;
  overflow-wrap: anywhere;
}

.failure-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 26px;
}

.failure-actions :deep(.n-button) {
  min-width: 148px;
}

.failure-hint {
  margin: 14px 0 0;
  color: #8c716b;
  font-size: 13px;
}

.progress-title {
  justify-content: flex-start;
  margin-bottom: 22px;
}

.stage-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-top: 22px;
}

.stage-item {
  border: 1px solid #e5eaf2;
  border-radius: 8px;
  padding: 14px 10px;
  color: #64748b;
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: center;
}

.stage-item.active {
  color: #b83126;
  border-color: #d9b5ab;
  background: #f3eadf;
}

.result-panel {
  text-align: center;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
}

.result-panel--wide {
  align-items: stretch;
  text-align: left;
  min-height: 0;
}

.result-toolbar,
.result-heading,
.result-toolbar-actions,
.revision-box__heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
}

.result-heading {
  justify-content: flex-start;
}

.result-heading .result-mark {
  flex: 0 0 auto;
  width: 62px;
  height: 62px;
  margin: 0;
}

.result-toolbar-actions {
  flex-wrap: wrap;
  justify-content: flex-end;
}

.preview-alert {
  margin-top: 18px;
}

.preview-empty {
  margin: 20px 0;
  padding: 32px;
  border: 1px dashed #cbd5e1;
  color: #64748b;
  text-align: center;
}

.html-preview-stage {
  margin-top: 22px;
  border: 1px solid #d2cabc;
  background: #111827;
  box-shadow: 0 8px 24px rgba(55, 45, 30, .14);
}

.html-preview-frame {
  display: block;
  width: 100%;
  aspect-ratio: 16 / 9;
  min-height: 560px;
  border: 0;
  background: #0f172a;
}

.html-preview-stage__caption {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 16px;
  color: #e2e8f0;
  font-size: 12px;
}

.html-preview-stage__caption span {
  color: #94a3b8;
}

.preview-workbench {
  display: grid;
  grid-template-columns: 112px minmax(0, 1fr);
  gap: 16px;
  margin-top: 22px;
  min-width: 0;
}

.preview-rail {
  display: grid;
  align-content: start;
  gap: 10px;
  max-height: 700px;
  overflow-y: auto;
  padding-right: 2px;
}

.preview-thumb {
  position: relative;
  display: block;
  width: 100%;
  padding: 3px 3px 18px;
  border: 1px solid #d2cabc;
  background: #f4efe7;
  cursor: pointer;
}

.preview-thumb.active {
  border-color: #b83126;
  box-shadow: 0 0 0 2px rgba(184, 49, 38, .14);
}

.preview-thumb__canvas {
  display: block;
  width: 100%;
  aspect-ratio: 16 / 9;
  height: auto;
  object-fit: contain;
  background: #fff;
}

.preview-thumb > span {
  position: absolute;
  right: 6px;
  bottom: 3px;
  color: #64748b;
  font-size: 10px;
}

.preview-stage {
  min-width: 0;
}

.preview-stage__canvas-wrap {
  width: 100%;
  aspect-ratio: 16 / 9;
  overflow: hidden;
  border: 1px solid #d2cabc;
  background: #e7e1d8;
  box-shadow: 0 8px 24px rgba(55, 45, 30, .14);
}

.preview-canvas {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.preview-stage__caption {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 10px;
  color: #475569;
  font-size: 12px;
}

.preview-stage__caption > div {
  display: grid;
  gap: 3px;
}

.preview-stage__caption span {
  color: #64748b;
}

.preview-stage__hint {
  white-space: nowrap;
}

.preview-editor {
  grid-column: 1 / -1;
  display: grid;
  grid-template-columns: 1fr 1fr 1.6fr auto;
  gap: 10px;
  align-items: start;
  padding: 14px;
  border: 1px solid #d2cabc;
  background: #f4efe7;
}

.preview-editor__heading {
  display: grid;
  gap: 4px;
  color: #334155;
  font-size: 13px;
}

.preview-editor__heading span {
  color: #64748b;
  font-size: 11px;
}

.preview-editor__nav {
  display: flex;
  gap: 6px;
}

.preview-slide {
  position: relative;
  aspect-ratio: 16 / 9;
  overflow: hidden;
  padding: 22px 24px 18px;
  background: var(--preview-bg);
  color: var(--preview-text);
  border: 1px solid color-mix(in srgb, var(--preview-accent) 35%, #ffffff 65%);
  box-shadow: 0 8px 20px rgba(15, 23, 42, 0.12);
}

.preview-slide::before {
  content: '';
  position: absolute;
  inset: 0 auto 0 0;
  width: 5px;
  background: var(--preview-accent);
}

.preview-slide-index {
  position: absolute;
  top: 10px;
  right: 14px;
  color: color-mix(in srgb, var(--preview-text) 60%, transparent);
  font-size: 10px;
  letter-spacing: .08em;
}

.preview-slide-section {
  color: var(--preview-highlight);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: .1em;
  text-transform: uppercase;
}

.preview-slide h3 {
  max-width: 90%;
  margin-top: 12px;
  color: var(--preview-text);
  font-size: clamp(16px, 2.2vw, 27px);
  line-height: 1.15;
}

.preview-slide-headline {
  color: color-mix(in srgb, var(--preview-text) 72%, transparent);
  font-size: 12px;
  line-height: 1.4;
}

.preview-slide-bullets {
  display: grid;
  gap: 5px;
  margin: 14px 0 0;
  padding-left: 17px;
  color: color-mix(in srgb, var(--preview-text) 88%, transparent);
  font-size: 12px;
  line-height: 1.35;
}

.preview-slide-image {
  display: block;
  max-width: 54%;
  max-height: 44%;
  margin: 10px 0 0 auto;
  object-fit: contain;
  border: 1px solid color-mix(in srgb, var(--preview-accent) 35%, transparent);
}

.preview-slide-metrics {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}

.preview-slide-metrics span {
  display: grid;
  gap: 2px;
  min-width: 0;
  padding: 6px 9px;
  border: 1px solid color-mix(in srgb, var(--preview-accent) 36%, transparent);
  color: color-mix(in srgb, var(--preview-text) 74%, transparent);
  font-size: 9px;
}

.preview-slide-metrics strong {
  color: var(--preview-accent);
  font-size: 16px;
}

.preview-input {
  position: relative;
  z-index: 1;
  margin-top: 8px;
}

.preview-input--title :deep(.n-input__input-el) {
  font-weight: 700;
}

.revision-box {
  margin-top: 24px;
  padding-top: 20px;
  border-top: 1px solid #e2e8f0;
}

.pptd-editor-frame {
  width: 100%;
  height: min(720px, 72vh);
  margin-top: 16px;
  border: 1px solid rgba(148, 163, 184, .3);
  border-radius: 14px;
  background: #090b10;
}

.pptd-editor-frame--expanded {
  height: max(820px, calc(100vh - 92px));
}

.pptd-editor-guide {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 0 0 14px;
}

.pptd-editor-guide span {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 6px 10px 6px 7px;
  border: 1px solid #e1e7ef;
  border-radius: 999px;
  color: #526174;
  background: #f8fafc;
  font-size: 12px;
}

.pptd-editor-guide b {
  display: grid;
  width: 18px;
  height: 18px;
  place-items: center;
  border-radius: 50%;
  color: #fff;
  background: var(--preview-accent);
  font-size: 11px;
}

.pptd-editor-state {
  margin: 12px 0 -4px;
  color: #64748b;
  font-size: 13px;
}

.revision-box__heading {
  align-items: flex-start;
  margin-bottom: 12px;
}

.revision-box__heading h3 {
  font-size: 18px;
}

.revision-box__heading p {
  font-size: 13px;
}

.result-mark {
  width: 82px;
  height: 82px;
  border-radius: 999px;
  display: grid;
  place-items: center;
  background: #ecfdf5;
  color: #047857;
  margin-bottom: 18px;
}

.recent-panel {
  padding: 18px;
  position: sticky;
  top: 88px;
}

.recent-header {
  align-items: center;
  margin-bottom: 14px;
}

.recent-header h2 {
  font-size: 18px;
}

.recent-list {
  display: grid;
  gap: 10px;
}

.recent-item {
  width: 100%;
  border: 1px solid #d2cabc;
  border-radius: 2px;
  background: #fbf9f3;
  padding: 12px;
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 6px 10px;
  text-align: left;
  cursor: pointer;
  min-width: 0;
}

.recent-item strong {
  color: #0f172a;
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}

.recent-item span {
  color: #64748b;
  font-size: 12px;
}

.compact-alert {
  margin-bottom: 12px;
}

@media (max-width: 980px) {
  .workspace {
    grid-template-columns: 1fr;
  }

  .recent-panel {
    position: static;
  }
}

@media (max-width: 720px) {
  .container {
    width: min(100% - 20px, 1180px);
  }

  .panel {
    padding: 18px;
  }

  .panel-header,
  .recent-header {
    flex-direction: column;
  }

  .ppt-page-header {
    flex-direction: column;
  }

  .flow-steps {
    grid-template-columns: 1fr;
  }

  .template-grid,
  .upload-grid,
  .stage-grid,
  .generation-options__grid {
    grid-template-columns: 1fr;
  }

  .resource-field-block {
    padding: 14px;
  }

  .template-selector summary {
    min-height: 64px;
    padding: 10px 13px;
    gap: 10px;
  }

  .template-selector__selection > small {
    display: none;
  }

  .template-selector__body {
    padding: 13px;
  }

  .template-selector__intro {
    align-items: flex-start;
    flex-direction: column;
    gap: 5px;
  }

  .template-groups {
    max-height: 420px;
    padding-right: 5px;
  }

  .generation-options summary > small {
    display: none;
  }

  .template-picker {
    grid-template-columns: 1fr;
  }

  .template-showcase {
    position: static;
  }

  .preview-workbench {
    grid-template-columns: 78px minmax(0, 1fr);
    gap: 10px;
  }

  .html-preview-frame {
    min-height: 360px;
  }

  .html-preview-stage__caption {
    align-items: flex-start;
    flex-direction: column;
  }

  .preview-editor {
    grid-template-columns: 1fr;
  }

  .actions {
    display: grid;
    grid-template-columns: 1fr;
  }

  .actions :deep(.n-button) {
    width: 100%;
  }

  .progress-title {
    align-items: center;
  }

  .ppt-failure-panel {
    align-items: stretch;
  }

  .failure-hero {
    align-items: flex-start;
  }

  .failure-icon {
    width: 60px;
    height: 60px;
  }

  .failure-hero h2 {
    font-size: 21px;
  }

  .failure-actions,
  .failure-actions :deep(.n-button) {
    width: 100%;
  }

  .stage-item {
    justify-content: flex-start;
  }

  .recent-item {
    grid-template-columns: 1fr;
  }

  .result-toolbar,
  .revision-box__heading {
    align-items: flex-start;
    flex-direction: column;
  }

  .pptd-editor-guide {
    gap: 6px;
  }

  .pptd-editor-guide span {
    width: 100%;
  }

  .pptd-editor-frame {
    height: min(760px, 78vh);
    border-radius: 10px;
  }

  .pptd-editor-frame--expanded {
    height: calc(100vh - 24px);
  }

  .result-toolbar-actions {
    justify-content: flex-start;
  }

  .preview-stage__caption {
    align-items: flex-start;
    flex-direction: column;
  }

  .preview-stage__hint {
    white-space: normal;
  }

  .template-showcase__thumbs {
    gap: 4px;
  }

  .template-card,
  .file-box,
  .output-format-card {
    min-height: 44px;
  }
}
</style>
