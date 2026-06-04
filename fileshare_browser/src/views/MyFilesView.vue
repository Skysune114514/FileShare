<script setup>
/**
 * 【页面】我的文件 — 登录用户个人空间文件树（主数据：/api/fs/nodes）。
 * 含：面包屑导航、新建项目/文件夹、上传与 ZIP、修改文件、历史快照、临时分享、广场公开、项目成员管理（/api/fs/projects）。
 * 布局：gallery-shell-strip + 与分享/修改同系列的弹窗白卡片。
 */
import { ref, computed, watch, onMounted, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fsApi, shareCreateApi } from '@/api/http'
import { getStoredUser } from '@/utils/auth'
import { downloadBlob } from '@/utils/download'
import { getFilePreviewKind } from '@/utils/filePreview'
import ProjectMembersViewModal from '@/components/ProjectMembersViewModal.vue'

const route = useRoute()
const router = useRouter()
const me = computed(() => getStoredUser())

/** 空间根项目列表：成员姓名逗号串超过该长度则省略 */
const PROJECT_MEMBER_NAMES_MAX = 28

function formatProjectMemberNames(raw) {
  if (raw == null || String(raw).trim() === '') return '—'
  const s = String(raw).trim()
  if (s.length <= PROJECT_MEMBER_NAMES_MAX) return s
  return `${s.slice(0, PROJECT_MEMBER_NAMES_MAX)}…`
}

const loading = ref(false)
const errorMsg = ref('')
const statusMsg = ref('')
const nodes = ref([])

const crumbs = ref([{ id: 0, name: '我的根目录' }])
const currentParentId = computed(() => crumbs.value[crumbs.value.length - 1].id)

/** 当前所在项目根（面包屑第二段），在空间根时为 null */
const projectRootId = computed(() => (crumbs.value.length >= 2 ? crumbs.value[1].id : null))
/** 当前用户在该项目中的角色：project_admin / member / '' */
const myProjectRole = ref('')

const newFolderName = ref('')
const fileInput = ref(null)
const zipInput = ref(null)
const folderInput = ref(null)
const folderUploading = ref(false)
const modifyFileInput = ref(null)

/** 修改文件弹窗 */
const modifyModalVisible = ref(false)
const modifyRow = ref(null)
const modifyErr = ref('')
const modifyBusy = ref(false)

/** 历史快照弹窗 */
const historyModalVisible = ref(false)
const historyRow = ref(null)
const historyList = ref([])
const historyErr = ref('')
const historyLoading = ref(false)

async function loadList() {
  if (!me.value?.id) return
  loading.value = true
  errorMsg.value = ''
  try {
    const { data } = await fsApi.get('/nodes', { params: { parentId: currentParentId.value } })
    nodes.value = data
  } catch (e) {
    errorMsg.value = e.response?.data?.error || e.message || '加载失败'
    nodes.value = []
  } finally {
    loading.value = false
  }
}

function enterFolder(row) {
  if (row.nodeType !== 'FOLDER') return
  crumbs.value = [...crumbs.value, { id: row.id, name: row.name }]
}

function goCrumb(index) {
  crumbs.value = crumbs.value.slice(0, index + 1)
}

async function createFolder() {
  const name = newFolderName.value.trim()
  if (!name) return
  errorMsg.value = ''
  try {
    await fsApi.post('/folders', { parentId: currentParentId.value, name })
    newFolderName.value = ''
    await loadList()
  } catch (e) {
    errorMsg.value = e.response?.data?.error || e.message || '创建失败'
  }
}

/** 在空间根目录新建「项目」文件夹 */
async function createProjectRoot() {
  const name = prompt('请输入新项目名称（将出现在空间根目录）')
  if (!name?.trim()) return
  errorMsg.value = ''
  try {
    await fsApi.post('/projects', { name: name.trim() })
    await loadList()
  } catch (e) {
    errorMsg.value = e.response?.data?.error || e.message || '创建项目失败'
  }
}

function triggerPickFile() {
  fileInput.value?.click()
}

async function onFileChange(ev) {
  const file = ev.target.files?.[0]
  ev.target.value = ''
  if (!file) return
  const fd = new FormData()
  fd.append('file', file)
  errorMsg.value = ''
  try {
    await fsApi.post('/files', fd, { params: { parentId: currentParentId.value } })
    await loadList()
  } catch (e) {
    const st = e.response?.status
    const data = e.response?.data
    if (st === 409 && data?.code === 'FILE_NAME_CONFLICT' && data.existingFileId != null) {
      const existName = data.fileName || file.name
      if (!confirm(`同目录已有文件「${existName}」，是否视为对该文件的修改（将保留历史快照）？`)) {
        return
      }
      if (!confirm(`确认将「${existName}」替换为上传文件「${file.name}」？`)) {
        return
      }
      try {
        await submitReplace(Number(data.existingFileId), file)
        await loadList()
      } catch (e2) {
        errorMsg.value = e2.response?.data?.error || e2.message || '替换失败'
      }
      return
    }
    errorMsg.value = data?.error || e.message || '上传失败'
  }
}

