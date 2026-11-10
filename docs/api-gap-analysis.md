# API 契约与实现差异分析

日期：2026-11-10。基准：[业务 Spec](requirements.md)、DEC-001—061、[API 契约](api-contract.md)。

## 落地执行状态（DEC-060）

- DIFF-01、DIFF-02、MISS-01 已完成修复；本次全量60项通过（失败／错误／跳过均为0），日志 target/contract-primary-full.log。
- 证据、逐项红绿循环及后续批次见 [contract-acceptance.md](contract-acceptance.md)。下文第1—5节的原代码事实作为修复前审查记录保留，不再表示当前仍存在这些三项差异。
- 已自动完成第7节后续校验；最终clean全量74项、失败／错误／跳过0，独立JAR及重启通过。不以第一批60项替代最终结果。

## 1. 本轮方法与结论

- 已阅读业务规则、决策与 IMPL 记录、现行／第一批设计、Controller、DTO、Service、Mapper XML、媒体及存储适配、配置、六个测试源文件和历史验收记录。
- 核对当前工作区，不因文件尚未提交而忽略其内容。
- 本节保留原文档审查阶段的方法及发现；该阶段只修改文档。其后按 DEC-060 实施，当前结果见落地状态及第9节。
- “静态一致”表示源码与规则相符且找到相应测试意图，不等于已按本契约重新运行成功。
- 历史 refactor-acceptance-results.json 记录 52 项通过，其中 49 项功能验收、3 项架构约束；该历史结果不涵盖本轮新决定。
- 四类结果：原规则有一致的静态实现；本轮确认后有 2 项明确实现不一致、1 项能力缺失；另有未经逐项确认的接口表达／代码选择，详见第三类。
- 没有把“缺少独立资源绑定端点”“缺少推荐模型”“没有前端”当作缺失功能；既有决策已明确不需要这些扩展。

### 1.1 本轮已确认而尚未落实的三项

| 问题／决定 | 当前事实 | 分类 | 后续要求 |
| --- | --- | --- | --- |
| Q-051 A；DEC-057 | 修改附件列表可省略或 null，并被解释为清空 | 第二类 DIFF-01 | 显式数组必填；缺失／null 400；[] 清空 |
| Q-052 A；DEC-058 | 热榜返回 liked/favorited=false；公开两类列表对象也不同 | 第二类 DIFF-02 | 使用统一 PostCard，禁止个人状态字段；详情保留真实个人状态 |
| Q-053 A；DEC-059 | 作者修改没有 revision 输入或版本比较 | 第四类 MISS-01 | 当前版本条件、409、并发唯一成功及无副作用重试 |

上述已确认且现已修复，不再重复提问；表内“当前事实”记录修复前审查时的代码。关闭依据为本次测试，不是历史绿灯。

## 2. 第一类：Spec 已明确，静态实现一致

只判断各行列出的规则维度，不将其扩张为整个接口验收通过。

