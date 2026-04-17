# HNU 校园交流平台 - 前端执行全流程详解

## 项目结构概述

```
HNU-frontend/my-app/
├── src/
│   ├── api/
│   │   ├── client.ts        # Axios 客户端配置，请求/响应拦截器
│   │   └── types.ts         # TypeScript 类型定义
│   ├── components/
│   │   └── AppLayout.tsx    # 应用布局组件（头部导航、退出登录）
│   ├── pages/
│   │   ├── Login.tsx        # 登录页面
│   │   └── ...
│   ├── store/
│   │   └── auth.tsx         # 认证状态管理（Token 存储、用户信息）
│   ├── App.tsx              # 路由配置
│   └── main.tsx             # 应用入口
```

---

## 1. 应用入口 (`main.tsx`)

```typescript
// HNU-frontend/my-app/src/main.tsx
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { ConfigProvider, App as AntApp } from 'antd'
import App from './App.tsx'
import { AuthProvider } from './store/auth'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ConfigProvider theme={{ token: { colorPrimary: '#1677ff' } }}>
      <AntApp>
        <AuthProvider>          // ← 提供认证状态
          <BrowserRouter>        // ← 路由管理
            <App />              // ← 主应用组件
          </BrowserRouter>
        </AuthProvider>
      </AntApp>
    </ConfigProvider>
  </StrictMode>,
)
```

---

## 2. 认证状态管理 (`store/auth.tsx`)

### Token 存储方式

| 存储类型 | Key | 值格式 |
|----------|-----|--------|
| Token | `'hnu_token'` | string (JWT) |
| 用户信息 | `'hnu_user'` | JSON.stringify({ userId, nickname, role }) |

### 核心函数

```typescript
// Token 相关函数
const TOKEN_KEY = 'hnu_token'
const USER_KEY = 'hnu_user'
const BASE_URL = 'http://localhost:8080'

// 获取 token
export function getToken() {
  return localStorage.getItem(TOKEN_KEY)  // ← 从 localStorage 读取 token
}

// 设置 token
export function setToken(token: string) {
  localStorage.setItem(TOKEN_KEY, token)  // ← 存入 localStorage
  notifyAuthUpdated()  // ← 触发自定义事件通知其他组件
}

// 清除 token
export function clearToken() {
  localStorage.removeItem(TOKEN_KEY)
  notifyAuthUpdated()
}

// 获取用户信息
export function getStoredUser(): AuthUser | null {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as AuthUser  // ← JSON 解析用户数据
  } catch {
    return null
  }
}

// 设置用户信息
export function setStoredUser(user: AuthUser) {
  localStorage.setItem(USER_KEY, JSON.stringify(user))
  notifyAuthUpdated()
}
```

### 自定义事件通知机制

```typescript
function notifyAuthUpdated() {
  window.dispatchEvent(new Event('auth:updated'))
}

// 在 AuthProvider 中监听这个事件来同步状态
useEffect(() => {
  const syncFromStorage = () => {
    setTokenState(getToken())
    setUserState(getStoredUser())
  }
  window.addEventListener('auth:updated', syncFromStorage)
  return () => {
    window.removeEventListener('auth:updated', syncFromStorage)
  }
}, [])
```

### 启动验证流程 (bootstrap)

