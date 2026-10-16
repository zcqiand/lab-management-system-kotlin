package io.xr.lab.impl

/** 类型化异常（fastapi impl/errors.py 镜像）：StatusPages 按 HTTP 语义映射，见 harnessModule。 */
class BadRequestError(message: String) : RuntimeException(message)

class InvalidCredentialsError(message: String = "invalid credentials") : RuntimeException(message)

/** saas IdP 5xx/不可达 → 502 SAAS_UNAVAILABLE（REQ-2026-023 五仓统一）。 */
class SaasIdpUnavailableError(message: String) : RuntimeException(message)

/**
 * 出站归类内部件（fastapi saas_client 同名镜像）：401→UnauthorizedClient、其他 4xx→InvalidGrant、5xx/连接→SaasUpstream。
 */
class UnauthorizedClientError(message: String) : RuntimeException(message)

class InvalidGrantError(message: String) : RuntimeException(message)

class SaasUpstreamError(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
