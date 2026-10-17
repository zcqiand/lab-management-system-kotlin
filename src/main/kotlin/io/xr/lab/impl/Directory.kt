package io.xr.lab.impl

import io.xr.lab.shared.models.CurrentUser
import io.xr.lab.shared.models.MyTenant

private const val FALLBACK_ROLE_VIEWER = "viewer"

private val DEMO_USER =
    CurrentUser(id = "USER-A", username = "alice", displayName = "管理员", roleCode = "admin")

private val DEMO_TENANTS =
    listOf(
        MyTenant(
            tenantId = "TENANT-001",
            code = "city-lab",
            name = "市住建工程质量检测中心",
            roleIds = listOf("admin"),
        ),
        MyTenant(
            tenantId = "TENANT-002",
            code = "district-lab",
            name = "区检测站",
            roleIds = listOf("technician"),
        ),
        MyTenant(
            tenantId = "TENANT-003",
            code = "third-party",
            name = "第三方检测实验室",
            roleIds = listOf("viewer"),
        ),
    )

/**
 * 配置式内存用户目录（ADR-0008 无 DB 形态，fastapi directory.py 1:1 镜像）： 查/比/upsert 全内存；lab 不建 tenants 表；运行时
 * upsert 落 upserted 内存 Map。
 */
class ConfigUserDirectory {

  private val upserted = mutableMapOf<String, CurrentUser>()
  private val saasRefreshTokens = mutableMapOf<String, String>()

  fun findById(userId: String?): CurrentUser? =
      when {
        userId == null -> null
        DEMO_USER.id == userId -> DEMO_USER
        else -> upserted.values.firstOrNull { it.id == userId }
      }

  fun findByEmail(email: String?): CurrentUser? =
      when {
        // 镜像 quirk：springboot findByEmail 对 DEMO 用户比对的是 username（非 email 字段）
        email == null -> null
        email == DEMO_USER.username -> DEMO_USER
        else -> upserted.values.firstOrNull { it.username == email }
      }

  fun findByUsername(username: String?): CurrentUser? =
      when {
        username == null -> null
        DEMO_USER.username == username -> DEMO_USER
        else -> upserted.values.firstOrNull { it.username == username }
      }

  // username 形参镜像 fastapi tenants_of（按签名对称保留，语义不消费）
  @Suppress("UnusedParameter")
  fun tenantsOf(username: String?): List<MyTenant> = DEMO_TENANTS.toList()

  /** demo 目录默认租户（fastapi directory.default_tenant 镜像——me 缓存 miss 分支的 currentTenantId 兜底）。 */
  fun defaultTenant(): MyTenant = DEMO_TENANTS.first()

  /**
   * email 键 upsert（登录链目录口）：命中即返回既有（不更新字段，镜像 springboot）； roleCode 空兜
   * viewer。调用方保证键非空（firstNonEmpty(email, username) 链）。
   */
  fun upsert(userId: String, email: String, displayName: String?, roleCode: String?): CurrentUser {
    upserted[email]?.let {
      return it
    }
    val user =
        CurrentUser(
            id = userId,
            username = email,
            displayName = displayName,
            roleCode = roleCode?.takeIf { it.isNotEmpty() } ?: FALLBACK_ROLE_VIEWER,
        )
    upserted[email] = user
    return user
  }

  /** per-user saas refresh 槽（预留；本切片密码路径不写真值）。 */
  fun setSaasRefreshToken(userId: String?, saasRefreshToken: String?) {
    if (userId.isNullOrEmpty() || saasRefreshToken.isNullOrEmpty()) return
    saasRefreshTokens[userId] = saasRefreshToken
  }

  fun getSaasRefreshToken(userId: String): String? = saasRefreshTokens[userId]
}
