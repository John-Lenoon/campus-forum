# 后端实现与运行设计

完整 API 以 [api-contract.md](api-contract.md) 为准；差异及关闭证据见 [api-gap-analysis.md](api-gap-analysis.md)、[contract-acceptance.md](contract-acceptance.md)。已落实 DEC-057/058/059/061；业务基线、DEC-049、整体验收门槛及技术栈保持不变。本文只解释实现，不把技术取值写成用户逐项确认。

交付前整改的当前证据见 [后端审查记录](backend-review/01-architecture.md)；最终回归及独立 JAR 的验收结论见 [本次最终验收](backend-review/06-final-acceptance.md)，历史结果不自动覆盖后续修改。

## 1. 技术与一致性

- Java 21、Spring Boot 3.5.16、Maven、Spring MVC、MyBatis-Plus 3.5.18、真实 openGauss、Redis、RabbitMQ。
- 密码 PBKDF2-HMAC-SHA256，独立随机盐，600,000 次迭代。
- 登录返回 256 位随机 Bearer token；Redis 仅保存摘要索引及绝对到期时间，固定 24 小时，不滑动续期。
- 预置管理员及必要增列在服务监听前完成，避免首次注册抢占 admin；不重置已有管理员密码，不把异常普通账号静默升级为管理员。
- 帖子修改、审核、删除、互动与评论创建／删除均以帖子行锁协调，事务提交后响应。
- 审核必须携带 revision；修改递增版本、保存全部新内容并进入 PENDING；不保留公开旧稿或草稿。
- 作者修改也必须携带当前 revision；在同一稿件行锁事务中比较，旧版本409且不解绑、不下架、不增版本。
- 资源绑定与帖子保存同事务；失败全部回滚。移除资源立即退休，旧地址不可读，24 小时后可回收。
- 帖子删除采用 deleted_at；所有内容读取、管理列表、本人互动列表和热榜均过滤删除内容。
- 评论删除仅保留本人历史例外；不得通过管理员或原讨论接口读取删除正文。
- 统计从唯一互动关系计算，不使用延迟计数缓存；操作成功后立即可读。
- 图片及视频文件位于 MEDIA_ROOT，未放入静态公开目录。每次原文件、清单、片段请求均查当前权限，响应 no-store。
- 图片先登记可过期的内部非 READY 记录，再在资源锁事务内写文件、校验并提交 READY；提交结果不确定时保留有期限的记录和文件。内部预留不作为已完成资源对外读取。
- FFmpeg／FFprobe 等待被中断或超时时，适配器终止子进程并等待退出。视频 READY 写入异常不作为媒体处理失败回退，失败状态更新仅适用于仍在处理的同一租约。
- 本人互动列表在读取候选 ID 后再次检查当前发布状态；并发下架的条目被过滤，不返回待审内容或导致整页 404。
- 昵称、标题、驳回原因在 openGauss A 模式使用 VARCHAR(n CHAR)，避免按字节误拒中文及 emoji。

### 1.1 代码分层与数据库访问

- 工程约束见 [architecture-spec.md](architecture-spec.md)，对应 DEC-054/055。
- controller：Auth、Post、Users、Interactions、Comments、Media、HotFeed、Dependencies、ResourceMaintenance HTTP 适配。
- service：账号、凭证、用户资料、帖子、互动、评论、热榜、媒体、视频及依赖检查用例；事务位于 Service。
- entity：UserEntity、PostEntity、CommentEntity、ResourceEntity、InteractionEntity、ChunkEntity；PostView 为帖子查询投影。
- mapper：UserMapper、PostMapper、CommentMapper、ResourceMapper、InteractionMapper、ChunkMapper；SystemMapper 负责版本探测和固定增列语句。
- 单主键对象使用 MyBatis-Plus BaseMapper 的插入、按 ID 查询；联合主键对象使用具名映射，保持原联合唯一性。
- 自定义 SQL 位于 src/main/resources/mapper；动态条件由 XML choose/if 固定结构生成，值使用绑定参数，不透传任意 SQL。
- dto：请求、认证上下文、文件下载描述及文件组装输入；持久化实体不直接作为 HTTP 响应。
- infrastructure：ResourceStorage 管理私有路径及文件；VideoFiles 负责流式分片校验、组装及播放文件发布；MediaTools 适配 FFmpeg/FFprobe。
- job：ResourceSweep、VideoWorker 仅触发 Service；config 负责 Bean、队列、增列及管理员预置。
- exception：业务异常与 HTTP 异常映射分离；support 保存纯文本校验及头像地址函数。
- Mapper 不自行开启新事务；同一 Service 事务中的跨 Mapper 更新、行锁、回滚及提交后 MQ 投递保持既有语义。

