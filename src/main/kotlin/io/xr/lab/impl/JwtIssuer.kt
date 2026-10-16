package io.xr.lab.impl

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import java.util.Date
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * lab 自家 HS256 签发/验签（fastapi security.py JwtIssuer 镜像）：claims iss/sub/tenant_id?/typ/iat/exp——lab
 * JWT 无 aud/jti/nbf（与 saas 仓 JwtIssuer 是两个东西）。refresh typ=refresh，内嵌 saas_refresh_token claim（lab
 * 无状态 不持 saas 会话；密码路径恒 dev-placeholder）。
 */
class JwtIssuer(
    signingKey: String,
    private val issuer: String,
    private val accessTtlSeconds: Long,
    private val refreshTtlSeconds: Long,
) {

  private val key: SecretKey

  init {
    val bytes = signingKey.toByteArray()
    require(bytes.size >= HS256_MIN_KEY_BYTES) { "JWT_SIGNING_KEY must be >=32 bytes for HS256" }
    key = SecretKeySpec(bytes, "HmacSHA256")
  }

  fun issue(userId: String, tenantId: String?): String {
    val epoch = System.currentTimeMillis() / MILLIS_PER_SECOND
    val builder =
        Jwts.builder()
            .issuer(issuer)
            .subject(userId)
            .claim("typ", TYPE_ACCESS)
            .issuedAt(Date(epoch * MILLIS_PER_SECOND))
            .expiration(Date((epoch + accessTtlSeconds) * MILLIS_PER_SECOND))
    if (!tenantId.isNullOrBlank()) builder.claim(CLAIM_TENANT_ID, tenantId)
    return builder.signWith(key).compact()
  }

  fun issueRefresh(userId: String, saasRefreshToken: String): String {
    require(saasRefreshToken.isNotEmpty()) { "saasRefreshToken required for refresh token" }
    val epoch = System.currentTimeMillis() / MILLIS_PER_SECOND
    return Jwts.builder()
        .issuer(issuer)
        .subject(userId)
        .claim("typ", TYPE_REFRESH)
        .claim(CLAIM_SAAS_REFRESH_TOKEN, saasRefreshToken)
        .issuedAt(Date(epoch * MILLIS_PER_SECOND))
        .expiration(Date((epoch + refreshTtlSeconds) * MILLIS_PER_SECOND))
        .signWith(key)
        .compact()
  }

  fun verify(token: String): Claims =
      Jwts.parser().verifyWith(key).requireIssuer(issuer).build().parseSignedClaims(token).payload

  private companion object {

    const val HS256_MIN_KEY_BYTES = 32
    const val MILLIS_PER_SECOND = 1000L
    const val TYPE_ACCESS = "access"
    const val TYPE_REFRESH = "refresh"
    const val CLAIM_TENANT_ID = "tenant_id"
    const val CLAIM_SAAS_REFRESH_TOKEN = "saas_refresh_token"
  }
}
