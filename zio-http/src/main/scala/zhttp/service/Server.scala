package zhttp.service

import io.netty.bootstrap.ServerBootstrap
import io.netty.util.ResourceLeakDetector
import zhttp.experiment.HEndpoint
import zhttp.http.{Status, _}
import zhttp.service.server.ServerSSLHandler._
import zhttp.service.server._
import zio.{ZManaged, _}

sealed trait Server[-R, +E] { self =>

  import Server._

  def ++[R1 <: R, E1 >: E](other: Server[R1, E1]): Server[R1, E1] =
    Concat(self, other)

  private def settings[R1 <: R, E1 >: E](s: Settings[R1, E1] = Settings()): Settings[R1, E1] = self match {
    case Concat(self, other)  => other.settings(self.settings(s))
    case Port(port)           => s.copy(port = port)
    case LeakDetection(level) => s.copy(leakDetectionLevel = level)
    case App(http)            => s.copy(http = http)
    case MaxRequestSize(size) => s.copy(maxRequestSize = size)
    case Error(errorHandler)  => s.copy(error = Some(errorHandler))
    case Ssl(sslOption)       => s.copy(sslOption = sslOption)
    case Endpoint(endpoint)   => s.copy(endpoint = endpoint)
  }

  def make(implicit ev: E <:< Throwable): ZManaged[R with EventLoopGroup with ServerChannelFactory, Throwable, Unit] =
    Server.make(self.asInstanceOf[Server[R, Throwable]])

  def start(implicit ev: E <:< Throwable): ZIO[R with EventLoopGroup with ServerChannelFactory, Throwable, Nothing] =
    make.useForever
}

object Server {
  private[zhttp] final case class Settings[-R, +E](
    http: HttpApp[R, E] = HttpApp.empty(Status.NOT_FOUND),
    port: Int = 8080,
    leakDetectionLevel: LeakDetectionLevel = LeakDetectionLevel.SIMPLE,
    maxRequestSize: Int = 4 * 1024, // 4 kilo bytes
    error: Option[Throwable => ZIO[R, Nothing, Unit]] = None,
    sslOption: ServerSSLOptions = null,
    endpoint: HEndpoint[R, E] = HEndpoint.empty,
  )

  private final case class Concat[R, E](self: Server[R, E], other: Server[R, E])      extends Server[R, E]
  private final case class Port(port: Int)                                            extends UServer
  private final case class LeakDetection(level: LeakDetectionLevel)                   extends UServer
  private final case class MaxRequestSize(size: Int)                                  extends UServer
  private final case class App[R, E](http: HttpApp[R, E])                             extends Server[R, E]
  private final case class Error[R](errorHandler: Throwable => ZIO[R, Nothing, Unit]) extends Server[R, Nothing]
  private final case class Ssl(sslOptions: ServerSSLOptions)                          extends UServer
  private final case class Endpoint[R, E](endpoint: HEndpoint[R, E])                  extends Server[R, E]

  def app[R, E](http: HttpApp[R, E]): Server[R, E]                                   = Server.App(http)
  def hApp[R, E](http: HEndpoint[R, E]): Server[R, E]                                = Server.Endpoint(http)
  def maxRequestSize(size: Int): UServer                                             = Server.MaxRequestSize(size)
  def port(int: Int): UServer                                                        = Server.Port(int)
  def error[R](errorHandler: Throwable => ZIO[R, Nothing, Unit]): Server[R, Nothing] = Server.Error(errorHandler)
  def ssl(sslOptions: ServerSSLOptions): UServer                                     = Server.Ssl(sslOptions)
  val disableLeakDetection: UServer                                                  = LeakDetection(LeakDetectionLevel.DISABLED)
  val simpleLeakDetection: UServer                                                   = LeakDetection(LeakDetectionLevel.SIMPLE)
  val advancedLeakDetection: UServer                                                 = LeakDetection(LeakDetectionLevel.ADVANCED)
  val paranoidLeakDetection: UServer                                                 = LeakDetection(LeakDetectionLevel.PARANOID)

  /**
   * Launches the app on the provided port.
   */
  def start[R <: Has[_]](
    port: Int,
    http: RHttpApp[R],
  ): ZIO[R, Throwable, Nothing] =
    (Server.port(port) ++ Server.app(http)).make.useForever
      .provideSomeLayer[R](EventLoopGroup.auto(0) ++ ServerChannelFactory.auto)

  def start0[R <: Has[_]](
    port: Int,
    app: HEndpoint[R, Throwable],
  ): ZIO[R, Throwable, Nothing] =
    (Server.port(port) ++ Server.hApp(app)).make.useForever
      .provideSomeLayer[R](EventLoopGroup.auto(0) ++ ServerChannelFactory.auto)

  def make[R](
    server: Server[R, Throwable],
  ): ZManaged[R with EventLoopGroup with ServerChannelFactory, Throwable, Unit] = {
    val settings = server.settings()
    for {
      channelFactory <- ZManaged.access[ServerChannelFactory](_.get)
      eventLoopGroup <- ZManaged.access[EventLoopGroup](_.get)
      zExec          <- UnsafeChannelExecutor.sticky[R](eventLoopGroup).toManaged_
      httpH           = ServerRequestHandler(zExec, settings)
      init            = ServerChannelInitializer(zExec, settings)
      serverBootstrap = new ServerBootstrap().channelFactory(channelFactory).group(eventLoopGroup)
      _ <- ChannelFuture.asManaged(serverBootstrap.childHandler(init).bind(settings.port))
    } yield {
      ResourceLeakDetector.setLevel(settings.leakDetectionLevel.jResourceLeakDetectionLevel)
    }
  }
}