| 编号 | 规则及契约端点 | 当前实现证据 | 已有测试意图（本轮未重跑） |
| --- | --- | --- | --- |
| MATCH-01 | BR-25/27，DEC-051；U01/U02 | [AccountService](../src/main/java/campus/service/AccountService.java) register/verify；普通注册固定 USER、默认头像昵称、大小写唯一；[AdminInitializer](../src/main/java/campus/config/AdminInitializer.java) 在监听前预置管理员 | ForumHttpTest.registrationReturnsOrdinaryUserAndDefaultAvatar、duplicateUsernameIsCaseInsensitiveAndLoginAcceptsOriginalPassword；BootstrapAcceptanceTest.presetAdminExistsBeforePublicRegistrationCanReachTheServer |
| MATCH-02 | BR-40；U02及需认证端点 | [SessionService](../src/main/java/campus/service/SessionService.java) 固定 24h、摘要索引、绝对过期检查，不续期 | ForumHttpTest.loginIssuesFixedLifetimeTokenAndRejectsWrongPassword、successfulReadsNeverExtendSessionExpiry |
| MATCH-03 | BR-01/16/18/28/45；U03—U06、P05/P06 | [Controllers](../src/main/java/campus/controller/) 按 USER/ADMIN/登录区分；[UserService](../src/main/java/campus/service/UserService.java) 独立查询只返回 ID、昵称、头像；非公开详情授权在 PostService | ForumHttpTest.inputCannotGrantAdminOrOverridePostIdentityOrState、rejectionReasonIsVisibleOnlyToAuthorAndAdmin；RemainingFlowsTest.nicknameChangeAppearsInAuthenticatedSingleBatchAndPublicAuthors |
| MATCH-04 | BR-02/26/29/38；P01、R01—R05 | [PostService.create](../src/main/java/campus/service/PostService.java) 事务创建 PENDING；[MediaService.bindPost](../src/main/java/campus/service/MediaService.java) 统一归属／用途／数量／就绪校验 | ForumHttpTest.textSubmissionIsPendingAndHiddenFromVisitors、maximumTextAndLiteralMarkupRoundTripWithoutFormatting；MediaFlowsTest.imageUploadBindsAtomicallyAndAllStatesEnforceDirectAccess |
| MATCH-05 | BR-05/06/23/39；P03 | PostService.review 使用当前 revision 行锁、首次结论、同结论重试、不覆盖原因；[PostMapper.xml](../src/main/resources/mapper/PostMapper.xml) 保留 firstPublishedAt | ForumHttpTest.reviewValidationAndRepeatedVerdictsPreserveOriginalResult、concurrentOpposingReviewsHaveExactlyOneWinner |
| MATCH-06 | BR-04/17/19/23/24/46；P02 的原状态流转维度 | PostService.edit 对三态保存、重审、清原因及原子绑定；最新列表按首次发布时间，管理按提交时间。新增作者版本前置条件除外，见 MISS-01 | RemainingFlowsTest.editResubmitsLatestRevisionAndDeleteHidesFromEveryone；CrossModuleAcceptanceTest.allEditableStatesReReviewKeepHistoryAndDiscardOnlyCurrentReason；合法请求数据需按新契约更新 |
| MATCH-07 | BR-07/20/22；P07、C05—C07 | PostService.delete 隐藏全角色读取并级联评论退休资源；CommentService 保留本人历史 | CrossModuleAcceptanceTest.authorCanDeletePendingAndRejectedAndAdminListNeverReturnsDeleted、commentPaginationBoundariesAndAdminPostCascadePreserveEachOwnersHistory |
| MATCH-08 | BR-11—13/24/44；I01—I06、P05 | [InteractionService](../src/main/java/campus/service/InteractionService.java) 行锁＋唯一关系；[InteractionMapper.xml](../src/main/resources/mapper/InteractionMapper.xml) 本人公开列表；详情实时计数和状态 | RemainingFlowsTest.interactionsAreIdempotentImmediateAndSurviveReReview；CrossModuleAcceptanceTest.concurrentLikesAndPersonalOrderingReflectCompletedActions |
| MATCH-09 | BR-14/15/21/22；C01—C07 | [CommentService](../src/main/java/campus/service/CommentService.java)、[CommentMapper.xml](../src/main/resources/mapper/CommentMapper.xml) 两层平铺、独立分页、根级联、单回复删除、本人历史 | RemainingFlowsTest.commentsFlattenRepliesCascadeDeleteAndPreserveOnlyOwnHistory；CrossModuleAcceptanceTest.commentPaginationBoundariesAndAdminPostCascadePreserveEachOwnersHistory |
| MATCH-10 | BR-29/31/38；图片／文字边界 | [TextValidation](../src/main/java/campus/support/TextValidation.java)、[MediaTools](../src/main/java/campus/infrastructure/MediaTools.java) 真实容器／编码、静态头像、码点边界；不是只看文件名 | ForumHttpTest.textBoundsAreEnforced、maximumTextAndLiteralMarkupRoundTripWithoutFormatting；MediaFlowsTest.imageFormatsSizeLimitsAndNineImageBindingAreEnforced、imageTypeIsValidatedFromContainerNotFilenameOrVideoCodec |
| MATCH-11 | BR-32—35；R02—R06 | [VideoService](../src/main/java/campus/service/VideoService.java)、[VideoFiles](../src/main/java/campus/infrastructure/VideoFiles.java)、VideoWorker：分片摘要、合并、PROCESSING 持久任务、真实 HLS 就绪 | MediaFlowsTest.multipleVideoPartsAllowOutOfOrderResumeAndDuplicateSuccessfulParts、videoDurationAndByteBoundariesAndFailedProcessingAreObservable、actualOneGiBVideoUploadsWithBoundedChunksAndIsCleanedAfterExpiry |
| MATCH-12 | BR-09/33/36/37；资源访问／期限 | MediaService 按当前帖子权限；[ResourceMapper.xml](../src/main/resources/mapper/ResourceMapper.xml) 期限锁定；ResourceStorage 物理删除；有效绑定／处理中排除 | MediaFlowsTest.avatarReplacementIsImmediatePublicAndOldResourceCannotBeReused、failedAndRetiredResourcesArePhysicallyReclaimedOnlyAfterTheirDeadline、expiryCleanupHonorsSuccessfulProgressAndDoesNotRemoveBoundResources、processingHasNoTemporaryExpiryAndDatabaseJobRecoversAfterRabbitRestart |
| MATCH-13 | BR-41—44；F01 的计分／候选／顺序维度 | [HotFeedService](../src/main/java/campus/service/HotFeedService.java)、PostMapper.rankPublished 累计加权、ID 快照、过滤下架、10min；不判断其现有响应字段合规 | RemainingFlowsTest.hotFeedFreezesOrderRefreshesAndFiltersHiddenPostsImmediately；CrossModuleAcceptanceTest.expiredHotSnapshotRequiresExplicitRefresh |

