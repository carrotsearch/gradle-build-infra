package com.carrotsearch.gradle.buildinfra.publishing.mavencentral

import java.util.concurrent.ConcurrentLinkedQueue
import org.apache.hc.core5.http.ClassicHttpRequest
import org.apache.hc.core5.http.ClassicHttpResponse
import org.apache.hc.core5.http.ContentType
import org.apache.hc.core5.http.impl.bootstrap.HttpServer
import org.apache.hc.core5.http.impl.bootstrap.ServerBootstrap
import org.apache.hc.core5.http.io.HttpRequestHandler
import org.apache.hc.core5.http.io.entity.EntityUtils
import org.apache.hc.core5.http.io.entity.StringEntity
import org.apache.hc.core5.io.CloseMode

/**
 * A stub of Central Portal's publisher API.
 */
class CentralPortalStub implements AutoCloseable {
  private static final String HOST = "127.0.0.1"

  static class RecordedRequest {
    String method
    String path
    String query
    Map<String, String> headers
    byte[] body
  }

  final List<RecordedRequest> requests = [].asSynchronized()
  final Queue<String> deploymentStates = new ConcurrentLinkedQueue<>()

  int uploadResponseCode = 201
  String uploadResponseBody = "deployment-id-1234"
  String lastState = "PENDING"

  private final HttpServer server

  CentralPortalStub() {
    server = ServerBootstrap.bootstrap()
        .setLocalAddress(InetAddress.getLoopbackAddress())
        // Must match the host of incoming requests (or they're rejected with HTTP 421).
        .setCanonicalHostName(HOST)
        .setListenerPort(0)
        .register("*", { ClassicHttpRequest request, ClassicHttpResponse response, context ->
          handle(request, response)
        } as HttpRequestHandler)
        .create()
    server.start()
  }

  String getUrl() {
    return "http://${HOST}:${server.localPort}"
  }

  List<RecordedRequest> requestsTo(String pathSuffix) {
    return requests.findAll { it.path.endsWith(pathSuffix) }
  }

  private void handle(ClassicHttpRequest request, ClassicHttpResponse response) {
    def uri = request.uri
    requests.add(new RecordedRequest(
        method: request.method,
        path: uri.path,
        query: uri.query,
        headers: request.headers.collectEntries { [it.name.toLowerCase(Locale.ROOT), it.value] },
        body: request.entity == null ? new byte[0] : EntityUtils.toByteArray(request.entity)))

    switch (uri.path) {
      case "/api/v1/publisher/upload":
        respond(response, uploadResponseCode, uploadResponseBody, ContentType.TEXT_PLAIN)
        break

      case "/api/v1/publisher/status":
        lastState = deploymentStates.poll() ?: lastState
        respond(response, 200, """
          {"deploymentId": "${uploadResponseBody}", "deploymentName": "test",
           "deploymentState": "${lastState}", "purls": [],
           "errors": {"common": ["Stub validation error."]}}
          """, ContentType.APPLICATION_JSON)
        break

      default:
        respond(response, 404, "Not found: ${uri.path}", ContentType.TEXT_PLAIN)
    }
  }

  private static void respond(ClassicHttpResponse response, int code, String body, ContentType contentType) {
    response.setCode(code)
    response.setEntity(new StringEntity(body, contentType))
  }

  @Override
  void close() {
    server.close(CloseMode.IMMEDIATE)
  }
}
