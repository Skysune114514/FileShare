<script setup>
/**
 * 【组件】广场项目网格面板 — 展示公开项目卡片、分页、（管理员）撤销公开事件；由 ExploreView 组装数据传入。
 */
import dirIconClosedUrl from '@/resource/dir_icon-closed.svg'
import dirIconOpenUrl from '@/resource/dir_icon-open.svg'

defineProps({
  loading: { type: Boolean, default: false },
  projects: { type: Array, default: () => [] },
  totalPages: { type: Number, default: 0 },
  totalElements: { type: Number, default: 0 },
  page: { type: Number, default: 1 },
  /** 管理员在广场网格卡片上显示「撤销公开」 */
  adminMode: { type: Boolean, default: false },
})

const emit = defineEmits(['open-project', 'prev-page', 'next-page', 'admin-unpublish'])
</script>

<template>
  <div class="gallery-plaza-panel">
    <div class="gallery-plaza-body">
      <div class="gallery-plaza-stack">
        <p v-if="loading" class="muted gallery-plaza-slab">加载中…</p>

        <template v-else>
          <div class="gallery-plaza-grid-wrap">
            <div class="grid-outer">
              <div class="project-grid">
                <div
                  v-for="p in projects"
                  :key="`${p.ownerUserId}-${p.id}`"
                  class="project-card-wrap"
                >
                  <button
                    type="button"
                    class="project-card"
                    @click="emit('open-project', p)"
                  >
                    <div class="card-main">
                      <div class="card-folder-glyphs" aria-hidden="true">
                        <img
                          :src="dirIconClosedUrl"
                          alt=""
                          class="folder-svg folder-svg--closed"
                        />
                        <img
                          :src="dirIconOpenUrl"
                          alt=""
                          class="folder-svg folder-svg--open"
                        />
                      </div>
                      <span class="card-title">{{ p.name }}</span>
                      <span class="card-meta">上传者 {{ p.uploadedByName || '—' }}</span>
                    </div>
                    <span v-if="p.updatedAt" class="card-date">{{ p.updatedAt }}</span>
                  </button>
                  <button
                    v-if="adminMode"
                    type="button"
                    class="card-admin-unpublish"
                    title="取消该项目的广场公开"
                    @click.stop="emit('admin-unpublish', p)"
                  >
                    撤销公开
                  </button>
                </div>
              </div>
            </div>
            <p v-if="!projects.length" class="muted empty-hint">
              暂无公开项目。请到「我的文件」中新建项目并将<strong>项目根文件夹</strong>设为公开。
            </p>

            <nav v-if="totalPages > 1" class="pager" aria-label="分页">
              <button
                type="button"
                class="btn ghost"
                :disabled="page <= 1"
                @click="emit('prev-page')"
              >
                上一页
              </button>
              <span class="pager-info">
                第 {{ page }} / {{ totalPages }} 页（共 {{ totalElements }} 个）
              </span>
              <button
                type="button"
                class="btn ghost"
                :disabled="page >= totalPages"
                @click="emit('next-page')"
              >
                下一页
              </button>
            </nav>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.gallery-plaza-body {
  display: flex;
  flex-direction: column;
  justify-content:flex-start;
  align-items: center;
  min-height: min(72vh, 680px);
  padding: 0.35rem 0 0.65rem;
  box-sizing: border-box;
}
.gallery-plaza-stack {
  width: 100%;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  box-sizing: border-box;
}
.gallery-plaza-grid-wrap,
.gallery-plaza-slab {
  width: 100%;
  margin: 0;
  background: transparent;
  box-sizing: border-box;
  padding: 0;
  border-radius: 0;
}
.gallery-plaza-grid-wrap {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  justify-content: flex-start;
}
.gallery-plaza-slab {
  text-align: center;
}
.grid-outer {
  display: flex;
  justify-content: center;
  align-items: flex-start;
  width: 100%;
  overflow-x: auto;
  border: none;
  background: transparent;
  padding: 0;
  margin: 0 0 0.5rem;
}
.project-card-wrap {
  position: relative;
  width: 100%;
  min-width: 0;
}
.card-admin-unpublish {
  position: absolute;
  top: 0.2rem;
  right: 0.2rem;
  z-index: 2;
  padding: 0.12rem 0.35rem;
  font-size: 0.62rem;
  font-weight: 700;
  line-height: 1.2;
  border-radius: 5px;
  border: 1px solid #c62828;
  background: #fff;
  color: #c62828;
  cursor: pointer;
  font: inherit;
}
.card-admin-unpublish:hover {
  background: #ffebee;
  border-color: #c62828;
  color: #c62828;
}
.project-grid {
  --card-w: 9.25rem;
  display: grid;
  /* 列宽等于卡片宽，不再用 1fr 把列拉满；整网用 justify-content 在中间聚拢 */
  grid-template-columns: repeat(5, var(--card-w));
  grid-template-rows: repeat(3, auto);
  gap: 0.85rem 1.25rem;
  width: 100%;
  justify-content: center;
  justify-items: stretch;
  align-items: start;
  align-content: start;
}
.project-card {
  box-sizing: border-box;
  width: 100%;
  min-width: 0;
  max-width: 100%;
  aspect-ratio: 1 / 1;
  height: auto;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  padding: 0.25rem 0.2rem 0.22rem;
  border: 1px solid #9e9e9e;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(6px);
  cursor: pointer;
  font: inherit;
  overflow: hidden;
  transition: border-color 0.15s, box-shadow 0.15s;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
}
.project-card:hover {
  border-color: #1565c0;
  box-shadow: 0 6px 18px rgba(21, 101, 192, 0.22);
}
.card-main {
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 5px;
  min-height: 0;
  width: 100%;
  text-align: center;
}
.card-folder-glyphs {
  position: relative;
  width: 1.75rem;
  height: 1.75rem;
  flex-shrink: 0;
}
.folder-svg {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: contain;
  transition: opacity 0.15s ease;
}
.folder-svg--closed {
  opacity: 1;
}
.folder-svg--open {
  opacity: 0;
}
.project-card:hover .folder-svg--closed {
  opacity: 0;
}
.project-card:hover .folder-svg--open {
  opacity: 1;
}
.card-title {
  font-weight: 700;
  font-size: 0.82rem;
  color: #1a237e;
  line-height: 1.2;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
  line-clamp: 3;
  overflow: hidden;
  word-break: break-word;
  max-width: 100%;
}
.card-meta {
  font-size: 0.68rem;
  color: #555;
  line-height: 1.15;
  max-width: 100%;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  overflow: hidden;
}
.card-date {
  flex-shrink: 0;
  font-size: 0.58rem;
  color: #888;
  margin-top: auto;
  padding-top: 0.2rem;
  line-height: 1.1;
  text-align: center;
  width: 100%;
  border-top: 1px solid rgba(208, 215, 222, 0.6);
}
.empty-hint {
  width: 100%;
  text-align: center;
  margin: 0.5rem 0 0;
  box-sizing: border-box;
}
.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 0.75rem 1rem;
  padding-top: 0.75rem;
}
.pager-info {
  font-size: 0.88rem;
  color: #444;
}
.btn {
  padding: 0.35rem 0.6rem;
  border-radius: 6px;
  border: 1px solid #1565c0;
  background: rgba(255, 255, 255, 0.95);
  color: #1565c0;
  cursor: pointer;
  font: inherit;
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
.muted {
  color: #666;
  font-size: 0.9rem;
  text-align: center;
}
</style>