```typescript
// store/auth.tsx:76-125
export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setTokenState] = useState<string | null>(getToken())
  const [user, setUserState] = useState<AuthUser | null>(getStoredUser())
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let canceled = false

    const bootstrap = async () => {
      // 1. 从 localStorage 获取 token
      const storedToken = getToken()

      if (!storedToken) {
        if (!canceled) {
          setUserState(null)
          setLoading(false)
        }
        return
      }

      // 2. 发送请求验证 token 有效性
      try {
        const res = await axios.get<ApiResponse<UserInfo>>(
          `${BASE_URL}/api/v1/users/me`,
          {
            headers: { Authorization: `Bearer ${storedToken}` },
            withCredentials: true,  // ← 发送 cookie (用于刷新 token)
          }
        )
        const payload = res.data as ApiResponse<UserInfo> | UserInfo
        const data = 'data' in payload ? payload.data : payload

        // 3. 如果 token 有效，更新用户信息
        if (data && !canceled) {
          const nextUser = mapUserInfo(data)
          setStoredUser(nextUser)   // ← 更新 localStorage
          setUserState(nextUser)    // ← 更新 state
        }
      } catch {
        // token 无效，清除存储
        clearToken()
        clearStoredUser()
        if (!canceled) {
          setTokenState(null)
          setUserState(null)
        }
      } finally {
        if (!canceled) {
          setLoading(false)
        }
      }
    }

    bootstrap()
    return () => {
      canceled = true
    }
  }, [])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
```

---

## 3. API 客户端配置 (`api/client.ts`)

### Axios 实例创建

```typescript
const baseURL = 'http://localhost:8080'

// 带拦截器的 API 实例
export const api = axios.create({
  baseURL,
  withCredentials: true,  // ← 允许发送/接收 cookie
})

// 原始 API 实例（不带拦截器，用于刷新 token）
const rawApi = axios.create({
  baseURL,
  withCredentials: true,
})
```

### 请求拦截器：自动添加 Token

```typescript
// api/client.ts:26-32
api.interceptors.request.use((config) => {
  const token = getToken()  // ← 从 localStorage 获取 token
  if (token) {
    config.headers.Authorization = `Bearer ${token}`  // ← 添加到请求头
  }
  return config
})
```

### 响应拦截器：自动处理 Token 刷新

```typescript
// api/client.ts:52-109
let refreshPromise: Promise<LoginResponse | null> | null = null

function redirectToLogin() {
  clearToken()
  clearStoredUser()
  if (window.location.pathname !== '/login') {
    window.location.href = '/login'
  }
}

// 刷新 token 的 API
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
  // 成功响应处理
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
  // 错误响应处理
  async (error) => {
    const originalRequest = error?.config as (AxiosRequestConfig & { _retry?: boolean }) | undefined

    // 当收到 401 错误时，尝试刷新 token
    if (error?.response?.status === 401) {
      // 没有 token，直接跳转登录
      if (!getToken()) {
        redirectToLogin()
        return Promise.reject(error)
      }

      // 已经重试过或原始请求为空，跳转登录
      if (!originalRequest || originalRequest._retry) {
        redirectToLogin()
        return Promise.reject(error)
      }

      // 刷新请求本身返回 401，跳转登录
      if (originalRequest.url?.includes('/api/v1/auth/refresh')) {
        redirectToLogin()
        return Promise.reject(error)
      }

      // 标记为已重试
      originalRequest._retry = true

      try {
        // 防止并发刷新（使用 Promise 缓存）
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

        // 刷新成功：更新 token 和用户信息
        setToken(data.token)  // ← 更新 localStorage
        if (data.userId && data.nickname && data.role) {
          setStoredUser({ userId: data.userId, nickname: data.nickname, role: data.role })
        }

        // 重试原始请求
        originalRequest.headers = originalRequest.headers || {}
        originalRequest.headers.Authorization = `Bearer ${data.token}`
        return api(originalRequest)
      } catch (refreshError) {
        redirectToLogin()
        return Promise.reject(refreshError)
      }
    }

    return Promise.reject(error)
  }
)
```

---

## 4. 路由配置 (`App.tsx`)

