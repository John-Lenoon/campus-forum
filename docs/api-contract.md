# API 契约

版本：1.0 落地基线。日期：2026-11-10。按 DEC-060 执行测试、实现和回归；本次最终结果见 [contract-acceptance.md](contract-acceptance.md)。

## 1. 效力与依据

- 业务依据：[requirements.md](requirements.md) 的 BR-01—46、AC-01—15，以及 [decision-log.md](decision-log.md)。
- DEC-049 和需求 10.4 原文不变；本文不降低整体验收门槛。
- 技术栈沿用 Java、Spring Boot、MyBatis-Plus、openGauss、Redis、RabbitMQ；不新增依赖。
- 后续流程固定为：业务 Spec → API 契约 → 测试与实现 → 验收及文档核对（DEC-056）。
- 现有代码、测试、backend-design.md 仅供对照；代码有此行为不等于用户确认此规则。
- 实现偏差及验证计划见 [api-gap-analysis.md](api-gap-analysis.md)。本稿不代表接口已通过本契约验收。

### 1.1 规则来源标记

| 标记 | 含义 | 使用方式 |
| --- | --- | --- |
| B | 原业务 Spec／用户已确认决策 | 必须保留，不因代码现状改变 |
| U | 本轮用户明确选择 | DEC-057/058/059/061，属于新补充或边界澄清 |
| I | DEC-051/053 授权内、已有 IMPL 编号的实施决定 | 可以沿用，但不写成用户逐项确认 |
| D | 契约阶段提出的接口表达及校验设计 | 按 DEC-060 作为落地依据；保留设计来源，不写成用户逐项确认或代码事实自动升级 |

下文路径、HTTP 方法、JSON 字段名、成功状态和错误码的表达属于 D，除非明确引用 B/U/I。
同样的业务规则可以有多种接口表达；选用现有可用表达，是降低迁移成本的设计建议，不作为确认依据。

### 1.2 本轮已明确的约束

| 问题 | 用户选择 | 契约约束 |
| --- | --- | --- |
| Q-051 | A；DEC-057 | 修改帖必须显式传 resourceIds 数组；[] 清空；省略或 null 返回 400 |
| Q-052 | A；DEC-058 | 公开最新流与热榜统一使用作品卡片，不返回 liked/favorited；个人状态查询帖子详情 |
| Q-053 | A；DEC-059 | 修改帖必须传当前 revision；版本不匹配 409，成功后递增，旧请求不能再次改稿下架 |

上述三项已确认，不再重复提问。完整 PostCard 等 D 表达按 DEC-060 纳入本次实现与验证；Q-054 的错误码边界另由 DEC-061 明确。

## 2. 通用协议

### 2.1 路径、类型和空值

- 除系统默认头像外，表中路径统一加前缀 `/api/v1`。
- JSON 请求及响应使用 UTF-8；JSON 请求为 `application/json`，成功结果直接返回对象，不增加 data/code 外层包装（D）。
- 请求字段“必填”指必须出现；“可空”指是否接受显式 JSON null。省略、null、空串和空数组分别定义，不能互相替代。
- 响应字段“必有”指键必须出现；可空字段显式返回 null。条件字段在条件不满足时省略，不使用空串伪装 null（D）。
- 没有默认值的必填字段不得由客户端省略；响应值由服务器计算，不接受客户端覆盖。
- JSON 未知字段拒绝，不能传入 role、authorId、status 等未定义字段（I：IMPL-007）。
- D：JSON 字符串不得由数字／布尔隐式转换；整数不得由字符串／小数／布尔隐式转换；数组元素也按声明类型校验。现有支持情况另列差异。
- 字符串保持原文，不自动 trim、截断或格式化；长度按 Unicode 码点计数。非空白包括 Unicode 空格；拒绝 NUL 和不完整代理字符（I：IMPL-018）。
- D：只定义表中查询参数，未知查询参数忽略；空的必需查询值非法，不把空串默认为枚举或合法 ID。

| 公共类型 | JSON 类型／范围 | 必填／可空／默认规则 |
| --- | --- | --- |
| ID | string；服务端生成 UUID 字符串（I：IMPL-006） | 路径 ID 必填、不可空、无默认；按不透明标识使用。无匹配目标统一 404，不因不是 UUID 单独泄漏目标信息（D） |
| Timestamp | integer(int64)，UTC epoch 毫秒（I：IMPL-006） | 必有性／可空性由各模型规定；无客户端默认 |
| Count | integer(int64)，≥0 | 必有、不可空；实际计数，不能用固定值替代 |
| URL | string，同源相对访问地址（D） | 必有、不可空；不能据此绕过权限；资源未 READY 时不保证文件可读 |
| Boolean | boolean | 必有时不可用 null／0／1／字符串代替；条件字段规则见模型 |

### 2.2 认证及角色

| 权限标记 | 凭证要求 | 语义 |
| --- | --- | --- |
| 匿名 | 无须 Authorization | 不读取个人身份；公开列表按 DEC-058 不携带本人互动状态 |
| 可选登录 | 可省略；提供时必须有效 | 省略按访客；提供无效／到期凭证返回 401，不悄悄改成访客（D，沿用已有读取语义） |
| 登录 | 必须有效 | USER 或 ADMIN；只允许该接口定义的操作 |
| USER | 必须是普通用户 | ADMIN 返回 403；管理员不能投稿、上传普通用户资源或互动（B：BR-28） |
| ADMIN | 必须是管理员 | USER 返回 403（B：BR-01、DEC-051） |
| 作者／评论者 | 先认证，再验证归属 | 作者仅本人帖子；评论者仅本人评论；无权目标统一 404（B：DEC-051） |

- 凭证为 `Authorization: Bearer <token>`；token 是 64 位小写十六进制随机值（I：IMPL-003）。
- 登录成功起固定 24h，到期重登；读取不续期，不增加 refresh/logout 接口（B：BR-40）。
- 必需认证缺失或失效 401，角色禁止 403，详情不存在或不可见 404（B：DEC-051）。
- 帖子 PENDING/REJECTED 的详情、附件、评论仅作者及 ADMIN 可读取；已发布公开；删除后所有角色禁读，仅本人评论历史保留例外（B）。
- 表中业务错误按结构正确、其他前置条件满足的单一错误场景定义；多个错误同时存在时，不保证业务校验先后顺序。不得以异常返回被拒绝对象的正文或密钥（D）。

### 2.3 分页与排序

