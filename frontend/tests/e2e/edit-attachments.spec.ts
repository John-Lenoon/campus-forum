import { expect, test } from "@playwright/test"

const png = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAIAAACQkWg2AAAACXBIWXMAAAABAAAAAQBPJcTWAAAAIklEQVR4nGP8t4SBJMBCmnKGUQ1EARbilCHAqAZiAMmhBABgJQHg26tj6AAAAABJRU5ErkJggg==",
  "base64"
)

test("作者替换作品图片并重新提交审核", async ({ page, playwright }) => {
  const username = `editimg_${process.env.E2E_RUN_ID}`
  const password = "edit_image_123"
  const api = await playwright.request.newContext({
    baseURL: `${process.env.E2E_API_URL}/`,
  })
  try {
    expect(
      (
        await api.post("auth/register", { data: { username, password } })
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
    const headers = (token: string) => ({ Authorization: `Bearer ${token}` })
    const uploaded = await api.post("resources/images?purpose=POST", {
      headers: headers(owner),
      multipart: {
        file: { name: "old.png", mimeType: "image/png", buffer: png },
      },
    })
    expect(uploaded.status()).toBe(201)
    const oldId = (await uploaded.json()).id as string
    const created = await api.post("posts", {
      headers: headers(owner),
      data: { title: "替换图片作品", body: "正文", resourceIds: [oldId] },
    })
    const post = (await created.json()) as { id: string; revision: number }
    expect(
      (
        await api.post(`admin/posts/${post.id}/review`, {
          headers: headers(admin),
          data: { revision: post.revision, decision: "APPROVE" },
        })
      ).status()
    ).toBe(200)
    await page.goto("/")
    await page.getByLabel("账号").fill(username)
    await page.getByLabel("密码").fill(password)
    await page.getByRole("button", { name: "登录", exact: true }).click()
    await page.getByRole("button", { name: "个人中心", exact: true }).click()
    await page
      .locator('[data-slot="card"]')
      .filter({ hasText: "替换图片作品" })
      .getByRole("button", { name: "编辑" })
      .click()
    await page.getByRole("button", { name: "移除附件" }).click()
    await page
      .getByLabel("选择图片（选填）")
      .setInputFiles({ name: "new.png", mimeType: "image/png", buffer: png })
    await page.getByRole("button", { name: "上传图片" }).click()
    await expect(page.getByText("已就绪图片：1 / 9")).toBeVisible()
    await page.getByRole("button", { name: "提交修改" }).click()
    await expect(page.getByText("修改已提交审核。")).toBeVisible()
    const revised = await (
      await api.get(`posts/${post.id}`, { headers: headers(owner) })
    ).json()
    expect(revised.status).toBe("PENDING")
    expect(revised.revision).toBe(2)
    expect(revised.attachments).toHaveLength(1)
    expect(revised.attachments[0].id).not.toBe(oldId)
    expect((await api.get(`posts/${post.id}`)).status()).toBe(404)
  } finally {
    await api.dispose()
  }
})
