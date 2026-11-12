import { useState } from "react"
import { Button } from "@/components/ui/button"
import { VideoPlayer } from "@/components/VideoPlayer"
import { ResourceImage } from "@/components/ResourceImage"
import { api, type Post } from "@/lib/api"

export function ReviewMedia({ id, token }: { id: string; token: string }) {
  const [post, setPost] = useState<Post | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState("")
  async function load() {
    setLoading(true)
    setError("")
    try {
      setPost(await api<Post>(`/posts/${id}`, { token }))
    } catch {
      setError("无法读取作品附件，请重试。")
    } finally {
      setLoading(false)
    }
  }
  return (
    <div className="mt-4">
      {!post && (
        <Button
          type="button"
          variant="outline"
          disabled={loading}
          onClick={() => void load()}
        >
          {loading ? "加载中…" : "查看附件"}
        </Button>
      )}
      {error && (
        <p role="alert" className="mt-2 text-sm text-red-700">
          {error}
        </p>
      )}
      {post && post.attachments.length === 0 && (
        <p className="text-sm text-[#71897d]">这篇作品没有附件。</p>
      )}
      {post?.attachments
        .filter((item) => item.type === "VIDEO")
        .map((item) => (
          <VideoPlayer key={item.id} url={item.url} token={token} />
        ))}
      {post?.attachments
        .filter((item) => item.type === "IMAGE")
        .map((item, index) => (
          <ResourceImage
            key={item.id}
            url={item.url}
            token={token}
            alt={`作品图片 ${index + 1}`}
          />
        ))}
    </div>
  )
}
