<script setup>
/**
 * 【页面】分享落地页 — 访客输入 PIN（若需要）、查看元数据、解锁后预览/下载。公开路由 /s/:code。
 */
import { ref, computed, watch, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import GalleryShellLayout from '@/components/GalleryShellLayout.vue'
import { downloadBlob } from '@/utils/download'
import { getFilePreviewKind } from '@/utils/filePreview'

const route = useRoute()
const code = computed(() => String(route.params.code || '').toUpperCase())

const loading = ref(true)
const err = ref('')
const meta = ref(null)
const pinInput = ref('')
const unlockErr = ref('')
const unlocked = ref(false)

const fullShareUrl = computed(() => `${window.location.origin}/s/${code.value}`)

const canPreview = computed(() => {
  if (!meta.value?.fileName) return false
  const k = getFilePreviewKind(meta.value.fileName, meta.value.contentType || '')
  return k === 'image' || k === 'video' || k === 'pdf' || k === 'office'
})

async function loadMeta() {
  loading.value = true
  err.value = ''
  unlockErr.value = ''
  try {
    const res = await fetch(`/api/share/${code.value}/meta`, { credentials: 'include' })
    const j = await res.json().catch(() => ({}))
    if (!res.ok) throw new Error(j.error || res.statusText)
    meta.value = j
    if (!j.requiresPin) {
      unlocked.value = true
    }
  } catch (e) {
    err.value = e.message || '加载失败'
    meta.value = null
  } finally {
    loading.value = false
  }
}

async function submitPin() {
  unlockErr.value = ''
  try {
    const res = await fetch(`/api/share/${code.value}/unlock`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'include',
      body: JSON.stringify({ pin: pinInput.value }),
    })
    if (!res.ok) {
      const j = await res.json().catch(() => ({}))
      throw new Error(j.error || res.statusText)
    }
    unlocked.value = true
    pinInput.value = ''
  } catch (e) {
    unlockErr.value = e.message || '验证失败'
  }
}

function formatTtl(sec) {
  if (sec == null || sec <= 0) return '即将过期或已过期'
  if (sec < 3600) return `约 ${Math.ceil(sec / 60)} 分钟`
  if (sec < 86400) return `约 ${(sec / 3600).toFixed(1)} 小时`
  return `约 ${(sec / 86400).toFixed(1)} 天`
}

async function copyLink() {
  try {
    await navigator.clipboard.writeText(fullShareUrl.value)
    alert('链接已复制')
  } catch {
    prompt('复制以下链接', fullShareUrl.value)
  }
}

async function download() {
  try {
    await downloadBlob(`/api/share/${code.value}/download`, meta.value?.fileName || 'download')
  } catch (e) {
    err.value = e.message || '下载失败'
  }
}

function openPreview() {
  const q = new URLSearchParams({
    name: meta.value.fileName,
    ...(meta.value.contentType ? { ct: meta.value.contentType } : {}),
  })
  window.open(`/preview/share/${code.value}?${q.toString()}`, '_blank', 'noopener,noreferrer')
}

watch(code, () => {
  unlocked.value = false
  loadMeta()
})

onMounted(() => {
  loadMeta()
})
</script>

<template>
  <GalleryShellLayout>
    <div class="gallery-auth-center">
      <div class="gallery-shell-strip gallery-auth-card">
        <div class="gallery-auth-inner">
          <div class="gallery-auth-head-block">
            <p class="gallery-auth-brand">FileShare</p>
            <h1>文件分享</h1>
            <p class="sub">通过临时分享码访问单文件；过期后自动失效。</p>
          </div>

          <p v-if="loading" class="muted slab">加载中…</p>
          <p v-else-if="err" class="err slab">{{ err }}</p>

          <template v-else-if="meta">
            <div class="meta-block">
              <p class="file-name">{{ meta.fileName }}</p>
              <p class="ttl-line">剩余有效期：{{ formatTtl(meta.ttlSecondsRemaining) }}</p>
            </div>

            <form v-if="meta.requiresPin && !unlocked" class="form" @submit.prevent="submitPin">
              <label>
                提取 PIN
                <input v-model="pinInput" type="password" autocomplete="one-time-code" maxlength="12" />
              </label>
              <p v-if="unlockErr" class="err">{{ unlockErr }}</p>
              <button type="submit" class="btn primary">验证并继续</button>
            </form>

            <div v-else class="actions-block">
              <button type="button" class="btn primary wide" @click="download">下载文件</button>
              <button
                v-if="canPreview"
                type="button"
                class="btn secondary wide"
                @click="openPreview"
              >
                在线浏览
              </button>
              <button type="button" class="btn ghost wide" @click="copyLink">复制分享链接</button>
            </div>
          </template>

          <p class="foot">
            <span class="foot-muted">分享码 {{ code }}</span>
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
.slab {
  margin: 0 0 1rem;
  text-align: left;
}
.meta-block {
  margin-bottom: 1rem;
  text-align: left;
}
.file-name {
  margin: 0 0 0.35rem;
  font-weight: 600;
  font-size: 1rem;
  color: #1a237e;
  word-break: break-all;
}
.ttl-line {
  margin: 0;
  font-size: 0.85rem;
  color: #666;
}
.form {
  display: flex;
  flex-direction: column;
  gap: 0.85rem;
  width: 100%;
  max-width: 22rem;
  align-self: center;
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
.actions-block {
  display: flex;
  flex-direction: column;
  gap: 0.65rem;
  width: 100%;
  max-width: 22rem;
  align-self: center;
}
.btn {
  padding: 0.55rem;
  border-radius: 8px;
  border: 1px solid #1565c0;
  background: #fff;
  color: #1565c0;
  cursor: pointer;
  font: inherit;
}
.btn.wide {
  width: 100%;
}
.btn.primary {
  background: #1565c0;
  color: #fff;
  border-color: #1565c0;
}
.btn.secondary {
  background: #fff;
  color: #1565c0;
}
.btn.ghost {
  border-style: dashed;
  color: #444;
  border-color: #bbb;
}
.err {
  color: #c62828;
  font-size: 0.85rem;
  margin: 0;
  text-align: left;
}
.muted {
  color: #666;
  font-size: 0.9rem;
}
.foot {
  margin: 1.25rem 0 0;
  font-size: 0.85rem;
  text-align: center;
  color: #888;
}
.foot-muted {
  font-family: ui-monospace, monospace;
}
</style>