| 参数 | 位置 | 类型 | 必填 | 可空 | 省略默认 | 校验 |
| --- | --- | --- | --- | --- | --- | --- |
| page | query | integer(int32) | 否 | 否 | 1 | 1—2147483647 |
| size | query | integer(int32) | 否 | 否 | 20 | 1—100 |
| order | 评论／回复 query | string enum | 否 | 否 | asc | 仅 asc/desc，大小写严格 |

默认与上限依据 DEC-051、IMPL-010。不可解析数值为 400 INVALID_REQUEST，已解析但越界为 400 INVALID_PAGE；非法 order 为 INVALID_ORDER（D）。

分页响应 `Page<T>`：

| 字段 | 类型 | 必有 | 可空 | 值／默认 |
| --- | --- | --- | --- | --- |
| items | `array<T>` | 是 | 否 | 实际当前页；无记录／越界页为 []，元素不可空 |
| page | integer(int32) | 是 | 否 | 本次页码 |
| size | integer(int32) | 是 | 否 | 本次页大小 |
| total | Count | 是 | 否 | 本次查询符合可见性及过滤条件的记录数；热榜例外见 F01 |

| 列表 | 固定排序 | 依据 |
| --- | --- | --- |
| 公开最新帖 | firstPublishedAt DESC，id DESC | BR-04、DEC-051 |
| 管理全部状态／待审帖 | submittedAt DESC，id DESC | BR-04、DEC-033 |
| 本人点赞／收藏 | 本次关系建立时间 DESC，postId DESC；取消重建使用新时间 | IMPL-010、BR-24 列表 |
| 根评论／每串回复 | createdAt、id 同方向；默认 ASC，可选 DESC；分别分页 | BR-14、IMPL-010 |
| 本人评论历史 | 原 createdAt DESC，id DESC | BR-22、IMPL-010 |
| 热榜 | 本次快照顺序；新榜按累计有效赞＋2×收藏 DESC，再 firstPublishedAt DESC、id DESC | BR-41—44、IMPL-012 |

普通 offset 列表不承诺跨请求快照；热榜有独立快照。分页字段不代表容量或性能承诺。

## 3. 账号注册登录 → 本人资料／独立用户查询

依据：BR-25、27、28、30、31、40、45；DEC-051；AC-01/02。

### 3.1 公共用户模型

| 模型／字段 | 类型 | 必有 | 可空 | 值／默认 |
| --- | --- | --- | --- | --- |
| PublicUser.id | ID | 是 | 否 | 目标用户 ID |
| PublicUser.nickname | string | 是 | 否 | 当前昵称；注册省略时取用户名 |
| PublicUser.avatarUrl | URL | 是 | 否 | 当前头像；初始 `/assets/default-avatar.svg` |
| SelfUser | PublicUser 的全部字段 | 是 | 按上述定义 | 注册、登录及本人资料专用 |
| SelfUser.role | string enum | 是 | 否 | USER/ADMIN；只能由服务端赋予 |

PublicUser 不含 username、role、密码、密码哈希或 token（B：BR-45）；公开帖子／评论作者也使用 PublicUser。

### U01｜POST /auth/register

权限：匿名。请求 JSON：

| 字段 | 类型 | 必填 | 可空 | 默认 | 校验／来源 |
| --- | --- | --- | --- | --- | --- |
| username | string | 是 | 否 | 无 | `[A-Za-z0-9_]{4,32}`，不分大小写唯一（B：DEC-051） |
| password | string | 是 | 否 | 无 | 8—64 码点（B）；不截断。非全空白为本稿建议的输入策略（D），不是新增已确认强度要求 |
| nickname | string | 否 | 是 | username | 非 null 时 1—32 码点且非全空白；省略／null 取用户名（B 的“未填”规则，null 表达为 D） |

- 成功：201，SelfUser，role 固定 USER；不自动签发 token，须调用 U02（D）。
- 拒绝：400 INVALID_USERNAME/INVALID_PASSWORD/INVALID_NICKNAME；409 USERNAME_EXISTS。
- 不提供校园验证字段；不允许客户端注册 ADMIN。
- 不承诺按请求键幂等：重复用户名返回 409，不创建第二账号（D）；唯一性仍是 B。

### U02｜POST /auth/login

权限：匿名。

| 字段 | 类型 | 必填 | 可空 | 默认 | 校验 |
| --- | --- | --- | --- | --- | --- |
| username | string | 是 | 否 | 无 | 原用户名；不区分大小写，不 trim |
| password | string | 是 | 否 | 无 | 原密码，逐字验证，不归一化；不增加强度校验替代密码比对 |

成功 200：

| 字段 | 类型 | 必有 | 可空 | 值／默认 |
| --- | --- | --- | --- | --- |
| token | string | 是 | 否 | 新随机凭证；64 位小写 hex |
| expiresAt | Timestamp | 是 | 否 | 本次签发时间＋24h |
| user | SelfUser | 是 | 否 | 本人身份；不会返回密码 |

- 缺字段／错误 JSON 类型：400 INVALID_REQUEST（D）；结构合法但账号或密码不匹配：401 INVALID_CREDENTIALS。
- 重复登录可产生多个独立凭证；不撤销其他有效凭证（D，原 Spec 未规定单点登录）。
- 管理员初始 admin/admin123，必须在对外接受注册前预置（B：BR-25；I：IMPL-020）。

### U03｜GET /me

- 权限 USER；无业务请求字段；200 SelfUser。
- 401／403 按通用认证；不提供管理员账号管理功能。

### U04｜PATCH /me

权限 USER。请求 JSON：

| 字段 | 类型 | 必填 | 可空 | 省略默认 | 校验 |
| --- | --- | --- | --- | --- | --- |
| nickname | string | 至少一项 | 否 | 保持原值 | 1—32 码点，非全空白 |
| avatarResourceId | ID | 至少一项 | 否 | 保持原值 | 本人 AVATAR、IMAGE、READY、未失效且未退休的资源 |

- 两项都省略：400 INVALID_PROFILE；显式 null 不表示清空／重置，建议返回 400（D）。不新增“恢复默认头像”操作。
- 两项同一次提交，全部成功或全部不改变；200 SelfUser，立即反映更新。
- 失败：400 INVALID_NICKNAME/INVALID_AVATAR/INVALID_PROFILE；资源不可用不得先提交昵称更新。
- 替换头像无需审核，新头像立即公开，旧资源即停访且不可复用；原资源身份重复指定为当前头像可成功且无变化（D）。

### U05｜GET /users/{id}

- 权限登录；路径 id：ID、必填、不可空、无默认。
- 200 PublicUser；401 认证拒绝；不存在 404 NOT_FOUND。
- 与公开作者信息不同，不能匿名调用；ADMIN 可执行该管理查询范围内的读取。

