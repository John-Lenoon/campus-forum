# ARCH-01 验证与整改记录

日期：2026-11-10。范围仅为 [ARCH-01 原始发现](01-architecture.md)。没有处理 ARCH-02、ARCH-03 或 ARCH-04，没有调整技术栈、API 契约、DEC-049 或整体验收门槛。

## 结论

两种并发场景均在真实 HTTP 和 openGauss 中确定性复现：修复前回复请求返回 **201**，本人评论历史新增一条 `deleted=false` 的回复；修复后均返回 **404 NOT_FOUND**，本人历史无该回复。删除一级评论的场景中，原有目标回复的本人详情返回 410，根回复列表返回 404。

定向和受影响回归 **24 项通过**；随后 `full-media verify` **76 项通过，失败 0、错误 0、跳过 0**。这是本次回归结果；ARCH-02 等审查发现未在本轮处理，不能据此声称满足 DEC-049 的项目整体验收门槛。

## 依赖及缓存核对

- 项目依赖 MyBatis-Plus 3.5.18；本次构建的 JAR 含 MyBatis 3.5.19、MyBatis-Spring 3.0.6、Spring TX 6.2.19。openGauss、Redis、RabbitMQ 使用当前项目独立 Compose 服务。
- `application.properties` 未配置 `local-cache-scope`；本机 MyBatis 3.5.19 源码中 `Configuration` 默认 SESSION，`BaseExecutor.query` 会先查会话本地缓存。XML 的 SELECT 默认不刷新该缓存。`useCache=false` 单独作用于二级缓存，不能保证避开此问题。
- 原实现先用 `selectById` 读取回复目标以定位帖子，获取帖子行锁后再次使用同一查询校验目标。两次查询间只有锁定帖子的 SELECT，无本会话写入；真实红测验证了删除提交后仍使用锁前结果的可达后果。

## 确定性复现方法

测试见 [Arch01ConcurrencyTest.java](../../src/test/java/campus/Arch01ConcurrencyTest.java)。测试仅通过 HTTP 创建用户、投稿、审核、评论、删除和读取结果；使用真实数据库、真实 Mapper 和事务，没有替换 Mapper 结果。

1. 测试专用过滤器标识指定的删除和回复 HTTP 请求。测试专用 MyBatis 拦截器在删除请求执行完 `PostMapper.lockActive` 后暂停，此时删除事务持有帖子锁且未提交。
2. 发起回复请求；其先读取目标，再进入帖子锁查询。拦截器在回复调用锁查询前发信号，随后允许真实查询执行。
3. 测试专用 Mapper 查询 openGauss `pg_stat_activity`，确认同库同用户存在正在等待的帖子 `FOR UPDATE` 查询；到此确认回复已完成锁前读取并进入锁等待。该查询仅用于同步时序，不作为业务结果断言。
4. 释放删除请求，等待删除 HTTP 200，删除事务提交后，等待回复 HTTP 响应；读取回复者本人历史断言没有新回复。分别执行删除目标回复、删除一级评论及其现有回复两种情况。
5. 屏障与等待观察器均位于测试源码；生产代码没有测试开关。构建 JAR 检查未包含测试类。等待有超时，未使用随机碰撞、循环发起请求或 mock Mapper。

## 红测证据

修复前执行：

```powershell
mvn -o -s .mvn/settings.xml '-Dmaven.repo.local=C:/Users/intelligent uesr/.m2/repository' '-Dtest=Arch01ConcurrencyTest' test
```

| 场景 | 预期 | 修复前实际 |
| --- | --- | --- |
| 删除目标回复 | 回复 HTTP 404，历史无新回复 | HTTP 201，历史出现该新回复且 deleted=false |
| 删除一级评论 | 回复 HTTP 404，历史无漏过级联的新回复 | HTTP 201，历史出现该新回复且 deleted=false |

两项失败均发生在等待状态被确认及删除成功之后；测试运行数 2、断言失败 2、错误 0、跳过 0。首次编写测试时曾因测试 Mapper 注册影响自动扫描导致上下文启动失败，随后在测试配置中显式扫描生产 Mapper；该环境错误不计为红测。有效红测日志：[target/arch01-red.log](../../target/arch01-red.log)。

## 最小修复

- [CommentService.java](../../src/main/java/campus/service/CommentService.java)：保留原先的帖子锁及状态检查；锁后通过独立的 `findReplyTarget` 语句读取目标并检查删除状态。
- [CommentMapper.java](../../src/main/java/campus/mapper/CommentMapper.java)、[CommentMapper.xml](../../src/main/resources/mapper/CommentMapper.xml)：增加目标读取的具名映射。它与锁前的 BaseMapper `selectById` 使用不同的 mapped statement／缓存键，首次锁后查询不会命中前者的一级缓存。
- 没有更改全局缓存范围；没有仅依赖 `useCache=false`。当前用例持有帖子锁期间，评论删除也须先持有同一锁，因此锁后读取及插入受现有锁顺序保护。
- 没有改变删除级联、评论可见性、本人历史、权限或响应契约。原有测试及断言未修改。

## 本次测试结果

| 检查 | 实际结果 |
| --- | --- |
| 新增两个确定性 HTTP 并发测试 | 2 通过；删除目标回复及删除一级评论两种时序均经数据库等待状态确认 |
| 新增测试＋ArchitectureTest、RemainingFlowsTest、CrossModuleAcceptanceTest、ContractCoverageTest | 24 通过，失败／错误／跳过均为 0；见 [target/arch01-regression.log](../../target/arch01-regression.log) |
| 完整回归：`mvn -o -s .mvn/settings.xml '-Dmaven.repo.local=C:/Users/intelligent uesr/.m2/repository' -Pfull-media verify` | 76 通过，失败／错误／跳过均为 0；MediaFlowsTest 11 项包含实际 1 GiB 用例；见 [target/arch01-full.log](../../target/arch01-full.log) 和 target/surefire-reports |
| 依赖恢复 | full-media 中 Redis／RabbitMQ 故障恢复用例执行后，三个项目 Compose 服务均显示 healthy |

日志位于被 Git 忽略的 `target/`，后续 clean 可能清除。运行期间首次 Mapper 扫描配置错误已修正且未作为红测或最终通过证据。

**未执行**：独立新库 JAR 冒烟、生产环境／多实例验证、ARCH-02/03/04 的修复与验证。本轮没有提交 Git，也不自动进入下一轮。
