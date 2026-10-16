package io.xr.harness

import io.xr.lab.impl.HttpSaasAuthClient
import io.xr.lab.impl.SaasUpstreamError
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Review Focus 1 终审 Important 补钉：出站「读超时」必须翻 SaasUpstream（impl 层 502）。 挂死端点（accept
 * 后永不回包）——connectTimeout 只管建连，无请求级 timeout 时 send 永久阻塞。
 */
class OutboundTimeoutTest {

  @Test
  fun hangingSaasEndpointTimesOutToSaasUpstream() {
    val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val port = listener.localPort
    // accept 但只拿流不回包——模拟 saas accept-then-hang
    thread { runCatching { listener.accept().getOutputStream() } }
    val cfg = testAppConfig().copy(saasBaseUrl = "http://127.0.0.1:$port")
    val err =
        try {
          runBlocking { HttpSaasAuthClient(cfg).userLogin("u", "p") }
          null
        } catch (err: SaasUpstreamError) {
          err
        } finally {
          listener.close()
        }
    assertTrue(err != null, "挂死端点应读超时翻 SaasUpstream（实收正常返回或非上游异常）")
  }
}