## 3. 第二类：Spec／新确认决策已明确，但实现不一致

### DIFF-01｜修改省略或 null 附件列表会清空，违反 DEC-057

- 规范：P02 resourceIds 必填数组，[] 清空，缺少／null 返回 400，失败原帖不变。
- 静态事实：[SubmissionRequest](../src/main/java/campus/dto/SubmissionRequest.java) 允许 null；PostController.edit 直接传递；MediaService.bindPost 的 `requested == null ? List.of() : requested` 将其变为空列表并退休旧附件。
- 影响：只修改文字但漏传附件列表，会成功移除附件，甚至使已发布帖转待审；现代码不能表达“漏传应拒绝”。
- 已有 MediaFlowsTest.failedAndRetiredResourcesArePhysicallyReclaimedOnlyAfterTheirDeadline 用省略 resourceIds 触发移除。这是旧协议测试数据，需要显式 []，不能保留该旧输入语义冒充新规则。
- 待修复：独立修改请求模型／校验，拒绝省略和 null；保留显式空数组、完整保留数组、非法资源原子回滚用例。
- 状态：已关闭；本次红测复现200误成功，修复后省略/null均400，显式保留／清空、失败原子性及权限测试通过。

### DIFF-02｜热榜返回个人状态字段，违反 DEC-058

- 规范：公开最新流与热榜使用同一作品卡片，均不返回 liked/favorited；详情提供个人状态。
- 静态事实：[HotFeedController](../src/main/java/campus/controller/HotFeedController.java) 不读取凭证；HotFeedService.list 调用 `posts.detail(id, null)`，将整份详情加入 items，包含恒 false 的 liked/favorited。
- PostService.list 的最新列表去掉 body/rejectionReason，但也没有 attachments；热榜包含 body 和 attachments，两者没有统一卡片对象。
- 影响：热榜字段不能表示登录用户真实状态，也不符合本轮已选择的“不返回个人状态”；统一卡片的具体字段按契约 D 项审阅，不从上述实现差异反推业务必须返回正文。
- 待修复：公共 PostCard 组装；两类公开列表均排除个人字段；推荐顺序、计数实时性和过滤规则保留。附件卡片字段见 CODE-02。
- 测试现状：历史热榜测试只断言 ID、计数、顺序及剔除；没有断言排除 liked/favorited 或卡片完整字段，不能证明 DEC-058 已实现。
- 状态：已关闭；本次红测复现缺附件及热榜额外字段；统一PostCard后文字／图文／视频字段、个人状态隔离及原热榜规则通过。

