package com.twitter.finagle.buoyant.h2

import com.twitter.finagle._
import com.twitter.finagle.buoyant.Sampler
import com.twitter.finagle.tracing.{Trace, TraceInitializerFilter, Tracer}

object TraceInitializer {
  val role = TraceInitializerFilter.role

  class ServerFilter(tracer: Tracer, defaultSampler: Option[Sampler] = None)
    extends SimpleFilter[Request, Response] {

    /**
     * Establish context for this request, as follows:
     * 1. Set the trace id from the context header, if one was provided.
     * 2. Get a new span id for the current request.
     * 3. Use the sample header to determine if the request should be sampled.
     */
    def apply(req: Request, service: Service[Request, Response]) = {
      val headers = req.headers
      val ctx = LinkerdHeaders.Ctx.Trace.get(headers)
      LinkerdHeaders.Ctx.Trace.clear(headers)
      val sampler = LinkerdHeaders.Sample.get(headers).map(Sampler(_))
      LinkerdHeaders.Sample.clear(headers)

      Trace.letIdOption(ctx) {
        Trace.letTracerAndNextId(tracer) {
          sample(sampler.orElse(defaultSampler)) {
            service(req)
          }
        }
      }
    }

    /**
     * Only set _sampled on the trace ID if the sample header provided a
     * sample rate, the sampler determines that the request should be
     * sampled based on the sample rate, and the _sampled field is unset on
     * the current trace ID.
     */
    def sample[T](sampler: Option[Sampler])(f: => T) =
      sampler match {
        case None => f
        case Some(sampler) =>
          val id = Trace.id
          val sampled = id.copy(_sampled = Some(sampler(id.traceId.toLong)))
          Trace.letId(sampled)(f)
      }
  }

  class ClientFilter(tracer: Tracer) extends SimpleFilter[Request, Response] {
    def apply(req: Request, service: Service[Request, Response]) =
      Trace.letTracerAndNextId(tracer) {
        val headers = req.headers
        LinkerdHeaders.Ctx.Trace.set(headers, Trace.id)
        LinkerdHeaders.RequestId.set(headers, Trace.id)
        service(req)
      }
  }

  /**
   * The server reads the ctx header ([Headers.Ctx.Key]) to load
   * trace information.
   */
  val serverModule: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module1[param.Tracer, ServiceFactory[Request, Response]] {
      val role = TraceInitializer.role
      val description = "Reads trace information from incoming request"

      def make(_tracer: param.Tracer, next: ServiceFactory[Request, Response]) = {
        val param.Tracer(tracer) = _tracer
        new ServerFilter(tracer) andThen next
      }
    }

  /**
   * So, on the client side, we set headers after initializing a new context.
   */
  val clientModule: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module1[param.Tracer, ServiceFactory[Request, Response]] {
      val role = TraceInitializer.role
      val description = "Attaches trace information to the outgoing request"
      def make(_tracer: param.Tracer, next: ServiceFactory[Request, Response]) = {
        val param.Tracer(tracer) = _tracer
        new ClientFilter(tracer) andThen next
      }
    }
}