## 2. 通用 HTTP 契约

- 路径前缀 /api/v1；JSON UTF-8；ID 为 UUID 字符串；时间为 UTC epoch 毫秒。
- Authorization: Bearer <token>；匿名只访问已发布内容及当前公开头像。
- 未知 JSON 字段拒绝。字符串长度按 Unicode 码点，空白包含 Unicode 空格；拒绝 NUL 及不完整代理字符，避免持久化丢失。
- JSON 字符串／整数不做标量隐式转换；可省略不代表可显式传null；类型错误统一 INVALID_REQUEST，解析后的附件集合错误按 DEC-061 返回 INVALID_RESOURCES。
- 页码 page 从 1 开始；size 默认 20，最多 100。列表返回 items、total、page、size。
- 标准错误体包含 code；已删除本人评论还包含 message="该评论已删除"。

| 状态 | 含义 |
| --- | --- |
| 400 | 非法参数、正文／规格错误、不可绑定资源、上传不完整 |
| 401 | 所需凭证缺失、错误或到期 |
| 403 | 已认证但角色禁止 |
| 404 | 不存在、非公开目标对调用者不可见、已退休文件 |
| 409 | 版本／审核冲突、用户名重复、资源尚未就绪或上传已关闭 |
| 410 | 本人评论已删除、资源／热榜快照已过期 |
| 413 | 超出 multipart 请求解析上限 |
| 503 | 数据库、Redis、存储或媒体工具不可用 |

## 3. 账号和资料

| 方法与路径 | 权限 | 输入 | 成功输出 |
| --- | --- | --- | --- |
| POST /auth/register | 匿名 | username,password,nickname? | 201；id,nickname,role,avatarUrl |
| POST /auth/login | 匿名 | username,password | token,expiresAt,user |
| GET /me | USER | 无 | 本人 id,nickname,role,avatarUrl |
| PATCH /me | USER | nickname?、avatarResourceId?，至少一项 | 更新后本人资料 |
| GET /users/{id} | 登录 | 无 | 仅 id,nickname,avatarUrl |
| POST /users/batch | 登录 | ids，最多 100 个输入项 | items 去重；missingIds 明确列出缺失 ID |

- 用户名 4—32 位英文字母、数字或下划线，不分大小写唯一；密码 8—64 码点；昵称 1—32 码点且非空白。
- 不提供用户名修改；省略注册昵称使用用户名；管理员仍为 admin/admin123，无预置普通账号。
- 头像资源必须本人上传、purpose=AVATAR、静态图片且 READY。替换后立即公开，无审核，旧资源立即不可读且不可复用。
- 独立用户查询不返回用户名、角色、密码或凭证。注册／登录／本人资料的 role 用于表达本人身份。

## 4. 帖子与审核

| 方法与路径 | 权限 | 输入／行为 |
| --- | --- | --- |
| POST /posts | USER | title,body,resourceIds?；201，PENDING、revision=1 |
| PUT /posts/{id} | 作者 USER | revision,title,body,resourceIds 均必填；完整提交，旧revision409 |
| DELETE /posts/{id} | 作者或 ADMIN | 删除本人三种状态／管理员删除；所有原内容停访 |
| GET /posts | 匿名 | 已发布列表，首次发布时间倒序、ID 倒序 |
| GET /posts/{id} | 可选登录 | 已发布公开；非公开仅作者／ADMIN；删除全角色 404 |
| GET /admin/posts | ADMIN | status?；省略为全部未删除状态，按最近提交时间倒序 |
| POST /admin/posts/{id}/review | ADMIN | revision,decision=APPROVE/REJECT,reason? |

- title 1—100、body 1—10,000 码点，必填、非全空白；纯文本保留换行，不解释 HTML 或 Markdown。
- POST resourceIds省略默认为[]，显式null拒绝；PUT必须显式非null数组，[]清空，保留附件提交完整ID列表。重复／null元素／超量拒绝。
- 允许最多九张图片或一个视频，不混合；资源仅本人、单帖／单头像用途，不跨帖复用。
- 完整详情返回 title、body、status、revision、submittedAt、firstPublishedAt、author、attachments、likeCount、favoriteCount、liked、favorited。
- author 仅 id,nickname,avatarUrl；公开列表不返回正文和驳回原因。
- 最新流与热榜共用PostCard：基本字段、当前作者、计数和附件，不返回liked/favorited；详情与本人列表返回真实个人状态。
- REJECT 的 reason 必填、非空白、最多 1,000 码点；APPROVE 不带 reason。
- 同一 revision 首次结论生效；相同结论重试返回原结果，不替换原因或时间；相反结论及旧版本 409。
- 修改清除当前原因并递增 revision；首次发布时间不重置；删除后审核请求 404。