## 4. 第三类：Spec 未规定、代码或框架已有选择

已具 IMPL 授权的取值单独列出，不重新询问、不伪装成代码擅自增加的已确认业务规则。

| 编号 | 具体事项及当前证据 | 已有依据／不足 | 本稿建议及处理状态 |
| --- | --- | --- | --- |
| CODE-01 | 路径、HTTP 动词、201/200、无外层 data 包装、各字段命名及具体错误 code | 原业务 Spec 多未规定；backend-design.md 记载了现行表达，但不是逐项用户确认 | 沿用现有主要路由减少迁移；作为 D 契约审阅，不擅自引入另一 API 风格或依赖 |
| CODE-02 | 最新列表无附件、热榜是完整详情、本人列表是完整详情；管理列表含正文和 nullable 原因 | BR-13/45 定义能力和隐私，不规定完整卡片字段；DEC-058 已要求统一公开卡片及排除个人字段 | D 定义 PostCard 含附件元数据、无正文；管理／本人列表模型分别明确。附件卡片字段尚无现行验收证明，不能作为“原 Spec 已实现” |
| CODE-03 | Jackson 配置只显式拒绝未知属性及小数转整数；未冻结数字／布尔到 string、数字字符串到整数等转换 | 缺少这些具体请求类型测试；不把框架默认行为推断写成已经动态验证 | D 建议严格声明类型；后续逐字段验证和补齐配置／校验，不新增工具或依赖 |
| CODE-04 | 注册 nickname=null 使用用户名；个人资料 null 字段被当作未修改；投稿 resourceIds=null 被当成 [] | B 规定省略昵称默认、允许资料修改／纯文字，但未精确定义全部 null 表达；DEC-057 仅明确修改附件数组 | 契约分别写明；D 建议资料显式 null 拒绝、创建 resourceIds 可省略但不接受 null。均待整体审阅，不冒充用户逐项选择 |
| CODE-05 | 密码也经过 TextValidation.text，全空白密码拒绝；登录缺 username/password 返回 401；登录每次新 token | DEC-051 明确密码长度，未逐项确认空白强度、缺字段错误码或单点登录；固定 24h 已明确 | D 建议保持无额外强度算法、区分结构错误 400 与密码不匹配 401、不引入单点登录；全空白策略明确标 D |
| CODE-06 | 页码及大小、评论排序、本人互动时间、批量去重、缺失 ID、热榜10min、快照原位置 total/hasMore | 默认／上限及主要排序来自 DEC-051、IMPL-010/012，属于授权实施决定 | I 项继续沿用，不重复问；D 明确 total/hasMore 不等于当前可见数量。更改 size 是重新选窗口，不新增服务端游标功能 |
| CODE-07 | 用户批量允许 []、按首次输入去重；元素最长100使用 Java String.length；其他 ID 可为 missing 并返回缺失 | IMPL-010 规定最多100输入项、去重及 missingIds；未逐项确认 ID 字符长度、空集合及次序 | D 明确空集合和顺序、ID不透明；建议按码点统一长度，极端 ID 用例未执行；不把 UUID 生成方式变成强制所有查询都拒绝非 UUID |
| CODE-08 | 新投稿、评论、资源创建重试会创建新对象；删帖二次404、删评论在帖未删时可再次200；complete 重复返回当前状态 | B 只明确点赞收藏及同稿审核的幂等；没有统一所有 POST/DELETE 的响应重放要求 | D 明确副作用幂等与响应恒定不同；不新增 Idempotency-Key 子系统；作者旧请求安全由 DEC-059 解决 |
| CODE-09 | 未显式设置所有请求 consumes；统一异常映射缺少405/415/416及兜底500的code；资源 Range 有206用例，无416断言 | [ApiExceptionHandler](../src/main/java/campus/exception/ApiExceptionHandler.java) 及 MediaController 只证明已有分支；B 未规定这些码名 | D 提出完整错误体建议；协议项尚待补测试，不能记为业务 Spec 违约已动态复现 |
| CODE-10 | 原文件/HLS地址具体格式、ResourceSummary/Info 字段、非 READY 也返回未来 URL、实体过期时返回410而不是200 EXPIRED | B 规定可访问性及处理状态，不指定全部传输字段；I：IMPL-015/017 约束存储和任务方案 | D 将字段必有／nullable 与异步 FAILED 分开定义；不将私有路径或租约暴露给客户端 |
| CODE-11 | 8MiB分片、成功相同内容不延期、不同合法内容替换同序号；创建任务即开始初次24h | 8MiB及有效进展见 IMPL-017；B 未精确定义成功片改写及首次上传前的计时表达 | 沿用 I；不同内容替换、首次时点明确标 D；不把失败重传扩大为重新转码产品功能 |
| CODE-12 | cleanup、dependencies 两个运维端点及默认静态头像路由 | IMPL-005/014 及 BR-09 默认头像例外；不是独立产品模块或管理员账号管理 | 分别列 O01/O02/S01，不要求新增后台页面或独立用户资源删除功能 |