### U06｜POST /users/batch

权限登录。请求 JSON：

| 字段 | 类型 | 必填 | 可空 | 默认 | 校验 |
| --- | --- | --- | --- | --- | --- |
| ids | `array<string>` | 是 | 否 | 无 | 最多 100 个输入项（去重前）；元素非 null、非空白且最多 100 码点；允许 []（D；上限及去重见 IMPL-010） |

成功 200：

| 字段 | 类型 | 必有 | 可空 | 值／默认 |
| --- | --- | --- | --- | --- |
| items | `array<PublicUser>` | 是 | 否 | 有记录的去重用户；按输入首次出现顺序（D）；无记录为 [] |
| missingIds | `array<string>` | 是 | 否 | 未找到的去重 ID；按输入首次出现顺序（D）；无缺失为 [] |

无效集合 400 INVALID_IDS。合法但不存在的 ID 放入 missingIds，不把整批变成 404；不返回额外身份字段（I：IMPL-010；B：BR-45）。

## 4. 投稿 → 审核／修改重提 → 公开浏览 → 删除

依据：BR-02—07、17—20、23—26、29、38、39、46；AC-03—06、10、13；DEC-057/058/059。

### 4.1 帖子读取模型

`PostBase` 字段全部必有：

| 字段 | 类型 | 可空 | 值／默认 |
| --- | --- | --- | --- |
| id | ID | 否 | 帖子 ID |
| title | string | 否 | 原标题，1—100 码点 |
| status | string enum | 否 | PENDING/PUBLISHED/REJECTED；删除不作为可查询业务状态 |
| revision | integer(int32) | 否 | 首稿 1，每次成功修改＋1 |
| submittedAt | Timestamp | 否 | 最近一次成功提交审核时间 |
| firstPublishedAt | Timestamp | 是 | 从未通过为 null；首次通过后固定，重审不重置 |
| author | PublicUser | 否 | 当前作者 ID、昵称及头像 |
| likeCount | Count | 否 | 当前有效点赞数 |
| favoriteCount | Count | 否 | 当前有效收藏数 |

`PostDetail` = PostBase 加以下字段：

| 字段 | 类型 | 必有 | 可空 | 值／默认 |
| --- | --- | --- | --- | --- |
| body | string | 是 | 否 | 原正文，1—10000 码点，保留换行 |
| attachments | `array<ResourceSummary>` | 是 | 否 | 当前附件；纯文字 []；按资源创建时间、ID 正序（D） |
| liked | boolean | 是 | 否 | 当前认证用户对该帖的状态；匿名／ADMIN 为 false（D 的表示方式） |
| favorited | boolean | 是 | 否 | 同上 |
| rejectionReason | string | 非公开必有；PUBLISHED 必须省略 | 是 | PENDING 为 null；REJECTED 为当前原因；不暴露历史原因 |

`PostCard`（D 的具体字段集合，落实 DEC-058）：PostBase＋必有 attachments:`array<ResourceSummary>`；不可 null，纯文字允许 []；仅 PUBLISHED，firstPublishedAt 必非 null。
不含 body、rejectionReason、liked、favorited。最新流和热榜使用同一个 PostCard；卡片正文详情通过 P05 读取。

`AdminPostItem`（D）：PostBase＋必有 body:string（不可 null）、必有 rejectionReason:string|null；不包含互动个人状态。PENDING/PUBLISHED 原因为 null，REJECTED 为当前原因；详情及附件另查 P05。

### P01｜POST /posts

权限 USER。请求 JSON：

| 字段 | 类型 | 必填 | 可空 | 默认 | 校验 |
| --- | --- | --- | --- | --- | --- |
| title | string | 是 | 否 | 无 | 1—100 码点，非全空白，纯文本 |
| body | string | 是 | 否 | 无 | 1—10000 码点，非全空白，纯文本且保留换行 |
| resourceIds | `array<ID>` | 否 | 否 | [] | 0—9 项，元素不可空、不重复；完整绑定校验见 5.1；null 不视为 []（D） |

- 201 PostDetail：PENDING、revision=1、firstPublishedAt=null、rejectionReason=null；公开查询不能读取。
- 400 INVALID_TITLE/INVALID_BODY/INVALID_RESOURCES/INVALID_RESOURCE/MIXED_MEDIA。
- 帖子与全部附件关联原子成功；失败不产生残缺帖。
- POST 不提供 Idempotency-Key 去重承诺（D）；网络结果不明时不能假定重发不会重复投稿。

### P02｜PUT /posts/{id}

权限：作者 USER；id 必填 ID。请求 JSON：

| 字段 | 类型 | 必填 | 可空 | 默认 | 校验／依据 |
| --- | --- | --- | --- | --- | --- |
| revision | integer(int32) | 是 | 否 | 无 | ≥1；必须等于当前稿件版本（U：DEC-059） |
| title | string | 是 | 否 | 无 | 同 P01；完整提交，不能省略表示不修改 |
| body | string | 是 | 否 | 无 | 同 P01 |
| resourceIds | `array<ID>` | 是 | 否 | 无 | 0—9 项；[] 明确移除全部；保留资源必须列入（U：DEC-057） |

- 当前 PENDING/PUBLISHED/REJECTED 均可提交；成功 200 PostDetail，revision＋1、status=PENDING、submittedAt 更新、当前驳回原因清空。
- 原 firstPublishedAt、点赞收藏、未删除评论保留。已发布稿在全部提交成功后才下架；失败或未提交不影响公开状态。
- 在同一事务中锁定当前稿、校验 revision，再提交改稿及资源关联；版本不匹配 409 STALE_REVISION，不能先解绑或下架。
- 缺失／null／非法版本：400 INVALID_REVISION（D 错误码）；附件数组省略／null／重复／含 null／超量：400 INVALID_RESOURCES。JSON 类型或反序列化失败统一 INVALID_REQUEST，不属于此处的集合业务校验（U：DEC-061）。
- 其余文本及绑定错误同 P01；无权／已删／不存在 404 NOT_FOUND。
- 旧版本重发返回 409，不能再次递增／下架；这保证重试不产生第二次改稿，不承诺重放原成功响应。两个同 revision 的并发修改仅一个成功（U：DEC-059）。

示例（假设当前 revision=2）：

```json
{"revision":2,"title":"修改稿","body":"新的正文","resourceIds":[]}
```

### P03｜POST /admin/posts/{id}/review

权限 ADMIN；id 必填 ID。请求 JSON：

