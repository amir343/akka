package akka.remote.transport.netty

import akka.{ OnlyCauseStackTrace, ConfigurationException }
import akka.actor.{ Address, ExtendedActorSystem }
import akka.event.Logging
import akka.remote.netty.{ SSLSettings, NettySSLSupport, DefaultDisposableChannelGroup }
import akka.remote.transport.Transport._
import akka.remote.transport.netty.NettyTransportSettings.{ Udp, Tcp, Mode }
import akka.remote.transport.{ AssociationHandle, Transport }
import com.typesafe.config.Config
import java.net.{ UnknownHostException, SocketAddress, InetAddress, InetSocketAddress, ConnectException }
import java.util.concurrent.{ ConcurrentHashMap, Executor, Executors, CancellationException }
import org.jboss.netty.bootstrap.{ ConnectionlessBootstrap, Bootstrap, ClientBootstrap, ServerBootstrap }
import org.jboss.netty.buffer.ChannelBuffer
import org.jboss.netty.channel._
import org.jboss.netty.channel.group.{ ChannelGroupFuture, ChannelGroupFutureListener }
import org.jboss.netty.channel.socket.nio.{ NioDatagramChannelFactory, NioServerSocketChannelFactory, NioClientSocketChannelFactory }
import org.jboss.netty.handler.codec.frame.{ LengthFieldBasedFrameDecoder, LengthFieldPrepender }
import scala.concurrent.duration.{ Duration, FiniteDuration, MILLISECONDS }
import scala.concurrent.{ ExecutionContext, Promise, Future }
import scala.util.{ Try, Random }
import util.control.{ NoStackTrace, NonFatal }
import akka.dispatch.ThreadPoolConfig
import akka.remote.transport.AssociationHandle.HandleEventListener
import java.util.concurrent.atomic.AtomicInteger

object NettyTransportSettings {
  sealed trait Mode
  case object Tcp extends Mode { override def toString = "tcp" }
  case object Udp extends Mode { override def toString = "udp" }
}

object NettyFutureBridge {
  def apply(nettyFuture: ChannelFuture): Future[Channel] = {
    val p = Promise[Channel]()
    nettyFuture.addListener(new ChannelFutureListener {
      def operationComplete(future: ChannelFuture): Unit = p complete Try(
        if (future.isSuccess) future.getChannel
        else if (future.isCancelled) throw new CancellationException
        else throw future.getCause)
    })
    p.future
  }
}

class NettyTransportException(msg: String, cause: Throwable) extends RuntimeException(msg, cause) with OnlyCauseStackTrace {
  def this(msg: String) = this(msg, null)
}

class NettyTransportSettings(config: Config) {

  import config._

  val TransportMode: Mode = getString("transport-protocol") match {
    case "tcp"   ⇒ Tcp
    case "udp"   ⇒ Udp
    case unknown ⇒ throw new ConfigurationException(s"Unknown transport: $unknown")
  }

  val EnableSsl: Boolean = if (getBoolean("enable-ssl") && TransportMode == Udp)
    throw new ConfigurationException("UDP transport does not support SSL")
  else getBoolean("enable-ssl")

  val UseDispatcherForIo: Option[String] = getString("use-dispatcher-for-io") match {
    case "" | null  ⇒ None
    case dispatcher ⇒ Some(dispatcher)
  }

  private[this] def optionSize(s: String): Option[Int] = getBytes(s).toInt match {
    case 0          ⇒ None
    case x if x < 0 ⇒ throw new ConfigurationException(s"Setting '$s' must be 0 or positive (and fit in an Int)")
    case other      ⇒ Some(other)
  }

  val ConnectionTimeout: FiniteDuration = Duration(getMilliseconds("connection-timeout"), MILLISECONDS)

  val WriteBufferHighWaterMark: Option[Int] = optionSize("write-buffer-high-water-mark")

  val WriteBufferLowWaterMark: Option[Int] = optionSize("write-buffer-low-water-mark")

  val SendBufferSize: Option[Int] = optionSize("send-buffer-size")

  val ReceiveBufferSize: Option[Int] = optionSize("receive-buffer-size")

  val Backlog: Int = getInt("backlog")

  val Hostname: String = getString("hostname") match {
    case ""    ⇒ InetAddress.getLocalHost.getHostAddress
    case value ⇒ value
  }

