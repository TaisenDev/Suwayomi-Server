package suwayomi.tachidesk.manga.controller

import io.javalin.http.HttpStatus
import suwayomi.tachidesk.graphql.mutations.BridgeMediaToken
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.util.handler
import suwayomi.tachidesk.server.util.pathParam

/** Serves source images through short-lived signed handles without PageTable or cache files. */
object BridgeController {
    val media =
        handler(
            pathParam<String>("token"),
            documentWith = {},
            behaviorOf = { ctx, token ->
                ctx.future {
                    future { BridgeMediaToken.fetch(token) }.thenApply { image ->
                        if (image == null) {
                            ctx.status(HttpStatus.NOT_FOUND)
                            ctx.result("not found")
                        } else {
                            ctx.header("content-type", image.contentType)
                            ctx.header("cache-control", "private, max-age=300")
                            ctx.header("x-content-type-options", "nosniff")
                            ctx.result(image.bytes)
                        }
                    }
                }
            },
            withResults = {
                image(HttpStatus.OK)
                httpCode(HttpStatus.NOT_FOUND)
            },
        )
}
