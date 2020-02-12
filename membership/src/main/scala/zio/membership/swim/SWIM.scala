//package zio.membership
//
//import zio._
//import zio.clock.Clock
//import zio.duration._
//import zio.logging.Logging
//import zio.logging.slf4j._
//import zio.macros.delegate._
//import zio.nio.{InetAddress, SocketAddress}
//import zio.random.Random
//import zio.stm.{STM, TMap}
//import zio.stream.{Stream, ZStream}
//
//import scala.collection.immutable.SortedSet
//import zio.membership.Member
//import zio.membership.NodeId
//import zio.membership.discovery.Discovery
//import zio.membership.swim.InitialProtocol.Join
//import zio.membership.swim.{GossipState, InitialProtocol}
//import zio.membership.transport._
//
//final class SWIM[A](
//                  localAddr : A,
//                  nodeChannels: Ref[Map[NodeId, ChunkConnection]],//this should be connection state new, init, disconected
//                  gossipStateRef: Ref[GossipState[A]],
//                  userMessageQueue: zio.Queue[Message],
//                  clusterMessageQueue: zio.Queue[Message],
//                  clusterEventsQueue: zio.Queue[MembershipEvent[A]],
//                  subscribeToBroadcast: UIO[Stream[Nothing, Chunk[Byte]]],
//                  publishToBroadcast: Chunk[Byte] => UIO[Unit],
//                  msgOffset: Ref[Long],
//                  acks: TMap[Long, Promise[Error, Unit]]
//) extends Membership.Service[Any, A] {
//
////  override val events: ZStream[Any, Error, MembershipEvent] =
////    ZStream.fromQueue(clusterEventsQueue)
//
//  override val identity: ZIO[Any, Nothing, A] =
//    ZIO.succeed(localAddr)
//
////  override def broadcast(data: Chunk[Byte]): IO[Error, Unit] =
////    serializeMessage(localMember_, data, 2).flatMap[Any, Error, Unit](publishToBroadcast).unit
//
////  override def nodes: ZIO[Any, Nothing, List[A]] =
////    nodeChannels.get
////      .map(_.keys.toList)
//
////  override def receive: Stream[Error, Message] =
////    zio.stream.Stream.fromQueue(userMessageQueue)
////
////  override def send(data: Chunk[Byte], receipt: NodeId): IO[Error, Unit] =
////    sendMessage(receipt, 2, data)
//
////  private def sendMessage(to: NodeId, msgType: Int, payload: Chunk[Byte]) =
////    for {
////      node <- nodeChannels.get.map(_.get(to))
////      _ <- node match {
////            case Some(channel) =>
////              serializeMessage(localMember_, payload, msgType) >>= channel.send
////            case None => ZIO.fail(UnknownNode(to))
////          }
////    } yield ()
//
//  def receiveInitialProtocol[R <: Transport[T] with Logging[String], E >: Error, T](
//                                                                                                                        stream: ZStream[R, E, Managed[Nothing, ChunkConnection]],
//                                                                                                                        concurrentConnections: Int = 16
//                                                                                                                      )(
//                                                                                                                        implicit
//                                                                                                                        ev1: TaggedCodec[InitialProtocol[A]],
//                                                                                                                        ev2: ByteCodec[JoinReply[T]]
//                                                                                                                      ): ZStream[R, E, (A, Chunk[Byte] => IO[TransportError, Unit], Stream[Error, Chunk[Byte]], UIO[_])] =
//    ZStream.managed(ScopeIO.make).flatMap { allocate =>
//      stream
//        .mapMPar(concurrentConnections) { conM =>
//          allocate {
//            conM
//              .flatMap { con =>
//                con.receive.process.mapM[R, E, Option[(A, Chunk[Byte] => IO[TransportError, Unit], Stream[Error, Chunk[Byte]])]] { pull =>
//                  pull
//                    .foldM(
//                      _.fold[ZIO[R, E, Option[A]]](ZIO.succeed(None))(ZIO.fail), { raw =>
//                        TaggedCodec
//                          .read[InitialProtocol[A]](raw)
//                          .tap(msg => log.debug(s"receiveInitialProtocol: $msg"))
//                          .flatMap {
//                            case msg: Join[A] =>
//                              Views.using[T] {
//                                views =>
//                                  val accept = for {
//                                    reply <- TaggedCodec.write[NeighborReply](NeighborReply.Accept)
//                                    _     <- log.debug(s"Accepting neighborhood request from ${msg.sender}")
//                                    _     <- con.send(reply)
//                                  } yield Some(msg.sender)
//
//                                  val reject = for {
//                                    reply <- TaggedCodec.write[NeighborReply](NeighborReply.Reject)
//                                    _     <- log.debug(s"Rejecting neighborhood request from ${msg.sender}")
//                                    _     <- con.send(reply)
//                                  } yield None
//
//                                  if (msg.isHighPriority) {
//                                    accept
//                                  } else {
//                                    STM.atomically {
//                                      for {
//                                        full <- views.isActiveViewFull
//                                        task <- if (full) {
//                                          views
//                                            .addToPassiveView(msg.sender)
//                                            .as(
//                                              reject
//                                            )
//                                        } else {
//                                          STM.succeed(accept)
//                                        }
//                                      } yield task
//                                    }.flatten
//                                  }
//                              }
//                            case msg: InitialMessage.Join[T] =>
//                              Views.using[T] { views =>
//                                for {
//                                  others <- views.activeView.map(_.filterNot(_ == msg.sender)).commit
//                                  config <- getConfig
//                                  _ <- ZIO
//                                    .foreachPar_(others)(
//                                      node =>
//                                        views
//                                          .send(
//                                            node,
//                                            ActiveProtocol
//                                              .ForwardJoin(views.myself, msg.sender, TimeToLive(config.arwl))
//                                          )
//                                    )
//                                  reply <- ByteCodec[JoinReply[T]].toChunk(JoinReply(views.myself))
//                                  _     <- con.send(reply)
//                                } yield Some(msg.sender)
//                              }
//                            case msg: InitialMessage.ForwardJoinReply[T] =>
//                              // nothing to do here, we just continue to the next protocol
//                              ZIO.succeed(Some(msg.sender))
//                            case msg: InitialMessage.ShuffleReply[T] =>
//                              Views.using[T] { views =>
//                                views
//                                  .addShuffledNodes(msg.sentOriginally.toSet, msg.passiveNodes.toSet)
//                                  .commit
//                                  .as(None)
//                              }
//                          }
//                      }
//                    )
//                    .map(_.map((_, con.send, ZStream.fromPull(pull))))
//                }
//              }
//
//          }.foldM(
//            e => log.error("Failure while running initial protocol", Cause.fail(e)).as(None), {
//              case (None, release)                        => release.as(None)
//              case (Some((addr, send, receive)), release) => ZIO.succeed(Some((addr, send, receive, release)))
//            }
//          )
//        }
//        .collect {
//          case Some(x) => x
//        }
//    }
//
//  private def acceptConnectionRequests =
//    for {
//      env          <- ZManaged.environment[Logging[String] with Transport[A] with Clock]
//      _            <- handleClusterMessages(ZStream.fromQueue(clusterMessageQueue)).fork.toManaged_
//      //localAddress <- localMember.flatMap(_.addr.socketAddress).toManaged_
//      server <- env.transport.bind(localAddr) { channelOut =>
//                 (for {
//                   state <- gossipStateRef.get
//                   _     <- sendInternalMessage(channelOut, Join(state, localMember_))
//                   _ <- expects(channelOut) {
//                         case JoinCluster(remoteState, remoteMember) =>
//                           logger.info(remoteMember.toString + " joined cluster") *>
//                             addMember(remoteMember, channelOut) *>
//                             updateState(remoteState) *>
//                             listenOnChannel(channelOut, remoteMember)
//                       }
//                 } yield ())
//                   .catchAll(ex => logger.error(s"Connection failed: $ex"))
//                   .provide(env)
//               }
//    } yield server
//
//  private def ack(id: Long) =
//    for {
//      _ <- logger.info(s"message ack $id")
//      promOpt <- acks
//                  .get(id)
//                  .flatMap(
//                    _.fold(STM.succeed[Option[Promise[Error, Unit]]](None))(prom => acks.delete(id).as(Some(prom)))
//                  )
//                  .commit
//      _ <- promOpt.fold(ZIO.unit)(_.succeed(()).unit)
//    } yield ()
//
//  private def addMember(member: Member, send: ChannelOut) =
//    gossipStateRef.update(_.addMember(member)) *>
//      nodeChannels.update(_ + (member.nodeId -> send)) *>
//      propagateEvent(MembershipEvent.Join(member)) *>
//      logger.info("add member: " + member)
//
//  private def connect(
//    addr: SocketAddress
//  ) =
//    for {
//      connectionInit <- Promise.make[Error, (Member, ChannelOut)]
//      _ <- transport
//            .connect(addr)
//            .use { channel =>
//              logger.info(s"Initiating handshake with node at ${addr}") *>
//                expects(channel) {
//                  case Join(remoteState, remoteMember) =>
//                    (for {
//                      state    <- gossipStateRef.get
//                      newState = state.merge(remoteState)
//                      _        <- addMember(remoteMember, channel)
//                      _        <- updateState(newState)
//                    } yield ()) *> connectionInit.succeed((remoteMember, channel)) *> listenOnChannel(
//                      channel,
//                      remoteMember
//                    )
//                }
//            }
//            .mapError(HandshakeError(addr, _))
//            .catchAll(
//              ex =>
//                connectionInit.fail(ex) *>
//                  logger.error(s"Failed initiating connection with node [ ${addr} ]: $ex")
//            )
//            .fork
//      _ <- connectionInit.await
//    } yield ()
//
//  private def connectToSeeds(seeds: Set[SocketAddress]) =
//    for {
//      _            <- ZIO.foreach(seeds)(seed => connect(seed).ignore)
//      currentNodes <- nodeChannels.get
//      currentState <- gossipStateRef.get
//      _ <- ZIO.foreach(currentNodes.values)(
//            channel => sendInternalMessage(channel, JoinCluster(currentState, localMember_))
//          )
//    } yield ()
//
//  private def expects[R, A](
//    channel: ChannelOut
//  )(pf: PartialFunction[InternalProtocol, ZIO[R, Error, A]]): ZIO[R, Error, A] =
//    for {
//      bytes  <- readMessage(channel)
//      msg    <- InternalProtocol.deserialize(bytes._2.payload)
//      result <- pf.lift(msg).getOrElse(ZIO.fail(UnexpectedMessage(bytes._2)))
//    } yield result
//
//  private def handleClusterMessages(stream: Stream[Nothing, Message]) =
//    stream.tap { message =>
//      (for {
//        payload <- InternalProtocol.deserialize(message.payload)
//        _       <- logger.info(s"receive message: $payload")
//        _ <- payload match {
//              case Ack(ackId, state) =>
//                updateState(state) *>
//                  ack(ackId)
//              case Ping(ackId, state) =>
//                for {
//                  _     <- updateState(state)
//                  state <- gossipStateRef.get
//                  _     <- sendInternalMessage(message.sender, Ack(ackId, state))
//                } yield ()
//              case PingReq(target, originalAckId, state) =>
//                for {
//                  _     <- updateState(state)
//                  state <- gossipStateRef.get
//                  _ <- sendInternalMessageWithAck(target.nodeId, 5.seconds)(ackId => Ping(ackId, state))
//                        .foldM(
//                          _ => ZIO.unit,
//                          _ =>
//                            gossipStateRef.get
//                              .flatMap(state => sendInternalMessage(message.sender, Ack(originalAckId, state)))
//                        )
//                        .fork
//                } yield ()
//              case _ => logger.error("unknown message: " + payload)
//            }
//      } yield ())
//        .catchAll(
//          ex =>
//            //we should probably reconnect to the sender.
//            logger.error(s"Exception $ex processing cluster message $message")
//        )
//    }.runDrain
//
//  private def listenOnChannel(
//    channel: ChannelOut,
//    partner: Member
//  ): ZIO[Transport with Logging[String] with Clock, Error, Unit] = {
//
//    def handleSends(messages: Stream[Nothing, Chunk[Byte]]) =
//      messages.tap { bytes =>
//        channel
//          .send(bytes)
//          .catchAll(ex => ZIO.fail(SendError(partner.nodeId, bytes, ex)))
//      }.runDrain
//
//    (for {
//      _           <- logger.info(s"Setting up connection with [ ${partner.nodeId} ]")
//      broadcasted <- subscribeToBroadcast
//      _           <- handleSends(broadcasted).fork
//      _           <- routeMessages(channel, clusterMessageQueue, userMessageQueue)
//    } yield ())
//  }
//
//  private def routeMessages(
//    channel: ChannelOut,
//    clusterMessageQueue: Queue[Message],
//    userMessageQueue: Queue[Message]
//  ) = {
//    val loop = readMessage(channel)
//      .flatMap {
//        case (msgType, msg) =>
//          if (msgType == 1) {
//            clusterMessageQueue.offer(msg).unit
//          } else if (msgType == 2) {
//            userMessageQueue.offer(msg).unit
//          } else {
//            //this should be dead letter
//            logger.error("unsupported message type")
//          }
//      }
//      .catchAll { ex =>
//        logger.error(s"read message error: $ex")
//      }
//    loop.repeat(Schedule.doWhileM(_ => channel.isOpen.catchAll[Any, Nothing, Boolean](_ => ZIO.succeed(false))))
//  }
//
//  private def runSwim =
//    Ref.make(0).flatMap { roundRobinOffset =>
//      val loop = gossipStateRef.get.map(_.members.filterNot(_ == localMember_).toIndexedSeq).flatMap { nodes =>
//        if (nodes.nonEmpty) {
//          for {
//            next   <- roundRobinOffset.update(old => if (old < nodes.size - 1) old + 1 else 0)
//            state  <- gossipStateRef.get
//            target = nodes(next) // todo: insert in random position and keep going in round robin version
//            _ <- sendInternalMessageWithAck(target.nodeId, 10.seconds)(ackId => Ping(ackId, state))
//                  .foldM(
//                    _ => // attempt req messages
//                    {
//                      val nodesWithoutTarget = nodes.filter(_ != target)
//                      for {
//                        _ <- propagateEvent(MembershipEvent.Unreachable(target))
//                        jumps <- ZIO.collectAll(
//                                  List.fill(Math.min(3, nodesWithoutTarget.size))(
//                                    zio.random.nextInt(nodesWithoutTarget.size).map(nodesWithoutTarget(_))
//                                  )
//                                )
//                        pingReqs = jumps.map { jump =>
//                          sendInternalMessageWithAck(jump.nodeId, 5.seconds)(
//                            ackId => PingReq(target, ackId, state)
//                          )
//                        }
//
//                        _ <- if (pingReqs.nonEmpty) {
//                              ZIO
//                                .raceAll(pingReqs.head, pingReqs.tail)
//                                .foldM(
//                                  _ => removeMember(target),
//                                  _ =>
//                                    logger.info(
//                                      s"Successful ping req to [ ${target.nodeId} ] through [ ${jumps.map(_.nodeId).mkString(", ")} ]"
//                                    )
//                                )
//                            } else {
//                              logger.info(s"Ack failed timeout") *>
//                                removeMember(target)
//                            }
//                      } yield ()
//                    },
//                    _ => logger.info(s"Successful ping to [ ${target.nodeId} ]")
//                  )
//          } yield ()
//        } else {
//          logger.info("No nodes to spread gossip to")
//        }
//      }
//      loop.repeat(Schedule.spaced(10.seconds))
//    }
//
//  private def removeMember(member: Member) =
//    gossipStateRef.update(_.removeMember(member)) *>
//      nodeChannels.modify(old => (old.get(member.nodeId), old - member.nodeId)).flatMap {
//        case Some(channel) =>
//          channel.close.ignore *>
//            logger.info("channel closed for member: " + member)
//        case None =>
//          ZIO.unit
//      } *>
//      propagateEvent(MembershipEvent.Leave(member)) *>
//      logger.info("remove member: " + member)
//
//  private def propagateEvent(event: MembershipEvent) =
//    clusterEventsQueue.offer(event)
//
//  private def sendInternalMessageWithAck(to: NodeId, timeout: Duration)(fn: Long => InternalProtocol) =
//    for {
//      offset <- msgOffset.update(_ + 1)
//      prom   <- Promise.make[Error, Unit]
//      _      <- acks.put(offset, prom).commit
//      msg    = fn(offset)
//      _      <- sendInternalMessage(to, fn(offset))
//      _ <- prom.await
//            .ensuring(acks.delete(offset).commit)
//            .timeoutFail(AckMessageFail(offset, msg, to))(timeout)
//    } yield ()
//
//  private def sendInternalMessage(to: NodeId, msg: InternalProtocol): ZIO[Logging[String], Error, Unit] =
//    for {
//      node <- nodeChannels.get.map(_.get(to))
//      _ <- node match {
//            case Some(channel) =>
//              sendInternalMessage(channel, msg)
//            case None => ZIO.fail(UnknownNode(to))
//          }
//    } yield ()
//
//  private def sendInternalMessage(to: ChannelOut, msg: InternalProtocol): ZIO[Logging[String], Error, Unit] = {
//    for {
//      _       <- logger.info(s"sending $msg")
//      payload <- msg.serialize
//      msg     <- serializeMessage(localMember_, payload, 1)
//      _       <- to.send(msg)
//    } yield ()
//  }.catchAll { ex =>
//    logger.info(s"error during sending message: $ex")
//  }
//
//  private def updateState(newState: GossipState[A]): ZIO[Transport[A] with Logging[String] with Clock, Error, Unit] =
//    for {
//      current <- gossipStateRef.get
//      diff    = newState.diff(current)
//      _       <- ZIO.foreach(diff.local)(n => (n.addr.socketAddress >>= connect).ignore)
//    } yield ()
//
//}
//
//object SWIM {
//
//  def withSWIM[A](port: Int) =
//    enrichWithManaged[Membership[A]](join[A](port))
//
//  def join[A](
//    port: Int
//  ): ZManaged[Logging[String] with Clock with Random with Transport[A] with Discovery, Error, Membership[A]] =
//    for {
//      localHost            <- InetAddress.localHost.toManaged_.orDie
//      localMember          = Member(NodeId.generateNew, NodeAddress(localHost.address, port))
//      _                    <- logger.info(s"Starting node [ ${localMember.nodeId} ]").toManaged_
//      nodes                <- zio.Ref.make(Map.empty[NodeId, ChannelOut]).toManaged_
//      seeds                <- discovery.discoverNodes.toManaged_
//      _                    <- logger.info("seeds: " + seeds).toManaged_
//      userMessagesQueue    <- ZManaged.make(zio.Queue.bounded[Message](1000))(_.shutdown)
//      clusterEventsQueue   <- ZManaged.make(zio.Queue.sliding[MembershipEvent](100))(_.shutdown)
//      clusterMessagesQueue <- ZManaged.make(zio.Queue.bounded[Message](1000))(_.shutdown)
//      gossipState          <- Ref.make(GossipState(SortedSet(localMember))).toManaged_
//      broadcastQueue       <- ZManaged.make(zio.Queue.bounded[Chunk[Byte]](1000))(_.shutdown)
//      subscriberBroadcast <- ZStream
//                              .fromQueue(broadcastQueue)
//                              .distributedWithDynamic[Nothing, Chunk[Byte]](
//                                32,
//                                _ => ZIO.succeed(_ => true),
//                                _ => ZIO.unit
//                              )
//                              .map(_.map(_._2))
//                              .map(_.map(ZStream.fromQueue(_).unTake))
//      msgOffSet <- Ref.make(0L).toManaged_
//      ackMap    <- TMap.empty[Long, Promise[Error, Unit]].commit.toManaged_
//
//      swimMembership = new SWIM(
//        localMember_ = localMember,
//        nodeChannels = nodes,
//        gossipStateRef = gossipState,
//        userMessageQueue = userMessagesQueue,
//        clusterMessageQueue = clusterMessagesQueue,
//        clusterEventsQueue = clusterEventsQueue,
//        subscribeToBroadcast = subscriberBroadcast,
//        publishToBroadcast = (c: Chunk[Byte]) => broadcastQueue.offer(c).unit,
//        msgOffset = msgOffSet,
//        acks = ackMap
//      )
//
//      _ <- logger.info("Connecting to seed nodes: " + seeds).toManaged_
//      _ <- swimMembership.connectToSeeds(seeds).toManaged_
//      _ <- logger.info("Beginning to accept connections").toManaged_
//      _ <- swimMembership.acceptConnectionRequests
//            .use(channel => ZIO.never.ensuring(channel.close.ignore))
//            .toManaged_
//            .fork
//      _ <- logger.info("Starting SWIM membership protocol").toManaged_
//      _ <- swimMembership.runSwim.fork.toManaged_
//    } yield new Membership[A] {
//      override def membership: Membership.Service[Any, A] = swimMembership
//    }
//
//}