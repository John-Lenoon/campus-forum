# campus-forum

校园原创作品分享社区后端。已实现账号资料、帖子编辑审核删除、图片与视频、点赞收藏、两层评论及本人历史、互动热榜。

技术栈：Java 21、Spring Boot 3.5.16、MyBatis-Plus 3.5.18、openGauss、Redis、RabbitMQ；FFmpeg/FFprobe 负责真实媒体识别和 HLS 处理。

## 代码结构

~~~text
src/main/java/campus/
  controller/       HTTP 路由与参数绑定
  service/          业务编排、校验、事务及权限
  entity/           持久化实体与读取投影
  mapper/           MyBatis-Plus 数据访问接口
  dto/              请求、认证上下文与专用传输对象
  config/           Bean、队列、数据库增列与管理员初始化
  job/              定时任务及 RabbitMQ 消费入口
  infrastructure/   私有存储、视频文件处理、FFmpeg 适配
  exception/        业务异常及 HTTP 异常映射
  support/          纯校验与地址工具
src/main/resources/mapper/  自定义 SQL 映射
~~~

规范见 [分层 Spec](docs/architecture-spec.md)。Controller 调用 Service，Service 通过 Mapper 访问数据库；不直接操作 JDBC 或使用 JdbcTemplate。

## 本地运行

前提：JDK 21、Maven 3.6.3+、Docker Compose，Docker 已启动。

Windows 首次安装媒体工具（FFmpeg 官网链接的 Gyan 构建，脚本校验 SHA-256）：

~~~powershell
.\scripts\install-media-tools.ps1
~~~

Linux 可安装 ffmpeg/ffprobe 到 PATH，或用 FFMPEG_PATH 指定 ffmpeg，ffprobe 须在其同一目录。

~~~powershell
docker compose up -d --wait
mvn -s .mvn/settings.xml package -DskipTests
java -jar target/campus-forum-0.1.0.jar
~~~

地址 http://127.0.0.1:8080。管理员 admin / admin123；无预置普通账号。

| 服务 | 默认本机端口 | 说明 |
| --- | --- | --- |
| openGauss | 25432 | 数据库 forum，用户 forum |
| Redis | 26479 | 固定期限凭证及热榜快照 |
| RabbitMQ | 25679 | 视频任务；管理界面 25680 |
| HTTP | 8080 | 所有默认监听仅绑定本机 |

Compose 默认数据库／RabbitMQ 密码用于本地开发，见 compose.yaml。已有数据卷的密码不会因修改环境变量自动重置。首次 openGauss 初始化由 --wait 等待。

openGauss 官方固定镜像 7.0.0-RC3.B025 为本地验证版本，不代表生产环境认证。

## 配置

| 配置 | 默认／作用 |
| --- | --- |
| DB_URL、DB_USER、DB_PASSWORD | jdbc:postgresql://localhost:25432/forum；forum；Compose 本地密码 |
| REDIS_HOST、REDIS_PORT | localhost、26479 |
| RABBIT_HOST、RABBIT_PORT、RABBIT_USER、RABBIT_PASSWORD | localhost、25679、forum、Compose 本地密码 |
| MEDIA_ROOT | data/resources；私有文件目录，不能直接对外发布 |
| FFMPEG_PATH | Windows 自动寻找 .tools，其他环境使用 PATH |
| SERVER_ADDRESS | 127.0.0.1 |
| forum.video-queue | 默认按数据库 URL 隔离；共享数据库的多实例需使用同一值 |
| forum.video-poll-ms | 30000，未完成任务重投间隔 |
| forum.cleanup-ms | 60000，过期资源扫描间隔 |

以 java -jar 后追加 --forum.cleanup-ms=60000 等形式覆盖普通 Spring 配置。视频处理中无临时 TTL；两小时执行超时、125 分钟租约；崩溃任务在租约到期后重投。

文件、工具和生成报告均不进 Git。资源原文件、HLS 清单和片段都通过后端鉴权路由读取，禁止把 data 目录配置成静态服务。

## 测试与整体验收

测试连接真实 openGauss、Redis、RabbitMQ；不使用 H2 或内存替身。先创建独立测试库（仅一次，已存在时不用重复）：

