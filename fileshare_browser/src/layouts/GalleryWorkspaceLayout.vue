<script setup>
/**
 * 【布局】已登录工作台 — 在 GalleryShellLayout 内嵌 router-view，负责「我的文件 / 广场 / 管理」子页切换动画。
 */
import { useRoute } from 'vue-router'
import GalleryShellLayout from '@/components/GalleryShellLayout.vue'

const route = useRoute()
</script>

<template>
  <GalleryShellLayout>
    <div class="gallery-work-transition-wrap">
      <router-view v-slot="{ Component }">
        <Transition name="panel-fade">
          <component :is="Component" :key="route.path" />
        </Transition>
      </router-view>
    </div>
  </GalleryShellLayout>
</template>

<style>
/* 背景固定在 GalleryShellLayout；仅内层页面整体淡入淡出（与 out-in 不同，避免中间白屏） */
.gallery-work-transition-wrap {
  position: relative;
  width: 100%;
}

.panel-fade-leave-active {
  position: absolute;
  left: 0;
  right: 0;
  top: 0;
  width: 100%;
  z-index: 1;
  transition: opacity 0.22s ease;
  pointer-events: none;
}

.panel-fade-enter-active {
  position: relative;
  z-index: 2;
  transition: opacity 0.24s ease;
}

.panel-fade-enter-from,
.panel-fade-leave-to {
  opacity: 0;
}
</style>
