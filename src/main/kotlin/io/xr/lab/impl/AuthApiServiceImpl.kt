package io.xr.lab.impl

import io.jsonwebtoken.Claims
import io.ktor.server.routing.RoutingCall
import io.xr.lab.shared.api.AuthApiDelegate
import io.xr.lab.shared.models.AuthLogoutRequest
import io.xr.lab.shared.models.CurrentUser
import io.xr.lab.shared.models.CurrentUserSession
import io.xr.lab.shared.models.LoginRequest
import io.xr.lab.shared.models.LoginResponse
import io.xr.lab.shared.models.MenuNode
import io.xr.lab.shared.models.MyTenant
import io.xr.lab.shared.models.PermissionSet
import io.xr.lab.shared.models.RefreshTokenRequest
import io.xr.lab.shared.models.SwitchTenantRequest
import org.slf4j.LoggerFactory

private const val LAB_APP_CODE = "lab-management"
private const val DEV_PLACEHOLDER_REFRESH = "dev-placeholder"
private const val FALLBACK_ROLE = "member"
private const val FALLBACK_USER = "unknown"
private const val FALLBACK_DISPLAY_NAME = "未知用户"
private const val SAAS_STATUS_REMOVED = "removed"

/** _DEMO_PERMISSIONS 逐项镜像（fastapi auth_impl），顺序即契约——permissions 端点全量回包。 */
private val DEMO_PERMISSIONS =
    listOf(
        "contract:read",
        "contract:write",
        "sample:read",
        "sample:write",
        "report:read",
        "report:write",
        "report:issue",
        "inspection:read",
        "inspection:write",
        "audit:read",
        "*",
    )

private val logger = LoggerFactory.getLogger("io.xr.lab.impl.AuthApiServiceImpl")

/**
 * 首个非空值（空串 falsy 语义——saas displayName 可为空串，is-null 判断接不住， 家族记忆
 * saas-no-displayname-empty-string-fallback-trap）。
 */
private fun firstNonEmpty(vararg values: String?): String =
    values.firstOrNull { !it.isNullOrEmpty() } ?: ""

