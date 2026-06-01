<template>
  <div class="news-detail">
    <!-- 新闻详情 -->
    <section class="section">
      <div class="container">
        <div class="news-hero">
          <img :src="newsData.image" :alt="newsData.title">
        </div>

        <article class="news-article">
          <div class="article-category">{{ newsData.category }}</div>
          <div class="article-content" v-html="newsData.content"></div>

          <div class="article-share">
            <span>分享到：</span>
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

        <!-- 相关新闻 -->
        <div class="related-news">
          <h2>相关新闻</h2>
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

const router = useRouter()
const route = useRoute()

const LogoWeibo = ShareSocial

const newsData = ref({
  id: 1,
  title: '公司荣获2024年度最佳服务商',
  category: '公司动态',
  date: '2024-01-15',
  author: '市场部',
  views: 1234,
  image: 'https://picsum.photos/1200/600',
  summary: '凭借优质服务和创新技术，公司在2024年度行业评选中荣获最佳服务商奖项',
  content: `
    <p>近日，在2024年度行业评选中，我司凭借卓越的服务质量和持续的技术创新，荣获"最佳服务商"奖项。这是对公司多年来深耕企业服务领域的高度认可。</p>
    
    <h3>评选标准</h3>
    <p>本次评选从多个维度对参评企业进行综合评估，包括：服务质量、技术创新能力、客户满意度、市场影响力等。我司在各项指标中均表现突出，最终脱颖而出。</p>
    
    <h3>获奖感言</h3>
    <p>公司CEO表示："这一荣誉离不开全体员工的辛勤付出和客户的大力支持。我们将以此为新的起点，继续提升服务质量，为客户创造更大的价值。"</p>
    
    <h3>未来发展</h3>
    <p>获得这一奖项将进一步坚定公司的发展信心。未来，我们将持续加大研发投入，推出更多创新产品和服务，助力企业数字化转型。同时，我们也将积极参与行业交流，为行业发展贡献力量。</p>
  `
})

const relatedNews = computed(() => [
  {
    id: 2,
    title: '发布新产品2.0版本',
    summary: '经过半年的研发，公司推出新产品2.0版本',
    date: '2024-01-10',
    image: 'https://picsum.photos/200/150'
  },
  {
    id: 3,
    title: '参加行业峰会并发表主题演讲',
    summary: '公司CEO受邀参加年度行业峰会',
    date: '2024-01-05',
    image: 'https://picsum.photos/200/150'
  },
  {
    id: 4,
    title: '与知名企业达成战略合作',
    summary: '公司与某世界500强企业签署战略合作协议',
    date: '2023-12-28',
    image: 'https://picsum.photos/200/150'
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