| 字段 | 类型 | 必填 | 可空 | 默认 | 校验 |
| --- | --- | --- | --- | --- | --- |
| revision | integer(int32) | 是 | 否 | 无 | ≥1，最新稿版本 |
| decision | string enum | 是 | 否 | 无 | APPROVE/REJECT |
| reason | string | REJECT 时是 | REJECT 时否；APPROVE 可省略／null（D） | 无 | REJECT：1—1000 码点、非全空白；APPROVE 不得传非 null 原因（I：IMPL-007） |

- 200 PostDetail；APPROVE：PENDING→PUBLISHED；REJECT：PENDING→REJECTED。
- 同稿首次成功结果生效；相同 decision 重试成功，保留首次原因及审核时间，不更新为重试原因；相反 decision 为 409 REVIEW_CONFLICT。
- 旧 revision 为 409 STALE_REVISION；已删／不存在 404 NOT_FOUND。格式非法 400 INVALID_REVIEW，驳回原因非法 INVALID_REASON。
- 首次通过设置 firstPublishedAt，后续重审通过保持原值；审核并发与作者修改按同一稿件锁串行裁决。

### P04｜GET /posts

- 权限匿名；query：page、size，见 2.3；无其他业务过滤参数。
- 200 `Page<PostCard>`，只含当前已发布且未删除的作品；排序见 2.3。
- 不返回 liked/favorited，不解析凭证取得个人状态（U：DEC-058；D：匿名协议表达）。
- 400 INVALID_PAGE／INVALID_REQUEST；不存在页面为 200 空 items，不能把未发布稿作为占位返回。

### P05｜GET /posts/{id}

- 权限可选登录；路径 id 必填 ID；200 PostDetail。
- 已发布公开；PENDING/REJECTED 只允许作者和 ADMIN；已删包括 ADMIN 在内均 404。
- 401 无效已提供凭证；404 NOT_FOUND 不区分不存在和无权读取。
- 个人互动状态与计数按本次读取时的已完成操作返回（B：BR-12/44）。

### P06｜GET /admin/posts

权限 ADMIN；query 为 page、size，另有：

| 字段 | 类型 | 必填 | 可空 | 默认 | 校验 |
| --- | --- | --- | --- | --- | --- |
| status | string enum | 否 | 否 | 不过滤业务状态 | PENDING/PUBLISHED/REJECTED；空串非法 |

- 200 `Page<AdminPostItem>`；不传 status 查询全部未删除业务状态；`status=PENDING` 即待审列表。
- 排序按最近提交审核时间，不提供已删除原内容查询。
- 400 INVALID_STATUS／INVALID_PAGE；401/403 通用认证。

### P07｜DELETE /posts/{id}

- 权限登录；作者删本人三种未删除状态，ADMIN 管理删除；id 必填。
- 200 DeletedResult：`{"deleted":true}`；deleted 必有 boolean、不可空、成功恒 true（D）。
- 成功后公共／管理／本人互动列表与旧热榜过滤该帖；原帖子、讨论和全部附件对所有角色禁读；本人评论历史例外保留。
- 重复请求不恢复或重复产生副作用；已删除时 404 NOT_FOUND（D），不承诺 DELETE 重复响应恒为 200。
- 其他用户／不存在同样 404；无凭证 401；不提供恢复入口。

### 4.2 状态矩阵

| 原状态 | 操作 | 目标状态／结果 |
| --- | --- | --- |
| 无 | 合法新投稿 | PENDING，revision=1 |
| PENDING/REJECTED/PUBLISHED | 作者当前 revision 合法修改 | PENDING，revision＋1；附件及内容一起提交 |
| 任意未删状态 | 作者过期 revision 修改 | 409，原稿及资源无变化 |
| PENDING | 最新稿 APPROVE | PUBLISHED |
| PENDING | 最新稿 REJECT | REJECTED，保存当前原因 |
| PUBLISHED/REJECTED | 同 revision 相同审核结论 | 200，保持首次结果 |
| PUBLISHED/REJECTED | 同 revision 相反结论 | 409 REVIEW_CONFLICT |
| 任意未删状态 | 旧稿审核 | 409 STALE_REVISION |
| 任意未删状态 | 作者／ADMIN 删除 | 不再可读；不生成可查询的“已删除稿” |
| 已删除 | 读取、修改、审核或互动 | 404；本人评论历史按 C06/C07 例外 |

## 5. 先上传资源 → 查询／续传 → 就绪后关联投稿或头像

依据：BR-08—10、26、29、31—37；AC-10—12；I：IMPL-014—017。

### 5.1 资源及关联规则

- 图片：POST 用途实际 JPG/PNG/WebP，单张 ≤10485760 字节；最多九张；作品动画 WebP 不套用静态头像限制。
- 头像：AVATAR 用途实际 JPG/PNG/WebP 静态图，单张 ≤5242880 字节；替换通过 U04，不经过帖子审核。
- 视频：POST 用途 MP4，完整文件 ≤1073741824 字节、0＜时长≤1800 秒；单帖至多一个；与图片不能混用。
- 投稿关联资源须本人、POST 用途、READY、未失效／未退休；不得绑定他人的资源、其他帖的资源或头像。
- 同帖修改可继续列出仍有效的原资源 ID；移除即停访、不可复用；不能移到其他帖。
- 不增加独立批量绑定接口；批量 resourceIds 随 P01/P02 一次原子提交（B：BR-26）。
- 1 MiB=1048576 字节，1 GiB=1073741824 字节；校验真实文件，不以文件名或声明 MIME 冒充合法格式。

### 5.2 资源响应模型

`ResourceSummary`：

| 字段 | 类型 | 必有 | 可空 | 值／默认 |
| --- | --- | --- | --- | --- |
| id | ID | 是 | 否 | 唯一资源 ID |
| type | string enum | 是 | 否 | IMAGE/VIDEO |
| state | string enum | 是 | 否 | UPLOADING/PROCESSING/READY/FAILED；IMAGE 成功时为 READY |
| size | integer(int64) | 是 | 否 | 字节数；视频初始化为声明完整大小，合并时复核一致 |
| mime | string enum | 是 | 否 | image/jpeg、image/png、image/webp、video/mp4；视频清单响应类型另见 R06 |
| url | URL | 是 | 否 | 图片原文件或视频 HLS 入口；非 READY 时不能据此播放 |
| originalUrl | URL | 是 | 否 | 原文件访问入口，不是无权限校验直链 |
| expiresAt | Timestamp | 是 | 是 | 未绑定上传／READY／FAILED 的截止时间；PROCESSING 和有效绑定为 null |
| errorCode | string | 是 | 是 | FAILED 时为处理错误码；其他状态为 null |

