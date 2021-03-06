package io.buoyant.linkerd.protocol.h2

import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.buoyant.h2._
import com.twitter.finagle.{Service, Dtab, Path}
import com.twitter.io.Buf
import com.twitter.util.Future
import io.buoyant.router.RoutingFactory._
import io.buoyant.test.Awaits
import org.scalatest.FunSuite
import com.twitter.finagle.http.{Request => FRequest, Response => FResponse}

class IngressIdentifierTest extends FunSuite with Awaits {

  val ingressListResource = Buf.Utf8("""{
    "kind":"IngressList",
    "apiVersion":"extensions/v1beta",
    "items": [{
      "kind":"Ingress",
      "apiVersion":"extensions/v1beta",
      "metadata":{"name":"test-ingress","namespace":"fooNamespace","selfLink":"/apis/extensions/v1beta1/namespaces/srv/ingresses/test-ingress","resourceVersion":"4430527"},
      "spec": {
        "backend": {
          "serviceName": "defaultService",
          "servicePort": "defaultPort"
        },
        "rules": [{
          "host": "foo.bar.com",
          "http": {
            "paths": [{
              "path": "/fooPath/.*",
              "backend": {
                "serviceName": "fooPathService",
                "servicePort": "fooPathPort"
              }
            },
            {
              "backend": {
                "serviceName": "fooHostService",
                "servicePort": "fooHostPort"
              }
            }]
          }
        },{
          "http": {
            "paths": [{
              "path": "/barPath(/?.*)",
              "backend": {
                "serviceName": "barPathService",
                "servicePort": "barPathPort"
              }
            }]
          }
        }]
      }
    }]
  }""")

  val service = Service.mk[FRequest, FResponse] {
    case req if req.uri == "/apis/extensions/v1beta1/ingresses" =>
      val rsp = FResponse()
      rsp.content = ingressListResource
      Future.value(rsp)
    case req if req.uri == "/apis/extensions/v1beta1/ingresses?watch=true" =>
      val rsp = FResponse()
      rsp.content = ingressListResource
      Future.value(rsp)
    case req =>
      fail(s"unexpected request: $req")
  }

  test("identifies requests by host, without path") {
    val identifier = new IngressIdentifier(Path.Utf8("svc"), () => Dtab.empty, None, false, service)
    val req0 = Request("http", Method.Get, "foo.bar.com", "/penguins", Stream.empty())
    await(identifier(req0)) match {
      case IdentifiedRequest(Dst.Path(name, base, local), req1) =>
        assert(name == Path.read("/svc/fooNamespace/fooHostPort/fooHostService"))
        assert(req1.path == "/penguins")
      case id: UnidentifiedRequest[Request] => fail(s"unexpected identification: ${id.reason}")
    }
  }

  test("identifies requests by host & path") {
    val identifier = new IngressIdentifier(Path.Utf8("svc"), () => Dtab.empty, None, false, service)
    val req0 = Request("http", Method.Get, "foo.bar.com", "/fooPath/penguins", Stream.empty())
    await(identifier(req0)) match {
      case IdentifiedRequest(Dst.Path(name, _, _), req1) =>
        assert(name == Path.read("/svc/fooNamespace/fooPathPort/fooPathService"))
        assert(req1.path == "/fooPath/penguins")
      case id: UnidentifiedRequest[Request] => fail(s"unexpected identification: ${id.reason}")
    }
  }

  test("falls back to the default backend") {
    val identifier = new IngressIdentifier(Path.Utf8("svc"), () => Dtab.empty, None, false, service)
    val req0 = Request("http", Method.Get, "authority", "/", Stream.empty())
    await(identifier(req0)) match {
      case IdentifiedRequest(Dst.Path(name, _, _), req1) =>
        assert(name == Path.read("/svc/fooNamespace/defaultPort/defaultService"))
        assert(req1.path == "/")
      case id: UnidentifiedRequest[Request] => fail(s"unexpected identification: ${id.reason}")
    }
  }

  test("identifies requests by path and rewrites it using capturing groups") {
    val identifier = new IngressIdentifier(Path.Utf8("svc"), () => Dtab.empty, None, true, service)
    val req0 = Request("http", Method.Get, "authority", "/barPath/penguins", Stream.empty())
    await(identifier(req0)) match {
      case IdentifiedRequest(Dst.Path(name, _, _), req1) =>
        assert(name == Path.read("/svc/fooNamespace/barPathPort/barPathService"))
        assert(req1.path == "/penguins")
      case id: UnidentifiedRequest[Request] => fail(s"unexpected identification: ${id.reason}")
    }
  }
}
