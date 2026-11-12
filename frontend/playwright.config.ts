import { defineConfig } from "@playwright/test"
import { resolve } from "node:path"

const output = resolve(
  import.meta.dirname,
  "../target/playwright-e2e",
  process.env.E2E_RUN_ID || "discovery"
)

export default defineConfig({
  testDir: "./tests/e2e",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  globalSetup: "./tests/e2e/require-isolation.ts",
  timeout: 30_000,
  expect: { timeout: 5_000 },
  outputDir: resolve(output, "test-results"),
  reporter: [
    ["list"],
    ["html", { open: "never", outputFolder: resolve(output, "report") }],
    ["junit", { outputFile: resolve(output, "results.xml") }],
  ],
  use: {
    baseURL: process.env.E2E_BASE_URL,
    browserName: "chromium",
    viewport: { width: 900, height: 600 },
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
  },
})