`ResourceInfo` = ResourceSummary；VIDEO 额外包含下列全部必有字段，IMAGE 不返回这些字段（D）：

| 字段 | 类型 | 可空 | 值／默认 |
| --- | --- | --- | --- |
| duration | number(double)，秒 | 是 | READY 时实际完整时长；处理成功前为 null |
| chunkSize | integer(int32) | 否 | 8388608（8 MiB，I：IMPL-017） |
| totalParts | integer(int32) | 否 | ceil(size/chunkSize)，1—128 |
| uploadedParts | `array<integer(int32)>` | 否 | 已成功的零基分片序号，正序、无重复；初始 [] |

帖子附件使用 ResourceSummary，必须 READY、expiresAt=null、errorCode=null；不向公共帖子暴露上传进度或内部处理租约（D）。
过期不作为正常 200 的 EXPIRED 对象返回：本人查询临时过期结果为 410，已退休资源为 404；其他身份仍按不可见 404。

### R01｜POST /resources/images

权限 USER；`multipart/form-data`，query 与 multipart：

| 字段 | 位置 | 类型 | 必填 | 可空 | 默认 | 校验 |
| --- | --- | --- | --- | --- | --- | --- |
| purpose | query | string enum | 是 | 否 | 无 | POST/AVATAR |
| file | multipart | binary file | 是 | 否 | 无 | 单文件、非空；按用途真实格式及大小校验 |

- 成功 201 ResourceInfo（IMAGE），READY、未绑定，expiresAt 为成功就绪起 24h。
- 400 INVALID_PURPOSE/INVALID_IMAGE_SIZE/INVALID_MEDIA/INVALID_IMAGE/ANIMATED_AVATAR；缺文件或 multipart 结构错误 INVALID_REQUEST。
- 请求超出解析层大小上限 413 UPLOAD_TOO_LARGE；存储／工具不可用 503，不能创建假成功资源。
- 上传动作不承诺重试去重；重复合法上传生成独立 ID（D），最终由绑定及期限规则管理。

### R02｜POST /resources/videos

权限 USER；请求 JSON：

| 字段 | 类型 | 必填 | 可空 | 默认 | 校验 |
| --- | --- | --- | --- | --- | --- |
| size | integer(int64) | 是 | 否 | 无 | 1—1073741824，完整文件字节数，不是某片大小 |

- 成功 201 ResourceInfo（VIDEO）：UPLOADING、uploadedParts=[]、duration=null、errorCode=null。
- 首片上传前从任务创建起保留 24h；之后从最后一次成功进展起计时（D 的初始时点表达，B：24h 无进展规则）。
- 非法大小 400 INVALID_VIDEO_SIZE；错误 JSON 类型按通用 INVALID_REQUEST。
- 不承诺按文件去重；完成处理前不得提交为帖子附件。

### R03｜PUT /resources/{id}/chunks/{index}

权限：上传者 USER；原始二进制 `application/octet-stream`。

| 字段 | 位置 | 类型 | 必填 | 可空 | 默认 | 校验 |
| --- | --- | --- | --- | --- | --- | --- |
| id | path | ID | 是 | 否 | 无 | 本人未过期视频任务 |
| index | path | integer(int32) | 是 | 否 | 无 | 0≤index＜totalParts |
| 请求体 | body | binary | 是 | 否 | 无 | 长度必须恰好 min(chunkSize, size-index×chunkSize) |

- 200 ResourceInfo，上传状态仍为 UPLOADING；uploadedParts 反映已成功分片。
- 允许乱序；只有本片完整验证成功才记进度，不要求重传其他成功片。
- 相同成功分片内容重复提交不新增记录、不延长期限（I：IMPL-017）；同序号不同合法内容在 UPLOADING 时替换并算成功进展（D，既有代码策略待审阅）。
- 400 INVALID_PART/INVALID_PART_SIZE；不存在／非本人／非视频为 404；过期 410 RESOURCE_EXPIRED；已离开 UPLOADING 为 409 UPLOAD_CLOSED；存储故障 503。
- 客户端不提交服务端校验摘要；服务端计算 SHA-256，完成时逐片复核（I）。

### R04｜POST /resources/{id}/complete

- 权限上传者 USER；id 必填；无业务请求体。
- 已上传全部正确分片，合并大小及摘要一致后，UPLOADING→PROCESSING；200 ResourceInfo，表示任务已接受，不表示已可播放。
- 缺片 400 UPLOAD_INCOMPLETE；实际片损坏 PART_CORRUPT；失败不得把任务标为 READY。
- 重复 complete：已 PROCESSING/READY/FAILED 时返回当前 ResourceInfo，不重复启动处理或清除失败结果（D）。UPLOADING 以外不允许续传；失败后重新开始完整上传使用 R02，不新增“重新转码”产品接口。
- 数据库提交后投递 RabbitMQ，投递暂失败仍保留已接受任务并重投；不以 MQ 瞬时失败制造已回滚的假象（I）。
- 401/403、404、410、503 见通用及资源规则；不会让其他用户查询原上传内容。

### R05｜GET /resources/{id}

- 权限可选登录＋资源可见性：未绑定仅上传者；当前头像公开；帖子绑定后按帖子状态。
- 200 ResourceInfo，可查询分片进度、处理状态、失败码；仅 READY 的视频可关联投稿。
- PROCESSING 不计未绑定期限；READY 未绑定从就绪起 24h；FAILED 从失败起 24h。
- 本人已到期临时资源 410 RESOURCE_EXPIRED；退休／不可见／不存在 404 NOT_FOUND。
- 读取和失败上传不延长期限；上传恢复必须保留 resource id 并先查询本接口，不新增未绑定资源列表功能（D）。

### R06｜GET /resources/{id}/files/{filename}

权限可选登录＋每次资源／所属帖可见性校验，不因持有旧 URL 放行。

| 字段 | 位置 | 类型 | 必填 | 可空 | 默认 | 校验 |
| --- | --- | --- | --- | --- | --- | --- |
| id | path | ID | 是 | 否 | 无 | 当前可见资源 |
| filename | path | string | 是 | 否 | 无 | original；VIDEO 另允许 index.m3u8、服务端生成的 part 数字 .ts；禁止任意路径 |
| Range | header | string | 否 | 否 | 返回完整文件 | D：本契约承诺原文件的单范围 bytes=start-end；其他范围形式不作为本轮保证 |

