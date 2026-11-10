# Lab 管理系统 Kotlin/Ktor 端

Lab 管理系统 Kotlin/Ktor 端（`lab-management-system-kotlin`）——一句话系统定位（待补）。

本仓为《（书稿信息待补）》案例（待补）的可运行配套工程，是书稿代码块的 **source of truth**。

## 快速开始

```bash
# 前置：gradle 在 PATH（本机 gradle-9.8.1，见 memory: gate-ops-ledger）
gradle ktfmtCheck       # L1 格式
gradle detekt           # L2 静态
gradle compileKotlin    # L3 编译
gradle test             # L4 测试
gradle run              # 本地起服务（mainClass io.xr.harness.ApplicationKt，SERVER_PORT=5209）
```

## 功能特性

按特性分点描述核心功能（见 `docs/functions/function-tree.md`）。

## 技术栈

| 技术 | 版本 |
| :--- | :--- |
| Kotlin / Ktor | 版本全集见 version-lock.json（gradle 9.8.1 / detekt / ktfmt / exposed…） |

> 依赖版本与 `version-lock.json` 的 `version_lock` 一致，不引入 lock 外的库。

## 配套书籍及章节映射

| 章 | 主题 | 对应源文件 |
| :--- | :--- | :--- |
| （待补） | | |

### 书一《Kotlin 从入门到项目实践》（卷四）

- 书稿定位：xr-know-020，第 28、29、30、31 章正文代码块挂本仓 `source=` 锚点
- 配套版本：`v0.2.1-20261025`（registry 冻结 tag，代码以此为准）

| 章 | 章题 | 对应源文件 |
| :--- | :--- | :--- |
| 28 | 项目一启动：建筑工程实验室管理系统需求与架构 | `src/main/kotlin/io/xr/harness/Application.kt` + `src/main/kotlin/io/xr/lab/shared/api/AuthApi.kt` + `src/main/kotlin/io/xr/lab/impl/AuthApiServiceImpl.kt` + `src/main/kotlin/io/xr/lab/impl/Errors.kt` + `src/test/kotlin/io/xr/harness/AuthSessionTest.kt` |
| 29 | 项目一：数据模型与仓储层 | `src/main/kotlin/io/xr/lab/impl/Directory.kt` + `src/main/kotlin/io/xr/lab/impl/SnapshotCaches.kt` + `src/main/kotlin/io/xr/lab/impl/JwtIssuer.kt` + `src/main/kotlin/io/xr/lab/impl/AuthApiServiceImpl.kt` + `src/test/kotlin/io/xr/harness/AuthSessionTest.kt` |
| 30 | 项目一：检测业务模块与流程引擎 | `src/main/kotlin/io/xr/lab/impl/AuthApiServiceImpl.kt` + `src/main/kotlin/io/xr/lab/impl/Security.kt` + `src/main/kotlin/io/xr/lab/impl/SnapshotCaches.kt` + `src/main/kotlin/io/xr/harness/Application.kt` + `src/test/kotlin/io/xr/harness/AuthSessionTest.kt` |
| 31 | 项目一：测试防线、门控与部署收官 | `src/test/kotlin/io/xr/harness/AuthNativeLoginTest.kt` + `src/test/kotlin/io/xr/harness/AuthSsoTest.kt` + `src/test/kotlin/io/xr/harness/AuthRefreshTest.kt` + `src/main/kotlin/io/xr/lab/impl/AuthApiServiceImpl.kt` + `src/main/kotlin/io/xr/lab/impl/HttpSaasClients.kt` + `src/main/kotlin/io/xr/harness/AppConfig.kt` |
## 快速链接

- [CLAUDE.md](CLAUDE.md) — 开发约定与编码规范
- [系统架构.md](docs/ARCHITECTURE.md) — 结构 / 边界 / 数据流 / 决策
- [功能规格.md](docs/functions/function-tree.md) — 功能名称、描述与验收标准
- [未来开发计划](PLAN.md) — 待办与迭代方向
- [更新日志](CHANGELOG.md) — 版本变更记录
