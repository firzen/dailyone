package com.firzen.dailyone

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import java.net.URI

/** A local HTTPS DAV fixture with the Nutstore response quirks observed on-device. */
class LocalDavServer : java.io.Closeable {
    val stalled = java.util.concurrent.atomic.AtomicInteger()
    val stallNext = java.util.concurrent.atomic.AtomicBoolean(false)
    private val files = linkedMapOf<String, ByteArray>()
    private val directories = mutableSetOf<String>()
    private val server = MockWebServer()
    val config = CloudConfig("https://dav.jianguoyun.com/dav/", "fixture", "fixture")
    val client: OkHttpClient
    init {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(files) {
                if (stallNext.compareAndSet(true, false)) { stalled.incrementAndGet(); return@synchronized MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
                val path = URI(request.path!!).path
                fun response(code: Int) = MockResponse().setResponseCode(code)
                fun etag(data: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
                when (request.method) {
                    "MKCOL" -> response(if (directories.add(path)) 201 else 405)
                    "PROPFIND" -> {
                        val root = path.trimEnd('/') + "/"
                        val children = files.filterKeys { it.startsWith(root) && !it.removePrefix(root).contains('/') }
                        val xml = children.map { (name, data) -> "<d:response><d:href>$name</d:href><d:propstat><d:prop><d:getetag>${etag(data)}</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" }.joinToString("")
                        response(207).setBody("<d:multistatus xmlns:d=\"DAV:\">$xml</d:multistatus>")
                    }
                    "GET" -> files[path]?.let { response(200).setHeader("ETag", etag(it)).setBody(Buffer().write(it)) } ?: response(404)
                    "PUT" -> {
                        val current = files[path]
                        val condition = request.getHeader("If-Match")
                        if (condition != null && (current == null || condition != etag(current))) response(412)
                        else { files[path] = request.body.readByteArray(); response(if (current == null) 201 else 204) }
                    }
                    "MOVE" -> {
                        val destination = URI(request.getHeader("Destination")!!).path
                        if (request.getHeader("Overwrite") == "F" && files.containsKey(destination)) response(409)
                        else files.remove(path)?.let { files[destination] = it; response(201) } ?: response(404)
                    }
                    "DELETE" -> { files.keys.removeAll { it == path || it.startsWith(path.trimEnd('/') + "/") }; directories.remove(path); response(204) }
                    else -> response(405)
                }
            }
        }
        server.start()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .followRedirects(false).addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(chain.request().url.newBuilder().host("localhost").port(server.port).build()).build())
            }.build()
    }
    override fun close() { server.shutdown() }
}
