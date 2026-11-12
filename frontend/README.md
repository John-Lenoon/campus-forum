# 校园原创前端

React + Vite + TypeScript，界面组件使用 shadcn/ui（Radix UI），视频播放使用 hls.js。页面覆盖注册登录、文字／图片／视频投稿、管理员审核、公开作品浏览、个人中心和评论互动。

## 本地启动

先按照[项目根目录的运行说明](../README.md)启动后端及依赖，确认后端监听 `127.0.0.1:8080`。然后在新的终端中执行：

```powershell
cd frontend
pnpm install --frozen-lockfile
pnpm dev
```

打开 Vite 输出的本地地址（默认 `http://localhost:5173`）。开发服务器将 `/api` 和默认头像地址代理到本机 8080 端口；本地联调无需 Nginx。该代理仅用于 Vite 开发服务器，部署时需另行配置服务入口。

作品详情使用 `/posts/<作品 ID>` 地址。部署静态前端时，服务入口需要将详情地址回退到 `index.html`，以支持直接打开和刷新详情；API 和资源请求仍由后端处理。

管理员开发账号见根目录说明；普通账号可在页面注册。切换账号只会移除当前浏览器标签页保存的登录信息。

```powershell
pnpm lint
pnpm build
```

## 浏览器回归

需要 JDK 21、Maven、Docker Compose 和正在运行的本项目 openGauss、RabbitMQ：

```powershell
docker compose up -d --wait
cd frontend
pnpm install --frozen-lockfile
pnpm exec playwright install chromium --only-shell
pnpm test:e2e
```

脚本从当前后端源码构建独立服务，为每次运行创建临时数据库、Redis 容器和 RabbitMQ 队列；执行结束后清理这些测试资源，不使用开发库或用户账号。运行记录、失败截图、轨迹和 HTML 报告保存在 `target/playwright-e2e/<运行编号>/`。有已知的用户界面缺陷时，相关测试会如实失败并返回非零退出码，详见[浏览器回归记录](../docs/playwright-regression.md)。

视频播放用例需要 FFmpeg：可将其放在项目 `.tools` 中、设置 `FFMPEG_PATH`，或将 `ffmpeg` 加入 PATH。用例会生成有效视频，并检查 HLS 分段、连续播放、暂停和继续。

可使用 `pnpm exec playwright test --list` 发现测试；实际运行请使用 `pnpm test:e2e`，以创建隔离的测试环境。

## 视频投稿

在投稿页填写标题和正文，选择 MP4 视频并点击“开始上传”。等待处理完成并预览后，点击“提交审核”。视频最大 1 GiB、最长 30 分钟；审核通过后访客可播放。

上传中断后，可在同一浏览器标签页重新打开投稿页，选择原视频并继续上传。前端会校验分片内容，再查询并续传未成功的分片。任务信息保存在该标签页的会话存储中；关闭标签页后不保证恢复。视频处理及资源有效期由后端决定。

管理员可在审核页面点击“查看附件”预览图片或视频。投稿也支持最多九张图片；图片与视频不能混用。登录用户可在个人中心修改资料、查看及编辑或删除本人投稿，并查看点赞、收藏和评论历史。公开作品详情支持点赞、收藏、评论和回复。

现有热榜接口尚未接入前端；管理员的已发布作品列表及违规作品删除入口也尚未接入。