  @deprecated("WARNING: This should only be used by professionals.", "2.0")
  val PortSelector: Int = getInt("port")

  val SslSettings: Option[SSLSettings] = if (EnableSsl) Some(new SSLSettings(config.getConfig("ssl"))) else None

  val ServerSocketWorkerPoolSize: Int = computeWPS(config.getConfig("server-socket-worker-pool"))

  val ClientSocketWorkerPoolSize: Int = computeWPS(config.getConfig("client-socket-worker-pool"))

  private def computeWPS(config: Config): Int =
    ThreadPoolConfig.scaledPoolSize(
      config.getInt("pool-size-min"),
      config.getDouble("pool-size-factor"),
      config.getInt("pool-size-max"))

}

trait CommonHandlers extends NettyHelpers {
  protected val transport: NettyTransport

  final override def onOpen(ctx: ChannelHandlerContext, e: ChannelStateEvent): Unit = transport.channelGroup.add(e.getChannel)

  protected def createHandle(channel: Channel, localAddress: Address, remoteAddress: Address): AssociationHandle

  protected def registerListener(channel: Channel,
                                 listener: HandleEventListener,
                                 msg: ChannelBuffer,
                                 remoteSocketAddress: InetSocketAddress): Unit

  final protected def init(channel: Channel, remoteSocketAddress: SocketAddress, msg: ChannelBuffer)(op: (AssociationHandle ⇒ Any)): Unit = {
    import transport._
    (addressFromSocketAddress(channel.getLocalAddress), addressFromSocketAddress(remoteSocketAddress)) match {
      case (Some(localAddress), Some(remoteAddress)) ⇒
        val handle = createHandle(channel, localAddress, remoteAddress)
        handle.readHandlerPromise.future.onSuccess {
          case listener: HandleEventListener ⇒
            registerListener(channel, listener, msg, remoteSocketAddress.asInstanceOf[InetSocketAddress])
            channel.setReadable(true)
        }
        op(handle)

      case _ ⇒ NettyTransport.gracefulClose(channel)
    }
  }
}

abstract class ServerHandler(protected final val transport: NettyTransport,
                             private final val associationListenerFuture: Future[AssociationEventListener])
  extends NettyServerHelpers with CommonHandlers {

  import transport.executionContext

  final protected def initInbound(channel: Channel, remoteSocketAddress: SocketAddress, msg: ChannelBuffer): Unit = {
    channel.setReadable(false)
    associationListenerFuture.onSuccess {
      case listener: AssociationEventListener ⇒ init(channel, remoteSocketAddress, msg) { listener notify InboundAssociation(_) }
    }
  }

}

abstract class ClientHandler(protected final val transport: NettyTransport,
                             private final val statusPromise: Promise[AssociationHandle])
  extends NettyClientHelpers with CommonHandlers {

  final protected def initOutbound(channel: Channel, remoteSocketAddress: SocketAddress, msg: ChannelBuffer): Unit = {
    channel.setReadable(false)
    init(channel, remoteSocketAddress, msg)(statusPromise.success)
  }

}

private[transport] object NettyTransport {
  // 4 bytes will be used to represent the frame length. Used by netty LengthFieldPrepender downstream handler.
  val FrameLengthFieldLength = 4
  def gracefulClose(channel: Channel): Unit = channel.disconnect().addListener(ChannelFutureListener.CLOSE)

  val uniqueIdCounter = new AtomicInteger(0)
}

// FIXME: Split into separate UDP and TCP classes
class NettyTransport(private val settings: NettyTransportSettings, private val system: ExtendedActorSystem) extends Transport {

  def this(system: ExtendedActorSystem, conf: Config) = this(new NettyTransportSettings(conf), system)

  import NettyTransport._
  import settings._

  implicit val executionContext: ExecutionContext = system.dispatcher

  override val schemeIdentifier: String = TransportMode + (if (EnableSsl) ".ssl" else "")
  override val maximumPayloadBytes: Int = 32000 // The number of octets required by the remoting specification

  private final val isDatagram: Boolean = TransportMode == Udp

  @volatile private var localAddress: Address = _
  @volatile private var serverChannel: Channel = _

  private val log = Logging(system, this.getClass)

