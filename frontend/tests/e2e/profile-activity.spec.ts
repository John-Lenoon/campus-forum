import { expect, test } from "@playwright/test"

test("个人中心查看点赞、收藏和已删除的评论历史", async ({
  page,
  playwright,
}) => {
  const api = await playwright.request.newContext({
    baseURL: `${process.env.E2E_API_URL}/`,
  })
  const run = process.env.E2E_RUN_ID!
  const username = `activity_${run}`
  const password = "activity_test_123"
  const title = `互动列表 ${run}`
  try {
    expect(
      (
        await api.post("auth/register", { data: { username, password } })
      ).status()
    ).toBe(201)
    const token = (
      await (
        await api.post("auth/login", { data: { username, password } })
      ).json()
    ).token as string
    const admin = (
      await (
        await api.post("auth/login", {
          data: { username: "admin", password: "admin123" },
        })
      ).json()
    ).token as string
    const auth = { Authorization: `Bearer ${token}` }
    const postResponse = await api.post("posts", {
      headers: auth,
      data: { title, body: "个人互动列表", resourceIds: [] },
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
    expect(
      (await api.put(`posts/${post.id}/likes`, { headers: auth })).status()
    ).toBe(200)
    expect(
      (await api.put(`posts/${post.id}/favorites`, { headers: auth })).status()
    ).toBe(200)
    const rootResponse = await api.post(`posts/${post.id}/comments`, {
      headers: auth,
      data: { body: "历史根评论" },
    })
    expect(rootResponse.status()).toBe(201)
    const root = (await rootResponse.json()) as { id: string }
    expect(
      (
        await api.post(`comments/${root.id}/replies`, {
          headers: auth,
          data: { body: "历史回复" },
        })
      ).status()
    ).toBe(201)
    expect(
      (await api.delete(`comments/${root.id}`, { headers: auth })).status()
    ).toBe(200)

    await page.goto("/")
    await page.getByLabel("账号").fill(username)
    await page.getByLabel("密码").fill(password)
    await page.getByRole("button", { name: "登录", exact: true }).click()
    await page.getByRole("button", { name: "个人中心", exact: true }).click()
    await page.getByRole("button", { name: "我的点赞", exact: true }).click()
    await expect(page.getByText(title, { exact: true })).toBeVisible()
    await page.getByRole("button", { name: "我的收藏", exact: true }).click()
    await expect(page.getByText(title, { exact: true })).toBeVisible()
    await page.getByRole("button", { name: "我的评论", exact: true }).click()
    const history = page.getByRole("article", { name: "历史评论：历史根评论" })
    await expect(history).toContainText("已删除")
    await expect(page.getByText("历史回复", { exact: true })).toBeVisible()
    await history.getByRole("button", { name: "查看评论" }).click()
    await expect(page.getByText("该评论已删除", { exact: true })).toBeVisible()
  } finally {
    await api.dispose()
  }
})