## 5. 点赞收藏与评论

| 方法与路径 | 权限 | 输入／行为 |
| --- | --- | --- |
| PUT /posts/{id}/likes、/favorites | USER | 当前已发布可新增；重复无变化 |
| DELETE /posts/{id}/likes、/favorites | USER | 待审／驳回可取消原关系；无关系成功无变化；删帖 404 |
| GET /me/likes、/me/favorites | USER | 只返当前已发布帖子，互动建立时间倒序，含本人状态 |
| POST /posts/{id}/comments | USER | body；201，仅已发布可新增 |
| POST /comments/{id}/replies | USER | body；201，可回复根或已有回复，统一归入根评论 |
| GET /posts/{id}/comments | 可选登录 | 仅根评论分页，回复折叠、replyCount |
| GET /comments/{id}/replies | 可选登录 | 该根下全部回复平铺，独立分页 |
| GET /comments/{id} | 可选登录 | 可见评论；已删本人记录 410＋删除提示，他人 404 |
| DELETE /comments/{id} | 本人或 ADMIN | 删根级联全部回复；删回复仅本条 |
| GET /me/comments | USER | 仅本人原文、原发表时间、删除标记；不返回原讨论或他人被删正文 |

- 评论／回复 1—1,000 码点，纯文本、非空白。默认 order=asc，可选 desc；同时间 ID 同方向排序。
- 回复返回 rootId、replyToId；目标已删时 replyTargetDeleted=true 及提示，不暴露其正文，不能继续回复已删除目标。
- 原评论、关系在重审期间保留；评论不公开，禁止新增互动；可取消原关系、删除本人评论；再次发布不恢复已删评论。
- 本人历史按原时间倒序；自删、管理员删、根级联、删帖均覆盖。

## 6. 热榜

- GET /feed/hot?size=20：首次或刷新创建新的 snapshotId、expiresAt。
- GET /feed/hot?snapshotId=...&page=2&size=20：连续翻页；page>1 必须提供快照。
- 候选为全部当前已发布作品，不按年龄、已看或兴趣过滤，不设新作位。
- 排序：累计有效点赞＋2×累计有效收藏降序；同分按首次发布时间、ID 倒序。评论播放不计分，无衰减。
- Redis 快照保存 ID 顺序，固定 10 分钟，不滑动续期；到期 410 REFRESH_REQUIRED，不静默生成新榜。
- 每页先按原位置切片，再过滤已下架／删除内容；因此可能少于 size，避免过滤造成其他 ID 跨页移位。
- total 为本次原始快照数量，hasMore 表示后续原位置是否存在；数字与当前互动状态可变化，顺序至刷新才变。

## 7. 上传、处理及文件访问

| 方法与路径 | 权限 | 输入／行为 |
| --- | --- | --- |
| POST /resources/images?purpose=POST/AVATAR | USER | multipart file，201；按实际内容校验 JPG/PNG/WebP |
| POST /resources/videos | USER | JSON size，1—1 GiB；201，UPLOADING、chunkSize、totalParts |
| PUT /resources/{id}/chunks/{index} | 上传者 USER | 原始二进制，index 从 0 开始；每片大小必须符合服务端声明 |
| GET /resources/{id} | 按资源权限 | state、size、url、originalUrl、expiresAt、errorCode；视频含 uploadedParts、duration |
| POST /resources/{id}/complete | 上传者 USER | 验证全部分片、校验 SHA-256、合并原文件，提交 PROCESSING |
| GET /resources/{id}/files/original | 按资源权限 | 原文件，支持 Range |
| GET /resources/{id}/files/index.m3u8、partNNNNN.ts | 按资源权限 | HLS 清单及片段，逐请求鉴权 |

