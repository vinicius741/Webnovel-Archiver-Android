package com.vinicius741.webnovelarchiver.simulation

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.vinicius741.webnovelarchiver.app.AppTime
import com.vinicius741.webnovelarchiver.source.network.CloudflarePageRenderer
import com.vinicius741.webnovelarchiver.source.network.CloudflareRenderFailure
import com.vinicius741.webnovelarchiver.source.network.CloudflareRenderOutcome
import com.vinicius741.webnovelarchiver.source.network.CloudflareRenderedPage
import com.vinicius741.webnovelarchiver.source.network.CloudflareRenderedPageValidator
import com.vinicius741.webnovelarchiver.source.network.CloudflareWebViewRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Base64

/** Terminal application interceptor: no request ever reaches DNS or a socket. */
internal class SimulationResponder(
    private val config: SimulationConfig,
    private val time: AppTime,
    private val journal: SimulationJournal,
    private val image: ByteArray,
    private val replay: SimulationReplay? = null,
) : Interceptor,
    CloudflarePageRenderer {
    private val stories = SimulationFixtures.forScenario(config.scenario)
    private val chapterUrls = stories.flatMap { it.chapters }.map { it.url }.toSet()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = request.body?.let { Buffer().also(it::writeTo).readUtf8() }
        val json = body?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }
        val stage = stage(request, json) ?: deny()
        val key = "$stage-${digest(request.url.toString() + body.orEmpty())}"
        val attempt = journal.nextAttempt(key)
        val operation = "$key-$attempt"
        journal.event("request_started", operation)
        return try {
            awaitResponse(if (stage == "fixture") "source" else stage, operation) { chain.call().isCanceled() }
            val response =
                when (stage) {
                    "fixture" -> fixtureResponse(request)
                    "source", "source-image" -> source(request, attempt)
                    else -> ai(request, stage, json, attempt)
                }
            journal.event("request_finished", operation, response.code.toString())
            response
        } catch (error: IOException) {
            journal.event("request_finished", operation, if (chain.call().isCanceled()) "cancelled" else error.javaClass.simpleName)
            throw error
        }
    }

    private fun stage(
        request: Request,
        body: JsonObject?,
    ): String? {
        if (replay?.match(request) != null) return "fixture"
        if (request.url.host == "openrouter.ai") {
            val path = request.url.encodedPath
            return when {
                request.method == "GET" && path in setOf("/api/v1/models", "/api/v1/images/models", "/api/v1/key") -> "catalog"
                request.method != "POST" || body?.get("model")?.isJsonPrimitive != true -> null
                path == "/api/alpha/decisions" && body.has("state") && body.has("questions") -> "decisions"
                path == "/api/v1/images" && body.get("prompt")?.asString?.isNotBlank() == true -> "image"
                path == "/api/v1/chat/completions" && body.get("messages")?.isJsonArray == true ->
                    if (body.getAsJsonArray("messages").any { message ->
                            message.asJsonObject
                                .get("content")
                                ?.asString
                                ?.startsWith("Write one image-generation prompt") == true
                        }
                    ) {
                        "prompt"
                    } else {
                        "description"
                    }
                else -> null
            }
        }
        if (request.method != "GET") return null
        if (request.url.toString() in chapterUrls || stories.any { it.sourceUrl == request.url.toString() }) return "source"
        if (request.url.toString() == "https://www.royalroad.com/covers/990001.png") return "source-image"
        return null
    }

    private fun deny(): Nothing {
        journal.event("denied", "unmatched_request")
        throw IOException("No simulation fixture matches this request; external networking is disabled")
    }

    private fun ai(
        request: Request,
        stage: String,
        body: JsonObject?,
        attempt: Int,
    ): Response {
        val fault = config.scenario.faults[stage]
        if (fault != null) journal.event("fault", stage, fault)
        if (fault == "timeout") throw SocketTimeoutException("Simulated timeout")
        val status = fault?.toIntOrNull() ?: if (fault == "retry" && attempt == 1) 429 else 200
        if (status != 200) return response(request, status, "{\"error\":{\"message\":\"Simulated provider error\"}}")
        val data =
            when (stage) {
                "catalog" -> catalog(request)
                "decisions" -> decisions(checkNotNull(body), fault)
                "image" ->
                    JsonObject().apply {
                        add(
                            "data",
                            JsonArray().apply {
                                add(
                                    JsonObject().apply {
                                        addProperty(
                                            "b64_json",
                                            if (fault ==
                                                "invalid"
                                            ) {
                                                "invalid base64"
                                            } else {
                                                Base64.getEncoder().encodeToString(image)
                                            },
                                        )
                                        addProperty("media_type", if (fault == "unsupported") "image/svg+xml" else "image/png")
                                    },
                                )
                            },
                        )
                    }
                else -> chat(stage, fault)
            }
        if (stage != "catalog") {
            data.addProperty("id", "simulation-${config.seed}-$stage-$attempt")
            data.addProperty("model", body?.get("model")?.asString)
            if (config.scenario.faults["receipt"] != "missing") {
                data.add(
                    "usage",
                    JsonParser.parseString("""{"prompt_tokens":120,"completion_tokens":24,"total_tokens":144,"cost":0.000042}"""),
                )
            }
        }
        return response(request, 200, data.toString())
    }

    private fun catalog(request: Request): JsonObject =
        if (request.url.encodedPath.endsWith("/key")) {
            JsonParser
                .parseString(
                    """{"data":{"usage":0.0123,"usage_daily":0.001,"usage_monthly":0.0123,"limit":5,"limit_remaining":4.9877}}""",
                ).asJsonObject
        } else {
            JsonObject().apply {
                add(
                    "data",
                    JsonParser.parseString(
                        """[
                        {"id":"simulation/text","name":"Simulation text","context_length":128000,
                         "pricing":{"prompt":"0.000001","completion":"0.000002"},"supported_parameters":["reasoning"],
                         "architecture":{"output_modalities":["text"]}},
                        {"id":"simulation/decisions","name":"Simulation decisions","architecture":{"output_modalities":["decisions"]}},
                        {"id":"simulation/image","name":"Simulation image","architecture":{"output_modalities":["image"]},
                         "supported_parameters":{"aspect_ratio":["2:3"],"output_format":["png"],"resolution":["1K"]}}
                        ]""",
                    ),
                )
                val id =
                    when {
                        request.url.encodedPath.endsWith("/images/models") -> "simulation/image"
                        request.url.queryParameter("output_modalities") == "decisions" -> "simulation/decisions"
                        else -> "simulation/text"
                    }
                val models = getAsJsonArray("data")
                add(
                    "data",
                    JsonArray().apply {
                        models.filter { it.asJsonObject.get("id").asString == id }.forEach { add(it) }
                    },
                )
            }
        }

    private fun decisions(
        body: JsonObject,
        fault: String?,
    ): JsonObject =
        JsonObject().apply {
            if (fault != "invalid") {
                add(
                    "answers",
                    JsonObject().apply {
                        body.getAsJsonObject("questions").entrySet().forEach { (name, question) ->
                            val type = question.asJsonObject.get("type").asString
                            add(
                                name,
                                JsonObject().apply {
                                    addProperty("type", type)
                                    addProperty(
                                        type,
                                        if (type == "score") {
                                            3.0
                                        } else if (name in setOf("spoiler", "temporary")) {
                                            0.0
                                        } else {
                                            1.0
                                        },
                                    )
                                },
                            )
                        }
                    },
                )
            }
        }

    private fun chat(
        stage: String,
        fault: String?,
    ): JsonObject =
        JsonObject().apply {
            add(
                "choices",
                JsonArray().apply {
                    add(
                        JsonObject().apply {
                            addProperty("finish_reason", if (fault == "truncated") "length" else "stop")
                            add(
                                "message",
                                JsonObject().apply {
                                    addProperty("role", "assistant")
                                    addProperty(
                                        "content",
                                        if (fault ==
                                            "empty"
                                        ) {
                                            ""
                                        } else if (stage ==
                                            "prompt"
                                        ) {
                                            SimulationFixtures.PROMPT
                                        } else {
                                            SimulationFixtures.DESCRIPTION
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }

    private fun fixtureResponse(request: Request): Response {
        if (config.scenario.action == "protocols") {
            return response(request, 403, "<html><title>Just a moment...</title></html>")
                .newBuilder()
                .header("cf-mitigated", "challenge")
                .build()
        }
        val payload = checkNotNull(replay?.match(request))
        return response(request, 200, payload.bytes, payload.contentType)
    }

    private fun CloudflareWebViewRequest.asFixtureRequest(): Request {
        val builder = Request.Builder().url(url)
        if (method == "POST") {
            val form = okhttp3.FormBody.Builder()
            postData?.toString(Charsets.UTF_8)?.split('&')?.forEach { field ->
                val parts = field.split('=', limit = 2)
                form.addEncoded(parts[0], parts.getOrElse(1) { "" })
            }
            builder.post(form.build())
        }
        return builder.build()
    }

    private fun source(
        request: Request,
        attempt: Int,
    ): Response {
        if (request.url.encodedPath.endsWith(".png")) {
            return response(request, 200, image, "image/png")
        }
        if (request.url.host == "www.royalroad.com" && config.scenario.faults.containsKey("cloudflare") &&
            !journal.verified(request.url.host)
        ) {
            return response(
                request,
                403,
                "<html><title>Just a moment...</title><div id=\"cf-challenge-running\">Checking your browser</div></html>",
            ).newBuilder()
                .header("cf-mitigated", "challenge")
                .build()
        }
        val first =
            stories
                .first()
                .chapters
                .first()
                .url
        if (request.url.toString() == first) {
            when (config.scenario.faults["source"]) {
                "429" -> if (attempt == 1) return response(request, 429, "Rate limited").newBuilder().header("Retry-After", "3").build()
                "404" -> return response(request, 404, "Chapter removed")
                "invalid" -> return response(request, 200, "<html><body>Missing chapter</body></html>")
                "offline" -> if (attempt == 1) throw UnknownHostException("Simulated offline source")
                "timeout" -> if (attempt == 1) throw SocketTimeoutException("Simulated source timeout")
                "exhausted" -> return response(request, 503, "Unavailable")
            }
        }
        val html =
            stories.firstOrNull { it.sourceUrl == request.url.toString() && it.sourceId == "royal_road" }?.let(
                SimulationFixtures::storyHtml,
            )
                ?: SimulationFixtures.chapterHtml(request.url.toString())
        return response(request, 200, html, "text/html")
    }

    override fun render(request: CloudflareWebViewRequest): CloudflareRenderOutcome {
        val fixtureRequest = request.asFixtureRequest()
        replay?.match(fixtureRequest)?.let { fixture ->
            journal.event("browser_render", "fixture")
            return CloudflareRenderOutcome.Rendered(CloudflareRenderedPage(fixture.bytes.toString(Charsets.UTF_8), request.url))
        }
        if (request.url !in chapterUrls || request.method != "GET") deny()
        val operation = "browser-${digest(request.url)}"
        journal.event("browser_render", operation)
        val host = request.url.toHttpUrl().host
        val fault = config.scenario.faults["cloudflare"]
        if (!journal.verified(host)) {
            when (fault) {
                "manual", "still-blocked" -> return CloudflareRenderOutcome.NeedsManualVerification(CloudflareRenderFailure.ChallengeActive)
                "renderer-death" -> return CloudflareRenderOutcome.NeedsManualVerification(CloudflareRenderFailure.RenderProcessGone)
                "origin-404" -> return CloudflareRenderOutcome.OriginHttpError(404)
                "transport" -> {
                    if (journal.nextAttempt(operation) == 1) return CloudflareRenderOutcome.TransportError
                }
            }
        }
        val page =
            CloudflareRenderedPage(
                SimulationFixtures.chapterHtml(request.url),
                if (fault ==
                    "wrong-page"
                ) {
                    SimulationFixtures.OTHER_URL
                } else {
                    request.url
                },
            )
        return if (CloudflareRenderedPageValidator.isExpectedPage(request, page.finalUrl, page.html)) {
            CloudflareRenderOutcome.Rendered(page)
        } else {
            CloudflareRenderOutcome.PageContentUnexpected(CloudflareRenderedPage("<html>Wrong page</html>", page.finalUrl))
        }
    }

    private fun awaitResponse(
        stage: String,
        operation: String,
        cancelled: () -> Boolean,
    ) {
        if (config.hold == stage) {
            journal.event("held", operation)
            journal.phase("held")
            val deadline = System.nanoTime() + 120_000_000_000L
            while (!File(journal.directory, "release/$stage").exists() && !File(journal.directory, "release/$operation").exists()) {
                checkCancelled(cancelled)
                if (System.nanoTime() > deadline) throw SocketTimeoutException("Simulation hold expired")
                Thread.sleep(25)
            }
            journal.event("released", operation)
            journal.phase("running")
        }
        val logicalDelay =
            if (config.timing == "fast") {
                100L
            } else {
                when (stage) {
                    "image" -> 10_000L
                    "description", "prompt" -> 4_000L
                    "source" -> 800L
                    else -> 200L
                }
            }
        val deadline = System.nanoTime() + time.realDelayMillis(logicalDelay) * 1_000_000L
        while (System.nanoTime() < deadline) {
            checkCancelled(cancelled)
            Thread.sleep(minOf(25L, ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1L)))
        }
        checkCancelled(cancelled)
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw IOException("Simulated request cancelled")
    }

    private fun response(
        request: Request,
        code: Int,
        text: String,
        type: String = "application/json",
    ): Response = response(request, code, text.toByteArray(), type)

    private fun response(
        request: Request,
        code: Int,
        bytes: ByteArray,
        type: String,
    ): Response =
        Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("Simulation")
            .body(bytes.toResponseBody(type.toMediaType()))
            .build()

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(6).joinToString("") {
            "%02x".format(it)
        }
}
