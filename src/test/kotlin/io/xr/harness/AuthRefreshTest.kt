package io.xr.harness

import io.jsonwebtoken.Jwts
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
import io.xr.lab.impl.SaasCurrentUser
import io.xr.lab.impl.SaasLoginResponse
import io.xr.lab.impl.SaasMeClient
import io.xr.lab.impl.SaasMenuNode
import io.xr.lab.impl.SaasPlatformTenant
import io.xr.lab.impl.SaasTenantMembership
import io.xr.lab.impl.SaasToken
import io.xr.lab.impl.SaasUpstreamError
import java.util.Date
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * M01.F05.I04 refresh 链（fastapi auth_impl.auth_refresh 镜像）：typ/claim 双校验、 rotate-once 写回目录、saas 失败包
 * 401 不透 502。路径以 Paths.kt 生成物为准。
 */
@Suppress("TooManyFunctions")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthRefreshTest {

  private val cfg = testAppConfig()

  private val directory = ConfigUserDirectory()

  // 与 harnessModule 内部构造同 cfg → 同签名密钥，verify 互通
  private val jwt =
      JwtIssuer(cfg.jwtSigningKey, cfg.jwtIssuer, cfg.jwtTtlSeconds, cfg.jwtRefreshTtlSeconds)

  /** 记录型 fake：token() 调用计数 + 实参捕获（Review Focus 2 masquerade 零出站断言用）。 */
  private class RecordingSaasAuth(
      private val login: () -> SaasLoginResponse = { throw SaasUpstreamError("no stub") },
      private val service: () -> SaasToken = { throw SaasUpstreamError("no stub") },
      private val tokenResult: () -> SaasToken = { throw SaasUpstreamError("no stub") },
  ) : SaasAuthClient {
    var tokenCalls: Int = 0
    var lastGrantType: String? = null
    var lastRefreshToken: String? = null

    override suspend fun userLogin(username: String, password: String): SaasLoginResponse = login()

    override suspend fun serviceLogin(): SaasToken = service()

    override suspend fun token(
        grantType: String,
        code: String?,
        refreshToken: String?,
        redirectUri: String?,
    ): SaasToken {
      tokenCalls++
      lastGrantType = grantType
      lastRefreshToken = refreshToken
      return tokenResult()
    }
  }

  private class FakeSaasMe(
      private val whoami: () -> SaasCurrentUser = { throw SaasUpstreamError("no stub") },
      private val myTenants: () -> List<SaasTenantMembership> = { emptyList() },
      private val menus: () -> List<SaasMenuNode> = { emptyList() },
  ) : SaasMeClient {
    override suspend fun listPlatformTenants(accessToken: String): List<SaasPlatformTenant> =
        emptyList()

    override suspend fun listMyMenus(accessToken: String, appCode: String): List<SaasMenuNode> =
        menus()

    override suspend fun whoami(accessToken: String): SaasCurrentUser = whoami()

    override suspend fun listMyTenants(accessToken: String): List<SaasTenantMembership> =
        myTenants()
  }

  /** 单应用作用域（同 AuthSessionTest.withApp：多请求共享内存态）。 */
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
  ): Pair<HttpStatusCode, String> {
    val resp =
        post(path) {
          contentType(ContentType.Application.Json)
          setBody(body)
        }
    return resp.status to resp.bodyAsText()
  }

  private suspend fun HttpClient.getJson(
      path: String,
      token: String,
  ): Pair<HttpStatusCode, String> {
    val resp = get(path) { header("Authorization", "Bearer $token") }
    return resp.status to resp.bodyAsText()
  }

  /** 手工拼 refresh 形 JWT（typ=refresh）但无 saas_refresh_token claim——签名同款密钥可过验签。 */
  private fun refreshFormTokenWithoutSaasClaim(userId: String): String {
    val key = SecretKeySpec(cfg.jwtSigningKey.toByteArray(), "HmacSHA256")
    val epoch = System.currentTimeMillis() / 1000
    return Jwts.builder()
        .issuer(cfg.jwtIssuer)
        .subject(userId)
        .claim("typ", "refresh")
        .issuedAt(Date(epoch * 1000))
        .expiration(Date((epoch + cfg.jwtRefreshTtlSeconds) * 1000))
        .signWith(key)
        .compact()
  }

  @Test
  @Fn(["M01.F05.I04"])
  fun `refresh happy rotates and reissues`() {
    // 造 SSO 档：目录持旧 saas refresh；refresh JWT 内嵌 old-rt
    directory.upsert("u-sso", "alice@lab", "Alice", "viewer")
    directory.setSaasRefreshToken("u-sso", "old-rt")
    val saasAuth =
        RecordingSaasAuth(
            tokenResult = { SaasToken(accessToken = "saas-at-2", refreshToken = "saas-rt-2") }
        )
    val saasMe =
        FakeSaasMe(
            whoami = {
              SaasCurrentUser(
                  id = "u-sso",
                  email = "alice@lab",
                  displayName = "Alice",
                  currentTenantId = "TENANT-001",
              )
            },
            myTenants = {
              listOf(SaasTenantMembership(tenantId = "TENANT-001", roleIds = listOf("viewer")))
            },
        )
    val refreshToken = jwt.issueRefresh("u-sso", "old-rt")
    var refreshCall: Pair<HttpStatusCode, String>? = null
    var menusStatus: HttpStatusCode? = null
    var meStatus: HttpStatusCode? = null
    var meBody: String = ""
    withApp(saasAuth, saasMe) { client ->
      refreshCall = client.postJson("/api/auth/refresh", """{"refreshToken":"$refreshToken"}""")
      if (refreshCall!!.first == HttpStatusCode.OK) {
        val newToken =
            Json.parseToJsonElement(refreshCall!!.second)
                .jsonObject["token"]!!
                .jsonPrimitive
                .content
        menusStatus = client.getJson("/api/auth/menus", newToken).first
        val me = client.getJson("/api/auth/me", newToken)
        meStatus = me.first
        meBody = me.second
      }
    }
    assertEquals(HttpStatusCode.OK, refreshCall!!.first)
    val obj = Json.parseToJsonElement(refreshCall!!.second).jsonObject
    // 出站实参：grantType=refresh_token 且透传 old-rt（fastapi saas_client 镜像）
    assertEquals("refresh_token", saasAuth.lastGrantType)
    assertEquals("old-rt", saasAuth.lastRefreshToken)
    // 新 access：可验签 typ=access。tenant_id 透传旧 refresh JWT claim（fastapi auth_refresh
    // `str(tenant_id) if tenant_id else None` 镜像）——refresh JWT 无 tenant claim → 新 access 亦无，
    // me 靠快照 first() 兜 currentTenantId（Ruling：plan 测试行写死 TENANT-001 与参考实现相悖，修测试）
    val claims = jwt.verify(obj["token"]!!.jsonPrimitive.content)
    assertEquals("access", claims.get("typ", String::class.java))
    assertEquals("u-sso", claims.subject)
    assertEquals(null, claims.get("tenant_id"))
    // rotate-once：refresh JWT claim + 目录槽位双写回（Review Focus 3）
    val refreshClaims = jwt.verify(obj["refreshToken"]!!.jsonPrimitive.content)
    assertEquals("saas-rt-2", refreshClaims.get("saas_refresh_token", String::class.java))
    assertEquals("saas-rt-2", directory.getSaasRefreshToken("u-sso"))
    // 快照重拉：menuCache 有档 → menus GET 200（cacheMenus 用户 token 窗口版兑现）
    assertEquals(HttpStatusCode.OK, menusStatus)
    // membership 快照有档：me 200 且 currentTenantId=快照 first（claim 缺省兜底分支实证）
    assertEquals(HttpStatusCode.OK, meStatus)
    assertTrue(meBody.contains("TENANT-001"), "实收: $meBody")
  }

  @Test
  @Fn(["M01.F05.I04"])
  fun `access token masquerading as refresh is 401 without saas call`() {
    val saasAuth = RecordingSaasAuth()
    val accessToken = jwt.issue("u-sso", null) // typ=access 冒充
    val (status, body) =
        withApp(saasAuth, FakeSaasMe()) {
          it.postJson("/api/auth/refresh", """{"refreshToken":"$accessToken"}""")
        }
    assertEquals(HttpStatusCode.Unauthorized, status)
    assertTrue(body.contains("INVALID_CREDENTIALS"))
    assertEquals(0, saasAuth.tokenCalls) // Review Focus 2：零 saas 出站
  }

  @Test
  @Fn(["M01.F05.I04"])
  fun `refresh form jwt without saas claim is 401`() {
    val saasAuth = RecordingSaasAuth()
    val token = refreshFormTokenWithoutSaasClaim("u-sso")
    val (status, _) =
        withApp(saasAuth, FakeSaasMe()) {
          it.postJson("/api/auth/refresh", """{"refreshToken":"$token"}""")
        }
    assertEquals(HttpStatusCode.Unauthorized, status)
    assertEquals(0, saasAuth.tokenCalls)
  }

  @Test
  @Fn(["M01.F05.I04"])
  fun `saas refresh failure wraps to 401 not 502`() {
    directory.upsert("u-sso", "alice@lab", "Alice", "viewer")
    directory.setSaasRefreshToken("u-sso", "old-rt")
    val saasAuth =
        RecordingSaasAuth(tokenResult = { throw SaasUpstreamError("saas oauth/token down") })
    val refreshToken = jwt.issueRefresh("u-sso", "old-rt")
    val (status, body) =
        withApp(saasAuth, FakeSaasMe()) {
          it.postJson("/api/auth/refresh", """{"refreshToken":"$refreshToken"}""")
        }
    assertEquals(HttpStatusCode.Unauthorized, status) // 401 非 502：前端触发重登
    assertTrue(body.contains("INVALID_CREDENTIALS"))
    assertTrue(!body.contains("SAAS_UNAVAILABLE"), "502 语义不得漏出: $body")
  }

  @Test
  @Fn(["M01.F05.I04"])
  fun `empty body refresh token is 401`() {
    val (status, body) =
        withApp(RecordingSaasAuth(), FakeSaasMe()) {
          it.postJson("/api/auth/refresh", """{"refreshToken":""}""")
        }
    assertEquals(HttpStatusCode.Unauthorized, status)
    assertTrue(body.contains("INVALID_CREDENTIALS"))
  }

  /**
   * spec §6.2 重放场景显式证据（5.110⑤ M4 收口）：rotate-once 后旧 refresh JWT 重放第二次——出站 saas token 实参
   * **claim-driven 带旧值**（JWT 内嵌 old-rt），不是目录已 rotate 的新值 saas-rt-2。 行为本就如此（终审推定正确），此钉把推定翻成 HTTP
   * 可观测证据。
   */
  @Test
  @Fn(["M01.F05.I04"])
  fun `refresh replay after rotate still sends old claim value outbound`() {
    directory.upsert("u-sso", "alice@lab", "Alice", "viewer")
    directory.setSaasRefreshToken("u-sso", "old-rt")
    val saasAuth =
        RecordingSaasAuth(
            tokenResult = { SaasToken(accessToken = "saas-at-2", refreshToken = "saas-rt-2") }
        )
    val saasMe =
        FakeSaasMe(
            whoami = {
              SaasCurrentUser(
                  id = "u-sso",
                  email = "alice@lab",
                  displayName = "Alice",
                  currentTenantId = "TENANT-001",
              )
            },
        )
    val oldRefresh = jwt.issueRefresh("u-sso", "old-rt")
    withApp(saasAuth, saasMe) { client ->
      val first = client.postJson("/api/auth/refresh", """{"refreshToken":"$oldRefresh"}""")
      assertEquals(HttpStatusCode.OK, first.first)
      val replay = client.postJson("/api/auth/refresh", """{"refreshToken":"$oldRefresh"}""")
      assertEquals(HttpStatusCode.OK, replay.first)
    }
    assertEquals(2, saasAuth.tokenCalls)
    assertEquals("old-rt", saasAuth.lastRefreshToken)
  }
}
