/**
 * 下载工具：同源接口（带 Cookie）返回附件流 → 转 Blob → 触发浏览器保存。
 * 注意：适合中小文件；超大文件应改用直接 <a href> 导航下载，避免整体进内存。
 */
export async function downloadBlob(url, fileName) {
  const res = await fetch(url, { credentials: 'include' })
  if (!res.ok) {
    const j = await res.json().catch(() => ({}))
    throw new Error(j.error || res.statusText)
  }
  const blob = await res.blob()
  const objectUrl = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = objectUrl
  a.download = fileName || 'download'
  a.click()
  URL.revokeObjectURL(objectUrl)
}
