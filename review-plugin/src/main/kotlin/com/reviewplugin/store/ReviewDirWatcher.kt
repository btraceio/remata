package com.reviewplugin.store

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileEvent
import com.intellij.openapi.vfs.VirtualFileListener
import com.intellij.openapi.vfs.VirtualFileManager

class ReviewDirWatcher : ProjectActivity {

    override suspend fun execute(project: Project) {
        val store = project.getService(CommentStore::class.java)
        store.loadAll()

        val basePath = project.basePath ?: return
        val reviewPath = "$basePath/.review/comments"

        val listener = object : VirtualFileListener {
            override fun contentsChanged(event: VirtualFileEvent) {
                if (isReviewFile(event.file, reviewPath)) {
                    store.reload(event.file)
                }
            }

            override fun fileCreated(event: VirtualFileEvent) {
                if (isReviewFile(event.file, reviewPath)) {
                    store.reload(event.file)
                }
            }

            override fun fileDeleted(event: VirtualFileEvent) {
                if (isReviewFile(event.file, reviewPath)) {
                    store.remove(event.file)
                }
            }

            private fun isReviewFile(file: VirtualFile, expectedParent: String): Boolean {
                return file.extension == "json" &&
                    file.parent?.path == expectedParent
            }
        }

        VirtualFileManager.getInstance().addVirtualFileListener(listener, store)
    }
}
