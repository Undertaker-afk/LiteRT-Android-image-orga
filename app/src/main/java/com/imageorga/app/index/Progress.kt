package com.imageorga.app.index

import kotlinx.coroutines.flow.MutableStateFlow

/** Live state of background indexing, shown in the app while it runs. */
data class IndexProgress(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    /** Why the last run could not do its work, if it could not. */
    val problem: String? = null,
)

/** Live state of a model download. */
data class DownloadProgress(
    val running: Boolean = false,
    val fileName: String = "",
    val bytes: Long = 0,
    val total: Long = 0,
    val error: String? = null,
)

object Progress {
    val index = MutableStateFlow(IndexProgress())
    val download = MutableStateFlow(DownloadProgress())
}