async function submitReplace(nodeId, file) {
  const fd = new FormData()
  fd.append('file', file)
  await fsApi.post(`/nodes/${nodeId}/replace`, fd)
}

function openModifyModal(row) {
  if (row.nodeType !== 'FILE') return
  modifyRow.value = row
  modifyErr.value = ''
  modifyModalVisible.value = true
}

function closeModifyModal() {
  modifyModalVisible.value = false
  modifyRow.value = null
}

function triggerModifyPick() {
  modifyFileInput.value?.click()
}

async function onModifyFileSelected(ev) {
  const file = ev.target.files?.[0]
  ev.target.value = ''
  if (!file || !modifyRow.value) return
  const oldName = modifyRow.value.name
  if (!confirm(`确认将「${oldName}」修改为上传文件「${file.name}」？`)) {
    return
  }
  modifyErr.value = ''
  modifyBusy.value = true
  try {
    await submitReplace(modifyRow.value.id, file)
    await loadList()
    closeModifyModal()
  } catch (e) {
    modifyErr.value = e.response?.data?.error || e.message || '替换失败'
  } finally {
    modifyBusy.value = false
  }
}

async function openHistoryModal(row) {
  if (row.nodeType !== 'FILE') return
  historyRow.value = row
  historyErr.value = ''
  historyList.value = []
  historyModalVisible.value = true
  historyLoading.value = true
  try {
    const { data } = await fsApi.get(`/nodes/${row.id}/revisions`)
    historyList.value = Array.isArray(data) ? data : []
  } catch (e) {
    historyErr.value = e.response?.data?.error || e.message || '加载失败'
  } finally {
    historyLoading.value = false
  }
}

function closeHistoryModal() {
  historyModalVisible.value = false
  historyRow.value = null
}

async function deleteRevision(rev) {
  if (!historyRow.value) return
  if (!confirm(`确定删除该条历史快照「${rev.fileName}」？不可恢复。`)) return
  historyErr.value = ''
  try {
    await fsApi.delete(`/nodes/${historyRow.value.id}/revisions/${rev.id}`)
    const { data } = await fsApi.get(`/nodes/${historyRow.value.id}/revisions`)
    historyList.value = Array.isArray(data) ? data : []
  } catch (e) {
    historyErr.value = e.response?.data?.error || e.message || '删除失败'
  }
}

async function downloadRevision(rev) {
  if (!historyRow.value) return
  try {
    await downloadBlob(`/api/fs/nodes/${historyRow.value.id}/revisions/${rev.id}/download`, rev.fileName || `snapshot-${rev.id}`)
  } catch (e) {
    historyErr.value = e.message || '下载失败'
  }
}

function fmtDt(v) {
  if (v == null || v === '') return '—'
  return String(v).replace('T', ' ')
}

function triggerPickZip() {
  zipInput.value?.click()
}

async function onZipChange(ev) {
  const file = ev.target.files?.[0]
  ev.target.value = ''
  if (!file) return
  const fd = new FormData()
  fd.append('file', file)
  errorMsg.value = ''
  try {
    await fsApi.post('/upload-zip', fd, { params: { parentId: currentParentId.value } })
    await loadList()
  } catch (e) {
    errorMsg.value = e.response?.data?.error || e.message || 'ZIP 上传失败'
  }
}

function triggerPickFolder() {
  statusMsg.value = ''
  if (currentParentId.value === 0) {
    errorMsg.value = '请先在列表中点进某个「项目」文件夹，再使用「上传文件夹」'
    return
  }
  errorMsg.value = ''
  folderInput.value?.click()
}

/** 从已快照的 File[] 推断根目录名与各文件相对路径（兼容仅有 file.name 的浏览器） */
function buildFolderUploadPayload(files) {
  if (!files?.length) {
    return { error: '未读到任何文件：请选择包含文件的文件夹' }
  }
  const rels = files.map((f) => (f.webkitRelativePath || f.name || '').replace(/\\/g, '/'))
  const segLists = rels.map((r) => r.split('/').filter(Boolean))
  let rootName = null
  const multi = segLists.filter((s) => s.length >= 2)
  if (multi.length > 0) {
    rootName = multi[0][0]
    const bad = multi.some((s) => s[0] !== rootName)
    if (bad) {
      return { error: '一次只能上传一个根文件夹' }
    }
  } else {
    const first = rels[0] || ''
    const slash = first.indexOf('/')
    rootName = slash > 0 ? first.slice(0, slash) : `上传文件夹_${Date.now()}`
  }
  const paths = rels.map((r, i) => {
    const segs = segLists[i]
    if (segs.length >= 2) return r
    if (segs.length === 1) return `${rootName}/${segs[0]}`
    return r
  })
  return { rootName, paths, files }
}

