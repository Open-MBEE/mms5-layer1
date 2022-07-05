package org.openmbee.mms5

import io.kotest.assertions.ktor.shouldHaveStatus
import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.http.*
import io.ktor.response.*
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.apache.jena.vocabulary.XSD
import org.openmbee.mms5.util.*
import java.util.*

class BranchCreate : BranchAny() {
    init {
        "reject invalid branch id" {
            withTest {
                httpPut("/orgs/$orgId/repos/$repoId/branches/bad branch id") {
                    setTurtleBody(validBranchBodyFromMaster)
                }.apply {
                    response shouldHaveStatus HttpStatusCode.BadRequest
                }
            }
        }

        mapOf(
            "rdf:type" to "mms:NotBranch",
            "mms:id" to "\"not-$branchId\"",
            "mms:etag" to "\"${UUID.randomUUID()}\"",
            "mms:ref" to "<./nosuchbranch>"
        ).forEach { (pred, obj) ->
            "reject wrong $pred" {
                withTest {
                    httpPut(branchPath) {
                        setTurtleBody("""
                            <> dct:title "$branchName"@en .
                            <> $pred $obj .
                        """.trimIndent())
                    }.apply {
                        response shouldHaveStatus HttpStatusCode.BadRequest
                    }
                }
            }
        }

        "create branch from master after a commit to master" {
            val update = updateModel("""
                insert { <http:somesub> <http:somepred> 5 .}
            """.trimIndent(), "master", repoId, orgId)
            val commit = update.response.headers[HttpHeaders.ETag]
            withTest {
                httpPut(branchPath) {
                    setTurtleBody(
                        """
                        $validBranchBodyFromMaster
                    """.trimIndent()
                    )
                }.apply {
                    validateCreateBranchResponse(commit!!)
                }
            }
        }
        "create branch from empty master" {
            withTest {
                httpPut(branchPath) {
                    setTurtleBody(
                        """
                        $validBranchBodyFromMaster
                    """.trimIndent()
                    )
                }.apply {
                    validateCreateBranchResponse(repoEtag)
                }
            }
        }
    }
}
