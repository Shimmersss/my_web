<template>
  <div class="home-page">
    <!-- Hero Banner -->
    <section class="hero-section">
      <div class="hero-bg-slide"></div>
      <div class="container">
        <div class="hero-content">
          <h1 class="hero-title">{{ $t('home.hero.title') }}</h1>
          <p class="hero-subtitle">{{ $t('home.hero.subtitle') }}</p>
          <n-button type="primary" size="large" @click="navigateTo('/contact')" class="hero-btn">
            {{ $t('home.hero.cta') }}
          </n-button>
        </div>
      </div>
    </section>

    <!-- 核心工具板块 -->
    <section class="section services-section">
      <div class="container">
        <h2 class="section__title">{{ $t('home.services.title') }}</h2>
        <p class="section__subtitle">{{ $t('home.services.subtitle') }}</p>
        <n-grid :x-gap="24" :y-gap="24" :cols="3" :cols-md="2" :cols-sm="1">
          <n-grid-item v-for="service in services" :key="service.id">
            <a class="service-card" :href="`/business/${service.id}`" @click.prevent="navigateTo(`/business/${service.id}`)">
              <div class="service-image">
                <img :src="service.image" :alt="service.title">
              </div>
              <div class="service-content">
                <h3 class="service-title">{{ service.title }}</h3>
                <p class="service-desc">{{ service.description }}</p>
                <span class="service-btn">{{ $t('common.readMore') }} →</span>
              </div>
            </a>
          </n-grid-item>
        </n-grid>
      </div>
    </section>

    <!-- 工作方式 -->
    <section class="section advantages-section">
      <div class="container">
        <h2 class="section__title">{{ $t('home.advantages.title') }}</h2>
        <p class="section__subtitle">{{ $t('home.advantages.subtitle') }}</p>
        <n-grid :x-gap="24" :y-gap="24" :cols="4" :cols-md="2" :cols-sm="1">
          <n-grid-item v-for="advantage in advantages" :key="advantage.id">
            <div class="advantage-card">
              <div class="advantage-icon">
                <n-icon size="48" color="#1890ff">
                  <component :is="advantage.icon" />
                </n-icon>
              </div>
              <h3 class="advantage-title">{{ advantage.title }}</h3>
              <p class="advantage-desc">{{ advantage.description }}</p>
            </div>
          </n-grid-item>
        </n-grid>
      </div>
    </section>

    <!-- PPT 生成入口 -->
    <section class="section contact-section">
      <div class="container">
        <n-grid :x-gap="48" :y-gap="24" :cols="2" :cols-sm="1">
          <n-grid-item>
            <div class="contact-info">
              <h2>{{ $t('contact.title') }}</h2>
              <p>把论文、模板和提示词交给后端队列，让 mimo 规划结构并生成可编辑 PPTX。</p>
              <div class="contact-items">
                <div class="contact-item">
                  <n-icon size="24"><DocumentTextIcon /></n-icon>
                  <div>
                    <span>论文输入</span>
                    <p>支持 PDF / DOCX，也可以只输入提示词</p>
                  </div>
                </div>
                <div class="contact-item">
                  <n-icon size="24"><BusinessIcon /></n-icon>
                  <div>
                    <span>模板风格</span>
                    <p>可上传 PPTX 模板继承版式与配色</p>
                  </div>
                </div>
                <div class="contact-item">
                  <n-icon size="24"><FlashOutline /></n-icon>
                  <div>
                    <span>后台队列</span>
                    <p>单 worker 有界队列，生成完成后下载 PPTX</p>
                  </div>
                </div>
              </div>
            </div>
          </n-grid-item>
          <n-grid-item>
            <div class="contact-form">
              <h3>从论文到答辩稿</h3>
              <p>适合毕业答辩、项目汇报、论文分享等场景。页面保留 `/contact` URL，点击即可开始生成。</p>
              <n-button type="primary" block size="large" @click="navigateTo('/contact')">
                <template #icon>
                  <n-icon><DocumentTextIcon /></n-icon>
                </template>
                打开 PPT 生成
              </n-button>
            </div>
          </n-grid-item>
        </n-grid>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  NButton, NGrid, NGridItem, NIcon
} from 'naive-ui'
import {
  BusinessOutline,
  StarOutline,
  PeopleOutline,
  FlashOutline,
  DocumentTextOutline
} from '@vicons/ionicons5'
import literatureImage from '@/assets/images/home-literature-library.jpg'
import translationImage from '@/assets/images/home-pdf-translation.jpg'
import pptImage from '@/assets/images/home-ppt-generation.jpg'
import openClawImage from '@/assets/images/home-openclaw-chat.jpg'
import openSourceImage from '@/assets/images/home-open-source.jpg'
import backendQueueImage from '@/assets/images/home-backend-queue.jpg'

