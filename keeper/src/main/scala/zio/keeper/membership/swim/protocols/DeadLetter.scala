package zio.keeper.membership.swim.protocols

import zio.Chunk
import zio.keeper.membership.swim.{Message, Protocol}
import zio.stream.ZStream
import zio.logging._

object DeadLetter {

  def protocol =
    Protocol[Chunk[Byte]].apply(
      { msg =>
        log(LogLevel.Error)("message [" + msg + "] in dead letter")
          .as(Message.NoResponse)
      },
      ZStream.empty
    )

}
