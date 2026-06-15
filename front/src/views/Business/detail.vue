<template>
  <div class="business-detail">
    <!-- 工具详情 -->
    <section class="section">
      <div class="container">
        <div class="detail-content">
          <div class="detail-image">
            <img :src="businessData.image" :alt="businessData.title">
          </div>
          <div class="detail-info">
            <h2>工具介绍</h2>
            <p>{{ businessData.introduction }}</p>

            <h3>核心能力</h3>
            <ul class="feature-list">
              <li v-for="feature in businessData.features" :key="feature">
                <n-icon><CheckmarkCircleOutline /></n-icon>
                {{ feature }}
              </li>
            </ul>

            <h3>使用场景</h3>
            <div class="scenarios">
              <n-tag v-for="scenario in businessData.scenarios" :key="scenario" type="info" size="large">
                {{ scenario }}
              </n-tag>
            </div>

            <div class="detail-actions">
              <n-button type="primary" size="large" @click="navigateTo('/contact')">
                打开 PPT 生成
              </n-button>
              <n-button size="large" @click="navigateTo('/contact')">
                PPT 生成
              </n-button>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- 相关工具 -->
    <section class="section" style="background: #f5f5f5">
      <div class="container">
        <h2 class="section__title">相关工具</h2>
        <n-grid :x-gap="24" :y-gap="24" :cols="3">
          <n-grid-item v-for="item in relatedBusiness" :key="item.id">
            <div class="related-card" @click="navigateTo(`/business/${item.id}`)">
              <img :src="item.image" :alt="item.title">
              <h3>{{ item.title }}</h3>
              <p>{{ item.description }}</p>
            </div>
          </n-grid-item>
        </n-grid>
      </div>
    </section>

  </div>
</template>

