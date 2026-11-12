import {
  expect,
  test,
  type APIRequestContext,
  type Page,
} from "@playwright/test"

type Post = { id: string; title: string; revision: number }
type PostPage = { items: Post[]; page: number; total: number }

const apiUrl = process.env.E2E_API_URL!
const runId = process.env.E2E_RUN_ID!
const password = "e2e_password_123"
let api: APIRequestContext
let authorToken: string
let adminToken: string
let firstPage: PostPage
let secondPage: PostPage

async function json<T = unknown>(
  response: Awaited<ReturnType<APIRequestContext["get"]>>,
  status: number
): Promise<T> {
  expect(response.status(), await response.text()).toBe(status)
  return (await response.json()) as T
}

async function createPost(title: string): Promise<Post> {
  return json<Post>(
    await api.post("posts", {
      headers: { Authorization: `Bearer ${authorToken}` },
      data: { title, body: `正文：${title}`, resourceIds: [] },
    }),
    201
  )
}

async function approve(post: Post) {
  await json(
    await api.post(`admin/posts/${post.id}/review`, {
      headers: { Authorization: `Bearer ${adminToken}` },
      data: { revision: post.revision, decision: "APPROVE" },
    }),
    200
  )
}

function deferred() {
  let resolve!: () => void
  const promise = new Promise<void>((done) => {
    resolve = done
  })
  return { promise, resolve }
}

function postButton(page: Page, title: string) {
  return page
    .getByRole("button")
    .filter({ has: page.getByRole("heading", { name: title, exact: true }) })
}

async function openFeed(page: Page) {
  await page.goto("/")
  await expect(postButton(page, firstPage.items[0].title)).toBeVisible()
}

test.beforeAll(async ({ playwright }, testInfo) => {
  const username = `e2e_${runId}_${testInfo.workerIndex}`
  api = await playwright.request.newContext({ baseURL: `${apiUrl}/` })
  await json(
    await api.post("auth/register", {
      data: { username, password, nickname: "回归测试用户" },
    }),
    201
  )
  authorToken = (
    await json<{ token: string }>(
      await api.post("auth/login", { data: { username, password } }),
      200
    )
  ).token
  adminToken = (
    await json<{ token: string }>(
      await api.post("auth/login", {
        data: { username: "admin", password: "admin123" },
      }),
      200
    )
  ).token
  for (let i = 0; i < 13; i++)
    await approve(
      await createPost(`回归作品 ${runId} ${String(i).padStart(2, "0")}`)
    )
  firstPage = await json<PostPage>(await api.get("posts?page=1&size=6"), 200)
  secondPage = await json<PostPage>(await api.get("posts?page=2&size=6"), 200)
  expect(firstPage.items).toHaveLength(6)
  expect(secondPage.items).toHaveLength(6)
})

test.afterAll(async () => {
  await api?.dispose()
})

test("详情页刷新后仍显示同一篇作品", async ({ page }) => {
  const post = firstPage.items[0]
  await openFeed(page)
  await postButton(page, post.title).click()
  await expect(
    page
      .locator("article")
      .getByRole("heading", { name: post.title, exact: true })
  ).toBeVisible()
  await expect(page).toHaveURL(new RegExp(`/posts/${post.id}$`))
  const reloadDetail = page.waitForResponse((response) => {
    const url = new URL(response.url())
    return (
      url.pathname === `/api/v1/posts/${post.id}` && response.status() === 200
    )
  })
  await page.reload()
  await reloadDetail
  await expect(
    page
      .locator("article")
      .getByRole("heading", { name: post.title, exact: true })
  ).toBeVisible()
})