async function onFolderChange(ev) {
  const input = ev.target
  // input.files 是活引用：若先执行 input.value='' 再读 files，会变成 0 个文件
  const picked = input.files ? Array.from(input.files) : []
  input.value = ''
  statusMsg.value = ''
  if (!picked.length) {
    return
  }
  const built = buildFolderUploadPayload(picked)
  if (built.error) {
    errorMsg.value = built.error
    return
  }
  const { rootName, paths, files } = built
  const fd = new FormData()
  for (let i = 0; i < files.length; i++) {
    fd.append('files', files[i])
    fd.append('paths', paths[i])
  }
  if (import.meta.env.DEV) {
    console.info('[upload-folder] 准备上传', files.length, '个文件，rootName=', rootName, 'parentId=', currentParentId.value)
  }
  errorMsg.value = ''
  folderUploading.value = true
  try {
    const { data } = await fsApi.post('/upload-folder', fd, {
      params: { parentId: currentParentId.value, rootName },
      timeout: 600000,
    })
    statusMsg.value = `已上传文件夹「${data?.name ?? '文件夹'}」（${files.length} 个文件），正在进入该目录…`
    await loadList()
    const row = nodes.value.find((n) => n.id === data?.id)
    if (row?.nodeType === 'FOLDER') {
      enterFolder(row)
    }
  } catch (e) {
    const st = e.response?.status
    const detail = e.response?.data?.error || e.message || '文件夹上传失败'
    errorMsg.value = st ? `上传失败 (${st})：${detail}` : detail
    if (import.meta.env.DEV) {
      console.error('[upload-folder] 失败', st, e.response?.data || e)
    }
  } finally {
    folderUploading.value = false
  }
}

async function downloadFile(row) {
  errorMsg.value = ''
  try {
    await downloadBlob(`/api/fs/nodes/${row.id}/download`, row.name)
  } catch (e) {
    errorMsg.value = e.message || '下载失败'
  }
}

function openPreview(row) {
  if (row.nodeType !== 'FILE') return
  if (!getFilePreviewKind(row.name, row.contentType || '')) {
    errorMsg.value = '该文件类型不支持在线预览（支持常见图片、视频、PDF、Office 文档）'
    return
  }
  const r = router.resolve({
    name: 'file-preview-mine',
    params: { nodeId: String(row.id) },
    query: {
      name: row.name,
      ...(row.contentType ? { ct: row.contentType } : {}),
    },
  })
  window.open(r.href, '_blank', 'noopener,noreferrer')
}

function openRevisionPreview(rev) {
  if (!historyRow.value) return
  const name = rev.fileName || 'file'
  const ct = rev.contentType || ''
  if (!getFilePreviewKind(name, ct)) {
    historyErr.value = '该快照类型不支持在线预览'
    return
  }
  const r = router.resolve({
    name: 'file-preview-revision',
    params: {
      nodeId: String(historyRow.value.id),
      revisionId: String(rev.id),
    },
    query: {
      name,
      ...(ct ? { ct } : {}),
    },
  })
  window.open(r.href, '_blank', 'noopener,noreferrer')
}

async function renameFolderRow(row) {
  if (row.nodeType !== 'FOLDER') return
  const next = prompt(`将文件夹「${row.name}」改名为`, row.name)
  if (next == null) return
  const name = next.trim()
  if (!name || name === row.name) return
  errorMsg.value = ''
  try {
    await fsApi.patch(`/nodes/${row.id}/name`, { name })
    const ci = crumbs.value.findIndex((c) => c.id === row.id)
    if (ci >= 0) {
      crumbs.value = crumbs.value.map((c, i) => (i === ci ? { ...c, name } : c))
    }
    await loadList()
  } catch (e) {
    errorMsg.value = e.response?.data?.error || e.message || '改名失败'
  }
}

async function removeNode(row) {
  const tip =
    row.nodeType === 'FOLDER'
      ? `确定删除「${row.name}」？文件夹将递归删除其下全部内容。`
      : `确定删除文件「${row.name}」？`
  if (!confirm(tip)) return
  errorMsg.value = ''
  try {
    await fsApi.delete(`/nodes/${row.id}`)
    await loadList()
  } catch (e) {
    errorMsg.value = e.response?.data?.error || e.message || '删除失败'
  }
}

const shareModalVisible = ref(false)
const shareRow = ref(null)
const shareTtl = ref('24h')
const sharePin = ref('')
const shareCreateError = ref('')
const shareCreated = ref(null)
const shareSubmitting = ref(false)

function openShareModal(row) {
  if (row.nodeType !== 'FILE') return
  shareRow.value = row
  shareTtl.value = '24h'
  sharePin.value = ''
  shareCreateError.value = ''
  shareCreated.value = null
  shareModalVisible.value = true
}

function closeShareModal() {
  shareModalVisible.value = false
}

