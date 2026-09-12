<template>
  <footer class="app-footer">
    <div class="container footer-content">
      <div class="footer-section">
        <div class="footer-logo">
          <span class="footer-mark" aria-hidden="true"></span>
          <span class="logo-text">Research Desk</span>
        </div>
        <p class="footer-description">把文献、翻译与表达工具，放在一个安静的工作台里。</p>
        <span class="footer-meta">研究工具台 · 持续试运营</span>
      </div>

      <div class="footer-section">
        <h4 class="footer-title">快捷入口</h4>
        <ul class="footer-links">
          <li><a href="/" @click.prevent="navigateTo('/')">{{ $t('common.home') }}</a></li>
          <li><a href="/publications" @click.prevent="navigateTo('/publications')">文献</a></li>
          <li><a href="/translate" @click.prevent="navigateTo('/translate')">翻译</a></li>
          <li><a href="/contact" @click.prevent="navigateTo('/contact')">PPT 生成</a></li>
          <li><a href="/image-generate" @click.prevent="navigateTo('/image-generate')">GPT 生图</a></li>
          <li><a href="/matchmaking-report" @click.prevent="navigateTo('/matchmaking-report')">月下会客厅</a></li>
          <li><a href="/news" @click.prevent="navigateTo('/news')">GitHub 项目</a></li>
          <li><a href="/guestbook" @click.prevent="navigateTo('/guestbook')">留言板</a></li>
        </ul>
      </div>

    </div>

    <div class="footer-bottom">
      <div class="container">
        <p class="copyright">© 2026 Research Desk. Personal research tools.</p>
      </div>
    </div>
  </footer>
</template>

<script setup>
import { useRouter } from 'vue-router'

const router = useRouter()

const navigateTo = (path) => {
  router.push(path)
}
</script>

<style scoped lang="scss">
@use '@/assets/styles/variables' as *;

.app-footer {
  background: #e8e1d5;
  color: #333;
  padding: $spacing-section 0 0;

  .footer-content {
    display: grid;
    grid-template-columns: minmax(0, 2fr) minmax(180px, 1fr);
    gap: $spacing-xl;
    margin-bottom: $spacing-xl;
  }

  .footer-section {
    &:first-child {
      flex: 1.5;
    }

    &:not(:first-child) {
      flex: 1;
    }
  }

  .footer-logo {
    display: flex;
    align-items: center;
    gap: $spacing-md;
    margin-bottom: $spacing-md;

    .logo-text {
      font-size: 28px;
      font-weight: 700;
    color: #34332d;
    font-family: Georgia, serif;
    }
  }

  .footer-title {
    font-size: $font-size-title-h3;
    font-weight: 600;
    margin-bottom: $spacing-md;
    color: #000000;
  }

  .footer-links {
    list-style: none;
    padding: 0;

    a {
      display: inline-block;
      color: $text-color-secondary;
      margin-bottom: $spacing-sm;
      text-decoration: none;
      transition: all $transition-fast;
      font-size: $font-size-base;

      &:hover {
        color: $primary-color;
        padding-left: 4px;
      }
    }
  }

  .footer-bottom {
    border-top: 1px solid #e8e8e8;
    padding: $spacing-lg 0;
    text-align: center;

    .copyright {
      color: $text-color-light;
      font-size: $font-size-small;
      margin: 0;
    }
  }
}

.footer-mark {
  width: 4px;
  height: 28px;
  background: #b83126;
}

@media (max-width: 768px) {
  .app-footer {
    padding: $spacing-xl 0 0;

    .footer-content {
      grid-template-columns: 1fr;
      gap: $spacing-lg;
    }
  }
}

.footer-description {
  max-width: 360px;
  margin: 18px 0 8px;
  color: #766f64;
  font-size: 14px;
  line-height: 1.75;
}

.footer-meta {
  color: #a09789;
  font-size: 11px;
  letter-spacing: 0.04em;
}

.app-footer .footer-content {
  grid-template-columns: minmax(0, 1.3fr) minmax(240px, 0.7fr);
  align-items: start;
}

.app-footer .footer-links {
  display: grid;
  grid-template-columns: repeat(2, minmax(86px, auto));
  column-gap: 28px;
  row-gap: 4px;
}

.app-footer .footer-links a {
  margin-bottom: 0;
}

.footer-bottom {
  border-top-color: #d7d0c5 !important;
}

@media (max-width: 768px) {
  .app-footer .footer-content {
    grid-template-columns: 1fr;
  }

  .app-footer .footer-links {
    grid-template-columns: repeat(2, minmax(100px, auto));
  }
}
</style>