  final val udpConnectionTable = new ConcurrentHashMap[SocketAddress, HandleEventListener]()

  val channelGroup = new DefaultDisposableChannelGroup("akka-netty-transport-driver-channelgroup-" +
    uniqueIdCounter.getAndIncrement)

  private val clientChannelFactory: ChannelFactory = TransportMode match {
    case Tcp ⇒
      val boss, worker = UseDispatcherForIo.map(system.dispatchers.lookup) getOrElse Executors.newCachedThreadPool()
      new NioClientSocketChannelFactory(boss, worker, ClientSocketWorkerPoolSize)
    case Udp ⇒
      val pool = UseDispatcherForIo.map(system.dispatchers.lookup) getOrElse Executors.newCachedThreadPool()
      new NioDatagramChannelFactory(pool, ClientSocketWorkerPoolSize)
  }

  private val serverChannelFactory: ChannelFactory = TransportMode match {
    case Tcp ⇒
      val boss, worker = UseDispatcherForIo.map(system.dispatchers.lookup) getOrElse Executors.newCachedThreadPool()
      new NioServerSocketChannelFactory(boss, worker, ServerSocketWorkerPoolSize)
    case Udp ⇒
      val pool = UseDispatcherForIo.map(system.dispatchers.lookup) getOrElse Executors.newCachedThreadPool()
      new NioDatagramChannelFactory(pool, ServerSocketWorkerPoolSize)
  }

  private def newPipeline: DefaultChannelPipeline = {
    val pipeline = new DefaultChannelPipeline

    if (!isDatagram) {
      pipeline.addLast("FrameDecoder", new LengthFieldBasedFrameDecoder(
        maximumPayloadBytes,
        0,
        FrameLengthFieldLength,
        0,
        FrameLengthFieldLength, // Strip the header
        true))
      pipeline.addLast("FrameEncoder", new LengthFieldPrepender(FrameLengthFieldLength))
    }

    pipeline
  }

  private val associationListenerPromise: Promise[AssociationEventListener] = Promise()
  private val serverPipelineFactory: ChannelPipelineFactory = new ChannelPipelineFactory {
    override def getPipeline: ChannelPipeline = {
      val pipeline = newPipeline
      if (EnableSsl) pipeline.addFirst("SslHandler", NettySSLSupport(settings.SslSettings.get, log, false))
      val handler = if (isDatagram) new UdpServerHandler(NettyTransport.this, associationListenerPromise.future)
      else new TcpServerHandler(NettyTransport.this, associationListenerPromise.future)
      pipeline.addLast("ServerHandler", handler)
      pipeline
    }
  }

  private def clientPipelineFactory(statusPromise: Promise[AssociationHandle]): ChannelPipelineFactory = new ChannelPipelineFactory {
    override def getPipeline: ChannelPipeline = {
      val pipeline = newPipeline
      if (EnableSsl) pipeline.addFirst("SslHandler", NettySSLSupport(settings.SslSettings.get, log, true))
      val handler = if (isDatagram) new UdpClientHandler(NettyTransport.this, statusPromise)
      else new TcpClientHandler(NettyTransport.this, statusPromise)
      pipeline.addLast("clienthandler", handler)
      pipeline
    }
  }

  private def setupBootstrap[B <: Bootstrap](bootstrap: B, pipelineFactory: ChannelPipelineFactory): B = {
    // FIXME: Expose these settings in configuration
    bootstrap.setPipelineFactory(pipelineFactory)
    bootstrap.setOption("backlog", settings.Backlog)
    bootstrap.setOption("tcpNoDelay", true)
    bootstrap.setOption("child.keepAlive", true)
    bootstrap.setOption("reuseAddress", true)
    if (isDatagram) bootstrap.setOption("receiveBufferSizePredictorFactory", new FixedReceiveBufferSizePredictorFactory(ReceiveBufferSize.get))
    settings.ReceiveBufferSize.foreach(sz ⇒ bootstrap.setOption("receiveBufferSize", sz))
    settings.SendBufferSize.foreach(sz ⇒ bootstrap.setOption("sendBufferSize", sz))
    settings.WriteBufferHighWaterMark.foreach(sz ⇒ bootstrap.setOption("writeBufferHighWaterMark", sz))
    settings.WriteBufferLowWaterMark.foreach(sz ⇒ bootstrap.setOption("writeBufferLowWaterMark", sz))
    bootstrap
  }

