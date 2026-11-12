import { useEffect, useState } from "react"

export function ResourceImage({
  url,
  token,
  alt,
}: {
  url: string
  token?: string
  alt: string
}) {
  const [source, setSource] = useState<string | null>(null)
  const [error, setError] = useState(false)
  useEffect(() => {
    if (!token) return
    let active = true
    let objectUrl: string | null = null
    const target = new URL(url, window.location.origin)
    if (
      target.origin !== window.location.origin ||
      !target.pathname.startsWith("/api/v1/resources/")
    )
      return
    fetch(target.href, { headers: { Authorization: `Bearer ${token}` } })
      .then((response) => {
        if (!response.ok) throw new Error("Image unavailable")
        return response.blob()
      })
      .then((blob) => {
        if (!active) return
        objectUrl = URL.createObjectURL(blob)
        setSource(objectUrl)
      })
      .catch(() => {
        if (active) setError(true)
      })
    return () => {
      active = false
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [url, token])
  if (error)
    return (
      <p role="alert" className="text-sm text-red-700">
        图片加载失败。
      </p>
    )
  if (token && !source)
    return <p className="text-sm text-[#71897d]">正在加载图片…</p>
  return (
    <img
      src={token ? (source ?? undefined) : url}
      alt={alt}
      className="h-auto max-w-full rounded-xl"
      onError={() => setError(true)}
    />
  )
}
