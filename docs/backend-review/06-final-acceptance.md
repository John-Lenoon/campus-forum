# 交付前整改最终验收

日期：2026-11-11。基线 checkpoint：`6cd42654d47fdf9e8994c36665796cfc0e07912b`。适用业务依据为 requirements.md 的 BR-01—46、AC-01—15、API 契约及已确认决策；DEC-049 和需求 10.4 原文未修改。

## 结果

| 分类 | 结果 |
| --- | --- |
| 通过 | 最终代码 clean full-media 91 项全部通过；隔离新库独立 JAR 冒烟和重启持久化通过；接口契约、运行说明和实施记录已核对 |
| 失败 | 最终回归 0 失败、0 错误；最终 JAR 冒烟无失败。过程中红测及隔离环境错误如实保留在专项记录，不计为最终通过证据 |
| 阻塞 | 0；当前没有已知违反本期已确认需求且未解决的缺陷 |
| 未执行 | 本期约定的完整测试无跳过。前端页面、生产部署／高可用、跨版本认证、未约定的性能容量指标和全面安全审计不在本期范围，未执行且不宣称通过 |

基于本次已确认且适用的测试结果、接口与运行说明以及当前已知缺陷状态，**满足 DEC-049 的本期后端整体通过门槛，可以开始本地前端联调**。该结果不等同于生产上线、性能达标或用户最终签署需求。

## 本次最终代码验证

命令：

```powershell
mvn -o -s .mvn/settings.xml '-Dmaven.repo.local=C:/Users/intelligent uesr/.m2/repository' clean -Pfull-media verify
```

| 测试类 | 执行 | 失败 | 错误 | 跳过 |
| --- | ---: | ---: | ---: | ---: |
| Arch01ConcurrencyTest | 2 | 0 | 0 | 0 |
| Arch02ImageConsistencyTest | 11 | 0 | 0 | 0 |
| Arch03ProcessInterruptionTest | 1 | 0 | 0 | 0 |
| ArchitectureTest | 3 | 0 | 0 | 0 |
| BootstrapAcceptanceTest | 1 | 0 | 0 | 0 |
| ContractCoverageTest | 6 | 0 | 0 | 0 |
| ContractImplementationTest | 8 | 0 | 0 | 0 |
| ContractValidationTest | 8 | 0 | 0 | 0 |
| CrossModuleAcceptanceTest | 6 | 0 | 0 | 0 |
| ForumHttpTest | 24 | 0 | 0 | 0 |
| InteractionVisibilityRaceTest | 2 | 0 | 0 | 0 |
| MediaFlowsTest | 11 | 0 | 0 | 0 |
| RemainingFlowsTest | 7 | 0 | 0 | 0 |
| VideoCommitRaceTest | 1 | 0 | 0 | 0 |
| 总计 | 91 | 0 | 0 | 0 |

`MediaFlowsTest` 包含实际 1 GiB 上传、视频处理、MQ 恢复及物理回收；最终 XML 中 `actualOneGiBVideoUploadsWithBoundedChunksAndIsCleanedAfterExpiry` 已执行。实际新并发／故障用例使用真实 openGauss、RabbitMQ 和媒体进程；仅屏障与故障注入存在于测试环境。测试类未打入生产 JAR。原有效测试源码和断言与基线一致。

最终 JAR SHA-256：`b7d735503ac07d5b1f3f7b6db31ca496726db6d0c53c6bd2a073e597bdec95e2`。独立随机数据库内验证管理员预置、注册登录、真实头像、投稿驳回重提及审核、版本保护、互动评论、公开卡片、依赖检查；重启后原 Redis 凭证、帖子、评论与头像仍可用。专属数据库、队列和临时进程已回收，项目三个依赖服务正常。

本次所测文件树摘要：`src/main` 75 文件 `fcbbec15bdab0de0bdfbc554d611b6e0d78cfafe98c1e8e3b90e4195530c89fc`；`src/test` 14 文件 `63fbc6b8c6d90ffbb348c48d48240d1fe790521515579538585cc1d4ba126d28`。算法与 [架构审查基线](01-architecture.md) 相同。

## 整改证据及范围

- ARCH-01：[锁等待后目标状态](02-arch01-verification.md)；ARCH-02：[图片数据库／文件一致性](03-arch02-verification.md)；ARCH-03：[真实媒体进程中断](04-arch03-verification.md)；ARCH-04：原审查报告中已记录事务及锁顺序说明。
- [关键路径记录](05-critical-path.md)：本人互动列表下架竞争和视频完成回执异常状态回退均先取得确定性红测，再修复并回归；重复 MQ 消息使用独立队列验证。
- 原业务 Spec、DEC-049、API 契约、pom 和旧有效测试未修改；只读复核与最终重新执行结果均已纳入判断。工程使用现有 Java、Spring Boot、openGauss、Redis、RabbitMQ、MyBatis-Plus。
- 原始构建日志：`target/delivery-final-verify.log`；逐例报告：`target/surefire-reports/TEST-*.xml`；JAR 冒烟结果及日志：`target/contract-jar-smoke-result.json`、`target/delivery-jar-smoke.log`。红测及定向日志位于 `target/` 的对应文件；构建输出和日志按 Git 忽略，不随提交保存，后续 clean 会删除。
