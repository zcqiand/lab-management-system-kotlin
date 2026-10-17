package io.xr.harness

import io.xr.lab.impl.InvalidCredentialsError
import io.xr.lab.impl.JwtIssuer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** 验签面矩阵（fastapi security.py verify 镜像）：失败恒 InvalidCredentialsError，不裸 JwtException。 */
class JwtVerifyMatrixTest {

  private val key = "dev-key-32-bytes-minimum-length!"
  private val issuer = JwtIssuer(key, "lab-management", 3600, 7200)
  private val rogue = JwtIssuer(key, "other-issuer", 3600, 7200)

  @Test
  fun `happy path returns claims`() {
    val token = issuer.issue("USER-A", "TENANT-001")
    assertEquals("USER-A", issuer.verify(token).subject)
  }

  @Test
  fun `empty token is invalid credentials`() {
    assertThrows(InvalidCredentialsError::class.java) { issuer.verify("") }
  }

  @Test
  fun `tampered signature is invalid credentials`() {
    val token = issuer.issue("USER-A", null)
    val tampered = token.dropLast(4) + "AAAA"
    assertThrows(InvalidCredentialsError::class.java) { issuer.verify(tampered) }
  }

  @Test
  fun `wrong issuer is invalid credentials`() {
    val token = rogue.issue("USER-A", null)
    assertThrows(InvalidCredentialsError::class.java) { issuer.verify(token) }
  }
}
