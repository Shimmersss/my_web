<template>
  <div class="about-page">
    <!-- 企业简介 -->
    <section class="section">
      <div class="container">
        <h2 class="section__title">{{ $t('about.intro') }}</h2>
        <div class="intro-content">
          <div class="intro-text">
            <p>公司成立于2014年，是一家专注于企业级解决方案的科技公司。我们拥有一支经验丰富、技术精湛的团队，致力于为客户提供高质量、创新性的技术解决方案。</p>
            <p>经过多年的发展，公司已经成为行业内的领军企业，服务了众多知名企业客户。我们的使命是通过技术创新，帮助客户实现数字化转型，提升核心竞争力。</p>
            <div class="intro-stats">
              <div class="stat-item">
                <div class="stat-number">10+</div>
                <div class="stat-label">年行业经验</div>
              </div>
              <div class="stat-item">
                <div class="stat-number">500+</div>
                <div class="stat-label">服务客户</div>
              </div>
              <div class="stat-item">
                <div class="stat-number">100+</div>
                <div class="stat-label">技术团队</div>
              </div>
              <div class="stat-item">
                <div class="stat-number">50+</div>
                <div class="stat-label">成功案例</div>
              </div>
            </div>
          </div>
          <div class="intro-image">
            <img src="https://picsum.photos/600/400?random=1" alt="公司简介">
          </div>
        </div>
      </div>
    </section>

    <!-- 发展历程 -->
    <section class="section" style="background: #f5f5f5">
      <div class="container">
        <h2 class="section__title">{{ $t('about.history') }}</h2>
        <n-timeline>
          <n-timeline-item v-for="item in history" :key="item.year" :title="item.year">
            <div class="timeline-content">
              <h3>{{ item.title }}</h3>
              <p>{{ item.description }}</p>
            </div>
          </n-timeline-item>
        </n-timeline>
      </div>
    </section>

    <!-- 组织架构 -->
    <section class="section">
      <div class="container">
        <h2 class="section__title">{{ $t('about.organization') }}</h2>
        <div class="org-chart">
          <div class="org-level">
            <div class="org-box ceo">
              <n-icon size="40"><PersonCircleOutline /></n-icon>
              <h4>CEO</h4>
              <p>首席执行官</p>
            </div>
          </div>
          <div class="org-level">
            <div class="org-box cto">
              <n-icon size="32"><CodeSlashOutline /></n-icon>
              <h4>CTO</h4>
              <p>技术总监</p>
            </div>
            <div class="org-box coo">
              <n-icon size="32"><BuildOutline /></n-icon>
              <h4>COO</h4>
              <p>运营总监</p>
            </div>
            <div class="org-box cfo">
              <n-icon size="32"><CashOutline /></n-icon>
              <h4>CFO</h4>
              <p>财务总监</p>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- 核心团队 -->
    <section class="section" style="background: #f5f5f5">
      <div class="container">
        <h2 class="section__title">{{ $t('about.team') }}</h2>
        <n-grid :x-gap="24" :y-gap="24" :cols="4">
          <n-grid-item v-for="member in team" :key="member.id">
            <div class="team-card">
              <div class="team-avatar">
                <img :src="member.avatar" :alt="member.name">
              </div>
              <h3>{{ member.name }}</h3>
              <p class="team-position">{{ member.position }}</p>
              <p class="team-bio">{{ member.bio }}</p>
            </div>
          </n-grid-item>
        </n-grid>
      </div>
    </section>

    <!-- 荣誉资质 -->
    <section class="section">
      <div class="container">
        <h2 class="section__title">{{ $t('about.honors') }}</h2>
        <n-grid :x-gap="24" :y-gap="24" :cols="4">
          <n-grid-item v-for="honor in honors" :key="honor.id">
            <div class="honor-card">
              <n-icon size="48" color="#ffd700"><TrophyOutline /></n-icon>
              <h3>{{ honor.title }}</h3>
              <p>{{ honor.year }}</p>
            </div>
          </n-grid-item>
        </n-grid>
      </div>
    </section>

    <!-- 企业文化 -->
    <section class="section" style="background: #f5f5f5">
      <div class="container">
        <h2 class="section__title">{{ $t('about.culture') }}</h2>
        <div class="culture-grid">
          <div v-for="item in culture" :key="item.id" class="culture-item">
            <div class="culture-icon" :style="{ backgroundColor: item.color }">
              <n-icon size="32" color="#fff">
                <component :is="item.icon" />
              </n-icon>
            </div>
            <h3>{{ item.title }}</h3>
            <p>{{ item.description }}</p>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { NTimeline, NTimelineItem, NGrid, NGridItem, NIcon } from 'naive-ui'
import {
  PersonCircleOutline,
  CodeSlashOutline,
  BuildOutline,
  CashOutline,
  TrophyOutline,
  HeartOutline,
  BulbOutline,
  RocketOutline,
  PeopleOutline
} from '@vicons/ionicons5'

const history = ref([
  { year: '2014', title: '公司成立', description: '公司正式成立，专注于企业级软件开发' },
  { year: '2016', title: '业务扩张', description: '业务范围扩展到云计算和数据分析领域' },
  { year: '2018', title: '获得投资', description: '获得A轮融资，加速产品研发和市场拓展' },
  { year: '2020', title: '战略升级', description: '全面升级战略布局，推出AI解决方案' },
  { year: '2022', title: '行业领先', description: '成为行业领军企业，服务客户超过500家' },
  { year: '2024', title: '创新发展', description: '持续创新，引领行业发展新趋势' }
])

