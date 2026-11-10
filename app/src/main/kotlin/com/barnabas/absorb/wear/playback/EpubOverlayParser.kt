package com.barnabas.absorb.wear.playback

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Reads an EPUB 3's narration straight from its package and SMIL files, for
 * an Audiobookshelf server that only sees an ebook. [load] returns a file
 * inside the EPUB as text, or null when it isn't there (a local zip, or
 * range requests to one on the server). Mirrors the phone client's parser,
 * regexes and all, so both ends agree on sentences and timings.
 */
object EpubOverlayParser {

    private val ATTR = Regex("""([\w:.-]+)\s*=\s*("([^"]*)"|'([^']*)')""")
    private val ITEM = Regex("""<(?:\w+:)?item\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val ITEMREF = Regex("""<(?:\w+:)?itemref\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val ROOTFILE = Regex("""<(?:\w+:)?rootfile\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val PAR = Regex("""<(?:\w+:)?par\b[^>]*>(.*?)</(?:\w+:)?par\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TEXT = Regex("""<(?:\w+:)?text\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val AUDIO = Regex("""<(?:\w+:)?audio\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val NAV_LINK = Regex("""<a\b[^>]*href\s*=\s*("([^"]*)"|'([^']*)')[^>]*>(.*?)</a\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TAGS = Regex("<[^>]*>")
    private val SPACE = Regex("\\s+")
    private val CLOCK = Regex("""^([\d.]+)\s*(h|min|s|ms)?$""")

    private class Item(val href: String, val type: String, val overlay: String?, val props: String)

    suspend fun parse(load: suspend (String) -> String?): NarrationOverlay? {
        val container = load("META-INF/container.xml") ?: return null
        val rootfile = ROOTFILE.find(container) ?: return null
        val opfPath = attrs(rootfile.value)["full-path"] ?: return null
        val opf = load(opfPath) ?: return null

        val byId = LinkedHashMap<String, Item>()
        for (m in ITEM.findAll(opf)) {
            val a = attrs(m.value)
            val id = a["id"] ?: continue
            val href = a["href"] ?: continue
            byId[id] = Item(resolve(opfPath, href), a["media-type"] ?: "", a["media-overlay"], a["properties"] ?: "")
        }
        val types = byId.values.associate { it.href to it.type }
        if (byId.values.none { it.overlay != null }) return null
        val navPaths = byId.values.filter { it.props.split(' ').contains("nav") }.map { it.href }
        val labels = navLabels(load, navPaths)

        val itemrefs = ITEMREF.findAll(opf).map { attrs(it.value)["idref"] }.toList()
        val smilPaths = LinkedHashSet<String>()
        for (ref in itemrefs) {
            val overlayId = ref?.let { byId[it]?.overlay } ?: continue
            byId[overlayId]?.href?.let { smilPaths.add(it) }
        }
        // Each timing file is a request of its own on a server, so they come
        // a few at a time rather than one after another.
        val smils = HashMap<String, String?>()
        coroutineScope {
            for (batch in smilPaths.chunked(6)) {
                batch.map { path -> async { path to load(path) } }.awaitAll().forEach { (p, s) -> smils[p] = s }
            }
        }

        val raw = ArrayList<NarrationOverlay.RawClip>()
        val sectionInfo = HashMap<Int, Pair<String, String?>>()
        val spine = ArrayList<String>()
        for ((spineIndex, ref) in itemrefs.withIndex()) {
            val item = ref?.let { byId[it] }
            if (item != null) spine.add(item.href)
            val overlayId = item?.overlay ?: continue
            val smilPath = byId[overlayId]?.href ?: continue
            val smil = smils[smilPath] ?: continue
            sectionInfo[spineIndex] = item.href to labels[item.href]
            for (par in PAR.findAll(smil)) {
                val body = par.groupValues[1]
                val text = TEXT.find(body) ?: continue
                val audio = AUDIO.find(body) ?: continue
                val t = attrs(text.value)
                val au = attrs(audio.value)
                val src = t["src"] ?: continue
                val audioSrc = au["src"] ?: continue
                val begin = parseClock(au["clipbegin"]) ?: 0.0
                val hash = src.indexOf('#')
                raw.add(
                    NarrationOverlay.RawClip(
                        spineIndex,
                        resolve(smilPath, src),
                        if (hash < 0) null else NarrationOverlay.undoEscapes(src.substring(hash + 1)),
                        resolve(smilPath, audioSrc),
                        begin,
                        parseClock(au["clipend"]),
                    ),
                )
            }
        }
        return NarrationOverlay.build(raw, types, sectionInfo)?.withContents(spine, labels)
    }

    /** Chapter names from the navigation document, by the file they point at. */
    private suspend fun navLabels(load: suspend (String) -> String?, navPaths: List<String>): Map<String, String> {
        val out = HashMap<String, String>()
        for (path in navPaths) {
            val doc = load(path) ?: continue
            for (m in NAV_LINK.findAll(doc)) {
                val href = m.groupValues[2].ifEmpty { m.groupValues[3] }
                val label = unescape(m.groupValues[4].replace(TAGS, " ")).replace(SPACE, " ").trim()
                if (href.isEmpty() || label.isEmpty()) continue
                out.putIfAbsent(resolve(path, href), label)
            }
        }
        return out
    }

    /** SMIL clock values: "1:02:03.5", "02:03.5", "12.5s", "250ms", "1.5min",
     *  "2h", or plain seconds. */
    fun parseClock(v: String?): Double? {
        val s = v?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (s.contains(':')) {
            val parts = s.split(':').map { it.trim().toDoubleOrNull() }
            if (parts.any { it == null }) return null
            return parts.fold(0.0) { acc, p -> acc * 60 + p!! }
        }
        val m = CLOCK.find(s) ?: return null
        val n = m.groupValues[1].toDoubleOrNull() ?: return null
        return when (m.groupValues[2]) {
            "h" -> n * 3600
            "min" -> n * 60
            "ms" -> n / 1000
            else -> n
        }
    }

    private fun attrs(tag: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (m in ATTR.findAll(tag)) {
            var name = m.groupValues[1].lowercase()
            val colon = name.lastIndexOf(':')
            if (colon >= 0 && !name.startsWith("xml")) name = name.substring(colon + 1)
            out.putIfAbsent(name, unescape(m.groupValues[3].ifEmpty { m.groupValues[4] }))
        }
        return out
    }

    private fun unescape(s: String): String = s
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    private fun dir(path: String): String {
        val i = path.lastIndexOf('/')
        return if (i < 0) "" else path.substring(0, i + 1)
    }

    /** [href] relative to [base]'s folder, as a path inside the zip, every
     *  escape undone the way the zip names its files. */
    private fun resolve(base: String, href: String): String {
        val clean = NarrationOverlay.undoEscapes(href.substringBefore('#').substringBefore('?'))
        val parts = ArrayList<String>()
        for (p in (dir(base) + clean).split('/')) {
            if (p.isEmpty() || p == ".") continue
            if (p == "..") {
                if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            } else {
                parts.add(p)
            }
        }
        return parts.joinToString("/")
    }
}