- 正常 200 binary；原文件合法范围 206 binary，返回匹配的 Content-Range；不返回 JSON 文件对象。
- 类型：原文件按真实 mime；清单 `application/vnd.apple.mpegurl`；片段 `video/mp2t`。
- 返回 Cache-Control: no-store、X-Content-Type-Options: nosniff（I：IMPL-015 的访问边界设计）。
- 资源可见但非 READY：409 RESOURCE_NOT_READY；文件名／文件不存在 404；认证、过期规则同 R05。
- 不可满足的范围：416；D 建议统一错误 code=RANGE_NOT_SATISFIABLE，现有支持需验证，不能标记通过。
- 发布后原视频、入口、所有片段公开；重审／驳回仅作者及 ADMIN；删除后各身份禁读。替换头像／解绑附件即刻禁读，不能复用旧地址。

### 5.3 资源状态与期限矩阵

| 条件／状态 | 可查询／访问 | 可关联 | 期限／转换 |
| --- | --- | --- | --- |
| IMAGE 上传成功 | 上传者可读未绑定图 | 对应本人用途可绑定 | READY；未绑定就绪起 24h |
| VIDEO UPLOADING | 上传者查进度、上传合法片；文件不可播放 | 否 | 创建起／最后成功进展起 24h |
| VIDEO PROCESSING | 上传者查状态；不提供未完成播放文件 | 否 | 不计临时 TTL；完成→READY，失败→FAILED |
| VIDEO READY 未绑定 | 上传者可完整预览 | 是 | 就绪起 24h |
| VIDEO FAILED | 上传者可查 errorCode；文件不可播放 | 否 | 失败起 24h；不接受新片或重复启动 |
| 当前有效帖子／头像绑定 | 依帖子／头像权限 | 只延续原绑定，不跨用途转移 | 临时 expiresAt=null，不误清理 |
| 旧头像／已移除附件／删帖附件 | 所有身份停访 | 否 | 退休起 24h 后物理回收 |
| 到期未绑定资源 | 上传者 410，其余 404 | 否 | 自动清理；读取不刷新期限 |

自动扫描默认 60 秒；正常运行下一轮结束完成，故障重试，不承诺故障下的绝对完成时限（I：IMPL-014）。

## 6. 点赞／收藏 → 立即查询状态与本人列表

依据：BR-11—13、24及补充／列表、44；AC-05/07；I：IMPL-011。

| ID | 方法与路径 | 权限 | 请求 | 成功响应 | 幂等及拒绝 |
| --- | --- | --- | --- | --- | --- |
| I01 | PUT /posts/{id}/likes | USER | path id；无 body | 200 ActiveResult，active=true | 当前 PUBLISHED 才可操作；重复不新增；不存在／非公开／已删 404 |
| I02 | DELETE /posts/{id}/likes | USER | 同上 | 200 ActiveResult，active=false | 未删除非公开帖也可取消本人关系；无关系成功无变化；已删 404 |
| I03 | PUT /posts/{id}/favorites | USER | 同上 | 200 ActiveResult，active=true | 同 I01，收藏独立唯一关系 |
| I04 | DELETE /posts/{id}/favorites | USER | 同上 | 200 ActiveResult，active=false | 同 I02 |
| I05 | GET /me/likes | USER | page、size | 200 `Page<PostDetail>` | 仅本人当前已发布点赞帖；包含真实 liked/favorited |
| I06 | GET /me/favorites | USER | page、size | 200 `Page<PostDetail>` | 仅本人当前已发布收藏帖；包含真实 liked/favorited |

`ActiveResult` 仅含 active：必有 boolean、不可空、无默认，值表示操作完成后的本人关系目标状态（D）。

- 路径 id 必填 ID；kind 仅 likes/favorites，其他路径为 404；401/403 按通用认证。
- 成功后读取 P05 或本人列表，状态、计数及列表立即一致；并发其他用户的合法操作按实际结果累计。
- I05/I06 本人列表保留个人状态，不适用 DEC-058 的公开列表去个人字段规则。
- 重审隐藏本人列表项但保留关系；可取消，不能新增；通过后未取消关系恢复展示；删帖后移除。
- 重复 PUT 在目标仍公开时成功无变化；目标已变非公开时可拒绝，拒绝不创建新关系。不以统计总数作请求默认值。

## 7. 评论／平铺回复 → 删除 → 本人历史

依据：BR-14—16、18、20—24；AC-08/09；I：IMPL-010/011。

### 7.1 评论模型

`CommentBase` 全部必有：

| 字段 | 类型 | 可空 | 值／默认 |
| --- | --- | --- | --- |
| id | ID | 否 | 当前评论／回复 |
| postId | ID | 否 | 所属帖子 |
| body | string | 否 | 原文字，1—1000 码点，保留换行 |
| createdAt | Timestamp | 否 | 原发表时间，不因删除改写 |
| rootId | ID | 是 | 根评论 null；回复为所属根评论 ID |
| replyToId | ID | 是 | 根评论 null；回复为直接回复对象 ID |

`CommentView` = CommentBase＋：

| 字段 | 类型 | 必有 | 可空 | 值／默认 |
| --- | --- | --- | --- | --- |
| author | PublicUser | 是 | 否 | 当前作者公开资料 |
| replyTargetDeleted | boolean | 是 | 否 | 根评论为 false；回复根据目标当前删除状态 |
| replyTargetMessage | string | 仅 replyTargetDeleted=true 时 | 否 | 固定“该评论已删除”；目标有效时省略 |
| replyCount | Count | 仅根评论时 | 否 | 当前未删除回复总数；没有为 0；回复不含此字段 |

`CommentHistory` = CommentBase＋：

| 字段 | 类型 | 必有 | 可空 | 值／默认 |
| --- | --- | --- | --- | --- |
| deleted | boolean | 是 | 否 | 本人条目是否已删除 |
| message | string | 仅 deleted=true 时 | 否 | 固定“该评论已删除”；有效条目省略 |

历史仅本人，不返回他人被删正文、author、replyCount 或原讨论展开结果；ID 不代表可以绕过删除规则（D 的字段表达，B：BR-22）。

### 7.2 端点