const router = useRouter()

const services = ref([
  { id: 1, title: 'Zotero 文献库', description: '缓存 Zotero 条目、分组和附件，快速检索论文资料', image: literatureImage },
  { id: 2, title: 'PDF 论文翻译', description: 'BabelDOC 保留版式输出中文或双语 PDF', image: translationImage },
  { id: 3, title: 'PPT 自动生成', description: '论文、提示词和模板直出可编辑答辩 PPTX', image: pptImage },
  { id: 4, title: 'OpenClaw 对话', description: '站内访问本机 OpenClaw Gateway 会话和产物', image: openClawImage },
  { id: 5, title: '开源项目展示', description: '由后端代理 GitHub 仓库数据和 README', image: openSourceImage },
  { id: 6, title: '受控后台队列', description: '按 2 核 4GB 服务器基线控制并发、内存和历史记录', image: backendQueueImage }
])

const advantages = ref([
  { id: 1, title: '个人研究流', description: '围绕论文阅读、翻译、答辩和实验汇报组织入口', icon: PeopleOutline },
  { id: 2, title: '服务端代理', description: '浏览器只访问站内 API，密钥和外部请求留在后端', icon: FlashOutline },
  { id: 3, title: '文件落盘', description: '大 PDF、PPTX、图片和任务结果不长期占用 JVM 内存', icon: StarOutline },
  { id: 4, title: '可复查产物', description: '任务目录保留 manifest、日志和输出文件，便于定位问题', icon: BusinessOutline }
])

const BusinessIcon = BusinessOutline
const DocumentTextIcon = DocumentTextOutline

const navigateTo = (path) => {
  router.push(path)
}
</script>

<style scoped lang="scss">
@use '@/assets/styles/variables' as *;

.home-page {
  padding-bottom: 0;
}

// Hero Section
.hero-section {
  position: relative;
  height: calc(100vh - 72px);
  min-height: 600px;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;

  .hero-bg-slide {
    position: absolute;
    top: 0;
    left: 0;
    right: 0;
    bottom: 0;
    background-image: url('@/assets/images/research-workbench-hero.jpg');
    background-size: cover;
    background-position: center;
    opacity: 1;
    z-index: 0;

    &::after {
      content: '';
      position: absolute;
      top: 0;
      left: 0;
      right: 0;
      bottom: 0;
      background: rgba(0, 0, 0, 0.45);
    }
  }

  .container {
    position: relative;
    z-index: 1;
  }
}

.hero-content {
  text-align: center;
  color: #fff;

  .hero-title {
    font-size: 48px;
    font-weight: 700;
    line-height: $line-height-title;
    margin-bottom: $spacing-md;
    color: #fff;
  }

  .hero-subtitle {
    font-size: $font-size-large;
    color: rgba(255, 255, 255, 0.95);
    margin-bottom: $spacing-xl;
    line-height: $line-height;
  }

  .hero-btn {
    font-size: $font-size-base;
    padding: $spacing-md $spacing-xxl;
    font-weight: 500;
  }
}

// 统一的 Section 样式
.section {
  padding: $spacing-section 0;

  &__title {
    font-size: $font-size-title-h1;
    font-weight: 600;
    text-align: center;
    margin-bottom: $spacing-sm;
    color: #000000;
    line-height: $line-height-title;
  }

  &__subtitle {
    font-size: $font-size-base;
    color: $text-color-secondary;
    text-align: center;
    margin-bottom: $spacing-xl;
    line-height: $line-height;
  }
}

