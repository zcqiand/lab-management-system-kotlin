package io.xr.lab.impl

import io.ktor.server.routing.RoutingCall
import io.jsonwebtoken.Claims

/**
 * Bearer 验签面（fastapi security.py require_bearer 镜像）：生成路由无全局鉴权兜底， impl 首行显式挂——不用 ktor
 * Authentication 插件（错误仍走 StatusPages 单通道，ADR-0049 决策 1）。
 */
fun requireBearer(call: RoutingCall, jwt: JwtIssuer): Claims {
  val header = call.request.headers["Authorization"]
  if (header == null || !header.startsWith("Bearer ")) {
    throw InvalidCredentialsError("Bearer token required")
  }
  return jwt.verify(header.removePrefix("Bearer ").trim())
}
