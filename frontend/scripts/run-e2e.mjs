import { spawn, spawnSync } from "node:child_process"
import { randomBytes } from "node:crypto"
import { mkdirSync, openSync, closeSync, cpSync, copyFileSync } from "node:fs"
import { createServer } from "node:net"
import { dirname, join, resolve } from "node:path"
import { fileURLToPath } from "node:url"
import { setTimeout as delay } from "node:timers/promises"

const frontend = resolve(dirname(fileURLToPath(import.meta.url)), "..")
const root = resolve(frontend, "..")
const runId = randomBytes(6).toString("hex")
const database = `forum_e2e_${runId}`
const queue = `campus-forum.e2e.${runId}`
const redisName = `campus-forum-e2e-${runId}`
const output = join(root, "target", "playwright-e2e", runId)
mkdirSync(output, { recursive: true })
let databaseCreated = false
let redisCreated = false
let backend
let vite
let logFile
let exitCode = 1

function sql(statement) {
  const result = spawnSync(
    "docker",
    [
      "compose",
      "exec",
      "-T",
      "opengauss",
      "su",
      "-",
      "omm",
      "-c",
      "gsql -d postgres -v ON_ERROR_STOP=1",
    ],
    {
      cwd: root,
      input: `${statement}\n`,
      encoding: "utf8",
      timeout: 40000,
    }
  )
  if (result.error || result.status !== 0)
    throw new Error(`Database command failed: ${result.stderr || result.error}`)
}

async function freePort(excluded = 0) {
  const port = await new Promise((resolvePort, reject) => {
    const server = createServer()
    server.once("error", reject)
    server.listen(0, "127.0.0.1", () => {
      const address = server.address()
      server.close((error) =>
        error ? reject(error) : resolvePort(address.port)
      )
    })
  })
  return port === excluded ? freePort(excluded) : port
}

async function waitReady(url, child) {
  const deadline = Date.now() + 90000
  let lastError
  while (Date.now() < deadline) {
    if (child.exitCode !== null)
      throw new Error(`Server exited before ready: ${url}; see ${output}`)
    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(2000) })
      if (response.ok) return
      lastError = `HTTP ${response.status}`
    } catch (error) {
      lastError = error.message
    }
    await delay(250)
  }
  throw new Error(
    `Server did not become ready: ${url}; ${lastError}; see ${output}`
  )
}

async function stop(child) {
  if (!child || child.exitCode !== null || child.signalCode !== null) return
  const exited = new Promise((resolveExit) => child.once("exit", resolveExit))
  child.kill()
  await Promise.race([
    exited,
    delay(10000).then(() => {
      throw new Error(`Could not stop process ${child.pid}`)
    }),
  ])
}

try {
  const buildDirectory = join(output, "backend-build")
  mkdirSync(join(buildDirectory, "src"), { recursive: true })
  cpSync(join(root, "src", "main"), join(buildDirectory, "src", "main"), {
    recursive: true,
  })
  cpSync(join(root, ".mvn"), join(buildDirectory, ".mvn"), { recursive: true })
  copyFileSync(join(root, "pom.xml"), join(buildDirectory, "pom.xml"))
  const build = spawnSync("mvn -q -s .mvn/settings.xml -DskipTests package", {
    cwd: buildDirectory,
    shell: true,
    stdio: "inherit",
  })
  if (build.status !== 0) throw new Error("Backend packaging failed")
  sql(`CREATE DATABASE ${database} OWNER forum;`)
  databaseCreated = true
  const backendPort = await freePort()
  const frontendPort = await freePort(backendPort)
  let redisPort = await freePort(backendPort)
  while (redisPort === frontendPort) redisPort = await freePort(backendPort)
  const redis = spawnSync(
    "docker",
    [
      "run",
      "-d",
      "--rm",
      "--name",
      redisName,
      "-p",
      `127.0.0.1:${redisPort}:6379`,
      "redis:7-alpine",
    ],
    { cwd: root, encoding: "utf8", timeout: 60000 }
  )
  if (redis.error || redis.status !== 0)
    throw new Error(
      `Could not start isolated Redis: ${redis.stderr || redis.error}`
    )
  redisCreated = true
  const backendUrl = `http://127.0.0.1:${backendPort}`
  const frontendUrl = `http://127.0.0.1:${frontendPort}`
  logFile = openSync(join(output, "servers.log"), "w")
  backend = spawn(
    "java",
    [
      "-jar",
      join(buildDirectory, "target", "campus-forum-0.1.0.jar"),
      `--server.port=${backendPort}`,
      `--spring.datasource.url=jdbc:postgresql://localhost:25432/${database}`,
      `--spring.data.redis.port=${redisPort}`,
      `--forum.video-queue=${queue}`,
      `--forum.storage-root=${join(output, "resources")}`,
    ],
    { cwd: root, stdio: ["ignore", logFile, logFile], windowsHide: true }
  )
  await waitReady(`${backendUrl}/api/v1/posts`, backend)
  vite = spawn(
    process.execPath,
    [
      join(frontend, "node_modules", "vite", "bin", "vite.js"),
      "--host",
      "127.0.0.1",
      "--port",
      String(frontendPort),
      "--strictPort",
    ],
    {
      cwd: frontend,
      env: { ...process.env, CAMPUS_BACKEND_URL: backendUrl },
      stdio: ["ignore", logFile, logFile],
      windowsHide: true,
    }
  )
  await waitReady(frontendUrl, vite)
  console.log(`Isolated Playwright run ${runId}; server log: ${output}`)
  const runner = spawn(
    process.execPath,
    [
      join(frontend, "node_modules", "@playwright", "test", "cli.js"),
      "test",
      ...process.argv.slice(2),
    ],
    {
      cwd: frontend,
      env: {
        ...process.env,
        E2E_BASE_URL: frontendUrl,
        E2E_API_URL: `${backendUrl}/api/v1`,
        E2E_RUN_ID: runId,
      },
      stdio: "inherit",
      windowsHide: true,
    }
  )
  exitCode = await new Promise((resolveExit, reject) => {
    runner.once("error", reject)
    runner.once("exit", (code) => resolveExit(code ?? 1))
  })
} catch (error) {
  console.error(error)
} finally {
  try {
    await stop(vite)
    await stop(backend)
    if (databaseCreated) {
      const queueCleanup = spawnSync(
        "docker",
        [
          "compose",
          "exec",
          "-T",
          "rabbitmq",
          "rabbitmqctl",
          "delete_queue",
          queue,
        ],
        { cwd: root, encoding: "utf8", timeout: 30000 }
      )
      if (
        queueCleanup.status !== 0 &&
        !`${queueCleanup.stdout}${queueCleanup.stderr}`.includes("not found")
      )
        throw new Error(
          `Could not remove isolated queue: ${queueCleanup.stderr}`
        )
      sql(`DROP DATABASE ${database};`)
      console.log("Removed isolated database and queue.")
    }
  } catch (error) {
    console.error(error)
    exitCode = 1
  }
  if (redisCreated) {
    const stopped = spawnSync("docker", ["stop", redisName], {
      cwd: root,
      encoding: "utf8",
      timeout: 30000,
    })
    if (stopped.status !== 0) {
      console.error(
        `Could not stop isolated Redis ${redisName}: ${stopped.stderr}`
      )
      exitCode = 1
    } else console.log("Stopped isolated Redis.")
  }
  if (logFile !== undefined) closeSync(logFile)
}
process.exitCode = exitCode