- 作品图片每张最多 10 MiB、每帖最多 9 张；头像静态图片最多 5 MiB；系统默认 SVG 不属于用户上传资源。
- 视频 MP4、每帖一个、总大小最多 1 GiB、完整时长最多 30 分钟。不是对单片计算上限。
- 固定 8 MiB 分片；允许乱序、失败片单独重传、相同成功片重复提交。查询进度后无需重传成功部分。
- 上传中自最后一次成功进展起 24 小时失效；重复相同成功片不延长。失败／未完整片不记进度。
- complete 在数据库提交后尝试投递持久化 RabbitMQ 队列；未投递成功仍保留数据库 PROCESSING 任务，30 秒扫描重投。
- 队列按数据库连接区分，避免开发／测试任务相互消费；多实例部署需配置一致的 forum.video-queue。
- 消费者通过数据库租约幂等领取；FFprobe 校验实际 MP4、视频流及完整时长；FFmpeg 转 H.264/AAC、约六秒 HLS、完整 VOD 清单。
- 处理执行上限两小时，租约 125 分钟；进程中断可在租约到期后重新投递，不把处理超时等同于业务时长上限。
- 全部分段存在、完整清单写入且处理成功后才标 READY；此前无法绑定投稿。错误标 FAILED，可查询 errorCode，不产生不可播放的成功投稿。
- PROCESSING 不计临时过期倒计时；READY 未绑定从就绪起保留 24 小时，FAILED 从失败起保留 24 小时。
- 未绑定仅上传者可读；绑定帖子后按帖子状态，已发布公开、待审／驳回作者和 ADMIN 可读、删除全角色禁读。当前头像公开。
- 映射文件名仅允许 original、index.m3u8、生成的 part 数字 .ts；禁止任意路径和静态目录旁路。
- 图片上传要求multipart/form-data，分片要求application/octet-stream；合法multipart缺文件仍400。错误方法／类型／Range／未处理异常分别返回405/415/416/500及稳定code；Range在返回文件前校验，合法范围仍206。

## 8. 回收与运维

- 旧头像／已移除附件立即退休；退休 24 小时后回收。有效绑定资源不因临时期限误清理。
- 自动扫描默认 60 秒；正常运行时期限到达后的下一次扫描清理。文件系统故障会失败并在下轮重试，不虚构故障下完成时限。
- POST /admin/resources/cleanup：ADMIN 主动执行同一回收逻辑，返回 removed 数量；不新增用户恢复或删除资源能力。
- GET /admin/dependencies：ADMIN 检查 openGauss、Redis、RabbitMQ；失败 503。
- Redis 故障不退化为内存凭证或绕过鉴权；RabbitMQ 故障不改变已提交的审核状态。
- schema.sql 创建新表；DatabaseEvolution 补建既有表的新列。早期字符长度列修正见 V002，升级时按 README 执行。
- 物理文件及测试工具不进入 Git。测试库独立，生成的测试媒体与普通开发数据按资源 ID 分开，不清空开发数据。

## 9. 数据模型

| 表／新增列 | 主要数据及约束 |
| --- | --- |
| forum_users | UUID 主键、唯一 username_key、密码哈希、字符语义昵称、USER/ADMIN 检查；avatar_resource_id 指向当前头像 |
| forum_posts | 作者外键、标题正文、三态检查、revision、提交／首次发布时间、当前原因、审核者；deleted_at 控制删除 |
| forum_interactions | user_id/post_id/kind 联合主键与外键、建立时间；唯一关系避免重复计数 |
| forum_comments | 帖子／用户外键、根评论／回复对象自关联、原文、原时间、deleted_at；本人历史沿用原记录 |
| forum_resources | 上传者、用途、类型、状态、大小／时长、帖子／头像关联、ever_bound、过期／退休时间、分片规格、处理租约及错误码 |
| forum_chunks | resource_id/part_index 联合主键、大小、SHA-256；服务器验证每片预期长度、合并大小和摘要 |

- 列定义以 src/main/resources/schema.sql 为准，后续新增列由 DatabaseEvolution 检查补建。
- 审核、修改、删除与互动锁定帖子后修改；资源按 ID 顺序锁定，头像先锁定用户再锁资源，避免交叉锁顺序。
- 资源关联失败不提交任何新帖子／修改；文件上传先于投稿，不将文件系统写入当作数据库事务回滚。
- 文件回收只遍历已校验 UUID 对应的配置根目录子目录；元数据仅在文件删除成功后标 EXPIRED。
- 删除帖子不物理清空评论原文，满足仅本人历史保留；原讨论入口均拒绝读取。

## 10. 验收

- 业务测试通过公开 HTTP；真实 openGauss、Redis、RabbitMQ、FFmpeg；仅时间边界可控。物理回收另观察配置存储根目录的文件副作用，确认未提前删除及确实回收。
- 常规 mvn test 执行快速及媒体回归，默认排除 large-media 标签的 1 GiB 用例。
- mvn -Pfull-media verify 包含实际 1 GiB 分片上传与处理，并执行全部回归。大文件用例结束后按过期清理释放生成数据。
- 最终结果、适用范围、失败／阻塞／未执行项见 final-acceptance.md，不以文档编写或编译成功代替测试通过。
