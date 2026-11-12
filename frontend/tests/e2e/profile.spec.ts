import { expect, test } from "@playwright/test"

test("个人中心更新资料并管理本人投稿", async ({ page, playwright }) => {
  const run = process.env.E2E_RUN_ID!
  const api = await playwright.request.newContext({
    baseURL: `${process.env.E2E_API_URL}/`,
  })
  const username = `profile_${run}`
  const password = "profile_test_123"
  const title = `已发布作品 ${run}`
  const rejectedTitle = `被驳回作品 ${run}`
  const headers = (token: string) => ({ Authorization: `Bearer ${token}` })
  try {
    expect(
      (
        await api.post("auth/register", {
          data: { username, password, nickname: "初始昵称" },
        })
      ).status()
    ).toBe(201)
    const owner = (
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
    async function create(postTitle: string) {
      const response = await api.post("posts", {
        headers: headers(owner),
        data: { title: postTitle, body: "初始正文", resourceIds: [] },
      })
      expect(response.status()).toBe(201)
      return (await response.json()) as { id: string; revision: number }
    }
    const published = await create(title)
    const rejected = await create(rejectedTitle)
    expect(
      (
        await api.post(`admin/posts/${published.id}/review`, {
          headers: headers(admin),
          data: { revision: published.revision, decision: "APPROVE" },
        })
      ).status()
    ).toBe(200)
    expect(
      (
        await api.post(`admin/posts/${rejected.id}/review`, {
          headers: headers(admin),
          data: {
            revision: rejected.revision,
            decision: "REJECT",
            reason: "请补充内容",
          },
        })
      ).status()
    ).toBe(200)

    await page.goto("/")
    await page.getByLabel("账号").fill(username)
    await page.getByLabel("密码").fill(password)
    await page.getByRole("button", { name: "登录", exact: true }).click()
    await page.getByRole("button", { name: "个人中心", exact: true }).click()
    await expect(page.getByText(title, { exact: true })).toBeVisible()
    await expect(page.getByText(rejectedTitle, { exact: true })).toBeVisible()
    await expect(page.getByText("请补充内容", { exact: false })).toBeVisible()

    await page.getByLabel("昵称", { exact: true }).fill("更新后的昵称")
    await page
      .getByLabel("选择头像")
      .setInputFiles({
        name: "avatar.png",
        mimeType: "image/png",
        buffer: Buffer.from(
          "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAIAAACQkWg2AAAACXBIWXMAAAABAAAAAQBPJcTWAAAAIklEQVR4nGP8t4SBJMBCmnKGUQ1EARbilCHAqAZiAMmhBABgJQHg26tj6AAAAABJRU5ErkJggg==",
          "base64"
        ),
      })
    await page.getByRole("button", { name: "保存资料" }).click()
    await expect(page.getByText("资料已更新。")).toBeVisible()
    const me = await (await api.get("me", { headers: headers(owner) })).json()
    expect(me.nickname).toBe("更新后的昵称")
    expect(me.avatarUrl).toMatch(/^\/api\/v1\/resources\//)
    expect((await api.get(me.avatarUrl.replace("/api/v1/", ""))).status()).toBe(
      200
    )

    const publishedCard = page
      .locator('[data-slot="card"]')
      .filter({ hasText: title })
    await publishedCard.getByRole("button", { name: "编辑" }).click()
    await page.getByLabel("作品标题").fill(`修改后 ${run}`)
    await page.getByLabel("作品正文").fill("重新提交的正文")
    await page.getByRole("button", { name: "提交修改" }).click()
    await expect(page.getByText("修改已提交审核。")).toBeVisible()
    const revised = await (
      await api.get(`posts/${published.id}`, { headers: headers(owner) })
    ).json()
    expect(revised.status).toBe("PENDING")
    expect(revised.revision).toBe(2)
    expect((await api.get(`posts/${published.id}`)).status()).toBe(404)

    page.once("dialog", (dialog) => void dialog.accept())
    await page
      .locator('[data-slot="card"]')
      .filter({ hasText: rejectedTitle })
      .getByRole("button", { name: "删除" })
      .click()
    await expect(page.getByText(rejectedTitle, { exact: true })).toHaveCount(0)
    expect(
      (
        await api.get(`posts/${rejected.id}`, { headers: headers(owner) })
      ).status()
    ).toBe(404)
    await page.reload()
    await page.getByRole("button", { name: "个人中心", exact: true }).click()
    await expect(page.getByLabel("昵称", { exact: true })).toHaveValue(
      "更新后的昵称"
    )
    await expect(page.getByText(`修改后 ${run}`, { exact: true })).toBeVisible()
  } finally {
    await api.dispose()
  }
})
