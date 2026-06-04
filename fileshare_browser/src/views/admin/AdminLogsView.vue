<script setup>
/**
 * 【页面】管理员访问日志 — 表格分页，接口 /api/admin/access-logs。
 */
import { ref, onMounted } from 'vue'
import { adminApi } from '@/api/http'

const loading = ref(true)
const err = ref('')
const rows = ref([])
const page = ref(1)
const totalPages = ref(0)
const totalElements = ref(0)
const pageSize = 20

async function load() {
  loading.value = true
  err.value = ''
  try {
    const { data } = await adminApi.get('/access-logs', { params: { page: page.value, size: pageSize } })
    rows.value = data.content ?? []
    totalPages.value = data.totalPages ?? 0
    totalElements.value = data.totalElements ?? 0
  } catch (e) {
    err.value = e.response?.data?.error || e.message || '加载失败'
    rows.value = []
  } finally {
    loading.value = false
  }
}

function goPage(p) {
  const next = Math.min(Math.max(1, p), Math.max(1, totalPages.value || 1))
  page.value = next
  load()
}

function fmtTime(v) {
  if (!v) return '—'
  return String(v).replace('T', ' ')
}

onMounted(load)
</script>

<template>
  <div class="admin-page">
    <header class="top gallery-shell-strip admin-head">
      <div>
        <h1>访问日志</h1>
        <p class="sub">由 AOP 写入 MySQL <code>api_access_log</code>，仅管理员可查看。</p>
      </div>
    </header>

    <div class="gallery-shell-strip admin-panel">
      <p v-if="err" class="err">{{ err }}</p>
      <p v-if="loading" class="muted">加载中…</p>
      <template v-else>
        <p class="meta">共 {{ totalElements }} 条 · 第 {{ page }} / {{ Math.max(1, totalPages) }} 页</p>
        <div class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th>时间</th>
                <th>方法</th>
                <th>URI</th>
                <th>用户</th>
                <th>成功</th>
                <th>ms</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="r in rows" :key="r.id">
                <td class="mono">{{ fmtTime(r.occurredAt) }}</td>
                <td>{{ r.httpMethod }}</td>
                <td class="uri">{{ r.requestUri }}</td>
                <td>{{ r.userId ?? '—' }}</td>
                <td>{{ r.success ? '是' : '否' }}</td>
                <td>{{ r.durationMs }}</td>
                <td class="mono small">{{ r.controllerMethod }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="totalPages > 1" class="pager">
          <button type="button" class="btn" :disabled="page <= 1" @click="goPage(page - 1)">上一页</button>
          <button type="button" class="btn" :disabled="page >= totalPages" @click="goPage(page + 1)">下一页</button>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.admin-page {
  width: 100%;
}
.admin-head {
  text-align: center;
}
.admin-head > div {
  width: 100%;
}
h1 {
  margin: 0 0 0.35rem;
  font-size: 1.35rem;
}
.sub {
  margin: 0;
  font-size: 0.86rem;
  color: #555;
  line-height: 1.5;
}
code {
  font-size: 0.85em;
  background: #f0f0f0;
  padding: 0.1rem 0.25rem;
  border-radius: 4px;
}
.err {
  color: #c62828;
}
.muted {
  color: #666;
}
.meta {
  font-size: 0.85rem;
  color: #666;
  margin-bottom: 0.75rem;
}
.table-wrap {
  overflow-x: auto;
}
.table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.82rem;
}
.table th,
.table td {
  border-bottom: 1px solid #eee;
  padding: 0.45rem 0.35rem;
  text-align: left;
  vertical-align: top;
}
.table th {
  color: #666;
  font-weight: 600;
}
.mono {
  font-family: ui-monospace, monospace;
  white-space: nowrap;
}
.uri {
  max-width: 14rem;
  word-break: break-all;
}
.small {
  font-size: 0.78rem;
}
.btn {
  padding: 0.35rem 0.6rem;
  border-radius: 6px;
  border: 1px solid #bbb;
  background: #fff;
  cursor: pointer;
  font: inherit;
}
.btn:disabled {
  opacity: 0.5;
}
.pager {
  display: flex;
  gap: 0.5rem;
  margin-top: 1rem;
}
</style>
