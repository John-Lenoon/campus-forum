import { expect, test, type APIRequestContext } from "@playwright/test"
import { spawnSync } from "node:child_process"
import { existsSync, readdirSync, readFileSync } from "node:fs"
import { resolve, join } from "node:path"

const apiUrl = process.env.E2E_API_URL!
const runId = process.env.E2E_RUN_ID!
let api: APIRequestContext
let postId: string
let fixtureFile: string
let username: string
const password = "video_test_123"

function ffmpegPath() {
  if (process.env.FFMPEG_PATH) return process.env.FFMPEG_PATH
  const tools = resolve(import.meta.dirname, "../../../.tools")
  if (existsSync(tools)) {
    for (const name of readdirSync(tools)) {
      const candidate = join(tools, name, "bin", "ffmpeg.exe")
      if (existsSync(candidate)) return candidate
    }
  }
  return "ffmpeg"
}

test.beforeAll(async ({ playwright }, testInfo) => {
  test.setTimeout(120_000)
  username = `video_${runId}_${testInfo.workerIndex}`
  api = await playwright.request.newContext({ baseURL: `${apiUrl}/` })
  const file = resolve(
    import.meta.dirname,
    `../../../target/playwright-e2e/${runId}/playback-fixture.mp4`
  )
  fixtureFile = file
  const generated = spawnSync(
    ffmpegPath(),
    [
      "-hide_banner",
      "-loglevel",
      "error",
      "-f",
      "lavfi",
      "-i",
      "testsrc=size=320x180:rate=24",
      "-f",
      "lavfi",
      "-i",
      "sine=frequency=440:sample_rate=44100",
      "-t",
      "18",
      "-c:v",
      "libx264",
      "-pix_fmt",
      "yuv420p",
      "-c:a",
      "aac",
      "-movflags",
      "+faststart",
      "-y",
      file,
    ],
    { encoding: "utf8", timeout: 60_000 }
  )
  expect(generated.status, generated.stderr || String(generated.error)).toBe(0)
  const bytes = readFileSync(file)
  const register = await api.post("auth/register", {
    data: { username, password },
  })
  expect(register.status(), await register.text()).toBe(201)
  const login = await api.post("auth/login", { data: { username, password } })
  expect(login.status()).toBe(200)
  const token: string = (await login.json()).token
  const headers = { Authorization: `Bearer ${token}` }
  const init = await api.post("resources/videos", {
    headers,
    data: { size: bytes.length },
  })
  expect(init.status(), await init.text()).toBe(201)
  const resource: { id: string; chunkSize: number; totalParts: number } =
    await init.json()
  for (let index = 0; index < resource.totalParts; index++) {
    const chunk = await api.put(`resources/${resource.id}/chunks/${index}`, {
      headers: { ...headers, "Content-Type": "application/octet-stream" },
      data: bytes.subarray(
        index * resource.chunkSize,
        (index + 1) * resource.chunkSize
      ),
    })
    expect(chunk.status(), await chunk.text()).toBe(200)
  }
  const complete = await api.post(`resources/${resource.id}/complete`, {
    headers,
  })
  expect(complete.status(), await complete.text()).toBe(200)
  let resourceState = ""
  await expect
    .poll(
      async () => {
        const response = await api.get(`resources/${resource.id}`, { headers })
        expect(response.status()).toBe(200)
        const result = await response.json()
        resourceState = result.state
        if (resourceState === "FAILED")
          throw new Error(`Video processing failed: ${result.errorCode}`)
        return resourceState
      },
      { timeout: 90_000, intervals: [250, 500, 1000] }
    )
    .toBe("READY")
  const postResponse = await api.post("posts", {
    headers,
    data: {
      title: `视频播放 ${runId}`,
      body: "真实多分段视频播放回归",
      resourceIds: [resource.id],
    },
  })
  expect(postResponse.status(), await postResponse.text()).toBe(201)
  const post = await postResponse.json()
  postId = post.id
  const admin = await api.post("auth/login", {
    data: { username: "admin", password: "admin123" },
  })
  const adminToken = (await admin.json()).token
  const approved = await api.post(`admin/posts/${post.id}/review`, {
    headers: { Authorization: `Bearer ${adminToken}` },
    data: { revision: post.revision, decision: "APPROVE" },
  })
  expect(approved.status(), await approved.text()).toBe(200)
})

