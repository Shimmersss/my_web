<template>
  <div class="home-page">
    <!-- Hero Banner -->
    <section class="hero-section">
      <div class="hero-bg-slide"></div>
      <div class="hero-bg-slide hero-bg-slide-2"></div>
      <div class="hero-bg-slide hero-bg-slide-3"></div>
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

    <!-- 核心业务板块 -->
    <section class="section services-section">
      <div class="container">
        <h2 class="section__title">{{ $t('home.services.title') }}</h2>
        <p class="section__subtitle">{{ $t('home.services.subtitle') }}</p>
        <n-grid :x-gap="24" :y-gap="24" :cols="3" :cols-md="2" :cols-sm="1">
          <n-grid-item v-for="service in services" :key="service.id">
            <div class="service-card" @click="navigateTo(`/business/${service.id}`)">
              <div class="service-icon" :style="{ backgroundColor: service.color }">
                <n-icon size="40" color="#fff">
                  <component :is="service.icon" />
                </n-icon>
              </div>
              <h3 class="service-title">{{ service.title }}</h3>
              <p class="service-desc">{{ service.description }}</p>
              <n-button text type="primary" class="service-btn">
                {{ $t('common.readMore') }} →
              </n-button>
            </div>
          </n-grid-item>
        </n-grid>
      </div>
    </section>

    <!-- 企业优势 -->
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

    <!-- 客户案例轮播 -->
    <section class="section cases-section">
      <div class="container">
        <h2 class="section__title">{{ $t('home.cases.title') }}</h2>
        <p class="section__subtitle">{{ $t('home.cases.subtitle') }}</p>
        <n-carousel show-dots>
          <div class="carousel-item" v-for="caseItem in cases" :key="caseItem.id">
            <div class="case-card">
              <div class="case-image">
                <img :src="caseItem.image" :alt="caseItem.title">
              </div>
              <div class="case-content">
                <h3>{{ caseItem.title }}</h3>
                <p>{{ caseItem.description }}</p>
                <n-button text type="primary" @click="navigateTo(`/cases/${caseItem.id}`)">
                  {{ $t('common.readMore') }}
                </n-button>
              </div>
            </div>
          </div>
        </n-carousel>
      </div>
    </section>

    <!-- GitHub 项目开源 -->
    <section class="section news-section">
      <div class="container">
        <h2 class="section__title">{{ $t('home.news.title') }}</h2>
        <p class="section__subtitle">{{ $t('home.news.subtitle') }}</p>
        <n-grid :x-gap="24" :y-gap="24" :cols="3" :cols-md="2" :cols-sm="1">
          <n-grid-item v-for="project in projectList" :key="project.repo">
            <div class="news-card" @click="navigateTo('/news')">
              <div class="news-image">
                <div class="project-preview">
                  <n-icon size="54"><LogoGithub /></n-icon>
                  <span>{{ project.category }}</span>
                </div>
                <div class="news-date">{{ project.repo.split('/')[0] }}</div>
              </div>
              <div class="news-content">
                <h3>{{ project.repo.split('/')[1] }}</h3>
                <p>{{ project.highlight }}</p>
              </div>
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
              <p>上传论文与可选模板，让 mimo 规划结构并生成可编辑 PPTX。</p>
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
                    <p>可上传 PPTX 模板抽取配色和素材</p>
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
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  NButton, NGrid, NGridItem, NCarousel, NIcon
} from 'naive-ui'
import {
  BusinessOutline,
  CloudOutline,
  AnalyticsOutline,
  BuildOutline,
  StarOutline,
  PeopleOutline,
  ShieldCheckmarkOutline,
  FlashOutline,
  DocumentTextOutline,
  LogoGithub
} from '@vicons/ionicons5'
import { getGithubProjects } from '@/api'
import { defaultGithubProjects } from '@/config/githubProjects'

const router = useRouter()

