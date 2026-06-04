import axios from 'axios'

const withCred = { withCredentials: true }

/**
 * Axios 实例（按后端模块拆分 baseURL，便于在页面中一眼区分调用的子系统）：
 * - authApi：/api/auth*
 * - fsApi：/api/fs*（登录态文件树 + 项目成员前缀 /api/fs/projects）
 * - galleryApi：/api/fs/gallery*（广场只读）
 * - shareCreateApi：/api/share（创建临时分享）
 * - adminApi：/api/admin*
 */
export const authApi = axios.create({ baseURL: '/api', ...withCred })

/** 需登录态的文件接口（Redis Session Cookie） */
export const fsApi = axios.create({ baseURL: '/api/fs', ...withCred })

/** 广场浏览（只读；可带 Cookie 无妨） */
export const galleryApi = axios.create({ baseURL: '/api/fs/gallery', ...withCred })

/** 创建临时分享（须登录） */
export const shareCreateApi = axios.create({ baseURL: '/api/share', ...withCred })

/** 管理员接口 */
export const adminApi = axios.create({ baseURL: '/api/admin', ...withCred })
