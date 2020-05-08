package zio.keeper.membership.swim

import zio._
import zio.clock.Clock
import zio.keeper.ClusterError.UnknownNode
import zio.keeper.membership.MembershipEvent
import zio.keeper.membership.MembershipEvent.{ Join, Leave }
import zio.keeper.membership.swim.Nodes.{ NodeState, NodeStateChanged }
import zio.keeper.{ Error, NodeAddress }
import zio.logging._
import zio.stm.TMap
import zio.stream._

/**
 * Nodes maintains state of the cluster.
 *
 * @param nodeStates - states
 * @param roundRobinOffset - offset for round-robin
 */
final class Nodes(
  nodeStates: TMap[NodeAddress, NodeState],
  roundRobinOffset: Ref[Int],
  eventsQueue: Queue[MembershipEvent],
  internalEventsQueue: Queue[NodeStateChanged]
) {

  def addNode(node: NodeAddress) =
    nodeStates
      .put(node, NodeState.Init)
      .whenM(nodeStates.contains(node).map(!_))
      .commit
      .unit

  /**
   * Changes node state and issue membership event.
   * @param id - member id
   * @param newState - new state
   */
  def changeNodeState(id: NodeAddress, newState: NodeState): ZIO[Logging, Error, Unit] =
    nodeState(id)
      .flatMap { prev =>
        ZIO.when(prev != newState) {
          log.info(s"changing node[$id] status from: [$prev] to: [$newState]") *>
            nodeStates
              .put(id, newState)
              .commit
              .tap(
                _ => {
                  if (newState == NodeState.Healthy && prev == NodeState.Init) {
                    eventsQueue.offer(Join(id))
                  } else if (newState == NodeState.Death || newState == NodeState.Left) {
                    eventsQueue.offer(Leave(id))
                  } else {
                    ZIO.unit
                  } *> internalEventsQueue.offer(NodeStateChanged(id, prev, newState)).unit
                }
              )
        }
      }

  /**
   * close connection and remove Node from cluster.
   * @param id node id
   */
  def disconnect(id: NodeAddress): IO[Error, Unit] =
    nodeStates.delete(id).commit

  /**
   *  Stream of Membership Events
   */
  def events: Stream[Nothing, MembershipEvent] =
    ZStream.fromQueue(eventsQueue)

  /**
   *  Stream of Membership Events for internal purpose.
   *  This exists only because I cannot find the way to duplicates events from one queue
   */
  val internalEvents: Stream[Nothing, NodeStateChanged] =
    ZStream.fromQueue(internalEventsQueue)

  /**
   * Returns next Healthy node.
   */
  val next: UIO[Option[NodeAddress]] /*(exclude: List[NodeId] = Nil)*/ =
    for {
      list      <- healthyNodes
      nextIndex <- roundRobinOffset.updateAndGet(old => if (old < list.size - 1) old + 1 else 0)
      _         <- nodeStates.removeIf((_, v) => v == NodeState.Death).when(nextIndex == 0).commit
    } yield list.drop(nextIndex).headOption.map(_._1)

  /**
   * Node state for given NodeId.
   */
  def nodeState(id: NodeAddress): IO[Error, NodeState] =
    nodeStates.get(id).commit.get.orElseFail(UnknownNode(id))

  def numberOfNodes =
    nodeStates.keys.map(_.size).commit

  /**
   * Lists members that are in healthy state.
   */
  def healthyNodes: UIO[List[(NodeAddress, NodeState)]] =
    nodeStates.toList.map(_.filter(_._2 == NodeState.Healthy)).commit

  /**
   * Returns string with cluster state.
   */
  val prettyPrint: UIO[String] =
    nodeStates.toList.commit.map(
      nodes =>
        "[ size: " + nodes.size +
          " nodes: [" +
          nodes
            .map {
              case (address, nodeState) =>
                "address: " + address + " state: " + nodeState
            }
            .mkString("|") +
          "]]"
    )

}

object Nodes {

  sealed trait NodeState

  object NodeState {
    case object Init        extends NodeState
    case object Healthy     extends NodeState
    case object Unreachable extends NodeState
    case object Suspicion   extends NodeState
    case object Death       extends NodeState
    case object Left        extends NodeState
  }

  final case class NodeStateChanged(node: NodeAddress, oldState: NodeState, newState: NodeState)

  def make: ZIO[Logging with Clock, Nothing, Nodes] =
    for {
      nodeStates       <- TMap.empty[NodeAddress, NodeState].commit
      events           <- Queue.sliding[MembershipEvent](100)
      internalEvents   <- Queue.sliding[NodeStateChanged](100)
      roundRobinOffset <- Ref.make(0)
    } yield new Nodes(
      nodeStates,
      roundRobinOffset,
      events,
      internalEvents
    )

}
