package io.xr.harness

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.resources.Resources
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.xr.lab.impl.AuthApiServiceImpl
import io.xr.lab.impl.BadRequestError
import io.xr.lab.impl.ConfigUserDirectory
import io.xr.lab.impl.HttpSaasAuthClient
import io.xr.lab.impl.HttpSaasMeClient
import io.xr.lab.impl.InvalidCredentialsError
import io.xr.lab.impl.MenusUnavailableError
import io.xr.lab.impl.NotFoundError
import io.xr.lab.impl.JwtIssuer
import io.xr.lab.impl.SaasAuthClient
import io.xr.lab.impl.SaasIdpUnavailableError
import io.xr.lab.impl.SaasMeClient
import io.xr.lab.impl.SnapshotCache
import io.xr.lab.shared.api.AuthApi
import io.xr.lab.shared.infrastructure.AppDelegates
import io.xr.lab.shared.infrastructure.BadParameterException
import io.xr.lab.shared.infrastructure.delegates
import io.xr.lab.shared.models.ErrorResponse
import io.xr.lab.shared.models.MenuNode
import io.xr.lab.shared.models.MyTenant
import java.net.URI

/**
 * 组合根：基础件装配 + delegates 挂载（ADR-0047）。测试注入 fake 客户端（mock-friendly 无网）；未挂载的 9 个 API 走接口默认
 * 500——显式未实现（决策 2）。
 */
fun Application.harnessModule(
    cfg: AppConfig,
    saasAuth: SaasAuthClient = HttpSaasAuthClient(cfg),
    saasMe: SaasMeClient = HttpSaasMeClient(cfg),
    directory: ConfigUserDirectory = ConfigUserDirectory(),
) {
  val jwt = JwtIssuer(cfg.jwtSigningKey, cfg.jwtIssuer, cfg.jwtTtlSeconds, cfg.jwtRefreshTtlSeconds)
  val menuCache = SnapshotCache.create<List<MenuNode>>()
  val membershipCache = SnapshotCache.create<List<MyTenant>>()

  install(ContentNegotiation) { json() }
  // 生成区 AuthApi 用 typed routing（post<Paths.…>），必须装 Resources 插件
  // （saas Phase 3 教训：缺它 = MissingApplicationPluginException 500）
  install(Resources)
  installCors(cfg)
  installErrorMapping()
  delegates(
      AppDelegates(
          authApiDelegate =
              AuthApiServiceImpl(directory, jwt, saasAuth, saasMe, menuCache, membershipCache),
      )
  )
  routing {
    // 家族探活契约形状：GET /health → {"ok":true,"mode":"ktor"}
    get("/health") { call.respondText("""{"ok":true,"mode":"ktor"}""") }
    AuthApi()
  }
}

fun main() {
  val cfg = appConfigFromEnv()
  embeddedServer(Netty, port = cfg.serverPort) { harnessModule(cfg) }.start(wait = true)
}

/** CORS 白名单（LAB_CORS_ALLOWED_ORIGINS，fastapi CORSMiddleware 同语义）。 */
private fun Application.installCors(cfg: AppConfig) {
  install(CORS) {
    cfg.corsOrigins.forEach { raw ->
      val origin = URI(raw)
      // ktor 3.6 allowHost 无 ports 参数（javap + 源码实证）：端口拼进 host 串
      // （host 按 Origin 头里的 host:port 形态匹配）；Origin 不带显式端口 = 默认端口
      val host = if (origin.port == -1) (origin.host ?: "") else "${origin.host}:${origin.port}"
      allowHost(host = host, schemes = listOf(origin.scheme))
    }
    // fastapi CORSMiddleware 口径（终审 Important 对齐）：allow_methods 含 CRUD 写动词
    // （ktor 默认只有 GET/POST/HEAD，Phase 5 跨域 PUT/PATCH/DELETE 会被浏览器拦）、
    // allow_credentials=true；headers 维持 Authorization/Content-Type 显式集
    allowMethod(HttpMethod.Put)
    allowMethod(HttpMethod.Patch)
    allowMethod(HttpMethod.Delete)
    allowCredentials = true
    allowHeader(HttpHeaders.Authorization)
    allowHeader(HttpHeaders.ContentType)
  }
}

/** 错误只走异常映射（ADR-0047 决策 1：impl 永不直接 respond，防双 respond 崩）。 */
private fun Application.installErrorMapping() {
  install(StatusPages) {
    exception<InvalidCredentialsError> { call, _ ->
      call.respond(
          HttpStatusCode.Unauthorized,
          ErrorResponse(code = "INVALID_CREDENTIALS", message = "invalid credentials"),
      )
    }
    // saas IdP 5xx/不可达 → 502（REQ-2026-023 五仓统一）
    exception<SaasIdpUnavailableError> { call, cause ->
      call.respond(
          HttpStatusCode.BadGateway,
          ErrorResponse(code = "SAAS_UNAVAILABLE", message = cause.message ?: "saas unavailable"),
      )
    }
    exception<NotFoundError> { call, cause ->
      call.respond(
          HttpStatusCode.NotFound,
          ErrorResponse(code = "NOT_FOUND", message = cause.message ?: "not found"),
      )
    }
    // 菜单快照 miss → 503 可恢复临时态（fastapi MENUS_UNAVAILABLE 镜像）
    exception<MenusUnavailableError> { call, cause ->
      call.respond(
          HttpStatusCode.ServiceUnavailable,
          ErrorResponse(code = "MENUS_UNAVAILABLE", message = cause.message ?: "menus unavailable"),
      )
    }
    exception<BadRequestError> { call, cause ->
      call.respond(
          HttpStatusCode.BadRequest,
          ErrorResponse(code = "BAD_REQUEST", message = cause.message ?: "bad request"),
      )
    }
    // 生成区 validate() 对契约约束违反抛 BadParameterException——形状并入 BAD_REQUEST
    exception<BadParameterException> { call, cause ->
      call.respond(
          HttpStatusCode.BadRequest,
          ErrorResponse(code = "BAD_REQUEST", message = cause.message ?: "bad request"),
      )
    }
    exception<BadRequestException> { call, _ ->
      call.respond(
          HttpStatusCode.BadRequest,
          ErrorResponse(code = "BAD_REQUEST", message = "malformed request body"),
      )
    }
    exception<Throwable> { call, _ ->
      call.respond(
          HttpStatusCode.InternalServerError,
          ErrorResponse(code = "INTERNAL", message = "internal error"),
      )
    }
  }
}
