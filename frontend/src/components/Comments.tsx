import { useEffect, useState, type FormEvent } from "react"
import { Button } from "@/components/ui/button"
import { Textarea } from "@/components/ui/textarea"
import { Label } from "@/components/ui/label"
import { api, ApiError, type Comment, type Page, type Session } from "@/lib/api"

type Props = { postId: string; status: string; session: Session | null }
type ReplyTarget = { id: string; rootId: string; nickname: string }
const pageSize = 10

function message(error: unknown) {
  if (error instanceof ApiError) {
    if (error.code === "INVALID_COMMENT")
      return "评论需为 1–1000 个字符，且不能全为空白。"
    if (error.code === "NOT_FOUND") return "内容已删除或不可见，请刷新后重试。"
    return `操作未完成（${error.code}）。`
  }
  return "无法连接服务，请稍后重试。"
}

function submitError(error: unknown, kind: "评论" | "回复") {
  if (
    error instanceof ApiError &&
    [
      "INVALID_COMMENT",
      "NOT_FOUND",
      "AUTH_REQUIRED",
      "INVALID_TOKEN",
      "TOKEN_EXPIRED",
      "ROLE_FORBIDDEN",
    ].includes(error.code)
  )
    return message(error)
  return `${kind}提交结果未确认，请刷新后核对，避免重复发表。`
}

