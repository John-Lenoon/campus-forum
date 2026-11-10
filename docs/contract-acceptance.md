# API 契约落地验收记录

日期：2026-11-10。依据：api-contract.md、api-gap-analysis.md、DEC-057—061，整体验收遵守 DEC-049 和需求 10.4。

## 当前状态

**本次契约落地及约定后端功能整体验收通过。** 三项差异全部关闭，已自动完成后续契约校验批次；历史结果未替代本次验证。

| 分类 | 最终结果 |
| --- | --- |
| 通过 | clean完整构建74项：原52项＋新增22项；独立JAR新库运行及重启持久化 |
| 失败 | 0；最终测试失败0、错误0、跳过0 |
| 阻塞 | 0；发现的Q-054歧义已询问并按用户A记录DEC-061，无未回答的必要问题 |
| 未执行 | 本次约定Spec／契约适用验收无跳过或未执行；前端、生产部署／高可用、未约定性能与容量指标属于范围外，未执行且不宣称通过 |

## 第一批：两个实现差异与作者版本保护

公开 HTTP 为测试边界，真实 openGauss、Redis、RabbitMQ 与媒体工具；未替换内部 Mapper 或业务 Service。

| 项目 | 修复前实际失败 | 最小修复 | 定向结果 |
| --- | --- | --- | --- |
| MISS-01；DEC-059 | 合法 revision 修改请求应200，实际400；请求DTO原本不接受revision | 独立 EditPostRequest；同事务行锁下比较作者当前版本，再绑定资源和保存 | staleAuthorReplayCannotHideAReviewedPost 通过；旧请求409、已审新稿保持公开 |
| DIFF-01；DEC-057 | 漏传附件集合应400，实际200 | 修改入口拒绝 null／省略集合；[]显式清空不受影响 | omittedOrNullAttachmentsCannotClearOrHideAPublishedPost 通过；原稿及文件保持不变 |
| DIFF-02；DEC-058 | 最新卡片缺attachments；热榜包含liked/favorited/body，两者结构不同 | 统一 PostCard；热榜按当前公开资格读取该投影 | latestAndHotUseTheSamePublicCardWithoutPersonalFlags 通过；详情／本人列表保留真实个人状态 |

- 失败日志：target/contract-red-version.log、contract-red-attachments.log、contract-red-cards.log；均为实际断言失败，非编译或环境故障。
- 定向绿色日志：target/contract-green-version.log、contract-green-edit.log、contract-green-primary.log。
- 额外回归检查缺失／null／非法版本、同版本双写唯一胜出、修改与审核竞争、资源原子失败及显式保留／清空、身份边界、文字／图文／视频卡片。
- 原有测试在10处合法修改请求中显式加入当前revision及附件数组；另将原“缺文件”用例改为合法multipart载体，使它继续验证缺文件400，而不与错误Content-Type415混淆。全部有效断言保留，不让helper替缺字段负向用例补字段。
- 受影响常规回归59项通过；随后执行 mvn -s .mvn/settings.xml -Pfull-media verify，本次第一批全量60项、失败0、错误0、跳过0；含实际1GiB、长视频、MQ恢复及物理回收。
- 本批通过后已自动进入后续契约校验；60项为阶段结果，不替代后续修改后的最终验收。

## 后续批次

第一批60项通过后自动进入并完成以下批次。原D来源保留为实施设计，未写成用户逐项选择。

