<script setup>
/**
 * 【页面】登录 — 写本地用户缓存并跳转「我的文件」。接口：/api/auth/login。
 */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { authApi } from '@/api/http'
import { setStoredUser } from '@/utils/auth'
import GalleryShellLayout from '@/components/GalleryShellLayout.vue'

const router = useRouter()
const email = ref('')
const password = ref('')
const err = ref('')
const loading = ref(false)

async function submit() {
  err.value = ''
  loading.value = true
  try {
    // 步骤1：POST /api/auth/login，Cookie 由浏览器自动保存（Session）
    const { data } = await authApi.post('/auth/login', {
      email: email.value.trim(),
      password: password.value,
    })
    // 步骤2：localStorage 缓存用户信息供路由守卫与顶栏显示
    setStoredUser(data)
    // 步骤3：跳转「我的文件」
    await router.replace({ name: 'my-files' })
  } catch (e) {
    err.value = e.response?.data?.error || e.message || '登录失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <GalleryShellLayout>
    <div class="gallery-auth-center">
      <div class="gallery-shell-strip gallery-auth-card">
        <div class="gallery-auth-inner">
          <div class="gallery-auth-head-block">
            <p class="gallery-auth-brand">FileShare</p>
            <h1>登录</h1>
            <p class="sub">登录后仅可查看与管理<strong>本人空间</strong>内的文件与文件夹。</p>
          </div>
          <form class="form" @submit.prevent="submit">
            <label>邮箱 <input v-model="email" type="email" required autocomplete="username" /></label>
            <label>密码 <input v-model="password" type="password" required autocomplete="current-password" /></label>
            <p v-if="err" class="err">{{ err }}</p>
            <button type="submit" class="btn primary" :disabled="loading">{{ loading ? '…' : '登录' }}</button>
          </form>
          <p class="foot">
            没有账号？
            <router-link to="/register">注册</router-link>
          </p>
        </div>
      </div>
    </div>
  </GalleryShellLayout>
</template>

<style scoped>
h1 {
  margin: 0 0 0.5rem;
  font-size: 1.4rem;
}
.sub {
  margin: 0 0 1.25rem;
  font-size: 0.88rem;
  color: #555;
  line-height: 1.5;
}
.form {
  display: flex;
  flex-direction: column;
  gap: 0.85rem;
}
label {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  font-size: 0.85rem;
  color: #444;
}
input {
  padding: 0.5rem 0.6rem;
  border: 1px solid #ccc;
  border-radius: 8px;
  font: inherit;
  width: 100%;
  box-sizing: border-box;
}
.btn {
  margin-top: 0.25rem;
  padding: 0.55rem;
  border-radius: 8px;
  border: none;
  font: inherit;
  cursor: pointer;
  align-self: center;
  min-width: 10rem;
}
.btn.primary {
  background: #1565c0;
  color: #fff;
}
.btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.err {
  color: #c62828;
  font-size: 0.85rem;
  margin: 0;
  text-align: left;
}
.foot {
  margin: 1.25rem 0 0;
  font-size: 0.9rem;
  color: #666;
}
a {
  color: #1565c0;
}
</style>
