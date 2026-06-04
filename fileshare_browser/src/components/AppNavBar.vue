<script setup>
/**
 * 【组件】主导航条 — 「我的文件 / 广场 / 新建项目 / 管理日志(管理员)」与登出。依赖登录态与路由 meta。
 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getStoredUser, clearStoredUser } from '@/utils/auth'
import { authApi } from '@/api/http'

const route = useRoute()
const router = useRouter()

const me = computed(() => getStoredUser())
const authed = computed(() => !!me.value?.id)

const isExplore = computed(() => route.name === 'explore')
const isMyFiles = computed(() => route.name === 'my-files')
const isAdminLogs = computed(() => route.name === 'admin-logs')
const isAdmin = computed(() => me.value?.role === 'admin')

function goNewProject() {
  router.push({ name: 'my-files', query: { newProject: '1' } })
}

async function logout() {
  try {
    await authApi.post('/auth/logout')
  } catch {
    /* 忽略网络错误，仍清理本地态 */
  }
  clearStoredUser()
  await router.replace({ name: 'login' })
}
</script>

<template>
  <header v-if="authed" class="app-nav">
    <div class="nav-inner">
      <router-link to="/my-files" class="brand">FileShare</router-link>

      <nav class="links" aria-label="主导航">
        <router-link
          to="/explore"
          class="nav-item"
          :class="{ active: isExplore }"
        >
          广场
        </router-link>
        <router-link
          to="/my-files"
          class="nav-item"
          :class="{ active: isMyFiles }"
        >
          我的文件
        </router-link>
        <template v-if="isAdmin">
          <router-link
            to="/admin/logs"
            class="nav-item"
            :class="{ active: isAdminLogs }"
          >
            管理·日志
          </router-link>
        </template>
        <button type="button" class="nav-cta" @click="goNewProject">
          新建项目
        </button>
      </nav>

      <div class="user">
        <span class="user-text" :title="me?.email ?? ''">
          {{ me?.name || '用户' }}
        </span>
        <a
          v-if="me?.email"
          :href="'mailto:' + me.email"
          class="user-mail"
        >{{ me.email }}</a>
        <button type="button" class="btn-out" @click="logout">退出</button>
      </div>
    </div>
  </header>
</template>

<style scoped>
.app-nav {
  position: sticky;
  top: 0;
  z-index: 50;
  background: #fff;
  border-bottom: 1px solid #9e9e9e;
  box-shadow: 0 6px 18px rgba(0, 0, 0, 0.12);
}
.nav-inner {
  max-width: 1200px;
  margin: 0 auto;
  padding: 0.55rem 1.25rem;
  display: flex;
  align-items: center;
  gap: 1rem;
  flex-wrap: wrap;
}
.brand {
  font-weight: 800;
  font-size: 1.05rem;
  color: #0d47a1;
  text-decoration: none;
  letter-spacing: -0.02em;
  margin-right: 0.5rem;
}
.brand:hover {
  color: #1565c0;
}
.links {
  display: flex;
  align-items: center;
  gap: 0.35rem;
  flex: 1;
  flex-wrap: wrap;
}
.nav-item {
  padding: 0.4rem 0.75rem;
  border-radius: 8px;
  font-size: 0.9rem;
  color: #444;
  text-decoration: none;
  border: 1px solid transparent;
}
.nav-item:hover {
  background: #f5f5f5;
  color: #1565c0;
}
.nav-item.active {
  background: #e3f2fd;
  color: #0d47a1;
  border-color: #bbdefb;
  font-weight: 600;
}
.nav-cta {
  margin-left: 0.25rem;
  padding: 0.4rem 0.85rem;
  border-radius: 8px;
  border: none;
  background: #1565c0;
  color: #fff;
  font-size: 0.88rem;
  font-weight: 600;
  cursor: pointer;
}
.nav-cta:hover {
  background: #0d47a1;
}
.user {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-wrap: wrap;
  margin-left: auto;
}
.user-text {
  font-size: 0.88rem;
  font-weight: 600;
  color: #333;
  max-width: 8rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.user-mail {
  font-size: 0.78rem;
  color: #666;
  max-width: 10rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  text-decoration: none;
  cursor: default;
}
.btn-out {
  padding: 0.3rem 0.55rem;
  font-size: 0.82rem;
  border: 1px dashed #bbb;
  background: #fff;
  color: #555;
  border-radius: 6px;
  cursor: pointer;
}
.btn-out:hover {
  border-color: #c62828;
  color: #c62828;
}
</style>
