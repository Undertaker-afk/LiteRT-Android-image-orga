package com.imageorga.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.imageorga.app.data.Category
import com.imageorga.app.data.Config
import com.imageorga.app.data.Db
import com.imageorga.app.data.ImageRow
import com.imageorga.app.data.ModelCatalog
import com.imageorga.app.data.Settings
import com.imageorga.app.index.Categorizer
import com.imageorga.app.index.Permissions
import com.imageorga.app.index.PhotoAccess
import com.imageorga.app.index.Progress
import com.imageorga.app.index.Scheduler
import com.imageorga.app.index.VectorIndex
import com.imageorga.app.ml.Embedder
import com.imageorga.app.ml.ModelMissingException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Stats(
    val total: Int = 0,
    val indexed: Int = 0,
    val failed: Int = 0,
    val modelReady: Boolean = false,
    val modelFileName: String = "",
    val modelsOnDiskMb: Long = 0,
    val unusedModelsMb: Long = 0,
)

data class SearchState(
    /** False while the search box is empty and no "similar photos" view is open. */
    val active: Boolean = false,
    val busy: Boolean = false,
    val results: List<ImageRow> = emptyList(),
    val error: String? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val db = Db.get(app)

    val config = Settings.flow
    val accelNote = Settings.accelNote
    val indexProgress = Progress.index
    val download = Progress.download

    val permissions = MutableStateFlow(Permissions.state(app))
    val images = MutableStateFlow<List<ImageRow>>(emptyList())
    val categories = MutableStateFlow<List<Category>>(emptyList())
    val stats = MutableStateFlow(Stats())
    val query = MutableStateFlow("")
    val similarTo = MutableStateFlow<ImageRow?>(null)
    val search = MutableStateFlow(SearchState())
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    private var byId: Map<Long, ImageRow> = emptyMap()
    private var cachedQuery: Triple<String, String, FloatArray>? = null
    private var sortJob: Job? = null

    init {
        observeData()
        observeSearch()
    }

    private fun observeData() {
        viewModelScope.launch {
            // Only the start and end of a download matter here, not every progress tick.
            val downloading = Progress.download.map { it.running }.distinctUntilChanged()
            combine(Db.version, Settings.flow, downloading) { _, cfg, _ -> cfg }.collectLatest { cfg ->
                delay(150) // coalesce bursts of changes while indexing
                val loaded = withContext(Dispatchers.IO) {
                    val all = db.allImages()
                    val file = ModelCatalog.resolve(cfg)
                    val files = ModelCatalog.dir(ctx).listFiles().orEmpty()
                    val unused = files.filter { !it.name.startsWith(file.fileName) }.sumOf { it.length() }
                    Triple(
                        all, db.categories(),
                        Stats(
                            total = all.size,
                            indexed = db.indexedCount(cfg.signature),
                            failed = db.failedCount(),
                            modelReady = file.isReady(ctx),
                            modelFileName = file.fileName,
                            modelsOnDiskMb = files.sumOf { it.length() } / 1_000_000,
                            unusedModelsMb = unused / 1_000_000,
                        )
                    )
                }
                byId = loaded.first.associateBy { it.id }
                images.value = loaded.first
                categories.value = loaded.second
                stats.value = loaded.third
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeSearch() {
        viewModelScope.launch {
            combine(query.debounce(200), similarTo, images, Settings.flow) { q, sim, _, cfg -> Triple(q.trim(), sim, cfg) }
                .collectLatest { (q, sim, cfg) -> runSearch(q, sim, cfg) }
        }
    }

    private suspend fun runSearch(q: String, sim: ImageRow?, cfg: Config) {
        if (sim == null && q.isEmpty()) {
            search.value = SearchState()
            return
        }
        search.value = search.value.copy(active = true, busy = true, error = null)
        try {
            val hits = withContext(Dispatchers.Default) {
                if (sim != null) {
                    val v = VectorIndex.vectorOf(ctx, cfg, sim.id)
                        ?: throw IllegalStateException("This photo has not been indexed yet.")
                    VectorIndex.search(ctx, cfg, v, limit = 60, relativeCut = 0f)
                } else {
                    val cached = cachedQuery?.takeIf { it.first == q && it.second == cfg.signature }
                    val v = cached?.third ?: Embedder.embedQuery(ctx, q).also {
                        cachedQuery = Triple(q, Settings.current.signature, it)
                    }
                    VectorIndex.search(ctx, Settings.current, v, limit = 300, relativeCut = 0.4f)
                }
            }
            search.value = SearchState(active = true, results = hits.mapNotNull { byId[it.id] })
        } catch (e: ModelMissingException) {
            search.value = SearchState(active = true, error = "Download the model in Settings to search.")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            search.value = SearchState(active = true, error = t.message ?: "Search failed.")
        }
    }

    // ---- lifecycle --------------------------------------------------------------------

    fun onStart() {
        Embedder.uiVisible = true
        refreshPermissions()
    }

    fun onStop() {
        Embedder.uiVisible = false
        // Outlives the screen on purpose: frees the model's memory once the app is hidden.
        @Suppress("OPT_IN_USAGE")
        GlobalScope.launch { Embedder.releaseIfIdle() }
    }

    fun refreshPermissions() {
        val before = permissions.value
        val now = Permissions.state(ctx)
        permissions.value = now
        if (now.photos != PhotoAccess.NONE && (before.photos != now.photos || images.value.isEmpty())) {
            Scheduler.indexNow(ctx)
        }
    }

    // ---- settings ---------------------------------------------------------------------

    fun updateConfig(transform: (Config) -> Config) {
        val old = Settings.current
        Settings.update(transform)
        val new = Settings.current
        if (old.backend != new.backend) Settings.clearAccelNote()
        if (old.autoIndex != new.autoIndex || old.chargingOnly != new.chargingOnly) Scheduler.applyAutoIndex(ctx)
        if (old.signature != new.signature) {
            cachedQuery = null
            if (ModelCatalog.resolve(new).isReady(ctx)) Scheduler.indexNow(ctx)
        } else if (old.strictness != new.strictness) {
            resort(delayMs = 400)
        }
    }

    fun startDownload() = Scheduler.download(ctx)
    fun cancelDownload() = Scheduler.cancelDownload(ctx)

    fun deleteUnusedModels() {
        viewModelScope.launch(Dispatchers.IO) {
            ModelCatalog.deleteUnused(ctx, Settings.current)
            Db.bump()
        }
    }

    fun indexNow() = Scheduler.indexNow(ctx)
    fun stopIndexing() = Scheduler.cancelIndex(ctx)

    fun rebuildIndex() {
        viewModelScope.launch(Dispatchers.IO) {
            Scheduler.cancelIndex(ctx)
            db.resetIndex()
            VectorIndex.invalidate()
            Scheduler.indexNow(ctx)
        }
    }

    // ---- categories -------------------------------------------------------------------

    fun saveCategory(id: Long?, name: String, description: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (id == null) db.insertCategory(name.trim(), description.trim())
            else db.updateCategory(id, name.trim(), description.trim())
            resort()
        }
    }

    fun deleteCategory(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            db.deleteCategory(id)
            resort()
        }
    }

    /** Places a photo by hand; automatic sorting will not move it again. */
    fun movePhoto(image: ImageRow, categoryId: Long?) {
        viewModelScope.launch(Dispatchers.IO) { db.setManual(image.id, categoryId) }
    }

    /** Hands a photo back to automatic sorting. */
    fun autoSortPhoto(image: ImageRow) {
        viewModelScope.launch(Dispatchers.IO) {
            db.clearManual(image.id)
            resort()
        }
    }

    private fun resort(delayMs: Long = 0) {
        sortJob?.cancel()
        sortJob = viewModelScope.launch {
            delay(delayMs)
            try {
                Categorizer.run(ctx)
            } catch (e: ModelMissingException) {
                messages.tryEmit("Download the model in Settings so photos can be sorted.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                messages.tryEmit("Sorting failed: ${t.message}")
            }
        }
    }
}
