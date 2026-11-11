import { useCallback, useEffect, useState, type FormEvent } from "react"
import {
  ArrowLeft,
  ArrowRight,
  BookOpenText,
  Check,
  Clock3,
  Heart,
  LogOut,
  PenLine,
  ShieldCheck,
  Sparkles,
  X,
} from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import { VideoUpload } from "@/components/VideoUpload"
import { VideoPlayer } from "@/components/VideoPlayer"
import { ReviewMedia } from "@/components/ReviewMedia"
import {
  api,
  ApiError,
  readVideoDraft,
  videoDraftKey,
  type Page,
  type Post,
  type Session,
  type VideoResource,
  type VideoFileInfo,
} from "@/lib/api"

type Screen = "feed" | "write" | "review"
type Notice = { type: "error" | "success"; text: string }
const pageSize = 6
const sessionKey = "campus-forum-session"

function loadSession(): Session | null {
  try {
    const raw = sessionStorage.getItem(sessionKey)
    if (!raw) return null
    const session = JSON.parse(raw) as Session
    if (!session.token || !session.user || session.expiresAt <= Date.now()) {
      sessionStorage.removeItem(sessionKey)
      return null
    }
    return session
  } catch {
    sessionStorage.removeItem(sessionKey)
    return null
  }
}

function errorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return "无法连接服务，请稍后重试。"
  const messages: Record<string, string> = {
    AUTH_REQUIRED: "请先登录。",
    INVALID_TOKEN: "登录已失效，请重新登录。",
    TOKEN_EXPIRED: "登录已过期，请重新登录。",
    INVALID_CREDENTIALS: "账号或密码不正确。",
    USERNAME_EXISTS: "这个账号已被使用，请换一个。",
    INVALID_USERNAME: "账号需为 4–32 位字母、数字或下划线。",
    INVALID_PASSWORD: "密码需为 8–64 个字符，且不能全为空白。",
    INVALID_NICKNAME: "昵称需为 1–32 个字符，且不能全为空白。",
    INVALID_TITLE: "标题需为 1–100 个字符，且不能全为空白。",
    INVALID_BODY: "正文需为 1–10000 个字符，且不能全为空白。",
    INVALID_REASON: "请填写 1–1000 个字符的驳回原因。",
    STALE_REVISION: "作品已更新，请刷新审核列表后重试。",
    REVIEW_CONFLICT: "这篇作品已有审核结果，请刷新列表。",
    ROLE_FORBIDDEN: "当前账号没有此操作权限。",
    NOT_FOUND: "内容不存在或当前不可见，请刷新后重试。",
    DEPENDENCY_UNAVAILABLE: "服务暂时不可用，请稍后重试。",
  }
  return messages[error.code] ?? `操作未完成（${error.code}），请稍后重试。`
}

function dateLabel(time: number | null) {
  return time === null
    ? "刚刚"
    : new Intl.DateTimeFormat("zh-CN", {
        year: "numeric",
        month: "short",
        day: "numeric",
      }).format(time)
}

