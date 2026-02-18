package org.scala.abusers.sls

import jsonrpclib.fs2.*
import jsonrpclib.CallId

private case class LSPCancelRequest(id: CallId)

object LSPCancelRequest {
  import io.circe.Codec
  given Codec[LSPCancelRequest] = Codec.derived[LSPCancelRequest]

  val cancelTemplate: CancelTemplate = CancelTemplate
    .make[LSPCancelRequest](
      "$/cancelRequest",
      _.id,
      LSPCancelRequest(_)
    )
}
