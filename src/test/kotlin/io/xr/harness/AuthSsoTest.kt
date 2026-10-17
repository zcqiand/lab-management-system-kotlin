package io.xr.harness

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.xr.lab.impl.ConfigUserDirectory
import io.xr.lab.impl.InvalidGrantError
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * M01.F05.I02/I03 SSO 双端点（fastapi auth_impl ssoAuthorize/ssoCallback 镜像，跳板形收敛）： authorize 只消费
 * redirect_uri+state（client_id 取 config）； callback
 * token→whoami/tenants→upsertByEmail→rotate-once→快照。
 */
@Suppress("TooManyFunctions")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthSsoTest {

  private val cfg = testAppConfig()

  private val directory = ConfigUserDirectory()

  private val jwt =
      JwtIssuer(cfg.jwtSigningKey, cfg.jwtIssuer, cfg.jwtTtlSeconds, cfg.jwtRefreshTtlSeconds)

  private class TokenStubSaasAuth(
      private val tokenResult: () -> SaasToken = { throw SaasUpstreamError("no stub") },
  ) : SaasAuthClient {
    var lastGrantType: String? = null
    var lastRedirectUri: String? = null

    override suspend fun userLogin(username: String, password: String): SaasLoginResponse =
        throw SaasUpstreamError("no stub")

    override suspend fun serviceLogin(): SaasToken = throw SaasUpstreamError("no stub")

    override suspend fun token(
        grantType: String,
        code: String?,
        refreshToken: String?,
        redirectUri: String?,
    ): SaasToken {
      lastGrantType = grantType
      lastRedirectUri = redirectUri
      return tokenResult()
    }
  }

  private class FakeSaasMe(
      private val whoami: () -> SaasCurrentUser = { throw SaasUpstreamError("no stub") },
      private val myTenants: () -> List<SaasTenantMembership> = { emptyList() },
  ) : SaasMeClient {
    override suspend fun listPlatformTenants(accessToken: String): List<SaasPlatformTenant> =
        emptyList()

    override suspend fun listMyMenus(accessToken: String, appCode: String): List<SaasMenuNode> =
        emptyList()

    override suspend fun whoami(accessToken: String): SaasCurrentUser = whoami()

    override suspend fun listMyTenants(accessToken: String): List<SaasTenantMembership> =
        myTenants()
  }

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

  private suspend fun HttpClient.getAuthorize(
      redirectUri: String,
      state: String,
  ): Pair<HttpStatusCode, String> {
    val resp =
        get("/api/auth/sso/authorize") {
          parameter("responseType", "code")
          parameter("clientId", "ignored")
          parameter("redirectUri", redirectUri)
          parameter("state", state)
        }
    return resp.status to resp.bodyAsText()
  }

  // === authorize（M01.F05.I02）===

  @Test
  @Fn(["M01.F05.I02"])
  fun `authorize builds jump url with config client id`() {
    val (status, body) =
        withApp(TokenStubSaasAuth(), FakeSaasMe()) {
          it.getAuthorize("http://localhost:5201/cb", "s1")
        }
    assertEquals(HttpStatusCode.OK, status)
    val obj = Json.parseToJsonElement(body).jsonObject
    val url = obj["authorizeUrl"]!!.jsonPrimitive.content
    // 跳板形：client_id 取 config（"lab-management"）非 query 的 "ignored"；state 原样回传
    assertTrue(url.startsWith("${cfg.effectiveLoginUrl}/login?"), "实收: $url")
    assertTrue(url.contains("redirect_uri=http%3A%2F%2Flocalhost%3A5201%2Fcb"), "实收: $url")
    assertTrue(url.contains("state=s1"), "实收: $url")
    assertTrue(url.contains("client_id=lab-management"), "实收: $url")
    assertTrue(!url.contains("ignored"), "query clientId 不得透传: $url")
    assertEquals("s1", obj["state"]!!.jsonPrimitive.content)
  }

  @Test
  @Fn(["M01.F05.I02"])
  fun `authorize blank redirect_uri is 400 not env fallback`() {
    // ADR-0019：redirect_uri 缺失 fail-fast 禁兜 env（ssoCallbackRedirect 不得漏出）
    val (status, body) = withApp(TokenStubSaasAuth(), FakeSaasMe()) { it.getAuthorize("", "s1") }
    assertEquals(HttpStatusCode.BadRequest, status)
    assertTrue(!body.contains("5201/callback"), "env 兜底回归: $body")
  }

  // === callback（M01.F05.I03）===

  private val callbackBody =
      """{"grantType":"authorization_code","code":"c1","redirectUri":"http://localhost:5201/cb","state":"s1"}"""

  private fun happyMe() =
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

  @Test
  @Fn(["M01.F05.I03"])
  fun `callback happy upserts by email rotates and sessions whoami tenant`() {
    val saasAuth =
        TokenStubSaasAuth(tokenResult = { SaasToken(accessToken = "at", refreshToken = "rt") })
    val (status, body) =
        withApp(saasAuth, happyMe()) { it.postJson("/api/auth/sso/callback", callbackBody) }
    assertEquals(HttpStatusCode.OK, status)
    // 出站形状：grantType=authorization_code + redirectUri 透传
    assertEquals("authorization_code", saasAuth.lastGrantType)
    assertEquals("http://localhost:5201/cb", saasAuth.lastRedirectUri)
    // upsertByEmail：目录按 email 自愈，角色固定 viewer（镜像 springboot）
    val labUser = directory.findByEmail("alice@lab")
    assertNotNull(labUser)
    assertEquals("viewer", labUser!!.roleCode)
    // rotate-once：新 saas refresh 写回目录槽位
    assertEquals("rt", directory.getSaasRefreshToken("u-sso"))
    val obj = Json.parseToJsonElement(body).jsonObject
    // 新 access：tenant_id=whoami.currentTenantId；refresh claim 内嵌新 saas rt
    val claims = jwt.verify(obj["token"]!!.jsonPrimitive.content)
    assertEquals("TENANT-001", claims.get("tenant_id", String::class.java))
    val refreshClaims = jwt.verify(obj["refreshToken"]!!.jsonPrimitive.content)
    assertEquals("rt", refreshClaims.get("saas_refresh_token", String::class.java))
  }

  @Test
  @Fn(["M01.F05.I03"])
  fun `callback second visit rotates again not duplicates`() {
    // 同链两次：目录槽位指向最新 rt（rotate-once 幂等写，Review Focus 3）
    val saasMe = happyMe()
    val first =
        TokenStubSaasAuth(tokenResult = { SaasToken(accessToken = "at-1", refreshToken = "rt-1") })
    withApp(first, saasMe) { it.postJson("/api/auth/sso/callback", callbackBody) }
    val second =
        TokenStubSaasAuth(tokenResult = { SaasToken(accessToken = "at-2", refreshToken = "rt-2") })
    val (status, _) =
        withApp(second, saasMe) { it.postJson("/api/auth/sso/callback", callbackBody) }
    assertEquals(HttpStatusCode.OK, status)
    assertEquals("rt-2", directory.getSaasRefreshToken("u-sso"))
    // upsertByEmail 命中既有：id 不漂（email 键唯一，directory 无枚举面，二次建档=同键覆盖即同 id）
    assertEquals("u-sso", directory.findByEmail("alice@lab")!!.id)
  }

  @Test
  @Fn(["M01.F05.I03"])
  fun `callback token failure classifies per outbound rules`() {
    // 裸出站异常漏到映射层走 fastapi errors.py ApiError 状态镜像（Ruling：plan 写 401/SAAS_UNAVAILABLE
    // 与参考相悖——ssoCallback 不二次包装）：InvalidGrantError → 400 INVALID_GRANT（detail 留痕）；
    // SaasUpstreamError → 502 SAAS_UPSTREAM_ERROR
    val grantFail =
        TokenStubSaasAuth(tokenResult = { throw InvalidGrantError("saas oauth/token 400 bad") })
    val (s1, b1) =
        withApp(grantFail, happyMe()) { it.postJson("/api/auth/sso/callback", callbackBody) }
    assertEquals(HttpStatusCode.BadRequest, s1)
    assertTrue(b1.contains("INVALID_GRANT"), "实收: $b1")
    val upstreamFail =
        TokenStubSaasAuth(tokenResult = { throw SaasUpstreamError("saas oauth/token down") })
    val (s2, b2) =
        withApp(upstreamFail, happyMe()) { it.postJson("/api/auth/sso/callback", callbackBody) }
    assertEquals(HttpStatusCode.BadGateway, s2)
    assertTrue(b2.contains("SAAS_UPSTREAM_ERROR"), "实收: $b2")
  }
}