async function submitShareCreate() {
  if (!shareRow.value) return
  shareCreateError.value = ''
  shareSubmitting.value = true
  try {
    const body = { nodeId: shareRow.value.id, ttl: shareTtl.value }
    const pin = sharePin.value.trim()
    if (pin) body.pin = pin
    const { data } = await shareCreateApi.post('', body)
    const path = data.path || `/s/${data.code}`
    shareCreated.value = {
      code: data.code,
      path,
      url: `${window.location.origin}${path}`,
      ttlSeconds: data.ttlSeconds,
    }
  } catch (e) {
    shareCreateError.value = e.response?.data?.error || e.message || '创建失败'
  } finally {
    shareSubmitting.value = false
  }
}

async function copyShareUrl() {
  if (!shareCreated.value?.url) return
  try {
    await navigator.clipboard.writeText(shareCreated.value.url)
    alert('链接已复制')
  } catch {
    prompt('复制以下链接', shareCreated.value.url)
  }
}

function canToggleGalleryPublic(row) {
  return row.nodeType === 'FOLDER' && row.parentId === 0 && row.ownerUserId === me.value?.id
}

function canDeleteInTable(row) {
  if (crumbs.value.length === 1) {
    return row.parentId === 0 && row.ownerUserId === me.value?.id
  }
  if (myProjectRole.value === 'project_admin') return true
  if (row.nodeType === 'FILE') {
    const mineById = row.uploadedByUserId != null && row.uploadedByUserId === me.value?.id
    // 老数据没有 uploadedByUserId 时退化为昵称比对
    const mineByName = row.uploadedByUserId == null && me.value?.name && row.uploadedByName === me.value.name
    if (mineById || mineByName) return true
  }
  return false
}

/** 查看成员（只读，项目内所有成员） */
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
    const { data } = await fsApi.get(`/projects/${rid}/members`)
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

/** 项目成员管理（仅项目管理员） */
const membersModalVisible = ref(false)
const membersList = ref([])
const membersErr = ref('')
const membersLoading = ref(false)
const newMemberUserId = ref('')
const newMemberRole = ref('member')

async function openMembersModal() {
  const rid = projectRootId.value
  if (rid == null) return
  membersModalVisible.value = true
  membersErr.value = ''
  membersList.value = []
  newMemberUserId.value = ''
  newMemberRole.value = 'member'
  membersLoading.value = true
  try {
    const { data } = await fsApi.get(`/projects/${rid}/members`)
    membersList.value = Array.isArray(data) ? data : []
  } catch (e) {
    membersErr.value = e.response?.data?.error || e.message || '加载失败'
  } finally {
    membersLoading.value = false
  }
}

function closeMembersModal() {
  membersModalVisible.value = false
}

async function addMemberSubmit() {
  const rid = projectRootId.value
  const uid = Number(newMemberUserId.value)
  if (!rid || !Number.isFinite(uid) || uid <= 0) {
    membersErr.value = '请输入有效的用户数字 ID'
    return
  }
  membersErr.value = ''
  try {
    await fsApi.post(`/projects/${rid}/members`, { userId: uid, role: newMemberRole.value })
    newMemberUserId.value = ''
    const { data } = await fsApi.get(`/projects/${rid}/members`)
    membersList.value = Array.isArray(data) ? data : []
  } catch (e) {
    membersErr.value = e.response?.data?.error || e.message || '添加失败'
  }
}

async function removeMemberRow(m) {
  const rid = projectRootId.value
  if (rid == null) return
  if (!confirm(`将「${m.name}」移出本项目？`)) return
  membersErr.value = ''
  try {
    await fsApi.delete(`/projects/${rid}/members/${m.userId}`)
    const { data } = await fsApi.get(`/projects/${rid}/members`)
    membersList.value = Array.isArray(data) ? data : []
  } catch (e) {
    membersErr.value = e.response?.data?.error || e.message || '移除失败'
  }
}

async function toggleGalleryPublic(row, makePublic) {
  const tip = makePublic
    ? `将「${row.name}」及其下全部子文件/文件夹在广场对他人可见？`
    : `取消「${row.name}」及其下全部内容的广场公开？`
  if (!confirm(tip)) return
  errorMsg.value = ''
  try {
    await fsApi.patch(`/nodes/${row.id}/public`, { public: makePublic })
    await loadList()
  } catch (e) {
    errorMsg.value = e.response?.data?.error || e.message || '更新失败'
  }
}

watch(currentParentId, () => {
  loadList()
})

watch(
  projectRootId,
  async (rootId) => {
    myProjectRole.value = ''
    if (rootId == null) return
    try {
      const { data } = await fsApi.get(`/projects/${rootId}/my-role`)
      myProjectRole.value = data?.role || ''
    } catch {
      myProjectRole.value = ''
    }
  },
  { immediate: true },
)

