<script setup>
/**
 * 【页面】统一文件预览 — 根据路由区分：本人文件、本人历史快照、广场公开、临时分享；请求对应 /api/fs 或 /api/share。
 */
import { ref, watch, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getStoredUser } from '@/utils/auth'
import { getFilePreviewKind, mimeForPreviewKind } from '@/utils/filePreview'

const route = useRoute()
const router = useRouter()

const loading = ref(true)
const error = ref('')
const blobUrl = ref('')
const kind = ref(null)
const title = ref('预览')

function revoke() {
  if (blobUrl.value) {
    URL.revokeObjectURL(blobUrl.value)
    blobUrl.value = ''
  }
}

function closeOrBack() {
  window.close()
  setTimeout(() => {
    if (!window.closed) {
      if (route.name === 'file-preview-share') {
        router.push({ name: 'share-landing', params: { code: String(route.params.code || '') } })
      } else {
        router.push({ name: 'my-files' })
      }
    }
  }, 50)
}

async function loadSharePreview() {
  const shareCode = String(route.params.code || '').toUpperCase()
  title.value = '预览'
  try {
    const metaRes = await fetch(`/api/share/${shareCode}/meta`, { credentials: 'include' })
    const meta = await metaRes.json().catch(() => ({}))
    if (!metaRes.ok) {
      throw new Error(meta.error || metaRes.statusText)
    }
    const fileName = meta.fileName || 'file'
    const hintMime = meta.contentType || ''
    title.value = fileName

    const prelimKind = getFilePreviewKind(fileName, hintMime)
    if (!prelimKind) {
      error.value = '该文件类型不支持在线预览'
      loading.value = false
      return
    }
    if (prelimKind === 'office') {
      const pdfRes = await fetch(`/api/share/${shareCode}/preview-pdf`, { credentials: 'include' })
      if (pdfRes.status === 422) {
        const j = await pdfRes.json().catch(() => ({}))
        error.value = `${j.error || '文档无法在线预览'} 请关闭本页后在分享页使用「下载」打开原件。`
        loading.value = false
        return
      }
      if (!pdfRes.ok) {
        const j = await pdfRes.json().catch(() => ({}))
        throw new Error(j.error || pdfRes.statusText)
      }
      const raw = await pdfRes.blob()
      const pdfBlob = new Blob([await raw.arrayBuffer()], { type: 'application/pdf' })
      kind.value = 'pdf'
      blobUrl.value = URL.createObjectURL(pdfBlob)
      loading.value = false
      return
    }

    const res = await fetch(`/api/share/${shareCode}/download`, { credentials: 'include' })
    if (!res.ok) {
      const j = await res.json().catch(() => ({}))
      throw new Error(j.error || res.statusText)
    }
    const headerMime = (res.headers.get('content-type') || '').split(';')[0].trim()
    const resolvedKind = getFilePreviewKind(fileName, headerMime || hintMime)
    if (!resolvedKind) {
      error.value = '服务器返回的类型不支持在线预览'
      loading.value = false
      return
    }
    let blob = await res.blob()
    const needType =
      !blob.type ||
      blob.type === 'application/octet-stream' ||
      (resolvedKind === 'pdf' && blob.type !== 'application/pdf')
    if (needType) {
      const t = mimeForPreviewKind(resolvedKind, fileName) || headerMime
      if (t) {
        blob = new Blob([await blob.arrayBuffer()], { type: t })
      }
    }
    kind.value = resolvedKind
    blobUrl.value = URL.createObjectURL(blob)
  } catch (e) {
    error.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

async function loadRevisionPreview() {
  const nodeId = String(route.params.nodeId || '')
  const revisionId = String(route.params.revisionId || '')
  const fileName = typeof route.query.name === 'string' ? route.query.name : 'file'
  const hintMime = typeof route.query.ct === 'string' ? route.query.ct : ''
  title.value = `${fileName}（历史）`

  const u = getStoredUser()
  if (!u?.id) {
    await router.replace({ name: 'login', query: { redirect: route.fullPath } })
    loading.value = false
    return
  }

  const prelimKind = getFilePreviewKind(fileName, hintMime)
  if (!prelimKind) {
    error.value = '该文件类型不支持在线预览（支持常见图片、视频、PDF、Office 文档转 PDF）'
    loading.value = false
    return
  }

  let res
  try {
    if (prelimKind === 'office') {
      const pdfRes = await fetch(
        `/api/fs/nodes/${nodeId}/revisions/${revisionId}/preview-pdf`,
        { credentials: 'include' },
      )
      if (pdfRes.status === 422) {
        const j = await pdfRes.json().catch(() => ({}))
        error.value = `${j.error || '文档无法在线预览'} 请关闭本页后使用「下载该版」打开原件。`
        loading.value = false
        return
      }
      if (!pdfRes.ok) {
        const j = await pdfRes.json().catch(() => ({}))
        throw new Error(j.error || pdfRes.statusText)
      }
      const raw = await pdfRes.blob()
      const pdfBlob = new Blob([await raw.arrayBuffer()], { type: 'application/pdf' })
      kind.value = 'pdf'
      blobUrl.value = URL.createObjectURL(pdfBlob)
      loading.value = false
      return
    }

    res = await fetch(`/api/fs/nodes/${nodeId}/revisions/${revisionId}/download`, {
      credentials: 'include',
    })
    if (!res.ok) {
      const j = await res.json().catch(() => ({}))
      throw new Error(j.error || res.statusText)
    }
  } catch (e) {
    error.value = e.message || '加载失败'
    loading.value = false
    return
  }

  const headerMime = (res.headers.get('content-type') || '').split(';')[0].trim()
  const resolvedKind = getFilePreviewKind(fileName, headerMime || hintMime)
  if (!resolvedKind) {
    error.value = '服务器返回的类型不支持在线预览'
    loading.value = false
    return
  }

  let blob = await res.blob()
  const needType =
    !blob.type ||
    blob.type === 'application/octet-stream' ||
    (resolvedKind === 'pdf' && blob.type !== 'application/pdf')
  if (needType) {
    const t = mimeForPreviewKind(resolvedKind, fileName) || headerMime
    if (t) {
      blob = new Blob([await blob.arrayBuffer()], { type: t })
    }
  }

  kind.value = resolvedKind
  blobUrl.value = URL.createObjectURL(blob)
  loading.value = false
}

async function loadBody() {
  revoke()
  loading.value = true
  error.value = ''
  kind.value = null

  if (route.name === 'file-preview-share') {
    await loadSharePreview()
    return
  }

  if (route.name === 'file-preview-revision') {
    await loadRevisionPreview()
    return
  }

  const u = getStoredUser()
  if (!u?.id) {
    await router.replace({ name: 'login', query: { redirect: route.fullPath } })
    loading.value = false
    return
  }

  const fileName = typeof route.query.name === 'string' ? route.query.name : 'file'
  title.value = fileName

  const isPublic = route.name === 'file-preview-public'
  const nodeId = route.params.nodeId
  const ownerId = route.params.ownerId

  const hintMime =
    typeof route.query.ct === 'string' ? route.query.ct : ''
  const prelimKind = getFilePreviewKind(fileName, hintMime)
  if (!prelimKind) {
    error.value = '该文件类型不支持在线预览（支持常见图片、视频、PDF、Office 文档转 PDF）'
    loading.value = false
    return
  }

  let res
  try {
    if (prelimKind === 'office') {
      const pdfUrl = isPublic
        ? `/api/fs/gallery/${ownerId}/files/${nodeId}/preview-pdf`
        : `/api/fs/nodes/${nodeId}/preview-pdf`
      res = await fetch(pdfUrl, { credentials: 'include' })
      if (res.status === 422) {
        const j = await res.json().catch(() => ({}))
        error.value = `${j.error || '文档无法在线预览'} 请关闭本页后在列表中使用「下载」打开原件。`
        loading.value = false
        return
      }
      if (!res.ok) {
        const j = await res.json().catch(() => ({}))
        throw new Error(j.error || res.statusText)
      }
      const raw = await res.blob()
      const pdfBlob = new Blob([await raw.arrayBuffer()], { type: 'application/pdf' })
      kind.value = 'pdf'
      blobUrl.value = URL.createObjectURL(pdfBlob)
      loading.value = false
      return
    }

    if (isPublic) {
      res = await fetch(`/api/fs/gallery/${ownerId}/files/${nodeId}/download`, {
        credentials: 'include',
      })
    } else {
      res = await fetch(`/api/fs/nodes/${nodeId}/download`, { credentials: 'include' })
    }
    if (!res.ok) {
      const j = await res.json().catch(() => ({}))
      throw new Error(j.error || res.statusText)
    }
  } catch (e) {
    error.value = e.message || '加载失败'
    loading.value = false
    return
  }

  const headerMime = (res.headers.get('content-type') || '').split(';')[0].trim()
  const resolvedKind = getFilePreviewKind(fileName, headerMime || hintMime)
  if (!resolvedKind) {
    error.value = '服务器返回的类型不支持在线预览'
    loading.value = false
    return
  }

  let blob = await res.blob()
  const needType =
    !blob.type ||
    blob.type === 'application/octet-stream' ||
    (resolvedKind === 'pdf' && blob.type !== 'application/pdf')
  if (needType) {
    const t = mimeForPreviewKind(resolvedKind, fileName) || headerMime
    if (t) {
      blob = new Blob([await blob.arrayBuffer()], { type: t })
    }
  }

  kind.value = resolvedKind
  blobUrl.value = URL.createObjectURL(blob)
  loading.value = false
}

watch(
  () => ({
    name: route.name,
    nodeId: route.params.nodeId,
    revisionId: route.params.revisionId,
    ownerId: route.params.ownerId,
    code: route.params.code,
    qname: route.query.name,
    ct: route.query.ct,
  }),
  () => {
    loadBody()
  },
  { immediate: true },
)

onUnmounted(() => revoke())
</script>

<template>
  <div class="fp-root">
    <header class="fp-bar">
      <button type="button" class="fp-close" @click="closeOrBack">关闭</button>
      <span class="fp-title" :title="title">{{ title }}</span>
    </header>

    <div v-if="loading" class="fp-state">加载中…</div>
    <div v-else-if="error" class="fp-state fp-err">{{ error }}</div>
    <div v-else class="fp-body">
      <img v-if="kind === 'image'" :src="blobUrl" :alt="title" class="fp-img" />
      <video v-else-if="kind === 'video'" :src="blobUrl" controls playsinline class="fp-video" />
      <iframe v-else-if="kind === 'pdf'" :src="blobUrl" class="fp-pdf" title="PDF 预览" />
    </div>
  </div>
</template>

<style scoped>
.fp-root {
  min-height: 100vh;
  margin: 0;
  display: flex;
  flex-direction: column;
  background: #1a1a1a;
  color: #eee;
  font-family: system-ui, sans-serif;
}
.fp-bar {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  flex-shrink: 0;
  padding: 0.5rem 0.75rem;
  background: #111;
  border-bottom: 1px solid #333;
}
.fp-close {
  padding: 0.35rem 0.65rem;
  border-radius: 6px;
  border: 1px solid #555;
  background: #222;
  color: #eee;
  cursor: pointer;
  font: inherit;
}
.fp-close:hover {
  background: #2a2a2a;
}
.fp-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 0.9rem;
}
.fp-state {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 2rem;
  font-size: 0.95rem;
}
.fp-err {
  color: #ff8a80;
}
.fp-body {
  flex: 1;
  min-height: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0.5rem;
  box-sizing: border-box;
}
.fp-img {
  max-width: 100%;
  max-height: calc(100vh - 3.5rem);
  object-fit: contain;
}
.fp-video {
  max-width: 100%;
  max-height: calc(100vh - 3.5rem);
  background: #000;
}
.fp-pdf {
  width: 100%;
  height: calc(100vh - 3.25rem);
  border: none;
  background: #525659;
}
</style>
