package zio.keeper.membership.swim.protocols

import upickle.default.{ readBinary, writeBinary }
import zio.keeper.{ ByteCodec, TaggedCodec }
import zio.keeper.SerializationError._
import zio.keeper.membership.swim.{ Message, Protocol }
import zio.stream._
import zio.{ Chunk, IO, ZIO }

final case class User[A](msg: A) extends AnyVal

object User {

  implicit def taggedRequests[A](
    implicit
    u: ByteCodec[User[A]]
  ): TaggedCodec[User[A]] =
    TaggedCodec.instance(
      { _: User[_] =>
        101
      }, {
        case 101 => u.asInstanceOf[ByteCodec[User[A]]]
      }
    )

  implicit def codec[A: TaggedCodec]: ByteCodec[User[A]] =
    new ByteCodec[User[A]] {

      override def fromChunk(chunk: Chunk[Byte]): IO[DeserializationTypeError, User[A]] =
        ZIO
          .effect(readBinary[Array[Byte]](chunk.toArray))
          .mapError(DeserializationTypeError(_))
          .flatMap { b1 =>
            TaggedCodec.read[A](Chunk.fromArray(b1))
          }
          .map(ab => User(ab))

      override def toChunk(a: User[A]): IO[SerializationTypeError, Chunk[Byte]] =
        TaggedCodec
          .write[A](a.msg)
          .flatMap { ch1 =>
            ZIO
              .effect(
                Chunk.fromArray(
                  writeBinary[Array[Byte]](ch1.toArray)
                )
              )
              .mapError(SerializationTypeError(_))
          }
    }

  def protocol[B: TaggedCodec](
    userIn: zio.Queue[Message.Direct[B]],
    userOut: zio.Queue[Message.Direct[B]]
  ) =
    Protocol[User[B]].make(
      msg => Message.direct(msg.node, msg.message.msg).flatMap(userIn.offer(_)).as(Message.NoResponse),
      ZStream
        .fromQueue(userOut)
        .collect {
          case Message.Direct(node, conversationId, msg) =>
            Message.Direct(node, conversationId, User(msg))
        }
    )

}
