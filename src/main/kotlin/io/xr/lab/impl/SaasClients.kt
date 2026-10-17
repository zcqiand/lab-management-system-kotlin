package io.xr.lab.impl

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SaasLoginUser(
    val id: String,
    val username: String? = null,
    val email: String? = null,
    @SerialName("displayName") val displayName: String? = null,
)

@Serializable
data class SaasAvailableTenant(
    @SerialName("tenantId") val tenantId: String,
    @SerialName("roleIds") val roleIds: List<String> = emptyList(),
    val status: String? = null,
)

/** saas POST /api/v1/auth/login 响应全形（REQ-2026-023 登录代理出站，fastapi saas_client 镜像）。 */
@Serializable
data class SaasLoginResponse(
    val user: SaasLoginUser? = null,
    @SerialName("availableTenants") val availableTenants: List<SaasAvailableTenant> = emptyList(),
    @SerialName("accessToken") val accessToken: String,
    @SerialName("refreshToken") val refreshToken: String = "",
    @SerialName("userId") val userId: String? = null,
    @SerialName("currentTenantId") val currentTenantId: String? = null,
)

@Serializable
data class SaasPlatformTenant(
    val id: String,
    val name: String? = null,
    @SerialName("tenantKey") val tenantKey: String? = null,
)

/** saas /me/menus 节点：名称字段是 title 不是 name（家族记忆 saas-me-menus-title-not-name）。 */
@Serializable
data class SaasMenuNode(
    val id: String,
    val code: String? = null,
    val title: String? = null,
    val path: String? = null,
    val icon: String? = null,
    val type: String? = null,
    @SerialName("sortOrder") val sortOrder: Int? = null,
    val children: List<SaasMenuNode> = emptyList(),
)

data class SaasToken(val accessToken: String, val refreshToken: String)

/** saas GET /api/v1/me 响应（fastapi SaasCurrentUser 镜像；email 契约必填）。 */
@Serializable
data class SaasCurrentUser(
    val id: String,
    val email: String,
    @SerialName("displayName") val displayName: String? = null,
    @SerialName("currentTenantId") val currentTenantId: String? = null,
)

/** saas GET /api/v1/me/tenants 行（契约只有 tenantId + roleIds，不带名）。 */
@Serializable
data class SaasTenantMembership(
    @SerialName("tenantId") val tenantId: String,
    @SerialName("roleIds") val roleIds: List<String> = emptyList(),
)

/**
 * 出站客户端接口缝：测试注入 fake（mock-friendly 无网，fastapi 同构）； HTTP 实现仅生产组合根装配，不被单测打（真连归 contract-test live）。
 */
interface SaasAuthClient {

  suspend fun userLogin(username: String, password: String): SaasLoginResponse

  suspend fun serviceLogin(): SaasToken

  /** OAuth token 端点（fastapi saas_client.token 镜像）：authorization_code / refresh_token 双 grant。 */
  suspend fun token(
      grantType: String,
      code: String? = null,
      refreshToken: String? = null,
      redirectUri: String? = null,
  ): SaasToken
}

interface SaasMeClient {

  suspend fun listPlatformTenants(accessToken: String): List<SaasPlatformTenant>

  suspend fun listMyMenus(accessToken: String, appCode: String): List<SaasMenuNode>

  suspend fun whoami(accessToken: String): SaasCurrentUser

  suspend fun listMyTenants(accessToken: String): List<SaasTenantMembership>
}