test("登录作者的上传预览跨多个分段并可暂停继续", async ({ page }) => {
  test.setTimeout(120_000)
  const segments = new Set<string>()
  page.on("response", (response) => {
    const path = new URL(response.url()).pathname
    if (/\/part\d+\.ts$/.test(path) && response.status() === 200)
      segments.add(path)
  })
  await page.goto("/")
  await page.getByLabel("账号").fill(username)
  await page.getByLabel("密码").fill(password)
  await page.getByRole("button", { name: "登录", exact: true }).click()
  await page.getByRole("button", { name: "投稿", exact: true }).click()
  await page.getByLabel("选择视频（选填）").setInputFiles(fixtureFile)
  await page.getByRole("button", { name: "开始上传" }).click()
  await expect(page.getByText("视频已就绪，可以预览并提交审核。")).toBeVisible({
    timeout: 90_000,
  })
  const player = page.getByLabel("视频播放")
  await player.evaluate((video) => (video as HTMLVideoElement).play())
  await expect
    .poll(
      () => player.evaluate((video) => (video as HTMLVideoElement).currentTime),
      { timeout: 15_000 }
    )
    .toBeGreaterThan(3)
  await player.evaluate((video) => {
    ;(video as HTMLVideoElement).playbackRate = 4
  })
  await expect
    .poll(
      () => player.evaluate((video) => (video as HTMLVideoElement).currentTime),
      { timeout: 15_000 }
    )
    .toBeGreaterThan(13)
  await player.evaluate((video) => (video as HTMLVideoElement).pause())
  await expect
    .poll(() => player.evaluate((video) => (video as HTMLVideoElement).paused))
    .toBe(true)
  const pausedAt = await player.evaluate(
    (video) => (video as HTMLVideoElement).currentTime
  )
  await player.evaluate((video) => (video as HTMLVideoElement).play())
  await expect
    .poll(
      () => player.evaluate((video) => (video as HTMLVideoElement).currentTime),
      { timeout: 5000 }
    )
    .toBeGreaterThan(pausedAt + 0.5)
  expect(segments.size).toBeGreaterThanOrEqual(3)
})

test.afterAll(async () => {
  await api?.dispose()
})

test("真实多分段视频持续播放并支持暂停和继续", async ({ page }, testInfo) => {
  test.setTimeout(60_000)
  const segments: { path: string; status: number }[] = []
  page.on("response", (response) => {
    const path = new URL(response.url()).pathname
    if (/\/part\d+\.ts$/.test(path))
      segments.push({ path, status: response.status() })
  })
  const manifest = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname.endsWith("/index.m3u8") &&
      response.status() === 200
  )
  await page.goto(`/posts/${postId}`)
  const player = page.getByLabel("视频播放")
  await expect(player).toBeVisible()
  const playlist = await (await manifest).text()
  expect((playlist.match(/#EXTINF:/g) || []).length).toBeGreaterThanOrEqual(3)
  await player.evaluate((video) => {
    const element = video as HTMLVideoElement & { __events?: unknown[] }
    element.__events = []
    for (const name of [
      "play",
      "playing",
      "pause",
      "waiting",
      "stalled",
      "timeupdate",
      "ended",
      "error",
    ]) {
      element.addEventListener(name, () =>
        element.__events?.push({
          event: name,
          time: element.currentTime,
          paused: element.paused,
          readyState: element.readyState,
          error: element.error?.code ?? null,
        })
      )
    }
  })
  try {
    await player.evaluate((video) => (video as HTMLVideoElement).play())
    await expect
      .poll(
        () =>
          player.evaluate((video) => (video as HTMLVideoElement).currentTime),
        { timeout: 15_000 }
      )
      .toBeGreaterThan(3)
    await player.evaluate((video) => {
      ;(video as HTMLVideoElement).playbackRate = 4
    })
    await expect
      .poll(
        () =>
          player.evaluate((video) => (video as HTMLVideoElement).currentTime),
        { timeout: 15_000 }
      )
      .toBeGreaterThan(13)
    await player.evaluate((video) => (video as HTMLVideoElement).pause())
    await expect
      .poll(() =>
        player.evaluate((video) => (video as HTMLVideoElement).paused)
      )
      .toBe(true)
    const pausedAt = await player.evaluate(
      (video) => (video as HTMLVideoElement).currentTime
    )
    await player.evaluate((video) => (video as HTMLVideoElement).play())
    await expect
      .poll(
        () =>
          player.evaluate((video) => (video as HTMLVideoElement).currentTime),
        { timeout: 5000 }
      )
      .toBeGreaterThan(pausedAt + 0.5)
    expect(
      new Set(
        segments
          .filter((segment) => segment.status === 200)
          .map((segment) => segment.path)
      ).size
    ).toBeGreaterThanOrEqual(3)
    expect(segments.filter((segment) => segment.status !== 200)).toEqual([])
  } finally {
    const events = await player
      .evaluate(
        (video) =>
          (video as HTMLVideoElement & { __events?: unknown[] }).__events
      )
      .catch(() => [])
    await testInfo.attach("playback-events-and-segments.json", {
      body: JSON.stringify({ events, segments, playlist }, null, 2),
      contentType: "application/json",
    })
  }
})
