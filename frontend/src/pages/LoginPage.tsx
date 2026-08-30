import { useState } from 'react'
import { Alert, Button, Card, Form, Input, Tabs, Typography, message, type FormInstance } from 'antd'
import { Navigate, useNavigate, useSearchParams } from 'react-router-dom'
import { login, register } from '../api/auth'
import type { LoginParams } from '../api/types'
import { ApiError } from '../api/errorCode'
import { useAuth } from '../auth/AuthContext'
import { sanitizeRedirect } from '../auth/redirect'

/**
 * 登录/注册同页切换：
 * - 前端预校验与后端规则一致（手机号 ^1[3-9]\d{9}$，密码 6-32 位）
 * - 登录失败（手机号不存在/密码错误）、注册失败（手机号已注册）内联到对应表单字段
 * - HTTP 400 参数校验失败等其他错误：表单内 Alert 内联提示（不用全局 toast）
 * - 成功后 token 经 AuthContext 持久化，回跳 ?redirect= 指定的来源页
 */

/** 业务错误码 → 表单字段映射；返回 null 表示已内联到字段，否则返回表单级错误文案 */
function applyAuthError(form: FormInstance<LoginParams>, error: ApiError): string | null {
  if (error.code === 500501 || error.code === 500503) {
    // 手机号不存在（登录）/ 手机号已注册（注册）
    form.setFields([
      { name: 'mobile', errors: [error.message] },
      { name: 'password', errors: [] },
    ])
    return null
  }
  if (error.code === 500502) {
    form.setFields([
      { name: 'mobile', errors: [] },
      { name: 'password', errors: [error.message] },
    ])
    return null
  }
  // HTTP 400 校验失败 / 网络异常等：表单级内联提示
  return error.message
}

export default function LoginPage() {
  const { isAuthenticated, signIn } = useAuth()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const redirectTo = sanitizeRedirect(searchParams.get('redirect'))

  // 已登录访问 /login：直接去目标页（或首页）
  if (isAuthenticated) {
    return <Navigate to={redirectTo} replace />
  }

  const handleAuthenticated = (token: string, mode: 'login' | 'register') => {
    signIn(token)
    message.success(mode === 'login' ? '登录成功' : '注册成功')
    navigate(redirectTo, { replace: true })
  }

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: '#f5f5f5',
      }}
    >
      <Card style={{ width: 380 }}>
        <Typography.Title level={3} style={{ textAlign: 'center' }}>
          秒杀商城
        </Typography.Title>
        <Tabs
          centered
          items={[
            {
              key: 'login',
              label: '登录',
              children: <AuthForm mode="login" submitText="登录" onAuthenticated={handleAuthenticated} />,
            },
            {
              key: 'register',
              label: '注册',
              children: <AuthForm mode="register" submitText="注册" onAuthenticated={handleAuthenticated} />,
            },
          ]}
        />
      </Card>
    </div>
  )
}

function AuthForm({
  mode,
  submitText,
  onAuthenticated,
}: {
  mode: 'login' | 'register'
  submitText: string
  onAuthenticated: (token: string, mode: 'login' | 'register') => void
}) {
  const [form] = Form.useForm<LoginParams>()
  const [loading, setLoading] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)

  const handleFinish = async (values: LoginParams) => {
    setLoading(true)
    setFormError(null)
    try {
      const token = mode === 'login' ? await login(values) : await register(values)
      onAuthenticated(token, mode)
    } catch (error) {
      if (error instanceof ApiError) {
        setFormError(applyAuthError(form, error))
      } else {
        setFormError('请求失败，请稍后重试')
      }
    } finally {
      setLoading(false)
    }
  }

  return (
    <Form<LoginParams> form={form} layout="vertical" onFinish={handleFinish} requiredMark={false}>
      <Form.Item
        name="mobile"
        label="手机号"
        rules={[
          { required: true, message: '请输入手机号' },
          { pattern: /^1[3-9]\d{9}$/, message: '手机号格式错误' },
        ]}
      >
        <Input placeholder="请输入手机号" maxLength={11} autoComplete="username" />
      </Form.Item>
      <Form.Item
        name="password"
        label="密码"
        rules={[
          { required: true, message: '请输入密码' },
          { min: 6, max: 32, message: '密码长度必须在6-32位之间' },
        ]}
      >
        <Input.Password placeholder="请输入密码" autoComplete={mode === 'login' ? 'current-password' : 'new-password'} />
      </Form.Item>
      {formError && <Alert type="error" message={formError} showIcon style={{ marginBottom: 16 }} />}
      <Button type="primary" htmlType="submit" block loading={loading}>
        {submitText}
      </Button>
    </Form>
  )
}
