package org.openmbee.mms5.util

import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.net.ServerSocket
import java.net.URLClassLoader

/**
 * Fuseki SPARQL backend. Runs fuseki-server JAR in an isolated ClassLoader using an in-memory dataset.
 */
class FusekiBackend : SparqlBackend {

    companion object {
        private val datasetGraphInMemoryConstructor: Constructor<*>
        private val makeFusekiMethod: Method
        private val startFusekiMethod: Method
        private val stopFusekiMethod: Method

        init {
            val fusekiJarUrls = (File("build" + File.separator + "test-fuseki-server").listFiles() ?: arrayOf<File>())
                .filter { it.extension.lowercase() == "jar" }
                .map { it.toURI().toURL() }
                .toTypedArray()
            if (fusekiJarUrls.isEmpty()) {
                throw IllegalStateException("No fuseki-server JAR Found; run gradle build to initialize")
            }
            val bootstrapClassLoader = ClassLoader.getSystemClassLoader().parent
            val fusekiClassLoader = URLClassLoader(fusekiJarUrls, bootstrapClassLoader)

            val fusekiServerClass = fusekiClassLoader.loadClass("org.apache.jena.fuseki.main.FusekiServer")
            makeFusekiMethod = fusekiServerClass.getMethod("make", Integer.TYPE, fusekiClassLoader.loadClass("java.lang.String"), fusekiClassLoader.loadClass("org.apache.jena.sparql.core.DatasetGraph"))
            startFusekiMethod = fusekiServerClass.getMethod("start")
            stopFusekiMethod = fusekiServerClass.getMethod("stop")
            datasetGraphInMemoryConstructor =
                fusekiClassLoader.loadClass("org.apache.jena.sparql.core.mem.DatasetGraphInMemory")
                    .getConstructor()
        }
    }

    /** Port to use for fuseki. Finds a random open port */
    private val fusekiPort = ServerSocket(0).use { it.localPort }

    /** FusekiServer object */
    private val dsg = datasetGraphInMemoryConstructor.newInstance() as org.apache.jena.sparql.core.DatasetGraph
    private val fusekiServer = makeFusekiMethod.invoke(null, fusekiPort, "/ds", dsg);

    override fun start() {
        startFusekiMethod.invoke(fusekiServer)
    }

    override fun stop() {
        stopFusekiMethod.invoke(fusekiServer)
    }

    override fun saveToFile() {
        dsg.stream()
        val testFile = File("/tmp/data.trig")
        val artifact = File("build" + File.separator + "test-fuseki-server")
    }

    override fun getQueryUrl(): String {
        return "http://localhost:${fusekiPort}/ds/query"
    }

    override fun getUpdateUrl(): String {
        return "http://localhost:${fusekiPort}/ds/update"
    }

    override fun getUploadUrl(): String {
        return "http://localhost:${fusekiPort}/ds/data"
    }

}