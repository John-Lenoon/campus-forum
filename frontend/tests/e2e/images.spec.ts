import { expect, test } from "@playwright/test"

const png = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAIAAACQkWg2AAAACXBIWXMAAAABAAAAAQBPJcTWAAAAIklEQVR4nGP8t4SBJMBCmnKGUQ1EARbilCHAqAZiAMmhBABgJQHg26tj6AAAAABJRU5ErkJggg==",
  "base64"
)

test("发布图文作品、审核预览并公开显示图片", async ({ page, playwright }) => {
  const run = process.env.E2E_RUN_ID!
  const username = `image_${run}`
  const password = "image_test_123"
  const title = `图文作品 ${run}`
  const api = await playwright.request.newContext({
    baseURL: `${process.env.E2E_API_URL}/`,
  })
  try {
    expect(
      (
        await api.post("auth/register", { data: { username, password } })
      ).status()
    ).toBe(201)
    await page.goto("/")
    await page.getByLabel("账号").fill(username)
    await page.getByLabel("密码").fill(password)
    await page.getByRole("button", { name: "登录", exact: true }).click()
    await page.getByRole("button", { name: "投稿", exact: true }).click()
    await page.getByLabel("标题", { exact: true }).fill(title)
    await page.getByLabel("正文", { exact: true }).fill("图文作品正文")
    await page
      .getByLabel("选择图片（选填）")
      .setInputFiles({ name: "work.png", mimeType: "image/png", buffer: png })
    await page.getByRole("button", { name: "上传图片" }).click()
    await expect(page.getByText("已就绪图片：1 / 9")).toBeVisible()
    await page.getByRole("button", { name: "提交审核" }).click()
    await expect(
      page.getByText("投稿成功，审核通过后会出现在作品列表。")
    ).toBeVisible()
    await page.getByRole("button", { name: "切换账号" }).click()
    await page.getByLabel("账号").fill("admin")
    await page.getByLabel("密码").fill("admin123")
    await page.getByRole("button", { name: "登录", exact: true }).click()
    const card = page.locator('[data-slot="card"]').filter({ hasText: title })
    await expect(card).toBeVisible()
    await card.getByRole("button", { name: "查看附件" }).click()
    const preview = card.getByRole("img", { name: "作品图片 1" })
    await expect(preview).toBeVisible()
    await expect
      .poll(() =>
        preview.evaluate((img) => (img as HTMLImageElement).naturalWidth)
      )
      .toBeGreaterThan(0)
    await card.getByRole("button", { name: "通过审核" }).click()
    await expect(page.getByText("已通过审核，作品现已公开。")).toBeVisible()
    await page.getByRole("button", { name: "切换账号" }).click()
    await page
      .getByRole("button")
      .filter({ has: page.getByRole("heading", { name: title, exact: true }) })
      .click()
    const image = page.getByRole("img", { name: "作品图片 1" })
    await expect(image).toBeVisible()
    await expect
      .poll(() =>
        image.evaluate((img) => (img as HTMLImageElement).naturalWidth)
      )
      .toBeGreaterThan(0)
  } finally {
    await api.dispose()
  }
})