test("列表第二页打开作品后，浏览器后退恢复页码和视口", async ({ page }) => {
  const post = secondPage.items[4]
  await openFeed(page)
  await page.getByRole("button", { name: "下一页" }).click()
  await expect(page.getByText("第 2 页", { exact: true })).toBeVisible()
  const card = postButton(page, post.title)
  await card.scrollIntoViewIfNeeded()
  await expect(card).toBeInViewport({ ratio: 0.5 })
  await expect
    .poll(() => page.evaluate(() => window.scrollY))
    .toBeGreaterThan(0)
  await card.click()
  await expect(
    page
      .locator("article")
      .getByRole("heading", { name: post.title, exact: true })
  ).toBeVisible()
  await page.goBack({ waitUntil: "domcontentloaded" })
  await expect.soft(page.getByText("第 2 页", { exact: true })).toBeVisible()
  await expect(postButton(page, post.title)).toBeInViewport({ ratio: 0.5 })
  await page.goForward({ waitUntil: "domcontentloaded" })
  await expect(
    page
      .locator("article")
      .getByRole("heading", { name: post.title, exact: true })
  ).toBeVisible()
})

test("访客可直接打开作品 URL", async ({ page }) => {
  const post = firstPage.items[0]
  await page.goto(`/posts/${post.id}`)
  await expect(
    page
      .locator("article")
      .getByRole("heading", { name: post.title, exact: true })
  ).toBeVisible()
  await expect(
    page.locator("article").getByText(`正文：${post.title}`, { exact: true })
  ).toBeVisible()
})