```typescript
// HNU-frontend/my-app/src/App.tsx
import { Navigate, Route, Routes } from 'react-router-dom'

// 登录验证组件
function RequireAuth({ children }: { children: ReactNode }) {
  const { token, loading } = useAuth()
  if (loading) return null
  if (!token) return <Navigate to="/login" replace />
  return <>{children}</>
}

// 管理员验证组件
function RequireAdmin({ children }: { children: ReactNode }) {
  const { token, user, loading } = useAuth()
  if (loading) return null
  if (!token) return <Navigate to="/login" replace />
  if (user?.role !== 'ADMIN') return <Navigate to="/" replace />
  return <>{children}</>
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />

      {/* 首页 - 无需登录 */}
      <Route path="/" element={<AppLayout><PostsPage /></AppLayout>} />

      {/* 帖子详情 - 无需登录 */}
      <Route path="/posts/:id" element={<AppLayout><PostDetailPage /></AppLayout>} />

      {/* 需要登录的页面 */}
      <Route path="/posts/create" element={
        <RequireAuth><AppLayout><CreatePostPage /></AppLayout></RequireAuth>
      } />
      <Route path="/users/me/posts" element={
        <RequireAuth><AppLayout><MyPostsPage /></AppLayout></RequireAuth>
      } />
      <Route path="/users/me" element={
        <RequireAuth><AppLayout><ProfilePage /></AppLayout></RequireAuth>
      } />

      {/* 需要管理员权限的页面 */}
      <Route path="/admin/pending" element={
        <RequireAdmin><AppLayout><AdminPendingPage /></AppLayout></RequireAdmin>
      } />

      {/* 默认跳转首页 */}
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
```

---

## 5. 登录流程 (`pages/Login.tsx`)

```typescript
// HNU-frontend/my-app/src/pages/Login.tsx
export default function LoginPage() {
  const navigate = useNavigate()
  const { setAuth } = useAuth()  // ← 获取认证上下文

  // 发送验证码
  const handleSendCode = async () => {
    const phone = form.getFieldValue('phone')
    await api.post('/api/v1/auth/send-verify-code', null, { params: { phone } })
    setCountdown(60)  // 60秒倒计时
  }

  // 登录提交
  const onFinish = async (values: { phone: string; password: string; verifyCode: string }) => {
    try {
      // 1. 发送登录请求
      const res = await api.post<ApiResponse<LoginResponse>>('/api/v1/auth/login', values)

      const payload = res as unknown as ApiResponse<LoginResponse>

      // 2. 保存 token 和用户信息
      setAuth(payload.data.token, {          // ← 调用 auth.tsx 的 setAuth
        userId: payload.data.userId,
        nickname: payload.data.nickname,
        role: payload.data.role,
      })

      // 内部会执行：
      // - localStorage.setItem('hnu_token', token)
      // - localStorage.setItem('hnu_user', JSON.stringify(user))
      // - 更新 React state

      // 3. 跳转到首页
      message.success('登录成功')
      navigate('/')
    } catch (error) {
      // 错误处理...
    }
  }

  return (
    <div className="auth-page">
      <Card className="auth-card">
        <Form form={form} layout="vertical" onFinish={onFinish}>
          <Form.Item name="phone" label="手机号" rules={[{ required: true }]}>
            <Input placeholder="请输入手机号" />
          </Form.Item>
          <Form.Item name="verifyCode" label="验证码" required>
            <Space.Compact>
              <Input placeholder="请输入验证码" />
              <Button onClick={handleSendCode}>获取验证码</Button>
            </Space.Compact>
          </Form.Item>
          <Form.Item name="password" label="密码" rules={[{ required: true }]}>
            <Input.Password placeholder="请输入密码" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block>登录</Button>
        </Form>
      </Card>
    </div>
  )
}
```

---

## 6. 退出登录流程 (`components/AppLayout.tsx`)

```typescript
// HNU-frontend/my-app/src/components/AppLayout.tsx
export default function AppLayout({ children }: { children: ReactNode }) {
  const { user, clearAuth, loading } = useAuth()
  const navigate = useNavigate()

  return (
    <Layout>
      <Header>
        <Space>
          <Typography.Title level={4}>HNU 校园服务平台</Typography.Title>
          <Menu items={items} />
        </Space>
        <Space>
          {user ? (
            <>
              <span>你好,{user.nickname}</span>
              <Button
                onClick={async () => {
                  try {
                    await api.post('/api/v1/auth/logout')  // ← 通知后端退出
                  } catch {
                    // best-effort logout
                  } finally {
                    clearAuth()      // ← 清除 localStorage 中的 token 和用户信息
                    navigate('/login')
                  }
                }}
              >
                退出登录
              </Button>
            </>
          ) : (
            <>
              <Button onClick={() => navigate('/login')}>登录</Button>
              <Button onClick={() => navigate('/register')}>注册</Button>
            </>
          )}
        </Space>
      </Header>
      <Content>{children}</Content>
    </Layout>
  )
}
```

