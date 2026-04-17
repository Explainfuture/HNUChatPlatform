import {
  Button,
  Card,
  Divider,
  Form,
  Input,
  List,
  Space,
  Tag,
  Typography,
  message,
  App as AntApp,
} from 'antd'
import { useEffect, useRef, useState } from 'react'
import { useParams } from 'react-router-dom'
import { api } from '../api/client'
import type { ApiResponse } from '../api/client'
import type { CommentItem, PostDetail } from '../api/types'
import { useAuth } from '../store/auth'

export default function PostDetailPage() {
  const { notification } = AntApp.useApp()
  const { id } = useParams()
  const { token, user, loading: authLoading } = useAuth()
  const [detail, setDetail] = useState<PostDetail | null>(null)
  const isAdmin = !authLoading && user?.role === 'ADMIN'
  const [loading, setLoading] = useState(false)
  const [replyTo, setReplyTo] = useState<CommentItem | null>(null)
  const [form] = Form.useForm()
  const [useMock, setUseMock] = useState(false)
  const fetchedIdRef = useRef<string | undefined>(undefined)

  /*const buildMockDetail = (postId: number): PostDetail | null => {
    const post = (mockPosts as PostDetail[]).find((item) => item.id === postId)
    if (!post) {
      return null
    }
    const comments =
      (mockComments as Record<string, CommentItem[]>)[String(postId)] || []
    return {
      ...post,
      content:
        (post as unknown as { content?: string }).content ||
        post.contentSummary ||
        '暂无内容',
      isLiked: false,
      comments,
    }
  }*/

  const fetchDetail = async () => {
    if (!id) return
    setLoading(true)
    try {
      const res = await api.get<ApiResponse<PostDetail>>(`/api/v1/posts/${id}`)
      const payload = res as unknown as ApiResponse<PostDetail> | PostDetail
      const data = 'data' in payload ? payload.data : payload
      if (data) {
        setUseMock(false)
        setDetail({ ...data, comments: data.comments || [] })
        return
      }
      /*const mockDetail = buildMockDetail(Number(id))
      if (mockDetail) {
        setUseMock(true)
        setDetail(mockDetail)
      }*/
    } catch (error) {
      /*const mockDetail = buildMockDetail(Number(id))
      if (mockDetail) {
        setUseMock(true)
        setDetail(mockDetail)
      } else {
        const msg = error instanceof Error ? error.message : '加载失败'
        message.error(msg)
      }*/
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    if (!id) return
    if (fetchedIdRef.current === id) {
      return
    }
    fetchedIdRef.current = id
    fetchDetail()
  }, [id])

  const handleToggleLike = async () => {
    if (!id) return
    if (!token) {
      notification.warning({
        message: '请先登录',
        placement: 'topRight'
      })
      message.warning('请先登录')
      return
    }
    try {
      if (useMock && detail) {
        const nextLiked = !detail.isLiked
        setDetail({
          ...detail,
          isLiked: nextLiked,
          likeCount: nextLiked
            ? detail.likeCount + 1
            : Math.max(0, detail.likeCount - 1),
        })
      } else {
        await api.post(`/api/v1/posts/${id}/like`)
        await fetchDetail()
      }
    } catch (error) {
      const msg = error instanceof Error ? error.message : '操作失败'
      message.error(msg)
    }
  }

  const handleDeletePost = async () => {
    if (!id) return
    if (!token) {
      notification.warning({
        message: '请先登录',
        placement: 'topRight'
      })
      message.warning('请先登录')
      return
    }
    try {
      if (useMock) {
        message.success('删除成功（模拟数据）')
        window.location.href = '/'
        return
      }
      if (isAdmin) {
        await api.delete(`/api/v1/admin/posts/${id}`)
      } else {
        await api.delete(`/api/v1/posts/${id}`)
      }
      notification.success({
        message: '删除成功',
        placement: 'topRight'
      })
      message.success('删除成功')
      window.location.href = '/'
    } catch (error) {
      const msg = error instanceof Error ? error.message : '删除失败'
      message.error(msg)
    }
  }

  const handleCommentLike = async (commentId: number) => {
    if (!token) {
      notification.warning({
        message: '请先登录',
        placement: 'topRight'
      })
      message.warning('请先登录')
      return
    }
    try {
      if (useMock && detail) {
        const updateComments = (items: CommentItem[]): CommentItem[] =>
          items.map((item) => {
            if (item.id === commentId) {
              const nextLiked = !item.isLiked
              return {
                ...item,
                isLiked: nextLiked,
                likeCount: nextLiked
                  ? item.likeCount + 1
                  : Math.max(0, item.likeCount - 1),
              }
            }
            if (item.replies && item.replies.length > 0) {
              return { ...item, replies: updateComments(item.replies) }
            }
            return item
          })
        setDetail({ ...detail, comments: updateComments(detail.comments) })
      } else {
        await api.post(`/api/v1/comments/${commentId}/like`)
        await fetchDetail()
      }
    } catch (error) {
      const msg = error instanceof Error ? error.message : '操作失败'
      message.error(msg)
    }
  }

  const handleDeleteComment = async (commentId: number, commentUserId: number) => {
    if (!token) {
      notification.warning({
        message: '请先登录',
        placement: 'topRight'
      })
      message.warning('请先登录')
      return
    }
    try {
      if (useMock && detail) {
        const markDeleted = (items: CommentItem[]): CommentItem[] =>
          items.map((item) => {
            if (item.id === commentId) {
              return {
                ...item,
                content: '该评论用户已自行删除',
                isLiked: false,
              }
            }
            if (item.replies && item.replies.length > 0) {
              return { ...item, replies: markDeleted(item.replies) }
            }
            return item
          })
        setDetail({ ...detail, comments: markDeleted(detail.comments) })
        message.success('删除成功（模拟数据）')
        return
      }
      if (isAdmin && user.userId !== commentUserId) {
        await api.delete(`/api/v1/admin/comments/${commentId}`)
      } else {
        await api.delete(`/api/v1/comments/${commentId}`)
      }
      message.success('删除成功')
      await fetchDetail()
    } catch (error) {
      const msg = error instanceof Error ? error.message : '删除失败'
      message.error(msg)
    }
  }

  const handleSubmitComment = async (values: { content: string }) => {
    if (!id) return
    if (!token) {
      notification.warning({
        message: '请先登录',
        placement: 'topRight'
      })
      message.warning('请先登录')
      return
    }
    try {
      if (useMock && detail) {
        const now = new Date().toISOString()
        const newComment: CommentItem = {
          id: Date.now(),
          userId: 999,
          userNickname: '测试用户',
          content: values.content,
          parentId: replyTo?.id,
          parentUserNickname: replyTo?.userNickname,
          likeCount: 0,
          isLiked: false,
          createTime: now,
          replies: [],
        }
        if (replyTo) {
          const addReply = (items: CommentItem[]): CommentItem[] =>
            items.map((item) => {
              if (item.id === replyTo.id) {
                return {
                  ...item,
                  replies: [...(item.replies || []), newComment],
                }
              }
              if (item.replies && item.replies.length > 0) {
                return { ...item, replies: addReply(item.replies) }
              }
              return item
            })
          setDetail({ ...detail, comments: addReply(detail.comments) })
        } else {
          setDetail({ ...detail, comments: [newComment, ...detail.comments] })
        }
        message.success('评论成功（模拟数据）')
        setReplyTo(null)
        form.resetFields()
      } else {
        await api.post('/api/v1/comments', {
          postId: Number(id),
          content: values.content,
          parentId: replyTo?.id,
        })
        message.success('评论成功')
        setReplyTo(null)
        form.resetFields()
        await fetchDetail()
      }
    } catch (error) {
      const msg = error instanceof Error ? error.message : '评论失败'
      if (msg.includes('禁言')) {
        notification.error({
          message: '无法评论',
          description: '你已被禁言，请联系管理员处理',
          placement: 'topRight',
        })
      } else {
        message.error(msg)
      }
    }
  }

  const flattenReplies = (items: CommentItem[]): CommentItem[] => {
    const result: CommentItem[] = []
    const stack: CommentItem[] = [...items]
    while (stack.length > 0) {
      const current = stack.shift()
      if (!current) continue
      result.push(current)
      if (current.replies && current.replies.length > 0) {
        stack.unshift(...current.replies)
      }
    }
    return result
  }

  const renderCommentActions = (comment: CommentItem, isDeleted: boolean) => {
    const canDelete =
      user && (isAdmin || user.userId === comment.userId)
    if (isDeleted) return null
    return (
      <Space size={12}>
        <Button size="small" type="link" onClick={() => handleCommentLike(comment.id)}>
          {comment.isLiked ? '已赞' : '点赞'} {comment.likeCount}
        </Button>
        <Button size="small" type="link" onClick={() => setReplyTo(comment)}>
          回复
        </Button>
        {canDelete ? (
          <Button
            size="small"
            danger
            type="link"
            onClick={() => handleDeleteComment(comment.id, comment.userId)}
          >
            删除
          </Button>
        ) : null}
      </Space>
    )
  }

  const renderRootComment = (comment: CommentItem) => {
    const isDeleted = comment.content === '该评论用户已自行删除'
    const replies = comment.replies ? flattenReplies(comment.replies) : []
    return (
      <div style={{ width: '100%' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12 }}>
          <Space size={8} wrap>
            <Typography.Text strong>{comment.userNickname || '匿名'}</Typography.Text>
            {comment.parentUserNickname ? (
              <Typography.Text type="secondary">
                回复 @{comment.parentUserNickname}
              </Typography.Text>
            ) : null}
            {isDeleted ? <Tag color="default">已删除</Tag> : null}
          </Space>
          <Typography.Text type="secondary">
            {new Date(comment.createTime).toLocaleString()}
          </Typography.Text>
        </div>
        <Typography.Paragraph style={{ margin: '8px 0' }} type={isDeleted ? 'secondary' : undefined}>
          {comment.content}
        </Typography.Paragraph>
        {renderCommentActions(comment, isDeleted)}

        {replies.length > 0 ? (
          <div
            style={{
              marginTop: 12,
              paddingLeft: 16,
              borderLeft: '2px solid #f0f0f0',
            }}
          >
            <Space direction="vertical" size={12} style={{ width: '100%' }}>
              {replies.map((reply) => {
                const replyDeleted = reply.content === '该评论用户已自行删除'
                return (
                  <div key={reply.id} style={{ width: '100%' }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12 }}>
                      <Space size={8} wrap>
                        <Typography.Text strong>
                          {reply.userNickname || '匿名'}
                        </Typography.Text>
                        {reply.parentUserNickname ? (
                          <Typography.Text type="secondary">
                            回复 @{reply.parentUserNickname}
                          </Typography.Text>
                        ) : null}
                        {replyDeleted ? <Tag color="default">已删除</Tag> : null}
                      </Space>
                      <Typography.Text type="secondary">
                        {new Date(reply.createTime).toLocaleString()}
                      </Typography.Text>
                    </div>
                    <Typography.Paragraph
                      style={{ margin: '8px 0' }}
                      type={replyDeleted ? 'secondary' : undefined}
                    >
                      {reply.content}
                    </Typography.Paragraph>
                    {renderCommentActions(reply, replyDeleted)}
                  </div>
                )
              })}
            </Space>
          </div>
        ) : null}
      </div>
    )
  }

  if (!detail) {
    return (
      <div style={{ maxWidth: 960, margin: '24px auto', padding: '0 16px' }}>
        <Card loading={loading}>加载中...</Card>
      </div>
    )
  }

  return (
    <div style={{ maxWidth: 960, margin: '24px auto', padding: '0 16px' }}>
      <Card loading={loading}>
        <Typography.Title level={3} style={{ marginBottom: 8 }}>
          {detail.title}
        </Typography.Title>
        <Space wrap>
          <Typography.Text type="secondary">
            作者：{detail.authorNickname || '匿名'}
          </Typography.Text>
          <Typography.Text type="secondary">
            分类：{detail.categoryName || '未分类'}
          </Typography.Text>
          <Typography.Text type="secondary">
            发布时间：{new Date(detail.createTime).toLocaleString()}
          </Typography.Text>
          {detail.contactInfo ? (
            <Typography.Text type="secondary">联系方式：{detail.contactInfo}</Typography.Text>
          ) : null}
          {useMock ? <Tag color="orange">模拟数据</Tag> : null}
        </Space>
        <Divider style={{ margin: '12px 0' }} />
        <Typography.Paragraph style={{ fontSize: 15, lineHeight: 1.8 }}>
          {detail.content}
        </Typography.Paragraph>
        <Divider style={{ margin: '12px 0' }} />
        <Space wrap>
          <Button type={detail.isLiked ? 'primary' : 'default'} onClick={handleToggleLike}>
            {detail.isLiked ? '已点赞' : '点赞'} {detail.likeCount}
          </Button>
          <Button type="default">评论 {detail.comments?.length ?? 0}</Button>
          <Typography.Text type="secondary">浏览 {detail.viewCount}</Typography.Text>
          {user && (isAdmin || user.userId === detail.authorId) ? (
            <Button danger onClick={handleDeletePost}>
              删除帖子
            </Button>
          ) : null}
        </Space>
      </Card>

      <Card title="发表评论" style={{ marginTop: 16 }}>
        {replyTo ? (
          <Space style={{ marginBottom: 12 }} wrap>
            <Typography.Text type="secondary">
              回复 @{replyTo.userNickname || '匿名'}
            </Typography.Text>
            <Button size="small" type="link" onClick={() => setReplyTo(null)}>
              取消回复
            </Button>
          </Space>
        ) : null}
        <Form form={form} layout="vertical" onFinish={handleSubmitComment}>
          <Form.Item
            name="content"
            rules={[{ required: true, message: '请输入评论内容' }]}
          >
            <Input.TextArea autoSize={{ minRows: 3, maxRows: 6 }} placeholder="写下你的评论..." />
          </Form.Item>
          <Form.Item style={{ marginBottom: 0 }}>
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 12 }}>
              <Button onClick={() => form.resetFields()}>清空</Button>
              <Button type="primary" htmlType="submit">
                提交评论
              </Button>
            </div>
          </Form.Item>
        </Form>
      </Card>

      <Card title="评论列表" style={{ marginTop: 16 }}>
        <List
          dataSource={detail.comments || []}
          renderItem={(item) => (
            <List.Item key={item.id} style={{ width: '100%' }}>
              {renderRootComment(item)}
            </List.Item>
          )}
          locale={{ emptyText: '暂无评论' }}
        />
      </Card>
    </div>
  )
}
