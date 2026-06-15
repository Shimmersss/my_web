<template>
  <footer class="app-footer">
    <div class="container footer-content">
      <div class="footer-section">
        <div class="footer-logo">
          <span class="footer-mark" aria-hidden="true"></span>
          <span class="logo-text">Research Desk</span>
        </div>
        <p class="footer-desc">{{ $t('home.hero.subtitle') }}</p>
        <div class="social-links">
          <n-icon size="20" class="social-icon">
            <WechatIcon />
          </n-icon>
          <n-icon size="20" class="social-icon">
            <WeiboIcon />
          </n-icon>
          <n-icon size="20" class="social-icon">
            <LinkedinIcon />
          </n-icon>
        </div>
      </div>

      <div class="footer-section">
        <h4 class="footer-title">工作入口</h4>
        <ul class="footer-links">
          <li><a href="/" @click.prevent="navigateTo('/')">{{ $t('common.home') }}</a></li>
          <li><a href="/publications" @click.prevent="navigateTo('/publications')">文献</a></li>
          <li><a href="/translate" @click.prevent="navigateTo('/translate')">翻译</a></li>
          <li><a href="/news" @click.prevent="navigateTo('/news')">GitHub 项目</a></li>
        </ul>
      </div>

      <div class="footer-section">
        <h4 class="footer-title">当前工具</h4>
        <div class="contact-info">
          <a href="/contact" @click.prevent="navigateTo('/contact')">
            <n-icon><LocationIcon /></n-icon>
            PPT 生成
          </a>
          <a href="/translate" @click.prevent="navigateTo('/translate')">
            <n-icon><PhoneIcon /></n-icon>
            论文翻译
          </a>
          <a href="/openclaw" @click.prevent="navigateTo('/openclaw')">
            <n-icon><MailIcon /></n-icon>
            OpenClaw 对话
          </a>
        </div>
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
import { NIcon } from 'naive-ui'
import {
  LogoWechat,
  ShareSocial,
  LogoLinkedin,
  LocationOutline,
  CallOutline,
  MailOutline
} from '@vicons/ionicons5'

const router = useRouter()

const WechatIcon = LogoWechat
const WeiboIcon = ShareSocial
const LinkedinIcon = LogoLinkedin
const LocationIcon = LocationOutline
const PhoneIcon = CallOutline
const MailIcon = MailOutline

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
    grid-template-columns: 2fr 1fr 1fr;
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

  .social-links {
    display: flex;
    gap: $spacing-md;

    .social-icon {
      cursor: pointer;
      transition: all $transition-fast;
      color: $text-color-secondary;

      &:hover {
        transform: scale(1.1);
        color: $primary-color;
      }
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

  .contact-info {
    a {
      display: flex;
      align-items: center;
      gap: $spacing-sm;
      color: $text-color-secondary;
      margin-bottom: $spacing-sm;
      font-size: $font-size-base;
      line-height: $line-height;
      text-decoration: none;

      .n-icon {
        flex-shrink: 0;
        color: $primary-color;
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