---

## 完整流程图

```
┌─────────────────────────────────────────────────────────────────────┐
│                         用户访问网站                                 │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│  main.tsx - 应用启动                                                │
│  ├─ ConfigProvider (Ant Design 主题)                               │
│  ├─ BrowserRouter (路由)                                            │
│  └─ AuthProvider (认证状态管理) ← 启动时执行 bootstrap              │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│  AuthProvider 启动流程 (bootstrap)                                  │
│  │                                                                   │
│  1. getToken() → localStorage.getItem('hnu_token')                 │
│     │                                                                │
│     ├─ 没有 token → loading=false (未登录状态)                      │
│     │                                                                │
│     └─ 有 token → 继续验证                                          │
│             │                                                        │
│             ▼                                                        │
│  2. GET /api/v1/users/me                                            │
│     + Authorization: Bearer {token}                                │
│     + withCredentials: true (发送 cookie)                           │
│     │                                                                │
│     ├─ 成功 (200) → 更新用户信息到 localStorage                      │
│     │   └─ setStoredUser() → localStorage.setItem('hnu_user', ...)  │
│     │                                                                │
│     └─ 失败 (401) → 清除 token → loading=false                     │
│        └─ clearToken() → localStorage.removeItem('hnu_token')      │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│  App.tsx - 路由匹配                                                 │
│  └─ RequireAuth / RequireAdmin 保护需要登录的页面                  │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
         ┌─────────────────────┼─────────────────────┐
         │                     │                     │
         ▼                     ▼                     ▼
   ┌──────────┐         ┌──────────┐          ┌──────────┐
   │ 登录页面  │         │首页/帖子 │          │ 个人中心 │
   │Login.tsx │         │Posts.tsx │          │Profile   │
   └────┬─────┘         └────┬─────┘          └────┬─────┘
        │                     │                     │
        ▼                     │                     │
   ┌──────────────────────────────────────────────────────┐
   │  用户操作：登录                                        │
   │                                                       │
   │  1. POST /api/v1/auth/login                           │
   │     { phone, password, verifyCode }                  │
   │     ↓                                                 │
   │  2. 后端返回: { token, userId, nickname, role }      │
   │     ↓                                                 │
   │  3. setAuth(token, user)                             │
   │     ├─ localStorage.setItem('hnu_token', token)      │
   │     └─ localStorage.setItem('hnu_user', JSON)        │
   │     ↓                                                 │
   │  4. navigate('/') - 跳转首页                         │
   └──────────────────────────────────────────────────────┘

   任何需要认证的 API 请求:

   ┌──────────────────────────────────────────────────────┐
   │  api.get/post/put/delete()                           │
   │       │                                               │
   │       ▼                                               │
   │  请求拦截器 (api/client.ts:26)                       │
   │  └─ getToken() → localStorage.getItem('hnu_token')  │
   │  └─ headers.Authorization = `Bearer ${token}`        │
   │       │                                               │
   │       ▼                                               │
   │  发送请求到后端 http://localhost:8080                 │
   │  + withCredentials: true (包含 cookie)               │
   │       │                                               │
   │       ▼                                               │
   │  响应拦截器 (api/client.ts:52)                       │
   │  │                                                   │
   │  ├─ 200 成功 → 返回 data                              │
   │  │                                                   │
   │  └─ 401 未授权 →                                      │
   │         ├─ 调用 /api/v1/auth/refresh                 │
   │         │   (使用 rawApi，不带拦截器)               │
   │         │   ↓                                         │
   │         │   成功: { token, userId, nickname, role }  │
   │         │   ↓                                         │
   │         │   setToken(data.token)                    │
   │         │   setStoredUser(data.user)                 │
   │         │   ↓                                         │
   │         │   重试原始请求                              │
   │         │                                             │
   │         └─ 刷新失败 → redirectToLogin()              │
   │            └─ window.location.href = '/login'        │
   └──────────────────────────────────────────────────────┘
```

