<template>
  <div class="app-footer">
    <div class="container footer-content">
      <div class="footer-section">
        <div class="footer-logo">
          <n-icon size="36" color="#1890ff">
            <LogoIcon />
          </n-icon>
          <span class="logo-text">COMPANY</span>
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
        <h4 class="footer-title">快捷导航</h4>
        <ul class="footer-links">
          <li @click="navigateTo('/')">{{ $t('common.home') }}</li>
          <li @click="navigateTo('/about')">{{ $t('common.about') }}</li>
          <li @click="navigateTo('/business')">{{ $t('common.business') }}</li>
          <li @click="navigateTo('/cases')">{{ $t('common.cases') }}</li>
          <li @click="navigateTo('/news')">{{ $t('common.news') }}</li>
        </ul>
      </div>

      <div class="footer-section">
        <h4 class="footer-title">联系方式</h4>
        <div class="contact-info">
          <p>
            <n-icon><LocationIcon /></n-icon>
            北京市朝阳区XX路XX号XX大厦XX层
          </p>
          <p>
            <n-icon><PhoneIcon /></n-icon>
            400-XXX-XXXX
          </p>
          <p>
            <n-icon><MailIcon /></n-icon>
            contact@company.com
          </p>
        </div>
      </div>
    </div>

    <div class="footer-bottom">
      <div class="container">
        <p class="copyright">© 2024 Company. All rights reserved. 京ICP备XXXXXXXX号</p>
      </div>
    </div>
  </div>
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

const LogoIcon = () => '🏢'
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
  background: #f5f5f5;
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
      color: $primary-color;
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

    li {
      color: $text-color-secondary;
      margin-bottom: $spacing-sm;
      cursor: pointer;
      transition: all $transition-fast;
      font-size: $font-size-base;

      &:hover {
        color: $primary-color;
        padding-left: 4px;
      }
    }
  }

  .contact-info {
    p {
      display: flex;
      align-items: center;
      gap: $spacing-sm;
      color: $text-color-secondary;
      margin-bottom: $spacing-sm;
      font-size: $font-size-base;
      line-height: $line-height;

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
