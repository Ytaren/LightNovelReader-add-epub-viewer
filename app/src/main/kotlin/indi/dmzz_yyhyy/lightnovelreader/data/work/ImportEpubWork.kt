package indi.dmzz_yyhyy.lightnovelreader.data.work

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import indi.dmzz_yyhyy.lightnovelreader.data.bookshelf.BookshelfRepository
import indi.dmzz_yyhyy.lightnovelreader.data.epub.EpubImporter
import indi.dmzz_yyhyy.lightnovelreader.data.local.LocalBookDataSource

@HiltWorker
class ImportEpubWork @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val localBookDataSource: LocalBookDataSource,
    private val bookshelfRepository: BookshelfRepository
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "ImportEpubWork"
    }

    override suspend fun doWork(): Result {
        val uri = inputData.getString("uri")?.let(Uri::parse) ?: return Result.failure()
        val preferredBookshelfId = inputData.getInt("bookshelfId", -1)

        return try {
            val importedBook = EpubImporter.import(appContext, uri)
            localBookDataSource.updateBookInformation(importedBook.bookInformation)
            localBookDataSource.updateBookVolumes(importedBook.bookVolumes)
            importedBook.chapterContents.forEach(localBookDataSource::updateChapterContent)

            val targetBookshelfId = when {
                preferredBookshelfId != -1 && bookshelfRepository.getBookshelf(preferredBookshelfId) != null -> preferredBookshelfId
                else -> bookshelfRepository.getAllBookshelfIds().firstOrNull() ?: -1
            }

            if (targetBookshelfId != -1) {
                bookshelfRepository.addBookIntoBookShelf(targetBookshelfId, importedBook.bookInformation)
            }

            Result.success(
                workDataOf(
                    "bookId" to importedBook.bookInformation.id,
                    "title" to importedBook.bookInformation.title
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import EPUB", e)
            Result.failure()
        }
    }
}
