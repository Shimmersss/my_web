<template>
  <transition name="fade">
    <button v-if="show" class="scroll-to-top" type="button" aria-label="返回页面顶部" @click="handleClick">
      <n-icon size="24" color="#fff">
        <ChevronUpOutline />
      </n-icon>
    </button>
  </transition>
</template>

<script setup>
import { useScrollToTop } from '@/composables/useScroll'
import { NIcon } from 'naive-ui'
import { ChevronUpOutline } from '@vicons/ionicons5'

const { showButton, scrollToTop } = useScrollToTop({ threshold: 300 })

const show = showButton
const handleClick = scrollToTop
</script>

<style scoped lang="scss">
@use '@/assets/styles/variables' as *;

.scroll-to-top {
  appearance: none;
  border: 0;
  padding: 0;
  position: fixed;
  bottom: $spacing-xl;
  right: $spacing-xl;
  width: 50px;
  height: 50px;
  border-radius: 50%;
  background: linear-gradient(135deg, #1890ff 0%, #722ed1 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  box-shadow: $shadow-lg;
  transition: all $transition-base;
  z-index: 999;

  &:hover {
    transform: translateY(-4px);
    box-shadow: 0 8px 40px rgba(24, 144, 255, 0.5);
  }

  &:focus-visible {
    outline: 3px solid rgba(24, 144, 255, 0.35);
    outline-offset: 4px;
  }
}

.fade-enter-active,
.fade-leave-active {
  transition: all $transition-base;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
  transform: translateY(20px);
}
</style>
