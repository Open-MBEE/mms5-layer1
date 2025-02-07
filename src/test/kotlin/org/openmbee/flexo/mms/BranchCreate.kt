package org.openmbee.flexo.mms

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.http.*
import kotlinx.coroutines.delay
import org.apache.jena.rdf.model.ResourceFactory
import org.openmbee.flexo.mms.util.*
import java.util.*

class BranchCreate : RefAny() {
    init {
        "reject invalid branch id".config(tags=setOf(NoAuth)) {
            withTest {
                httpPut("/orgs/$demoOrgId/repos/$demoRepoId/branches/bad branch id") {
                    setTurtleBody(withAllTestPrefixes(validBranchBodyFromMaster))
                }.apply {
                    response shouldHaveStatus HttpStatusCode.BadRequest
                }
            }
        }

        mapOf(
            "rdf:type" to "mms:NotBranch",
            "mms:id" to "\"not-$demoBranchId\"",
            "mms:etag" to "\"${UUID.randomUUID()}\"",
            "mms:ref" to "<./nosuchbranch>"
        ).forEach { (pred, obj) ->
            "reject wrong $pred".config(tags=setOf(NoAuth)) {
                withTest {
                    httpPut(demoBranchPath) {
                        var ref = "<> mms:ref <./master> ."
                        if (pred == "mms:ref")
                            ref = ""
                        setTurtleBody(withAllTestPrefixes("""
                            <> dct:title "$demoBranchName"@en .
                            <> $pred $obj .
                            $ref
                        """.trimIndent()))
                    }.apply {
                        response shouldHaveStatus HttpStatusCode.BadRequest
                    }
                }
            }
        }

        "create branch from master after a commit to master" {
            val update = commitModel(masterBranchPath, """
                insert data {
                    <mms:urn:s> <mms:urn:p> 5 . 
                }
            """.trimIndent())

            val commit = update.response.headers[HttpHeaders.ETag]

            withTest {
                httpPut(demoBranchPath) {
                    setTurtleBody(withAllTestPrefixes(validBranchBodyFromMaster))
                }.apply {
                    validateCreateBranchResponse(commit!!)
                }
            }
        }

        "create branch from empty master" {
            withTest {
                httpPut(demoBranchPath) {
                    setTurtleBody(withAllTestPrefixes(validBranchBodyFromMaster))
                }.apply {
                    validateCreateBranchResponse(repoEtag)
                }
            }
        }

        "insert, replace x 4, branch on 2nd" {
            val init = commitModel(masterBranchPath, """
                insert data {
                    <urn:mms:s> <urn:mms:p> 1 . 
                }
            """.trimIndent())

            val initCommitId = init.response.headers[HttpHeaders.ETag]

            val commitIds = mutableListOf<String>();

            suspend fun replaceCounterValue(value: Int): String {
                val update = commitModel(masterBranchPath, """
                    delete where {
                        <urn:mms:s> <urn:mms:p> ?previous .
                    } ;
                    insert data {
                        <urn:mms:s> <urn:mms:p> $value . 
                    }
                """.trimIndent()
                )

                val commitId = update.response.headers[HttpHeaders.ETag]!!

                commitIds.add(commitId)

                // wait for interim lock to be deleted
                delay(2_000L)

                return commitId;
            }

            val restoredValue = 2
            val restoreCommitId = replaceCounterValue(restoredValue)

            for(index in 3..5) {
                replaceCounterValue(index)
            }

            withTest {
                // create branch and validate
                httpPut(demoBranchPath) {
                    setTurtleBody(withAllTestPrefixes("""
                        ${title(demoBranchName)}
                        <> mms:commit mor-commit:$restoreCommitId .
                    """.trimIndent()))
                }.apply {
                    validateCreateBranchResponse(restoreCommitId)
                }

                // assert the resultant model is in the correct state
                val refPath = "$demoBranchPath/graph"
                httpGet(refPath) {
                    addHeader(HttpHeaders.Accept, RdfContentTypes.Turtle.toString())
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    val model = KModel()
                    parseTurtle(response.content!!, model, demoBranchPath)

                    val s = ResourceFactory.createResource("urn:mms:s")
                    val p = ResourceFactory.createProperty("urn:mms:p")
                    val values = model.listObjectsOfProperty(s, p).toList()

                    values shouldHaveSize 1
                    values[0].asLiteral().string shouldBe "$restoredValue"
                }
            }
        }

        "model load x 3, branch on 2" {
            val load1 = loadModel(masterBranchPath, """
                <urn:mms:s> <urn:mms:p> 1 .
            """.trimIndent())

            delay(500L)

            val load2 = loadModel(masterBranchPath, """
                <urn:mms:s> <urn:mms:p> 2 .
            """.trimIndent())

            val commitId2 = load2.response.headers[HttpHeaders.ETag]!!

            delay(500L)

            val load3 = loadModel(masterBranchPath, """
                <urn:mms:s> <urn:mms:p> 3 .
            """.trimIndent())

            delay(500L)

            withTest {
                httpPut(demoBranchPath) {
                    setTurtleBody(withAllTestPrefixes("""
                        ${title(demoBranchName)}
                        <> mms:commit mor-commit:${commitId2} .
                    """.trimIndent()))
                }.apply {
                    validateCreateBranchResponse(commitId2)
                }


                val refPath = "$demoBranchPath/graph"
                httpGet(refPath) {
                    addHeader(HttpHeaders.Accept, RdfContentTypes.Turtle.toString())
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    val model = KModel()
                    parseTurtle(response.content!!, model, demoBranchPath)

                    val s = ResourceFactory.createResource("urn:mms:s")
                    val p = ResourceFactory.createProperty("urn:mms:p")
                    val values = model.listObjectsOfProperty(s, p).toList()

                    values shouldHaveSize 1
                    values[0].asLiteral().string shouldBe "2"
                }
            }
        }
    }
}