const services = ref([
  { id: 1, title: '云计算服务', description: '提供稳定可靠的云计算解决方案', icon: CloudOutline, color: '#1890ff' },
  { id: 2, title: '数据分析', description: '专业的数据分析与商业智能服务', icon: AnalyticsOutline, color: '#52c41a' },
  { id: 3, title: '技术咨询', description: '全方位的技术咨询与架构设计', icon: BuildOutline, color: '#faad14' },
  { id: 4, title: '人工智能', description: 'AI驱动的智能化解决方案', icon: BusinessOutline, color: '#722ed1' },
  { id: 5, title: '移动开发', description: '跨平台移动应用开发服务', icon: CloudOutline, color: '#eb2f96' },
  { id: 6, title: '安全服务', description: '企业级安全防护体系', icon: ShieldCheckmarkOutline, color: '#fa541c' }
])

const advantages = ref([
  { id: 1, title: '专业团队', description: '10年+行业经验', icon: PeopleOutline },
  { id: 2, title: '技术创新', description: '领先的技术栈', icon: FlashOutline },
  { id: 3, title: '品质保证', description: 'ISO质量认证', icon: StarOutline },
  { id: 4, title: '贴心服务', description: '24小时技术支持', icon: BusinessOutline }
])

const cases = ref([
  { id: 1, title: '某大型企业数字化转型', description: '帮助客户实现业务流程数字化', image: 'https://picsum.photos/800/400?random=1' },
  { id: 2, title: '智能客服系统', description: 'AI驱动的智能客服解决方案', image: 'https://picsum.photos/800/400?random=2' },
  { id: 3, title: '电商平台搭建', description: '高性能电商系统开发', image: 'https://picsum.photos/800/400?random=3' }
])

const projectList = ref(defaultGithubProjects.filter(item => item.featured).slice(0, 3))

const BusinessIcon = BusinessOutline
const DocumentTextIcon = DocumentTextOutline

const navigateTo = (path) => {
  router.push(path)
}

