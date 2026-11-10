# 分层与 MyBatis-Plus 重构验收

日期：2026-11-10。依据：DEC-054/055、[architecture-spec.md](architecture-spec.md)。

## 1. 结论

**分层与 MyBatis-Plus 重构验收通过。** Spec 先行补充，生产代码及运行文档已完成迁移。

| 分类 | 结果 |
| --- | --- |
| 通过 | 52 项全量测试：49 项原功能验收、3 项架构约束；独立 JAR 新库运行与重启持久化 |
| 失败 | 0；最终构建无测试失败或错误 |
| 阻塞 | 0；没有待用户决定的实际阻塞 |
| 未执行 | 本次 Spec 及后端适用功能验收为 0；前端、生产部署和未约定性能指标仍属于范围外，未执行 |

## 2. 修改范围

- Controller、Service、Entity、Mapper、DTO 分包；每个公开顶层类型独立文件。
- 数据库访问集中到 MyBatis-Plus Mapper 和 XML，移除应用中的 JdbcTemplate／直接 JDBC 操作。
- 用户、帖子、评论、资源使用 BaseMapper；互动、视频分片保留既有联合主键和具名映射。
- 资料修改事务移入 UserService；评论读取作者资料调用 UserService。
- 登录与帖子 HTTP 适配分别为 AuthController、PostController。
- 启动增列及管理员预置保留在监听前；调度、MQ 入口、私有存储、视频文件处理及异常映射独立。
- 实体字段强类型，SQL 结果不再以 Map 穿透到业务逻辑；既有 API 响应保持原 JSON 形状。

## 3. 执行证据

- 原实现常规回归：48 项，失败 0、错误 0、跳过 0。
- 初次 MyBatis-Plus 迁移后的账号／帖子／启动回归：25 项通过；其余常规功能回归：23 项通过。
- 架构约束对旧源码运行：3 项断言失败、错误 0；对当前分层源码运行：3 项通过。
- 原有 5 个功能测试源文件未修改；最终回归须包含原 49 项功能验收及 3 项架构约束。
- 最终命令：mvn -s .mvn/settings.xml clean -Pfull-media verify。

| 测试类 | 执行 | 失败 | 错误 | 跳过 |
| --- | --- | --- | --- | --- |
| ArchitectureTest | 3 | 0 | 0 | 0 |
| BootstrapAcceptanceTest | 1 | 0 | 0 | 0 |
| ForumHttpTest | 24 | 0 | 0 | 0 |
| CrossModuleAcceptanceTest | 6 | 0 | 0 | 0 |
| MediaFlowsTest（含 large-media） | 11 | 0 | 0 | 0 |
| RemainingFlowsTest | 7 | 0 | 0 | 0 |
| 总计 | 52 | 0 | 0 | 0 |

- clean 移除旧包编译残留后重新构建，实际产物只保留分层后的类；JAR 包含 7 份 Mapper XML 和 MyBatis-Plus 3.5.18 依赖。
- 全量测试实际执行 1 GiB 上传、30 分钟视频处理、完整 HLS 解码、MQ 停机恢复、物理回收与启动竞态，不以声明值或模拟结果代替。
- 独立 JAR 在随机新建 openGauss 库上验证注册登录、管理员预置、资料与真实图片、投稿驳回重提通过、旧稿冲突、互动评论、热榜及依赖检查。
- 独立进程重启后，原 Redis 凭证、数据库记录、公开帖子、评论和头像文件仍可用；临时进程、专属测试队列及测试数据库已回收。
- [逐例结果与不变量核对](refactor-acceptance-results.json)；原始 XML／文本结果位于 target/surefire-reports；构建日志位于 target/refactor-full-verify.log；独立产物结果位于 target/layer-smoke-result.json。
- 架构约束对旧源码的预期失败属于检查有效性证据，不是最终实现的未解决失败。

## 4. 不变量与边界

- 业务规则与 HTTP 契约保持不变；原 5 个功能测试、数据库建表与迁移脚本已按字节核对快照一致。
- DEC-049 决策行及需求 10.4 整节已逐字核对，原文不变。
- 不增加前端、生产部署或性能承诺；历史报告不冒充本次重构结果。
- MyBatis-Plus 是用户明确选型；后续技术栈选型或变更先询问用户。
