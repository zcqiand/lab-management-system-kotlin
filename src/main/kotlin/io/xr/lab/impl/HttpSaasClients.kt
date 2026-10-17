package io.xr.lab.impl

import io.xr.harness.AppConfig
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val OUTBOUND_TIMEOUT_SECONDS = 10L
private const val ERROR_BODY_SNIPPET = 200
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_BAD_REQUEST = 400
private const val HTTP_SERVER_ERROR = 500

private val outboundJson = Json { ignoreUnknownKeys = true }

/** 进程级共享客户端（终审 Important：每请求 newBuilder 从不 close = 线程/连接泄漏）。 */
private val outboundHttp: HttpClient =
    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(OUTBOUND_TIMEOUT_SECONDS)).build()

/**
 * 请求级读超时（终审 Important：connectTimeout 只管建连，saas accept 后挂死 = send 永久阻塞）。 HttpTimeoutException 是
 * IOException 子类，sendForJson 既有 catch 已翻 SaasUpstream（502）。
 */
private fun outboundRequest(build: HttpRequest.Builder.() -> Unit): HttpRequest =
    HttpRequest.newBuilder()
        .timeout(Duration.ofSeconds(OUTBOUND_TIMEOUT_SECONDS))
        .apply(build)
        .build()

/**
 * 出站公共段（fastapi saas_client 镜像，Review Focus 1/2 防位）： 连接失败/超时 → SaasUpstream（impl 层翻 502）；saas 401 →
 * UnauthorizedClient、 其他 4xx → InvalidGrant（impl 层统一翻 401 INVALID_CREDENTIALS，不透传内部码）。
 */
private suspend fun sendForJson(request: HttpRequest, endpoint: String): String =
    withContext(Dispatchers.IO) {
      val resp =
          try {
            outboundHttp.send(request, HttpResponse.BodyHandlers.ofString())
          } catch (err: IOException) {
            throw SaasUpstreamError("saas $endpoint connect failed: ${err.message}", err)
          } catch (err: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SaasUpstreamError("saas $endpoint interrupted", err)
          }
      val status = resp.statusCode()
      if (status >= HTTP_BAD_REQUEST) classifySaasError(status, resp.body(), endpoint)
      resp.body()
    }

private fun classifySaasError(status: Int, body: String, endpoint: String): Nothing {
  val truncated =
      if (body.length <= ERROR_BODY_SNIPPET) body else body.take(ERROR_BODY_SNIPPET) + "..."
  val classified =
      when {
        status == HTTP_UNAUTHORIZED -> UnauthorizedClientError("saas $endpoint 401 $truncated")
        status < HTTP_SERVER_ERROR -> InvalidGrantError("saas $endpoint $status $truncated")
        else -> SaasUpstreamError("saas $endpoint 5xx: $status")
      }
  throw classified
}

private fun loginRequest(base: String, body: String): HttpRequest = outboundRequest {
  uri(URI.create("$base/api/v1/auth/login"))
  header("Content-Type", "application/json")
  POST(HttpRequest.BodyPublishers.ofString(body))
}

private fun tokenRequest(base: String, body: String): HttpRequest = outboundRequest {
  uri(URI.create("$base/api/v1/oauth/token"))
  header("Content-Type", "application/json")
  POST(HttpRequest.BodyPublishers.ofString(body))
}

private fun bearerGetRequest(base: String, path: String, accessToken: String): HttpRequest =
    outboundRequest {
      uri(URI.create(base + path))
      header("Authorization", "Bearer $accessToken")
      GET()
    }

private fun loginPayload(username: String, password: String, clientId: String): String =
    buildJsonObject {
      put("username", username)
      put("password", password)
      put("clientId", clientId)
    }
    .toString()

class HttpSaasAuthClient(private val cfg: AppConfig) : SaasAuthClient {

  override suspend fun userLogin(username: String, password: String): SaasLoginResponse {
    val payload =
        sendForJson(
            loginRequest(cfg.saasBaseUrl, loginPayload(username, password, cfg.saasClientId)),
            "auth/login",
        )
    return outboundJson.decodeFromString(SaasLoginResponse.serializer(), payload)
  }

