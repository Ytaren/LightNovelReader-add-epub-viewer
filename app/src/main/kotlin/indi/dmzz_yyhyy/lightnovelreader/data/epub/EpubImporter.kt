package indi.dmzz_yyhyy.lightnovelreader.data.epub

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import indi.dmzz_yyhyy.lightnovelreader.data.book.createLocalEpubBookId
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.MutableBookInformation
import io.nightfish.lightnovelreader.api.book.MutableChapterContent
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.lightnovelreader.api.book.WordCount
import io.nightfish.lightnovelreader.api.content.builder.ContentBuilder
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponentData
import io.nightfish.lightnovelreader.api.content.component.ImageComponentData
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

object EpubImporter {

    data class ImportedBook(
        val bookInformation: BookInformation,
        val bookVolumes: BookVolumes,
        val chapterContents: List<ChapterContent>
    )

    private data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String
    )

    private data class TocEntry(
        val path: String,
        val title: String
    )

    private val blockTags = setOf(
        "p", "div", "section", "article", "blockquote", "h1", "h2", "h3", "h4", "h5", "h6",
        "li", "ul", "ol", "table", "tr", "td", "th", "pre"
    )

    fun import(context: Context, epubUri: Uri): ImportedBook {
        val now = LocalDateTime.now()
        val bookId = createLocalEpubBookId()
        val displayName = queryDisplayName(context, epubUri)
            ?.removeSuffix(".epub")
            ?.ifBlank { null }
            ?: "Imported EPUB"

        val bookDir = context.filesDir.resolve("epub").resolve(bookId).also { if (!it.exists()) it.mkdirs() }
        val assetsDir = bookDir.resolve("assets").also { if (!it.exists()) it.mkdirs() }
        val sourceEpubFile = bookDir.resolve("source.epub")

        context.contentResolver.openInputStream(epubUri)?.use { input ->
            sourceEpubFile.outputStream().buffered().use { output ->
                input.copyTo(output)
            }
        } ?: error("Failed to open epub uri")

        ZipFile(sourceEpubFile).use { zipFile ->
            val containerXml = readZipEntryText(zipFile, "META-INF/container.xml")
            val containerDoc = Jsoup.parse(containerXml, "", Parser.xmlParser())
            val opfPath = containerDoc.selectFirst("rootfile")
                ?.attr("full-path")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: error("Invalid EPUB: OPF path not found")

            val opfXml = readZipEntryText(zipFile, opfPath)
            val opfDoc = Jsoup.parse(opfXml, "", Parser.xmlParser())
            val metadata = opfDoc.selectFirst("metadata")

            val title = metadata?.firstTagText("title").orEmpty().ifBlank { displayName }
            val author = metadata?.firstTagText("creator").orEmpty()
            val description = metadata?.firstTagText("description").orEmpty()
            val publisher = metadata?.firstTagText("publisher").orEmpty()

            val manifestMap = mutableMapOf<String, ManifestItem>()
            opfDoc.select("manifest > item").forEach { item ->
                val id = item.attr("id").trim()
                val href = item.attr("href").trim()
                if (id.isEmpty() || href.isEmpty()) return@forEach
                manifestMap[id] = ManifestItem(
                    id = id,
                    href = href,
                    mediaType = item.attr("media-type").trim(),
                    properties = item.attr("properties").trim()
                )
            }

            val spineItems = opfDoc.select("spine > itemref")
                .mapNotNull { itemRef ->
                    val idRef = itemRef.attr("idref").trim()
                    manifestMap[idRef]
                }
                .filter { it.isHtmlContent() && !it.properties.contains("nav") }
                .toMutableList()

            if (spineItems.isEmpty()) {
                spineItems += manifestMap.values
                    .filter { it.isHtmlContent() && !it.properties.contains("nav") }
                    .sortedBy { it.href }
            }

            if (spineItems.isEmpty()) {
                error("Invalid EPUB: chapter spine is empty")
            }

            val spinePaths = spineItems
                .map { resolveReference(opfPath, it.href) }
                .distinct()
            val spinePathSet = spinePaths.toSet()

            val tocTitleByPath = linkedMapOf<String, String>()
            parseTocEntries(opfDoc, opfPath, manifestMap, zipFile).forEach { tocEntry ->
                if (tocEntry.path in spinePathSet && !tocTitleByPath.containsKey(tocEntry.path)) {
                    tocTitleByPath[tocEntry.path] = tocEntry.title
                }
            }

            val minTocMatch = when {
                spinePaths.size <= 3 -> 1
                spinePaths.size <= 8 -> 2
                else -> 3
            }

            val selectedChapterPaths = if (tocTitleByPath.size >= minTocMatch) {
                spinePaths.filter { tocTitleByPath.containsKey(it) }
            } else {
                spinePaths
            }

            if (selectedChapterPaths.isEmpty()) {
                error("Invalid EPUB: selected chapter list is empty")
            }

            val extractedAssets = mutableMapOf<String, Uri>()
            fun extractAsset(entryPath: String): Uri? {
                val normalized = normalizePath(entryPath)
                extractedAssets[normalized]?.let { return it }
                val entry = findZipEntry(zipFile, normalized) ?: return null
                val originalName = entry.name.substringAfterLast('/')
                val safeOriginalName = sanitizeFileName(originalName.ifBlank { "asset.bin" })
                val uniqueName = "${normalized.hashCode().toUInt().toString(16)}_$safeOriginalName"
                val outFile = assetsDir.resolve(uniqueName)
                zipFile.getInputStream(entry).use { input ->
                    outFile.outputStream().buffered().use { output ->
                        input.copyTo(output)
                    }
                }
                return Uri.fromFile(outFile).also { extractedAssets[normalized] = it }
            }

            val coverUri = findCoverManifestItem(metadata, manifestMap)
                ?.href
                ?.let { resolveReference(opfPath, it) }
                ?.let(::extractAsset)
                ?: Uri.EMPTY

            val mutableChapterContents = mutableListOf<MutableChapterContent>()
            var totalWordCount = 0
            var chapterFirstImageUri = Uri.EMPTY

            selectedChapterPaths.forEachIndexed { index, chapterEntryPath ->
                val chapterHtml = readZipEntryText(zipFile, chapterEntryPath)
                val chapterDoc = Jsoup.parse(chapterHtml, "", Parser.htmlParser())
                val chapterTitle = tocTitleByPath[chapterEntryPath]
                    ?.takeIf { it.isNotBlank() }
                    ?: chapterDoc.chapterTitle(index + 1)

                val componentData = collectChapterComponentData(
                    document = chapterDoc,
                    chapterEntryPath = chapterEntryPath,
                    resolveImageUri = { src -> resolveChapterImageUri(src, chapterEntryPath, ::extractAsset) }
                ).toMutableList()

                if (componentData.isEmpty()) {
                    componentData += SimpleTextComponentData(chapterTitle)
                }

                if (chapterFirstImageUri == Uri.EMPTY) {
                    chapterFirstImageUri = componentData
                        .firstOrNull { it is ImageComponentData }
                        ?.let { (it as ImageComponentData).uri }
                        ?: Uri.EMPTY
                }

                totalWordCount += componentData
                    .filterIsInstance<SimpleTextComponentData>()
                    .sumOf { it.text.replace(Regex("\\s+"), "").length }

                val chapterId = "$bookId-chapter-${index + 1}"
                val contentJson = ContentBuilder().apply {
                    componentData.forEach(::component)
                }.build()

                mutableChapterContents += MutableChapterContent(
                    id = chapterId,
                    title = chapterTitle,
                    content = contentJson
                )
            }

            mutableChapterContents.forEachIndexed { index, chapter ->
                chapter.lastChapter = if (index > 0) mutableChapterContents[index - 1].id else ""
                chapter.nextChapter = if (index < mutableChapterContents.lastIndex) mutableChapterContents[index + 1].id else ""
            }

            val chapters = mutableChapterContents.map { ChapterInformation(it.id, it.title) }
            val bookVolumes = BookVolumes(
                bookId = bookId,
                volumes = listOf(
                    Volume(
                        volumeId = "$bookId-volume-1",
                        volumeTitle = "Main Volume",
                        chapters = chapters
                    )
                )
            )

            val finalCoverUri = if (coverUri != Uri.EMPTY) coverUri else chapterFirstImageUri
            val bookInformation = MutableBookInformation(
                id = bookId,
                title = title,
                subtitle = "",
                coverUrl = finalCoverUri,
                author = author,
                description = description,
                tags = listOf("EPUB", "Local"),
                publishingHouse = publisher,
                wordCount = WordCount(totalWordCount),
                lastUpdated = now,
                isComplete = true
            )

            return ImportedBook(
                bookInformation = bookInformation,
                bookVolumes = bookVolumes,
                chapterContents = mutableChapterContents
            )
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index < 0) return@use null
                cursor.getString(index)
            }
    }

    private fun ManifestItem.isHtmlContent(): Boolean {
        val media = mediaType.lowercase()
        val hrefLower = href.lowercase()
        return media.contains("xhtml") || media.contains("html") || hrefLower.endsWith(".xhtml") || hrefLower.endsWith(".html")
    }

    private fun parseTocEntries(
        opfDoc: Document,
        opfPath: String,
        manifestMap: Map<String, ManifestItem>,
        zipFile: ZipFile
    ): List<TocEntry> {
        val navEntries = parseNavTocEntries(opfPath, manifestMap, zipFile)
        if (navEntries.isNotEmpty()) return navEntries
        return parseNcxTocEntries(opfDoc, opfPath, manifestMap, zipFile)
    }

    private fun parseNavTocEntries(
        opfPath: String,
        manifestMap: Map<String, ManifestItem>,
        zipFile: ZipFile
    ): List<TocEntry> {
        return runCatching {
            val navManifestItem = manifestMap.values.firstOrNull { item ->
                item.isHtmlContent() && item.properties.contains("nav", ignoreCase = true)
            } ?: return emptyList()

            val navPath = resolveReference(opfPath, navManifestItem.href)
            val navHtml = readZipEntryText(zipFile, navPath)
            val navDoc = Jsoup.parse(navHtml, "", Parser.htmlParser())
            val tocNav = navDoc.select("nav")
                .firstOrNull { nav ->
                    nav.attr("epub:type").contains("toc", ignoreCase = true)
                            || nav.attr("type").contains("toc", ignoreCase = true)
                }
                ?: navDoc.selectFirst("nav")
                ?: return emptyList()

            tocNav.select("a[href]")
                .mapNotNull { anchor ->
                    val href = anchor.attr("href").trim()
                    if (href.isEmpty()) return@mapNotNull null
                    TocEntry(
                        path = resolveReference(navPath, href),
                        title = normalizeText(anchor.text())
                    )
                }
                .distinctBy { it.path }
        }.getOrElse { emptyList() }
    }

    private fun parseNcxTocEntries(
        opfDoc: Document,
        opfPath: String,
        manifestMap: Map<String, ManifestItem>,
        zipFile: ZipFile
    ): List<TocEntry> {
        return runCatching {
            val spineTocId = opfDoc.selectFirst("spine")
                ?.attr("toc")
                ?.trim()
                .orEmpty()

            val ncxManifestItem = (if (spineTocId.isNotEmpty()) manifestMap[spineTocId] else null)
                ?: manifestMap.values.firstOrNull { item ->
                    item.mediaType.contains("ncx", ignoreCase = true)
                            || item.href.lowercase().endsWith(".ncx")
                }
                ?: return emptyList()

            val ncxPath = resolveReference(opfPath, ncxManifestItem.href)
            val ncxXml = readZipEntryText(zipFile, ncxPath)
            val ncxDoc = Jsoup.parse(ncxXml, "", Parser.xmlParser())

            ncxDoc.select("navMap navPoint")
                .mapNotNull { navPoint ->
                    val src = navPoint.selectFirst("content")
                        ?.attr("src")
                        ?.trim()
                        .orEmpty()
                    if (src.isEmpty()) return@mapNotNull null
                    TocEntry(
                        path = resolveReference(ncxPath, src),
                        title = normalizeText(
                            navPoint.selectFirst("navLabel > text")
                                ?.text()
                                ?.trim()
                                .orEmpty()
                        )
                    )
                }
                .distinctBy { it.path }
        }.getOrElse { emptyList() }
    }

    private fun findCoverManifestItem(metadata: Element?, manifestMap: Map<String, ManifestItem>): ManifestItem? {
        val metadataCoverId = metadata
            ?.select("meta")
            ?.firstOrNull { meta ->
                meta.attr("name").equals("cover", ignoreCase = true)
            }
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        if (metadataCoverId != null) {
            manifestMap[metadataCoverId]?.let { return it }
        }

        return manifestMap.values.firstOrNull {
            it.properties.contains("cover-image") || it.id.contains("cover", ignoreCase = true)
        }
    }

    private fun collectChapterComponentData(
        document: Document,
        chapterEntryPath: String,
        resolveImageUri: (String) -> Uri?
    ): List<AbstractContentComponentData> {
        val body = document.selectFirst("body") ?: document
        val components = mutableListOf<AbstractContentComponentData>()
        val textBuffer = StringBuilder()

        fun appendLineBreak() {
            if (textBuffer.isNotEmpty() && textBuffer.last() != '\n') {
                textBuffer.append('\n')
            }
        }

        fun flushText() {
            val normalized = normalizeText(textBuffer.toString())
            if (normalized.isNotBlank()) {
                components += SimpleTextComponentData(normalized)
            }
            textBuffer.clear()
        }

        fun walk(node: Node) {
            when (node) {
                is TextNode -> {
                    val text = node.text().replace('\u00A0', ' ')
                    if (text.isBlank()) {
                        if (text.contains('\n')) appendLineBreak()
                        return
                    }
                    val first = text.firstOrNull()
                    if (
                        textBuffer.isNotEmpty() &&
                        first != null &&
                        !first.isWhitespace() &&
                        !textBuffer.last().isWhitespace() &&
                        textBuffer.last() != '\n'
                    ) {
                        textBuffer.append(' ')
                    }
                    textBuffer.append(text)
                }

                is Element -> {
                    val tag = node.normalName().substringAfter(':').lowercase()
                    if (tag == "script" || tag == "style") {
                        return
                    }
                    when (tag) {
                        "img", "image", "object" -> {
                            flushText()
                            if (tag == "object") {
                                val objectType = node.attr("type").trim().lowercase()
                                if (objectType.isNotEmpty() && !objectType.startsWith("image/")) {
                                    return
                                }
                            }
                            val src = node.findImageSource().orEmpty()
                            if (src.isNotEmpty()) {
                                resolveImageUri(src)?.let { components += ImageComponentData(it) }
                            }
                        }

                        "br" -> appendLineBreak()

                        else -> {
                            val isBlock = tag in blockTags
                            if (isBlock) appendLineBreak()
                            node.childNodes().forEach(::walk)
                            if (isBlock) appendLineBreak()
                        }
                    }
                }

                else -> node.childNodes().forEach(::walk)
            }
        }

        body.childNodes().forEach(::walk)
        flushText()

        return components
    }

    private fun resolveChapterImageUri(
        source: String,
        chapterEntryPath: String,
        extractAsset: (String) -> Uri?
    ): Uri? {
        val normalized = source.trim()
        if (normalized.isEmpty()) return null

        if (normalized.startsWith("data:", ignoreCase = true)) {
            return Uri.parse(normalized)
        }

        val hasScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(normalized)
        if (hasScheme) {
            return Uri.parse(normalized)
        }

        return extractAsset(resolveReference(chapterEntryPath, normalized))
    }

    private fun Element.findImageSource(): String? {
        return when (normalName().substringAfter(':').lowercase()) {
            "img" -> firstAttributeValue("src", "data-src", "data-original")
                ?: parseSrcSetFirstCandidate(attr("srcset"))
                ?: firstAttributeValue("xlink:href", "href")
            "image" -> firstAttributeValue("href", "xlink:href", "src")
            "object" -> firstAttributeValue("data", "src")
            else -> null
        }
    }

    private fun Element.firstAttributeValue(vararg names: String): String? {
        names.forEach { name ->
            val value = attr(name).trim()
            if (value.isNotEmpty()) return value
        }
        return null
    }

    private fun parseSrcSetFirstCandidate(srcSet: String): String? {
        return srcSet
            .split(',')
            .asSequence()
            .map { it.trim().substringBefore(' ').trim() }
            .firstOrNull { it.isNotEmpty() }
    }
    private fun Document.chapterTitle(index: Int): String {
        val title = selectFirst("title")?.text()?.trim().orEmpty()
        if (title.isNotEmpty()) return title

        val heading = selectFirst("h1, h2, h3")?.text()?.trim().orEmpty()
        if (heading.isNotEmpty()) return heading

        return "Chapter $index"
    }

    private fun Element.firstTagText(vararg localNames: String): String {
        val nameSet = localNames.map { it.lowercase() }.toSet()
        return children().firstNotNullOfOrNull { child ->
            val localName = child.tagName().substringAfter(':').lowercase()
            val text = child.text().trim()
            if (localName in nameSet && text.isNotEmpty()) text else null
        }.orEmpty()
    }

    private fun readZipEntryText(zipFile: ZipFile, entryPath: String): String {
        val entry = findZipEntry(zipFile, entryPath)
            ?: error("Zip entry not found: $entryPath")
        return zipFile.getInputStream(entry).bufferedReader().use { it.readText() }
    }

    private fun findZipEntry(zipFile: ZipFile, path: String): ZipEntry? {
        val candidates = linkedSetOf(
            path,
            normalizePath(path)
        )

        decodePath(path)?.let { decoded ->
            candidates += decoded
            candidates += normalizePath(decoded)
        }

        path.replace("%20", " ").let { candidates += it }

        for (candidate in candidates) {
            zipFile.getEntry(candidate)?.let { return it }
        }
        return null
    }

    private fun resolveReference(basePath: String, reference: String): String {
        val cleaned = decodePath(reference.substringBefore('#').substringBefore('?').trim())
            ?.replace('\\', '/')
            .orEmpty()

        if (cleaned.isEmpty()) return normalizePath(basePath)
        if (cleaned.startsWith('/')) return normalizePath(cleaned.removePrefix("/"))

        val baseDir = basePath.substringBeforeLast('/', "")
        val joined = if (baseDir.isEmpty()) cleaned else "$baseDir/$cleaned"
        return normalizePath(joined)
    }

    private fun normalizePath(path: String): String {
        val parts = path.replace('\\', '/').split('/')
        val stack = ArrayDeque<String>()
        for (part in parts) {
            when {
                part.isEmpty() || part == "." -> Unit
                part == ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(part)
            }
        }
        return stack.joinToString("/")
    }

    private fun normalizeText(text: String): String {
        return text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(Regex("[\\t\\u000B\\u000C]+"), " ")
            .replace(Regex(" +"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun sanitizeFileName(fileName: String): String {
        return fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    private fun decodePath(path: String): String? {
        return runCatching {
            URLDecoder.decode(path, StandardCharsets.UTF_8.name())
        }.getOrNull()
    }
}