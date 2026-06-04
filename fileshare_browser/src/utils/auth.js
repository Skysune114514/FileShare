const KEY = 'fileshare_auth_user'

export function getStoredUser() {
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return null
    return JSON.parse(raw)
  } catch {
    return null
  }
}

export function setStoredUser(user) {
  localStorage.setItem(KEY, JSON.stringify(user))
}

export function clearStoredUser() {
  localStorage.removeItem(KEY)
}

export function isAdminUser() {
  return getStoredUser()?.role === 'admin'
}
