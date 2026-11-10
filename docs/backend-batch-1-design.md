# 第一批后端设计与实施契约

状态：第一批历史设计快照。现行全量契约见 backend-design.md；本批历史结果见 batch-1-acceptance.md，整体结果见 final-acceptance.md。

## 1. 范围

- 注册、普通用户／管理员登录、纯文字投稿、审核列表及审核裁决、公开列表／详情、作者读取本人非公开帖与驳回原因。
- 暂不实现资料修改、编辑重提、删除、附件、互动、评论、热榜、独立用户查询；总体范围不删减。
- 默认头像为随应用提供的静态 SVG，不属于用户上传资源。
- DEC-049 与需求 10.4 是整体验收依据；本批仅形成阶段结果。

## 2. 技术方案

| 层次 | 选择 | 原因 |
| --- | --- | --- |
| 服务 | Java 21 / Spring Boot 3.5.16 / Maven | 本地工具兼容，单服务完成闭环 |
| HTTP | Spring MVC / JSON | 明确身份、角色、可见性与错误契约 |
| 数据 | openGauss / Spring JDBC | 真实数据库约束与行锁审核，不引入 ORM 自动建模 |
| 凭证 | Redis / 256 位随机 Bearer token | 固定 TTL 和绝对过期，无刷新／注销接口 |
| 消息 | Spring AMQP / RabbitMQ | 本批只接入依赖就绪检查；无新增业务事件 |
| 密码 | PBKDF2-HMAC-SHA256 | 独立盐、600,000 次迭代，不存明文 |
| 测试 | JUnit / Spring Boot 随机端口 / TestRestTemplate | 通过公开 HTTP 行为验证真实数据库和 Redis |

