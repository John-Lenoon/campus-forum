import { useEffect, useRef, useState } from "react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import {
  api,
  ApiError,
  uploadChunk,
  type VideoResource,
  type VideoFileInfo,
} from "@/lib/api"
import { VideoPlayer } from "@/components/VideoPlayer"

type Props = {
  token: string
  video: VideoResource | null
  fileInfo: VideoFileInfo | null
  hashes: Record<string, string>
  selected: boolean
  onChange: (
    video: VideoResource | null,
    selected: boolean,
    file: VideoFileInfo | null,
    hashes: Record<string, string>
  ) => void
}

function message(error: unknown) {
  if (error instanceof ApiError) {
    if (error.code === "RESOURCE_EXPIRED")
      return "上传任务已过期，请重新选择视频上传。"
    return `上传未完成（${error.code}），请重试或重新选择视频。`
  }
  return "连接中断，请检查网络并重试。"
}

export function VideoUpload({
  token,
  video,
  fileInfo,
  hashes,
  selected,
  onChange,
}: Props) {
  const [file, setFile] = useState<File | null>(null)
  const [working, setWorking] = useState(false)
  const [error, setError] = useState("")
  const [refreshKey, setRefreshKey] = useState(0)
  const alive = useRef(true)
  const fileInput = useRef<HTMLInputElement>(null)
  useEffect(() => {
    alive.current = true
    return () => {
      alive.current = false
    }
  }, [])

  useEffect(() => {
    if (video?.state !== "PROCESSING") return
    let active = true
    const timer = window.setTimeout(() => {
      api<VideoResource>(`/resources/${video.id}`, { token }).then(
        (result) => {
          if (active) {
            onChange(result, true, fileInfo, hashes)
            setError("")
          }
        },
        (err) => {
          if (active) setError(message(err))
        }
      )
    }, 2000)
    return () => {
      active = false
      window.clearTimeout(timer)
    }
  }, [video, token, onChange, refreshKey, fileInfo, hashes])

  function choose(next: File | null) {
    setError("")
    if (!next) return
    if (
      !next.name.toLowerCase().endsWith(".mp4") ||
      next.size < 1 ||
      next.size > 1073741824
    ) {
      setError("请选择不超过 1 GiB 的非空 MP4 视频。")
      setFile(null)
      if (!video) onChange(null, true, null, {})
      return
    }
    if (
      video &&
      fileInfo &&
      (next.name !== fileInfo.name ||
        next.size !== fileInfo.size ||
        next.lastModified !== fileInfo.lastModified)
    ) {
      setError("请选择上次上传的原视频；如需更换，请先移除当前任务。")
      setFile(null)
      return
    }
    setFile(next)
    onChange(
      video,
      true,
      { name: next.name, size: next.size, lastModified: next.lastModified },
      hashes
    )
  }

  async function upload() {
    if (
      !file ||
      !file.name.toLowerCase().endsWith(".mp4") ||
      file.size < 1 ||
      file.size > 1073741824
    )
      return
    setWorking(true)
    setError("")
    try {
      let current = video
        ? await api<VideoResource>(`/resources/${video.id}`, { token })
        : await api<VideoResource>("/resources/videos", {
            method: "POST",
            token,
            body: { size: file.size },
          })
      if (!alive.current) return
      let verifiedHashes = { ...hashes }
      onChange(current, true, fileInfo, verifiedHashes)
      if (current.size !== file.size) throw new Error("file size changed")
      if (current.state === "UPLOADING") {
        const priorHashes = { ...verifiedHashes }
        for (let index = 0; index < current.totalParts; index++) {
          if (!alive.current) return
          const chunk = file.slice(
            index * current.chunkSize,
            Math.min((index + 1) * current.chunkSize, file.size)
          )
          const digest = Array.from(
            new Uint8Array(
              await crypto.subtle.digest("SHA-256", await chunk.arrayBuffer())
            ),
            (byte) => byte.toString(16).padStart(2, "0")
          ).join("")
          if (!alive.current) return
          const oldHash = verifiedHashes[String(index)]
          if (oldHash && oldHash !== digest) throw new Error("FILE_CHANGED")
          verifiedHashes = { ...verifiedHashes, [String(index)]: digest }
          onChange(current, true, fileInfo, verifiedHashes)
        }
        for (let index = 0; index < current.totalParts; index++) {
          if (!alive.current) return
          if (
            current.uploadedParts.includes(index) &&
            priorHashes[String(index)] === verifiedHashes[String(index)]
          )
            continue
          const chunk = file.slice(
            index * current.chunkSize,
            Math.min((index + 1) * current.chunkSize, file.size)
          )
          current = await uploadChunk(current.id, index, chunk, token)
          if (!alive.current) return
          onChange(current, true, fileInfo, verifiedHashes)
        }
        current = await api<VideoResource>(
          `/resources/${current.id}/complete`,
          { method: "POST", token }
        )
        if (alive.current) onChange(current, true, fileInfo, verifiedHashes)
      }
    } catch (err) {
      if (alive.current)
        setError(
          err instanceof Error && err.message === "FILE_CHANGED"
            ? "所选视频与已上传内容不一致，请选择原文件，或移除任务后重新上传。"
            : message(err)
        )
    } finally {
      if (alive.current) setWorking(false)
    }
  }

  return (
    <div className="space-y-3 border-t border-[#edf2ee] pt-5">
      <div>
        <Label htmlFor="post-video">选择视频（选填）</Label>
        <p className="mt-2 text-xs leading-6 text-[#71897d]">
          MP4，最多 1 GiB、30 分钟；每篇作品最多一个视频。
        </p>
      </div>
      <Input
        ref={fileInput}
        id="post-video"
        type="file"
        accept="video/mp4,.mp4"
        disabled={
          working || video?.state === "PROCESSING" || video?.state === "READY"
        }
        onChange={(event) => choose(event.target.files?.[0] ?? null)}
      />
      {video?.state === "UPLOADING" && !file && (
        <p className="text-sm text-[#526f61]">
          请重新选择原视频 {fileInfo?.name}，以恢复上传。
        </p>
      )}
      {selected && file && !video && (
        <p className="text-sm text-[#526f61]">
          {file.name} · {(file.size / 1048576).toFixed(1)} MiB
        </p>
      )}
      {video?.state === "UPLOADING" && (
        <p role="status" className="text-sm text-[#526f61]">
          上传进度：{video.uploadedParts.length} / {video.totalParts} 片
        </p>
      )}
      {video?.state === "PROCESSING" && (
        <p role="status" className="text-sm text-[#526f61]">
          视频处理中，请稍候。处理完成后才能提交审核。
        </p>
      )}
      {video?.state === "FAILED" && (
        <p role="alert" className="text-sm text-red-700">
          视频处理失败（{video.errorCode ?? "未知原因"}），请移除后重新上传。
        </p>
      )}
      {video?.state === "READY" && (
        <>
          <p role="status" className="text-sm text-[#28634f]">
            视频已就绪，可以预览并提交审核。
          </p>
          <VideoPlayer url={video.url} token={token} />
        </>
      )}
      {error && (
        <p role="alert" className="text-sm text-red-700">
          {error}
        </p>
      )}
      <div className="flex flex-wrap gap-2">
        {file && (!video || video.state === "UPLOADING") && (
          <Button
            type="button"
            variant="outline"
            disabled={working}
            onClick={() => void upload()}
          >
            {working ? "上传中…" : video ? "继续上传" : "开始上传"}
          </Button>
        )}
        {video?.state === "PROCESSING" && error && (
          <Button
            type="button"
            variant="outline"
            onClick={() => setRefreshKey((v) => v + 1)}
          >
            重试查询
          </Button>
        )}
        {selected && (
          <Button
            type="button"
            variant="ghost"
            disabled={working}
            onClick={() => {
              setFile(null)
              setError("")
              if (fileInput.current) fileInput.current.value = ""
              onChange(null, false, null, {})
            }}
          >
            移除视频
          </Button>
        )}
      </div>
    </div>
  )
}
