import { expect, test } from "@playwright/test"

test("本人投稿列表仅返回自己的未删除稿件，并遵循权限和分页", async ({
  playwright,
}) => {
  const api = await playwright.request.newContext({
    baseURL: `${process.env.E2E_API_URL}/`,
  })
  const run = process.env.E2E_RUN_ID!
  async function user(suffix: string) {
    const username = `mine_${run}_${suffix}`
    const password = "test_password_123"
    expect(
      (
        await api.post("auth/register", { data: { username, password } })
      ).status()
    ).toBe(201)
    const login = await api.post("auth/login", { data: { username, password } })
    expect(login.status()).toBe(200)
    return (await login.json()).token as string
  }
  const owner = await user("owner")
  const other = await user("other")
  const adminLogin = await api.post("auth/login", {
    data: { username: "admin", password: "admin123" },
  })
  expect(adminLogin.status()).toBe(200)
  const admin = (await adminLogin.json()).token as string
  const auth = (token: string) => ({ Authorization: `Bearer ${token}` })
  async function create(token: string, title: string) {
    const result = await api.post("posts", {
      headers: auth(token),
      data: { title, body: "本人列表测试", resourceIds: [] },
    })
    expect(result.status()).toBe(201)
    return (await result.json()) as { id: string; revision: number }
  }
  async function review(
    post: { id: string; revision: number },
    decision: "APPROVE" | "REJECT"
  ) {
    const response = await api.post(`admin/posts/${post.id}/review`, {
      headers: auth(admin),
      data: {
        revision: post.revision,
        decision,
        ...(decision === "REJECT" ? { reason: "需要修改" } : {}),
      },
    })
    expect(response.status()).toBe(200)
  }
  try {
    const pending = await create(owner, "待审稿")
    const published = await create(owner, "已发布稿")
    const rejected = await create(owner, "已驳回稿")
    const deleted = await create(owner, "已删除稿")
    const outsider = await create(other, "其他作者稿")
    await review(published, "APPROVE")
    await review(rejected, "REJECT")
    expect(
      (
        await api.delete(`posts/${deleted.id}`, { headers: auth(owner) })
      ).status()
    ).toBe(200)

    const response = await api.get("me/posts?size=2&page=1", {
      headers: auth(owner),
    })
    expect(response.status(), await response.text()).toBe(200)
    const first = await response.json()
    const secondResponse = await api.get("me/posts?size=2&page=2", {
      headers: auth(owner),
    })
    expect(secondResponse.status()).toBe(200)
    const second = await secondResponse.json()
    expect(first.total).toBe(3)
    expect(first.items).toHaveLength(2)
    expect(second.items).toHaveLength(1)
    const all = [...first.items, ...second.items]
    expect(all.map((item) => item.id)).toEqual(
      expect.arrayContaining([pending.id, published.id, rejected.id])
    )
    expect(all.map((item) => item.id)).not.toContain(deleted.id)
    expect(all.map((item) => item.id)).not.toContain(outsider.id)
    expect(all.map((item) => item.status).sort()).toEqual([
      "PENDING",
      "PUBLISHED",
      "REJECTED",
    ])
    expect(all.find((item) => item.id === rejected.id).rejectionReason).toBe(
      "需要修改"
    )
    expect(
      all.every(
        (item) =>
          typeof item.body === "string" && !Object.hasOwn(item, "attachments")
      )
    ).toBe(true)
    expect(all.map((item) => [item.submittedAt, item.id])).toEqual(
      [...all]
        .sort(
          (a, b) => b.submittedAt - a.submittedAt || b.id.localeCompare(a.id)
        )
        .map((item) => [item.submittedAt, item.id])
    )
    expect((await api.get("me/posts")).status()).toBe(401)
    expect((await api.get("me/posts", { headers: auth(admin) })).status()).toBe(
      403
    )
    expect(
      (await api.get("me/posts?page=0", { headers: auth(owner) })).status()
    ).toBe(400)
    const empty = await api.get("me/posts?page=10", { headers: auth(owner) })
    expect((await empty.json()).items).toEqual([])
  } finally {
    await api.dispose()
  }
})
