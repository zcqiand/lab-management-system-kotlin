package io.xr.harness

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.xr.lab.impl.InvalidGrantError
import io.xr.lab.impl.JwtIssuer
import io.xr.lab.impl.SaasAuthClient
import io.xr.lab.impl.SaasCurrentUser
import io.xr.lab.impl.SaasTenantMembership
import io.xr.lab.impl.SaasAvailableTenant
import io.xr.lab.impl.SaasLoginResponse
import io.xr.lab.impl.SaasLoginUser
import io.xr.lab.impl.SaasMeClient
import io.xr.lab.impl.SaasMenuNode
import io.xr.lab.impl.SaasPlatformTenant
import io.xr.lab.impl.SaasToken
import io.xr.lab.impl.SaasUpstreamError
import io.xr.lab.impl.UnauthorizedClientError
import io.xr.lab.shared.models.ErrorResponse
import io.xr.lab.shared.models.LoginResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * M01.F05.I06 native-login 代理链 + I05 logout（fastapi auth_impl 七步镜像）； saas IdP 全走 fake（无网）。路径以
 * Paths.kt 生成物为准。
 */
@Suppress("TooManyFunctions")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthNativeLoginTest {

  private val cfg = testAppConfig()

  // 断言 lab JWT claims 用：与 harnessModule 内部构造同 cfg 同参 → 同签名密钥，verify 互通
  private val jwt =
      JwtIssuer(cfg.jwtSigningKey, cfg.jwtIssuer, cfg.jwtTtlSeconds, cfg.jwtRefreshTtlSeconds)

  /** 可编程 fake：按用例注入 saas 响应/异常（mock-friendly 无网）。 */
  private class FakeSaasAuth(
      private val login: () -> SaasLoginResponse = { throw SaasUpstreamError("no stub") },
      private val service: () -> SaasToken = { throw SaasUpstreamError("no stub") },
  ) : SaasAuthClient {
    override suspend fun userLogin(username: String, password: String): SaasLoginResponse = login()

    override suspend fun serviceLogin(): SaasToken = service()

    override suspend fun token(
        grantType: String,
        code: String?,
        refreshToken: String?,
        redirectUri: String?,
    ): SaasToken = throw SaasUpstreamError("no stub")
  }

  private class FakeSaasMe(
      private val tenants: () -> List<SaasPlatformTenant> = { emptyList() },
      private val menus: () -> List<SaasMenuNode> = { emptyList() },
  ) : SaasMeClient {
    override suspend fun listPlatformTenants(accessToken: String): List<SaasPlatformTenant> =
        tenants()

    override suspend fun listMyMenus(accessToken: String, appCode: String): List<SaasMenuNode> =
        menus()

    override suspend fun whoami(accessToken: String): SaasCurrentUser =
        throw SaasUpstreamError("no stub")

    override suspend fun listMyTenants(accessToken: String): List<SaasTenantMembership> =
        throw SaasUpstreamError("no stub")
  }

  private val happyLogin: () -> SaasLoginResponse = {
    SaasLoginResponse(
        user =
            SaasLoginUser(
                id = "saas-u-1",
                username = "alice",
                email = "alice@lab",
                displayName = "",
            ),
        availableTenants =
            listOf(
                SaasAvailableTenant(
                    tenantId = "TENANT-001",
                    roleIds = listOf("admin"),
                    status = "active",
                ),
                SaasAvailableTenant(
                    tenantId = "TENANT-002",
                    roleIds = listOf("viewer"),
                    status = "removed",
                ),
            ),
        accessToken = "saas-at",
        refreshToken = "saas-rt-must-not-leak",
        userId = "saas-u-1",
        currentTenantId = "TENANT-001",
    )
  }

  /** 唯一请求 helper：testApplication 挂 harnessModule(cfg, saasAuth, saasMe)，打任意 auth 路径。 */
  private fun postLoginOn(
      path: String,
      body: String,
      saasAuth: SaasAuthClient,
      saasMe: SaasMeClient,
  ): Pair<HttpStatusCode, String> {
    lateinit var status: HttpStatusCode
    lateinit var text: String
    runBlocking {
      testApplication {
        application { harnessModule(cfg, saasAuth, saasMe) }
        val resp =
            client.post(path) {
              contentType(ContentType.Application.Json)
              setBody(body)
            }
        status = resp.status
        text = resp.bodyAsText()
      }
    }
    return status to text
  }

  private fun postLogin(body: String, saasAuth: SaasAuthClient, saasMe: SaasMeClient) =
      postLoginOn("/api/auth/native-login", body, saasAuth, saasMe)

  @Test
  @Fn(["M01.F05.I06"])
  fun nativeLoginHappyPathReturnsContractShape() {
    val (status, body) =
        postLogin(
            """{"username":"alice","password":"dev123456"}""",
            FakeSaasAuth(login = happyLogin),
            FakeSaasMe(
                tenants = {
                  listOf(
                      SaasPlatformTenant(
                          id = "TENANT-001",
                          name = "市住建工程质量检测中心",
                          tenantKey = "city-lab",
                      )
                  )
                },
                menus = { listOf(SaasMenuNode(id = "m1", title = "样品管理", type = "group")) },
            ),
        )
    assertEquals(HttpStatusCode.OK, status)
    val dto = Json.decodeFromString<LoginResponse>(body)
    assertEquals("saas-u-1", dto.user.id)
    assertEquals("alice", dto.user.username)
    // displayName 空串回退链：displayName→username→email→未知用户（空串 falsy，不是 null 判断）
    assertEquals("alice", dto.user.displayName)
    assertEquals("admin", dto.user.roleCode) // 首租户 roleIds[0]
    assertEquals(listOf("TENANT-001"), dto.tenants.map { it.tenantId }) // removed 已滤
    assertEquals("city-lab", dto.tenants.single().code) // 富化命中
    assertEquals("市住建工程质量检测中心", dto.tenants.single().name)
    // lab 自家 JWT：claims 可验、tenant_id=saas currentTenantId
    val claims = jwt.verify(dto.token)
    assertEquals("saas-u-1", claims.subject)
    assertEquals("TENANT-001", claims.get("tenant_id", String::class.java))
    // Review Focus 5：refresh 恒 dev-placeholder，不透传 saas 值
    assertTrue(
        dto.refreshToken?.isNotBlank() == true && dto.refreshToken != "saas-rt-must-not-leak"
    )
    val refreshClaims = jwt.verify(dto.refreshToken!!)
    assertEquals("refresh", refreshClaims.get("typ", String::class.java))
    assertEquals("dev-placeholder", refreshClaims.get("saas_refresh_token", String::class.java))
  }

  @Test
  @Fn(["M01.F05.I06"])
  fun displayNameFallsThroughEmptyChainToUnknown() {
    val (status, body) =
        postLogin(
            """{"username":"nobody@lab","password":"x"}""",
            FakeSaasAuth(
                login = {
                  SaasLoginResponse(
                      user =
                          SaasLoginUser(id = "u2", username = "", email = "", displayName = null),
                      accessToken = "at",
                      userId = "u2",
                      currentTenantId = null,
                  )
                }
            ),
            FakeSaasMe(),
        )
    assertEquals(HttpStatusCode.OK, status)
    val dto = Json.decodeFromString<LoginResponse>(body)
    assertEquals("未知用户", dto.user.displayName) // 全空链末端
    assertEquals("u2", dto.user.id) // user.id → login.userId 回退
  }

  @Test
  @Fn(["M01.F05.I06"])
  fun enrichmentMissDegradesToTenantId() {
    val (status, body) =
        postLogin(
            """{"username":"alice","password":"dev123456"}""",
            FakeSaasAuth(login = happyLogin),
            FakeSaasMe(tenants = { throw SaasUpstreamError("enrich down") }), // 富化失败
        )
    assertEquals(HttpStatusCode.OK, status) // Review Focus 3：不阻塞登录
    val dto = Json.decodeFromString<LoginResponse>(body)
    assertEquals("TENANT-001", dto.tenants.single().code) // 降级 code=tenantId
    assertEquals("TENANT-001", dto.tenants.single().name) // 降级 name=tenantId
  }

  @Test
  @Fn(["M01.F05.I06"])
  fun menuSnapshotFailureDoesNotBlockLogin() {
    val (status, _) =
        postLogin(
            """{"username":"alice","password":"dev123456"}""",
            FakeSaasAuth(login = happyLogin),
            FakeSaasMe(menus = { throw SaasUpstreamError("menu down") }), // 菜单失败
        )
    assertEquals(HttpStatusCode.OK, status) // Review Focus 3
  }

  @Test
  @Fn(["M01.F05.I06"])
  fun wrongPasswordIs401WithoutSaasCodePassthrough() {
    val (status, body) =
        postLogin(
            """{"username":"alice","password":"bad"}""",
            FakeSaasAuth(
                login = {
                  throw InvalidGrantError("""saas auth/login 400 {"error":"invalid_grant"}""")
                }
            ),
            FakeSaasMe(),
        )
    assertEquals(HttpStatusCode.Unauthorized, status)
    val err = Json.decodeFromString<ErrorResponse>(body)
    assertEquals("INVALID_CREDENTIALS", err.code)
    assertTrue(!body.contains("invalid_grant")) // Review Focus 2：saas 内部码不透传
  }

  @Test
  @Fn(["M01.F05.I06"])
  fun saasUnauthorizedIs401SameSemantics() {
    val (status, body) =
        postLogin(
            """{"username":"alice","password":"bad"}""",
            FakeSaasAuth(login = { throw UnauthorizedClientError("saas auth/login 401") }),
            FakeSaasMe(),
        )
    assertEquals(HttpStatusCode.Unauthorized, status)
    assertEquals("INVALID_CREDENTIALS", Json.decodeFromString<ErrorResponse>(body).code)
  }

  @Test
  @Fn(["M01.F05.I06"])
  fun saasUnreachableIs502() {
    val (status, body) =
        postLogin(
            """{"username":"alice","password":"dev123456"}""",
            FakeSaasAuth(login = { throw SaasUpstreamError("saas auth/login connect failed") }),
            FakeSaasMe(),
        )
    assertEquals(HttpStatusCode.BadGateway, status) // Review Focus 1：502 非 500
    assertEquals("SAAS_UNAVAILABLE", Json.decodeFromString<ErrorResponse>(body).code)
  }

  @Test
  @Fn(["M01.F05.I06"])
  fun blankUsernameOrPasswordIs400() {
    val (status, body) =
        postLogin("""{"username":"  ","password":"x"}""", FakeSaasAuth(), FakeSaasMe())
    assertEquals(HttpStatusCode.BadRequest, status)
    assertEquals("BAD_REQUEST", Json.decodeFromString<ErrorResponse>(body).code)
  }

  @Test
  @Fn(["M01.F05.I05"])
  fun logoutIsNoOpSuccess() {
    val (status, _) =
        postLoginOn(
            "/api/auth/logout",
            """{"token":"whatever"}""",
            FakeSaasAuth(),
            FakeSaasMe(),
        )
    // Review Focus 3（spec）：只断 2xx，确切码（204 vs 200 空体）CT live 定版（ADR-0047 后果）
    assertTrue(status.value in 200..299, "logout 应 2xx，实收 $status")
  }

  @Test
  fun zeroTenantSaasUserGetsEmptyTenantsNotDemoFallback() {
    // 终审 Important 补钉：saas 零租户用户——空列表保持空（fastapi `tenants if tenants is not
    // None` 镜像：密码路径空即空），不得回落 demo 字面量租户（hard-rules §3 同族：身份字段禁兜 demo）
    val (status, body) =
        postLogin(
            """{"username":"lonely","password":"x"}""",
            FakeSaasAuth(
                login = {
                  SaasLoginResponse(
                      user = SaasLoginUser(id = "u9", username = "lonely"),
                      availableTenants = emptyList(),
                      accessToken = "at",
                      userId = "u9",
                      currentTenantId = null,
                  )
                }
            ),
            FakeSaasMe(),
        )
    assertEquals(HttpStatusCode.OK, status)
    val dto = Json.decodeFromString<LoginResponse>(body)
    assertEquals(emptyList<String>(), dto.tenants.map { it.tenantId }) // 现实现兜 3 个 demo 租户 → RED
    assertTrue(!body.contains("city-lab"), "demo 租户字面量不得出现")
  }

  @Test
  fun deprecatedLoginSharesSameChain() {
    // I01 已废弃不挂 ID；同链冒烟：/auth/login 与 native-login 同响应
    val (nativeStatus, _) =
        postLogin(
            """{"username":"alice","password":"dev123456"}""",
            FakeSaasAuth(login = happyLogin),
            FakeSaasMe(),
        )
    val (loginStatus, _) =
        postLoginOn(
            "/api/auth/login",
            """{"username":"alice","password":"dev123456"}""",
            FakeSaasAuth(login = happyLogin),
            FakeSaasMe(),
        )
    assertEquals(nativeStatus, loginStatus)
  }
}
