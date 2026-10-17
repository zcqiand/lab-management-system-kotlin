package io.xr.lab.impl

/** 组合根设施测试用 no-op fake：不外呼、恒空（工程设施测试不触 IdP 面）。 */
object FakeNoopClients {

  val auth: SaasAuthClient =
      object : SaasAuthClient {
        override suspend fun userLogin(username: String, password: String): SaasLoginResponse =
            throw SaasUpstreamError("infra test must not reach saas auth")

        override suspend fun serviceLogin(): SaasToken =
            throw SaasUpstreamError("infra test must not reach saas auth")

        override suspend fun token(
            grantType: String,
            code: String?,
            refreshToken: String?,
            redirectUri: String?,
        ): SaasToken = throw SaasUpstreamError("infra test must not reach saas auth")
      }

  val me: SaasMeClient =
      object : SaasMeClient {
        override suspend fun listPlatformTenants(accessToken: String): List<SaasPlatformTenant> =
            emptyList()

        override suspend fun listMyMenus(accessToken: String, appCode: String): List<SaasMenuNode> =
            emptyList()

        override suspend fun whoami(accessToken: String): SaasCurrentUser =
            throw SaasUpstreamError("infra test must not reach saas auth")

        override suspend fun listMyTenants(accessToken: String): List<SaasTenantMembership> =
            throw SaasUpstreamError("infra test must not reach saas auth")
      }
}
