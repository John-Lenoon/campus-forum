import { useEffect, useState } from "react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import {
  api,
  ApiError,
  type CommentHistory,
  type Page,
  type Post,
} from "@/lib/api"

type Props = {
  kind: "likes" | "favorites" | "comments"
  token: string
  onOpenPost: (id: string) => void
}
const size = 6

export function ActivityLists({ kind, token, onOpenPost }: Props) {
  const [page, setPage] = useState(1)
  const [data, setData] = useState<Page<Post | CommentHistory> | null>(null)
  const [error, setError] = useState("")
  const [notice, setNotice] = useState("")
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let active = true
    api<Page<Post | CommentHistory>>(`/me/${kind}?page=${page}&size=${size}`, {
      token,
    }).then(
      (result) => {
        if (active) {
          setData(result)
          setError("")
        }
      },
      () => {
        if (active) setError("暂时无法加载，请稍后重试。")
      }
    )
    return () => {
      active = false
    }
  }, [kind, page, token])

  async function openComment(item: CommentHistory) {
    setBusy(true)
    setNotice("")
    try {
      await api(`/comments/${item.id}`, { token })
      onOpenPost(item.postId)
    } catch (problem) {
      if (problem instanceof ApiError && problem.code === "COMMENT_DELETED")
        setNotice("该评论已删除")
      else if (problem instanceof ApiError && problem.code === "NOT_FOUND")
        setNotice("所属内容不存在或不可见。")
      else setNotice("无法查看评论，请稍后重试。")
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="space-y-4">
      <h2 className="text-xl font-semibold">
        {kind === "likes"
          ? "我的点赞"
          : kind === "favorites"
            ? "我的收藏"
            : "我的评论"}
      </h2>
      {error && (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      {notice && (
        <p role="status" className="text-sm text-[#71897d]">
          {notice}
        </p>
      )}
      {!data && !error && <p className="text-sm text-[#71897d]">正在加载…</p>}
      {data && data.items.length === 0 && (
        <p className="rounded-xl border border-dashed p-6 text-sm text-[#71897d]">
          {kind === "comments" ? "还没有评论记录。" : "暂时没有可查看的作品。"}
        </p>
      )}
      {kind === "comments"
        ? (data?.items as CommentHistory[] | undefined)?.map((item) => (
            <article
              key={item.id}
              aria-label={`历史评论：${item.body}`}
              className="space-y-3 rounded-xl border border-[#e1ebe4] bg-white p-5"
            >
              <div className="flex justify-between gap-3">
                <p className="text-sm break-words whitespace-pre-wrap">
                  {item.body}
                </p>
                {item.deleted && <Badge variant="secondary">已删除</Badge>}
              </div>
              <p className="text-xs text-[#71897d]">
                {new Date(item.createdAt).toLocaleString("zh-CN")}
              </p>
              <Button
                variant="outline"
                disabled={busy}
                onClick={() => void openComment(item)}
              >
                查看评论
              </Button>
            </article>
          ))
        : (data?.items as Post[] | undefined)?.map((item) => (
            <Card
              key={item.id}
              className="border border-[#e1ebe4] bg-white shadow-none"
            >
              <CardContent className="space-y-3">
                <h3 className="font-semibold">{item.title}</h3>
                <p className="text-sm text-[#71897d]">
                  {item.author.nickname} · {item.likeCount} 点赞 ·{" "}
                  {item.favoriteCount} 收藏
                </p>
                <Button variant="outline" onClick={() => onOpenPost(item.id)}>
                  查看作品
                </Button>
              </CardContent>
            </Card>
          ))}
      {data && data.total > size && (
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
            disabled={page * size >= data.total || busy}
            onClick={() => setPage(page + 1)}
          >
            下一页
          </Button>
        </div>
      )}
    </div>
  )
}
