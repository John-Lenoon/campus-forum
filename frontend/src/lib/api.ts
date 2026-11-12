export type User = {
  id: string
  nickname: string
  role: "USER" | "ADMIN"
  avatarUrl: string
}
export type Session = { token: string; expiresAt: number; user: User }
export type Resource = {
  id: string
  type: "IMAGE" | "VIDEO"
  state: "UPLOADING" | "PROCESSING" | "READY" | "FAILED"
  size: number
  url: string
  originalUrl: string
  errorCode: string | null
}
export type VideoResource = Resource & {
  type: "VIDEO"
  chunkSize: number
  totalParts: number
  uploadedParts: number[]
  duration: number | null
}
export type VideoFileInfo = { name: string; size: number; lastModified: number }
export type VideoDraft = {
  resource: VideoResource
  file: VideoFileInfo
  hashes: Record<string, string>
}

export function videoDraftKey(userId: string) {
  return `campus-video-draft:${userId}`
}
export function readVideoDraft(userId?: string): VideoDraft | null {
  if (!userId) return null
  try {
    const raw = sessionStorage.getItem(videoDraftKey(userId))
    if (!raw) return null
    const saved = JSON.parse(raw) as VideoDraft
    return saved.resource?.id && saved.file && saved.hashes ? saved : null
  } catch {
    return null
  }
}
export type Post = {
  id: string
  title: string
  body?: string
  status: "PENDING" | "PUBLISHED" | "REJECTED"
  revision: number
  submittedAt: number
  firstPublishedAt: number | null
  author: { id: string; nickname: string; avatarUrl: string }
  likeCount: number
  favoriteCount: number
  attachments: Resource[]
  liked?: boolean
  favorited?: boolean
  rejectionReason?: string | null
}
export type AuthorPost = Omit<Post, "attachments" | "liked" | "favorited"> & {
  body: string
  rejectionReason: string | null
}
export type Comment = {
  id: string
  postId: string
  body: string
  createdAt: number
  rootId: string | null
  replyToId: string | null
  author: { id: string; nickname: string; avatarUrl: string }
  replyTargetDeleted: boolean
  replyTargetMessage?: string
  replyCount?: number
}
export type CommentHistory = {
  id: string
  postId: string
  body: string
  createdAt: number
  rootId: string | null
  replyToId: string | null
  deleted: boolean
  message?: string
}
export type Page<T> = { items: T[]; total: number; page: number; size: number }

export class ApiError extends Error {
  code: string
  constructor(code: string) {
    super(code)
    this.name = "ApiError"
    this.code = code
  }
}

export async function api<T = unknown>(
  path: string,
  options: { method?: string; token?: string; body?: unknown } = {}
): Promise<T> {
  const headers = new Headers()
  if (options.token) headers.set("Authorization", `Bearer ${options.token}`)
  if (options.body !== undefined)
    headers.set("Content-Type", "application/json")
  const response = await fetch(`/api/v1${path}`, {
    method: options.method ?? "GET",
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  })
  const result: unknown = await response.json().catch(() => null)
  if (!response.ok) {
    const code =
      result &&
      typeof result === "object" &&
      "code" in result &&
      typeof result.code === "string"
        ? result.code
        : `HTTP_${response.status}`
    throw new ApiError(code)
  }
  return result as T
}

export async function uploadChunk(
  id: string,
  index: number,
  data: Blob,
  token: string
): Promise<VideoResource> {
  const response = await fetch(`/api/v1/resources/${id}/chunks/${index}`, {
    method: "PUT",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/octet-stream",
    },
    body: data,
  })
  const result: unknown = await response.json().catch(() => null)
  if (!response.ok) {
    const code =
      result &&
      typeof result === "object" &&
      "code" in result &&
      typeof result.code === "string"
        ? result.code
        : `HTTP_${response.status}`
    throw new ApiError(code)
  }
  return result as VideoResource
}

export async function uploadImage(
  file: File,
  purpose: "POST" | "AVATAR",
  token: string
): Promise<Resource> {
  const form = new FormData()
  form.set("file", file)
  const response = await fetch(`/api/v1/resources/images?purpose=${purpose}`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
    body: form,
  })
  const result: unknown = await response.json().catch(() => null)
  if (!response.ok) {
    const code =
      result &&
      typeof result === "object" &&
      "code" in result &&
      typeof result.code === "string"
        ? result.code
        : `HTTP_${response.status}`
    throw new ApiError(code)
  }
  return result as Resource
}
