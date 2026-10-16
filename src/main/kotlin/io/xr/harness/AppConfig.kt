package io.xr.harness

/** 运行配置：main 从 env 组装（fail-fast 禁兜底，fastapi config.py 镜像），测试直接构造。 */
data class AppConfig(
    val serverPort: Int,
    val databaseUrl: String,
    val jwtSigningKey: String,
    val jwtIssuer: String,
    val jwtTtlSeconds: Long,
    val jwtRefreshTtlSeconds: Long,
    val saasBaseUrl: String,
    val ssoLoginUrl: String,
    val saasClientId: String,
    val saasClientSecret: String,
    val saasDefaultTenantId: String,
    val ssoCallbackRedirect: String,
    val saasServiceUser: String,
    val saasServicePassword: String,
    val saasServiceClientId: String,
    val corsAllowedOrigins: String,
) {

  /** saas IdP 登录页 base：空回落 saas base（家族语义 springboot effectiveLoginUrl，非 env 兜底）。 */
  val effectiveLoginUrl: String
    get() = ssoLoginUrl.ifBlank { saasBaseUrl }

  /** CORS 白名单 CSV → origin 列表（fastapi CORSMiddleware allow_origins 同语义）。 */
  val corsOrigins: List<String>
    get() = corsAllowedOrigins.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}

private fun requireEnv(name: String): String =
    System.getenv(name) ?: error("$name 未设置。禁止默认值兜底（suite-hard-rules §1）；dev 值见 .env.example。")

fun appConfigFromEnv(): AppConfig {
  val port = requireEnv("SERVER_PORT").toIntOrNull() ?: error("SERVER_PORT 未设置或不是整数。")
  val ttl = requireEnv("JWT_TTL_SECONDS").toLongOrNull() ?: error("JWT_TTL_SECONDS 未设置或不是整数。")
  val refreshTtl =
      requireEnv("JWT_REFRESH_TTL_SECONDS").toLongOrNull()
          ?: error("JWT_REFRESH_TTL_SECONDS 未设置或不是整数。")
  return AppConfig(
      serverPort = port,
      databaseUrl = requireEnv("DATABASE_URL"),
      jwtSigningKey = requireEnv("JWT_SIGNING_KEY"),
      jwtIssuer = requireEnv("JWT_ISSUER"),
      jwtTtlSeconds = ttl,
      jwtRefreshTtlSeconds = refreshTtl,
      saasBaseUrl = requireEnv("LAB_SAAS_BASE_URL"),
      // 唯一可空键：空回落 saas base 是家族语义（springboot effectiveLoginUrl），非 env 兜底
      ssoLoginUrl = System.getenv("LAB_SSO_LOGIN_URL") ?: "",
      saasClientId = requireEnv("LAB_SAAS_CLIENT_ID"),
      saasClientSecret = requireEnv("LAB_SAAS_CLIENT_SECRET"),
      saasDefaultTenantId = requireEnv("LAB_SAAS_DEFAULT_TENANT_ID"),
      ssoCallbackRedirect = requireEnv("LAB_SSO_CALLBACK_REDIRECT"),
      saasServiceUser = requireEnv("LAB_SAAS_SERVICE_USER"),
      saasServicePassword = requireEnv("LAB_SAAS_SERVICE_PASSWORD"),
      saasServiceClientId = requireEnv("LAB_SAAS_SERVICE_CLIENT_ID"),
      corsAllowedOrigins = requireEnv("LAB_CORS_ALLOWED_ORIGINS"),
  )
}