watch(
  () => route.query.newProject,
  async (v) => {
    if (v == null || v === '') return
    await router.replace({ name: 'my-files', query: {} })
    await nextTick()
    await createProjectRoot()
  },
)

onMounted(() => {
  if (!me.value?.id) {
    router.replace({ name: 'login' })
    return
  }
  loadList()
})
</script>

<template>
  <div class="my-files-page">
    <header class="top gallery-shell-strip my-files-top">
      <div class="my-files-top-head">
        <div>
          <h1>我的文件</h1>
          <p class="sub">
            顶部导航可去<strong>广场</strong>或<strong>新建项目</strong>。仅在<strong>项目根文件夹</strong>（空间根下的一级目录）可设为广场公开。
          </p>
        </div>
        <div v-if="projectRootId != null" class="my-files-top-actions">
          <button type="button" class="btn" @click="openViewMembersModal">查看成员</button>
          <button
            v-if="myProjectRole === 'project_admin'"
            type="button"
            class="btn"
            @click="openMembersModal"
          >
            成员管理
          </button>
        </div>
      </div>
    </header>

    <div class="my-files-main gallery-shell-strip">
      <nav class="crumbs">
        <template v-for="(c, i) in crumbs" :key="c.id + '-' + i">
          <button type="button" class="crumb" @click="goCrumb(i)">{{ c.name }}</button>
          <span v-if="i < crumbs.length - 1" class="sep">/</span>
        </template>
      </nav>

      <section class="toolbar">
        <div class="row">
          <button type="button" class="btn primary" @click="createProjectRoot">新建项目</button>
          <span class="hint">在项目内可继续新建子文件夹与上传文件</span>
        </div>
        <div class="row">
          <input v-model="newFolderName" type="text" placeholder="当前目录下新文件夹名称" class="input" />
          <button type="button" class="btn" @click="createFolder">新建文件夹</button>
        </div>
        <div class="row">
          <button type="button" class="btn primary" @click="triggerPickFile">上传文件</button>
          <input ref="fileInput" type="file" class="hidden" @change="onFileChange" />
          <button type="button" class="btn" @click="triggerPickZip">上传 ZIP（整包成树）</button>
          <input ref="zipInput" type="file" accept=".zip,application/zip" class="hidden" @change="onZipChange" />
          <button type="button" class="btn" :disabled="folderUploading" @click="triggerPickFolder">
            {{ folderUploading ? '文件夹上传中…' : '上传文件夹' }}
          </button>
          <input
            ref="folderInput"
            type="file"
            class="hidden"
            webkitdirectory
            mozdirectory
            multiple
            @change="onFolderChange"
          />
          <button type="button" class="btn ghost" :disabled="loading" @click="loadList">刷新</button>
        </div>
      </section>

      <p v-if="statusMsg" class="ok">{{ statusMsg }}</p>
      <p v-if="errorMsg" class="err">{{ errorMsg }}</p>
      <p v-if="loading" class="loading">加载中…</p>

      <div v-else class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>名称</th>
            <th>类型</th>
            <th>大小</th>
            <th>署名</th>
            <th v-if="currentParentId === 0">团队成员</th>
            <th>广场</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in nodes" :key="row.id">
            <td>
              <button
                v-if="row.nodeType === 'FOLDER'"
                type="button"
                class="link"
                @click="enterFolder(row)"
              >
                📁 {{ row.name }}
              </button>
              <span v-else>📄 {{ row.name }}</span>
            </td>
            <td>{{ row.nodeType }}</td>
            <td>{{ row.nodeType === 'FILE' && row.sizeBytes != null ? row.sizeBytes + ' B' : '—' }}</td>
            <td class="sig">{{ row.uploadedByName || '—' }}</td>
            <td
              v-if="currentParentId === 0"
              class="member-names"
              :title="row.projectMemberNames || ''"
            >
              {{ formatProjectMemberNames(row.projectMemberNames) }}
            </td>
            <td class="pub">
              <span v-if="row.isPublic" class="badge on">公开</span>
              <span v-else class="badge off">私有</span>
              <template v-if="canToggleGalleryPublic(row)">
                <button
                  v-if="!row.isPublic"
                  type="button"
                  class="btn tiny"
                  @click="toggleGalleryPublic(row, true)"
                >
                  公开 subtree
                </button>
                <button
                  v-else
                  type="button"
                  class="btn tiny muted"
                  @click="toggleGalleryPublic(row, false)"
                >
                  取消公开
                </button>
              </template>
            </td>
            <td class="actions">
              <button
                v-if="row.nodeType === 'FILE'"
                type="button"
                class="btn small"
                @click="openModifyModal(row)"
              >
                修改
              </button>
              <button
                v-if="row.nodeType === 'FILE'"
                type="button"
                class="btn small"
                @click="openHistoryModal(row)"
              >
                历史
              </button>
              <button
                v-if="row.nodeType === 'FILE' && getFilePreviewKind(row.name, row.contentType || '')"
                type="button"
                class="btn small"
                @click="openPreview(row)"
              >
                浏览
              </button>
              <button
                v-if="row.nodeType === 'FILE'"
                type="button"
                class="btn small"
                @click="openShareModal(row)"
              >
                分享
              </button>
              <button
                v-if="row.nodeType === 'FILE'"
                type="button"
                class="btn small"
                @click="downloadFile(row)"
              >
                下载
              </button>
              <button
                v-if="row.nodeType === 'FOLDER'"
                type="button"
                class="btn small"
                @click="renameFolderRow(row)"
              >
                改名
              </button>
              <button
                v-if="canDeleteInTable(row)"
                type="button"
                class="btn small danger"
                @click="removeNode(row)"
              >
                删除
              </button>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-if="nodes.length === 0" class="empty">当前目录为空，可新建文件夹或上传文件</p>
      </div>
    </div>
  <Transition name="share-modal">
    <div
      v-if="shareModalVisible"
      class="share-modal-back"
      role="dialog"
      aria-modal="true"
      @click.self="closeShareModal"
    >
      <div class="share-modal">
        <h2 class="share-modal-title">临时分享</h2>
        <p v-if="shareRow" class="share-file-hint">文件：{{ shareRow.name }}</p>
        <p class="share-desc">生成单文件分享链接，可选 PIN；有效期可选 3 / 24 / 72 小时（默认 24 小时）。</p>
        <label class="share-label">
          有效期
          <select v-model="shareTtl" class="share-select">
            <option value="3h">3 小时</option>
            <option value="24h">24 小时</option>
            <option value="72h">72 小时</option>
          </select>
        </label>
        <label class="share-label">
          提取 PIN（可选，4～12 位数字或字母）
          <input v-model="sharePin" type="password" maxlength="12" autocomplete="off" class="share-input" />
        </label>
        <p v-if="shareCreateError" class="err">{{ shareCreateError }}</p>
        <Transition name="share-result">
          <div v-if="shareCreated" class="share-result">
            <p class="share-url-line">{{ shareCreated.url }}</p>
            <button type="button" class="btn primary" @click="copyShareUrl">复制链接</button>
          </div>
        </Transition>
        <div class="share-actions">
          <button type="button" class="btn" :disabled="shareSubmitting" @click="closeShareModal">关闭</button>
          <button
            v-if="!shareCreated"
            type="button"
            class="btn primary"
            :disabled="shareSubmitting"
            @click="submitShareCreate"
          >
            {{ shareSubmitting ? '生成中…' : '生成分享' }}
          </button>
        </div>
      </div>
    </div>
  </Transition>

  <Transition name="share-modal">
    <div
      v-if="modifyModalVisible"
      class="share-modal-back"
      role="dialog"
      aria-modal="true"
      @click.self="closeModifyModal"
    >
      <div class="share-modal">
        <h2 class="share-modal-title">修改文件</h2>
        <p v-if="modifyRow" class="share-file-hint">当前：{{ modifyRow.name }}</p>
        <p class="share-desc">
          请选择新文件内容。确认后将把<strong>当前版本</strong>写入历史快照（每文件最多保留 20 条），再替换为上传内容。
        </p>
        <input ref="modifyFileInput" type="file" class="hidden" @change="onModifyFileSelected" />
        <p v-if="modifyErr" class="err">{{ modifyErr }}</p>
        <div class="share-actions">
          <button type="button" class="btn" :disabled="modifyBusy" @click="closeModifyModal">取消</button>
          <button type="button" class="btn primary" :disabled="modifyBusy" @click="triggerModifyPick">
            {{ modifyBusy ? '处理中…' : '选择新文件' }}
          </button>
        </div>
      </div>
    </div>
  </Transition>

  <Transition name="share-modal">
    <div
      v-if="historyModalVisible"
      class="share-modal-back"
      role="dialog"
      aria-modal="true"
      @click.self="closeHistoryModal"
    >
      <div class="share-modal history-modal-wide">
        <h2 class="share-modal-title">历史快照</h2>
        <p v-if="historyRow" class="share-file-hint">文件：{{ historyRow.name }}</p>
        <p class="share-desc">以下为修改前的历史版本（新→旧），最多保留 20 条；可删除单条快照。支持预览的类型可点「浏览」新窗口打开，其余请用「下载该版」。当前最新内容请在列表中下载。</p>
        <p v-if="historyErr" class="err">{{ historyErr }}</p>
        <p v-if="historyLoading" class="muted">加载中…</p>
        <div v-else-if="historyList.length === 0" class="muted">暂无历史快照</div>
        <ul v-else class="rev-list">
          <li v-for="r in historyList" :key="r.id" class="rev-item">
            <span class="rev-meta">{{ r.fileName }} · {{ r.sizeBytes ?? '?' }} B · {{ fmtDt(r.createdAt) }} · {{ r.createdByName }}</span>
            <span class="rev-actions">
              <button
                v-if="getFilePreviewKind(r.fileName, r.contentType || '')"
                type="button"
                class="btn small"
                @click="openRevisionPreview(r)"
              >
                浏览
              </button>
              <button type="button" class="btn small" @click="downloadRevision(r)">下载该版</button>
              <button type="button" class="btn small danger" @click="deleteRevision(r)">删除</button>
            </span>
          </li>
        </ul>
        <div class="share-actions">
          <button type="button" class="btn" @click="closeHistoryModal">关闭</button>
        </div>
      </div>
    </div>
  </Transition>

  <Transition name="share-modal">
    <div
      v-if="membersModalVisible"
      class="share-modal-back"
      role="dialog"
      aria-modal="true"
      @click.self="closeMembersModal"
    >
      <div class="share-modal history-modal-wide">
        <h2 class="share-modal-title">项目成员</h2>
        <p class="share-desc">
          添加尚未在本项目成员表中的用户：输入其<strong>数字用户 ID</strong>（可在管理或数据库中查看），并选择角色
          member 或 project_admin；移除为硬删除。创建者不可在此移除。
        </p>
        <p v-if="membersErr" class="err">{{ membersErr }}</p>
        <p v-if="membersLoading" class="muted">加载中…</p>
        <div v-else class="member-add-row">
          <label class="share-label member-add-label">
            新成员用户 ID
            <input v-model="newMemberUserId" type="number" min="1" class="share-input" placeholder="例如 2" />
          </label>
          <label class="share-label member-add-label">
            角色
            <select v-model="newMemberRole" class="share-select">
              <option value="member">member</option>
              <option value="project_admin">project_admin</option>
            </select>
          </label>
          <button type="button" class="btn primary" @click="addMemberSubmit">添加成员</button>
        </div>
        <p class="muted small-gap">当前成员</p>
        <ul class="rev-list">
          <li v-for="m in membersList" :key="m.userId" class="rev-item">
            <span class="rev-meta">{{ m.name }} · {{ m.email }} · {{ m.role }}</span>
            <span class="rev-actions">
              <button type="button" class="btn small danger" @click="removeMemberRow(m)">移除</button>
            </span>
          </li>
        </ul>
        <div class="share-actions">
          <button type="button" class="btn" @click="closeMembersModal">关闭</button>
        </div>
      </div>
    </div>
  </Transition>

  <ProjectMembersViewModal
    :visible="viewMembersVisible"
    :loading="viewMembersLoading"
    :error="viewMembersErr"
    :members="viewMembersList"
    @close="closeViewMembersModal"
  />
  </div>
