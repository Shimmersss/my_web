<template>
  <footer class="app-footer">
    <div class="container footer-content">
      <div class="footer-section">
        <div class="footer-logo">
          <span class="footer-mark" aria-hidden="true"></span>
          <span class="logo-text">Research Desk</span>
        </div>
        <p class="footer-desc">{{ $t('home.hero.subtitle') }}</p>
      </div>

      <div class="footer-section">
        <h4 class="footer-title">工作入口</h4>
        <ul class="footer-links">
          <li><a href="/" @click.prevent="navigateTo('/')">{{ $t('common.home') }}</a></li>
          <li><a href="/publications" @click.prevent="navigateTo('/publications')">文献</a></li>
          <li><a href="/translate" @click.prevent="navigateTo('/translate')">翻译</a></li>
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

  .footer-desc {
    color: $text-color-secondary;
    line-height: $line-height;
    margin-bottom: $spacing-md;
    font-size: $font-size-base;
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
</style>
