import { expect, test } from "@playwright/test"

test("点赞收藏与评论回复在刷新后保持正确状态", async ({ page, playwright }) => {
  const run = process.env.E2E_RUN_ID!
  const api = await playwright.request.newContext({
    baseURL: `${process.env.E2E_API_URL}/`,
  })
  const password = "interaction_test_123"
  async function createUser(prefix: string) {
    const username = `${prefix}_${run}`
    expect(
      (
        await api.post("auth/register", { data: { username, password } })
      ).status()
    ).toBe(201)
    const login = await api.post("auth/login", { data: { username, password } })
    expect(login.status()).toBe(200)
    return { username, token: (await login.json()).token as string }
  }
  try {
    const author = await createUser("author")
    const reader = await createUser("reader")
    const admin = (
      await (
        await api.post("auth/login", {
          data: { username: "admin", password: "admin123" },
        })
      ).json()
    ).token as string
    const postResponse = await api.post("posts", {
      headers: { Authorization: `Bearer ${author.token}` },
      data: {
        title: `互动测试 ${run}`,
        body: "真实互动测试正文",
        resourceIds: [],
      },
    })
    expect(postResponse.status()).toBe(201)
    const post = (await postResponse.json()) as { id: string; revision: number }
    expect(
      (
        await api.post(`admin/posts/${post.id}/review`, {
          headers: { Authorization: `Bearer ${admin}` },
          data: { revision: post.revision, decision: "APPROVE" },
        })
      ).status()
    ).toBe(200)
    const readerHeaders = { Authorization: `Bearer ${reader.token}` }

    await page.goto(`/posts/${post.id}`)
    await page.getByLabel("账号").fill(reader.username)
    await page.getByLabel("密码").fill(password)
    await page.getByRole("button", { name: "登录", exact: true }).click()
    await expect(
      page.getByRole("button", { name: "点赞", exact: true })
    ).toBeVisible()
    await page.getByRole("button", { name: "点赞", exact: true }).click()
    await expect(
      page.getByRole("button", { name: "取消点赞", exact: true })
    ).toContainText("1")
    await page.getByRole("button", { name: "收藏", exact: true }).click()
    await expect(
      page.getByRole("button", { name: "取消收藏", exact: true })
    ).toContainText("1")
    let current = await (
      await api.get(`posts/${post.id}`, { headers: readerHeaders })
    ).json()
    expect(current).toMatchObject({
      liked: true,
      favorited: true,
      likeCount: 1,
      favoriteCount: 1,
    })

    await page.getByLabel("评论内容").fill("第一条评论")
    await page.getByRole("button", { name: "发表评论", exact: true }).click()
    const root = page.getByRole("article", { name: "评论：第一条评论" })
    await expect(root).toBeVisible()
    await root.getByRole("button", { name: "回复", exact: true }).click()
    await page.getByLabel("回复内容").fill("第一条回复")
    await page.getByRole("button", { name: "发送回复", exact: true }).click()
    const reply = page.getByRole("article", { name: "评论：第一条回复" })
    await expect(reply).toBeVisible()
    await reply.getByRole("button", { name: "回复", exact: true }).click()
    await page.getByLabel("回复内容").fill("回复一条回复")
    await page.getByRole("button", { name: "发送回复", exact: true }).click()
    await expect(
      page.getByRole("article", { name: "评论：回复一条回复" })
    ).toBeVisible()

    await page.reload()
    await expect(
      page.getByRole("button", { name: "取消点赞", exact: true })
    ).toContainText("1")
    await expect(
      page.getByRole("button", { name: "取消收藏", exact: true })
    ).toContainText("1")
    await expect(
      page.getByRole("article", { name: "评论：第一条评论" })
    ).toBeVisible()
    await page.getByRole("button", { name: "查看回复（2）" }).click()
    await expect(
      page.getByRole("article", { name: "评论：第一条回复" })
    ).toBeVisible()
    await expect(
      page.getByRole("article", { name: "评论：回复一条回复" })
    ).toBeVisible()
    await page.getByRole("button", { name: "取消点赞", exact: true }).click()
    await expect(
      page.getByRole("button", { name: "点赞", exact: true })
    ).toContainText("0")
    await page.getByRole("button", { name: "取消收藏", exact: true }).click()
    await expect(
      page.getByRole("button", { name: "收藏", exact: true })
    ).toContainText("0")
    current = await (
      await api.get(`posts/${post.id}`, { headers: readerHeaders })
    ).json()
    expect(current).toMatchObject({
      liked: false,
      favorited: false,
      likeCount: 0,
      favoriteCount: 0,
    })
    const comments = await (await api.get(`posts/${post.id}/comments`)).json()
    expect(comments.items).toHaveLength(1)
    expect(comments.items[0].replyCount).toBe(2)
  } finally {
    await api.dispose()
  }
})