---

## 关键技术点总结

| 功能 | 文件位置 | 行号 | 实现方式 |
|------|----------|------|----------|
| **Token 存储** | `store/auth.tsx` | 31-33 | `localStorage.getItem('hnu_token')` |
| **Token 设置** | `store/auth.tsx` | 35-38 | `localStorage.setItem('hnu_token', token)` |
| **Token 清除** | `store/auth.tsx` | 40-43 | `localStorage.removeItem('hnu_token')` |
| **用户信息存储** | `store/auth.tsx` | 55-58 | `localStorage.setItem('hnu_user', JSON.stringify(user))` |
| **自动添加 Token** | `api/client.ts` | 26-32 | 请求拦截器自动添加 `Authorization: Bearer ${token}` |
| **自动刷新 Token** | `api/client.ts` | 67-105 | 响应拦截器处理 401，调用 `/api/v1/auth/refresh` |
| **启动验证** | `store/auth.tsx` | 84-119 | 调用 `/api/v1/users/me` 验证 token |
| **退出登录** | `components/AppLayout.tsx` | 66-74 | 调用 `/api/v1/auth/logout` + `clearAuth()` |
| **Cookie 发送** | `api/client.ts` | 14-17 | `withCredentials: true` |
| **路由保护** | `App.tsx` | 14-37 | `RequireAuth` / `RequireAdmin` 组件 |

---

## API 端点汇总

| 端点 | 方法 | 用途 | 是否需要 Token |
|------|------|------|----------------|
| `/api/v1/auth/send-verify-code` | POST | 发送验证码 | 否 |
| `/api/v1/auth/login` | POST | 用户登录 | 否 |
| `/api/v1/auth/refresh` | POST | 刷新 Token | 否 (使用 Cookie) |
| `/api/v1/auth/logout` | POST | 退出登录 | 是 |
| `/api/v1/users/me` | GET | 获取当前用户信息 | 是 |

---

## 数据类型定义 (`api/types.ts`)

```typescript
// 登录响应
export type LoginResponse = {
  token: string
  expiresIn?: number
  userId: number
  nickname: string
  role: string
}

// 用户信息
export type UserInfo = {
  id: number
  phone: string
  nickname: string
  studentId?: string
  campusCardUrl?: string
  authStatus: string
  role: string
  isMuted: boolean
  createTime: string
}

// 帖子列表项
export type PostListItem = {
  id: number
  title: string
  contentSummary: string
  categoryId: number
  categoryName?: string
  authorId: number
  authorNickname?: string
  viewCount: number
  likeCount: number
  hotScore: number
  createTime: string
}

// 帖子详情
export type PostDetail = {
  id: number
  title: string
  content: string
  categoryId: number
  categoryName?: string
  authorId: number
  authorNickname?: string
  contactInfo?: string
  viewCount: number
  likeCount: number
  hotScore: number
  isLiked: boolean
  createTime: string
  comments: CommentItem[]
}

// 评论项
export type CommentItem = {
  id: number
  userId: number
  userNickname?: string
  content: string
  parentId?: number
  parentUserNickname?: string
  likeCount: number
  isLiked: boolean
  createTime: string
  replies?: CommentItem[]
}

// 待审核用户
export type PendingUser = {
  id: number
  phone: string
  nickname: string
  studentId?: string
  campusCardUrl?: string
  authStatus: string
  role: string
  isMuted: boolean
  createTime: string
}
```
