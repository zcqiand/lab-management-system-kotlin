package io.xr.harness

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.xr.lab.impl.FakeNoopClients
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
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
}
