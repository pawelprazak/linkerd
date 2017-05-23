package io.buoyant.namerd

import com.fasterxml.jackson.annotation.JsonIgnore
import com.github.ghik.silencer.silent
import com.twitter.finagle.Stack
import com.twitter.finagle.ssl.{KeyCredentials, Ssl}
import com.twitter.finagle.ssl.server.{LegacyKeyServerEngineFactory, SslServerConfiguration, SslServerEngineFactory}
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.finagle.transport.Transport
import com.twitter.finagle.{Namer, Path}
import io.buoyant.config.types.Port
import io.buoyant.config.{PolymorphicConfig, ConfigInitializer}
import java.io.File
import java.net.{InetAddress, InetSocketAddress}

/**
 * Configures a network interface to namerd functionality.
 */
abstract class InterfaceConfig extends PolymorphicConfig {
  var ip: Option[InetAddress] = None
  var port: Option[Port] = None
  var tls: Option[TlsServerConfig] = None

  @JsonIgnore
  def addr = new InetSocketAddress(
    ip.getOrElse(defaultAddr.getAddress),
    port.map(_.port).getOrElse(defaultAddr.getPort)
  )

  @JsonIgnore
  protected def defaultAddr: InetSocketAddress

  def mk(store: DtabStore, namers: Map[Path, Namer], stats: StatsReceiver): Servable
}

abstract class InterfaceInitializer extends ConfigInitializer

case class TlsServerConfig(certPath: String, keyPath: String) {
  val params = {
    val creds = KeyCredentials.CertAndKey(new File(certPath), new File(keyPath))
    @silent val sslServerEngine = SslServerEngineFactory.Param(LegacyKeyServerEngineFactory)
    Stack.Params.empty +
      Transport.ServerSsl(Some(SslServerConfiguration(keyCredentials = creds))) +
      sslServerEngine
  }
}
