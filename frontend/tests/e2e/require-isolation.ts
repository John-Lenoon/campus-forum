export default function requireIsolation() {
  if (
    !process.env.E2E_BASE_URL ||
    !process.env.E2E_API_URL ||
    !process.env.E2E_RUN_ID
  ) {
    throw new Error(
      "Run through pnpm test:e2e so the backend and test database are isolated."
    )
  }
}
