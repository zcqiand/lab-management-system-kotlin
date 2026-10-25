# 设计与功能对齐 — Lab 管理系统 Kotlin/Ktor 端

> 人填、人评审。机器只检查功能 ID 存在性。
> 回答一个问题：**这个功能子项，落到哪段代码、哪张表、哪个权限码上？**
> 答不上来的行，说明设计没做完，别开工。

## 映射表

| 功能子项 ID | 页面/组件 | 接口 | 数据表 | 权限码 | 设计稿 | 状态 |
|---|---|---|---|---|---|---|
| M00.F01.I01 | —（后端仓） | AuthApiServiceImpl#authGetCurrentUser · GET /api/auth/me | 内存 ConfigUserDirectory + SnapshotCache（saas 快照，TTL）+ saas IdP 出站（HttpSaasClients）；本仓 auth 面无自有 PG 表 | M00.F01.I01 | - | 开发中 |
| M00.F02.I01 | —（后端仓） | AuthApiServiceImpl#authSwitchTenant · POST /api/auth/switch-tenant | 同上（归属校验走目录/快照，JWT 重签不落表） | M00.F02.I01 | - | 开发中 |
| M01.F04.I01 | —（后端仓） | AuthApiServiceImpl#authGetMenus · GET /api/auth/menus | SnapshotCache（菜单快照三分支） | M01.F04.I01 | - | 开发中 |
| M01.F04.I02 | —（后端仓） | AuthApiServiceImpl#authGetPermissions · GET /api/auth/permissions | SnapshotCache（权限快照） | M01.F04.I02 | - | 开发中 |
| M01.F05.I02 | —（后端仓） | AuthApiServiceImpl#authSsoAuthorize · GET /api/auth/sso/authorize | saas IdP 出站（HttpSaasClients → POST /api/v1/oauth/authorize）；state 前端生成/比对 | M01.F05.I02 | - | 开发中 |
| M01.F05.I03 | —（后端仓） | AuthApiServiceImpl#authSsoCallback · POST /api/auth/sso/callback | saas IdP 出站（code 换 token + whoami/tenants）+ ConfigUserDirectory（upsertByEmail）+ JwtIssuer 重签 | M01.F05.I03 | - | 开发中 |
| M01.F05.I04 | —（后端仓） | AuthApiServiceImpl#authRefresh · POST /api/auth/refresh | JwtIssuer（HS256 typ=refresh 双校验）+ saas IdP 出站（refresh_token grant）+ rotate-once | M01.F05.I04 | - | 开发中 |
| M01.F05.I05 | —（后端仓） | AuthApiServiceImpl#authLogout · POST /api/auth/logout | 无（无状态 JWT，前端清存储） | M01.F05.I05 | - | 开发中 |
| M01.F05.I06 | —（后端仓） | AuthApiServiceImpl#authNativeLogin · POST /api/auth/native-login | saas IdP 出站（账密代理 /api/v1/auth/login，clientId=lab-management）+ ConfigUserDirectory + JwtIssuer 重签 | M01.F05.I06 | - | 开发中 |

## 约定

1. **权限码 = 功能子项 ID。** 前端按钮的权限判断直接写 ID。
2. 一个接口服务多个子项时，多行重复写。不要为表好看而合并 —— 合并后看不清接口还有没有别的调用方。
3. 状态列必须与功能清单一致。不一致以功能清单为准。

## 评审时问这三个问题

1. 有没有子项没有权限码？→ 那它就是任何人都能点的按钮
2. 有没有一张表被三个以上模块直接写入？→ 边界破了
3. 「开发中」的行里接口和表填了吗？→ 没填就是还在纸上，别报进度
