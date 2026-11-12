import { useEffect, useRef, useState, type FormEvent } from "react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import {
  api,
  ApiError,
  uploadImage,
  type AuthorPost,
  type Page,
  type Post,
  type Resource,
  type Session,
  type User,
  type VideoFileInfo,
  type VideoResource,
} from "@/lib/api"
import { ActivityLists } from "@/components/ActivityLists"
import { ImageUpload } from "@/components/ImageUpload"
import { VideoUpload } from "@/components/VideoUpload"

type Props = {
  session: Session
  onUserChange: (user: User) => void
  onOpenPost: (id: string) => void
}
const size = 6
const statusText: Record<AuthorPost["status"], string> = {
  PENDING: "待审核",
  PUBLISHED: "已发布",
  REJECTED: "已驳回",
}

function explain(error: unknown) {
  if (error instanceof ApiError) {
    if (error.code === "STALE_REVISION")
      return "作品已被更新，请重新打开编辑后再提交。"
    if (
      error.code === "INVALID_AVATAR" ||
      error.code === "INVALID_IMAGE" ||
      error.code === "ANIMATED_AVATAR"
    )
      return "头像必须是有效的静态 JPG、PNG 或 WebP 图片。"
    if (error.code === "INVALID_NICKNAME")
      return "昵称需为 1–32 个字符，且不能全为空白。"
    if (error.code === "NOT_FOUND")
      return "作品不存在或已不可操作，请刷新列表。"
    return `操作未完成（${error.code}）。`
  }
  return "无法连接服务，请稍后重试。"
}

