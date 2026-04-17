import { createContext, useContext, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import axios from 'axios'
import type { ApiResponse } from '../api/client'
import type { LoginResponse } from '../api/types'

export type AuthUser = {
  userId: number
  nickname: string
  role: string
}

type AuthState = {
  token: string | null
  user: AuthUser | null
  loading: boolean
  setAuth: (token: string, user: AuthUser) => void
  clearAuth: () => void
}

const USER_KEY = 'hnu_user'
const CLIENT_INSTANCE_KEY = 'hnu_client_instance_id'
const CLIENT_INSTANCE_HEADER = 'X-Client-Instance-Id'
const BASE_URL = 'http://localhost:8080'

let accessToken: string | null = null

const AuthContext = createContext<AuthState | undefined>(undefined)

function getSessionStorage() {
  if (typeof window === 'undefined') {
    return null
  }
  return window.sessionStorage
}

function notifyAuthUpdated() {
  window.dispatchEvent(new Event('auth:updated'))
}

function generateClientInstanceId() {
  if (typeof window !== 'undefined' && window.crypto?.randomUUID) {
    return window.crypto.randomUUID()
  }
  return `tab_${Date.now()}_${Math.random().toString(36).slice(2, 10)}`
}

export function getClientInstanceId() {
  const storage = getSessionStorage()
  if (!storage) {
    return 'server-render'
  }
  let clientInstanceId = storage.getItem(CLIENT_INSTANCE_KEY)
  if (!clientInstanceId) {
    clientInstanceId = generateClientInstanceId()
    storage.setItem(CLIENT_INSTANCE_KEY, clientInstanceId)
  }
  return clientInstanceId
}

export function getToken() {
  return accessToken
}

export function setToken(token: string) {
  accessToken = token
  notifyAuthUpdated()
}

export function clearToken() {
  accessToken = null
  notifyAuthUpdated()
}

export function getStoredUser(): AuthUser | null {
  const raw = getSessionStorage()?.getItem(USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as AuthUser
  } catch {
    return null
  }
}

export function setStoredUser(user: AuthUser) {
  getSessionStorage()?.setItem(USER_KEY, JSON.stringify(user))
  notifyAuthUpdated()
}

export function clearStoredUser() {
  getSessionStorage()?.removeItem(USER_KEY)
  notifyAuthUpdated()
}

function mapLoginUser(data: LoginResponse): AuthUser {
  return {
    userId: data.userId,
    nickname: data.nickname,
    role: data.role,
  }
}

async function restoreSession(): Promise<LoginResponse | null> {
  try {
    const res = await axios.post<ApiResponse<LoginResponse>>(
      `${BASE_URL}/api/v1/auth/refresh`,
      null,
      {
        withCredentials: true,
        headers: {
          [CLIENT_INSTANCE_HEADER]: getClientInstanceId(),
        },
      },
    )
    const payload = res.data as ApiResponse<LoginResponse> | LoginResponse
    const data = 'data' in payload ? payload.data : payload
    if (!data?.token) {
      return null
    }
    return data
  } catch {
    return null
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setTokenState] = useState<string | null>(getToken())
  const [user, setUserState] = useState<AuthUser | null>(getStoredUser())
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let canceled = false

    const bootstrap = async () => {
      const restored = await restoreSession()
      if (!restored) {
        clearToken()
        clearStoredUser()
        if (!canceled) {
          setTokenState(null)
          setUserState(null)
          setLoading(false)
        }
        return
      }

      const nextUser = mapLoginUser(restored)
      setToken(restored.token)
      setStoredUser(nextUser)

      if (!canceled) {
        setTokenState(restored.token)
        setUserState(nextUser)
        setLoading(false)
      }
    }

    bootstrap()
    return () => {
      canceled = true
    }
  }, [])

  useEffect(() => {
    const syncFromState = () => {
      setTokenState(getToken())
      setUserState(getStoredUser())
    }
    window.addEventListener('auth:updated', syncFromState)
    return () => {
      window.removeEventListener('auth:updated', syncFromState)
    }
  }, [])

  const value = useMemo<AuthState>(
    () => ({
      token,
      user,
      loading,
      setAuth: (newToken, newUser) => {
        setToken(newToken)
        setStoredUser(newUser)
        setTokenState(newToken)
        setUserState(newUser)
      },
      clearAuth: () => {
        clearToken()
        clearStoredUser()
        setTokenState(null)
        setUserState(null)
      },
    }),
    [token, user, loading],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) {
    throw new Error('useAuth must be used within AuthProvider')
  }
  return ctx
}