// 核心工具板块
.services-section {
  .service-card {
    color: inherit;
    text-decoration: none;
    background: #fff;
    border-radius: $border-radius-lg;
    box-shadow: $shadow-sm;
    transition: all $transition-base;
    cursor: pointer;
    height: 100%;
    overflow: hidden;

    &:hover {
      transform: translateY(-6px);
      box-shadow: $shadow-md;

      .service-image img {
        transform: scale(1.04);
      }
    }

    &:focus-visible {
      outline: 3px solid rgba(24, 144, 255, 0.32);
      outline-offset: 4px;
    }

    .service-image {
      aspect-ratio: 4 / 3;
      overflow: hidden;
      background: #eef2f7;

      img {
        width: 100%;
        height: 100%;
        object-fit: cover;
        transition: transform $transition-base;
      }
    }

    .service-content {
      padding: $spacing-lg;
    }

    .service-title {
      font-size: $font-size-title-h2;
      font-weight: 600;
      margin-bottom: $spacing-sm;
      line-height: $line-height;
    }

    .service-desc {
      color: $text-color-secondary;
      margin-bottom: $spacing-md;
      line-height: $line-height;
      font-size: $font-size-base;
    }
  }
}

// 工作方式
.advantages-section {
  background: $bg-color-secondary;

  .advantage-card {
    text-align: center;
    padding: $spacing-xl;
    height: 100%;

    .advantage-icon {
      margin-bottom: $spacing-md;
    }

    .advantage-title {
      font-size: $font-size-title-h3;
      font-weight: 600;
      margin-bottom: $spacing-sm;
      line-height: $line-height;
    }

    .advantage-desc {
      color: $text-color-secondary;
      font-size: $font-size-base;
      line-height: $line-height;
    }
  }
}

// PPT 生成入口
.contact-section {
  background: linear-gradient(135deg, #155e75 0%, #365314 52%, #713f12 100%);
  color: #fff;

  .container {
    :deep(.n-grid) {
      color: #fff;
    }
  }

  .contact-info {
    h2 {
      font-size: $font-size-title-h1;
      font-weight: 600;
      margin-bottom: $spacing-md;
      color: #fff;
      line-height: $line-height-title;
    }

    p {
      color: rgba(255, 255, 255, 0.95);
      margin-bottom: $spacing-xl;
      font-size: $font-size-large;
      line-height: $line-height;
    }

    .contact-items {
      .contact-item {
        display: flex;
        align-items: flex-start;
        gap: $spacing-md;
        margin-bottom: $spacing-lg;

        .n-icon {
          flex-shrink: 0;
          color: rgba(255, 255, 255, 0.95);
        }

        span {
          font-weight: 600;
          display: block;
          margin-bottom: $spacing-xs;
          color: rgba(255, 255, 255, 0.95);
          font-size: $font-size-base;
        }

        p {
          color: rgba(255, 255, 255, 0.85);
          margin: 0;
          font-size: $font-size-base;
          line-height: $line-height;
        }
      }
    }
  }

  .contact-form {
    background: #fff;
    padding: $spacing-xl;
    border-radius: $border-radius-lg;
    box-shadow: $shadow-lg;

    :deep(.n-form-item-label) {
      color: $text-color;
      font-weight: 500;
      font-size: $font-size-base;
    }
  }
}

// 响应式设计
@media (max-width: 992px) {
  .hero-section {
    height: calc(100vh - 72px);
    min-height: 500px;

    .hero-content {
      text-align: center;
    }
  }

}

@media (max-width: 768px) {
  .hero-section {
    height: calc(100vh - 64px);
    padding: $spacing-xl 0;

  }

  .hero-content {
    text-align: center;
    margin-bottom: $spacing-xl;

    .hero-title {
      font-size: 36px;
    }

    .hero-subtitle {
      font-size: $font-size-base;
    }
  }

  .section {
    &__title {
      font-size: 32px;
    }

    &__subtitle {
      font-size: $font-size-base;
    }
  }

  .contact-section {
    .contact-info {
      h2 {
        font-size: 32px;
      }

      p {
        font-size: $font-size-base;
      }
    }
  }
}

@media (max-width: 480px) {
  .hero-content {
    .hero-title {
      font-size: 28px;
    }
  }

  .service-card {
    .service-content {
      padding: $spacing-md;
    }
  }

  .contact-form {
    padding: $spacing-lg;
  }
}
</style>