export function Profile({ session, onUserChange, onOpenPost }: Props) {
  const [profile, setProfile] = useState<User>(session.user)
  const [nickname, setNickname] = useState(session.user.nickname)
  const [avatar, setAvatar] = useState<File | null>(null)
  const avatarInput = useRef<HTMLInputElement>(null)
  const [page, setPage] = useState(1)
  const [posts, setPosts] = useState<Page<AuthorPost> | null>(null)
  const [loadError, setLoadError] = useState("")
  const [notice, setNotice] = useState<{
    type: "error" | "success"
    text: string
  } | null>(null)
  const [busy, setBusy] = useState(false)
  const [editing, setEditing] = useState<Post | null>(null)
  const [editTitle, setEditTitle] = useState("")
  const [editBody, setEditBody] = useState("")
  const [resources, setResources] = useState<string[]>([])
  const [images, setImages] = useState<Resource[]>([])
  const [imagePending, setImagePending] = useState(false)
  const [video, setVideo] = useState<VideoResource | null>(null)
  const [videoFile, setVideoFile] = useState<VideoFileInfo | null>(null)
  const [videoHashes, setVideoHashes] = useState<Record<string, string>>({})
  const [videoSelected, setVideoSelected] = useState(false)
  const [tab, setTab] = useState<"posts" | "likes" | "favorites" | "comments">(
    "posts"
  )
  const selectedAttachments =
    editing?.attachments.filter((item) => resources.includes(item.id)) ?? []
  const existingImages = selectedAttachments.filter(
    (item) => item.type === "IMAGE"
  ).length
  const existingVideo = selectedAttachments.some(
    (item) => item.type === "VIDEO"
  )

  useEffect(() => {
    let active = true
    api<User>("/me", { token: session.token }).then(
      (user) => {
        if (active) {
          setProfile(user)
          setNickname(user.nickname)
        }
      },
      (error) => {
        if (active) setNotice({ type: "error", text: explain(error) })
      }
    )
    return () => {
      active = false
    }
  }, [session.token])

  useEffect(() => {
    let active = true
    api<Page<AuthorPost>>(`/me/posts?page=${page}&size=${size}`, {
      token: session.token,
    }).then(
      (result) => {
        if (active) {
          setPosts(result)
          setLoadError("")
        }
      },
      (error) => {
        if (active) setLoadError(explain(error))
      }
    )
    return () => {
      active = false
    }
  }, [page, session.token])

  async function refreshPosts() {
    const result = await api<Page<AuthorPost>>(
      `/me/posts?page=${page}&size=${size}`,
      { token: session.token }
    )
    setPosts(result)
    setLoadError("")
  }

  async function saveProfile(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setNotice(null)
    if (!nickname.trim() || Array.from(nickname).length > 32) {
      setNotice({
        type: "error",
        text: "昵称需为 1–32 个字符，且不能全为空白。",
      })
      return
    }
    if (avatar && (avatar.size === 0 || avatar.size > 5 * 1024 * 1024)) {
      setNotice({ type: "error", text: "头像大小需大于 0 且不超过 5 MiB。" })
      return
    }
    if (!avatar && nickname === profile.nickname) {
      setNotice({ type: "success", text: "资料没有变化。" })
      return
    }
    setBusy(true)
    try {
      const uploaded = avatar
        ? await uploadImage(avatar, "AVATAR", session.token)
        : null
      const user = await api<User>("/me", {
        method: "PATCH",
        token: session.token,
        body: {
          ...(nickname !== profile.nickname ? { nickname } : {}),
          ...(uploaded ? { avatarResourceId: uploaded.id } : {}),
        },
      })
      setProfile(user)
      setNickname(user.nickname)
      setAvatar(null)
      if (avatarInput.current) avatarInput.current.value = ""
      onUserChange(user)
      setNotice({ type: "success", text: "资料已更新。" })
    } catch (error) {
      setNotice({ type: "error", text: explain(error) })
    } finally {
      setBusy(false)
    }
  }

  async function edit(post: AuthorPost) {
    setBusy(true)
    setNotice(null)
    try {
      const detail = await api<Post>(`/posts/${post.id}`, {
        token: session.token,
      })
      setEditing(detail)
      setEditTitle(detail.title)
      setEditBody(detail.body ?? "")
      setResources(detail.attachments.map((item) => item.id))
      setImages([])
      setImagePending(false)
      setVideo(null)
      setVideoSelected(false)
      setVideoFile(null)
      setVideoHashes({})
    } catch (error) {
      setNotice({ type: "error", text: explain(error) })
    } finally {
      setBusy(false)
    }
  }

  async function savePost(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!editing) return
    if (
      !editTitle.trim() ||
      Array.from(editTitle).length > 100 ||
      !editBody.trim() ||
      Array.from(editBody).length > 10000
    ) {
      setNotice({ type: "error", text: "请填写有效的标题和正文。" })
      return
    }
    if (imagePending || (videoSelected && video?.state !== "READY")) {
      setNotice({ type: "error", text: "请先完成附件上传或移除未完成的附件。" })
      return
    }
    setBusy(true)
    setNotice(null)
    try {
      await api(`/posts/${editing.id}`, {
        method: "PUT",
        token: session.token,
        body: {
          revision: editing.revision,
          title: editTitle,
          body: editBody,
          resourceIds: [
            ...resources,
            ...images.map((item) => item.id),
            ...(video ? [video.id] : []),
          ],
        },
      })
      setEditing(null)
      try {
        await refreshPosts()
        setNotice({ type: "success", text: "修改已提交审核。" })
      } catch {
        setNotice({ type: "error", text: "修改已提交，但列表刷新失败。" })
      }
    } catch (error) {
      setNotice({ type: "error", text: explain(error) })
    } finally {
      setBusy(false)
    }
  }

  async function remove(post: AuthorPost) {
    if (!window.confirm(`确定删除“${post.title}”？删除后无法恢复。`)) return
    setBusy(true)
    setNotice(null)
    let deleted = false
    try {
      await api(`/posts/${post.id}`, { method: "DELETE", token: session.token })
      deleted = true
      if (editing?.id === post.id) setEditing(null)
      if (posts?.items.length === 1 && page > 1) setPage(page - 1)
      else await refreshPosts()
      setNotice({ type: "success", text: "作品已删除。" })
    } catch (error) {
      setNotice({
        type: "error",
        text: deleted ? "作品已删除，但列表刷新失败。" : explain(error),
      })
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="space-y-7">
      <div>
        <p className="mb-2 text-sm text-[#789486]">管理资料与作品</p>
        <h1 className="text-3xl font-semibold">个人中心</h1>
      </div>
      {notice && (
        <Alert variant={notice.type === "error" ? "destructive" : "default"}>
          <AlertDescription>{notice.text}</AlertDescription>
        </Alert>
      )}
      <Card className="border border-[#e1ebe4] bg-white shadow-none">
        <CardHeader>
          <CardTitle>个人资料</CardTitle>
        </CardHeader>
        <CardContent>
          <form onSubmit={saveProfile} className="space-y-5">
            <div className="flex items-center gap-4">
              <img
                src={profile.avatarUrl}
                alt="当前头像"
                className="size-16 rounded-full bg-[#e4eee8]"
              />
              <p className="text-sm text-[#71897d]">头像保存后立即生效。</p>
            </div>
            <div className="space-y-2">
              <Label htmlFor="profile-nickname">昵称</Label>
              <Input
                id="profile-nickname"
                value={nickname}
                onChange={(event) => setNickname(event.target.value)}
                required
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="profile-avatar">选择头像</Label>
              <Input
                ref={avatarInput}
                id="profile-avatar"
                type="file"
                accept="image/jpeg,image/png,image/webp"
                onChange={(event) => setAvatar(event.target.files?.[0] ?? null)}
              />
              <p className="text-xs text-[#71897d]">
                静态 JPG、PNG 或 WebP，最大 5 MiB。
              </p>
            </div>
            <Button
              type="submit"
              disabled={busy}
              className="bg-[#1c5a4e] text-white hover:bg-[#16493f]"
            >
              {busy ? "处理中…" : "保存资料"}
            </Button>
          </form>
        </CardContent>
      </Card>
      <nav aria-label="个人中心导航" className="flex flex-wrap gap-2">
        {(
          [
            ["posts", "我的投稿"],
            ["likes", "我的点赞"],
            ["favorites", "我的收藏"],
            ["comments", "我的评论"],
          ] as const
        ).map(([value, label]) => (
          <Button
            key={value}
            type="button"
            variant={tab === value ? "secondary" : "ghost"}
            onClick={() => {
              setTab(value)
              setNotice(null)
            }}
          >
            {label}
          </Button>
        ))}
      </nav>
      {tab === "posts" && (
        <>
          {editing && (
            <Card className="border border-[#e1ebe4] bg-white shadow-none">
              <CardHeader>
                <CardTitle>编辑作品</CardTitle>
                <p className="text-sm text-[#71897d]">
                  提交修改后，作品将重新进入审核。
                </p>
              </CardHeader>
              <CardContent>
                <form onSubmit={savePost} className="space-y-5">
                  <div className="space-y-2">
                    <Label htmlFor="edit-title">作品标题</Label>
                    <Input
                      id="edit-title"
                      value={editTitle}
                      onChange={(event) => setEditTitle(event.target.value)}
                      required
                    />
                  </div>
                  <div className="space-y-2">
                    <Label htmlFor="edit-body">作品正文</Label>
                    <Textarea
                      id="edit-body"
                      value={editBody}
                      onChange={(event) => setEditBody(event.target.value)}
                      required
                      className="min-h-40"
                    />
                  </div>
                  {editing.attachments.length > 0 && (
                    <div className="space-y-2">
                      <p className="text-sm font-medium">已关联附件</p>
                      {editing.attachments.map((item, index) => (
                        <div
                          key={item.id}
                          className="flex items-center justify-between gap-3 rounded-lg border p-3 text-sm"
                        >
                          <span>
                            {item.type === "VIDEO" ? "视频" : "图片"}{" "}
                            {index + 1}
                            {!resources.includes(item.id)
                              ? "（提交后移除）"
                              : ""}
                          </span>
                          <Button
                            type="button"
                            variant="ghost"
                            disabled={
                              !resources.includes(item.id) &&
                              (item.type === "VIDEO"
                                ? existingVideo ||
                                  existingImages > 0 ||
                                  images.length > 0 ||
                                  imagePending ||
                                  videoSelected
                                : existingVideo ||
                                  videoSelected ||
                                  imagePending ||
                                  existingImages + images.length >= 9)
                            }
                            onClick={() =>
                              setResources((current) =>
                                current.includes(item.id)
                                  ? current.filter((id) => id !== item.id)
                                  : [...current, item.id]
                              )
                            }
                          >
                            {resources.includes(item.id)
                              ? "移除附件"
                              : "保留附件"}
                          </Button>
                        </div>
                      ))}
                    </div>
                  )}
                  <ImageUpload
                    token={session.token}
                    images={images}
                    existingCount={existingImages}
                    disabled={existingVideo || videoSelected}
                    inputId="edit-images"
                    onChange={setImages}
                    onPendingChange={setImagePending}
                  />
                  <VideoUpload
                    token={session.token}
                    video={video}
                    fileInfo={videoFile}
                    hashes={videoHashes}
                    selected={videoSelected}
                    disabled={
                      existingVideo ||
                      existingImages > 0 ||
                      images.length > 0 ||
                      imagePending
                    }
                    onChange={(next, selected, file, hashes) => {
                      setVideo(next)
                      setVideoSelected(selected)
                      setVideoFile(file)
                      setVideoHashes(hashes)
                    }}
                  />
                  <div className="flex gap-2">
                    <Button
                      type="submit"
                      disabled={
                        busy ||
                        imagePending ||
                        (videoSelected && video?.state !== "READY")
                      }
                      className="bg-[#1c5a4e] text-white hover:bg-[#16493f]"
                    >
                      提交修改
                    </Button>
                    <Button
                      type="button"
                      variant="outline"
                      disabled={busy}
                      onClick={() => setEditing(null)}
                    >
                      取消编辑
                    </Button>
                  </div>
                </form>
              </CardContent>
            </Card>
          )}
          <div>
            <h2 className="text-xl font-semibold">我的投稿</h2>
            <p className="mt-2 text-sm text-[#71897d]">
              查看审核状态，修改或删除自己的作品。
            </p>
          </div>
          {loadError && (
            <Alert variant="destructive">
              <AlertDescription>
                {loadError}{" "}
                <Button
                  variant="link"
                  type="button"
                  onClick={() =>
                    void refreshPosts().catch((error) =>
                      setLoadError(explain(error))
                    )
                  }
                >
                  重试
                </Button>
              </AlertDescription>
            </Alert>
          )}
          {!posts && !loadError && (
            <p className="text-sm text-[#71897d]">正在加载投稿…</p>
          )}
          {posts && posts.items.length === 0 && (
            <p className="rounded-xl border border-dashed p-6 text-sm text-[#71897d]">
              还没有投稿。
            </p>
          )}
          <div className="space-y-3">
            {posts?.items.map((post) => (
              <Card
                key={post.id}
                className="border border-[#e1ebe4] bg-white shadow-none"
              >
                <CardContent className="space-y-4">
                  <div className="flex items-start justify-between gap-3">
                    <h3 className="font-semibold">{post.title}</h3>
                    <Badge variant="secondary">{statusText[post.status]}</Badge>
                  </div>
                  {post.status === "REJECTED" && post.rejectionReason && (
                    <p className="text-sm text-[#9a5c40]">
                      驳回原因：{post.rejectionReason}
                    </p>
                  )}
                  <div className="flex gap-2">
                    <Button
                      type="button"
                      variant="outline"
                      disabled={busy}
                      onClick={() => void edit(post)}
                    >
                      编辑
                    </Button>
                    <Button
                      type="button"
                      variant="ghost"
                      disabled={busy}
                      onClick={() => void remove(post)}
                    >
                      删除
                    </Button>
                  </div>
                </CardContent>
              </Card>
            ))}
          </div>
          {posts && posts.total > size && (
            <div className="flex justify-end gap-2">
              <Button
                variant="outline"
                disabled={page === 1 || busy}
                onClick={() => setPage(page - 1)}
              >
                上一页
              </Button>
              <span className="px-2 py-1 text-sm">第 {page} 页</span>
              <Button
                variant="outline"
                disabled={page * size >= posts.total || busy}
                onClick={() => setPage(page + 1)}
              >
                下一页
              </Button>
            </div>
          )}
        </>
      )}
      {tab !== "posts" && (
        <ActivityLists
          key={tab}
          kind={tab}
          token={session.token}
          onOpenPost={onOpenPost}
        />
      )}
    </div>
  )
}
