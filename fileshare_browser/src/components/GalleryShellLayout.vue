<script setup>
/**
 * 【布局】Gallery 视觉壳 — 全屏背景图 + 半透明遮罩 + 内容槽；登录/注册/分享落地页与 GalleryWorkspaceLayout 共用。
 */
import bgUrl from '@/resource/f2aa9dd7a300b16f99a85efded2ed35f.jpg?url'
</script>

<template>
  <div class="gallery-shell-page">
    <div
      class="gallery-shell-bg"
      aria-hidden="true"
      :style="{ backgroundImage: `url(${bgUrl})` }"
    />
    <div class="gallery-shell-scrim" aria-hidden="true" />
    <div class="gallery-shell-content">
      <slot />
    </div>
  </div>
</template>
