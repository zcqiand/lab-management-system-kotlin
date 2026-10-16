package io.xr.harness

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.xr.lab.impl.FakeNoopClients
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 工程设施测试（ktor-conventions 规则 4）：组合根探活 + CORS 白名单，不挂功能 ID。 */
class HarnessModuleTest {

  @Test
  fun `harness module serves health and cors echo`() {
    lateinit var body: String
    var status: HttpStatusCode? = null
    var echo: String? = null
    runBlocking {
      testApplication {
        application { harnessModule(testAppConfig(), FakeNoopClients.auth, FakeNoopClients.me) }
        body = client.get("/health").bodyAsText()
        val cors = client.get("/health") { header("Origin", "http://localhost:5101") }
        status = cors.status
        echo = cors.headers["Access-Control-Allow-Origin"]
      }
    }
    assertEquals("""{"ok":true,"mode":"ktor"}""", body)
    assertEquals(HttpStatusCode.OK, status)
    assertEquals("http://localhost:5101", echo)
  }

  @Test
  fun `cors preflight allows write methods like fastapi allow_methods star`() {
    // 终审 Important 补钉：fastapi CORSMiddleware allow_methods=["*"]——跨域 PUT/PATCH/DELETE
    // （Phase 5 CRUD 面）不得只在 ktor 后端被浏览器拦
    var status: HttpStatusCode? = null
    var allowMethods: String? = null
    runBlocking {
      testApplication {
        application { harnessModule(testAppConfig(), FakeNoopClients.auth, FakeNoopClients.me) }
        val preflight =
            client.options("/api/auth/native-login") {
              header("Origin", "http://localhost:5201")
              header("Access-Control-Request-Method", "PUT")
            }
        status = preflight.status
        allowMethods = preflight.headers["Access-Control-Allow-Methods"]
      }
    }
    assertEquals(HttpStatusCode.OK, status)
    assertTrue(
        allowMethods?.contains("PUT") == true,
        "preflight PUT 应放行，实收 Allow-Methods=$allowMethods",
    )
  }
}
