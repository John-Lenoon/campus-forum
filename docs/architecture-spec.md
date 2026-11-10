# 后端分层规范

日期：2026-11-10。依据：用户要求先补充 Spec，再按 Controller、Service、Entity、Mapper 分层重构，改善可读性。

## 1. 范围及不变量

- 全部后端业务代码按本规范整理，不仅移动目录。
- 技术栈为 Java、Spring Boot、openGauss、Redis、RabbitMQ、MyBatis-Plus。技术栈变更须先询问用户，不自行决定。
- 保持接口路径、请求与响应、错误码、访问权限、业务状态、数据库结构及事务语义。
- 保持已确认业务规则、DEC-049、requirements.md 10.4 原文。
- 本次不增加产品功能；发现规则冲突或需要用户决策的问题立即提出。

## 2. 包及职责

| 包 | 职责 | 约束 |
| --- | --- | --- |
| campus.controller | HTTP 路由、参数绑定、认证入口调用及 HTTP 响应封装 | 只调用 Service；不执行 SQL、不依赖 Mapper、不声明业务事务 |
| campus.service | 用例编排、业务校验、状态转换、授权判断、事务边界 | 调用 Mapper 访问数据库；不得依赖 Controller 或直接使用 JDBC |
| campus.mapper | 数据库查询、写入、行锁、SQL 与结果集映射 | 按用户、帖子、评论、互动、资源、分片等对象命名方法；禁止提供任意 SQL 透传接口 |
| campus.entity | 有明确字段和类型的持久化对象及读取投影 | 不包含 HTTP 路由、SQL、Spring 服务或外部调用；数据库行不能在 Service 中以无类型 Map 读取 |
| campus.dto | 请求对象、响应专用对象及认证上下文 | 与数据库实体区分；保留原 JSON 契约 |
| campus.config | Spring Bean、定时任务开关、队列声明及启动初始化 | 数据库初始化通过专用 Mapper；不得混入 HTTP 接口 |
| campus.infrastructure | FFmpeg、文件处理等外部技术适配 | 不承担业务用例或 HTTP 路由 |
| campus.job | 定时调度与 MQ 消费入口 | 调用 Service；不直接访问数据库或重复实现业务规则 |
| campus.exception / campus.support | 业务异常、HTTP 异常映射、纯校验工具 | 业务层不得依赖 HTTP 异常处理器 |

## 3. 数据访问及事务

- 用户明确指定 MyBatis-Plus；数据库访问通过 MyBatis-Plus Mapper 完成，不使用 JdbcTemplate、RowMapper 或应用代码直接操作 JDBC。
- 常规持久化采用 MyBatis-Plus 实体映射和 BaseMapper；复杂查询、行锁及条件更新使用具名 Mapper 方法，SQL 集中在 Mapper 对应的映射定义中。
- 用户、帖子、评论、资源为单主键实体，Mapper 继承 BaseMapper；互动、视频分片保留既有联合主键，以具名映射操作，不虚构单列主键套用 BaseMapper。
- Mapper 返回 Entity、明确字段的投影、标量或其集合；禁止数据库结果 Map 穿透到业务层。
- Service 保留原有事务、锁顺序、提交后消息投递、幂等和竞争裁决语义。
- 同一业务事务的跨 Mapper 操作使用 Spring 管理的同一事务连接。
- 账号预置、必要增列在监听端口前完成，不改变启动竞态修复。
- Redis 凭证和热榜快照属于现有 Service 的技术协作，不伪装成关系数据库 Mapper。

## 4. 可读性

- 每个公开顶层类型独立文件，类名体现职责；不在业务服务文件中夹带 Controller 或配置类。
- Controller 保持薄层，资料修改、用户公开信息查询、依赖检查、文件元数据等业务移入 Service。
- Service 之间通过业务接口协作；评论读取作者资料调用用户 Service，不调用用户 Controller。
- 使用构造器注入；使用常规缩进及分行，避免多语句挤在一行。
- Entity 表达持久化数据；API 响应组合可保留现有 Map 形式，不强制改变返回协议。

## 5. 验收

- 静态架构检查覆盖生产代码：目录与 package 一致、职责类独立、Controller 不越层、没有 JdbcTemplate／直接 JDBC 操作、MyBatis-Plus Mapper 返回类型明确、Service 不依赖 Controller。
- 编译全部代码并执行原有真实 HTTP 集成验收；不通过模拟 Mapper 返回值替代业务回归。
- 全量回归包含 large-media 的实际 1 GiB 上传、真实媒体处理、RabbitMQ 恢复、启动竞态及物理清理。
- 更新现行技术设计、实施计划和本次重构验收记录；历史验收保留日期与历史效力。

## 6. 依赖定位

- 使用 mybatis-plus-spring-boot3-starter 3.5.18，匹配项目现有 Spring Boot 3；引入依据为 [MyBatis-Plus 官方安装说明](https://baomidou.com/getting-started/install/)。
- 不另外引入 MyBatis starter、ORM、Lombok 或其他业务框架。
- openGauss 驱动仍是数据库底层连接依赖；应用不直接编写 JDBC 操作。
