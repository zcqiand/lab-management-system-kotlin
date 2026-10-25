# REQ-2026-002 kotlin 镜像树 GA 收口（9 ID 对齐批）

| 项 | 值 |
|---|---|
| 提出人 | zcqiand |
| 提出日期 | 2026-10-25 |
| 优先级 | P1 |
| 状态 | 已完成 |
| 与 saas 仓 REQ-2026-002 同批镜像 | 是 |

## 1. 需求描述

> 背景：REQ-2026-001（deploy 链）定序「kotlin 业务功能先完成」。本仓 9 个后端功能子项自 Phase 4/5 认证纵切起已实现并有仓内测试锚定（git log 在案：`30b022d`→`a665705`），但未走 REQ 立档，功能树状态停在「开发中」，需求/设计/流程三列无引用。本需求为**对齐收口批**：补登记、补映射、凭人工验收记录走 mirror 免批 GA 翻转（ADR-0042 同款通道）。不写任何新业务代码。
>
> M01.F04.I03（路由守卫）/ M01.F04.I04（动态菜单 lab 侧边栏）为前端标签页行，实现归 react/vue/nextjs 仓，本仓随 SSOT 照录、**保持开发中**（shared BASE 同为开发中，收口范围不含它们）。
>
> 遗留提示：M01.F01.I01（（待填）/规划）为 shared BASE 不存在的镜像孤儿，处置见独立人裁项（不在本 REQ 范围）。

### 澄清记录

| 留问 | 澄清结论 | 澄清人 | 日期 |
|---|---|---|---|
| 为什么一个 REQ 覆盖 9 个 ID？ | 登记补课而非新功能开发；9 个 ID 的实现/测试已存在，需求锚点一次补齐。 | Claude 提案，人裁照准 | 2026-10-25 |
| I03/I04 为什么不翻？ | 前端标签页行，后端无端点无测试；shared BASE 开发中，等前端仓实现后随 SSOT 一并推进。 | shared 树口径 | 2026-10-25 |

### 端点 → 功能 ID 对照

| 端点（ktor 实挂） | 功能 ID | 说明 |
|---|---|---|
| GET /api/auth/me | M00.F01.I01 | 当前用户会话（user+租户列表+currentTenantId） |
| POST /api/auth/switch-tenant | M00.F02.I01 | 选租户换发携带 tenant_id claim 的 token |
| GET /api/auth/menus | M01.F04.I01 | 按角色下发导航树（镜像 lab-msw） |
| GET /api/auth/permissions | M01.F04.I02 | RBAC 权限串列表 |
| GET /api/auth/sso/authorize | M01.F05.I02 | SSO 跳转（RFC 6749 §10.12 state） |
| POST /api/auth/sso/callback | M01.F05.I03 | SSO 回调（code 换 token + upsertByEmail） |
| POST /api/auth/refresh | M01.F05.I04 | 刷新 token（HS256 typ=refresh，内嵌 saas refresh） |
| POST /api/auth/logout | M01.F05.I05 | 登出（无状态，前端清存储） |
| POST /api/auth/native-login | M01.F05.I06 | 原生登录（saas IdP 代理，REQ-2026-023 方案 A） |

## 2. 验收标准

| 编号 | 场景（给定） | 操作（当） | 预期（则） |
|---|---|---|---|
| AC-1 | 本仓 gate 全量跑 | 查看 exit code | 0，L1-L5 全绿 |
| AC-2 | alignment 矩阵生成 | 查 9 个 ID 行 | 需求/设计/流程/测试四列全部非空 |
| AC-3 | GA 翻转提案 `--apply` | 查 function-tree | 9 ID 状态=已上线；I03/I04 保持开发中 |

## 3. 任务拆解

| 任务 ID | 任务描述 | 类型 | 负责人 | 预估 | 状态 |
|---|---|---|---|---|---|
| T-1 | 本 REQ 立档 + 端点→ID 对照登记 | 文档 | Claude | — | 已完成 |
| T-2 | design-function-map.md 填 9 行（实取 impl/存储） | 文档 | Claude | — | 已完成 |
| T-3 | flow-function-map.md 填 FLOW-01/02 | 文档 | Claude | — | 已完成 |
| T-4 | gate 全量绿 + 证据汇总给人 | 验证 | Claude | — | 已完成 |
| T-5 | 人工验收（人裁停点：人确认「通过」后落记录） | 人裁 | zcqiand | — | 已完成（2026-10-25 通过） |
| T-6 | mirror 提案 `--apply`：9× 开发中→已上线 | 树翻转 | Claude | --apply 免批 | 已完成（12 行翻转：9 I 级 + M00/M00.F01/M00.F02） |

## 4. 功能影响（需求与功能对齐的唯一位置）

| 功能 ID | 功能名称 | 影响类型 | 说明 | 关联任务 |
|---|---|---|---|---|
| M00.F01.I01 | 当前会话 | 变更 | 仅状态翻转（开发中→已上线），行为不变 | T-5/T-6 |
| M00.F02.I01 | 选租户换发 | 变更 | 同上 | T-5/T-6 |
| M01.F04.I01 | 动态菜单 | 变更 | 同上 | T-5/T-6 |
| M01.F04.I02 | 权限集 | 变更 | 同上 | T-5/T-6 |
| M01.F05.I02 | SSO 跳转 | 变更 | 同上 | T-5/T-6 |
| M01.F05.I03 | SSO 回调 | 变更 | 同上 | T-5/T-6 |
| M01.F05.I04 | 刷新 token | 变更 | 同上 | T-5/T-6 |
| M01.F05.I05 | 登出 | 变更 | 同上 | T-5/T-6 |
| M01.F05.I06 | 原生登录 | 变更 | 同上 | T-5/T-6 |

## 5. 流程影响

FLOW-01（认证会话主流程）+ FLOW-02（认证异常流程）已登记 `docs/design/flow-function-map.md`，覆盖上表 9 ID。无新增业务流程。

## 6. 测试锚定证据

| 测试文件（src/test/kotlin/io/xr/harness/） | 挂载 ID |
|---|---|
| AuthSessionTest.kt | M00.F01.I01、M00.F02.I01、M01.F04.I01、M01.F04.I02 |
| AuthSsoTest.kt | M01.F05.I02、M01.F05.I03 |
| AuthRefreshTest.kt | M01.F05.I04 |
| AuthNativeLoginTest.kt | M01.F05.I06（8 处）、M01.F05.I05 |
| contract-test 仓 auth.test.ts 等 | M01.F05.I06 等跨仓锚（.state/alignment.json 记录） |

## 7. 人工验收记录

> 本段是 mirror 免批 GA 翻转的凭据（GA_ACCEPT_RE）。验收人确认下述证据后由人落「通过」，模型不得代写。

- 证据包：gate L0-L5 全绿（2026-10-25，20 条目，软告警 13→2，余 2 条为 I03/I04 前端标签页行保持开发中的预期项）；alignment 矩阵 9 ID 需求/设计/流程/测试四列全覆盖；测试锚定清单见 §6
- 结论：**人工验收 2026-10-25 通过（zcqiand）**，9 ID 执行 mirror GA 翻转（I03/I04 除外，保持开发中）