<script setup>
import { ref, onMounted, computed, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { NButton, NGrid, NGridItem, NTag, NIcon } from 'naive-ui'
import { CheckmarkCircleOutline } from '@vicons/ionicons5'
import heroImage from '@/assets/images/research-workbench-hero.jpg'
import pipelineImage from '@/assets/images/research-pipeline-panel.jpg'

const router = useRouter()
const route = useRoute()

const toolModules = [
  {
    id: 1,
    title: 'Zotero 文献库',
    description: '展示私有 Zotero 库、附件代理和引用导出',
    introduction: '文献库页面由后端缓存 Zotero Web API 数据，浏览器只请求站内接口。附件代理会处理 Zotero 的 S3 跳转、ZIP 打包和真实 content-type 推断，适合直接查看 PDF、Markdown 和网页快照。',
    features: ['启动预热与定时刷新', '母条目和孤立附件统一渲染', 'PDF / Markdown 附件代理', 'BibTeX / RIS / APA 引用导出'],
    scenarios: ['论文检索', '附件预览', '引用整理', '研究资料归档'],
    image: pipelineImage
  },
  {
    id: 2,
    title: 'PDF 论文翻译',
    description: '按页面范围生成保留版式的中文或双语 PDF',
    introduction: '翻译页面以 BabelDOC 为主链路，先上传 PDF 读取页数，再选择页面范围、字体族和速度模式。翻译结果落盘保存，可预览纯中文或双语 PDF，也可下载从最终 PDF 提取的中文 TXT。',
    features: ['页面范围选择', '纯中文 / 双语 PDF 缓存', 'SSE 实时进度', '加速模式资源保护与稳定模式降级'],
    scenarios: ['论文精读', '英文资料翻译', '版式复查', '中文文本提取'],
    image: heroImage
  },
  {
    id: 3,
    title: 'PPT 生成',
    description: '论文、提示词和 PPTX 模板生成可编辑演示稿',
    introduction: 'PPT 生成页保留 /contact URL，支持仅提示词、提示词加论文、提示词加论文加模板。后端抽取论文文本和图片，调用 mimo 规划结构，再通过自由 renderer 或原生模板填充链路输出 PPTX。',
    features: ['PDF / DOCX 论文解析', 'PPTX 模板风格继承', '论文图片和表格受控分配', '任务令牌保护下载'],
    scenarios: ['毕业答辩', '论文分享', '项目汇报', '模板化演示稿'],
    image: pipelineImage
  },
  {
    id: 4,
    title: 'GitHub 项目展示',
    description: '后端代理仓库元数据、README 和展示配置',
    introduction: 'GitHub 项目页通过后端代理仓库元数据和 README。前端不直接请求 api.github.com 或 raw.githubusercontent.com，管理配置仍由 ADMIN_KEY 保护。',
    features: ['ADMIN_KEY 管理保护', '仓库元数据补全', 'README 代理', '展示配置落盘'],
    scenarios: ['项目作品集', 'README 展示', '开源记录', '展示配置维护'],
    image: heroImage
  },
  {
    id: 5,
    title: 'GitHub 项目展示',
    description: '后端代理仓库元数据和 README',
    introduction: '开源项目页面当前入口折叠保留，适合后续重新展示精选仓库。数据由后端读取本地展示配置，并代理 GitHub API 和 raw README，避免浏览器直连外部接口。',
    features: ['仓库配置落盘', 'README 代理', 'stars / forks / language 补全', '管理员保存配置'],
    scenarios: ['项目作品集', 'README 展示', '开源记录', '后续入口恢复'],
    image: pipelineImage
  },
  {
    id: 6,
    title: '部署与资源保护',
    description: '围绕 2 核 4GB 服务器限制控制队列、内存和文件保留',
    introduction: '生产环境按 2 核 CPU / 4 GB 内存设计。翻译和 PPT 都使用单 worker 与有界队列，大文件落盘到 .run 目录，部署脚本覆盖 jar 前先停止服务并检查 uv 等运行依赖。',
    features: ['单 worker 有界队列', '任务结果落盘', '部署前依赖检查', '内存与 swap 阈值保护'],
    scenarios: ['公网部署', '低资源运行', '任务排障', '热修回流'],
    image: heroImage
  }
]

const businessData = ref(toolModules[0])

const relatedBusiness = computed(() => [
  ...toolModules.filter(item => item.id !== Number(route.params.id || 1)).slice(0, 3)
])

const syncBusinessData = () => {
  const id = Number(route.params.id || 1)
  businessData.value = toolModules.find(item => item.id === id) || toolModules[0]
}

onMounted(syncBusinessData)
watch(() => route.params.id, syncBusinessData)

const navigateTo = (path) => {
  router.push(path)
}
</script>

<style scoped lang="scss">
@use '@/assets/styles/variables' as *;

.detail-content {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: $spacing-xl;
  align-items: start;

  .detail-image {
    img {
      width: 100%;
      border-radius: $border-radius-lg;
      box-shadow: $shadow-lg;
    }
  }

  .detail-info {
    h2 {
      font-size: 32px;
      font-weight: 600;
      margin-bottom: $spacing-md;
      color: $primary-color;
    }

    h3 {
      font-size: 24px;
      font-weight: 600;
      margin: $spacing-xl 0 $spacing-md;
    }

    p {
      font-size: 16px;
      line-height: 1.8;
      color: $text-color-secondary;
      margin-bottom: $spacing-md;
    }

    .feature-list {
      list-style: none;
      padding: 0;

      li {
        display: flex;
        align-items: center;
        gap: $spacing-sm;
        margin-bottom: $spacing-sm;
        color: $text-color;
        font-size: 16px;

        .n-icon {
          flex-shrink: 0;
          color: $success-color;
          font-size: 20px;
        }
      }
    }

    .scenarios {
      display: flex;
      flex-wrap: wrap;
      gap: $spacing-sm;
      margin-bottom: $spacing-xl;
    }

    .detail-actions {
      display: flex;
      gap: $spacing-md;
    }
  }
}

.related-card {
  background: #fff;
  border-radius: $border-radius-lg;
  overflow: hidden;
  box-shadow: $shadow-sm;
  cursor: pointer;
  transition: all $transition-base;

  &:hover {
    transform: translateY(-4px);
    box-shadow: $shadow-md;
  }

  img {
    width: 100%;
    height: 200px;
    object-fit: cover;
  }

  h3 {
    font-size: 18px;
    font-weight: 600;
    margin: $spacing-md 0 $spacing-sm;
    padding: 0 $spacing-md;
  }

  p {
    color: $text-color-secondary;
    font-size: 14px;
    padding: 0 $spacing-md $spacing-md;
    line-height: 1.6;
  }
}

@media (max-width: 992px) {
  .detail-content {
    grid-template-columns: 1fr;
  }
}
</style>
