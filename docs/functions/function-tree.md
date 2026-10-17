# 功能清单（Function Tree）— Lab 管理系统 Kotlin/Ktor 端

> **全体系唯一锚点。** 需求、流程、设计、测试都引用这里的 ID。
> 不在这里的 ID 是悬空引用，L5 门会拦。**改功能，先改这份表。**

## 编号规则

| 层级 | 名称 | 格式 | 含义 |
|---|---|---|---|
| 一级 | 功能模块 | `M01` | 业务域边界，通常对应一级菜单 |
| 二级 | 功能 | `M01.F01` | 一个完整业务步骤 / 独立闭环流程 / 数据管理页面 |
| 三级 | 功能子项 | `M0x.F0y.I0z` | 技术交付单元 / 权限挂载点。对应一个 API 接口、页面组件、图表区块或权限控制点 |

**硬规则**

1. 编号单调递增，永不复用。废弃改状态，不删行。
2. 子项编号必须以父级为前缀。
3. 一个子项 = 一个权限点。权限码即 ID，不另起一套编码。
4. 拆不出子项的功能 → 它其实是子项，往上并。子项超 20 个 → 它其实是模块，往下拆。

**状态**：`规划` | `开发中` | `已上线` | `已废弃`
**子项类型**：`页面` | `标签页` | `查询` | `按钮` | `报表` | `接口`
**交付**：`前端+后端` | `仅前端` | `仅后端` | `后端`

---

## 模块总览

| 模块 ID | 模块名称 | 说明 | 状态 |
|---|---|---|---|
| M00 | 基础会话 | 当前用户会话 + 登录选租户（镜像 SSOT lab shared 树） | 开发中 |
| M01 | （待填） | | 规划 |

---

## M01 （待填）

| 功能 ID | 功能名称 | 说明 | 状态 |
|---|---|---|---|
| M00.F01 | 当前用户会话 | 当前用户信息 + 关联租户列表 + 当前选中租户（GET /auth/me） | 开发中 |
| M00.F02 | 登录选租户 | 登录后选择租户，换发携带 tenant_id claim 的 token（POST /auth/switch-tenant） | 开发中 |
| M01.F01 | （待填） | | 规划 |
| M01.F04 | 权限管理 | RBAC 角色权限、路由守卫、权限指令、动态菜单（身份平台下发） | 开发中 |
| M01.F05 | 认证管理 | 认证通道切片（ktor 契约面；Phase 4 首个纵切） | 开发中 |

## M00 基础会话（Phase 5 新增段，镜像 SSOT lab shared 树）

### M00.F01 当前用户会话

| 子项 ID | 名称 | 类型 | 交付 | 说明 | 状态 |
|---|---|---|---|---|---|
| M00.F01.I01 | 当前会话 | 接口 | 前端+后端 | GET /api/auth/me：user + 关联租户列表 + currentTenantId（token tenant_id claim，缺省 TENANT-001） | 开发中 |

### M00.F02 登录选租户

| 子项 ID | 名称 | 类型 | 交付 | 说明 | 状态 |
|---|---|---|---|---|---|
| M00.F02.I01 | 选租户换发 | 接口 | 前端+后端 | POST /api/auth/switch-tenant：校验租户归属后换发携带 tenant_id claim 的 token | 开发中 |

## M01 （待填）

### M01.F01 （待填）

| 子项 ID | 名称 | 类型 | 交付 | 说明 | 状态 |
|---|---|---|---|---|---|
| M01.F01.I01 | （待填） | 查询 | 后端 | | 规划 |

### M01.F04 权限管理

> Phase 5 新增段，镜像 SSOT lab shared 树 M01.F04；I03/I04 为前端标签页行（本仓无对应端点，随 SSOT 照录）。

| 子项 ID | 名称 | 类型 | 交付 | 说明 | 状态 |
|---|---|---|---|---|---|
| M01.F04.I01 | 动态菜单 | 接口 | 前端+后端 | GET /api/auth/menus：按角色下发导航树（5 根节点，镜像 lab-msw） | 开发中 |
| M01.F04.I02 | 权限集 | 接口 | 前端+后端 | GET /api/auth/permissions：RBAC 权限串列表（admin 全量 11 项） | 开发中 |
| M01.F04.I03 | 路由守卫（未登录/无权限拦截） | 标签页 | 前端+后端 | 前端路由守卫 useRequireAuth 钩子（react/vue 仓实现）；本仓 BASE 登记仓内未挂 entry，react/vue 仓 useRequireAuth 5+ 处引用作为产品线 anchor | 开发中 |
| M01.F04.I04 | 动态菜单（lab 侧边栏）          | 标签页 | 前端+后端 | 前端 useSidebarContainer 钩子锚点（nextjs 仓 `<aside>` 实现），无后端端点对应 | 开发中 |

