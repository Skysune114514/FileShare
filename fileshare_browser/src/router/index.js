import { createRouter, createWebHistory } from 'vue-router'
import LoginView from '@/views/LoginView.vue'
import RegisterView from '@/views/RegisterView.vue'
import MyFilesView from '@/views/MyFilesView.vue'
import ExploreView from '@/views/ExploreView.vue'
import FilePreviewView from '@/views/FilePreviewView.vue'
import ShareView from '@/views/ShareView.vue'
import GalleryWorkspaceLayout from '@/layouts/GalleryWorkspaceLayout.vue'
import { getStoredUser } from '@/utils/auth'

/**
 * 前端路由表：认证区（login/register）、已登录工作台（my-files/explore/admin-logs）、预览多模式、分享短链。
 * 守卫：requiresAuth / adminOnly / public；与 App.vue 顶栏显示条件配合。
 */
const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/login', name: 'login', component: LoginView, meta: { guest: true } },
    { path: '/register', name: 'register', component: RegisterView, meta: { guest: true } },
    { path: '/s/:code', name: 'share-landing', component: ShareView, meta: { public: true } },
    {
      path: '/',
      component: GalleryWorkspaceLayout,
      meta: { requiresAuth: true },
      redirect: { name: 'my-files' },
      children: [
        {
          path: 'my-files',
          name: 'my-files',
          component: MyFilesView,
          meta: { requiresAuth: true },
        },
        {
          path: 'explore',
          name: 'explore',
          component: ExploreView,
          meta: { requiresAuth: true },
        },
        {
          path: 'admin/gallery',
          redirect: { name: 'explore' },
          meta: { requiresAuth: true },
        },
        {
          path: 'admin/logs',
          name: 'admin-logs',
          component: () => import('@/views/admin/AdminLogsView.vue'),
          meta: { requiresAuth: true, adminOnly: true },
        },
      ],
    },
    {
      path: '/preview/mine/:nodeId/revision/:revisionId',
      name: 'file-preview-revision',
      component: FilePreviewView,
      meta: { requiresAuth: true },
    },
    {
      path: '/preview/mine/:nodeId',
      name: 'file-preview-mine',
      component: FilePreviewView,
      meta: { requiresAuth: true },
    },
    {
      path: '/preview/public/:ownerId/:nodeId',
      name: 'file-preview-public',
      component: FilePreviewView,
      meta: { requiresAuth: true },
    },
    {
      path: '/preview/share/:code',
      name: 'file-preview-share',
      component: FilePreviewView,
      meta: { public: true },
    },
  ],
})

router.beforeEach((to) => {
  // 步骤1：public 路由（分享页、分享预览）无需登录
  if (to.meta.public) {
    return true
  }
  const u = getStoredUser()
  // 步骤2：须登录但未登录 → 跳转 login 并带 redirect
  if (to.meta.requiresAuth && !u?.id) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  // 步骤3：admin 专属页非 admin → 回我的文件
  if (to.meta.adminOnly && u?.role !== 'admin') {
    return { name: 'my-files' }
  }
  // 步骤4：已登录用户访问 login/register → 回我的文件
  if (to.meta.guest && u?.id) {
    return { name: 'my-files' }
  }
  return true
})

export default router