test("详情中的锚点后退仍保留作品", async ({ page }) => {
  const post = firstPage.items[0]
  await page.goto(`/posts/${post.id}`)
  const heading = page
    .locator("article")
    .getByRole("heading", { name: post.title, exact: true })
  await expect(heading).toBeVisible()
  await page.getByRole("link", { name: "登录 / 注册" }).click()
  await expect(page).toHaveURL(/#account$/)
  await page.goBack({ waitUntil: "domcontentloaded" })
  await expect(heading).toBeVisible()
})

test("首次进入且无列表数据时显示加载提示", async ({ page }) => {
  const captured = deferred()
  const release = deferred()
  await page.route(
    (url) =>
      url.pathname === "/api/v1/posts" && url.searchParams.get("page") === "1",
    async (route) => {
      const response = await route.fetch()
      expect(response.status()).toBe(200)
      captured.resolve()
      await release.promise
      await route.fulfill({ response })
    }
  )
  try {
    await page.goto("/")
    await captured.promise
    await expect(page.getByText("正在加载作品…", { exact: true })).toBeVisible()
    await expect(postButton(page, firstPage.items[0].title)).toHaveCount(0)
    release.resolve()
    await expect(postButton(page, firstPage.items[0].title)).toBeVisible()
  } finally {
    release.resolve()
  }
})

test("快速翻页时旧响应最后返回，仍显示最后选择的页码和内容", async ({
  page,
}) => {
  await openFeed(page)
  const captured = deferred()
  const release = deferred()
  const heldPageTwo = deferred()
  await page.route(
    (url) =>
      url.pathname === "/api/v1/posts" && url.searchParams.get("page") === "2",
    async (route) => {
      const response = await route.fetch()
      expect(response.status()).toBe(200)
      captured.resolve()
      await release.promise
      await route.fulfill({ response })
      heldPageTwo.resolve()
    }
  )
  try {
    await page.getByRole("button", { name: "下一页" }).click()
    await captured.promise
    await expect(page.getByText("第 2 页", { exact: true })).toBeVisible()
    const newestResponse = page.waitForResponse((response) => {
      const url = new URL(response.url())
      return (
        url.pathname === "/api/v1/posts" &&
        url.searchParams.get("page") === "1" &&
        response.status() === 200
      )
    })
    await page.getByRole("button", { name: "上一页" }).click()
    await newestResponse
    await expect(page.getByText("第 1 页", { exact: true })).toBeVisible()
    await expect(postButton(page, firstPage.items[0].title)).toBeVisible()
    const oldResponse = page.waitForResponse((response) => {
      const url = new URL(response.url())
      return (
        url.pathname === "/api/v1/posts" &&
        url.searchParams.get("page") === "2" &&
        response.status() === 200
      )
    })
    release.resolve()
    await heldPageTwo.promise
    await (await oldResponse).finished()
    await page.evaluate(
      () =>
        new Promise<void>((resolve) =>
          requestAnimationFrame(() => requestAnimationFrame(() => resolve()))
        )
    )
    await expect(page.getByText("第 1 页", { exact: true })).toBeVisible()
    await expect(
      page.locator("section").getByRole("heading", { level: 3 })
    ).toHaveText(firstPage.items.map((post) => post.title))
    await expect(postButton(page, secondPage.items[0].title)).toHaveCount(0)
  } finally {
    release.resolve()
  }
})

test("同页刷新等待时保留原列表，响应后显示新数据", async ({
  page,
}, testInfo) => {
  const pending = await createPost(`审核刷新 ${runId}`)
  await openFeed(page)
  await page.getByLabel("账号").fill("admin")
  await page.getByLabel("密码").fill("admin123")
  await page.getByRole("button", { name: "登录", exact: true }).click()
  await expect(page.getByText(pending.title, { exact: true })).toBeVisible()
  const captured = deferred()
  const release = deferred()
  const delivered = deferred()
  await page.route(
    (url) =>
      url.pathname === "/api/v1/posts" && url.searchParams.get("page") === "1",
    async (route) => {
      const response = await route.fetch()
      expect(response.status()).toBe(200)
      captured.resolve()
      await release.promise
      await route.fulfill({ response })
      delivered.resolve()
    }
  )
  try {
    await page.locator('[data-slot="card"]').filter({ hasText: pending.title }).getByRole("button", { name: "通过审核" }).click()
    await captured.promise
    await page.getByRole("button", { name: "发现作品" }).click()
    await testInfo.attach("refresh-pending-page.txt", {
      body: await page.locator("body").innerText(),
      contentType: "text/plain",
    })
    await testInfo.attach("refresh-pending.png", {
      body: await page.screenshot(),
      contentType: "image/png",
    })
    await expect
      .soft(
        postButton(page, firstPage.items[0].title),
        "等待刷新时原作品仍可见"
      )
      .toBeVisible()
    release.resolve()
    await delivered.promise
    await expect(postButton(page, pending.title)).toBeVisible()
  } finally {
    release.resolve()
  }
})

test("普通用户注册、投稿，经管理员审核后访客可查看", async ({ page }) => {
  const title = `真实业务链路 ${runId}`
  await openFeed(page)
  await page.getByRole("button", { name: "注册", exact: true }).click()
  await page.getByLabel("账号").fill(`flow_${runId}`)
  await page.getByLabel("昵称").fill("流程测试用户")
  await page.getByLabel("密码").fill(password)
  await page.getByRole("button", { name: "创建账号" }).click()
  await expect(page.getByText("账号创建成功，请登录。")).toBeVisible()
  await page.getByLabel("密码").fill(password)
  await page.getByRole("button", { name: "登录", exact: true }).click()
  await page.getByRole("button", { name: "投稿", exact: true }).click()
  await page.getByLabel("标题").fill(title)
  await page.getByLabel("正文").fill("测试真实后端业务链路。")
  await page.getByRole("button", { name: "提交审核" }).click()
  await expect(
    page.getByText("投稿成功，审核通过后会出现在作品列表。")
  ).toBeVisible()
  await expect(postButton(page, title)).toHaveCount(0)
  await page.getByRole("button", { name: "切换账号" }).click()
  await page.getByLabel("账号").fill("admin")
  await page.getByLabel("密码").fill("admin123")
  await page.getByRole("button", { name: "登录", exact: true }).click()
  await expect(page.getByText(title, { exact: true })).toBeVisible()
  await page.locator('[data-slot="card"]').filter({ hasText: title }).getByRole("button", { name: "通过审核" }).click()
  await expect(page.getByText("已通过审核，作品现已公开。")).toBeVisible()
  await page.getByRole("button", { name: "切换账号" }).click()
  await postButton(page, title).click()
  await expect(
    page.locator("article").getByText("测试真实后端业务链路。", { exact: true })
  ).toBeVisible()
})
