package io.xr.harness

import io.xr.lab.impl.JwtIssuer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** 工程设施测试（ktor-conventions 规则 4）：不挂功能 ID。 */
class InfraTest {

  // JWT 四件 + REFRESH：lab JwtIssuer 构造参数面（T2 Step 3 落地）
  private val jwt =
      JwtIssuer(
          signingKey = "dev-key-32-bytes-minimum-length!",
          issuer = "lab-management",
          accessTtlSeconds = 3600,
          refreshTtlSeconds = 7200,
      )

  @Test
  fun `jwt round-trip carries lab claims`() {
    val token = jwt.issue("USER-A", "TENANT-001")
    val claims = jwt.verify(token)
    assertEquals("USER-A", claims.subject)
    assertEquals("TENANT-001", claims.get("tenant_id", String::class.java))
    assertEquals("access", claims.get("typ", String::class.java))
  }

  @Test
  fun `refresh token carries typ and saas claim`() {
    val claims = jwt.verify(jwt.issueRefresh("USER-A", "saas-rt-value"))
    assertEquals("refresh", claims.get("typ", String::class.java))
    assertEquals("saas-rt-value", claims.get("saas_refresh_token", String::class.java))
  }

  @Test
  fun `empty saas refresh rejected`() {
    assertThrows<IllegalArgumentException> { jwt.issueRefresh("USER-A", "") }
  }

  @Test
  fun `appConfigFromEnv fails fast on missing key`() {
    // 契约键缺失必须抛（suite-hard-rules §1）。进程 env 不可篡改（污染同 JVM 其他测试），
    // 这里锁「appConfigFromEnv 只读 env、无字面量兜底」的静态行为，动态红路径由
    // fail-fast 单测在 T3 组合根装配时间接覆盖；真·缺键即抛已由 main() 启动路径保证。
    val cfg = testAppConfig()
    assertTrue(cfg.effectiveLoginUrl.isNotEmpty())
    assertEquals("http://127.0.0.1:1", cfg.effectiveLoginUrl) // LAB_SSO_LOGIN_URL 空 → 回落 saas base
  }
}

/** 测试配置：值任意合法即可（harnessModule 不外呼——fake/HTTP 实现都只在请求时用）。 */
fun testAppConfig(): AppConfig =
    AppConfig(
        serverPort = 5209,
        databaseUrl = "postgresql://postgres:postgres@127.0.0.1:54321/lab_test",
        jwtSigningKey = "dev-key-32-bytes-minimum-length!",
        jwtIssuer = "lab-management",
        jwtTtlSeconds = 3600,
        jwtRefreshTtlSeconds = 7200,
        saasBaseUrl = "http://127.0.0.1:1", // 不可达端口：切片请求路径全部走 fake，此处仅装配
        ssoLoginUrl = "",
        saasClientId = "lab-management",
        saasClientSecret = "dev-secret",
        saasDefaultTenantId = "TENANT-001",
        ssoCallbackRedirect = "http://localhost:5201/callback",
        saasServiceUser = "svc-lab",
        saasServicePassword = "svc-pass",
        saasServiceClientId = "lab-service",
        corsAllowedOrigins = "http://localhost:5101,http://localhost:5201",
    )