- JDBC 依赖为 org.opengauss:opengauss-jdbc:5.0.0，该实际 JAR 的驱动类是 org.postgresql.Driver，连接前缀 jdbc:postgresql；服务器仍为真实 openGauss，不能仅凭类名认定使用 PostgreSQL 服务。
- Docker 镜像固定 7.0.0-RC3.B025，来自官方仓库已存在的标签；属于本地验证构建，不承诺生产环境可用性。
- 数据库与 Redis 失败返回 503，不静默退化为内存数据或允许未认证请求。
- 审核、投稿的正确性不依赖 RabbitMQ；不在数据库事务中进行 MQ 双写。
- 凭证仅随机原值交给调用方；Redis key 保存 SHA-256 摘要，value 为用户 ID 和绝对过期毫秒。
- 所有查询动态值参数绑定；排序字段由服务固定，避免拼接客户端 SQL。
- 来源：[Boot 兼容性](https://docs.spring.io/spring-boot/3.5/system-requirements.html)、[openGauss 容器安装](https://docs.opengauss.org/en/docs/latest/installation_guide/installing_the_container_image.html)、[Spring Redis](https://docs.spring.io/spring-data/redis/reference/redis/template.html)、[Spring AMQP](https://docs.spring.io/spring-amqp/reference/amqp/template.html)。

## 3. 通用契约

- JSON UTF-8；未知字段返回 400；字符串长度按 Unicode 码点，不按字节或 UTF-16 单元。
- 账号保留输入大小写展示，唯一键为 ASCII 小写。注册仅 USER，admin 名称已被预置账号占用。
- 昵称省略时使用用户名；显式空白昵称拒绝。密码原值不修剪、不截断。
- 标题 1—100、正文 1—10,000，非空白；保存纯文本及换行。
- 所需凭证格式 Authorization: Bearer <token>；有效期固定 86,400 秒，不因读取延长。
- 时间为 UTC epoch 毫秒，ID 为 UUID 字符串。
- 错误体 {"code":"稳定错误码"}，不返回数据库语句、堆栈、哈希或凭证。
- 400 参数错误；401 认证缺失／失效；403 角色禁止；404 不存在／不可见；409 用户名重复／审核版本或结论冲突；503 依赖不可用。
- 列表 page 从 1 开始、size 默认 20、最大 100，非法参数 400；返回 items、page、size、total。
- 公开列表按 firstPublishedAt DESC、id DESC；审核列表按 submittedAt DESC、id DESC。
- 普通最新流为 offset 分页；并发新增下可能跨页变化，不套用热榜固定排序规则。

## 4. 本批 HTTP 接口

| 方法与路径 | 权限 | 请求 | 成功 |
| --- | --- | --- | --- |
| POST /api/v1/auth/register | 匿名 | username,password,nickname? | 201；id,nickname,role,avatarUrl |
| POST /api/v1/auth/login | 匿名 | username,password | 200；token,expiresAt,user |
| POST /api/v1/posts | USER | title,body | 201；完整帖子，初始 PENDING、revision=1 |
| GET /api/v1/posts?page=1&size=20 | 匿名 | 分页 | 200；只含 PUBLISHED 的摘要列表 |
| GET /api/v1/posts/{id} | 可选认证 | 无 | 200；公开帖任意人可读，非公开仅作者／管理员 |
| GET /api/v1/admin/posts?status=PENDING&page=1&size=20 | ADMIN | status 可省略；允许 PENDING/PUBLISHED/REJECTED | 200；未删除帖列表 |
| POST /api/v1/admin/posts/{id}/review | ADMIN | revision,decision=APPROVE/REJECT,reason? | 200；最新帖子 |
| GET /api/v1/admin/dependencies | ADMIN | 无 | 200；openGauss、Redis、RabbitMQ 连通；任一失败 503 |
| GET /assets/default-avatar.svg | 匿名 | 无 | 200 image/svg+xml |

完整帖子字段：id,title,body,status,revision,submittedAt,firstPublishedAt,author,likeCount,favoriteCount；非公开作者／管理员可读时增加 rejectionReason。author 仅 id,nickname,avatarUrl。本批无互动写入，两个计数均为 0；不得称已经实现互动模块。

公开摘要不返回正文、驳回原因、审核者或凭证。详情对无权调用者与不存在目标均返回 404。提交非空附件或角色／作者／状态字段作为未知字段 400，不静默忽略。

审核 REJECT 的 reason 必填非空白、最多 1,000 码点；APPROVE 不携带 reason。同稿相同已完成结论重试返回已有结果，不更新原因或发布时间；相反结论或 revision 不匹配返回 409。

## 5. 数据设计

### forum_users

| 列 | 类型／约束 |
| --- | --- |
| id | VARCHAR(36)，主键 |
| username | VARCHAR(32)，必填，原始大小写 |
| username_key | VARCHAR(32)，必填唯一，小写规范化 |
| password_hash | VARCHAR(256)，必填，含盐与参数编码结果 |
| nickname | VARCHAR(32 CHAR)，必填，显式字符语义 |
| role | VARCHAR(8)，USER／ADMIN 检查约束 |
| created_at | BIGINT，必填 |

### forum_posts

| 列 | 类型／约束 |
| --- | --- |
| id | VARCHAR(36)，主键 |
| author_id | 外键 forum_users(id)，必填 |
| title | VARCHAR(100 CHAR)，必填，显式字符语义 |
| body | TEXT，必填；服务检查码点长度 |
| status | VARCHAR(10)，PENDING／PUBLISHED／REJECTED |
| revision | INTEGER，必填，初始 1；本批无修改入口 |
| submitted_at | BIGINT，必填 |
| first_published_at | BIGINT，可空，首次通过赋值 |
| rejection_reason | VARCHAR(1000 CHAR)，仅本轮原因，可空 |
| reviewed_by | 用户外键，可空 |
| reviewed_at | BIGINT，可空 |

- 索引：(status, first_published_at, id)、(status, submitted_at, id)。
- 不建立附件、互动、评论或审核历史表。
- schema.sql 为本批可重复初始化基线；后续更改需版本迁移，不能把 IF NOT EXISTS 当作升级机制。
- 早期开发库的字节长度列通过 db/migrations/V002__character_semantics.sql 非破坏迁移；新库基线已修正。依据：[openGauss 字符类型](https://docs.opengauss.org/en/docs/7.0.0/sql_reference/character_types.html)。
- 初始化只补建 admin，不重置已有账号密码，不预置普通用户。
- 测试数据使用随机账号与独立测试数据库，不清空开发数据。

### 审核事务

1. 按 ID SELECT FOR UPDATE，锁住当前稿件。
2. 不存在返回 404；版本不符返回 409。
3. 已完成同结论返回原结果；相反结论返回 409。
4. PENDING 更新为 PUBLISHED 或 REJECTED；首次通过仅在为空时设置 first_published_at。
5. 提交事务后返回结果；异常整体回滚。
6. 已提交后公开查询立即按当前状态过滤，不经过帖子缓存。

## 6. TDD 任务清单与边界

自主执行授权覆盖以下公开 HTTP 测试边界（IMPL-008），不再为普通边界逐项发起访谈。

每个行为独立执行：先编写用例并确认失败 → 只实现该行为 → 单项通过 → 回归已有用例。不得将一组批量测试后的集中实现描述为逐条 TDD。

| 次序 | 行为用例 | 对应验收 |
| --- | --- | --- |
| 1 | 注册普通用户、默认昵称与头像、不返回密码 | AC-01 |
| 2 | 登录返回固定 24h 凭证；错误密码拒绝 | AC-01 |
| 3 | USER 发布纯文字，初始待审且访客不可读 | AC-02、03 |
| 4 | ADMIN 通过后访客可读且首次时间正确 | AC-04、13 |
| 5 | ADMIN 驳回原因、作者可读、其他人 404 | AC-02、04 |
| 6 | 同稿重试幂等、相反结论及旧 revision 冲突 | AC-04 |
| 7 | 并发审批只生效一个结论 | AC-04 |
| 8 | 注册／文本边界、身份伪造及角色越权 | AC-01、02、10 |
| 9 | 列表分页、状态过滤、确定排序 | AC-13 |
| 10 | 凭证到期且读取不续期、真实依赖就绪 | AC-01及实施可靠性 |

- 普通业务断言只观察 HTTP；不用数据库查询替代外部行为断言。
- 真实 openGauss／Redis，不模拟内部 Service／DAO。
- 过期测试替换 Clock，正式期限仍为 24 小时。
- 编译、测试、打包、启动与接口说明复核完成后再形成阶段验收报告。
