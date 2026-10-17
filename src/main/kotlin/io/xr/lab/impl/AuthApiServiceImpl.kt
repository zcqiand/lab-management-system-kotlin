package io.xr.lab.impl

import io.ktor.server.routing.RoutingCall
import io.xr.lab.shared.api.AuthApiDelegate
import io.xr.lab.shared.models.AuthLogoutRequest
import io.xr.lab.shared.models.CurrentUser
import io.xr.lab.shared.models.LoginRequest
import io.xr.lab.shared.models.LoginResponse
import io.xr.lab.shared.models.MenuNode
import io.xr.lab.shared.models.MyTenant
import org.slf4j.LoggerFactory

private const val LAB_APP_CODE = "lab-management"
private const val DEV_PLACEHOLDER_REFRESH = "dev-placeholder"
private const val FALLBACK_ROLE = "member"
private const val FALLBACK_USER = "unknown"
private const val FALLBACK_DISPLAY_NAME = "未知用户"
private const val SAAS_STATUS_REMOVED = "removed"

private val logger = LoggerFactory.getLogger("io.xr.lab.impl.AuthApiServiceImpl")

/**
 * 首个非空值（空串 falsy 语义——saas displayName 可为空串，is-null 判断接不住， 家族记忆
 * saas-no-displayname-empty-string-fallback-trap）。
 */
private fun firstNonEmpty(vararg values: String?): String =
    values.firstOrNull { !it.isNullOrEmpty() } ?: ""

/**
 * M01.F05.I06 native-login + 已废弃 I01 login（同链镜像）+ I05 logout（no-op）。 其余 7 op 不 override = 接口默认
 * 500（显式未实现，ADR-0047 决策 2）。 登录语义七步镜像 fastapi auth_impl.auth_login（spec §4）。
 */