## 5. 第四类：已要求但尚未实现

### MISS-01｜作者修改的 revision 前置条件缺失（DEC-059）

- P02 必须接收当前 revision，在与保存同一事务／稿件行锁内比较，不匹配返回 409。
- 当前 SubmissionRequest 只有 title/body/resourceIds；PostController.edit 未接版本，PostService.edit 未比较版本，[PostMapper.edit](../src/main/resources/mapper/PostMapper.xml) 直接 revision=revision+1。
- 传入新契约 revision 时还可能被未知属性策略拒绝；省略时当前接口却可以提交。不能用路由已存在证明新能力已实现。
- 旧请求在首次成功后再发仍可递增版本，把刚审核通过作品再次变 PENDING；这是静态控制流结论，本轮未执行动态复现。
- 需要成功当前版本、缺失／null／非法版本、旧版本重试、同版并发竞争、过期请求不得解绑或下架等用例。
- 不重复计入 DIFF-01：本项专指未实现的作者版本保护；附件集合约束是独立问题。
- 关闭结果：已实现 EditPostRequest 及同事务行锁下版本比较；当前版本成功、缺失/null/非法400、旧版本409、并发唯一胜出、审核竞争与无副作用重放均通过本次测试。

原 BR-01—46 范围内，本次未发现整个业务模块或必要独立路由完全缺失；这不等于证明所有分支完整正确。
新卡片具体字段、严格类型及统一错误等 D 设计项，暂列第三类，不能伪装成原业务 Spec 早已要求的遗漏。

## 6. 路由与测试核对索引

表内“已有”只指路由存在、查到相关测试意图；整行均未按新契约重新动态验收。