</template>

<style scoped>
.my-files-page {
  width: 100%;
}
.top {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  align-items: center;
  gap: 1rem;
}
.my-files-top {
  text-align: center;
}
.my-files-top-head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
  width: 100%;
  text-align: left;
}
.my-files-top-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  flex-shrink: 0;
}
h1 {
  margin: 0 0 0.35rem;
  font-size: 1.45rem;
}
.sub {
  margin: 0;
  font-size: 0.86rem;
  color: #555;
  line-height: 1.5;
  max-width: none;
}
.crumbs {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.25rem;
  margin-bottom: 1rem;
  padding: 0.5rem 0;
  border-bottom: 1px solid #e0e0e0;
}
.toolbar {
  margin-bottom: 1rem;
}
.pub {
  font-size: 0.82rem;
  vertical-align: middle;
}
.badge {
  display: inline-block;
  padding: 0.1rem 0.35rem;
  border-radius: 4px;
  margin-right: 0.35rem;
  font-size: 0.75rem;
}
.badge.on {
  background: #e8f5e9;
  color: #2e7d32;
}
.badge.off {
  background: #f5f5f5;
  color: #757575;
}
.btn.tiny {
  padding: 0.15rem 0.4rem;
  font-size: 0.75rem;
  border: 1px solid #ccc;
  background: #fff;
  border-radius: 4px;
  cursor: pointer;
}
.btn.tiny.muted {
  color: #666;
}
.crumb {
  border: none;
  background: none;
  color: #1565c0;
  cursor: pointer;
  padding: 0.2rem 0.1rem;
  font: inherit;
}
.crumb:hover {
  text-decoration: underline;
}
.sep {
  color: #999;
}
.meta {
  margin-left: auto;
  font-size: 0.8rem;
  color: #888;
}
.toolbar .row {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  align-items: center;
  margin-bottom: 0.5rem;
}
.toolbar .row:last-child {
  margin-bottom: 0;
}
.hint {
  font-size: 0.82rem;
  color: #666;
}
.input {
  padding: 0.45rem 0.6rem;
  border: 1px solid #ccc;
  border-radius: 6px;
  min-width: 200px;
  font: inherit;
}
.btn {
  padding: 0.45rem 0.75rem;
  border-radius: 6px;
  border: 1px solid #bbb;
  background: #fff;
  cursor: pointer;
  font: inherit;
}
.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.btn.primary {
  background: #1565c0;
  color: #fff;
  border-color: #1565c0;
}
.btn.ghost {
  border-style: dashed;
}
.btn.small {
  padding: 0.25rem 0.5rem;
  font-size: 0.85rem;
}
.btn.danger {
  border-color: #c62828;
  color: #c62828;
}
.hidden {
  display: none;
}
.ok {
  color: #2e7d32;
  font-size: 0.9rem;
  margin-bottom: 0.75rem;
}
.err {
  color: #c62828;
  font-size: 0.9rem;
  margin-bottom: 0.75rem;
}
.loading {
  color: #666;
  margin-bottom: 0.75rem;
}
.table-wrap {
  overflow-x: auto;
}
.table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.92rem;
}
.table th,
.table td {
  border-bottom: 1px solid #eee;
  padding: 0.55rem 0.4rem;
  text-align: left;
}
.table th {
  color: #666;
  font-weight: 600;
}
.sig {
  color: #5d4037;
  font-size: 0.88rem;
}
.member-names {
  max-width: 12rem;
  color: #455a64;
  font-size: 0.86rem;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.link {
  border: none;
  background: none;
  color: #1565c0;
  cursor: pointer;
  font: inherit;
  padding: 0;
  text-align: left;
}
.link:hover {
  text-decoration: underline;
}
.actions {
  display: flex;
  gap: 0.35rem;
  flex-wrap: wrap;
}
.empty {
  color: #888;
  margin: 1rem 0 0;
  text-align: center;
  font-size: 0.9rem;
}
.share-modal-back {
  position: fixed;
  inset: 0;
  z-index: 2000;
  background: rgba(0, 0, 0, 0.35);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1rem;
  box-sizing: border-box;
}
.share-modal {
  background: #fff;
  border-radius: 12px;
  max-width: 26rem;
  width: 100%;
  padding: 1.25rem 1.35rem;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.12);
}
.share-modal-title {
  margin: 0 0 0.5rem;
  font-size: 1.15rem;
}
.share-file-hint {
  margin: 0 0 0.5rem;
  font-size: 0.88rem;
  color: #333;
  word-break: break-all;
}
.share-desc {
  margin: 0 0 1rem;
  font-size: 0.82rem;
  color: #666;
  line-height: 1.45;
}
.share-label {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  font-size: 0.85rem;
  color: #444;
  margin-bottom: 0.85rem;
}
.share-select,
.share-input {
  padding: 0.45rem 0.55rem;
  border: 1px solid #ccc;
  border-radius: 8px;
  font: inherit;
}
.share-result {
  margin: 0.75rem 0 1rem;
  padding: 0.65rem;
  background: #f5f5f5;
  border-radius: 8px;
}
.share-url-line {
  margin: 0 0 0.65rem;
  font-size: 0.78rem;
  word-break: break-all;
  font-family: ui-monospace, monospace;
  color: #1565c0;
}
.share-actions {
  display: flex;
  gap: 0.5rem;
  justify-content: flex-end;
  flex-wrap: wrap;
}