onMounted(async () => {
  try {
    const res = await getGithubProjects()
    if (res.code === 200 && Array.isArray(res.data) && res.data.length) {
      projectList.value = res.data.filter(item => item.featured).slice(0, 3)
    }
  } catch (e) {
    projectList.value = defaultGithubProjects.filter(item => item.featured).slice(0, 3)
  }
})
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
    background-image: url('https://images.unsplash.com/photo-1497366216548-37526070297c?w=1920');
    background-size: cover;
    background-position: center;
    opacity: 1;
    animation: slideShow 18s infinite;
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

  .hero-bg-slide-2 {
    background-image: url('https://images.unsplash.com/photo-1486406146926-c627a92ad1ab?w=1920');
    animation-delay: 6s;
    opacity: 0;
  }

  .hero-bg-slide-3 {
    background-image: url('https://images.unsplash.com/photo-1497366811353-6870744d04b2?w=1920');
    animation-delay: 12s;
    opacity: 0;
  }

  @keyframes slideShow {
    0%, 30% {
      opacity: 1;
      transform: scale(1);
    }
    35%, 65% {
      opacity: 0;
      transform: scale(1.1);
    }
    70%, 100% {
      opacity: 0;
      transform: scale(1);
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

// 核心业务板块
.services-section {
  .service-card {
    text-align: center;
    padding: $spacing-xl;
    background: #fff;
    border-radius: $border-radius-lg;
    box-shadow: $shadow-sm;
    transition: all $transition-base;
    cursor: pointer;
    height: 100%;

    &:hover {
      transform: translateY(-6px);
      box-shadow: $shadow-md;
    }

    .service-icon {
      width: 72px;
      height: 72px;
      border-radius: 50%;
      display: flex;
      align-items: center;
      justify-content: center;
      margin: 0 auto $spacing-md;
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

// 企业优势
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

// 客户案例轮播
.cases-section {
  :deep(.n-carousel) {
    .n-carousel__slides {
      padding: $spacing-md 0;
    }
  }

  .carousel-item {
    padding: 0 $spacing-md;
  }

  .case-card {
    background: #fff;
    border-radius: $border-radius-lg;
    overflow: hidden;
    box-shadow: $shadow-sm;
    display: flex;
    height: 420px;

    .case-image {
      flex: 1;
      overflow: hidden;

      img {
        width: 100%;
        height: 100%;
        object-fit: cover;
      }
    }

    .case-content {
      flex: 1;
      padding: $spacing-xl;
      display: flex;
      flex-direction: column;
      justify-content: center;

      h3 {
        font-size: $font-size-title-h2;
        font-weight: 600;
        margin-bottom: $spacing-md;
        line-height: $line-height;
      }

      p {
        color: $text-color-secondary;
        margin-bottom: $spacing-md;
        line-height: $line-height;
        font-size: $font-size-base;
      }
    }
  }
}

// GitHub 项目开源
.news-section {
  background: $bg-color-secondary;

  .news-card {
    background: #fff;
    border-radius: $border-radius-lg;
    overflow: hidden;
    box-shadow: $shadow-sm;
    cursor: pointer;
    transition: all $transition-base;
    height: 100%;
    display: flex;
    flex-direction: column;

    &:hover {
      transform: translateY(-4px);
      box-shadow: $shadow-md;
    }

    .news-image {
      position: relative;
      height: 200px;
      overflow: hidden;
      flex-shrink: 0;

      img {
        width: 100%;
        height: 100%;
        object-fit: cover;
        transition: transform $transition-base;
      }

      &:hover img {
        transform: scale(1.08);
      }

      .project-preview {
        width: 100%;
        height: 100%;
        display: grid;
        place-items: center;
        gap: $spacing-sm;
        align-content: center;
        color: #fff;
        background:
          radial-gradient(circle at 20% 20%, rgba(24, 144, 255, 0.28), transparent 28%),
          linear-gradient(135deg, #24292f 0%, #334155 58%, #0f766e 100%);

        span {
          font-size: 13px;
          font-weight: 700;
          background: rgba(255, 255, 255, 0.14);
          border: 1px solid rgba(255, 255, 255, 0.24);
          border-radius: $border-radius-sm;
          padding: 3px 9px;
        }
      }

      .news-date {
        position: absolute;
        top: $spacing-sm;
        right: $spacing-sm;
        background: $primary-color;
        color: #fff;
        padding: $spacing-xs $spacing-sm;
        border-radius: $border-radius-sm;
        font-size: $font-size-caption;
        font-weight: 500;
      }
    }

    .news-content {
      padding: $spacing-lg;
      flex-grow: 1;
      display: flex;
      flex-direction: column;

      h3 {
        font-size: $font-size-title-h3;
        font-weight: 600;
        margin-bottom: $spacing-sm;
        line-height: $line-height;
        flex-grow: 1;
      }

      p {
        color: $text-color-secondary;
        font-size: $font-size-base;
        line-height: $line-height;
      }
    }
  }
}

// 联系方式
.contact-section {
  background: linear-gradient(135deg, #1890ff 0%, #722ed1 100%);
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

  .case-card {
    flex-direction: column;
    height: auto;

    .case-image {
      height: 280px;
    }
  }
}

@media (max-width: 768px) {
  .hero-section {
    height: calc(100vh - 64px);
    padding: $spacing-xl 0;

    .hero-bg-slide {
      animation: slideShowMobile 18s infinite;
    }

    @keyframes slideShowMobile {
      0%, 30% {
        transform: translateX(-50%);
      }
      35%, 65% {
        transform: translateX(0%);
      }
      70%, 100% {
        transform: translateX(50%);
      }
    }
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

  .case-card {
    .case-image {
      height: 220px;
    }

    .case-content {
      padding: $spacing-lg;

      h3 {
        font-size: $font-size-title-h2;
      }
    }
  }

  .news-image {
    height: 180px;
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
    padding: $spacing-md;
  }

  .contact-form {
    padding: $spacing-lg;
  }
}
</style>