  override suspend fun serviceLogin(): SaasToken {
    val payload =
        sendForJson(
            loginRequest(
                cfg.saasBaseUrl,
                loginPayload(cfg.saasServiceUser, cfg.saasServicePassword, cfg.saasServiceClientId),
            ),
            "auth/login",
        )
    val obj =
        outboundJson.parseToJsonElement(payload) as? JsonObject
            ?: throw SaasUpstreamError("saas auth/login unexpected payload shape")
    val accessToken =
        obj["accessToken"]?.jsonPrimitive?.contentOrNull
            ?: throw SaasUpstreamError("saas auth/login missing accessToken")
    val refreshToken = obj["refreshToken"]?.jsonPrimitive?.contentOrNull ?: ""
    return SaasToken(accessToken = accessToken, refreshToken = refreshToken)
  }

  override suspend fun token(
      grantType: String,
      code: String?,
      refreshToken: String?,
      redirectUri: String?,
  ): SaasToken {
    val body =
        buildJsonObject {
          put("grantType", grantType)
          put("clientId", cfg.saasClientId)
          put("clientSecret", cfg.saasClientSecret)
          put("tenantId", cfg.saasDefaultTenantId)
          if (code != null) put("code", code)
          if (refreshToken != null) put("refreshToken", refreshToken)
          if (redirectUri != null) put("redirectUri", redirectUri)
        }
        .toString()
    val payload = sendForJson(tokenRequest(cfg.saasBaseUrl, body), "oauth/token")
    val obj =
        outboundJson.parseToJsonElement(payload) as? JsonObject
            ?: throw SaasUpstreamError("saas oauth/token unexpected payload shape")
    val accessToken =
        obj["accessToken"]?.jsonPrimitive?.contentOrNull
            ?: throw SaasUpstreamError("saas oauth/token missing accessToken")
    return SaasToken(
        accessToken = accessToken,
        refreshToken = obj["refreshToken"]?.jsonPrimitive?.contentOrNull ?: "",
    )
  }
}

class HttpSaasMeClient(private val cfg: AppConfig) : SaasMeClient {

  override suspend fun listPlatformTenants(accessToken: String): List<SaasPlatformTenant> {
    val payload =
        sendForJson(
            bearerGetRequest(
                cfg.saasBaseUrl,
                "/api/v1/admin/tenants?page=0&pageSize=100",
                accessToken,
            ),
            "admin/tenants",
        )
    val rows =
        (outboundJson.parseToJsonElement(payload) as? JsonObject)?.get("items")
            ?: JsonArray(emptyList())
    return outboundJson.decodeFromString(
        kotlinx.serialization.builtins.ListSerializer(SaasPlatformTenant.serializer()),
        rows.toString(),
    )
  }

  override suspend fun listMyMenus(accessToken: String, appCode: String): List<SaasMenuNode> {
    val payload =
        sendForJson(bearerGetRequest(cfg.saasBaseUrl, "/api/v1/me/menus", accessToken), "me/menus")
    val tree =
        (outboundJson.parseToJsonElement(payload) as? JsonObject)?.get(appCode)
            ?: JsonArray(emptyList())
    return outboundJson.decodeFromString(
        kotlinx.serialization.builtins.ListSerializer(SaasMenuNode.serializer()),
        tree.toString(),
    )
  }

  override suspend fun whoami(accessToken: String): SaasCurrentUser =
      outboundJson.decodeFromString(
          SaasCurrentUser.serializer(),
          sendForJson(bearerGetRequest(cfg.saasBaseUrl, "/api/v1/me", accessToken), "me"),
      )

  override suspend fun listMyTenants(accessToken: String): List<SaasTenantMembership> {
    val payload =
        sendForJson(
            bearerGetRequest(cfg.saasBaseUrl, "/api/v1/me/tenants", accessToken),
            "me/tenants",
        )
    // fastapi _as_list 镜像：裸数组契约，非数组=形状异常翻 SaasUpstream
    val rows =
        outboundJson.parseToJsonElement(payload) as? JsonArray
            ?: throw SaasUpstreamError("saas me/tenants unexpected payload shape")
    return outboundJson.decodeFromString(
        kotlinx.serialization.builtins.ListSerializer(SaasTenantMembership.serializer()),
        rows.toString(),
    )
  }
}
