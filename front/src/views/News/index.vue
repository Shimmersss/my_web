<template>
  <div class="news-page">
    <!-- 新闻列表 -->
    <section class="section">
      <div class="container">
        <div class="news-search">
          <n-input
            v-model:value="searchText"
            placeholder="搜索新闻..."
            size="large"
            clearable
          >
            <template #prefix>
              <n-icon><SearchOutline /></n-icon>
            </template>
          </n-input>
        </div>

        <div class="news-list">
          <div v-for="news in filteredNews" :key="news.id" class="news-item" @click="navigateTo(`/news/${news.id}`)">
            <div class="news-image">
              <img :src="news.image" :alt="news.title">
              <div class="news-date">
                <div class="day">{{ formatDate(news.date).day }}</div>
                <div class="month">{{ formatDate(news.date).month }}</div>
              </div>
            </div>
            <div class="news-content">
              <div class="news-category">{{ news.category }}</div>
              <h3>{{ news.title }}</h3>
              <p class="news-summary">{{ news.summary }}</p>
              <div class="news-meta">
                <span><n-icon><PersonOutline /></n-icon> {{ news.author }}</span>
                <span><n-icon><EyeOutline /></n-icon> {{ news.views }}</span>
              </div>
            </div>
          </div>
        </div>

        <div class="pagination">
          <n-pagination v-model:page="page" :page-count="10" show-quick-jumper />
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { NInput, NPagination, NIcon } from 'naive-ui'
import { SearchOutline, PersonOutline, EyeOutline } from '@vicons/ionicons5'

const router = useRouter()
const page = ref(1)
const searchText = ref('')

const news = ref([
  {
    id: 1,
    title: '公司荣获2024年度最佳服务商',
    summary: '凭借优质服务和创新技术，公司在2024年度行业评选中荣获最佳服务商奖项',
    category: '公司动态',
    date: '2024-01-15',
    author: '市场部',
    views: 1234,
    image: 'https://picsum.photos/400/300'
  },
  {
    id: 2,
    title: '发布新产品2.0版本',
    summary: '经过半年的研发，公司推出新产品2.0版本，带来更好的用户体验和功能',
    category: '产品发布',
    date: '2024-01-10',
    author: '产品部',
    views: 2345,
    image: 'https://picsum.photos/400/300'
  },
  {
    id: 3,
    title: '参加行业峰会并发表主题演讲',
    summary: '公司CEO受邀参加年度行业峰会，分享数字化转型经验',
    category: '行业活动',
    date: '2024-01-05',
    author: '市场部',
    views: 1876,
    image: 'https://picsum.photos/400/300'
  },
  {
    id: 4,
    title: '与知名企业达成战略合作',
    summary: '公司与某世界500强企业签署战略合作协议，共同推进技术创新',
    category: '公司动态',
    date: '2023-12-28',
    author: '市场部',
    views: 3456,
    image: 'https://picsum.photos/400/300'
  },
  {
    id: 5,
    title: '完成新一轮融资',
    summary: '公司完成B轮融资，将加速产品研发和市场拓展',
    category: '公司动态',
    date: '2023-12-20',
    author: '市场部',
    views: 4567,
    image: 'https://picsum.photos/400/300'
  },
  {
    id: 6,
    title: '技术团队荣获专利证书',
    summary: '技术团队研发的核心技术获得国家发明专利',
    category: '技术创新',
    date: '2023-12-15',
    author: '技术部',
    views: 2890,
    image: 'https://picsum.photos/400/300'
  }
])

const filteredNews = computed(() => {
  if (!searchText.value) return news.value
  return news.value.filter(n =>
    n.title.toLowerCase().includes(searchText.value.toLowerCase()) ||
    n.summary.toLowerCase().includes(searchText.value.toLowerCase())
  )
})

const formatDate = (dateStr) => {
  const date = new Date(dateStr)
  const months = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC']
  return {
    day: date.getDate(),
    month: months[date.getMonth()]
  }
}

const navigateTo = (path) => {
  router.push(path)
}
</script>

<style scoped lang="scss">
@use '@/assets/styles/variables' as *;

.news-search {
  margin-bottom: $spacing-xl;
  max-width: 600px;
  margin-left: auto;
  margin-right: auto;
}

.news-list {
  display: flex;
  flex-direction: column;
  gap: $spacing-xl;
  margin-bottom: $spacing-xl;
}

.news-item {
  display: grid;
  grid-template-columns: 300px 1fr;
  gap: $spacing-xl;
  background: #fff;
  border-radius: $border-radius-lg;
  overflow: hidden;
  box-shadow: $shadow-sm;
  cursor: pointer;
  transition: all $transition-base;

  &:hover {
    transform: translateY(-4px);
    box-shadow: $shadow-lg;

    .news-image img {
      transform: scale(1.1);
    }
  }

  .news-image {
    position: relative;
    height: 200px;
    overflow: hidden;

    img {
      width: 100%;
      height: 100%;
      object-fit: cover;
      transition: transform $transition-base;
    }

    .news-date {
      position: absolute;
      top: $spacing-sm;
      left: $spacing-sm;
      background: $primary-color;
      color: #fff;
      padding: $spacing-sm;
      border-radius: $border-radius-base;
      text-align: center;
      min-width: 60px;

      .day {
        font-size: 24px;
        font-weight: 700;
        line-height: 1;
      }

      .month {
        font-size: 12px;
        margin-top: 2px;
      }
    }
  }

  .news-content {
    padding: $spacing-lg;
    display: flex;
    flex-direction: column;

    .news-category {
      display: inline-block;
      background: linear-gradient(135deg, #1890ff 0%, #722ed1 100%);
      color: #fff;
      padding: $spacing-xs $spacing-sm;
      border-radius: $border-radius-sm;
      font-size: 12px;
      margin-bottom: $spacing-sm;
      align-self: flex-start;
    }

    h3 {
      font-size: 24px;
      font-weight: 600;
      margin-bottom: $spacing-sm;
    }

    .news-summary {
      flex: 1;
      color: $text-color-secondary;
      line-height: 1.6;
      margin-bottom: $spacing-md;
    }

    .news-meta {
      display: flex;
      gap: $spacing-lg;
      font-size: 14px;
      color: $text-color-light;

      span {
        display: flex;
        align-items: center;
        gap: 4px;

        .n-icon {
          font-size: 16px;
        }
      }
    }
  }
}

.pagination {
  display: flex;
  justify-content: center;
}

@media (max-width: 768px) {
  .news-item {
    grid-template-columns: 1fr;
  }

  .news-image {
    height: 200px;
  }
}
</style>