class AuthApiServiceImpl(
    private val directory: ConfigUserDirectory,
    private val jwt: JwtIssuer,
    private val saasAuth: SaasAuthClient,
    private val saasMe: SaasMeClient,
    private val menuCache: SnapshotCache<List<MenuNode>>,
    private val membershipCache: SnapshotCache<List<MyTenant>>,
) : AuthApiDelegate {

  override suspend fun authLogin(loginRequest: LoginRequest, call: RoutingCall): LoginResponse =
      doLogin(loginRequest)

  override suspend fun authNativeLogin(
      loginRequest: LoginRequest,
      call: RoutingCall,
  ): LoginResponse = doLogin(loginRequest)

  override suspend fun authLogout(authLogoutRequest: AuthLogoutRequest, call: RoutingCall) {
    // 无状态登出（fastapi auth_logout 镜像）：服务端无 session store，body 的 token 不消费
  }

  private suspend fun doLogin(request: LoginRequest?): LoginResponse {
    val username = request?.username?.trim() ?: ""
    val password = request?.password ?: ""
    if (username.isEmpty() || password.isEmpty()) {
      throw BadRequestError("username and password are required")
    }
    // 步骤 2（REQ-2026-023）：账密校验代理 saas IdP。saas 4xx→401（不透传）；5xx/不可达→502
    val saasLogin = proxySaasLogin(username, password)
    val saasUser = saasLogin.user
    // 步骤 3：租户富化——瞬时 accessToken 拉平台租户名；失败降级 code=name=tenantId
    val nameById = fetchTenantNames(saasLogin)
    val tenants = tenantsFromLogin(saasLogin.availableTenants, nameById)
    // 步骤 4：CurrentUser 回退链（空串 falsy）
    val user =
        CurrentUser(
            id = firstNonEmpty(saasUser?.id, saasLogin.userId, FALLBACK_USER),
            username = firstNonEmpty(saasUser?.username, saasUser?.email, FALLBACK_USER),
            displayName =
                firstNonEmpty(
                    saasUser?.displayName,
                    saasUser?.username,
                    saasUser?.email,
                    FALLBACK_DISPLAY_NAME,
                ),
            roleCode = tenants.firstOrNull()?.roleIds?.firstOrNull() ?: FALLBACK_ROLE,
        )
    // 步骤 5：服务账号菜单快照（失败不阻塞登录；miss 时 Phase 5 menus 503）
    cacheMenusWithServiceAccount(user.id)
    // 步骤 6：membership 快照 + 目录 upsert（email 缺失降级 username 作键）
    membershipCache.put(user.id, tenants)
    directory.upsert(
        user.id,
        firstNonEmpty(saasUser?.email, user.username),
        user.displayName,
        user.roleCode,
    )
    // 步骤 7：lab 自家 JWT 重签；refresh 恒 dev-placeholder（密码路径不透传 saas refreshToken）。
    // 租户空列表保持空（终审 Important：fastapi `tenants if tenants is not None` 镜像——
    // 空即空，禁回落 demo 字面量，suite-hard-rules §3 同族）
    return session(user, saasLogin.currentTenantId ?: tenants.firstOrNull()?.tenantId, tenants)
  }

  private suspend fun proxySaasLogin(username: String, password: String): SaasLoginResponse =
      try {
        saasAuth.userLogin(username, password)
      } catch (err: UnauthorizedClientError) {
        throw InvalidCredentialsError("Invalid username or password", cause = err)
      } catch (err: InvalidGrantError) {
        throw InvalidCredentialsError("Invalid username or password", cause = err)
      } catch (err: SaasUpstreamError) {
        throw SaasIdpUnavailableError("saas IdP unavailable: ${err.message}", cause = err)
      }

  /**
   * 富化 best-effort：任何出站失败都降级空表（Review Focus 3），登录照常 200。吞异常是本函数 的语义本身（无日志框架，禁 println），降级行为由测试钉死。
   */
  @Suppress("SwallowedException")
  private suspend fun fetchTenantNames(
      saasLogin: SaasLoginResponse
  ): Map<String, SaasPlatformTenant> =
      try {
        saasMe
            .listPlatformTenants(saasLogin.accessToken)
            .associateBy { it.id }
            .filterKeys { it.isNotEmpty() }
      } catch (err: UnauthorizedClientError) {
        emptyMap()
      } catch (err: InvalidGrantError) {
        emptyMap()
      } catch (err: SaasUpstreamError) {
        emptyMap()
      }

  private fun tenantsFromLogin(
      availableTenants: List<SaasAvailableTenant>,
      nameById: Map<String, SaasPlatformTenant>,
  ): List<MyTenant> =
      availableTenants
          .filter { it.tenantId.isNotEmpty() && it.status != SAAS_STATUS_REMOVED }
          .map { t ->
            val enriched = nameById[t.tenantId]
            MyTenant(
                tenantId = t.tenantId,
                code = enriched?.tenantKey?.takeIf { it.isNotEmpty() } ?: t.tenantId,
                name = enriched?.name?.takeIf { it.isNotEmpty() } ?: t.tenantId,
                roleIds = t.roleIds.toList(),
            )
          }

  private suspend fun cacheMenusWithServiceAccount(userId: String) {
    try {
      val token = saasAuth.serviceLogin()
      val snapshot = saasMe.listMyMenus(token.accessToken, LAB_APP_CODE)
      menuCache.put(userId, mapSaasMenus(snapshot))
    } catch (err: UnauthorizedClientError) {
      logMenuFailure(userId, err)
    } catch (err: InvalidGrantError) {
      logMenuFailure(userId, err)
    } catch (err: SaasUpstreamError) {
      logMenuFailure(userId, err)
    }
  }

  /** best-effort 失败只 warn 不抛（Review Focus 3）；Phase 4 纯 no-op，本切片随日志框架兑现。 */
  private fun logMenuFailure(userId: String, err: RuntimeException) {
    logger.warn("menu snapshot fetch failed for user {}: {}", userId, err.message)
  }

  private fun session(
      user: CurrentUser,
      tenantId: String?,
      tenants: List<MyTenant>,
  ): LoginResponse =
      LoginResponse(
          token = jwt.issue(user.id, tenantId),
          refreshToken = jwt.issueRefresh(user.id, DEV_PLACEHOLDER_REFRESH),
          user = user,
          // 空列表保持空（fastapi `_session` 镜像：密码路径空即空，禁 demo 兜底）；
          // directory.tenantsOf 仍留作 SSO 路径（Phase 5）的 mirror 面
          tenants = tenants,
      )
}
