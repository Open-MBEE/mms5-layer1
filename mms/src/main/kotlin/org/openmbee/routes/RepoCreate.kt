package org.openmbee.routes

import io.ktor.application.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.openmbee.*


private val SPARQL_CONSTRUCT_TRANSACTION: (conditions: ConditionsGroup)->String = { """
    construct  {
        
        ?thing ?thing_p ?thing_o .
        
        ?m_s ?m_p ?m_o .
        
    } where {
        {
  
            graph m-graph:Cluster {
                mor: a mms:Repo ;
                    ?mor_p ?mor_o ;
                    .
    
                optional {
                    ?thing mms:repo mor: ; 
                        ?thing_p ?thing_o .
                }
            }
    
            graph m-graph:AccessControl.Policies {
                ?policy mms:scope mor: ;
                    ?policy_p ?policy_o .
            }
        
            graph mor-graph:Metadata {
                ?m_s ?m_p ?m_o .
            }
        } union ${it.unionInspectPatterns()}
    }
"""}


private val DEFAULT_CONDITIONS = ORG_CRUD_CONDITIONS.append {
    permit(Permission.CREATE_REPO, Scope.REPO)

    require("repoNotExists") {
        handler = { prefixes -> "The provided repo <${prefixes["mor"]}> already exists." }

        """
            # repo must not yet exist
            graph m-graph:Cluster {
                filter not exists {
                    mor: a mms:Repo .
                }
            }
        """
    }

    require("repoMetadataGraphEmpty") {
        handler = { prefixes -> "The Metadata graph <${prefixes["mor-graph"]}Metadata> is not empty." }

        """
            # repo metadata graph must be empty
            graph mor-graph:Metadata {
                filter not exists {
                    ?e_s ?e_p ?e_o .
                }
            }
        """
    }
}

fun String.normalizeIndentation(spaces: Int=0): String {
    return this.trimIndent().prependIndent(" ".repeat(spaces)).replace("^\\s+".toRegex(), "")
}

@OptIn(InternalAPI::class)
fun Application.createRepo() {
    routing {
        put("/orgs/{orgId}/repos/{repoId}") {
            call.crud {
                branchId = "main"

                pathParams {
                    org()
                    repo(legal = true)
                }

                val repoTriples = filterIncomingStatements("mor") {
                    repoNode().apply {
                        sanitizeCrudObject()

                        addProperty(RDF.type, MMS.Repo)
                        addProperty(MMS.id, repoId)
                        addProperty(MMS.org, orgNode())
                    }
                }

                val localConditions = DEFAULT_CONDITIONS

                val updateString = buildSparqlUpdate {
                    insert {
                        txn {
                            autoPolicy(Scope.REPO, Role.ADMIN_REPO)
                        }

                        graph("m-graph:Cluster") {
                            raw(repoTriples)
                        }

                        graph("mor-graph:Metadata") {
                            raw(
                                """
                                morc: a mms:Commit ;
                                    mms:parent rdf:nil ;
                                    mms:submitted ?_now ;
                                    mms:message ?_commitMessage ;
                                    mms:data morc-data: ;
                                    .
                        
                                morc-data: a mms:Load ;
                                    .
    
                                morb: a mms:Branch ;
                                    mms:id ?_branchId ;
                                    mms:commit morc: ;
                                    .
                                
                                ?_model a mms:Model ;
                                    mms:ref morb: ;
                                    mms:graph ?_modelGraph ;
                                    .
                                    
                                ?_staging a mms:Staging ;
                                    mms:ref morb: ;
                                    mms:graph ?_stagingGraph ;
                                    .
                            """
                            )
                        }
                    }
                    where {
                        raw(*localConditions.requiredPatterns())
                    }
                }

                executeSparqlUpdate(updateString) {
                    iri(
                        "_model" to "${prefixes["mor-snapshot"]}Model.${transactionId}",
                        "_modelGraph" to "${prefixes["mor-graph"]}Model.${transactionId}",
                        "_staging" to "${prefixes["mor-snapshot"]}Staging.${transactionId}",
                        "_stagingGraph" to "${prefixes["mor-graph"]}Staging.${transactionId}",
                    )
                }

                val constructString = buildSparqlQuery {
                    construct {
                        txn()

                        raw(
                            """
                            mor: ?mor_p ?mor_o .
                            
                            ?thing ?thing_p ?thing_o .
                        """
                        )
                    }
                    where {
                        group {
                            txn()

                            raw(
                                """
                                graph m-graph:Cluster {
                                    mor: a mms:Repo ;
                                        ?mor_p ?mor_o .
                                           
                                    optional {
                                        ?thing mms:repo mor: ; 
                                            ?thing_p ?thing_o .
                                    }
                                }
                                
                                graph mor-graph:Metadata {
                                    ?m_s ?m_p ?m_o .
                                }
                            """
                            )
                        }
                        raw("""union ${localConditions.unionInspectPatterns()}""")
                    }
                }

                val constructResponseText = executeSparqlConstructOrDescribe(constructString)

                validateTransaction(constructResponseText, localConditions)

                // respond
                call.respondText(constructResponseText, contentType = RdfContentTypes.Turtle)

                // delete transaction graph
                run {
                    // prepare SPARQL DROP
                    val dropResponseText = executeSparqlUpdate("""
                        delete where {
                            graph m-graph:Transactions {
                                mt: ?p ?o .
                            }
                        }
                    """)

                    // log response
                    log.info(dropResponseText)
                }
            }
        }
    }
}