| ID | 方法与路径 | 权限 | 请求字段 | 成功响应 | 拒绝规则 |
| --- | --- | --- | --- | --- | --- |
| C01 | POST /posts/{id}/comments | USER | path id；JSON body:string，必填、不可空、无默认，1—1000 码点非全空白 | 201 CommentView（根） | 非已发布／已删／不存在 404；无效正文 400 INVALID_COMMENT |
| C02 | POST /comments/{id}/replies | USER | path 为有效目标评论／回复 ID；JSON body 同上 | 201 CommentView（回复） | 目标已删、所属帖非公开／已删、不存在 404；无效正文 400 |
| C03 | GET /posts/{id}/comments | 可选登录 | path id；page、size、order | 200 `Page<CommentView>`，只有根评论 | 帖子不可见／已删 404；分页／排序非法 400 |
| C04 | GET /comments/{id}/replies | 可选登录 | path 为有效根 ID；page、size、order | 200 `Page<CommentView>`，根下回复平铺 | 不是根／已删根／所属帖不可见 404；参数非法 400 |
| C05 | DELETE /comments/{id} | 登录；本人或 ADMIN | path id；无 body | 200 DeletedResult（同 P07） | 他人普通用户、所属帖已删／不存在 404；重复删同一可识别条目可 200 无变化（D） |
| C06 | GET /comments/{id} | 可选登录 | path id | 200 CommentView，条目及帖子均可见 | 已删条目本人 410 COMMENT_DELETED，其他身份 404；有效条目仍服从帖子权限 |
| C07 | GET /me/comments | USER | page、size | 200 `Page<CommentHistory>` | 仅本人的历史；401/403；分页非法 400 |

- 回复可指向根或回复；总是归到原根下平铺，不增加无限嵌套结构。
- 删除根级联全部回复；单独删回复不级联其他回复，指向它的回复只显示删除提示且不能继续回复被删目标。
- DELETE 不清除本人历史的原正文、原时间；覆盖自删、管理员删、根级联、删帖四种来源。
- 已发布修改重审时，原讨论不公开，但本人可在未删帖上删除本人评论；ADMIN 可管理删除；不可新增评论／回复。
- 删除帖子后，不能凭评论 ID 继续浏览原讨论；本人已删记录只返回 410 和提示，不返回正文，正文仅留在 C07 本人历史。
- 评论与回复 POST 不承诺重试去重（D）；不引入评论编辑、审核、点赞、置顶或热门排序。

## 8. 公开热榜 → 固定顺序翻页 → 主动刷新

依据：BR-41—44；AC-14/15；I：IMPL-012；U：DEC-058。

### F01｜GET /feed/hot

权限匿名。query：

| 字段 | 类型 | 必填 | 可空 | 默认 | 校验 |
| --- | --- | --- | --- | --- | --- |
| snapshotId | string | 连续翻页时是 | 否 | 不传且 page=1 时创建新快照 | 服务端不透明标识；无效／不存在／到期 410 REFRESH_REQUIRED |
| page | integer(int32) | 否 | 否 | 1 | 通用分页；不传 snapshotId 且 page＞1 为 400 SNAPSHOT_REQUIRED |
| size | integer(int32) | 否 | 否 | 20 | 1—100；连续翻页调用方保持同一 size（D），重新选择窗口应刷新 |

成功 200：

| 字段 | 类型 | 必有 | 可空 | 值／默认 |
| --- | --- | --- | --- | --- |
| snapshotId | string | 是 | 否 | 本次排序标识 |
| expiresAt | Timestamp | 是 | 否 | 创建起 10 分钟，固定不续期（I） |
| items | `array<PostCard>` | 是 | 否 | 从快照原位置切片后，过滤当前不可公开项；可少于 size，甚至 [] |
| page | integer(int32) | 是 | 否 | 本次页码 |
| size | integer(int32) | 是 | 否 | 本次请求大小 |
| total | Count | 是 | 否 | 原快照候选数量，不是过滤后当前公开数量（I） |
| hasMore | boolean | 是 | 否 | 原快照当前位置后是否仍有位置，不保证下一页一定有可见作品（I 的分页设计表达） |

- 新榜按累计有效 likeCount＋2×favoriteCount 降序，同分按 firstPublishedAt、id 倒序；全部当前已发布类型同等候选。
- 不设年龄截止、时间衰减、新作位、兴趣模型、已看过滤；不采集曝光／播放时长。
- 本次顺序不因后续计数变化重排；计数读取当前值。下架、驳回、删除即从返回项剔除，不挪动其他快照位置跨页补位。
- 刷新方式：省略 snapshotId、page=1；必须产生新快照，不静默重用过期快照。
- 公开卡片不含 liked/favorited；个人状态另查 P05。固定的是排序，不保证某条内容始终可见。
- 400 INVALID_PAGE/SNAPSHOT_REQUIRED；410 REFRESH_REQUIRED；503 SNAPSHOT_UNAVAILABLE／DEPENDENCY_UNAVAILABLE。
- 改变 size 是重新选取快照窗口，不能据此主张跨页无重复／遗漏；不新增游标协议或绑定页大小的服务器功能（D 的边界说明）。

## 9. 既有运维接口及系统默认资源

以下只记录已有运维协作，不扩展为用户资源管理、恢复或管理员账号管理产品功能。

### O01｜POST /admin/resources/cleanup

- 依据 I：IMPL-014。权限 ADMIN；无业务请求体。
- 主动执行与自动回收相同的期限判断，不能提前删除有效绑定资源或 PROCESSING 任务。
- 成功 200：removed 为必有 Count、不可空，表示本次完成物理回收并标记失效的资源数量；无符合条件项为 0（D）。
- 重复调用不再次计入同一已完成资源；文件失败为 503 STORAGE_UNAVAILABLE，不虚报全部成功；401/403 同通用认证。

### O02｜GET /admin/dependencies

- 依据 I：IMPL-005；权限 ADMIN；无业务请求字段；不新增公共健康详情接口。
- 200：databaseVersion:string（必有、不可空，实际数据库版本）、redis:string（必有、不可空，PONG）、rabbitmq:string（必有、不可空，UP）；不返回连接密码或 token（D）。
- 任一依赖不可用 503 DEPENDENCY_UNAVAILABLE；认证失败 401/403。
- RabbitMQ 不可用时，视频 complete 的接受及恢复仍按 R04；不把探测失败等同于已提交业务回滚。

### S01｜GET /assets/default-avatar.svg

- 系统内置默认头像，匿名读取；无业务请求字段；200 SVG（D：image/svg+xml）。
- 不是用户上传资源，不受资源归属及 24h 清理约束（B：BR-09）。
- 不新增用户可上传 SVG 的能力，也不能用此路由公开其他私有文件。

## 10. 错误协议

### 10.1 错误体

```json
{"code":"STALE_REVISION"}
```

code：string，必有、不可空、无默认。message：string，条件必有、不可空；目前只承诺 COMMENT_DELETED 的“该评论已删除”。
其他错误不承诺解释性 message，不返回正文、密码、内部文件路径或堆栈（D）。

