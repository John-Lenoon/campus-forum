import { useEffect, useRef, useState } from "react"
import Hls from "hls.js"

export function VideoPlayer({ url, token }: { url: string; token?: string }) {
  const ref = useRef<HTMLVideoElement>(null)
  const [error, setError] = useState("")

  useEffect(() => {
    const video = ref.current
    if (!video) return
    const source = new URL(url, window.location.origin)
    if (
      source.origin !== window.location.origin ||
      !source.pathname.startsWith("/api/v1/resources/")
    )
      return
    if (Hls.isSupported()) {
      const hls = new Hls({
        xhrSetup(xhr, requestUrl) {
          const target = new URL(requestUrl, window.location.origin)
          if (
            token &&
            target.origin === window.location.origin &&
            target.pathname.startsWith("/api/v1/resources/")
          ) {
            xhr.open("GET", requestUrl, true)
            xhr.setRequestHeader("Authorization", `Bearer ${token}`)
          }
        },
      })
      hls.on(Hls.Events.ERROR, (_event, data) => {
        if (data.fatal) setError("视频加载失败，请检查网络和访问权限。")
      })
      hls.loadSource(source.href)
      hls.attachMedia(video)
      return () => {
        hls.destroy()
      }
    }
    if (!token && video.canPlayType("application/vnd.apple.mpegurl"))
      video.src = source.href
    else
      video.addEventListener(
        "error",
        () => setError("当前浏览器无法播放此视频。"),
        { once: true }
      )
    return () => {
      video.removeAttribute("src")
      video.load()
    }
  }, [url, token])

  return (
    <div className="mt-4 space-y-2">
      <video
        ref={ref}
        controls
        playsInline
        preload="metadata"
        className="w-full rounded-xl bg-black"
        aria-label="视频播放"
      />
      {error && (
        <p role="alert" className="text-sm text-red-700">
          {error}
        </p>
      )}
    </div>
  )
}
