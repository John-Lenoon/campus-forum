import { useEffect, useRef, useState } from "react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { uploadImage, type Resource } from "@/lib/api"

type Props = {
  token: string
  images: Resource[]
  existingCount?: number
  disabled?: boolean
  inputId: string
  onChange: (images: Resource[]) => void
  onPendingChange: (pending: boolean) => void
}

export function ImageUpload({
  token,
  images,
  existingCount = 0,
  disabled = false,
  inputId,
  onChange,
  onPendingChange,
}: Props) {
  const [files, setFiles] = useState<File[]>([])
  const [uploading, setUploading] = useState(false)
  const [error, setError] = useState("")
  const alive = useRef(true)
  const input = useRef<HTMLInputElement>(null)
  useEffect(() => {
    alive.current = true
    return () => {
      alive.current = false
      onPendingChange(false)
    }
  }, [onPendingChange])

  function choose(next: File[]) {
    setError("")
    if (!next.length) return
    if (existingCount + images.length + next.length > 9) {
      setError("每篇作品最多添加 9 张图片。")
      setFiles([])
      onPendingChange(false)
      if (input.current) input.current.value = ""
      return
    }
    if (
      next.some(
        (file) =>
          !["image/jpeg", "image/png", "image/webp"].includes(file.type) ||
          file.size < 1 ||
          file.size > 10 * 1024 * 1024
      )
    ) {
      setError("请选择不超过 10 MiB 的 JPG、PNG 或 WebP 图片。")
      setFiles([])
      onPendingChange(false)
      if (input.current) input.current.value = ""
      return
    }
    setFiles(next)
    onPendingChange(true)
  }

  async function upload() {
    if (!files.length || disabled) return
    setUploading(true)
    setError("")
    let current = [...images]
    let completed = 0
    try {
      for (const file of files) {
        const resource = await uploadImage(file, "POST", token)
        if (!alive.current) return
        current = [...current, resource]
        completed++
        onChange(current)
      }
      setFiles([])
      onPendingChange(false)
      if (input.current) input.current.value = ""
    } catch {
      if (alive.current) {
        setFiles(files.slice(completed))
        setError("图片上传未完成，已就绪的图片会保留；请检查剩余图片后重试。")
      }
    } finally {
      if (alive.current) setUploading(false)
    }
  }

  return (
    <div className="space-y-3 border-t border-[#edf2ee] pt-5">
      <div>
        <Label htmlFor={inputId}>选择图片（选填）</Label>
        <p className="mt-2 text-xs text-[#71897d]">
          JPG、PNG 或 WebP，每张不超过 10 MiB，最多 9 张。图片和视频不能混用。
        </p>
      </div>
      <Input
        ref={input}
        id={inputId}
        type="file"
        accept="image/jpeg,image/png,image/webp"
        multiple
        disabled={disabled || uploading}
        onChange={(event) => choose(Array.from(event.target.files ?? []))}
      />
      {disabled && (
        <p className="text-xs text-[#71897d]">请先移除视频，再添加图片。</p>
      )}
      {files.length > 0 && (
        <p className="text-sm text-[#526f61]">待上传 {files.length} 张图片。</p>
      )}
      {images.length > 0 && (
        <p role="status" className="text-sm text-[#28634f]">
          已就绪图片：{existingCount + images.length} / 9
        </p>
      )}
      {error && (
        <p role="alert" className="text-sm text-red-700">
          {error}
        </p>
      )}
      <div className="flex flex-wrap gap-2">
        {files.length > 0 && (
          <Button
            type="button"
            variant="outline"
            disabled={disabled || uploading}
            onClick={() => void upload()}
          >
            {uploading ? "上传中…" : "上传图片"}
          </Button>
        )}
        {files.length > 0 && (
          <Button
            type="button"
            variant="ghost"
            disabled={uploading}
            onClick={() => {
              setFiles([])
              setError("")
              onPendingChange(false)
              if (input.current) input.current.value = ""
            }}
          >
            取消选择
          </Button>
        )}
      </div>
      {images.map((item, index) => (
        <div
          key={item.id}
          className="flex items-center justify-between gap-3 rounded-lg border p-3 text-sm"
        >
          <span>图片 {existingCount + index + 1} 已就绪</span>
          <Button
            type="button"
            variant="ghost"
            disabled={uploading}
            onClick={() =>
              onChange(images.filter((image) => image.id !== item.id))
            }
          >
            移除
          </Button>
        </div>
      ))}
    </div>
  )
}