export default function App() {
  const [session, setSession] = useState<Session | null>(loadSession)
  const [screen, setScreen] = useState<Screen>("feed")
  const [posts, setPosts] = useState<Page<Post> | null>(null)
  const [pending, setPending] = useState<Page<Post> | null>(null)
  const [page, setPage] = useState(1)
  const [reviewPage, setReviewPage] = useState(1)
  const [detail, setDetail] = useState<Post | null>(null)
  const [authMode, setAuthMode] = useState<"login" | "register">("login")
  const [username, setUsername] = useState("")
  const [password, setPassword] = useState("")
  const [nickname, setNickname] = useState("")
  const [title, setTitle] = useState("")
  const [body, setBody] = useState("")
  const [restoredVideo] = useState(() => readVideoDraft(session?.user.id))
  const [video, setVideo] = useState<VideoResource | null>(
    restoredVideo?.resource ?? null
  )
  const [videoFile, setVideoFile] = useState<VideoFileInfo | null>(
    restoredVideo?.file ?? null
  )
  const [videoHashes, setVideoHashes] = useState<Record<string, string>>(
    restoredVideo?.hashes ?? {}
  )
  const [videoSelected, setVideoSelected] = useState(!!restoredVideo)
  const [reasons, setReasons] = useState<Record<string, string>>({})
  const [notice, setNotice] = useState<Notice | null>(null)
  const [busy, setBusy] = useState(false)
  const [loading, setLoading] = useState(true)

  const refreshFeed = useCallback(async (nextPage: number) => {
    setLoading(true)
    try {
      setPosts(
        await api<Page<Post>>(`/posts?page=${nextPage}&size=${pageSize}`)
      )
      return true
    } catch (error) {
      setPosts(null)
      setNotice({ type: "error", text: errorMessage(error) })
      return false
    } finally {
      setLoading(false)
    }
  }, [])
  const refreshPending = useCallback(
    async (token: string, nextPage: number) => {
      try {
        setPending(
          await api<Page<Post>>(
            `/admin/posts?status=PENDING&page=${nextPage}&size=${pageSize}`,
            { token }
          )
        )
        return true
      } catch (error) {
        setPending(null)
        setNotice({ type: "error", text: errorMessage(error) })
        return false
      }
    },
    []
  )
  useEffect(() => {
    let active = true
    api<Page<Post>>(`/posts?page=${page}&size=${pageSize}`).then(
      (result) => {
        if (active) {
          setPosts(result)
          setLoading(false)
        }
      },
      (error) => {
        if (active) {
          setPosts(null)
          setLoading(false)
          setNotice({ type: "error", text: errorMessage(error) })
        }
      }
    )
    return () => {
      active = false
    }
  }, [page])
  useEffect(() => {
    if (screen !== "review" || session?.user.role !== "ADMIN") return
    let active = true
    api<Page<Post>>(
      `/admin/posts?status=PENDING&page=${reviewPage}&size=${pageSize}`,
      { token: session.token }
    ).then(
      (result) => {
        if (active) setPending(result)
      },
      (error) => {
        if (active) {
          setPending(null)
          setNotice({ type: "error", text: errorMessage(error) })
        }
      }
    )
    return () => {
      active = false
    }
  }, [screen, session, reviewPage])

  function navigate(next: Screen) {
    setScreen(next)
    setDetail(null)
    setNotice(null)
  }
  function logout() {
    sessionStorage.removeItem(sessionKey)
    setSession(null)
    setPending(null)
    setPassword("")
    setVideo(null)
    setVideoSelected(false)
    setVideoFile(null)
    setVideoHashes({})
    navigate("feed")
  }

  const updateVideo = useCallback(
    (
      next: VideoResource | null,
      selected: boolean,
      file: VideoFileInfo | null,
      hashes: Record<string, string>
    ) => {
      setVideo(next)
      setVideoSelected(selected)
      setVideoFile(file)
      setVideoHashes(hashes)
      if (session?.user.id) {
        const key = videoDraftKey(session.user.id)
        if (next && file)
          sessionStorage.setItem(
            key,
            JSON.stringify({ resource: next, file, hashes })
          )
        else sessionStorage.removeItem(key)
      }
    },
    [session]
  )

  async function authenticate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setBusy(true)
    setNotice(null)
    try {
      if (authMode === "register") {
        await api("/auth/register", {
          method: "POST",
          body: {
            username,
            password,
            ...(nickname.trim() ? { nickname } : {}),
          },
        })
        setAuthMode("login")
        setPassword("")
        setNotice({ type: "success", text: "账号创建成功，请登录。" })
      } else {
        const next = await api<Session>("/auth/login", {
          method: "POST",
          body: { username, password },
        })
        sessionStorage.setItem(sessionKey, JSON.stringify(next))
        setSession(next)
        const saved = readVideoDraft(next.user.id)
        setVideo(saved?.resource ?? null)
        setVideoFile(saved?.file ?? null)
        setVideoHashes(saved?.hashes ?? {})
        setVideoSelected(!!saved)
        setPassword("")
        setNotice({
          type: "success",
          text: `欢迎回来，${next.user.nickname}。`,
        })
        if (next.user.role === "ADMIN") setScreen("review")
      }
    } catch (error) {
      setNotice({ type: "error", text: errorMessage(error) })
    } finally {
      setBusy(false)
    }
  }

  async function submitPost(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!session) return
    if (videoSelected && video?.state !== "READY") {
      setNotice({
        type: "error",
        text: "请等待视频处理完成，或移除视频后再投稿。",
      })
      return
    }
    if (
      !title.trim() ||
      Array.from(title).length > 100 ||
      !body.trim() ||
      Array.from(body).length > 10000
    ) {
      setNotice({
        type: "error",
        text: "请填写标题和正文；标题最多 100 字，正文最多 10000 字。",
      })
      return
    }
    setBusy(true)
    setNotice(null)
    try {
      await api<Post>("/posts", {
        method: "POST",
        token: session.token,
        body: { title, body, resourceIds: video ? [video.id] : [] },
      })
      setTitle("")
      setBody("")
      setVideo(null)
      setVideoSelected(false)
      setVideoFile(null)
      setVideoHashes({})
      sessionStorage.removeItem(videoDraftKey(session.user.id))
      setScreen("feed")
      setPage(1)
      if (await refreshFeed(1))
        setNotice({
          type: "success",
          text: "投稿成功，审核通过后会出现在作品列表。",
        })
      else
        setNotice({
          type: "error",
          text: "投稿已提交，但作品列表刷新失败，请稍后重试。",
        })
    } catch (error) {
      setNotice({ type: "error", text: errorMessage(error) })
    } finally {
      setBusy(false)
    }
  }

  async function review(post: Post, decision: "APPROVE" | "REJECT") {
    if (!session) return
    const reason = reasons[post.id] ?? ""
    if (
      decision === "REJECT" &&
      (!reason.trim() || Array.from(reason).length > 1000)
    ) {
      setNotice({ type: "error", text: "请填写不超过 1000 字的驳回原因。" })
      return
    }
    setBusy(true)
    setNotice(null)
    try {
      await api(`/admin/posts/${post.id}/review`, {
        method: "POST",
        token: session.token,
        body: {
          revision: post.revision,
          decision,
          ...(decision === "REJECT" ? { reason } : {}),
        },
      })
      const refreshed = await Promise.all([
        refreshPending(session.token, reviewPage),
        refreshFeed(page),
      ])
      if (refreshed.every(Boolean))
        setNotice({
          type: "success",
          text:
            decision === "APPROVE"
              ? "已通过审核，作品现已公开。"
              : "已驳回投稿。",
        })
      else
        setNotice({
          type: "error",
          text: "审核已完成，但列表刷新失败，请稍后重试。",
        })
    } catch (error) {
      setNotice({ type: "error", text: errorMessage(error) })
      await refreshPending(session.token, reviewPage)
    } finally {
      setBusy(false)
    }
  }

  async function openPost(id: string) {
    setBusy(true)
    setNotice(null)
    try {
      setDetail(await api<Post>(`/posts/${id}`, { token: session?.token }))
    } catch (error) {
      setNotice({ type: "error", text: errorMessage(error) })
      await refreshFeed(page)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="min-h-screen bg-[#f4f6f5] text-[#172a29]">
      <header className="sticky top-0 z-20 border-b border-[#dce5e1] bg-[#fbfcfa]/95 backdrop-blur">
        <div className="mx-auto flex h-17 max-w-7xl items-center justify-between gap-4 px-5 sm:px-8">
          <button
            type="button"
            onClick={() => navigate("feed")}
            className="flex items-center gap-3 text-left"
            aria-label="校园原创首页"
          >
            <span className="grid size-9 place-items-center rounded-xl bg-[#1c5a4e] text-white">
              <BookOpenText className="size-5" />
            </span>
            <span>
              <span className="block text-base font-bold tracking-wide">
                校园原创
              </span>
              <span className="block text-[10px] tracking-[0.16em] text-[#728b81]">
                CAMPUS STORIES
              </span>
            </span>
          </button>
          <nav aria-label="主导航" className="flex items-center gap-1 sm:gap-3">
            <Button
              variant="ghost"
              onClick={() => navigate("feed")}
              className={
                screen === "feed"
                  ? "bg-[#e4eee8] text-[#1c5a4e]"
                  : "text-[#627a70]"
              }
            >
              发现作品
            </Button>
            {session?.user.role === "USER" && (
              <Button
                variant="ghost"
                onClick={() => navigate("write")}
                className={
                  screen === "write"
                    ? "bg-[#e4eee8] text-[#1c5a4e]"
                    : "text-[#627a70]"
                }
              >
                投稿
              </Button>
            )}
            {session?.user.role === "ADMIN" && (
              <Button
                variant="ghost"
                onClick={() => navigate("review")}
                className={
                  screen === "review"
                    ? "bg-[#e4eee8] text-[#1c5a4e]"
                    : "text-[#627a70]"
                }
              >
                审核
              </Button>
            )}
          </nav>
          {session ? (
            <Button
              variant="ghost"
              onClick={logout}
              aria-label="切换账号"
              className="text-[#627a70]"
            >
              <LogOut /> <span className="hidden sm:inline">切换账号</span>
            </Button>
          ) : (
            <a
              href="#account"
              className="text-sm font-medium text-[#1c5a4e] hover:underline"
            >
              登录 / 注册
            </a>
          )}
        </div>
      </header>
      <main className="mx-auto max-w-7xl px-5 pb-16 sm:px-8">
        <div className="grid gap-8 py-8 lg:grid-cols-[minmax(0,1fr)_300px] lg:gap-12 lg:py-12">
          <section className="min-w-0">
            {notice && (
              <Alert
                variant={notice.type === "error" ? "destructive" : "default"}
                className="mb-6 border border-[#cbded3] bg-white"
              >
                <AlertDescription>{notice.text}</AlertDescription>
              </Alert>
            )}
            {screen === "feed" && !detail && (
              <>
                <div className="relative overflow-hidden rounded-3xl bg-[#dcebe1] px-7 py-9 sm:px-10 sm:py-11">
                  <div className="relative z-10 max-w-xl">
                    <div className="mb-5 inline-flex items-center gap-2 rounded-full bg-white/70 px-3 py-1.5 text-xs font-medium text-[#3d7565]">
                      <Sparkles className="size-3.5" /> 记录校园里的灵感
                    </div>
                    <h1 className="text-3xl leading-tight font-semibold tracking-tight sm:text-[2.6rem]">
                      把想法写下来，
                      <br />
                      让更多人看见。
                    </h1>
                    <p className="mt-4 max-w-md text-sm leading-7 text-[#52746a]">
                      每一篇原创，都是校园生活的另一种打开方式。在这里，分享你的观察与创作。
                    </p>
                    {session?.user.role === "USER" && (
                      <Button
                        onClick={() => navigate("write")}
                        className="mt-6 h-10 bg-[#1c5a4e] px-4 text-white hover:bg-[#16493f]"
                      >
                        <PenLine /> 写一篇作品
                      </Button>
                    )}
                    {!session && (
                      <a
                        href="#account"
                        className="mt-6 inline-flex h-10 items-center gap-2 rounded-lg bg-[#1c5a4e] px-4 text-sm font-medium text-white hover:bg-[#16493f]"
                      >
                        加入创作 <ArrowRight className="size-4" />
                      </a>
                    )}
                  </div>
                  <div
                    aria-hidden="true"
                    className="absolute -top-20 -right-9 size-60 rounded-full border-[36px] border-[#c6ded1]/70 sm:size-72"
                  />
                  <div
                    aria-hidden="true"
                    className="absolute right-20 -bottom-20 size-40 rounded-full bg-[#eaf3d6]/80"
                  />
                </div>
                <div className="mt-10 mb-5 flex items-end justify-between">
                  <div>
                    <p className="mb-1 text-xs font-medium text-[#7a9688]">
                      在这里发现
                    </p>
                    <h2 className="text-xl font-semibold">最新作品</h2>
                  </div>
                  <span className="text-xs text-[#839990]">按发布时间排序</span>
                </div>
                {loading ? (
                  <p className="py-12 text-center text-sm text-[#7a8f86]">
                    正在加载作品…
                  </p>
                ) : posts && posts.items.length === 0 ? (
                  <div className="rounded-2xl border border-dashed border-[#cddbd3] bg-white/60 px-6 py-14 text-center">
                    <BookOpenText className="mx-auto mb-3 size-6 text-[#8ba89a]" />
                    <p className="font-medium">这里还没有公开作品</p>
                    <p className="mt-2 text-sm text-[#82978d]">
                      审核通过的原创作品会出现在这里。
                    </p>
                  </div>
                ) : (
                  <div className="grid gap-4 sm:grid-cols-2">
                    {posts?.items.map((post, index) => (
                      <button
                        key={post.id}
                        type="button"
                        disabled={busy}
                        onClick={() => void openPost(post.id)}
                        className="group text-left"
                      >
                        <Card className="h-full border border-[#e2ebe5] bg-white shadow-none transition-colors hover:border-[#96bcaa]">
                          <CardContent className="flex h-full flex-col gap-5 pt-1">
                            <div className="flex items-center justify-between">
                              <Badge
                                variant="secondary"
                                className={
                                  index % 2 === 0
                                    ? "bg-[#eaf3e7] text-[#5e8065]"
                                    : "bg-[#f5efdf] text-[#957a41]"
                                }
                              >
                                原创作品
                              </Badge>
                              <span className="text-xs text-[#9bad9f]">
                                {dateLabel(post.firstPublishedAt)}
                              </span>
                            </div>
                            <h3 className="line-clamp-2 min-h-12 text-lg leading-6 font-semibold group-hover:text-[#1c6b58]">
                              {post.title}
                            </h3>
                            <div className="mt-auto flex items-center justify-between border-t border-[#edf2ee] pt-4">
                              <span className="flex items-center gap-2 text-xs text-[#678076]">
                                <img
                                  src={post.author.avatarUrl}
                                  alt=""
                                  className="size-7 rounded-full bg-[#e4eee8]"
                                />
                                {post.author.nickname}
                              </span>
                              <span className="flex items-center gap-1 text-xs text-[#9bac9f]">
                                <Heart className="size-3.5" />
                                {post.likeCount}
                              </span>
                            </div>
                          </CardContent>
                        </Card>
                      </button>
                    ))}
                  </div>
                )}
                {posts && posts.total > pageSize && (
                  <div className="mt-7 flex justify-end gap-2">
                    <Button
                      variant="outline"
                      disabled={page === 1 || loading}
                      onClick={() => setPage(page - 1)}
                    >
                      上一页
                    </Button>
                    <span className="px-2 py-1 text-sm text-[#71897d]">
                      第 {page} 页
                    </span>
                    <Button
                      variant="outline"
                      disabled={page * pageSize >= posts.total || loading}
                      onClick={() => setPage(page + 1)}
                    >
                      下一页
                    </Button>
                  </div>
                )}
              </>
            )}
            {screen === "feed" && detail && (
              <article>
                <Button
                  variant="ghost"
                  className="mb-6 pl-0 text-[#55786a]"
                  onClick={() => setDetail(null)}
                >
                  <ArrowLeft /> 返回作品列表
                </Button>
                <Card className="border border-[#e1ebe4] bg-white shadow-none">
                  <CardContent className="px-6 py-5 sm:px-10 sm:py-8">
                    <Badge
                      variant="secondary"
                      className="bg-[#eaf3e7] text-[#5e8065]"
                    >
                      原创作品
                    </Badge>
                    <h1 className="mt-5 text-3xl leading-tight font-semibold">
                      {detail.title}
                    </h1>
                    <div className="mt-5 flex items-center gap-3 border-b border-[#e8efea] pb-6 text-sm text-[#71897d]">
                      <img
                        src={detail.author.avatarUrl}
                        alt=""
                        className="size-9 rounded-full bg-[#e4eee8]"
                      />
                      <span>{detail.author.nickname}</span>
                      <span>·</span>
                      <span>{dateLabel(detail.firstPublishedAt)}</span>
                    </div>
                    <div className="min-h-48 py-8 text-[15px] leading-8 whitespace-pre-wrap text-[#344d43]">
                      {detail.body}
                    </div>
                    {detail.attachments
                      .filter((item) => item.type === "VIDEO")
                      .map((item) => (
                        <VideoPlayer
                          key={item.id}
                          url={item.url}
                          token={session?.token}
                        />
                      ))}
                    <div className="flex gap-5 border-t border-[#e8efea] pt-5 text-sm text-[#83978d]">
                      <span className="flex items-center gap-1.5">
                        <Heart className="size-4" /> {detail.likeCount} 点赞
                      </span>
                      <span>{detail.favoriteCount} 收藏</span>
                    </div>
                  </CardContent>
                </Card>
              </article>
            )}
            {screen === "write" && session?.user.role === "USER" && (
              <>
                <div className="mb-8">
                  <p className="mb-2 text-sm text-[#789486]">
                    一张空白页，留给你的灵感
                  </p>
                  <h1 className="text-3xl font-semibold">写一篇作品</h1>
                  <p className="mt-3 text-sm leading-6 text-[#72897e]">
                    分享文字和视频。提交后作品会进入审核，通过后公开展示。
                  </p>
                </div>
                <Card className="border border-[#e1ebe4] bg-white shadow-none">
                  <CardContent className="px-6 sm:px-8">
                    <form onSubmit={submitPost} className="space-y-6">
                      <div className="space-y-2">
                        <Label htmlFor="post-title">标题</Label>
                        <Input
                          id="post-title"
                          required
                          value={title}
                          onChange={(e) => setTitle(e.target.value)}
                          placeholder="给你的作品起个标题"
                          className="h-11"
                        />
                        <p className="text-right text-xs text-[#8ca097]">
                          {Array.from(title).length} / 100
                        </p>
                      </div>
                      <div className="space-y-2">
                        <Label htmlFor="post-body">正文</Label>
                        <Textarea
                          id="post-body"
                          required
                          value={body}
                          onChange={(e) => setBody(e.target.value)}
                          placeholder="从这里开始写下你的故事…"
                          className="min-h-64 resize-y leading-7"
                        />
                        <p className="text-right text-xs text-[#8ca097]">
                          {Array.from(body).length} / 10000
                        </p>
                      </div>
                      <VideoUpload
                        token={session.token}
                        video={video}
                        fileInfo={videoFile}
                        hashes={videoHashes}
                        selected={videoSelected}
                        onChange={updateVideo}
                      />
                      <div className="flex flex-wrap items-center justify-between gap-4 border-t border-[#edf2ee] pt-5">
                        <p className="flex items-center gap-2 text-xs text-[#80968a]">
                          <Clock3 className="size-4" /> 投稿后需经管理员审核
                        </p>
                        <Button
                          type="submit"
                          disabled={
                            busy || (videoSelected && video?.state !== "READY")
                          }
                          className="h-10 bg-[#1c5a4e] px-5 text-white hover:bg-[#16493f]"
                        >
                          {busy ? "提交中…" : "提交审核"} <ArrowRight />
                        </Button>
                      </div>
                    </form>
                  </CardContent>
                </Card>
              </>
            )}
            {screen === "review" && session?.user.role === "ADMIN" && (
              <>
                <div className="mb-8 flex items-end justify-between gap-4">
                  <div>
                    <p className="mb-2 text-sm text-[#789486]">
                      为每一份原创把关
                    </p>
                    <h1 className="text-3xl font-semibold">待审核作品</h1>
                    <p className="mt-3 text-sm text-[#72897e]">
                      审核通过后，作品将向所有访客展示。
                    </p>
                  </div>
                  <Badge
                    variant="secondary"
                    className="bg-[#e4eee8] text-[#376956]"
                  >
                    {pending?.total ?? "—"} 篇待审
                  </Badge>
                </div>
                {pending && pending.items.length === 0 ? (
                  <div className="rounded-2xl border border-dashed border-[#cddbd3] bg-white/60 px-6 py-14 text-center">
                    <Check className="mx-auto mb-3 size-6 text-[#8ba89a]" />
                    <p className="font-medium">暂时没有待审核作品</p>
                    <p className="mt-2 text-sm text-[#82978d]">
                      新的投稿会出现在这里。
                    </p>
                  </div>
                ) : (
                  <div className="space-y-4">
                    {pending?.items.map((post) => (
                      <Card
                        key={post.id}
                        className="border border-[#e1ebe4] bg-white shadow-none"
                      >
                        <CardHeader>
                          <div className="flex items-center justify-between gap-3">
                            <CardTitle className="text-lg font-semibold">
                              {post.title}
                            </CardTitle>
                            <Badge
                              variant="secondary"
                              className="bg-[#f8f1dc] text-[#9a7934]"
                            >
                              待审核
                            </Badge>
                          </div>
                          <p className="text-xs text-[#8ba096]">
                            {post.author.nickname} ·{" "}
                            {dateLabel(post.submittedAt)}
                          </p>
                        </CardHeader>
                        <CardContent>
                          <p className="text-sm leading-7 break-words whitespace-pre-wrap text-[#4b6559]">
                            {post.body}
                          </p>
                          <ReviewMedia id={post.id} token={session.token} />
                          <div className="mt-5 border-t border-[#edf2ee] pt-5">
                            <Label
                              htmlFor={`reason-${post.id}`}
                              className="mb-2 text-[#71897d]"
                            >
                              驳回原因（驳回时必填）
                            </Label>
                            <Textarea
                              id={`reason-${post.id}`}
                              value={reasons[post.id] ?? ""}
                              onChange={(e) =>
                                setReasons({
                                  ...reasons,
                                  [post.id]: e.target.value,
                                })
                              }
                              placeholder="向作者说明需要修改的地方"
                              className="min-h-16"
                            />
                            <div className="mt-4 flex justify-end gap-2">
                              <Button
                                variant="outline"
                                disabled={busy}
                                onClick={() => void review(post, "REJECT")}
                              >
                                <X /> 驳回
                              </Button>
                              <Button
                                disabled={busy}
                                onClick={() => void review(post, "APPROVE")}
                                className="bg-[#1c5a4e] text-white hover:bg-[#16493f]"
                              >
                                <Check /> 通过审核
                              </Button>
                            </div>
                          </div>
                        </CardContent>
                      </Card>
                    ))}
                  </div>
                )}
                {pending && pending.total > pageSize && (
                  <div className="mt-6 flex justify-end gap-2">
                    <Button
                      variant="outline"
                      disabled={reviewPage === 1 || busy}
                      onClick={() => setReviewPage(reviewPage - 1)}
                    >
                      上一页
                    </Button>
                    <span className="px-2 py-1 text-sm text-[#71897d]">
                      第 {reviewPage} 页
                    </span>
                    <Button
                      variant="outline"
                      disabled={reviewPage * pageSize >= pending.total || busy}
                      onClick={() => setReviewPage(reviewPage + 1)}
                    >
                      下一页
                    </Button>
                  </div>
                )}
              </>
            )}
          </section>
          <aside id="account" className="scroll-mt-24 space-y-5">
            {session ? (
              <Card className="border border-[#e1ebe4] bg-white shadow-none">
                <CardContent className="px-6">
                  <div className="flex items-center gap-3">
                    <img
                      src={session.user.avatarUrl}
                      alt=""
                      className="size-12 rounded-full bg-[#e4eee8]"
                    />
                    <div>
                      <p className="font-semibold">{session.user.nickname}</p>
                      <p className="mt-1 text-xs text-[#8ba096]">
                        {session.user.role === "ADMIN"
                          ? "管理员"
                          : "校园创作者"}
                      </p>
                    </div>
                  </div>
                  <p className="mt-5 border-t border-[#edf2ee] pt-5 text-sm leading-6 text-[#71897d]">
                    {session.user.role === "ADMIN"
                      ? "查看并处理等待审核的原创投稿。"
                      : "有灵感的时候，随时回来写一写。"}
                  </p>
                  <Button
                    variant="outline"
                    className="mt-4 w-full border-[#d7e6dc] text-[#2b6655]"
                    onClick={() =>
                      navigate(
                        session.user.role === "ADMIN" ? "review" : "write"
                      )
                    }
                  >
                    {session.user.role === "ADMIN" ? (
                      <>
                        <ShieldCheck /> 前往审核
                      </>
                    ) : (
                      <>
                        <PenLine /> 开始投稿
                      </>
                    )}
                  </Button>
                </CardContent>
              </Card>
            ) : (
              <Card className="border border-[#e1ebe4] bg-white shadow-none">
                <CardHeader>
                  <CardTitle className="text-lg font-semibold">
                    {authMode === "login" ? "欢迎来到校园原创" : "创建你的账号"}
                  </CardTitle>
                  <p className="text-sm leading-6 text-[#8aa095]">
                    {authMode === "login"
                      ? "登录后，分享你的每一个灵感。"
                      : "加入这里，让原创被更多人看见。"}
                  </p>
                </CardHeader>
                <CardContent>
                  <form onSubmit={authenticate} className="space-y-4">
                    <div className="space-y-2">
                      <Label htmlFor="username">账号</Label>
                      <Input
                        id="username"
                        required
                        autoComplete="username"
                        value={username}
                        onChange={(e) => setUsername(e.target.value)}
                        placeholder="输入账号"
                        className="h-10"
                      />
                    </div>
                    {authMode === "register" && (
                      <div className="space-y-2">
                        <Label htmlFor="nickname">
                          昵称{" "}
                          <span className="text-xs font-normal text-[#9bac9f]">
                            选填
                          </span>
                        </Label>
                        <Input
                          id="nickname"
                          value={nickname}
                          onChange={(e) => setNickname(e.target.value)}
                          placeholder="不填则使用账号名"
                          className="h-10"
                        />
                      </div>
                    )}
                    <div className="space-y-2">
                      <Label htmlFor="password">密码</Label>
                      <Input
                        id="password"
                        type="password"
                        required
                        autoComplete={
                          authMode === "login"
                            ? "current-password"
                            : "new-password"
                        }
                        value={password}
                        onChange={(e) => setPassword(e.target.value)}
                        placeholder="输入密码"
                        className="h-10"
                      />
                    </div>
                    <Button
                      type="submit"
                      disabled={busy}
                      className="h-10 w-full bg-[#1c5a4e] text-white hover:bg-[#16493f]"
                    >
                      {busy
                        ? "请稍候…"
                        : authMode === "login"
                          ? "登录"
                          : "创建账号"}
                    </Button>
                  </form>
                  <div className="mt-5 border-t border-[#edf2ee] pt-4 text-center text-sm text-[#8a9e93]">
                    {authMode === "login" ? "还没有账号？" : "已有账号？"}{" "}
                    <button
                      type="button"
                      className="font-medium text-[#1c6b58] hover:underline"
                      onClick={() => {
                        setAuthMode(authMode === "login" ? "register" : "login")
                        setPassword("")
                        setNotice(null)
                      }}
                    >
                      {authMode === "login" ? "注册" : "去登录"}
                    </button>
                  </div>
                </CardContent>
              </Card>
            )}
            <div className="rounded-2xl bg-[#e9f1eb] px-6 py-5">
              <p className="text-sm font-semibold text-[#466b59]">
                一起来认真创作
              </p>
              <p className="mt-2 text-xs leading-6 text-[#7b9686]">
                尊重原创，友善表达。每一次分享，都让校园多一种声音。
              </p>
            </div>
            <p className="px-1 text-xs leading-6 text-[#a1b2a7]">
              校园原创 · 分享值得被看见的作品
            </p>
          </aside>
        </div>
      </main>
    </div>
  )
}
