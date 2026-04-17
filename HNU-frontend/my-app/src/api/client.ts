import axios from 'axios'
import type { AxiosRequestConfig, InternalAxiosRequestConfig } from 'axios'
import type { LoginResponse } from './types'
import {
  clearStoredUser,
  clearToken,
  getClientInstanceId,
  getToken,
  setStoredUser,
  setToken,
} from '../store/auth'

export type ApiResponse<T> = {
  code: number
  message: string
  data: T
}

const baseURL = 'http://localhost:8080'

export const api = axios.create({
  baseURL,
  withCredentials: true,
})

const rawApi = axios.create({
  baseURL,
  withCredentials: true,
})

let refreshPromise: Promise<LoginResponse | null> | null = null

function applyClientBinding(config: InternalAxiosRequestConfig) {
  config.headers['X-Client-Instance-Id'] = getClientInstanceId()
  return config
}

api.interceptors.request.use((config) => {
  applyClientBinding(config)
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

rawApi.interceptors.request.use((config) => {
  applyClientBinding(config)
  return config
})

function redirectToLogin() {
  clearToken()
  clearStoredUser()
  if (window.location.pathname !== '/login') {
    window.location.href = '/login'
  }
}

async function refreshAccessToken(): Promise<LoginResponse | null> {
  const res = await rawApi.post<ApiResponse<LoginResponse>>('/api/v1/auth/refresh')
  const payload = res.data as ApiResponse<LoginResponse> | LoginResponse
  const data = 'data' in payload ? payload.data : payload
  if (!data?.token) {
    return null
  }
  return data
}

api.interceptors.response.use(
  (response) => {
    const payload = response.data as ApiResponse<unknown> | undefined
    if (payload && typeof payload.code === 'number') {
      if (payload.code === 401) {
        redirectToLogin()
        return Promise.reject(new Error(payload.message || '未授权，请重新登录'))
      }
      if (payload.code !== 200) {
        return Promise.reject(new Error(payload.message || '请求失败，请稍后重试'))
      }
      return payload
    }
    return response.data
  },
  async (error) => {
    const originalRequest = error?.config as (AxiosRequestConfig & { _retry?: boolean }) | undefined
    if (error?.response?.status === 401) {
      if (!getToken()) {
        redirectToLogin()
        return Promise.reject(error)
      }
      if (!originalRequest || originalRequest._retry) {
        redirectToLogin()
        return Promise.reject(error)
      }
      if (originalRequest.url?.includes('/api/v1/auth/refresh')) {
        redirectToLogin()
        return Promise.reject(error)
      }

      originalRequest._retry = true
      try {
        if (!refreshPromise) {
          refreshPromise = refreshAccessToken().finally(() => {
            refreshPromise = null
          })
        }
        const data = await refreshPromise
        if (!data) {
          redirectToLogin()
          return Promise.reject(error)
        }
        setToken(data.token)
        if (data.userId && data.nickname && data.role) {
          setStoredUser({ userId: data.userId, nickname: data.nickname, role: data.role })
        }
        originalRequest.headers = originalRequest.headers || {}
        originalRequest.headers.Authorization = `Bearer ${data.token}`
        return api(originalRequest)
      } catch (refreshError) {
        redirectToLogin()
        return Promise.reject(refreshError)
      }
    }

    return Promise.reject(error)
  },
)
