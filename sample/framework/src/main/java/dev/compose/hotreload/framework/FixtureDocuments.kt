package dev.compose.hotreload.framework

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File

/** A fixed, generated document for the real system picker; no user files are accessed. */
class FixtureDocuments : DocumentsProvider() {
    override fun onCreate() = true
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: arrayOf(
        Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES
    )).apply {
        newRow().apply {
            add(Root.COLUMN_ROOT_ID, "fixture"); add(Root.COLUMN_DOCUMENT_ID, "fixture")
            add(Root.COLUMN_TITLE, "Hot reload fixtures"); add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY)
            add(Root.COLUMN_MIME_TYPES, "image/png")
        }
    }
    private fun row(id: String, projection: Array<out String>?): Cursor = MatrixCursor(projection ?: arrayOf(
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE
    )).apply {
        newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, id)
            add(Document.COLUMN_DISPLAY_NAME, if (id == "fixture") "Hot reload fixtures" else "hot-reload.png")
            add(Document.COLUMN_MIME_TYPE, if (id == "fixture") Document.MIME_TYPE_DIR else "image/png")
            add(Document.COLUMN_FLAGS, 0); add(Document.COLUMN_SIZE, if (id == "fixture") 0 else fixturePng().size)
        }
    }
    override fun queryDocument(documentId: String, projection: Array<out String>?) = row(documentId, projection)
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?) = row("image", projection)
    override fun isChildDocument(parentDocumentId: String, documentId: String) = parentDocumentId == "fixture" && documentId == "image"
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        require(documentId == "image" && mode == "r")
        val file = File(context!!.cacheDir, "launcher-fixture.png")
        file.writeBytes(fixturePng())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
}