### M01.F05 认证管理

> 登录通道切片（Phase 4：native-login + logout）+ 认证收尾（Phase 5：SSO/refresh）。语义参照：fastapi
> auth_impl（spec docs/superpowers/specs/2026-10-16-lab-kotlin-login-proxy-slice-design.md、
> 2026-10-17-lab-kotlin-auth-finish-slice-design.md）。镜像 SSOT lab shared 树 M01.F05；I01 已废弃
> （见废弃段），I02-I06 本仓在册。

| 子项 ID | 名称 | 类型 | 交付 | 说明 | 状态 |
|---|---|---|---|---|---|
| M01.F05.I06 | 原生登录 | 接口 | 前端+后端 | POST /api/auth/native-login：非浏览器客户端密码通道；校验代理 saas IdP——请求体账密调 saas /api/v1/auth/login（clientId=lab-management），saas 4xx→401 INVALID_CREDENTIALS 不透传、5xx/不可达→502 SAAS_UNAVAILABLE；lab 自家 JWT 重签（HS256），refresh 恒 dev-placeholder，LoginResponse 契约形状不变 | 开发中 |
| M01.F05.I05 | 登出 | 接口 | 前端+后端 | POST /api/auth/logout：无状态 JWT 服务端无 session，前端清存储 | 开发中 |
| M01.F05.I02 | SSO 跳转 | 接口 | 前端+后端 | GET /api/auth/sso/authorize：RFC 6749 §10.12 标准 state（前端生成、原样透传 saas 回显、前端比对），forward saas POST /api/v1/oauth/authorize 拿 code | 开发中 |
| M01.F05.I03 | SSO 回调 | 接口 | 前端+后端 | POST /api/auth/sso/callback:saas POST /api/v1/oauth/token 用一次性 code 换 token,再 /me/whoami + /me/tenants 拿 user,membership 信 saas;首次 SSO 按 email upsert 到 lab directory;state 校验在前端回跳比对 | 开发中 |
| M01.F05.I04 | 刷新 token | 接口 | 前端+后端 | POST /api/auth/refresh:lab refresh token 是 HS256 JWT(typ=refresh),内嵌 saas refresh token;调 saas POST /api/v1/oauth/token grantType=refresh_token 续,再签新 lab JWT | 开发中 |

---

## 已废弃功能子项

> 所有 `已废弃` / `已迁移` 状态的功能子项统一汇总到这里，**按子项 ID 顺序排列**。
> 模块级（F 级及以上）的已废弃 / 已迁移状态保留在各模块章节内说明；本节只收 I 级。
>
> 列字段说明：
> - **模块归属**：该 I 所属 F 所在模块（用于跨模块检索）
> - **迁移去向**：若为「已迁移」，标注新 ID；若为「已废弃」，说明废弃原因

| 子项 ID | 名称 | 模块归属 | 迁移去向 | 状态 |
|---|---|---|---|---|
| M01.F05.I01 | 密码登录 | M01 认证管理 | 由 M01.F05.I06 native-login 取代（SSOT shared 树 2026-09-17 标记已废弃；tsp op 暂留） | 已废弃 |

（登记示例：`| M03.F02.I01 | 旧角色 CRUD 列表 | M03 角色权限 | → M04.F01.I02（新结构） | 已迁移 |` ——
示例 ID 仅示意格式，勿与活跃段 ID 重复，否则 L5 报「编号永不复用」）

---

## 维护约定

- 谁改功能，谁改表，同一个 commit。
- `规划` → `开发中`：必须先有需求文档引用它。
- `开发中` → `已上线`：L5 会警告它缺设计映射与测试引用。警告不阻断，由人裁量。
- **已废弃 / 已迁移 I 级子项**：从原 F 段移到「已废弃功能子项」段，不删除行；F 级保留在新结构下。
