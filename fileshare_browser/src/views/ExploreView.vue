<script setup>
/**
 * 【页面】广场 Explore — 公开项目网格与进入他人公开目录只读浏览；管理员可撤销公开内容。数据：/api/fs/gallery、/api/admin。
 */
import { ref, computed, watch, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { galleryApi, adminApi } from '@/api/http'
import { getStoredUser } from '@/utils/auth'
import { downloadBlob } from '@/utils/download'
import GalleryPlazaPanel from '@/components/GalleryPlazaPanel.vue'
import ProjectMembersViewModal from '@/components/ProjectMembersViewModal.vue'
import { getFilePreviewKind } from '@/utils/filePreview'

const router = useRouter()
const me = computed(() => getStoredUser())
const isAdmin = computed(() => me.value?.role === 'admin')

const loading = ref(false)
const err = ref('')

/** 广场首页：固定 5×3 网格，每页 15 条 */
const projects = ref([])
const totalPages = ref(0)
const totalElements = ref(0)
const page = ref(1)
const pageSize = 15

const selectedOwner = ref(null)
const projectRootId = ref(null)
const nodes = ref([])
const crumbs = ref([])
const parentId = computed(() => crumbs.value[crumbs.value.length - 1]?.id ?? 0)

const inProject = computed(() => projectRootId.value != null && selectedOwner.value != null)

const viewMembersVisible = ref(false)
const viewMembersList = ref([])
const viewMembersErr = ref('')
const viewMembersLoading = ref(false)

async function openViewMembersModal() {
  const rid = projectRootId.value
  if (rid == null) return
  viewMembersVisible.value = true
  viewMembersErr.value = ''
  viewMembersList.value = []
  viewMembersLoading.value = true
  try {
    const { data } = await galleryApi.get(`/projects/${rid}/members`)
    viewMembersList.value = Array.isArray(data) ? data : []
  } catch (e) {
    viewMembersErr.value = e.response?.data?.error || e.message || '加载失败'
  } finally {
    viewMembersLoading.value = false
  }
}

function closeViewMembersModal() {
  viewMembersVisible.value = false
}

/** 与广场副标题风格一致：当前目录名（路径末级） */
const currentLocationTitle = computed(() => {
  const c = crumbs.value
  if (!c.length) return '公开项目'
  return c[c.length - 1].name
})

async function loadProjects() {
  err.value = ''
  loading.value = true
  try {
    const { data } = await galleryApi.get('/projects', {
      params: { page: page.value, size: pageSize },
    })
    projects.value = data.content ?? []
    totalElements.value = data.totalElements ?? 0
    totalPages.value = data.totalPages ?? 0
  } catch (e) {
    err.value = e.response?.data?.error || e.message || '加载公开项目失败'
    projects.value = []
    totalElements.value = 0
    totalPages.value = 0
  } finally {
    loading.value = false
  }
}

function goPage(p) {
  const next = Math.min(Math.max(1, p), Math.max(1, totalPages.value || 1))
  page.value = next
  loadProjects()
}

async function loadNodes() {
  if (!selectedOwner.value || projectRootId.value == null) return
  err.value = ''
  loading.value = true
  try {
    const { data } = await galleryApi.get(`/${selectedOwner.value.id}/nodes`, {
      params: { parentId: parentId.value },
    })
    nodes.value = data
  } catch (e) {
    err.value = e.response?.data?.error || e.message || '加载列表失败'
    nodes.value = []
  } finally {
    loading.value = false
  }
}

function openProject(p) {
  selectedOwner.value = {
    id: p.ownerUserId,
    name: p.ownerName || `用户 ${p.ownerUserId}`,
  }
  projectRootId.value = p.id
  crumbs.value = [{ id: p.id, name: p.name }]
  loadNodes()
}

function backToGrid() {
  selectedOwner.value = null
  projectRootId.value = null
  nodes.value = []
  crumbs.value = []
  loadProjects()
}

function enterFolder(row) {
  if (row.nodeType !== 'FOLDER') return
  crumbs.value = [...crumbs.value, { id: row.id, name: row.name }]
}

function goCrumb(i) {
  crumbs.value = crumbs.value.slice(0, i + 1)
}

async function downloadPublic(row) {
  err.value = ''
  try {
    await downloadBlob(`/api/fs/gallery/${selectedOwner.value.id}/files/${row.id}/download`, row.name)
  } catch (e) {
    err.value = e.message || '下载失败'
  }
}

async function onAdminUnpublishProject(p) {
  if (!isAdmin.value) return
  if (!confirm(`确定取消广场公开项目「${p.name}」（所有者 ${p.ownerName || p.ownerUserId}）？`)) return
  err.value = ''
  try {
    await adminApi.post(`/gallery/projects/${p.id}/unpublish`)
    await loadProjects()
  } catch (e) {
    err.value = e.response?.data?.error || e.message || '撤销公开失败'
  }
}

/** 管理员：对当前列表中的公开文件/文件夹撤销公开（含子树） */
async function adminUnpublishPublicRow(row) {
  if (!isAdmin.value) return
  const tip =
    row.nodeType === 'FOLDER'
      ? `确定对「${row.name}」及其子内容取消广场公开？`
      : `确定取消文件「${row.name}」的广场公开？`
  if (!confirm(tip)) return
  err.value = ''
  try {
    await adminApi.post(`/gallery/projects/${row.id}/unpublish`)
    const isProjectRoot = row.nodeType === 'FOLDER' && row.id === projectRootId.value
    if (isProjectRoot) {
      backToGrid()
      await loadProjects()
    } else {
      await loadNodes()
    }
  } catch (e) {
    err.value = e.response?.data?.error || e.message || '撤销公开失败'
  }
}

function openPublicPreview(row) {
  if (row.nodeType !== 'FILE' || !selectedOwner.value) return
  if (!getFilePreviewKind(row.name, row.contentType || '')) {
    err.value = '该文件类型不支持在线预览（支持常见图片、视频、PDF、Office 文档）'
    return
  }
  const r = router.resolve({
    name: 'file-preview-public',
    params: { ownerId: String(selectedOwner.value.id), nodeId: String(row.id) },
    query: {
      name: row.name,
      ...(row.contentType ? { ct: row.contentType } : {}),
    },
  })
  window.open(r.href, '_blank', 'noopener,noreferrer')
}

watch(parentId, () => {
  if (inProject.value) loadNodes()
})

onMounted(() => {
  if (!me.value?.id) {
    router.replace({ name: 'login' })
    return
  }
  loadProjects()
})
</script>

<template>
  <div class="explore-root">
      <p v-if="err" class="err">{{ err }}</p>

      <template v-if="!inProject">
        <div class="page-head gallery-shell-strip gallery-shell-strip--roomy gallery-shell-head">
          <h1>浏览广场</h1>
          <p class="sub">
            下方为<strong>公开项目</strong>网格（5 列 × 3 行，先行后列从左到右）；卡片宽高比 1:1.2，主信息水平居中紧凑排布，时间固定在底部。
            <template v-if="isAdmin">
              <br />
              <span class="admin-plaza-tip">管理员：可在卡片右上角「撤销公开」取消整个项目的广场公开；进入项目后亦可对文件/子文件夹撤销公开。</span>
            </template>
          </p>
        </div>

        <div class="gallery-shell-strip gallery-shell-strip--roomy explore-plaza-h2-wrap">
          <h2 class="plaza-h2">公开项目</h2>
        </div>

        <GalleryPlazaPanel
          class="gallery-shell-strip gallery-shell-strip--roomy explore-plaza-panel-outer"
          :loading="loading"
          :projects="projects"
          :total-pages="totalPages"
          :total-elements="totalElements"
          :page="page"
          :admin-mode="isAdmin"
          @open-project="openProject"
          @admin-unpublish="onAdminUnpublishProject"
          @prev-page="goPage(page - 1)"
          @next-page="goPage(page + 1)"
        />
      </template>

      <section v-else class="gallery-shell-strip gallery-shell-strip--roomy explore-detail">
        <div class="explore-detail-inner">
          <header class="explore-detail-head">
            <div class="explore-detail-head-row">
              <button type="button" class="btn ghost explore-back" @click="backToGrid">
                ← 返回项目列表
              </button>
              <span class="owner-pill">所有者 {{ selectedOwner.name }}</span>
              <button type="button" class="btn explore-view-members" @click="openViewMembersModal">
                查看成员
              </button>
            </div>
            <h2 class="explore-detail-h2">{{ currentLocationTitle }}</h2>
            <p class="explore-detail-hint">
              公开项目内的文件与文件夹（只读）
              <template v-if="isAdmin">
                <span class="admin-plaza-tip"> · 管理员可使用「撤销公开」取消该项的广场公开。</span>
              </template>
            </p>
          </header>

          <nav class="crumbs crumbs-plaza" aria-label="路径">
            <template v-for="(c, i) in crumbs" :key="c.id + '-' + i">
              <button type="button" class="crumb" @click="goCrumb(i)">{{ c.name }}</button>
              <span v-if="i < crumbs.length - 1" class="sep">/</span>
            </template>
          </nav>

          <div class="explore-table-card">
            <p v-if="loading" class="muted explore-loading">加载中…</p>
            <template v-else>
              <div class="table-scroll">
                <table class="table explore-table">
                  <thead>
                    <tr>
                      <th>名称</th>
                      <th>类型</th>
                      <th>大小</th>
                      <th>署名</th>
                      <th class="th-actions">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="row in nodes" :key="row.id">
                      <td class="td-name">
                        <button
                          v-if="row.nodeType === 'FOLDER'"
                          type="button"
                          class="fname"
                          @click="enterFolder(row)"
                        >
                          <span class="icon-folder" aria-hidden="true">📁</span>
                          {{ row.name }}
                        </button>
                        <span v-else class="file-line">
                          <span class="icon-file" aria-hidden="true">📄</span>
                          {{ row.name }}
                        </span>
                      </td>
                      <td>
                        <span class="type-pill">{{ row.nodeType }}</span>
                      </td>
                      <td class="td-mono">
                        {{ row.nodeType === 'FILE' && row.sizeBytes != null ? row.sizeBytes + ' B' : '—' }}
                      </td>
                      <td>{{ row.uploadedByName || '—' }}</td>
                      <td class="td-actions">
                        <button
                          v-if="row.nodeType === 'FILE' && getFilePreviewKind(row.name, row.contentType || '')"
                          type="button"
                          class="btn small"
                          @click="openPublicPreview(row)"
                        >
                          浏览
                        </button>
                        <button
                          v-if="row.nodeType === 'FILE'"
                          type="button"
                          class="btn small btn-fill"
                          @click="downloadPublic(row)"
                        >
                          下载
                        </button>
                        <button
                          v-if="isAdmin"
                          type="button"
                          class="btn small btn-admin-down"
                          @click="adminUnpublishPublicRow(row)"
                        >
                          撤销公开
                        </button>
                        <span v-if="row.nodeType === 'FOLDER' && !isAdmin" class="dash-placeholder">—</span>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <p v-if="nodes.length === 0" class="muted explore-empty">此目录下没有更多公开项</p>
            </template>
          </div>
        </div>
      </section>
    </div>

    <ProjectMembersViewModal
      :visible="viewMembersVisible"
      :loading="viewMembersLoading"
      :error="viewMembersErr"
      :members="viewMembersList"
      hint="以下为参与本公开项目的成员（含创建者），广场访客可只读查看。"
      @close="closeViewMembersModal"
    />
</template>

<style scoped>
.explore-root {
  width: 100%;
}
.page-head h1 {
  margin: 0 0 0.35rem;
  font-size: 1.35rem;
}
.page-head.gallery-shell-strip {
  text-align: center;
}
.sub {
  margin: 0;
  font-size: 0.86rem;
  color: #555;
  line-height: 1.5;
  max-width: none;
}
.admin-plaza-tip {
  color: #6a1b9a;
  font-weight: 500;
}
.plaza-h2 {
  margin: 0;
  font-size: 1.05rem;
  text-align: center;
  font-weight: 600;
  color: #222;
}
.explore-detail {
  min-height: min(52vh, 560px);
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
  padding-top: 1.1rem;
  padding-bottom: 1.35rem;
}
.explore-detail-inner {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 0;
  flex: 1;
  width: 100%;
  box-sizing: border-box;
  padding-left: 15px;
  padding-right: 15px;
}
.explore-detail-head {
  text-align: left;
  margin-bottom: 0.65rem;
}
.explore-detail-head-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-start;
  gap: 0.65rem 1rem;
  margin-bottom: 0.5rem;
  width: 100%;
}
.explore-view-members {
  margin-left: auto;
}
.explore-back {
  flex-shrink: 0;
}
.owner-pill {
  font-size: 0.72rem;
  color: #555;
  background: rgba(255, 255, 255, 0.85);
  border: 1px solid rgba(158, 158, 158, 0.65);
  border-radius: 999px;
  padding: 0.28rem 0.65rem;
  line-height: 1.2;
}
.explore-detail-h2 {
  margin: 0 0 0.25rem;
  font-size: 1.05rem;
  font-weight: 600;
  color: #222;
  letter-spacing: 0.02em;
}
.explore-detail-hint {
  margin: 0;
  font-size: 0.82rem;
  color: #666;
  line-height: 1.45;
}
.crumbs-plaza {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-start;
  gap: 0.2rem 0.35rem;
  margin-bottom: 1rem;
  padding: 0.55rem 0.75rem;
  border-radius: 10px;
  border: 1px solid rgba(158, 158, 158, 0.55);
  background: rgba(255, 255, 255, 0.88);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
  box-sizing: border-box;
}
.crumb {
  border: none;
  background: none;
  color: #1565c0;
  cursor: pointer;
  font: inherit;
  font-size: 0.88rem;
  font-weight: 500;
  padding: 0.15rem 0.2rem;
  border-radius: 4px;
  transition: background 0.12s, color 0.12s;
}
.crumb:hover {
  background: rgba(21, 101, 192, 0.08);
  color: #0d47a1;
}
.sep {
  color: #9e9e9e;
  font-size: 0.85rem;
  user-select: none;
}
.explore-table-card {
  border: 1px solid #9e9e9e;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.92);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
  overflow: hidden;
  box-sizing: border-box;
  transition: box-shadow 0.15s;
}
.explore-table-card:hover {
  box-shadow: 0 6px 16px rgba(21, 101, 192, 0.12);
}
.explore-loading,
.explore-empty {
  text-align: center;
  padding: 2rem 1rem;
  margin: 0;
}
.table-scroll {
  width: 100%;
  overflow-x: auto;
  -webkit-overflow-scrolling: touch;
}
.explore-table {
  width: 100%;
  min-width: 520px;
  border-collapse: collapse;
  font-size: 0.88rem;
}
.explore-table thead {
  background: linear-gradient(180deg, #f4f6fb 0%, #eef1f8 100%);
}
.explore-table th {
  font-weight: 600;
  color: #1a237e;
  font-size: 0.8rem;
  letter-spacing: 0.03em;
  text-transform: none;
  padding: 0.65rem 0.75rem;
  text-align: left;
  border-bottom: 1px solid rgba(158, 158, 158, 0.45);
}
.explore-table th.th-actions {
  text-align: right;
  width: 1%;
  white-space: nowrap;
}
.explore-table td {
  padding: 0.55rem 0.75rem;
  text-align: left;
  border-bottom: 1px solid rgba(224, 224, 224, 0.9);
  vertical-align: middle;
  color: #333;
}
.explore-table tbody tr {
  transition: background 0.12s;
}
.explore-table tbody tr:hover {
  background: rgba(21, 101, 192, 0.045);
}
.explore-table tbody tr:last-child td {
  border-bottom: none;
}
.td-name {
  font-weight: 500;
}
.td-mono {
  font-variant-numeric: tabular-nums;
  color: #555;
  font-size: 0.84rem;
}
.td-actions {
  text-align: right;
  white-space: nowrap;
}
.td-actions .btn + .btn {
  margin-left: 0.35rem;
}
.type-pill {
  display: inline-block;
  font-size: 0.72rem;
  font-weight: 600;
  color: #455a64;
  background: rgba(207, 216, 220, 0.45);
  padding: 0.12rem 0.45rem;
  border-radius: 6px;
  letter-spacing: 0.02em;
}
.fname {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  border: none;
  background: none;
  color: #1565c0;
  cursor: pointer;
  font: inherit;
  font-weight: 600;
  padding: 0.2rem 0.15rem;
  border-radius: 6px;
  text-align: left;
  transition: background 0.12s, color 0.12s;
}
.fname:hover {
  background: rgba(21, 101, 192, 0.1);
  color: #0d47a1;
}
.file-line {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  color: #37474f;
}
.icon-folder,
.icon-file {
  flex-shrink: 0;
  opacity: 0.92;
}
.dash-placeholder {
  color: #bdbdbd;
  font-size: 0.9rem;
}
.btn {
  padding: 0.35rem 0.65rem;
  border-radius: 6px;
  border: 1px solid #1565c0;
  background: rgba(255, 255, 255, 0.95);
  color: #1565c0;
  cursor: pointer;
  font: inherit;
  transition: border-color 0.15s, box-shadow 0.15s, background 0.15s;
}
.btn:hover:not(:disabled) {
  border-color: #0d47a1;
  box-shadow: 0 2px 8px rgba(21, 101, 192, 0.18);
}
.btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.btn.ghost {
  border-style: dashed;
  color: #444;
  border-color: #bbb;
}
.btn.ghost:hover:not(:disabled) {
  border-color: #1565c0;
  color: #1565c0;
  box-shadow: 0 2px 8px rgba(21, 101, 192, 0.12);
}
.btn.small {
  font-size: 0.8rem;
  padding: 0.28rem 0.5rem;
}
.btn-fill {
  background: #1565c0;
  color: #fff;
  border-color: #1565c0;
}
.btn-fill:hover:not(:disabled) {
  background: #0d47a1;
  border-color: #0d47a1;
  box-shadow: 0 2px 10px rgba(21, 101, 192, 0.35);
}
.btn-admin-down {
  border-color: #c62828;
  color: #c62828;
  background: #fff;
}
.btn-admin-down:hover:not(:disabled) {
  background: #ffebee;
  border-color: #c62828;
  color: #c62828;
}
.err {
  color: #c62828;
  margin-bottom: 0.75rem;
}
.muted {
  color: #666;
  font-size: 0.9rem;
}
</style>
