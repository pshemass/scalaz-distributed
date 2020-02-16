package zio.keeper.membership.swim.protocols

import upickle.default._
import zio.keeper.SerializationError._
import zio.keeper.membership.swim.Protocol
import zio.keeper.{ByteCodec, TaggedCodec}
import zio.stream.ZStream
import zio.{Chunk, IO}


case class User[A](msg: A)

object User {

  implicit def taggedRequests[A, B](
                               implicit
                               u: ByteCodec[User[A]],
                             ): TaggedCodec[User[A]] =
    TaggedCodec.instance(
      {
        _: User[_] => 101
      }, {
        case 101 => u.asInstanceOf[ByteCodec[User[A]]]
      }
    )

  implicit def codec[A: TaggedCodec]: ByteCodec[User[A]] =
    new ByteCodec[User[A]] {
      override def fromChunk(chunk: Chunk[Byte]): IO[DeserializationTypeError, User[A]] =
        TaggedCodec.read[A](chunk).map(User(_))

      override def toChunk(a: User[A]): IO[SerializationTypeError, Chunk[Byte]] =
        TaggedCodec.write[A](a.msg)
    }


  def protocol[A: ReadWriter, B: TaggedCodec](
                                               userIn: zio.Queue[(A, B)],
                                               userOut: zio.Queue[(A, B)]
                                             ) =
    Protocol[A, User[B]].apply(
      (s, u: User[B]) => userIn.offer((s, u.msg)).as(None),
      ZStream.fromQueue(userOut)
        .map{
          case (recipient, msg) => (recipient, User(msg))
        }
    )

}
