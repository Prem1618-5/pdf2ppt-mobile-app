package app.papertoslides.core

import app.papertoslides.core.model.Box
import app.papertoslides.core.model.TextLine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Access to the handoff pack (fixtures, expected answers, tools). */
object Fixtures {
    val dir = File(System.getProperty("handoff.dir") ?: "../handoff")
    val outDir = File(System.getProperty("out.dir") ?: "build/test-out").apply { mkdirs() }
    fun file(name: String) = File(dir, "fixtures/$name")

    data class PageLines(val wIn: Double, val hIn: Double, val kind: String, val lines: List<TextLine>, val figs: List<Box>)

    fun lines(name: String): List<PageLines> {
        val root = Json.parseToJsonElement(File(dir, "fixtures/lines/$name.lines.json").readText()).jsonObject
        return root["pages"]!!.jsonArray.map { p ->
            p as JsonObject
            PageLines(
                p["w_in"]!!.jsonPrimitive.double, p["h_in"]!!.jsonPrimitive.double, p["kind"]!!.jsonPrimitive.content,
                p["lines"]!!.jsonArray.map { l ->
                    val a = l as JsonArray
                    TextLine(a[0].jsonPrimitive.double, a[1].jsonPrimitive.double, a[2].jsonPrimitive.double,
                        a[3].jsonPrimitive.double, a[4].jsonPrimitive.content, a[5].jsonPrimitive.double)
                },
                (p["figs"] as? JsonArray ?: JsonArray(emptyList())).map { f ->
                    val a = f.jsonArray.map { it.jsonPrimitive.double }
                    Box(a[0], a[1], a[2], a[3])
                },
            )
        }
    }

    fun expectedNumbers(pdf: String): List<Int> {
        val root = Json.parseToJsonElement(File(dir, "fixtures/expected.json").readText()).jsonObject
        return root[pdf]!!.jsonObject["numbers"]!!.jsonArray.map { it.jsonPrimitive.int }
    }
}