| 批次 | 实际红测发现与修复 | 当前证据 |
| --- | --- | --- |
| JSON类型 | 标量被转换后误接受或落入错误业务分支；禁止数字/布尔转字符串及字符串/小数/布尔转整数 | contract-red-types.log；类型矩阵通过 |
| 可省略与null | 创建显式null误成功，资料含null时发生部分更新；区分创建默认[]及资料字段null拒绝 | contract-red-null.log；合法省略／拒绝及事务回滚通过 |
| 登录结构与ID | 缺字段原401，合法100码点ID原400；修正结构400及码点／Unicode空白校验 | contract-red-login.log、contract-red-ids.log；登录401边界、100/101项、去重隐私通过 |
| 传输错误 | 405/415/500无稳定code，416返回非JSON文件响应；补齐错误体并在响应文件前验证Range | contract-red-transport.log；错误体、Allow、206及416通过 |
| 查询／上传类型 | 空分页／排序被默认值吞掉；分片text/plain被接受、图片JSON返回错误分支；显式校验参数与consumes | contract-red-query.log、contract-red-upload-type.log；正确默认、未知参数忽略、multipart缺文件400通过 |
| 其余闭环回归 | 对已正确行为补强，不虚构红测 | 字段白名单、评论／历史条件字段、回复分页、分片替换后实际字节、重复complete、收藏并发／排序、热榜原位置元数据、真实Redis故障503及原凭证恢复均通过 |

- Q-054 的两种错误码表达出现歧义时已立即询问；用户A后写入 DEC-061，并新增 jsonTypeErrorsAndParsedAttachmentCollectionErrorsAreDistinct，原稿不因拒绝请求变化。
- 500异常由测试专用入口触发，通过真实HTTP验证，不模拟内部Service；该入口只在测试上下文中注册，已检查不包含在生产JAR。
- 测试预期故障会产生日志ERROR；不等同于测试失败，最终以Surefire XML断言结果为准。

## 最终执行证据

命令：mvn -s .mvn/settings.xml clean -Pfull-media verify。实际执行使用本机已有Maven缓存及可选本地缓存目录参数，未新增依赖或更换技术栈。

| 测试类 | 执行 | 失败 | 错误 | 跳过 |
| --- | --- | --- | --- | --- |
| ArchitectureTest | 3 | 0 | 0 | 0 |
| BootstrapAcceptanceTest | 1 | 0 | 0 | 0 |
| ForumHttpTest | 24 | 0 | 0 | 0 |
| RemainingFlowsTest | 7 | 0 | 0 | 0 |
| CrossModuleAcceptanceTest | 6 | 0 | 0 | 0 |
| MediaFlowsTest（含large-media） | 11 | 0 | 0 | 0 |
| ContractImplementationTest | 8 | 0 | 0 | 0 |
| ContractValidationTest | 8 | 0 | 0 | 0 |
| ContractCoverageTest | 6 | 0 | 0 | 0 |
| 总计 | 74 | 0 | 0 | 0 |

- 本次完整验收重新执行实际1GiB分片上传、1800秒视频完整HLS解码、1801秒拒绝、RabbitMQ停机恢复、资源24h边界及物理回收、启动管理员竞态、并发与各状态权限；没有复用历史“通过”。
- 原业务Spec整份、DEC-049原文、建表及迁移脚本、pom.xml分别与实施前快照核对一致。原有效测试预期保留，其他3份原测试源文件按字节一致。
- 独立最终JAR在随机新建openGauss库验证预置账号、注册登录、图文资料、投稿驳回重提及通过、旧版本409、显式附件数组400、统一公开卡片、严格JSON和查询、互动评论及依赖检查。
- JAR重启后使用原Redis凭证读取资料、帖子计数、评论和头像文件成功；专属测试数据库、队列及临时进程已回收。
- 运行说明、API契约与当前实现已核对；未发现违反本期已确认业务规则及落地契约的未解决缺陷。未把本次通过解释为需求最终签署或未约定性能达标。

证据入口：

- [逐例结果、10项真实红测及不变量核对](contract-acceptance-results.json)
- 最终原始报告：target/surefire-reports；构建日志：target/contract-final-verify.log。
- 独立产物结果：target/contract-jar-smoke-result.json；应用日志：target/contract-jar-smoke-app.log。
- [契约](api-contract.md)、[差异关闭清单](api-gap-analysis.md)、[实施计划](implementation-plan.md)、[本地运行](../README.md)。