| HTTP | code | 条件／适用范围 | 来源 |
| --- | --- | --- | --- |
| 400 | INVALID_REQUEST | JSON 解析／结构／类型或参数绑定失败、必需 multipart 缺失 | I：IMPL-007；严格类型部分 D |
| 400 | INVALID_USERNAME / INVALID_PASSWORD / INVALID_NICKNAME | U01 字段校验；U04 昵称 | B 的边界，错误码 D |
| 400 | INVALID_PROFILE / INVALID_AVATAR / INVALID_IDS | U04/U06 非法修改、头像或用户 ID 集合 | D |
| 400 | INVALID_TITLE / INVALID_BODY | 投稿／改稿文本非法 | B 的边界，错误码 D |
| 400 | INVALID_RESOURCES / INVALID_RESOURCE / MIXED_MEDIA | 附件集合结构、归属用途就绪期限或混合限制 | B/U 的约束，错误码 D |
| 400 | INVALID_REVISION | P02 缺失／null／范围非法版本；错误 JSON 类型也可由通用 INVALID_REQUEST 拒绝 | U：DEC-059；码名 D |
| 400 | INVALID_REVIEW / INVALID_REASON | P03 版本、结论或原因不合法 | B/I 的约束，错误码 D |
| 400 | INVALID_PAGE / INVALID_ORDER / INVALID_STATUS | 已解析的分页范围、评论排序、管理过滤非法 | I 的范围，错误码 D |
| 400 | INVALID_COMMENT | 评论／回复文本不合法 | B 的约束，错误码 D |
| 400 | INVALID_PURPOSE / INVALID_IMAGE_SIZE | R01 用途／大小非法 | B 的约束，错误码 D |
| 400 | INVALID_MEDIA / INVALID_IMAGE / ANIMATED_AVATAR | R01 实际媒体或静态头像校验失败 | B/I 的约束，错误码 D |
| 400 | INVALID_VIDEO_SIZE / INVALID_PART / INVALID_PART_SIZE | R02/R03 完整大小、分片号及实际长度非法 | B/I 的约束，错误码 D |
| 400 | UPLOAD_INCOMPLETE / PART_CORRUPT | R04 缺片／合并完整性失败 | B/I 的约束，错误码 D |
| 400 | SNAPSHOT_REQUIRED | 新热榜第 2 页起缺少快照标识 | I/D |
| 401 | AUTH_REQUIRED | 必需凭证缺失 | B：DEC-051；码名 D |
| 401 | INVALID_CREDENTIALS | U02 用户名／密码不匹配；凭证关联账号不可用 | B/I；码名 D |
| 401 | INVALID_TOKEN / TOKEN_EXPIRED | 无效、未知或过期凭证 | B：24h；码名 D。Redis 已过期删除时可以 INVALID_TOKEN，不承诺精确区分原因 |
| 403 | ROLE_FORBIDDEN | 认证成功但角色无权操作 | B：DEC-051；码名 D |
| 404 | NOT_FOUND | 不存在／不可见／已删除／退休资源／无权对象操作 | B；码名 D |
| 409 | USERNAME_EXISTS | 大小写不敏感的用户名冲突 | B；码名 D |
| 409 | STALE_REVISION | 作者修改／管理员审核版本不是当前稿 | B/U；码名 D |
| 409 | REVIEW_CONFLICT | 同稿相反的已完成审核结果 | B；码名 D |
| 409 | RESOURCE_NOT_READY / UPLOAD_CLOSED | 资源不可播放／任务已不接受分片 | B/I；码名 D |
| 410 | COMMENT_DELETED | 本人已删评论详情；message 固定中文提示 | B；码名 D |
| 410 | RESOURCE_EXPIRED / REFRESH_REQUIRED | 本人临时资源过期／热榜排序失效 | B/I；码名 D |
| 413 | UPLOAD_TOO_LARGE | multipart 请求解析层超过配置上限；不取代业务 5/10 MiB 限制 | I/D |
| 503 | DEPENDENCY_UNAVAILABLE | 数据库／Redis／依赖探测不可用 | I/D |
| 503 | STORAGE_UNAVAILABLE / MEDIA_TOOL_UNAVAILABLE / MEDIA_INTERRUPTED | 同步文件／媒体识别失败或中断 | I/D |
| 503 | SNAPSHOT_UNAVAILABLE | 热榜序列化／读取故障，不返回假新快照 | I/D |

D 的统一传输错误：405 METHOD_NOT_ALLOWED、415 UNSUPPORTED_MEDIA_TYPE、416 RANGE_NOT_SATISFIABLE、未处理服务异常 500 INTERNAL_ERROR。
这些错误已按 DEC-060 实现并经公开 HTTP 验证；500 通过仅存在于测试的故障入口触发，生产包不包含此入口。

### 10.2 异步处理失败

- R05 查询正常为 200；state=FAILED、errorCode 非空表示该视频任务业务失败，不把查询接口本身改成 400。
- 错误包括 INVALID_MEDIA、INVALID_MP4、VIDEO_DURATION_LIMIT、PROCESSING_TIMEOUT、PROCESSING_FAILED；工具异常可能为 MEDIA_TOOL_UNAVAILABLE／MEDIA_INTERRUPTED（I/D）。
- 只有失败状态可有非 null errorCode；其他状态应为 null。不因失败创建可绑定的成功附件。
- HTTP 错误 code 与任务 errorCode 分属不同对象，客户端不能只检查 HTTP 200 判断视频可投稿。

## 11. 契约核对与下一阶段

- 本稿覆盖 35 个展开后的 API 方法／路径组合及一个系统默认头像资源；HEAD/OPTIONS 不另算业务功能。
- 代码路由与请求／响应字段来源、四类差异及待补测试见 api-gap-analysis.md。
- 原有 52 项测试记录包含 49 项业务验收及 3 项架构检查，是历史证据；不代表新增 revision、显式附件列表及统一卡片已验收。
- 按 DEC-060：先补会失败的公开 HTTP 用例，再修复实现；保留有效断言，合法改稿测试数据显式补 revision/resourceIds，不能让公共 helper 偷补待测缺失字段。
- 修复须验证成功、拒绝、边界、权限、重复／并发、状态转换及失败原子性；然后执行适用回归和 DEC-049 整体验收。
- 本次已完成三项差异的修复、第一批60项完整验收及后续协议／闭环校验；后续修改后的最终完整验收结果单独记录，不复用第一批或历史通过。