  private val inboundBootstrap: Bootstrap = settings.TransportMode match {
    case Tcp ⇒ setupBootstrap(new ServerBootstrap(serverChannelFactory), serverPipelineFactory)
    case Udp ⇒ setupBootstrap(new ConnectionlessBootstrap(serverChannelFactory), serverPipelineFactory)
  }

  private def outboundBootstrap(statusPromise: Promise[AssociationHandle]): ClientBootstrap = {
    val bootstrap = setupBootstrap(new ClientBootstrap(clientChannelFactory), clientPipelineFactory(statusPromise))
    bootstrap.setOption("connectTimeoutMillis", settings.ConnectionTimeout.toMillis)
    bootstrap
  }

  override def isResponsibleFor(address: Address): Boolean = true //TODO: Add configurable subnet filtering

  def addressFromSocketAddress(addr: SocketAddress,
                               systemName: Option[String] = None,
                               hostName: Option[String] = None): Option[Address] = addr match {
    case sa: InetSocketAddress ⇒ Some(Address(schemeIdentifier, systemName.getOrElse(""), hostName.getOrElse(sa.getHostName), sa.getPort))
    case _                     ⇒ None
  }

  def addressToSocketAddress(addr: Address): InetSocketAddress =
    new InetSocketAddress(InetAddress.getByName(addr.host.get), addr.port.get)

  override def listen: Future[(Address, Promise[AssociationEventListener])] =
    (Promise[(Address, Promise[AssociationEventListener])]() complete Try {
      val address = addressToSocketAddress(Address("", "", settings.Hostname, settings.PortSelector))
      val newServerChannel = inboundBootstrap match {
        case b: ServerBootstrap         ⇒ b.bind(address)
        case b: ConnectionlessBootstrap ⇒ b.bind(address)
      }

      // Block reads until a handler actor is registered
      newServerChannel.setReadable(false)
      channelGroup.add(newServerChannel)

      serverChannel = newServerChannel

      addressFromSocketAddress(newServerChannel.getLocalAddress, Some(system.name), Some(settings.Hostname)) match {
        case Some(address) ⇒
          localAddress = address
          associationListenerPromise.future.onSuccess { case listener ⇒ newServerChannel.setReadable(true) }
          (address, associationListenerPromise)
        case None ⇒ throw new NettyTransportException(s"Unknown local address type ${newServerChannel.getLocalAddress.getClass}")
      }
    }).future

  override def associate(remoteAddress: Address): Future[AssociationHandle] = {
    if (!serverChannel.isBound) Future.failed(new NettyTransportException("Transport is not bound"))
    else {
      val statusPromise = Promise[AssociationHandle]()
      (try {
        val f = NettyFutureBridge(outboundBootstrap(statusPromise).connect(addressToSocketAddress(remoteAddress))) recover {
          case c: CancellationException ⇒ throw new NettyTransportException("Connection was cancelled")
        }

        if (isDatagram)
          f map { channel ⇒
            channel.getRemoteAddress match {
              case addr: InetSocketAddress ⇒
                val handle = new UdpAssociationHandle(localAddress, remoteAddress, channel, NettyTransport.this)
                statusPromise.success(handle)
                handle.readHandlerPromise.future.onSuccess { case listener ⇒ udpConnectionTable.put(addr, listener) }
              case unknown ⇒ throw new NettyTransportException(s"Unknown remote address type ${unknown.getClass}")
            }
          }
        else f
      } catch {
        case e @ (_: UnknownHostException | _: SecurityException | _: IllegalArgumentException) ⇒
          Future.failed(InvalidAssociationException("Invalid association ", e))
        case NonFatal(e) ⇒
          Future.failed(e)
      }) onFailure {
        case t: ConnectException ⇒ statusPromise failure new NettyTransportException(t.getMessage, t.getCause)
        case t ⇒ statusPromise failure t
      }

      statusPromise.future
    }
  }

  override def shutdown(): Unit = {
    channelGroup.unbind()
    channelGroup.disconnect().addListener(new ChannelGroupFutureListener {
      def operationComplete(future: ChannelGroupFuture) {
        channelGroup.close()
        inboundBootstrap.releaseExternalResources()
      }
    })
  }

}