| 契约 ID | 方法与路径（统一 /api/v1 前缀） | Controller | 现有测试源及尚需核对 |
| --- | --- | --- | --- |
| U01/U02 | POST /auth/register；POST /auth/login | AuthController | ForumHttpTest、BootstrapAcceptanceTest；补严格类型、null及缺字段码 |
| U03/U04 | GET /me；PATCH /me | UsersController | RemainingFlowsTest、MediaFlowsTest；补空对象、显式 null、双字段回滚及角色分支 |
| U05/U06 | GET /users/{id}；POST /users/batch | UsersController | RemainingFlowsTest；补100/101、空集合、全字段隐私及ADMIN读取 |
| P01 | POST /posts | PostController | ForumHttpTest、MediaFlowsTest；保留纯文／图片／视频及原子失败，补字段类型 |
| P02 | PUT /posts/{id} | PostController | RemainingFlowsTest、CrossModuleAcceptanceTest、MediaFlowsTest；DIFF-01、MISS-01 必须补用例并更新合法输入 |
| P03 | POST /admin/posts/{id}/review | PostController | ForumHttpTest；保留同稿不同原因重试原结果、相反结论并发、旧稿拒绝 |
| P04/P05/P06 | GET /posts；GET /posts/{id}；GET /admin/posts | PostController | ForumHttpTest、RemainingFlowsTest；新增 PostCard schema／字段排除与管理模型契约 |
| P07 | DELETE /posts/{id} | PostController | RemainingFlowsTest、CrossModuleAcceptanceTest、MediaFlowsTest；补重复状态码等协议断言 |
| I01/I02/I03/I04 | PUT/DELETE /posts/{id}/likes；PUT/DELETE /posts/{id}/favorites | InteractionsController | RemainingFlowsTest、CrossModuleAcceptanceTest；保留16并发重复点赞、非公开取消；补收藏并发及返回 active |
| I05/I06 | GET /me/likes；GET /me/favorites | InteractionsController | RemainingFlowsTest、CrossModuleAcceptanceTest；补每字段类型／nullable、收藏列表排序，不移除本人状态 |
| C01/C02 | POST /posts/{id}/comments；POST /comments/{id}/replies | CommentsController | RemainingFlowsTest、CrossModuleAcceptanceTest；保留已删目标、非公开禁止与文字边界 |
| C03/C04 | GET /posts/{id}/comments；GET /comments/{id}/replies | CommentsController | RemainingFlowsTest、CrossModuleAcceptanceTest；补回复分页的全部边界、字段条件存在性 |
| C05/C06/C07 | DELETE /comments/{id}；GET /comments/{id}；GET /me/comments | CommentsController | 两个上述测试类；保留四种删除历史、410中文提示及他人404，补返回字段白名单 |
| F01 | GET /feed/hot | HotFeedController | 两个上述测试类；DIFF-02、新旧卡片一致性、空页hasMore及元数据语义 |
| R01/R02 | POST /resources/images；POST /resources/videos | MediaController | MediaFlowsTest；保留真实格式、5/10MiB、1GiB声明及实际1GiB传输，补类型／Content-Type |
| R03/R04/R05 | PUT /resources/{id}/chunks/{index}；POST /resources/{id}/complete；GET /resources/{id} | MediaController | MediaFlowsTest；保留乱序、相同重传、失败、期限、MQ恢复；补不同内容重传及重复complete |
| R06 | GET /resources/{id}/files/{filename} | MediaController | MediaFlowsTest；保留真实HLS完整解码、原文件206及重审删除权限；补416和错误体 |
| O01/O02 | POST /admin/resources/cleanup；GET /admin/dependencies | ResourceMaintenanceController、DependenciesController | MediaFlowsTest、ForumHttpTest；补所有角色、失败返回字段及回收计数 |
| S01 | GET /assets/default-avatar.svg（无 /api/v1 前缀） | 系统静态资源 | 默认地址有注册断言；直接获取的 MIME、内容及不受临期清理需单独验证 |

Schema、权限和边界用例不足属于验证缺口，不能据此直接声称对应功能完全未实现或已经失败。

## 7. 契约审阅后的修复与验收顺序