~~~powershell
docker compose exec -T opengauss su - omm -c 'gsql -d postgres -c "CREATE DATABASE forum_test OWNER forum;"'
mvn -s .mvn/settings.xml test
~~~

常规测试默认排除 large-media 标签的实际 1 GiB 用例。完整验收：

~~~powershell
mvn -s .mvn/settings.xml -Pfull-media verify
~~~

- 完整测试包含 1 GiB 的 128 片上传、真实 30 分钟 MP4、完整 HLS 解码、MQ 停机恢复、期限清理及跨模块回归。
- 至少预留约 3 GiB 空闲磁盘。大文件测试结束后通过资源回收释放生成数据。
- MQ 恢复测试会停止并恢复当前项目的 RabbitMQ；应使用此项目独立 Compose 服务，不连接其他用途的共享测试 broker。
- 使用 forum_test 和随机账号，不清空开发库；文件按资源 UUID 区分。
- ArchitectureTest 验证分包、依赖边界、MyBatis-Plus Mapper 及 SQL 归属；可用 mvn -s .mvn/settings.xml -Dtest=ArchitectureTest test 单独执行。
- 报告位于 target/surefire-reports；本次契约构建日志位于 target/contract-final-verify.log。
- 2026-11-10 分层及 MyBatis-Plus 重构结果见 [重构验收记录](docs/refactor-acceptance.md)；2026-11-09 业务验收保留为历史记录。
- 当前契约及本次落地结果见 [API 契约](docs/api-contract.md)、[差异清单](docs/api-gap-analysis.md)、[契约验收](docs/contract-acceptance.md)；历史结果不替代本次执行。
- 本次验证复用了本机 Maven 缓存，可选参数为 '-Dmaven.repo.local=C:\Users\intelligent uesr\.m2\repository'，其他机器无需使用此路径。

若使用最初开发阶段按字节长度创建的表，先分别迁移 forum 和 forum_test；新库不需要：

~~~powershell
Get-Content src/main/resources/db/migrations/V002__character_semantics.sql | docker compose exec -T opengauss su - omm -c 'gsql -d forum -v ON_ERROR_STOP=1'
Get-Content src/main/resources/db/migrations/V002__character_semantics.sql | docker compose exec -T opengauss su - omm -c 'gsql -d forum_test -v ON_ERROR_STOP=1'
~~~

DatabaseEvolution 会补齐后续新增列；schema.sql 负责初始化新表，不能代替对已有列的迁移。

停止服务并保留数据：

~~~powershell
docker compose stop
~~~

## API 起点

注册后登录，在后续请求携带 Authorization: Bearer <token>。完整请求字段、权限、错误、分页及媒体流程见现行接口契约。

修改帖子必须显式提交 revision、title、body、resourceIds；[]表示清空附件，旧revision返回409。公开最新流／热榜不返回个人互动状态，状态从详情查询。新增模块按“业务Spec → API契约 → 测试与实现 → 验收及文档核对”执行。

~~~powershell
$account = @{ username='student01'; password='password123'; nickname='同学' } | ConvertTo-Json
Invoke-RestMethod http://127.0.0.1:8080/api/v1/auth/register -Method Post -ContentType 'application/json; charset=utf-8' -Body $account
$session = Invoke-RestMethod http://127.0.0.1:8080/api/v1/auth/login -Method Post -ContentType 'application/json' -Body '{"username":"student01","password":"password123"}'
$headers = @{ Authorization = 'Bearer ' + $session.token }
$post = @{ title='原创作品'; body=('第一行','第二行' -join [Environment]::NewLine); resourceIds=@() } | ConvertTo-Json
Invoke-RestMethod http://127.0.0.1:8080/api/v1/posts -Method Post -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $post
~~~

## 文档

- [业务需求及 DEC-049 验收门槛](docs/requirements.md)
- [现行完整接口与技术设计](docs/backend-design.md)
- [实施计划及批次结果](docs/implementation-plan.md)
- [整体验收结果与未执行项](docs/final-acceptance.md)
- [决策及授权内实施决定](docs/decision-log.md)
- [原问题处理状态](docs/open-questions.md)
- [第一批历史验收基线](docs/batch-1-acceptance.md)

不交付前端、线上部署及未约定的性能指标。已确认业务规则和 DEC-049 保持不变。
