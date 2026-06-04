<script setup>
/**
 * 【根组件】按路由决定是否显示顶栏；全站挂载 router-view。子页面见 views/。
 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AppNavBar from '@/components/AppNavBar.vue'
import { getStoredUser } from '@/utils/auth'

const route = useRoute()
const showNav = computed(
  () => route.meta.requiresAuth && !!getStoredUser()?.id,
)
</script>

<template>
  <AppNavBar v-if="showNav" />
  <router-view />
</template>

<style>
body {
  margin: 0;
  background: #fafafa;
}
</style>
