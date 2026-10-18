package io.xr.harness

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.xr.lab.impl.ConfigUserDirectory
import io.xr.lab.impl.JwtIssuer
import io.xr.lab.impl.SaasAuthClient
import io.xr.lab.impl.SaasAvailableTenant
import io.xr.lab.impl.SaasCurrentUser
import io.xr.lab.impl.SaasLoginResponse
import io.xr.lab.impl.SaasLoginUser
import io.xr.lab.impl.SaasMeClient
import io.xr.lab.impl.SaasMenuNode
import io.xr.lab.impl.SaasPlatformTenant
import io.xr.lab.impl.SaasTenantMembership
import io.xr.lab.impl.SaasToken
import io.xr.lab.impl.SaasUpstreamError
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * me / switch-tenant / menus / permissions 四端点（fastapi auth_impl 镜像，Phase 5 T3）； 快照三分支与
 * requireBearer 边界钉死。路径以 Paths.kt 生成物为准。 多步流程在同一 testApplication 内走完——快照缓存是模块内内存态， 跨请求共享（Phase 4
 * 单请求测试未暴露此前提）。
 */
@Suppress("TooManyFunctions")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthSessionTest {

  private val cfg = testAppConfig()

  // 组合根同款目录实例（经 harnessModule 注入缝传入）：SSO 用例借 setSaasRefreshToken 种档
  private val directory = ConfigUserDirectory()

  // 与 harnessModule 内部构造同 cfg → 同签名密钥，verify 互通
  private val jwt =
      JwtIssuer(cfg.jwtSigningKey, cfg.jwtIssuer, cfg.jwtTtlSeconds, cfg.jwtRefreshTtlSeconds)

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
      private val whoami: () -> SaasCurrentUser = { throw SaasUpstreamError("no stub") },
      private val myTenants: () -> List<SaasTenantMembership> = {
        throw SaasUpstreamError("no stub")
      },
  ) : SaasMeClient {
    override suspend fun listPlatformTenants(accessToken: String): List<SaasPlatformTenant> =
        tenants()

    override suspend fun listMyMenus(accessToken: String, appCode: String): List<SaasMenuNode> =
        menus()

    override suspend fun whoami(accessToken: String): SaasCurrentUser = whoami()

    override suspend fun listMyTenants(accessToken: String): List<SaasTenantMembership> =
        myTenants()
  }

  /** 双租户登录桩：TENANT-001 active + TENANT-002 active（switch 用例的归属集）。 */
  private val twoTenantLogin: () -> SaasLoginResponse = {
    SaasLoginResponse(
        user = SaasLoginUser(id = "saas-u-1", username = "alice", email = "alice@lab"),
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
                    status = "active",
                ),
            ),
        accessToken = "saas-at",
        userId = "saas-u-1",
        currentTenantId = "TENANT-001",
    )
  }

  /** 单应用作用域：一个 testApplication 挂一个 harnessModule，块内多请求共享内存快照。 */
  private fun <T : Any> withApp(
      saasAuth: SaasAuthClient,
      saasMe: SaasMeClient,
      block: suspend (HttpClient) -> T,
  ): T {
    lateinit var result: T
    runBlocking {
      testApplication {
        application { harnessModule(cfg, saasAuth, saasMe, directory) }
        result = block(client)
      }
    }
    return result
  }

  private suspend fun HttpClient.postJson(
      path: String,
      body: String,
      token: String? = null,
  ): Pair<HttpStatusCode, String> {
    val resp =
        post(path) {
          contentType(ContentType.Application.Json)
          setBody(body)
          token?.let { header("Authorization", "Bearer $it") }
        }
    return resp.status to resp.bodyAsText()
  }

  private suspend fun HttpClient.getWith(
      path: String,
      token: String? = null,
      raw: String? = null,
  ): Pair<HttpStatusCode, String> {
    val resp =
        get(path) {
          when {
            raw != null -> header("Authorization", raw)
            token != null -> header("Authorization", "Bearer $token")
          }
        }
    return resp.status to resp.bodyAsText()
  }

  private suspend fun HttpClient.getJson(path: String, token: String? = null) =
      getWith(path, token = token)

  /** 登录并返回 token（密码登录即写双快照，Phase 4 doLogin 链）。 */
  private suspend fun HttpClient.loginForToken(): String {
    val (_, body) = postJson("/api/auth/native-login", """{"username":"alice","password":"x"}""")
    return tokenOf(body)
  }

  private fun tokenOf(body: String): String =
      Json.parseToJsonElement(body).jsonObject["token"]?.jsonPrimitive?.content
          ?: error("no token: $body")

  private fun bodyField(body: String, key: String): String? =
      Json.parseToJsonElement(body).jsonObject[key]?.jsonPrimitive?.contentOrNull

  // === me（M00.F01.I01）===

  @Test
  @Fn(["M00.F01.I01"])
  fun `me snapshot hit prefers tenant_id claim`() {
    val (status, body) =
        withApp(FakeSaasAuth(login = twoTenantLogin), FakeSaasMe()) { client ->
          val token = client.loginForToken()
          client.getJson("/api/auth/me", token)
        }
    assertEquals(HttpStatusCode.OK, status)
    val obj = Json.parseToJsonElement(body).jsonObject
    assertEquals("saas-u-1", obj["user"]!!.jsonObject["id"]!!.jsonPrimitive.content)
    assertEquals(
        listOf("TENANT-001", "TENANT-002"),
        obj["tenants"]!!.jsonArray.map { it.jsonObject["tenantId"]!!.jsonPrimitive.content },
    )
    assertEquals("TENANT-001", bodyField(body, "currentTenantId"))
  }

  @Test
  @Fn(["M00.F01.I01"])
  fun `me claim tenant wins over directory default`() {
    // demo 用户（目录直查 USER-A）+ token claim TENANT-002 + 无快照 → 目录兜底分支，claim 优先实证
    val token = jwt.issue("USER-A", "TENANT-002")
    val (status, body) = withApp(FakeSaasAuth(), FakeSaasMe()) { it.getJson("/api/auth/me", token) }
    assertEquals(HttpStatusCode.OK, status)
    assertEquals("TENANT-002", bodyField(body, "currentTenantId"))
    assertTrue(body.contains("TENANT-001") && body.contains("TENANT-003"), "目录兜底返回全 3 租户")
  }

  @Test
  @Fn(["M00.F01.I01"])
  fun `me sso user cache miss asks refresh not demo fallback`() {
    // SSO 用户（目录持 saas refresh）快照 miss → 401 提示自愈，禁 demo 目录兜底（Review Focus 1）。
    // message 透传钉子（5.110② M1 收口，saas 5.116① 同款）：fastapi auth_impl:195 透传
    // "cache miss; refresh required" 自愈提示，前端可辨——kotlin 收敛固定 "invalid credentials"
    // 是超集漂移，401 判据 + 文案断言双钉
    directory.upsert("u-sso", "sso@lab", "SSO 用户", null)
    directory.setSaasRefreshToken("u-sso", "rt-sso")
    val token = jwt.issue("u-sso", null)
    val (status, body) = withApp(FakeSaasAuth(), FakeSaasMe()) { it.getJson("/api/auth/me", token) }
    assertEquals(HttpStatusCode.Unauthorized, status)
    assertTrue(body.contains("INVALID_CREDENTIALS"))
    assertEquals(
        "membership snapshot unavailable for user u-sso (cache miss); refresh required",
        bodyField(body, "message"),
    )
    assertTrue(!body.contains("TENANT-001"), "SSO 用户 miss 不得落 demo 租户: $body")
  }

  @Test
  @Fn(["M00.F01.I01"])
  fun `me password user cache miss falls back to directory`() {
    val token = jwt.issue("USER-A", null)
    val (status, body) = withApp(FakeSaasAuth(), FakeSaasMe()) { it.getJson("/api/auth/me", token) }
    assertEquals(HttpStatusCode.OK, status)
    assertEquals("TENANT-001", bodyField(body, "currentTenantId")) // defaultTenant 兜底
    assertTrue(body.contains("TENANT-003"))
  }

  @Test
  @Fn(["M00.F01.I01"])
  fun `me unknown sub is 401`() {
    val token = jwt.issue("ghost", null)
    val (status, body) = withApp(FakeSaasAuth(), FakeSaasMe()) { it.getJson("/api/auth/me", token) }
    assertEquals(HttpStatusCode.Unauthorized, status)
    assertTrue(body.contains("INVALID_CREDENTIALS"))
  }

  @Test
  fun `me bearer boundaries are 401`() {
    // Review Focus 4：无头 / 非 Bearer 前缀 / Bearer 空串，三路 401（设施面不挂 ID）
    val (s1, _) = withApp(FakeSaasAuth(), FakeSaasMe()) { it.getJson("/api/auth/me") }
    assertEquals(HttpStatusCode.Unauthorized, s1)
    val (s2, _) =
        withApp(FakeSaasAuth(), FakeSaasMe()) { it.getWith("/api/auth/me", raw = "Basic abc") }
    assertEquals(HttpStatusCode.Unauthorized, s2)
    val (s3, _) = withApp(FakeSaasAuth(), FakeSaasMe()) { it.getJson("/api/auth/me", "") }
    assertEquals(HttpStatusCode.Unauthorized, s3)
  }

  // === switch-tenant（M00.F02.I01）===

  @Test
  @Fn(["M00.F02.I01"])
  fun `switch tenant reissues with target claim`() {
    val (status, body) =
        withApp(FakeSaasAuth(login = twoTenantLogin), FakeSaasMe()) { client ->
          val token = client.loginForToken()
          client.postJson("/api/auth/switch-tenant", """{"tenantId":"TENANT-002"}""", token)
        }
    assertEquals(HttpStatusCode.OK, status)
    val claims = jwt.verify(tokenOf(body))
    assertEquals("TENANT-002", claims.get("tenant_id", String::class.java))
    // refresh 恒 dev-placeholder（密码路径不透传 saas 值，Review Focus 3 同族）
    val refreshClaims = jwt.verify(bodyField(body, "refreshToken")!!)
    assertEquals("refresh", refreshClaims.get("typ", String::class.java))
    assertEquals("dev-placeholder", refreshClaims.get("saas_refresh_token", String::class.java))
  }

  @Test
  @Fn(["M00.F02.I01"])
  fun `switch tenant outside membership is 404`() {
    val (status, body) =
        withApp(FakeSaasAuth(login = twoTenantLogin), FakeSaasMe()) { client ->
          val token = client.loginForToken()
          client.postJson("/api/auth/switch-tenant", """{"tenantId":"TENANT-003"}""", token)
        }
    assertEquals(HttpStatusCode.NotFound, status)
    assertTrue(body.contains("NOT_FOUND"), "实收: $body")
  }

  // === menus（M01.F04.I01）===

  @Test
  @Fn(["M01.F04.I01"])
  fun `menus hit returns snapshot`() {
    val saasMe =
        FakeSaasMe(
            menus = {
              listOf(
                  SaasMenuNode(
                      id = "g1",
                      title = "样品管理",
                      type = "group",
                      sortOrder = 2,
                      children =
                          listOf(
                              SaasMenuNode(
                                  id = "p2",
                                  title = "样品登记",
                                  type = "page",
                                  sortOrder = 2,
                                  path = "/samples/new",
                              ),
                              SaasMenuNode(
                                  id = "p1",
                                  title = "样品列表",
                                  type = "page",
                                  sortOrder = 1,
                                  path = "/samples",
                              ),
                          ),
                  ),
              )
            },
        )
    val (status, body) =
        withApp(
            // 菜单快照走服务账号链 serviceLogin→listMyMenus：service 桩必须给 token，否则快照
            // 落空 → 503（best-effort 吞错不阻塞登录）
            FakeSaasAuth(
                login = twoTenantLogin,
                service = { SaasToken(accessToken = "svc-at", refreshToken = "svc-rt") },
            ),
            saasMe,
        ) { client ->
          val token = client.loginForToken()
          client.getJson("/api/auth/menus", token)
        }
    assertEquals(HttpStatusCode.OK, status)
    val roots = Json.parseToJsonElement(body).jsonArray
    assertEquals(1, roots.size)
    val root = roots[0].jsonObject
    assertEquals("样品管理", root["label"]!!.jsonPrimitive.content) // title→label 映射
    val children = root["children"]!!.jsonArray
    assertEquals("样品列表", children[0].jsonObject["label"]!!.jsonPrimitive.content) // sortOrder 升序
    assertEquals("样品登记", children[1].jsonObject["label"]!!.jsonPrimitive.content)
  }

  @Test
  @Fn(["M01.F04.I01"])
  fun `menus cache miss is 503 menus unavailable`() {
    val token = jwt.issue("ghost2", null)
    val (status, body) =
        withApp(FakeSaasAuth(), FakeSaasMe()) { it.getJson("/api/auth/menus", token) }
    assertEquals(HttpStatusCode.ServiceUnavailable, status)
    assertTrue(body.contains("MENUS_UNAVAILABLE"), "实收: $body")
  }

  // === permissions（M01.F04.I02）===

  @Test
  @Fn(["M01.F04.I02"])
  fun `permissions returns admin full set`() {
    val (status, body) =
        withApp(FakeSaasAuth(login = twoTenantLogin), FakeSaasMe()) { client ->
          val token = client.loginForToken()
          client.getJson("/api/auth/permissions", token)
        }
    assertEquals(HttpStatusCode.OK, status)
    val permissions =
        Json.parseToJsonElement(body).jsonObject["permissions"]!!.jsonArray.map {
          it.jsonPrimitive.content
        }
    assertEquals(
        listOf(
            "contract:read",
            "contract:write",
            "sample:read",
            "sample:write",
            "report:read",
            "report:write",
            "report:issue",
            "inspection:read",
            "inspection:write",
            "audit:read",
            "*",
        ),
        permissions,
    )
  }

  // === me 空租户快照（M00.F01.I01，终审 I2 家族级修复）===

  @Test
  @Fn(["M00.F01.I01"])
  fun `me empty membership snapshot is empty current tenant not 500`() {
    // 零 active 租户用户登录（快照空列表 + token 无 tenant_id claim）→ me 200 空态
    // （tenants=[] + currentTenantId=""，空即空禁 demo 兜底），非 NoSuchElementException → 500
    val zeroTenantLogin: () -> SaasLoginResponse = {
      SaasLoginResponse(
          user = SaasLoginUser(id = "saas-u-2", username = "oscar", email = "oscar@lab"),
          availableTenants = listOf(),
          accessToken = "saas-at",
          userId = "saas-u-2",
          currentTenantId = null,
      )
    }
    val (status, body) =
        withApp(FakeSaasAuth(login = zeroTenantLogin), FakeSaasMe()) { client ->
          val token = client.loginForToken()
          client.getJson("/api/auth/me", token)
        }
    assertEquals(HttpStatusCode.OK, status)
    assertEquals("", bodyField(body, "currentTenantId"))
    assertEquals(
        0,
        Json.parseToJsonElement(body).jsonObject["tenants"]!!.jsonArray.size,
    )
  }
}