export function Comments({ postId, status, session }: Props) {
  const [roots, setRoots] = useState<Page<Comment> | null>(null)
  const [page, setPage] = useState(1)
  const [order, setOrder] = useState<"asc" | "desc">("asc")
  const [expanded, setExpanded] = useState<Record<string, boolean>>({})
  const [replies, setReplies] = useState<Record<string, Page<Comment>>>({})
  const [replyTarget, setReplyTarget] = useState<ReplyTarget | null>(null)
  const [body, setBody] = useState("")
  const [replyBody, setReplyBody] = useState("")
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState("")
  const [notice, setNotice] = useState("")
  const token = session?.token
  const canWrite = session?.user.role === "USER" && status === "PUBLISHED"

  useEffect(() => {
    let active = true
    api<Page<Comment>>(
      `/posts/${postId}/comments?page=${page}&size=${pageSize}&order=${order}`,
      { token }
    ).then(
      (value) => {
        if (active) {
          setRoots(value)
          setError("")
        }
      },
      (problem) => {
        if (active) setError(message(problem))
      }
    )
    return () => {
      active = false
    }
  }, [postId, page, order, token])

  async function refreshRoots(nextPage = page) {
    const result = await api<Page<Comment>>(
      `/posts/${postId}/comments?page=${nextPage}&size=${pageSize}&order=${order}`,
      { token }
    )
    setRoots(result)
  }

  async function loadReplies(rootId: string, nextPage = 1) {
    const result = await api<Page<Comment>>(
      `/comments/${rootId}/replies?page=${nextPage}&size=${pageSize}&order=${order}`,
      { token }
    )
    setReplies((current) => ({ ...current, [rootId]: result }))
  }

  async function toggleReplies(root: Comment) {
    if (expanded[root.id]) {
      setExpanded((current) => ({ ...current, [root.id]: false }))
      return
    }
    setError("")
    try {
      await loadReplies(root.id)
      setExpanded((current) => ({ ...current, [root.id]: true }))
    } catch (problem) {
      setError(message(problem))
    }
  }

  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!token) return
    if (!body.trim() || Array.from(body).length > 1000) {
      setError("评论需为 1–1000 个字符，且不能全为空白。")
      return
    }
    setBusy(true)
    setError("")
    setNotice("")
    let submitted = false
    try {
      await api(`/posts/${postId}/comments`, {
        method: "POST",
        token,
        body: { body },
      })
      submitted = true
      setBody("")
      if (page !== 1) setPage(1)
      else await refreshRoots(1)
      setNotice("评论已发表。")
    } catch (problem) {
      setError(
        submitted
          ? "评论已发表，但列表刷新失败，请刷新后查看。"
          : submitError(problem, "评论")
      )
    } finally {
      setBusy(false)
    }
  }

  async function reply(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!token || !replyTarget) return
    if (!replyBody.trim() || Array.from(replyBody).length > 1000) {
      setError("回复需为 1–1000 个字符，且不能全为空白。")
      return
    }
    setBusy(true)
    setError("")
    setNotice("")
    const rootId = replyTarget.rootId
    let submitted = false
    try {
      await api(`/comments/${replyTarget.id}/replies`, {
        method: "POST",
        token,
        body: { body: replyBody },
      })
      submitted = true
      setReplyBody("")
      setReplyTarget(null)
      await Promise.all([refreshRoots(), loadReplies(rootId)])
      setExpanded((current) => ({ ...current, [rootId]: true }))
      setNotice("回复已发表。")
    } catch (problem) {
      setError(
        submitted
          ? "回复已发表，但列表刷新失败，请刷新后查看。"
          : submitError(problem, "回复")
      )
    } finally {
      setBusy(false)
    }
  }

  async function remove(comment: Comment) {
    if (
      !token ||
      !window.confirm(
        comment.rootId ? "确定删除这条回复？" : "确定删除这条评论及其回复？"
      )
    )
      return
    setBusy(true)
    setError("")
    setNotice("")
    let deleted = false
    try {
      await api(`/comments/${comment.id}`, { method: "DELETE", token })
      deleted = true
      if (comment.rootId)
        await Promise.all([
          refreshRoots(),
          loadReplies(comment.rootId, replies[comment.rootId]?.page ?? 1),
        ])
      else if (roots?.items.length === 1 && page > 1) setPage(page - 1)
      else await refreshRoots()
      setNotice("评论已删除。")
    } catch (problem) {
      setError(
        deleted
          ? "评论已删除，但列表刷新失败，请刷新后查看。"
          : message(problem)
      )
    } finally {
      setBusy(false)
    }
  }

  function commentItem(comment: Comment, rootId: string) {
    const canDelete =
      !!session &&
      (session.user.role === "ADMIN" || session.user.id === comment.author.id)
    const target =
      comment.replyToId && comment.replyToId !== rootId
        ? replies[rootId]?.items.find((item) => item.id === comment.replyToId)
        : null
    return (
      <article
        key={comment.id}
        aria-label={`评论：${comment.body}`}
        className="space-y-2 rounded-xl border border-[#e4ece6] bg-white p-4"
      >
        <div className="flex items-center gap-2 text-sm text-[#71897d]">
          <img
            src={comment.author.avatarUrl}
            alt=""
            className="size-7 rounded-full bg-[#e4eee8]"
          />
          <span className="font-medium text-[#345448]">
            {comment.author.nickname}
          </span>
          <time>{new Date(comment.createdAt).toLocaleString("zh-CN")}</time>
        </div>
        {comment.replyTargetDeleted && (
          <p className="text-xs text-[#9a7863]">回复的评论已删除</p>
        )}
        {!comment.replyTargetDeleted && target && (
          <p className="text-xs text-[#71897d]">
            回复 @{target.author.nickname}
          </p>
        )}
        <p className="text-sm leading-6 break-words whitespace-pre-wrap">
          {comment.body}
        </p>
        <div className="flex gap-2">
          {canWrite && (
            <Button
              type="button"
              variant="ghost"
              size="sm"
              disabled={busy}
              onClick={() => {
                setReplyTarget({
                  id: comment.id,
                  rootId,
                  nickname: comment.author.nickname,
                })
                setReplyBody("")
                setError("")
              }}
            >
              回复
            </Button>
          )}
          {canDelete && (
            <Button
              type="button"
              variant="ghost"
              size="sm"
              disabled={busy}
              onClick={() => void remove(comment)}
            >
              删除
            </Button>
          )}
        </div>
      </article>
    )
  }

  return (
    <section aria-label="评论区" className="mt-8 space-y-5">
      <div className="flex items-center justify-between">
        <h2 className="text-xl font-semibold">
          评论{roots ? `（${roots.total}）` : ""}
        </h2>
        <Button
          type="button"
          variant="ghost"
          aria-label="切换评论排序"
          onClick={() => {
            setOrder((current) => (current === "asc" ? "desc" : "asc"))
            setPage(1)
            setExpanded({})
            setReplies({})
            setReplyTarget(null)
          }}
        >
          {order === "asc" ? "按时间升序" : "按时间降序"}
        </Button>
      </div>
      {error && (
        <p role="alert" className="text-sm text-red-700">
          {error}
        </p>
      )}
      {notice && (
        <p role="status" className="text-sm text-[#28634f]">
          {notice}
        </p>
      )}
      {canWrite ? (
        <form onSubmit={create} className="space-y-3">
          <Label htmlFor="comment-body">评论内容</Label>
          <Textarea
            id="comment-body"
            value={body}
            onChange={(event) => setBody(event.target.value)}
            placeholder="写下你的评论"
            required
          />
          <Button
            type="submit"
            disabled={busy}
            className="bg-[#1c5a4e] text-white hover:bg-[#16493f]"
          >
            {busy ? "提交中…" : "发表评论"}
          </Button>
        </form>
      ) : (
        <p className="text-sm text-[#71897d]">
          {!session
            ? "登录后可以发表评论。"
            : status !== "PUBLISHED"
              ? "作品未公开，暂不能新增评论。"
              : "管理员可管理评论。"}
        </p>
      )}
      {!roots && !error && (
        <p className="text-sm text-[#71897d]">正在加载评论…</p>
      )}
      {roots && roots.items.length === 0 && (
        <p className="text-sm text-[#71897d]">还没有评论。</p>
      )}
      {roots?.items.map((root) => (
        <div key={root.id} className="space-y-3">
          {commentItem(root, root.id)}
          {(root.replyCount ?? 0) > 0 && (
            <Button
              type="button"
              variant="ghost"
              onClick={() => void toggleReplies(root)}
            >
              {expanded[root.id]
                ? "收起回复"
                : `查看回复（${root.replyCount}）`}
            </Button>
          )}
          {expanded[root.id] && (
            <div className="ml-4 space-y-3 border-l border-[#dbe8df] pl-4 sm:ml-8">
              {replies[root.id]?.items.map((item) =>
                commentItem(item, root.id)
              )}
              {replies[root.id] && replies[root.id].total > pageSize && (
                <div className="flex gap-2">
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    disabled={replies[root.id].page === 1}
                    onClick={() =>
                      void loadReplies(
                        root.id,
                        replies[root.id].page - 1
                      ).catch((problem) => setError(message(problem)))
                    }
                  >
                    上一页
                  </Button>
                  <span className="text-sm">第 {replies[root.id].page} 页</span>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    disabled={
                      replies[root.id].page * pageSize >= replies[root.id].total
                    }
                    onClick={() =>
                      void loadReplies(
                        root.id,
                        replies[root.id].page + 1
                      ).catch((problem) => setError(message(problem)))
                    }
                  >
                    下一页
                  </Button>
                </div>
              )}
            </div>
          )}
          {replyTarget?.rootId === root.id && (
            <form onSubmit={reply} className="ml-4 space-y-3 sm:ml-8">
              <Label htmlFor="reply-body">回复内容</Label>
              <p className="text-xs text-[#71897d]">
                回复 @{replyTarget.nickname}
              </p>
              <Textarea
                id="reply-body"
                value={replyBody}
                onChange={(event) => setReplyBody(event.target.value)}
                required
              />
              <div className="flex gap-2">
                <Button
                  type="submit"
                  disabled={busy}
                  className="bg-[#1c5a4e] text-white hover:bg-[#16493f]"
                >
                  发送回复
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  disabled={busy}
                  onClick={() => setReplyTarget(null)}
                >
                  取消
                </Button>
              </div>
            </form>
          )}
        </div>
      ))}
      {roots && roots.total > pageSize && (
        <div className="flex justify-end gap-2">
          <Button
            variant="outline"
            disabled={page === 1 || busy}
            onClick={() => setPage(page - 1)}
          >
            上一页
          </Button>
          <span className="text-sm">第 {page} 页</span>
          <Button
            variant="outline"
            disabled={page * pageSize >= roots.total || busy}
            onClick={() => setPage(page + 1)}
          >
            下一页
          </Button>
        </div>
      )}
    </section>
  )
}
