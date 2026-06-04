/**
 * 在线预览类型：图片、视频、原生 PDF；Office 走服务端 LibreOffice 转 PDF。
 * @param {string} fileName
 * @param {string} [mime]
 * @returns {'image' | 'video' | 'pdf' | 'office' | null}
 */
export function getFilePreviewKind(fileName, mime = '') {
  const m = String(mime || '')
    .split(';')[0]
    .trim()
    .toLowerCase()
  if (m.startsWith('image/')) return 'image'
  if (m.startsWith('video/')) return 'video'
  if (m === 'application/pdf') return 'pdf'
  if (isOfficeMime(m)) return 'office'

  const dot = String(fileName || '').lastIndexOf('.')
  const ext = dot >= 0 ? fileName.slice(dot).toLowerCase() : ''
  if (['.jpg', '.jpeg', '.png', '.gif', '.webp', '.svg', '.bmp', '.ico', '.avif'].includes(ext)) {
    return 'image'
  }
  if (['.mp4', '.webm', '.ogg', '.ogv', '.mov', '.m4v'].includes(ext)) {
    return 'video'
  }
  if (ext === '.pdf') return 'pdf'
  if (OFFICE_EXTENSIONS.has(ext)) return 'office'
  return null
}

const OFFICE_EXTENSIONS = new Set([
  '.doc',
  '.docx',
  '.docm',
  '.dot',
  '.dotx',
  '.xls',
  '.xlsx',
  '.xlsm',
  '.xlsb',
  '.ppt',
  '.pptx',
  '.pptm',
  '.pps',
  '.ppsx',
  '.odt',
  '.ods',
  '.odp',
  '.odg',
  '.odf',
  '.rtf',
  '.csv',
])

function isOfficeMime(m) {
  if (!m) return false
  return (
    m.includes('msword') ||
    m.includes('wordprocessingml') ||
    m.includes('spreadsheetml') ||
    m.includes('excel') ||
    m.includes('presentationml') ||
    m.includes('powerpoint') ||
    m.includes('opendocument') ||
    m === 'text/csv' ||
    m.includes('rtf')
  )
}

/**
 * 在 MIME 缺失或为 octet-stream 时，为 Blob 补全 type，便于 iframe / video 识别。
 */
export function mimeForPreviewKind(kind, fileName) {
  if (kind === 'pdf') return 'application/pdf'
  const dot = String(fileName || '').lastIndexOf('.')
  const ext = dot >= 0 ? fileName.slice(dot).toLowerCase() : ''
  const map = {
    '.mp4': 'video/mp4',
    '.webm': 'video/webm',
    '.ogg': 'video/ogg',
    '.ogv': 'video/ogg',
    '.mov': 'video/quicktime',
    '.m4v': 'video/x-m4v',
    '.jpg': 'image/jpeg',
    '.jpeg': 'image/jpeg',
    '.png': 'image/png',
    '.gif': 'image/gif',
    '.webp': 'image/webp',
    '.svg': 'image/svg+xml',
    '.bmp': 'image/bmp',
    '.ico': 'image/x-icon',
    '.avif': 'image/avif',
  }
  return map[ext] || ''
}
