package io.xr.harness

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.xr.lab.impl.FakeNoopClients
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Swagger 文档面工程设施测试（REQ-2026-003）：不挂功能 ID（基础设施端点，与 /health 同模式）。 */
class DocSurfaceTest {

  private fun <T : Any> withApp(
      followRedirects: Boolean = true,
      block: suspend (io.ktor.client.HttpClient) -> T,
  ): T {
    lateinit var result: T
    runBlocking {
      testApplication {
        // testApplication 默认 client 跟随重定向——钉 302 形状必须关掉
        val c = createClient { this.followRedirects = followRedirects }
        application { harnessModule(testAppConfig(), FakeNoopClients.auth, FakeNoopClients.me) }
        result = block(c)
      }
    }
    return result
  }

  @Test
  fun `root redirects to api docs`() {
    val resp = withApp(followRedirects = false) { it.get("/") }
    assertEquals(HttpStatusCode.Found, resp.status)
    assertEquals("/api-docs", resp.headers["Location"])
  }

  @Test
  fun `api docs serves swagger shell`() {
    // bodyAsText 是 suspend——必须在 withApp 的 suspend block 内消费（编译器实证）
    val body = withApp { it.get("/api-docs").bodyAsText() }
    assertTrue("SwaggerUIBundle" in body, "文档壳页应含 SwaggerUIBundle init，实收前 200 字：${body.take(200)}")
  }

  @Test
  fun `openapi yaml serves shared contract`() {
    val (resp, body) =
        withApp { c ->
          val r = c.get("/api-docs/openapi.yaml")
          r to r.bodyAsText()
        }
    assertEquals(HttpStatusCode.OK, resp.status)
    assertTrue(
        body.startsWith("openapi:"),
        "/api-docs/openapi.yaml 应是 shared 契约生成物原样，实收前 100 字：${body.take(100)}",
    )
  }

  @Test
  fun `swagger ui webjar serves bundle`() {
    val resp = withApp { it.get("/swagger-ui/5.34.0/swagger-ui.css") }
    assertEquals(HttpStatusCode.OK, resp.status)
  }
}
