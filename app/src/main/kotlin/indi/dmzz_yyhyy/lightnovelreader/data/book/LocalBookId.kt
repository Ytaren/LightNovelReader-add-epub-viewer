package indi.dmzz_yyhyy.lightnovelreader.data.book

import java.util.UUID

private const val EPUB_LOCAL_BOOK_ID_PREFIX = "epub-local:"

fun createLocalEpubBookId(): String = EPUB_LOCAL_BOOK_ID_PREFIX + UUID.randomUUID()

fun String.isLocalEpubBookId(): Boolean = startsWith(EPUB_LOCAL_BOOK_ID_PREFIX)