/* 弹窗整体 + 卡片：淡入淡出，略带上移（scoped 下过渡类与根元素同节点，选择器生效） */
.share-modal-enter-active,
.share-modal-leave-active {
  transition: opacity 0.24s ease;
}
.share-modal-enter-active .share-modal,
.share-modal-leave-active .share-modal {
  transition: opacity 0.24s ease, transform 0.24s ease;
}
.share-modal-enter-from,
.share-modal-leave-to {
  opacity: 0;
}
.share-modal-enter-from .share-modal,
.share-modal-leave-to .share-modal {
  opacity: 0;
  transform: translateY(10px) scale(0.98);
}

/* 生成成功后结果条：淡入 */
.share-result-enter-active,
.share-result-leave-active {
  transition: opacity 0.22s ease, transform 0.22s ease;
}
.share-result-enter-from,
.share-result-leave-to {
  opacity: 0;
  transform: translateY(6px);
}
.history-modal-wide {
  max-width: 34rem;
}
.rev-list {
  list-style: none;
  margin: 0 0 1rem;
  padding: 0;
  max-height: 50vh;
  overflow-y: auto;
}
.rev-item {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  padding: 0.5rem 0;
  border-bottom: 1px solid #eee;
  font-size: 0.82rem;
}
.rev-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.35rem;
  flex-shrink: 0;
}
.rev-meta {
  color: #444;
  word-break: break-all;
  flex: 1;
  min-width: 0;
}
.member-add-row,
.member-search-row {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
  align-items: flex-end;
  margin-bottom: 1rem;
}
.member-add-label {
  margin-bottom: 0 !important;
  min-width: 10rem;
}
.small-gap {
  font-size: 0.82rem;
  margin: 0.65rem 0 0.35rem;
}
.muted {
  color: #666;
  font-size: 0.88rem;
}
</style>
