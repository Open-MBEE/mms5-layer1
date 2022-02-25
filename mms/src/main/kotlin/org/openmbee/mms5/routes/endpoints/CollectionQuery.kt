package org.openmbee.mms5.routes.endpoints

import io.ktor.application.*
import io.ktor.routing.*
import org.openmbee.mms5.Permission
import org.openmbee.mms5.mmsL1
import org.openmbee.mms5.sanitizeUserQuery


fun Route.queryCollection() {
    post("/orgs/{orgId}/collections/{collectionId}/query/{inspect?}") {
        call.mmsL1(Permission.READ_COLLECTION) {
            pathParams {
                org()
                collection()
            }

            val (rewriter, outputQuery) = sanitizeUserQuery(requestBody)

            outputQuery.apply {
                // set default graph
                graphURIs.clear()
                // graphURIs.addAll()
            }
        }

    }
}