1. 冻结本稿的 D 协议表达；如出现真正业务冲突再提问，每轮最多3个；不重问已回答的 Q-051/052/053。
2. P02 闭环：先补缺附件／null附件400、显式[]、当前revision成功、旧revision409及并发只一胜出；再实现请求模型、同事务比较与原子失败。
3. 公开浏览闭环：先定义 PostCard 精确字段并断言公开无个人状态，再统一最新流／热榜映射；保留计数、首次时间、顺序及下架剔除断言。
4. 按账号→资料→媒体→互动→评论→热榜依赖补 JSON 类型／null、响应字段、通用错误及单端点权限矩阵。
5. 原有效断言保留；旧合法请求显式加正确 revision/resourceIds。缺字段负向用例必须真实缺字段，不能由 helper 自动补齐。
6. 使用现有 Java/Maven/真实依赖执行测试，不新增契约工具或依赖；常规 mvn -s .mvn/settings.xml test，完整 mvn -s .mvn/settings.xml clean -Pfull-media verify。
7. 核对 README、现行契约及实现，按 DEC-049 报告通过／失败／阻塞／未执行。当前已知实现差异未修复前，不能重新判定整体验收通过。

## 8. 原文档审查阶段结果（历史）

| 分类 | 本轮状态 |
| --- | --- |
| 通过 | 文档交付、35个API路由及默认资源覆盖、链接与字段结构静态核对完成；源码／测试／pom／整个业务Spec按字节核对未修改，DEC-049原文保留；只代表审查产物完成，不代表接口验收通过 |
| 不一致／缺失 | 2 项明确不一致＋1 项缺失能力；按源码定位，未伪称动态失败用例已运行 |
| 阻塞 | 本轮3个必要问题已答，无未回答的本轮业务问题；代码修复以契约完成审阅为下一阶段前置条件 |
| 未执行 | 业务代码修复、新契约动态测试、完整回归及新的 DEC-049 整体验收；均未计为通过 |

既有测试与历史验收记录保留日期及历史效力。本轮发现新差异后，历史“通过”不能代表当前代码已经符合本轮新增约束。

## 9. 契约落地的后续校验

| 对应第三类事项 | 本次处理 | 当前验证 |
| --- | --- | --- |
| CODE-01/02 | 沿用主要路由及包装；统一强类型PostCard，详情／本人列表保留个人状态，管理模型维持独立 | ContractImplementationTest 全部8项及ContractCoverageTest相关模型／权限用例通过 |
| CODE-03 | 现有Jackson中禁止JSON数字/布尔转字符串、字符串/小数/布尔转整数 | 红测确认误接受；ContractValidationTest 类型矩阵通过 |
| CODE-04 | 创建省略附件为[]、显式null拒绝；资料缺省保留、显式null拒绝，不产生部分更新 | 红测复现误创建／误改资料；null、合法省略、双字段原子性通过 |
| CODE-05 | 登录缺字段/null为400，错误凭据仍401；密码策略及固定24h保持 | 红测401→400修复通过；原登录边界及过期用例纳入最终回归 |
| CODE-06/07 | 保留分页／排序／快照；显式空分页和排序拒绝；批量ID按码点校验 | 红测复现空值被默认和100码点误拒绝；空集合、100/101输入、去重次序、隐私及快照元数据通过 |
| CODE-08/11 | 保留创建不承诺去重；作者重放受revision保护；分片相同内容不延期、不同内容可替换，complete重复返回当前状态 | 本次原始视频字节比对、重复complete、上传状态关闭，以及并发收藏／取消与排序通过 |
| CODE-09 | 统一405/415/416/500 code，上传Content-Type显式，合法Range保持206；字段错误边界按DEC-061 | 红测复现无统一code、非法Range返回非JSON及错误类型被接受；修复及专门边界用例通过 |
| CODE-10/12 | 明确资源／历史的条件字段；运维权限和默认SVG不扩展产品范围 | 当前模型字段白名单、真实Redis故障503及原凭证恢复、运维身份、默认SVG直接访问通过 |

- 新增 ContractImplementationTest 8项、ContractValidationTest 8项、ContractCoverageTest 6项，共22项定向用例已通过。
- 已正确的旧行为补强为回归用例，不虚构修复前红测；需要改变的行为均保留实际断言失败日志。
- 最终clean全量74项及独立JAR新库／重启通过，原始报告、逐例摘要及不变量证据见contract-acceptance.md；当前未关闭差异为0。
