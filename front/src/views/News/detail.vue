<template>
  <div class="news-detail">
    <!-- 项目记录详情 -->
    <section class="section">
      <div class="container">
        <div class="news-hero">
          <img :src="newsData.image" :alt="newsData.title">
        </div>

        <article class="news-article">
          <div class="article-category">{{ newsData.category }}</div>
          <div class="article-content" v-html="newsData.content"></div>

          <div class="article-share">
            <span>记录入口：</span>
            <n-button circle size="large" class="share-btn">
              <n-icon size="20"><LogoWechat /></n-icon>
            </n-button>
            <n-button circle size="large" class="share-btn">
              <n-icon size="20"><LogoWeibo /></n-icon>
            </n-button>
            <n-button circle size="large" class="share-btn">
              <n-icon size="20"><LogoLinkedin /></n-icon>
            </n-button>
          </div>
        </article>

        <!-- 相关记录 -->
        <div class="related-news">
          <h2>相关记录</h2>
          <div class="related-list">
            <div v-for="item in relatedNews" :key="item.id" class="related-item" @click="navigateTo(`/news/${item.id}`)">
              <img :src="item.image" :alt="item.title">
              <div class="related-content">
                <h3>{{ item.title }}</h3>
                <p>{{ item.summary }}</p>
                <span>{{ item.date }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { NButton, NIcon } from 'naive-ui'
import {
  CalendarOutline,
  PersonOutline,
  EyeOutline,
  LogoWechat,
  ShareSocial,
  LogoLinkedin
} from '@vicons/ionicons5'
import heroImage from '@/assets/images/research-workbench-hero.jpg'
import pipelineImage from '@/assets/images/research-pipeline-panel.jpg'

const router = useRouter()
const route = useRoute()

const LogoWeibo = ShareSocial

const newsData = ref({
  id: 1,
  title: '研究工具台入口整理',
  category: '项目记录',
  date: '2026-06-11',
  author: 'Research Desk',
  views: 1234,
  image: heroImage,
  summary: '把旧模板内容替换为文献、翻译、PPT 和 OpenClaw 工作流入口',
  content: `
    <p>这个页面保留为项目记录详情示例，不再展示无关新闻、获奖或外联内容。</p>
    
    <h3>当前入口</h3>
    <p>站点核心入口包括 Zotero 文献库、BabelDOC 论文翻译、PPT 生成和 OpenClaw 对话。浏览器通过站内 API 访问后端，由后端处理外部服务、密钥和任务队列。</p>
    
    <h3>视觉资产</h3>
    <p>首页和关于页使用本地生成的研究工作台图片，替换随机占位图和办公素材。</p>
    
    <h3>维护原则</h3>
    <p>后续新增功能继续按低资源服务器基线、服务端代理和可复查产物来组织，不恢复通用模板话术。</p>
  `
})

const relatedNews = computed(() => [
  {
    id: 2,
    title: 'PPT 生成链路',
    summary: '论文、提示词和可选模板生成 PPTX',
    date: '2026-06-11',
    image: pipelineImage
  },
  {
    id: 3,
    title: 'PDF 翻译链路',
    summary: 'BabelDOC 输出纯中文或双语 PDF',
    date: '2026-06-11',
    image: heroImage
  },
  {
    id: 4,
    title: 'OpenClaw 对话',
    summary: '站内访问本机会话、模型和产物',
    date: '2026-06-11',
    image: pipelineImage
  }
])

onMounted(() => {
  const id = route.params.id
  console.log('Loading news detail:', id)
})

const navigateTo = (path) => {
  router.push(path)
}
</script>

<style scoped lang="scss">
@use '@/assets/styles/variables' as *;

.news-meta {

      .n-icon {
        font-size: 18px;
      }
    }
  }
}

.news-hero {
  margin-bottom: $spacing-xl;

  img {
    width: 100%;
    border-radius: $border-radius-lg;
    box-shadow: $shadow-lg;
  }
}

.news-article {
  background: #fff;
  padding: $spacing-xxl;
  border-radius: $border-radius-lg;
  box-shadow: $shadow-sm;
  margin-bottom: $spacing-xl;

  .article-category {
    display: inline-block;
    background: linear-gradient(135deg, #1890ff 0%, #722ed1 100%);
    color: #fff;
    padding: $spacing-xs $spacing-sm;
    border-radius: $border-radius-sm;
    font-size: 14px;
    margin-bottom: $spacing-lg;
  }

  .article-content {
    font-size: 16px;
    line-height: 1.8;
    color: $text-color;

    :deep(p) {
      margin-bottom: $spacing-md;
    }

    :deep(h3) {
      font-size: 24px;
      font-weight: 600;
      margin: $spacing-xl 0 $spacing-md;
      color: $primary-color;
    }
  }

  .article-share {
    display: flex;
    align-items: center;
    gap: $spacing-md;
    margin-top: $spacing-xl;
    padding-top: $spacing-xl;
    border-top: 1px solid $border-color;

    span {
      font-weight: 600;
    }

    .share-btn {
      transition: all $transition-fast;

      &:hover {
        transform: translateY(-2px);
      }
    }
  }
}

.related-news {
  h2 {
    font-size: 28px;
    font-weight: 600;
    margin-bottom: $spacing-lg;
    color: $primary-color;
  }

  .related-list {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: $spacing-lg;

    .related-item {
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
        height: 150px;
        object-fit: cover;
      }

      .related-content {
        padding: $spacing-md;

        h3 {
          font-size: 16px;
          font-weight: 600;
          margin-bottom: $spacing-sm;
          @include text-ellipsis(2);
        }

        p {
          color: $text-color-secondary;
          font-size: 14px;
          margin-bottom: $spacing-sm;
          @include text-ellipsis(2);
        }

        span {
          color: $text-color-light;
          font-size: 12px;
        }
      }
    }
  }
}

@media (max-width: 992px) {
  .related-list {
    grid-template-columns: repeat(2, 1fr) !important;
  }
}

@media (max-width: 768px) {
  .news-article {
    padding: $spacing-lg;
  }

  .related-list {
    grid-template-columns: 1fr !important;
  }
}
</style>
