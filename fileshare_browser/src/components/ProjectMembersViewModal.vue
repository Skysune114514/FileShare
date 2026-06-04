<script setup>
/**
 * 只读：展示项目成员列表（样式与分享/历史弹窗一致）。
 */
defineProps({
  visible: { type: Boolean, default: false },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
  members: { type: Array, default: () => [] },
  title: { type: String, default: '项目成员' },
  hint: { type: String, default: '以下为参与本项目的成员（含创建者）。' },
})

defineEmits(['close'])

function roleLabel(role) {
  if (role === 'project_admin') return '项目管理员'
  if (role === 'member') return '成员'
  return role || '—'
}
</script>

<template>
  <Transition name="share-modal">
    <div
      v-if="visible"
      class="share-modal-back"
      role="dialog"
      aria-modal="true"
      aria-labelledby="project-members-view-title"
      @click.self="$emit('close')"
    >
      <div class="share-modal history-modal-wide">
        <h2 id="project-members-view-title" class="share-modal-title">{{ title }}</h2>
        <p v-if="hint" class="share-desc">{{ hint }}</p>
        <p v-if="error" class="err">{{ error }}</p>
        <p v-if="loading" class="muted">加载中…</p>
        <div v-else-if="!members.length" class="muted">暂无成员记录</div>
        <ul v-else class="rev-list">
          <li v-for="m in members" :key="m.userId" class="rev-item">
            <span class="rev-meta">{{ m.name }} · {{ m.email }} · {{ roleLabel(m.role) }}</span>
          </li>
        </ul>
        <div class="share-actions">
          <button type="button" class="btn primary" @click="$emit('close')">关闭</button>
        </div>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
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
  max-width: 28rem;
  width: 100%;
  padding: 1.25rem 1.35rem;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.12);
}
.history-modal-wide {
  max-width: 32rem;
}
.share-modal-title {
  margin: 0 0 0.5rem;
  font-size: 1.15rem;
}
.share-desc {
  margin: 0 0 1rem;
  font-size: 0.82rem;
  color: #666;
  line-height: 1.45;
}
.share-actions {
  display: flex;
  gap: 0.5rem;
  justify-content: flex-end;
  flex-wrap: wrap;
  margin-top: 1rem;
}
.rev-list {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 16rem;
  overflow-y: auto;
}
.rev-item {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem;
  padding: 0.45rem 0;
  border-bottom: 1px solid #eee;
}
.rev-meta {
  color: #444;
  word-break: break-all;
  flex: 1;
  min-width: 0;
  font-size: 0.88rem;
}
.err {
  color: #c62828;
  font-size: 0.9rem;
  margin-bottom: 0.75rem;
}
.muted {
  color: #666;
  font-size: 0.88rem;
}
.btn {
  padding: 0.45rem 0.75rem;
  border-radius: 6px;
  border: 1px solid #bbb;
  background: #fff;
  cursor: pointer;
  font: inherit;
}
.btn.primary {
  background: #1565c0;
  color: #fff;
  border-color: #1565c0;
}
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
</style>
