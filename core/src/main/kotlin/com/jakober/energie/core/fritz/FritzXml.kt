package com.jakober.energie.core.fritz

import kotlinx.datetime.Instant
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/** Zerlegt die XML-Antworten der AHA-HTTP-Schnittstelle. */
object FritzXml {

    fun parseDeviceList(xml: String): List<FritzDevice> {
        val root = parse(xml)
        return root.children("device").map { d ->
            val meter = d.child("powermeter")?.let { pm ->
                PowerMeter(
                    // "power" kommt in mW, "energy" in Wh, "voltage" in mV.
                    powerWatt = (pm.text("power")?.toDoubleOrNull() ?: 0.0) / 1000.0,
                    energyWh = pm.text("energy")?.toLongOrNull() ?: 0L,
                    voltage = pm.text("voltage")?.toDoubleOrNull()?.let { it / 1000.0 },
                )
            }
            FritzDevice(
                ain = d.getAttribute("identifier").trim(),
                id = d.getAttribute("id"),
                name = d.text("name") ?: "",
                productName = d.getAttribute("productname"),
                manufacturer = d.getAttribute("manufacturer"),
                firmware = d.getAttribute("fwversion"),
                functionBitmask = d.getAttribute("functionbitmask").toIntOrNull() ?: 0,
                present = d.text("present") == "1",
                powerMeter = meter,
                // Temperatur kommt in Zehntelgrad.
                temperatureCelsius = d.child("temperature")?.text("celsius")?.toDoubleOrNull()?.let { it / 10.0 },
                switchOn = d.child("switch")?.text("state")?.let { it == "1" },
            )
        }
    }

    fun parseDeviceStats(xml: String): DeviceStats {
        val root = parse(xml)
        fun series(tag: String): List<StatSeries> =
            root.child(tag)?.children("stats")?.map { s ->
                StatSeries(
                    gridSeconds = s.getAttribute("grid").toIntOrNull() ?: 0,
                    newestAt = s.getAttribute("datatime").toLongOrNull()?.let { Instant.fromEpochSeconds(it) },
                    values = s.textContent.trim().split(",").map { it.trim().toDoubleOrNull() },
                )
            } ?: emptyList()
        return DeviceStats(
            temperature = series("temperature").map { it.scaled(0.1) },
            voltage = series("voltage").map { it.scaled(0.001) },
            power = series("power").map { it.scaled(0.01) },
            energy = series("energy"),
        )
    }

    // Die Box liefert Temperatur in 0,1 Grad, Spannung in mV und Leistung in 0,01 W.
    private fun StatSeries.scaled(factor: Double) = copy(values = values.map { v -> v?.let { it * factor } })

    private fun parse(xml: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            // Kein DTD-Laden, kein Zugriff nach draussen: die Antwort kommt aus dem Heimnetz,
            // aber ein XML-Parser sollte trotzdem nie externe Inhalte nachladen.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            isNamespaceAware = false
            isExpandEntityReferences = false
        }
        val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        return doc.documentElement
    }

    private fun Element.children(tag: String): List<Element> {
        val out = ArrayList<Element>()
        var n: Node? = firstChild
        while (n != null) {
            if (n.nodeType == Node.ELEMENT_NODE && n.nodeName == tag) out += n as Element
            n = n.nextSibling
        }
        return out
    }

    private fun Element.child(tag: String): Element? = children(tag).firstOrNull()

    private fun Element.text(tag: String): String? = child(tag)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
}

data class SessionInfo(val sid: String, val challenge: String, val blockTimeSeconds: Int)

fun FritzXml.parseSessionInfo(xml: String): SessionInfo {
    // Kleine Antwort, einfacher Zugriff ueber regulaere Ausdruecke reicht hier.
    fun tag(name: String) = Regex("<$name>(.*?)</$name>").find(xml)?.groupValues?.get(1)?.trim() ?: ""
    return SessionInfo(
        sid = tag("SID").ifEmpty { FritzBoxClient.NO_SID },
        challenge = tag("Challenge"),
        blockTimeSeconds = tag("BlockTime").toIntOrNull() ?: 0,
    )
}