/**
 * 登录/会话七 op（native-login + 已废弃 login 同链 + logout + me + switch-tenant + menus +
 * permissions）； 剩余 refresh/SSO 三 op 不 override = 接口默认 500（显式未实现，ADR-0047 决策 2， Phase
 * 5 T4/T5 收口）。 登录语义七步镜像 fastapi auth_impl.auth_login（spec §4）。
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

  override suspend fun authGetCurrentUser(call: RoutingCall): CurrentUserSession {
    val claims = requireBearer(call, jwt)
    val user = resolveUser(claims)
    val cached = membershipCache.get(user.id)
    if (cached != null) {
      return CurrentUserSession(
          user = user,
          tenants = cached,
          currentTenantId = claims.get("tenant_id") as? String ?: cached.first().tenantId,
      )
    }
    // SSO 用户（目录持 saas refresh）快照 miss → 401 提示自愈，禁 demo 目录兜底（Review Focus 1）
    if (directory.getSaasRefreshToken(user.id) != null) {
      throw InvalidCredentialsError(
          "membership snapshot unavailable for user ${user.id} (cache miss); refresh required",
      )
    }
    return CurrentUserSession(
        user = user,
        tenants = directory.tenantsOf(user.username),
        currentTenantId = claims.get("tenant_id") as? String ?: directory.defaultTenant().tenantId,
    )
  }

  override suspend fun authSwitchTenant(
      switchTenantRequest: SwitchTenantRequest,
      call: RoutingCall,
  ): LoginResponse {
    val claims = requireBearer(call, jwt)
    val user = resolveUser(claims)
    val tenantId = switchTenantRequest.tenantId
    val known = membershipCache.get(user.id) ?: directory.tenantsOf(user.username)
    val target =
        known.firstOrNull { it.tenantId == tenantId } ?: throw NotFoundError("Tenant not found")
    // 命中换发：新 access 带 tenant_id=目标；saas refresh 不透传（第 4 参 null→placeholder）
    return session(user, target.tenantId, known, null)
  }

  override suspend fun authGetMenus(call: RoutingCall): List<MenuNode> {
    val claims = requireBearer(call, jwt)
    return menuCache.get(claims.subject)
        ?: throw MenusUnavailableError(
            "menu snapshot unavailable for user ${claims.subject}; re-login to refresh",
        )
  }

  override suspend fun authGetPermissions(call: RoutingCall): PermissionSet {
    requireBearer(call, jwt)
    return PermissionSet(permissions = DEMO_PERMISSIONS.toList())
  }

  override suspend fun authRefresh(
      refreshTokenRequest: RefreshTokenRequest,
      call: RoutingCall,
  ): LoginResponse {
    val body = refreshTokenRequest
    if (body.refreshToken.isEmpty()) throw InvalidCredentialsError("missing refresh_token")
    val claims =
        try {
          jwt.verify(body.refreshToken)
        } catch (err: InvalidCredentialsError) {
          throw InvalidCredentialsError("invalid refresh_token: ${err.message}", err)
        }
    if (claims.get("typ") != "refresh") {
      throw InvalidCredentialsError("invalid refresh_token: not a refresh token")
    }
    val saasRefresh = claims.get("saas_refresh_token") as? String
    if (saasRefresh.isNullOrEmpty()) {
      throw InvalidCredentialsError("invalid refresh_token: missing saas_refresh_token claim")
    }
    // saas 出站失败全包 401（前端触发重登）——whoami/listMyTenants 不包（fastapi 同款，出站 502 语义保留）
    val token =
        try {
          saasAuth.token("refresh_token", refreshToken = saasRefresh)
        } catch (err: UnauthorizedClientError) {
          throw InvalidCredentialsError("saas refresh failed: ${err.message}", err)
        } catch (err: InvalidGrantError) {
          throw InvalidCredentialsError("saas refresh failed: ${err.message}", err)
        } catch (err: SaasUpstreamError) {
          throw InvalidCredentialsError("saas refresh failed: ${err.message}", err)
        }
    val saasUser = saasMe.whoami(token.accessToken)
    val memberships = saasMe.listMyTenants(token.accessToken)
    val labUser = upsertByEmail(saasUser)
    // rotate-once：新 saas refresh 必须写回目录（Review Focus 3）
    directory.setSaasRefreshToken(labUser.id, token.refreshToken)
    cacheMenus(labUser.id, token.accessToken)
    val tenants = tenantsFrom(memberships, fetchTenantNames(token.accessToken))
    membershipCache.put(labUser.id, tenants)
    return session(labUser, claims.get("tenant_id") as? String, tenants, token.refreshToken)
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
    val nameById = fetchTenantNames(saasLogin.accessToken)
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
    return session(user, saasLogin.currentTenantId ?: tenants.firstOrNull()?.tenantId, tenants, null)
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
   * 富化 best-effort：任何出站失败都降级空表（Review Focus 3），登录照常 200。吞异常是本函数 的语义本身（随日志框架落
   * warn 由 cacheMenus 链兑现），降级行为由测试钉死。
   */
  @Suppress("SwallowedException")
  private suspend fun fetchTenantNames(accessToken: String): Map<String, SaasPlatformTenant> =
      try {
        saasMe
            .listPlatformTenants(accessToken)
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

  /** 登录链：服务账号 token 换菜单快照——try/catch 只罩 serviceLogin，拉取失败由 cacheMenus 罩。 */
  private suspend fun cacheMenusWithServiceAccount(userId: String) {
    val token =
        try {
          saasAuth.serviceLogin()
        } catch (err: UnauthorizedClientError) {
          logMenuFailure(userId, err)
          return
        } catch (err: InvalidGrantError) {
          logMenuFailure(userId, err)
          return
        } catch (err: SaasUpstreamError) {
          logMenuFailure(userId, err)
          return
        }
    cacheMenus(userId, token.accessToken)
  }

  /** 瞬时用户 token 窗口的菜单快照（refresh/SSO 链共用；失败只 warn 不阻塞）。 */
  private suspend fun cacheMenus(userId: String, accessToken: String) {
    try {
      menuCache.put(userId, mapSaasMenus(saasMe.listMyMenus(accessToken, LAB_APP_CODE)))
    } catch (err: UnauthorizedClientError) {
      logMenuFailure(userId, err)
    } catch (err: InvalidGrantError) {
      logMenuFailure(userId, err)
    } catch (err: SaasUpstreamError) {
      logMenuFailure(userId, err)
    }
  }

  /** email 键 upsert 自愈（新部署/重启目录空时兜住 SSO/refresh 链；角色镜像 springboot 固定 "viewer"）。 */
  private fun upsertByEmail(saasUser: SaasCurrentUser): CurrentUser =
      directory.findByEmail(saasUser.email)
          ?: directory.upsert(saasUser.id, saasUser.email, saasUser.displayName, "viewer")

  /** membership 行 → MyTenant（refresh/SSO 链；富化命中替换 code/name，miss 降级 tenantId）。 */
  private fun tenantsFrom(
      memberships: List<SaasTenantMembership>,
      nameById: Map<String, SaasPlatformTenant>,
  ): List<MyTenant> =
      memberships
          .filter { it.tenantId.isNotEmpty() }
          .map { m ->
            val enriched = nameById[m.tenantId]
            MyTenant(
                tenantId = m.tenantId,
                code = enriched?.tenantKey?.takeIf { it.isNotEmpty() } ?: m.tenantId,
                name = enriched?.name?.takeIf { it.isNotEmpty() } ?: m.tenantId,
                roleIds = m.roleIds.toList(),
            )
          }

  /** best-effort 失败只 warn 不抛（Review Focus 3）；Phase 4 纯 no-op，本切片随日志框架兑现。 */
  private fun logMenuFailure(userId: String, err: RuntimeException) {
    logger.warn("menu snapshot fetch failed for user {}: {}", userId, err.message)
  }

  /** sub 三路查目录（id→email→username，fastapi resolve_user 镜像）；全空 = 401。 */
  private fun resolveUser(claims: Claims): CurrentUser {
    val sub = claims.subject ?: ""
    if (sub.isEmpty()) throw InvalidCredentialsError("missing sub claim")
    return directory.findById(sub)
        ?: directory.findByEmail(sub)
        ?: directory.findByUsername(sub)
        ?: throw InvalidCredentialsError("unknown user: $sub")
  }

  private fun session(
      user: CurrentUser,
      tenantId: String?,
      tenants: List<MyTenant>,
      saasRefreshToken: String?,
  ): LoginResponse =
      LoginResponse(
          token = jwt.issue(user.id, tenantId),
          // saas refresh 透传进 JWT claim（refresh/SSO 链，T4/T5）；密码路径 null→dev-placeholder
          refreshToken = jwt.issueRefresh(user.id, saasRefreshToken ?: DEV_PLACEHOLDER_REFRESH),
          user = user,
          // 空列表保持空（fastapi `_session` 镜像：密码路径空即空，禁 demo 兜底）；
          // directory.tenantsOf 仍留作 SSO 路径（Phase 5）的 mirror 面
          tenants = tenants,
      )
}