const team = ref([
  {
    id: 1,
    name: '张三',
    position: '创始人 & CEO',
    bio: '15年行业经验，曾任职于多家知名科技公司',
    avatar: 'https://picsum.photos/150/150'
  },
  {
    id: 2,
    name: '李四',
    position: 'CTO',
    bio: '技术专家，拥有多项专利技术',
    avatar: 'https://picsum.photos/150/150'
  },
  {
    id: 3,
    name: '王五',
    position: 'COO',
    bio: '运营专家，擅长团队管理和业务拓展',
    avatar: 'https://picsum.photos/150/150'
  },
  {
    id: 4,
    name: '赵六',
    position: '技术总监',
    bio: '全栈工程师，精通多种技术栈',
    avatar: 'https://picsum.photos/150/150'
  }
])

const honors = ref([
  { id: 1, title: '最佳服务商奖', year: '2023' },
  { id: 2, title: '技术创新奖', year: '2022' },
  { id: 3, title: '优秀企业奖', year: '2021' },
  { id: 4, title: '行业领袖奖', year: '2020' }
])

const culture = ref([
  {
    id: 1,
    title: '客户至上',
    description: '以客户需求为中心，提供优质服务',
    icon: HeartOutline,
    color: '#ff4d4f'
  },
  {
    id: 2,
    title: '创新驱动',
    description: '持续创新，引领行业技术发展',
    icon: BulbOutline,
    color: '#1890ff'
  },
  {
    id: 3,
    title: '追求卓越',
    description: '精益求精，打造高品质产品',
    icon: RocketOutline,
    color: '#52c41a'
  },
  {
    id: 4,
    title: '团队合作',
    description: '团结协作，共创辉煌',
    icon: PeopleOutline,
    color: '#faad14'
  }
])
</script>

<style scoped lang="scss">
@use '@/assets/styles/variables' as *;

.intro-content {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: $spacing-xl;
  align-items: center;

  .intro-text {
    p {
      font-size: 16px;
      line-height: 1.8;
      color: $text-color-secondary;
      margin-bottom: $spacing-md;
    }

    .intro-stats {
      display: grid;
      grid-template-columns: repeat(4, 1fr);
      gap: $spacing-lg;
      margin-top: $spacing-xl;

      .stat-item {
        text-align: center;

        .stat-number {
          font-size: 36px;
          font-weight: 700;
          color: $primary-color;
          margin-bottom: $spacing-xs;
        }

        .stat-label {
          font-size: 14px;
          color: $text-color-secondary;
        }
      }
    }
  }

  .intro-image {
    img {
      width: 100%;
      border-radius: $border-radius-lg;
      box-shadow: $shadow-lg;
    }
  }
}

.timeline-content {
  h3 {
    font-size: 18px;
    font-weight: 600;
    margin-bottom: $spacing-xs;
  }

  p {
    color: $text-color-secondary;
  }
}

.org-chart {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: $spacing-xl;

  .org-level {
    display: flex;
    gap: $spacing-xl;
  }

  .org-box {
    text-align: center;
    padding: $spacing-xl;
    background: #fff;
    border-radius: $border-radius-lg;
    box-shadow: $shadow-sm;
    width: 200px;

    &.ceo {
      background: linear-gradient(135deg, #1890ff 0%, #722ed1 100%);
      color: #fff;
    }

    h4 {
      font-size: 18px;
      margin: $spacing-sm 0 $spacing-xs;
    }

    p {
      font-size: 14px;
      opacity: 0.8;
    }
  }
}

.team-card {
  text-align: center;
  padding: $spacing-xl;
  background: #fff;
  border-radius: $border-radius-lg;
  box-shadow: $shadow-sm;
  transition: all $transition-base;

  &:hover {
    transform: translateY(-8px);
    box-shadow: $shadow-lg;
  }

  .team-avatar {
    width: 120px;
    height: 120px;
    border-radius: 50%;
    overflow: hidden;
    margin: 0 auto $spacing-md;

    img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }
  }

  h3 {
    font-size: 18px;
    font-weight: 600;
    margin-bottom: $spacing-xs;
  }

  .team-position {
    color: $primary-color;
    font-weight: 500;
    margin-bottom: $spacing-sm;
  }

  .team-bio {
    font-size: 14px;
    color: $text-color-secondary;
    line-height: 1.6;
  }
}

.honor-card {
  text-align: center;
  padding: $spacing-xl;
  background: #fff;
  border-radius: $border-radius-lg;
  box-shadow: $shadow-sm;
  transition: all $transition-base;

  &:hover {
    transform: scale(1.05);
    box-shadow: $shadow-md;
  }

  h3 {
    font-size: 16px;
    font-weight: 600;
    margin: $spacing-md 0 $spacing-sm;
  }

  p {
    color: $text-color-secondary;
  }
}

.culture-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: $spacing-xl;

  .culture-item {
    text-align: center;
    padding: $spacing-xl;
    background: #fff;
    border-radius: $border-radius-lg;
    box-shadow: $shadow-sm;

    .culture-icon {
      width: 80px;
      height: 80px;
      border-radius: 50%;
      display: flex;
      align-items: center;
      justify-content: center;
      margin: 0 auto $spacing-md;
    }

    h3 {
      font-size: 20px;
      font-weight: 600;
      margin-bottom: $spacing-sm;
    }

    p {
      color: $text-color-secondary;
      font-size: 14px;
    }
  }
}

@media (max-width: 992px) {
  .intro-content {
    grid-template-columns: 1fr;
  }

  .intro-stats {
    grid-template-columns: repeat(2, 1fr) !important;
  }

  .culture-grid {
    grid-template-columns: repeat(2, 1fr) !important;
  }
}

@media (max-width: 768px) {
  .org-level {
    flex-direction: column !important;
  }

  .intro-stats {
    grid-template-columns: repeat(2, 1fr) !important;
  }
}
</style>
