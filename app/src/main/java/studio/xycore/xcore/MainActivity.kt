package studio.xycore.xcore

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import studio.xycore.xcore.core.XCoreConfig
import studio.xycore.xcore.core.XCoreEngine
import studio.xycore.xcore.core.XCoreError
import studio.xycore.xcore.core.XCoreEvent
import studio.xycore.xcore.core.XCoreLogRecord
import studio.xycore.xcore.core.XCoreLogLevel
import studio.xycore.xcore.core.XCoreResult
import studio.xycore.xcore.core.XCoreRuntimeChannel
import studio.xycore.xcore.core.asXCoreSuccess
import studio.xycore.xcore.core.errorOrNull
import studio.xycore.xcore.core.flatMap
import studio.xycore.xcore.core.valueOrNull
import studio.xycore.xcore.data.XCoreGameDirectory
import studio.xycore.xcore.data.XCoreInstance
import studio.xycore.xcore.data.XCoreInstanceStatus
import studio.xycore.xcore.data.XCoreLaunchRecord
import studio.xycore.xcore.data.XCoreLoaderKind
import studio.xycore.xcore.data.XCoreUiSettings
import studio.xycore.xcore.domain.XCoreCapability
import studio.xycore.xcore.domain.XCorePreflightReport
import studio.xycore.xcore.installer.XCoreInstallPhase
import studio.xycore.xcore.installer.XCoreInstallState
import studio.xycore.xcore.installer.XCoreInstallSummary
import studio.xycore.xcore.installer.XCoreDistributionTermsGate
import studio.xycore.xcore.ui.XCoreMotion

/**
 * The landscape launcher shell.
 */
class MainActivity : ComponentActivity() {

    private lateinit var graph: XCoreGraph

    private lateinit var homeScreen: View
    private lateinit var screenContainer: View
    private var subLogo: ImageView? = null
    private var subTitle: TextView? = null
    private var subSubtitle: TextView? = null
    private lateinit var playRoot: View
    private lateinit var playLabel: TextView
    private lateinit var homeName: TextView
    private lateinit var homeVersion: TextView
    private lateinit var screenInstances: View
    private lateinit var screenFiles: View
    private lateinit var screenSettings: View
    private lateinit var screenLibrary: View
    private lateinit var screenStats: View
    private lateinit var screenLogs: View
    private lateinit var screenControls: View
    private lateinit var instanceList: LinearLayout
    private lateinit var emptyState: LinearLayout
    private lateinit var filesContainer: LinearLayout
    private lateinit var filesProblem: TextView

    private var tab = LauncherTab.INSTANCES
    private var onHome = true
    private var instances: List<XCoreInstance> = emptyList()
    private var states: Map<String, XCoreInstallState> = emptyMap()
    private var selectedId: String? = null
    private var busy = false
    private var rowsProblem: String? = null

    /** What the library and log screens are currently narrowed to; both are view state only. */
    private var libraryFilter: LibraryKind? = null
    private var libraryQuery = ""
    private var libraryEntries: List<LibraryEntry> = emptyList()
    private var logFilter: XCoreLogLevel? = null
    private val logChips = listOf(
        R.id.chip_log_all to null,
        R.id.chip_log_debug to XCoreLogLevel.DEBUG,
        R.id.chip_log_info to XCoreLogLevel.INFO,
        R.id.chip_log_warn to XCoreLogLevel.WARN,
        R.id.chip_log_error to XCoreLogLevel.ERROR,
    )
    private var settingsGroup = SettingsGroup.GENERAL
    private var settingsRowHue = R.color.xcore_blue
    private var uiSettings = XCoreUiSettings()

    private val stampFormat = SimpleDateFormat("MMM d · HH:mm", Locale.getDefault())
    private val clockFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val abi: String =
        Build.SUPPORTED_ABIS.firstOrNull { candidate -> "64" in candidate } ?: Build.SUPPORTED_ABIS.firstOrNull()
            ?: "arm64-v8a"

    private val eulaUrl: String = XCoreDistributionTermsGate.EULA_URL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph = (application as XCoreApplication).graph
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        homeScreen = findViewById(R.id.screen_home)
        screenContainer = findViewById(R.id.container_frame)
        playRoot = findViewById(R.id.layout_play_button)
        playLabel = findViewById(R.id.tv_play_label)
        homeName = findViewById(R.id.tv_home_name)
        homeVersion = findViewById(R.id.tv_home_version)
        screenInstances = findViewById(R.id.screen_instances)
        screenFiles = findViewById(R.id.screen_files)
        screenSettings = findViewById(R.id.screen_settings)
        instanceList = findViewById(R.id.container_instances_list)
        emptyState = findViewById(R.id.container_empty_state)
        filesContainer = findViewById(R.id.container_files)
        filesProblem = findViewById(R.id.tv_files_problem)
        screenLibrary = findViewById(R.id.screen_library)
        screenStats = findViewById(R.id.screen_stats)
        screenLogs = findViewById(R.id.screen_logs)
        screenControls = findViewById(R.id.screen_controls)
        applySystemBarInsets()

        // Apply global motion & touch animations
        XCoreMotion.applyButtonTouchEffect(playRoot)

        // The block carries the game's bitmap face. A @font reference in the layout's fontFamily
        // attribute needs API 26, and this shell still runs on 24, so the typeface is set here.
        // The hero lockup lives in two includes, so each root is scoped separately.
        ResourcesCompat.getFont(this, R.font.silkscreen_bold)?.let { face ->
            playLabel.typeface = face
            for (root in listOf(homeScreen, screenInstances)) {
                root.findViewById<TextView>(R.id.tv_banner_title)?.typeface = face
                root.findViewById<TextView>(R.id.tv_banner_kicker)?.typeface = face
            }
        }

        // Home Header Actions
        findViewById<View>(R.id.btn_create_instance)?.let {
            XCoreMotion.applyButtonTouchEffect(it)
            it.setOnClickListener { showCreateDialog() }
        }
        findViewById<View>(R.id.container_account)?.setOnClickListener { showAccount() }
        findViewById<View>(R.id.btn_account_switch)?.setOnClickListener { showAccount() }
        findViewById<View>(R.id.btn_back)?.setOnClickListener { goHome() }

        findViewById<View>(R.id.card_instance_picker)?.let {
            XCoreMotion.applyCardTouchEffect(it)
            it.setOnClickListener { showInstancePicker() }
        }
        findViewById<View>(R.id.btn_downloads)?.let {
            XCoreMotion.applyButtonTouchEffect(it)
            it.setOnClickListener { showDownloads() }
        }

        bindRail()
        bindLibraryScreen()
        bindStatsScreen()
        bindLogsScreen()
        bindControlsScreen()
        loadUiSettings()

        bindSettingsTabs()
        bindSettingsRows()

        playRoot.setOnClickListener { onPlayTapped() }

        lifecycleScope.launch {
            graph.installations.progress.collect { progress ->
                if (progress == null) {
                    renderPlayLabel()
                } else {
                    val percent = (progress.aggregateFraction * 100f).toInt().coerceIn(0, 100)
                    playLabel.text = "Installing $percent%"
                    applyLiveProgress(progress.completedTasks, progress.totalTasks, progress.aggregateBytesDone, progress.aggregateBytesTotal, progress.label)
                }
            }
        }

        applyTab(LauncherTab.INSTANCES)
        paintPlayHeader()
        refreshInstances()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (onHome) {
                    finish()
                } else {
                    goHome()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        refreshInstances()
    }

    // ---------------------------------------------------------------- instances

    private fun refreshInstances() {
        graph.run({
            val listed = graph.instances.list()
            val stateOutcome = graph.installations.installStates()
            val selected = graph.instances.selected()
            val problem = listed.errorOrNull()?.format()
                ?: stateOutcome.errorOrNull()?.format()
                ?: (stateOutcome as? XCoreResult.Cancelled)?.let { cancelled -> "cancelled: ${cancelled.reason ?: cancelled.operation}" }
            XCoreResult.Success(
                Snapshot(
                    instances = listed.valueOrNull().orEmpty(),
                    states = stateOutcome.valueOrNull().orEmpty(),
                    selectedId = selected.valueOrNull()?.id,
                    problem = problem,
                ),
            )
        }) { outcome ->
            val snapshot = outcome.valueOrNull()
            if (snapshot == null) {
                rowsProblem = outcome.errorOrNull()?.format() ?: "the instance list could not be read"
                renderInstances()
                return@run
            }
            instances = snapshot.instances
            states = snapshot.states.associateBy { state -> state.versionId }
            selectedId = snapshot.selectedId ?: instances.firstOrNull()?.id
            rowsProblem = snapshot.problem
            renderInstances()
            if (tab == LauncherTab.FILES) refreshFiles()
        }
    }

    private fun renderInstances() {
        instanceList.removeAllViews()
        val featured = instances.firstOrNull { instance -> instance.id == selectedId } ?: instances.firstOrNull()
        val others = instances.filterNot { instance -> instance.id == featured?.id }
        featured?.let {
            val view = instanceRow(it, true)
            instanceList.addView(view)
            XCoreMotion.animateCardItemEnter(view, 0)
        }
        others.forEachIndexed { index, instance ->
            val view = instanceRow(instance, false)
            instanceList.addView(view)
            XCoreMotion.animateCardItemEnter(view, (index + 1) * 40L)
        }
        emptyState.visibility = if (instances.isEmpty()) View.VISIBLE else View.GONE
        homeName.text = featured?.name ?: getString(R.string.instance_title_default)
        homeVersion.text = featured?.versionId ?: getString(R.string.instance_version_default)
        renderPlayLabel()
    }

    private fun instanceRow(instance: XCoreInstance, selected: Boolean): View {
        val row = LayoutInflater.from(this).inflate(R.layout.item_instance, instanceList, false)
        XCoreMotion.applyCardTouchEffect(row)
        val card = row.findViewById<View>(R.id.card_instance)
        card.setBackgroundResource(if (selected) R.drawable.bg_card_selected else R.drawable.bg_card)
        row.findViewById<TextView>(R.id.tv_instance_title).text = instance.name
        row.findViewById<TextView>(R.id.tv_instance_version).text = instance.versionId
        card.setOnClickListener {
            selectedId = instance.id
            graph.run({ graph.instances.select(instance.id) }) { refreshInstances() }
        }
        row.findViewById<View>(R.id.btn_menu).setOnClickListener { anchor -> showRowMenu(anchor, instance) }

        val chips = row.findViewById<LinearLayout>(R.id.container_chips)
        if (chips != null) {
            addChip(chips, getString(R.string.tag_java), color(R.color.xcore_accent_soft), color(R.color.xcore_accent))
            if (instance.loader != XCoreLoaderKind.VANILLA) {
                addChip(chips, instance.loader.displayName, color(R.color.xcore_blue_soft), color(R.color.xcore_blue_bright))
            }
            val state = states[instance.versionId]
            when {
                state == null -> addChip(chips, getString(R.string.install_not_installed), color(R.color.xcore_chip_neutral), color(R.color.xcore_text_muted))
                state.isReady -> addChip(chips, getString(R.string.install_ready), color(R.color.xcore_accent_soft), color(R.color.xcore_accent))
                state.phase == XCoreInstallPhase.FAILED -> addChip(chips, getString(R.string.install_failed), color(R.color.xcore_chip_error), color(R.color.xcore_danger))
                state.isInProgress -> addChip(chips, state.phase.name.lowercase(), color(R.color.xcore_chip_warn), color(R.color.xcore_amber))
                else -> addChip(chips, state.phase.name.lowercase(), color(R.color.xcore_chip_neutral), color(R.color.xcore_text_muted))
            }
            if (instance.status != XCoreInstanceStatus.READY) {
                addChip(
                    chips,
                    instance.status.name.lowercase(),
                    if (instance.status == XCoreInstanceStatus.BROKEN) color(R.color.xcore_chip_error) else color(R.color.xcore_chip_neutral),
                    if (instance.status == XCoreInstanceStatus.BROKEN) color(R.color.xcore_danger) else color(R.color.xcore_text_muted),
                )
            }
        }

        val state = states[instance.versionId]
        val bar = row.findViewById<ProgressBar>(R.id.bar_install)
        val detail = row.findViewById<TextView>(R.id.tv_install_detail)
        val fraction = state?.progressFraction
        if (bar != null && detail != null) {
            if (fraction != null) {
                bar.visibility = View.VISIBLE
                bar.progress = (fraction * 100f).toInt().coerceIn(0, 100)
                detail.visibility = View.VISIBLE
                detail.text = "${state.label}  ${state.stage.bytesDone}/${state.stage.bytesTotal} B"
            } else {
                bar.visibility = View.GONE
                detail.visibility = if (state?.error != null) View.VISIBLE else View.GONE
                detail.text = state?.error?.format().orEmpty()
            }
        }
        row.tag = instance.id
        return row
    }

    private fun applyLiveProgress(completed: Int, total: Int, bytesDone: Long, bytesTotal: Long, label: String) {
        val featured = instances.firstOrNull { instance -> instance.id == selectedId } ?: return
        for (index in 0 until instanceList.childCount) {
            val child = instanceList.getChildAt(index)
            if (child.tag as? String != featured.id) continue
            child.findViewById<ProgressBar>(R.id.bar_install)?.apply {
                visibility = View.VISIBLE
                progress = if (bytesTotal > 0L) {
                    ((bytesDone.toFloat() / bytesTotal.toFloat()) * 100f).toInt().coerceIn(0, 100)
                } else {
                    if (total > 0) (completed * 100 / total) else 0
                }
            }
            child.findViewById<TextView>(R.id.tv_install_detail)?.apply {
                visibility = View.VISIBLE
                text = "$label  $completed/$total files  ${formatBytes(bytesDone)} of ${formatBytes(bytesTotal)}"
            }
        }
    }

    private fun showRowMenu(anchor: View, instance: XCoreInstance) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, getString(R.string.menu_select))
        menu.menu.add(0, 2, 1, getString(R.string.menu_install))
        menu.menu.add(0, 3, 2, getString(R.string.menu_audit))
        menu.menu.add(0, 4, 3, getString(R.string.menu_inspect))
        menu.menu.add(0, 5, 4, getString(R.string.menu_delete))
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    selectedId = instance.id
                    graph.run({ graph.instances.select(instance.id) }) { refreshInstances() }
                }

                2 -> startInstall(instance)
                3 -> graph.run({ graph.installations.audit(instance.versionId, abi) }) { outcome ->
                    val audit = outcome.valueOrNull()
                    showInfo(
                        title = "Verification of ${instance.versionId}",
                        rows = audit?.let {
                            listOf(
                                "checked" to "${it.checkedFiles} files, ${formatBytes(it.presentBytes)}",
                                "missing" to it.missing.joinToString(", ").ifEmpty { "none" },
                                "mismatched" to it.mismatched.joinToString(", ").ifEmpty { "none" },
                                "notes" to it.notes.joinToString("; ").ifEmpty { "none" },
                            )
                        } ?: listOf("result" to (outcome.errorOrNull()?.format() ?: "no result")),
                        note = "Digests are compared against the published values; nothing was downloaded.",
                    )
                }

                4 -> graph.run({ graph.instances.inspectDirectory(instance.id) }) { outcome ->
                    val directory = outcome.valueOrNull()
                    showInfo(
                        title = directory?.path ?: "Inspect failed",
                        rows = directory?.let {
                            listOf(
                                "exists" to it.exists.toString(),
                                "entries" to it.entries.toString(),
                                "bytes" to formatBytes(it.bytesUsed),
                                "writable" to it.writable.toString(),
                            )
                        } ?: listOf("error" to (outcome.errorOrNull()?.format() ?: "unknown")),
                    )
                }

                5 -> graph.run({ graph.instances.delete(instance.id, removeFiles = true) }) { outcome ->
                    val deletion = outcome.valueOrNull()
                    showInfo(
                        title = if (deletion != null) "Deleted '${deletion.instance.name}'" else "Delete failed",
                        rows = deletion?.let {
                            listOf(
                                "row removed" to it.rowRemoved.toString(),
                                "bytes freed" to formatBytes(it.filesRemoved),
                                "path" to (it.path ?: "not resolved"),
                                "files" to (it.error?.format() ?: "removed cleanly"),
                            )
                        } ?: listOf("error" to (outcome.errorOrNull()?.format() ?: "unknown")),
                    )
                    refreshInstances()
                }
            }
            true
        }
        menu.show()
    }

    // ---------------------------------------------------------------- install

    private fun onPlayTapped() {
        val instance = instances.firstOrNull { candidate -> candidate.id == selectedId }
        if (instance == null) {
            showInfo(
                title = "Nothing selected",
                rows = listOf("hint" to "Pick an instance first. Play never guesses which one you meant."),
            )
            return
        }
        if (states[instance.versionId]?.isReady == true) {
            preflight(instance)
        } else {
            offerInstall(instance)
        }
    }

    private fun offerInstall(instance: XCoreInstance) {
        graph.run({ graph.installations.termsAcknowledged() }) { outcome ->
            val acknowledged = outcome.valueOrNull() == true
            if (acknowledged) {
                startInstall(instance)
                return@run
            }
            showInfo(
                title = "Accept the distribution terms first",
                rows = listOf(
                    "Why" to "The version files belong to Mojang and are licensed through the EULA, so the launcher fetches them only for a player who has accepted it on this device.",
                    "Stored" to (outcome.errorOrNull()?.format() ?: "no acknowledgement is stored yet"),
                ),
                note = "Read it at $eulaUrl before accepting.",
                actions = listOf(
                    "I accept - fetch ${instance.versionId}" to {
                        graph.run({ graph.installations.acceptTerms() }) { accepted ->
                            if (accepted.errorOrNull() != null) {
                                showInfo(
                                    title = "Could not record the acceptance",
                                    rows = listOf("error" to accepted.errorOrNull()?.format().orEmpty()),
                                )
                            } else {
                                startInstall(instance)
                            }
                        }
                    },
                    "Cancel" to { },
                ),
            )
        }
    }

    private fun startInstall(instance: XCoreInstance) {
        if (busy) return
        busy = true
        playLabel.text = getString(R.string.install_phase_resolving)
        graph.run({ graph.installations.install(instance.versionId, abi, instance.id) }) { outcome ->
            busy = false
            when (outcome) {
                is XCoreResult.Success -> showInfo(
                    title = "Installed ${instance.versionId}",
                    rows = summaryRows(outcome.value),
                    note = "Client jar ${outcome.value.clientJarPath}",
                    actions = listOf("Preflight" to { preflight(instance) }),
                )

                is XCoreResult.Failure -> showInfo(
                    title = "Install failed",
                    rows = listOf(
                        "kind" to outcome.error.kind.name.lowercase(),
                        "detail" to outcome.error.format(),
                        "retryable" to outcome.error.retryable.toString(),
                    ),
                    note = "Nothing was reported as installed that is not on disk.",
                )

                is XCoreResult.Cancelled -> showInfo(
                    title = "Install interrupted",
                    rows = listOf("operation" to outcome.operation, "reason" to (outcome.reason ?: "none")),
                    note = "Open journal rows let a later run resume instead of starting over.",
                )
            }
            renderPlayLabel()
            refreshInstances()
        }
    }

    private fun summaryRows(summary: XCoreInstallSummary): List<Pair<String, String>> = listOf(
        "version" to summary.versionId,
        "files written" to summary.filesWritten.toString(),
        "already present" to summary.filesAlreadyPresent.toString(),
        "bytes written" to formatBytes(summary.bytesWritten),
        "libraries" to "${summary.libraryCount} (${summary.skippedLibraries.size} skipped by rules, ${summary.unverifiedLibraries.size} without a published digest)",
        "natives" to summary.nativeCount.toString(),
        "assets" to "${summary.assetCount} (index ${summary.assetIndexId ?: "none"})",
        "already counted" to formatBytes(summary.bytesAlreadyPresent),
        "elapsed" to "${summary.elapsedMillis} ms",
    )

    private fun preflight(instance: XCoreInstance) {
        graph.run({ graph.launches.preflight(instance.id, abi, graph.config.value) }) { outcome ->
            val report = outcome.valueOrNull()
            if (report == null) {
                showInfo(
                    title = "Preflight failed",
                    rows = listOf("error" to (outcome.errorOrNull()?.format() ?: "unknown")),
                )
            } else {
                showInfo(
                    title = if (report.ready) "Ready to launch" else "Blocked",
                    rows = report.checks.map { check ->
                        "${if (check.passed) "+ " else "- "}${check.label}" to check.detail
                    },
                    note = "This is the preflight only. The launch pipeline itself is Phase 6, so no process was started.",
                )
            }
        }
    }

    // ---------------------------------------------------------------- home band

    private fun showAccount() {
        showInfo(
            title = "Account",
            rows = listOf(
                "Sign-in" to "No Microsoft or Mojang flow is wired yet.",
                "Planned phase" to XCoreCapability.AUTHENTICATION.plannedPhase,
                "Effect" to "Nothing can be launched as a named player until it lands.",
            ),
            note = "Authentication is its own capability; this build does not fake a logged-in player.",
        )
    }

    private fun showInstancePicker() {
        val sheet = inflateDialog(R.layout.dialog_info)
        sheet.findViewById<TextView>(R.id.tv_dialog_title).text = getString(R.string.picker_title)
        val container = sheet.findViewById<LinearLayout>(R.id.container_dialog_rows)
        val dialog = dialogFor(sheet)
        if (instances.isEmpty()) {
            container.addView(plainDialogLine(getString(R.string.picker_empty)))
        }
        instances.forEach { instance ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_instance_picker, container, false)
            XCoreMotion.applyCardTouchEffect(row)
            row.findViewById<TextView>(R.id.tv_picker_name).text = instance.name
            val state = states[instance.versionId]
            row.findViewById<TextView>(R.id.tv_picker_detail).text = buildString {
                append(instance.versionId)
                append(" · ")
                append(
                    when {
                        state == null -> getString(R.string.install_not_installed)
                        state.isReady -> getString(R.string.install_ready)
                        state.phase == XCoreInstallPhase.FAILED -> getString(R.string.install_failed)
                        else -> state.phase.name.lowercase()
                    },
                )
            }
            row.findViewById<View>(R.id.row_instance).setBackgroundResource(
                if (instance.id == selectedId) R.drawable.bg_card_selected else R.drawable.bg_card,
            )
            row.setOnClickListener {
                dialog.dismiss()
                selectedId = instance.id
                graph.run({ graph.instances.select(instance.id) }) { refreshInstances() }
            }
            container.addView(row)
        }
        sheet.findViewById<TextView>(R.id.tv_dialog_note).apply {
            text = getString(R.string.picker_note)
            visibility = View.VISIBLE
        }
        val actions = sheet.findViewById<LinearLayout>(R.id.container_dialog_actions)
        addDialogAction(actions, dialog, getString(R.string.action_new_instance)) { showCreateDialog() }
        addDialogAction(actions, dialog, getString(R.string.action_close)) { }
        dialog.show()
    }

    private fun showDownloads() {
        graph.run({ graph.installations.installStates() }) { outcome ->
            val persisted = outcome.valueOrNull().orEmpty()
            val rows = mutableListOf<Pair<String, String>>()
            graph.installations.progress.value?.let { live ->
                rows += "running" to "${live.label} · ${(live.aggregateFraction * 100f).toInt()}% · " +
                    "${formatBytes(live.aggregateBytesDone)} of ${formatBytes(live.aggregateBytesTotal)} · " +
                    "${live.completedTasks}/${live.totalTasks} files"
            }
            persisted.forEach { state ->
                rows += state.versionId to buildString {
                    append(state.phase.name.lowercase())
                    if (state.label.isNotBlank()) append(" · ").append(state.label)
                    append(" · ").append(state.filesWritten).append(" written / ").append(state.filesSkipped).append(" kept")
                    append(" · ").append(formatBytes(state.bytesWritten))
                    state.error?.let { append(" · ").append(it.format()) }
                }
            }
            graph.tasks.history(limit = 8).forEach { handle ->
                rows += "task ${handle.name}" to buildString {
                    append(handle.state.name.lowercase())
                    handle.progress?.let { append(" · ").append((it * 100f).toInt()).append('%') }
                    handle.error?.let { append(" · ").append(it.format()) }
                }
            }
            showInfo(
                title = getString(R.string.downloads_title),
                rows = rows.ifEmpty { listOf("state" to getString(R.string.downloads_empty)) },
                note = outcome.errorOrNull()?.let { "installStates: ${it.format()}" } ?: getString(R.string.downloads_note),
                actions = listOf(
                    getString(R.string.action_open_game_tree) to { openScreen(LauncherTab.FILES) },
                    getString(R.string.refresh) to { showDownloads() },
                ),
            )
        }
    }

    private fun plainDialogLine(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(color(R.color.xcore_text_muted))
        setPadding(0, 6, 0, 6)
    }

    // ---------------------------------------------------------------- create

    private fun showCreateDialog() {
        val sheet = inflateDialog(R.layout.dialog_create_instance)
        val name = sheet.findViewById<EditText>(R.id.field_name)
        val version = sheet.findViewById<EditText>(R.id.field_version)
        val versions = sheet.findViewById<LinearLayout>(R.id.container_versions)
        val versionsStatus = sheet.findViewById<TextView>(R.id.tv_versions_status)
        val loaders = sheet.findViewById<LinearLayout>(R.id.container_loaders)
        val eula = sheet.findViewById<CheckBox>(R.id.check_eula)
        val dialog = dialogFor(sheet)
        var chosenLoader = XCoreLoaderKind.VANILLA

        versionsStatus.text = getString(R.string.versions_empty)
        sheet.findViewById<View>(R.id.btn_load_versions)?.let { btn ->
            XCoreMotion.applyButtonTouchEffect(btn)
            btn.setOnClickListener {
                versionsStatus.text = getString(R.string.versions_loading)
                graph.run({ graph.installations.versions() }) { outcome ->
                    val listing = outcome.valueOrNull()
                    versions.removeAllViews()
                    if (listing == null) {
                        versionsStatus.text = outcome.errorOrNull()?.format()
                            ?: (outcome as? XCoreResult.Cancelled)?.let { "cancelled: ${it.reason ?: it.operation}" }
                            ?: "no versions returned"
                        return@run
                    }
                    versionsStatus.text = buildString {
                        append("${listing.entries.size} of the manifest")
                        append(" · ${if (listing.servedFromCache) "from cache" else "fetched now"}")
                        append(" · latest release ${listing.latestRelease ?: "unknown"}")
                        append(" · latest snapshot ${listing.latestSnapshot ?: "unknown"}")
                    }
                    listing.entries.take(60).forEachIndexed { idx, entry ->
                        val row = LayoutInflater.from(this).inflate(R.layout.item_version_row, versions, false)
                        XCoreMotion.applyCardTouchEffect(row)
                        XCoreMotion.animateCardItemEnter(row, idx * 20L)
                        row.findViewById<TextView>(R.id.tv_version_id).text = entry.id
                        row.findViewById<TextView>(R.id.tv_version_type).text = entry.releaseType.displayName
                        row.findViewById<TextView>(R.id.tv_version_installed).text =
                            if (entry.id in listing.installedIds) "installed" else ""
                        row.setOnClickListener {
                            version.setText(entry.id)
                            dialog.dismiss()
                            createInstance(name.text.toString().trim(), entry.id, chosenLoader, eula.isChecked)
                        }
                        versions.addView(row)
                    }
                }
            }
        }

        XCoreLoaderKind.entries.take(3).forEach { kind ->
            val chip = TextView(this).apply {
                text = kind.displayName
                setTypeface(android.graphics.Typeface.MONOSPACE)
                textSize = 12f
                setPadding(26, 14, 26, 14)
                gravity = Gravity.CENTER
                background = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_chip)
                backgroundTintList = ColorStateList.valueOf(color(R.color.xcore_accent_soft))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = 10
                }
                setOnClickListener {
                    chosenLoader = kind
                    for (index in 0 until loaders.childCount) {
                        val child = loaders.getChildAt(index) as TextView
                        child.setTextColor(
                            if (child.text == kind.displayName) color(R.color.xcore_accent) else color(R.color.xcore_text_muted),
                        )
                    }
                }
            }
            chip.setTextColor(color(R.color.xcore_accent))
            loaders.addView(chip)
        }

        sheet.findViewById<View>(R.id.btn_cancel)?.setOnClickListener { dialog.dismiss() }
        sheet.findViewById<View>(R.id.btn_create)?.setOnClickListener {
            val requestedName = name.text.toString().trim()
            val requestedVersion = version.text.toString().trim()
            if (requestedName.isEmpty() || requestedVersion.isEmpty()) {
                versionsStatus.text = "Both a name and a version id are required."
                return@setOnClickListener
            }
            dialog.dismiss()
            createInstance(requestedName, requestedVersion, chosenLoader, eula.isChecked)
        }
        dialog.show()
    }

    private fun createInstance(
        name: String,
        versionId: String,
        loader: XCoreLoaderKind,
        acceptTerms: Boolean,
    ) {
        if (acceptTerms) {
            graph.run({ graph.installations.acceptTerms() }) { accepted ->
                accepted.errorOrNull()?.let { problem ->
                    showInfo(title = "EULA acceptance not stored", rows = listOf("error" to problem.format()))
                }
                createInstanceAfterTerms(name, versionId, loader)
            }
        } else {
            createInstanceAfterTerms(name, versionId, loader)
        }
    }

    private fun createInstanceAfterTerms(
        name: String,
        versionId: String,
        loader: XCoreLoaderKind,
    ) {
        graph.run({
            graph.installations
                .ensureRegistered(versionId)
                .flatMap { graph.instances.create(name, versionId, loader, "", "") }
        }) { outcome ->
            val created = outcome.valueOrNull()
            if (created == null) {
                showInfo(
                    title = "Could not create instance",
                    rows = listOf("error" to (outcome.errorOrNull()?.format() ?: "unknown")),
                )
            } else {
                selectedId = created.id
                refreshInstances()
            }
        }
    }

    // ---------------------------------------------------------------- files / settings

    private fun refreshFiles() {
        graph.run({
            val layout = graph.storageLayout()
                ?: return@run XCoreResult.Failure(
                    XCoreError.Storage("no storage layout is prepared; the engine has not probed one"),
                )
            val rows = XCoreGameDirectory.REQUIRED.mapNotNull { kind ->
                val directory = layout.dir(kind) ?: return@mapNotNull null
                val children = directory.walkTopDown().filter { file -> file.isFile }.toList()
                StorageRow(
                    name = kind.relativePath,
                    path = directory.absolutePath,
                    files = children.size,
                    bytes = children.sumOf { file -> file.length() },
                    purpose = kind.purpose,
                )
            }
            XCoreResult.Success(rows)
        }) { outcome ->
            filesContainer.removeAllViews()
            val rows = outcome.valueOrNull()
            filesProblem.visibility = if (rows == null) View.VISIBLE else View.GONE
            filesProblem.text = rows?.let { "${it.size} directories" }.orEmpty()
                ?: (outcome.errorOrNull()?.format() ?: "the game tree could not be read")
            rows?.forEachIndexed { index, row ->
                val view = LayoutInflater.from(this).inflate(R.layout.item_storage_row, filesContainer, false)
                XCoreMotion.applyCardTouchEffect(view)
                XCoreMotion.animateCardItemEnter(view, index * 30L)
                view.findViewById<TextView>(R.id.tv_row_name).text = row.name
                view.findViewById<TextView>(R.id.tv_row_counts).text = "${row.files} files · ${formatBytes(row.bytes)}"
                view.findViewById<TextView>(R.id.tv_row_path).text = row.path
                view.findViewById<TextView>(R.id.tv_row_purpose).text = row.purpose
                view.setOnClickListener {
                    showInfo(
                        title = row.name,
                        rows = listOf("path" to row.path, "files" to row.files.toString(), "bytes" to formatBytes(row.bytes)),
                        note = row.purpose,
                    )
                }
                filesContainer.addView(view)
            }
        }
    }

    private fun showGeneral() {
        val config = graph.config.value
        showInfo(
            title = "General",
            rows = listOf(
                "engine" to graph.engineState.value.name.lowercase(),
                "modules" to graph.moduleStatuses.value.joinToString(", ") { status -> "${status.id}:${status.state.name.lowercase()}" }
                    .ifEmpty { "none registered" },
                "concurrent downloads" to config.maxConcurrentDownloads.toString(),
                "http timeout" to "${config.httpTimeoutMillis} ms",
                "user agent" to config.userAgent,
                "log level" to config.logLevel.label,
                "storage root" to config.storageRoot.name.lowercase(),
                "problem" to (graph.engineProblem.value?.format() ?: "none"),
            ),
            note = rowsProblem,
            actions = listOf(
                getString(R.string.start_engine) to { graph.startEngine() },
                getString(R.string.shutdown_engine) to { graph.shutdown() },
                getString(R.string.refresh) to { refreshInstances() },
            ),
        )
    }

    private fun showStorage() {
        val layout = graph.storageLayout()
        val usable = File(layout?.plan?.rootPath ?: filesDir.absolutePath).usableSpace
        showInfo(
            title = "Storage",
            rows = listOf(
                "game root" to (layout?.plan?.rootPath ?: "not prepared yet"),
                "directories" to (layout?.directories?.size?.toString() ?: "0"),
                "usable now" to formatBytes(usable),
                "fallback used" to (layout?.plan?.usedFallback?.toString() ?: "unknown"),
                "reason" to (layout?.plan?.reason ?: "none"),
                "metadata" to "in the app's private databases directory",
            ),
            note = "Everything the launcher writes stays app-private, so uninstall removes it.",
            actions = listOf(getString(R.string.refresh) to { refreshInstances() }),
        )
    }

    private fun showSecurity() {
        graph.run({ graph.installations.termsState() }) { outcome ->
            val stored = outcome.valueOrNull()
            val engine = graph.installations.graphOrNull()
            showInfo(
                title = "Security",
                rows = listOf(
                    "EULA acknowledgement" to (stored ?: "not accepted on this device"),
                    "distribution hosts" to "only the allowlist in :versions is contacted",
                    "unverified libraries" to if (engine == null) {
                        "installation engine not running"
                    } else {
                        "coordinate-only Maven entries are listed, never trusted"
                    },
                    "game files" to "nothing copyrighted is bundled in this APK",
                    "storage" to "app-private; no shared or external write outside the game tree",
                ),
                note = "The install path reads the acknowledgement and refuses to fetch without it.",
                actions = listOf(
                    "Accept EULA" to { graph.run({ graph.installations.acceptTerms() }) { refreshInstances() } },
                    "Revoke" to { graph.run({ graph.installations.revokeTerms() }) { } },
                ),
            )
        }
    }

    private fun showAbout() {
        val report = graph.capabilityReport()
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        showInfo(
            title = "About X-Core",
            rows = listOf(
                "app" to "$versionName (${getString(R.string.app_name)})",
                "engine" to XCoreEngine.ENGINE_VERSION,
                "application id" to packageName,
                "device abi" to abi,
                "android api" to Build.VERSION.SDK_INT.toString(),
                "capabilities" to report.summarize(),
                "installed services" to report.installedServiceIds.size.toString(),
                "launchable" to report.launchable.toString(),
            ),
            note = if (report.inconsistencies.isEmpty()) {
                "Minecraft and the EULA belong to Mojang. X-Core is an independent launcher and bundles no game content."
            } else {
                "Capability table drift: ${report.inconsistencies.joinToString("; ")}"
            },
        )
    }

    private fun showCapability(capability: XCoreCapability) {
        val installed = graph.services.contains(capability.serviceId)
        showInfo(
            title = capability.displayName,
            rows = listOf(
                "status" to if (installed) "installed" else "not implemented",
                "service id" to capability.serviceId,
                "planned phase" to capability.plannedPhase,
            ),
            note = if (installed) {
                "The capability is in the catalog; the section UI for it has not been built yet."
            } else {
                "This control is shown as a gap rather than a working switch: ${capability.displayName} arrives in ${capability.plannedPhase}."
            },
        )
    }

    // ---------------------------------------------------------------- left rail

    /** One row in the rail. A null target means the Play page rather than a sub-screen. */
    private data class RailEntry(val includeId: Int, val labelRes: Int, val iconRes: Int, val target: LauncherTab?)

    private val railEntries = listOf(
        RailEntry(R.id.nav_play, R.string.nav_play, R.drawable.ic_home, null),
        RailEntry(R.id.nav_instances, R.string.nav_instances, R.drawable.ic_cube, LauncherTab.INSTANCES),
        RailEntry(R.id.nav_library, R.string.nav_library, R.drawable.ic_layers, LauncherTab.LIBRARY),
        RailEntry(R.id.nav_stats, R.string.nav_stats, R.drawable.ic_chart, LauncherTab.STATS),
        RailEntry(R.id.nav_logs, R.string.nav_logs, R.drawable.ic_terminal, LauncherTab.LOGS),
        RailEntry(R.id.nav_controls, R.string.nav_controls, R.drawable.ic_gamepad, LauncherTab.CONTROLS),
        RailEntry(R.id.nav_files, R.string.nav_files, R.drawable.ic_folder, LauncherTab.FILES),
        RailEntry(R.id.nav_settings, R.string.nav_settings, R.drawable.ic_gear, LauncherTab.SETTINGS),
    )

    private fun bindRail() {
        railEntries.forEach { entry ->
            val row = findViewById<View>(entry.includeId) ?: return@forEach
            row.findViewById<TextView>(R.id.tv_nav_label)?.setText(entry.labelRes)
            row.findViewById<ImageView>(R.id.iv_nav_icon)?.setImageResource(entry.iconRes)
            row.setOnClickListener {
                XCoreMotion.applyButtonTouchEffect(it)
                entry.target?.let { openScreen(it) } ?: goHome()
            }
        }
    }

    private fun paintRail(target: LauncherTab?) {
        val tint = ColorStateList.valueOf(color(R.color.mc_white))
        val dim = ColorStateList.valueOf(color(R.color.mc_dim))
        railEntries.forEach { entry ->
            val row = findViewById<View>(entry.includeId) ?: return@forEach
            val active = entry.target == target
            row.isSelected = active
            row.findViewById<View>(R.id.nav_marker)?.visibility = if (active) View.VISIBLE else View.INVISIBLE
            row.findViewById<TextView>(R.id.tv_nav_label)?.setTextColor(if (active) tint else dim)
            row.findViewById<ImageView>(R.id.iv_nav_icon)?.imageTintList = if (active) tint else dim
        }
    }

    /**
     * The rail's status line is the launcher's only claim about itself on the Play page, so it
     * reads the registry rather than asserting that anything is connected.
     */
    private fun renderRailStatus() {
        val dot = findViewById<View>(R.id.v_status_dot)
        val label = findViewById<TextView>(R.id.tv_status_text) ?: return
        if (graph.storageLayout() == null) {
            dot?.backgroundTintList = ColorStateList.valueOf(color(R.color.mc_amber))
            label.setText(R.string.rail_status_pending)
            return
        }
        dot?.backgroundTintList = ColorStateList.valueOf(color(R.color.mc_green))
        label.text = getString(R.string.rail_status_ready, graph.services.bindings().size)
    }

    private fun bindLibraryScreen() {
        findViewById<View>(R.id.btn_library_refresh)?.let { button ->
            XCoreMotion.applyButtonTouchEffect(button)
            button.setOnClickListener { refreshLibrary() }
        }
        findViewById<EditText>(R.id.field_library_search)?.addTextChangedListener { text ->
            libraryQuery = text?.toString()?.trim().orEmpty()
            renderLibrary()
        }
        bindLibraryChip(R.id.chip_library_all, getString(R.string.filter_all), R.drawable.ic_cube, null)
        LibraryKind.entries.forEach { kind ->
            bindLibraryChip(kind.chipId, getString(kind.filterLabel), kind.icon, kind)
        }
    }

    private fun bindLibraryChip(includeId: Int, label: String, iconRes: Int, kind: LibraryKind?) {
        bindPill(includeId, label, iconRes, selected = libraryFilter == kind) {
            libraryFilter = kind
            refreshLibrary()
        }
    }

    /** Applies a label, icon and selected state to one of the repeated `item_settings_tab` includes. */
    private fun bindPill(includeId: Int, label: String, iconRes: Int, selected: Boolean, onClick: () -> Unit) {
        val pill = findViewById<View>(includeId) ?: return
        pill.findViewById<TextView>(R.id.tv_tab_label)?.text = label
        pill.findViewById<ImageView>(R.id.iv_tab_icon)?.apply {
            setImageResource(iconRes)
            visibility = View.VISIBLE
        }
        paintPill(pill, selected)
        pill.setOnClickListener {
            XCoreMotion.applyButtonTouchEffect(it)
            onClick()
        }
    }

    /**
     * Selection is set from the render pass long after the pill is bound, so the rule has to be
     * repainted there too - the tabs carry no fill of their own.
     */
    private fun paintPill(pill: View, selected: Boolean) {
        val tint = color(if (selected) R.color.mc_white else R.color.mc_dim)
        pill.findViewById<TextView>(R.id.tv_tab_label)?.setTextColor(tint)
        pill.findViewById<ImageView>(R.id.iv_tab_icon)?.imageTintList = ColorStateList.valueOf(tint)
        pill.findViewById<View>(R.id.tab_rule)?.visibility = if (selected) View.VISIBLE else View.INVISIBLE
        pill.isSelected = selected
    }

    private fun paintPill(includeId: Int, selected: Boolean) {
        findViewById<View>(includeId)?.let { pill -> paintPill(pill, selected) }
    }

    private fun refreshLibrary() {
        graph.run({
            val layout = graph.storageLayout()
                ?: return@run XCoreResult.Failure(
                    XCoreError.Storage("no storage layout is prepared; the engine has not probed one"),
                )
            val instance = instances.firstOrNull { candidate -> candidate.id == selectedId }
                ?: return@run XCoreResult.Success(LibrarySnapshot(emptyList(), getString(R.string.library_scope_missing), null))
            val base = graph.paths.instanceDirectory(layout, instance.id)
            val root = base.valueOrNull()
                ?: return@run XCoreResult.Success(
                    LibrarySnapshot(emptyList(), instance.name, base.errorOrNull()?.format() ?: "instance folder not resolved"),
                )
            val entries = LibraryKind.entries.flatMap { kind ->
                val folder = File(root, kind.folder)
                folder.listFiles().orEmpty().mapNotNull { file ->
                    if (!file.name.contains(libraryQuery, ignoreCase = true)) return@mapNotNull null
                    val children: Array<File> =
                        if (file.isDirectory) file.listFiles() ?: emptyArray() else emptyArray()
                    LibraryEntry(
                        kind = kind,
                        name = file.name,
                        path = file.absolutePath,
                        bytes = if (file.isDirectory) children.sumOf { child -> child.length() } else file.length(),
                        items = children.size,
                        modified = file.lastModified(),
                        directory = file.isDirectory,
                    )
                }
            }.sortedByDescending { entry -> entry.modified }
            XCoreResult.Success(LibrarySnapshot(entries, instance.name, null))
        }) { outcome ->
            val snapshot = outcome.valueOrNull()
            val problem = findViewById<TextView>(R.id.tv_library_problem)
            if (snapshot == null) {
                problem?.visibility = View.VISIBLE
                problem?.text = outcome.errorOrNull()?.format() ?: "the instance folder could not be read"
                libraryEntries = emptyList()
            } else {
                problem?.visibility = if (snapshot.problem == null) View.GONE else View.VISIBLE
                problem?.text = snapshot.problem.orEmpty()
                libraryEntries = snapshot.entries
                findViewById<TextView>(R.id.tv_library_scope)?.text =
                    getString(R.string.library_scope, snapshot.scope)
            }
            renderLibrary()
        }
    }

    private fun renderLibrary() {
        val container = findViewById<LinearLayout>(R.id.container_library) ?: return
        val visible = libraryEntries.filter { entry -> libraryFilter == null || entry.kind == libraryFilter }
        paintPill(R.id.chip_library_all, libraryFilter == null)
        LibraryKind.entries.forEach { kind ->
            paintPill(kind.chipId, libraryFilter == kind)
        }
        container.removeAllViews()
        visible.forEachIndexed { index, entry ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_pack, container, false)
            XCoreMotion.applyCardTouchEffect(row)
            XCoreMotion.animateCardItemEnter(row, index * 35L)
            row.findViewById<ImageView>(R.id.iv_pack_icon)?.apply {
                setImageResource(entry.kind.icon)
                imageTintList = ColorStateList.valueOf(color(entry.kind.colorRes))
            }
            row.findViewById<TextView>(R.id.tv_pack_title)?.text = entry.name
            row.findViewById<TextView>(R.id.tv_pack_meta)?.text = if (entry.directory) {
                val count = if (entry.items == 1) getString(R.string.library_item_one)
                    else getString(R.string.library_item_many, entry.items)
                getString(R.string.library_entry_dir, count, formatTimestamp(entry.modified))
            } else {
                getString(R.string.library_entry_detail, formatBytes(entry.bytes), formatTimestamp(entry.modified))
            }
            row.findViewById<TextView>(R.id.tv_pack_kind)?.text = getString(entry.kind.labelRes)
            row.setOnClickListener {
                showInfo(
                    title = entry.name,
                    rows = listOf(
                        "kind" to getString(entry.kind.labelRes),
                        "path" to entry.path,
                        "size" to formatBytes(entry.bytes),
                        "items" to entry.items.toString(),
                        "modified" to formatTimestamp(entry.modified),
                    ),
                    note = "Counted from the filesystem at the last refresh. X-Core does not open or convert this format.",
                )
            }
            container.addView(row)
        }
        val empty = findViewById<View>(R.id.container_library_empty)
        empty?.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.tv_library_count)?.text =
            getString(R.string.library_count, visible.size, libraryEntries.size)
    }

    // ---------------------------------------------------------------- stats

    private fun bindStatsScreen() {
        findViewById<View>(R.id.btn_stats_refresh)?.let { button ->
            XCoreMotion.applyButtonTouchEffect(button)
            button.setOnClickListener { refreshStats() }
        }
    }

    private fun refreshStats() {
        graph.run({
            val listed = graph.repositories.launches.recent(limit = 240)
            val stored = graph.repositories.launches.count()
            XCoreResult.Success(
                listed.valueOrNull()?.let { records -> records to (stored.valueOrNull() ?: records.size.toLong()) },
            )
        }) { outcome ->
            val pair = outcome.valueOrNull()
            val problem = findViewById<TextView>(R.id.tv_stats_problem)
            problem?.visibility = if (pair == null) View.VISIBLE else View.GONE
            problem?.text = outcome.errorOrNull()?.format() ?: "the launch history could not be read"
            renderStats(pair?.first.orEmpty(), pair?.second)
        }
    }

    private fun renderStats(records: List<XCoreLaunchRecord>, storedRows: Long?) {
        val closed = records.mapNotNull { record ->
            record.endedAtMillis?.let { ended -> (ended - record.startedAtMillis).coerceAtLeast(0L) to record }
        }
        val totalMillis = closed.sumOf { (duration, _) -> duration }
        val failures = records.count { record -> record.errorKind != null || (record.exitCode != null && record.exitCode != 0) }
        val open = records.count { record -> record.endedAtMillis == null }
        val longest = closed.maxOfOrNull { (duration, _) -> duration } ?: 0L
        val average = if (closed.isEmpty()) 0L else totalMillis / closed.size

        findViewById<TextView>(R.id.tv_stats_scope)?.text = storedRows?.let { rows ->
            getString(R.string.stats_scope, rows)
        } ?: getString(R.string.stats_scope_unknown)

        XCoreMotion.animateCountUp(
            findViewById(R.id.tv_stats_hours) ?: return,
            totalMillis / 3_600_000L,
        )
        findViewById<TextView>(R.id.tv_stats_minutes)?.text =
            getString(R.string.stats_minutes_detail, totalMillis / 60_000L)

        val newest = records.maxByOrNull { record -> record.startedAtMillis }
        findViewById<TextView>(R.id.tv_stats_last)?.text = newest?.let { formatTimestamp(it.startedAtMillis) }
            ?: getString(R.string.stats_no_session)
        findViewById<TextView>(R.id.tv_stats_last_detail)?.text = newest?.let { record ->
            val closedText = when {
                record.endedAtMillis == null -> "still open"
                record.succeeded -> "closed cleanly"
                else -> "exit ${record.exitCode ?: record.errorKind?.name?.lowercase() ?: "?"}"
            }
            "${record.versionId} · $closedText"
        } ?: getString(R.string.stats_empty)

        val topVersion = records.groupingBy { record -> record.versionId }
            .eachCount()
            .maxByOrNull { (_, count) -> count }
            ?.let { (version, count) -> "$version · $count" }

        renderStatTiles(
            listOf(
                getString(R.string.stats_launches) to records.size.toString(),
                getString(R.string.stats_sessions_closed) to closed.size.toString(),
                getString(R.string.stats_sessions_open) to open.toString(),
                getString(R.string.stats_failures) to failures.toString(),
                getString(R.string.stats_average) to formatDuration(average),
                getString(R.string.stats_longest) to formatDuration(longest),
                getString(R.string.stats_top_version) to (topVersion ?: "-"),
                getString(R.string.stats_records) to (storedRows?.toString() ?: "-"),
            ),
        )
        renderWeekChart(records)
        renderLaunchRows(records)
    }

    private fun renderStatTiles(values: List<Pair<String, String>>) {
        val parent = findViewById<LinearLayout>(R.id.container_stat_tiles) ?: return
        parent.removeAllViews()
        values.chunked(4).forEachIndexed { rowIndex, row ->
            val line = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                orientation = LinearLayout.HORIZONTAL
                setBaselineAligned(false)
            }
            row.forEach { (label, value) ->
                val tile = LayoutInflater.from(this).inflate(R.layout.item_stat_tile, line, false)
                tile.findViewById<TextView>(R.id.tv_tile_label)?.text = label
                tile.findViewById<TextView>(R.id.tv_tile_value)?.apply {
                    text = value
                    setTextColor(color(currentHue.colorRes))
                }
                line.addView(tile)
            }
            // A short final row still needs the weight of the missing columns to keep the grid square.
            repeat(4 - row.size) {
                val spacer = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                }
                line.addView(spacer)
            }
            parent.addView(line)
            XCoreMotion.animateCardItemEnter(line, rowIndex * 60L)
        }
    }

    private fun renderWeekChart(records: List<XCoreLaunchRecord>) {
        val chart = findViewById<LinearLayout>(R.id.chart_week) ?: return
        chart.removeAllViews()
        val dayMillis = 86_400_000L
        val calendar = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val todayStart = calendar.timeInMillis
        val buckets = LongArray(7)
        records.forEach { record ->
            val ended = record.endedAtMillis ?: return@forEach
            val daysBack = ((todayStart - record.startedAtMillis) / dayMillis).toInt()
            if (daysBack in 0..6) buckets[6 - daysBack] += (ended - record.startedAtMillis).coerceAtLeast(0L)
        }
        val peak = buckets.maxOrNull() ?: 0L
        findViewById<TextView>(R.id.tv_stats_week_peak)?.text = getString(
            R.string.stats_week_peak,
            if (peak <= 0L) getString(R.string.stats_day_none) else formatDuration(peak),
        )
        val barAreaPx = (resources.getDimensionPixelSize(R.dimen.chart_height) - dp(46)).coerceAtLeast(dp(24))
        val weekday = SimpleDateFormat("EEE", Locale.getDefault())
        for (index in 0..6) {
            val column = LayoutInflater.from(this).inflate(R.layout.item_stat_bar, chart, false)
            val millis = buckets[index]
            val bar = column.findViewById<View>(R.id.bar_column)
            bar?.setBackgroundResource(currentHue.statBarRes)
            val ratio = if (peak > 0L) millis.toFloat() / peak.toFloat() else 0f
            bar?.layoutParams = bar.layoutParams.apply {
                height = if (millis > 0L) (ratio * barAreaPx).toInt().coerceAtLeast(dp(4)) else dp(3)
            }
            column.findViewById<TextView>(R.id.tv_bar_value)?.apply {
                setTextColor(color(currentHue.colorRes))
                text = if (millis > 0L) "${millis / 60_000L}m" else ""
            }
            column.findViewById<TextView>(R.id.tv_bar_label)?.text =
                weekday.format(Date(todayStart - (6 - index) * dayMillis))
            chart.addView(column)
            bar?.requestLayout()
            XCoreMotion.animateBarGrow(bar ?: column, index * 55L)
        }
    }

    private fun renderLaunchRows(records: List<XCoreLaunchRecord>) {
        val container = findViewById<LinearLayout>(R.id.container_launches) ?: return
        container.removeAllViews()
        val newest = records.sortedByDescending { record -> record.startedAtMillis }.take(12)
        newest.forEachIndexed { index, record ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_launch, container, false)
            XCoreMotion.animateCardItemEnter(row, index * 30L)
            row.findViewById<TextView>(R.id.tv_launch_when)?.text = formatTimestamp(record.startedAtMillis)
            row.findViewById<TextView>(R.id.tv_launch_detail)?.text = when {
                record.endedAtMillis == null -> getString(R.string.stats_open_row, record.versionId)
                record.errorKind != null -> getString(
                    R.string.stats_failed_row,
                    record.versionId,
                    record.errorKind?.name?.lowercase().orEmpty(),
                )
                else -> record.versionId + (record.exitCode?.let { " · exit $it" } ?: "")
            }
            row.findViewById<TextView>(R.id.tv_launch_duration)?.text = record.endedAtMillis
                ?.let { ended -> formatDuration((ended - record.startedAtMillis).coerceAtLeast(0L)) }
                ?: "-"
            row.findViewById<View>(R.id.mark_launch_state)?.setBackgroundColor(
                when {
                    record.errorKind != null -> color(R.color.xcore_danger)
                    record.endedAtMillis == null -> color(R.color.xcore_text_muted)
                    else -> color(R.color.xcore_accent)
                },
            )
            container.addView(row)
        }
        findViewById<View>(R.id.tv_launches_empty)?.visibility = if (newest.isEmpty()) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------- logs

    private fun bindLogsScreen() {
        findViewById<View>(R.id.btn_logs_refresh)?.let { button ->
            XCoreMotion.applyButtonTouchEffect(button)
            button.setOnClickListener { refreshLogs() }
        }
        findViewById<View>(R.id.btn_logs_clear)?.let { button ->
            XCoreMotion.applyButtonTouchEffect(button)
            button.setOnClickListener {
                graph.logger.clear()
                refreshLogs()
                notify(getString(R.string.logs_cleared, graph.events.recent(limit = 500).size))
            }
        }
        bindLogChip(R.id.chip_log_all, getString(R.string.logs_filter_all), null)
        bindLogChip(R.id.chip_log_debug, getString(R.string.logs_filter_debug), XCoreLogLevel.DEBUG)
        bindLogChip(R.id.chip_log_info, getString(R.string.logs_filter_info), XCoreLogLevel.INFO)
        bindLogChip(R.id.chip_log_warn, getString(R.string.logs_filter_warn), XCoreLogLevel.WARN)
        bindLogChip(R.id.chip_log_error, getString(R.string.logs_filter_error), XCoreLogLevel.ERROR)
    }

    private fun bindLogChip(includeId: Int, label: String, level: XCoreLogLevel?) {
        bindPill(includeId, label, R.drawable.ic_terminal, selected = logFilter == level) {
            logFilter = level
            refreshLogs()
        }
    }

    private fun refreshLogs() {
        graph.refreshEvents()
        val records = graph.logger.snapshot().reversed()
        val visible = records.filter { record -> logFilter?.accepts(record.level) ?: true }
        val events = graph.recentEvents.value.reversed().filter { event ->
            logFilter == null || (logFilter == XCoreLogLevel.ERROR && event is XCoreEvent.ErrorRaised)
        }
        val container = findViewById<LinearLayout>(R.id.container_logs) ?: return
        container.removeAllViews()
        visible.take(300).forEach { record -> container.addView(logLine(record)) }
        if (events.isNotEmpty()) {
            container.addView(plainDialogLine(getString(R.string.logs_events_header)))
            events.forEach { event -> container.addView(eventLine(event)) }
        }
        logChips.forEach { (includeId, level) ->
            paintPill(includeId, logFilter == level)
        }
        findViewById<TextView>(R.id.tv_logs_summary)?.text =
            getString(
                R.string.logs_summary,
                records.size,
                graph.logger.droppedCount,
                logFilter?.name?.lowercase() ?: "all",
            )
        findViewById<View>(R.id.tv_logs_empty)?.visibility =
            if (visible.isEmpty() && events.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun logLine(record: XCoreLogRecord): View {
        val line = LayoutInflater.from(this).inflate(R.layout.item_log_line, findViewById(R.id.container_logs), false)
        line.findViewById<TextView>(R.id.tv_log_time)?.text = clockFormat.format(Date(record.timestampMillis))
        line.findViewById<TextView>(R.id.tv_log_level)?.apply {
            text = record.level.name.lowercase()
            setTextColor(
                when (record.level) {
                    XCoreLogLevel.ERROR, XCoreLogLevel.NONE -> color(R.color.xcore_danger)
                    XCoreLogLevel.WARN -> color(R.color.xcore_amber)
                    XCoreLogLevel.INFO -> color(R.color.xcore_blue)
                    else -> color(R.color.xcore_text_muted)
                },
            )
        }
        line.findViewById<TextView>(R.id.tv_log_message)?.text =
            record.message + (record.error?.let { " <- ${it.message}" } ?: "")
        return line
    }

    private fun eventLine(event: XCoreEvent): View {
        val line = LayoutInflater.from(this).inflate(R.layout.item_log_line, findViewById(R.id.container_logs), false)
        line.findViewById<TextView>(R.id.tv_log_time)?.text = clockFormat.format(Date(event.timestampMillis))
        line.findViewById<TextView>(R.id.tv_log_level)?.apply {
            text = "event"
            setTextColor(color(R.color.xcore_violet))
        }
        line.findViewById<TextView>(R.id.tv_log_message)?.text =
            event::class.simpleName + (event as? XCoreEvent.ErrorRaised)?.let { raised -> ": ${raised.error.format() }" }.orEmpty()
        return line
    }

    // ---------------------------------------------------------------- controls

    private fun bindControlsScreen() {
        findViewById<View>(R.id.btn_control_add_button)?.let { button ->
            XCoreMotion.applyCardTouchEffect(button)
            button.setOnClickListener { showCapability(XCoreCapability.NATIVE_BRIDGE) }
        }
        findViewById<View>(R.id.btn_control_add_stick)?.let { button ->
            XCoreMotion.applyCardTouchEffect(button)
            button.setOnClickListener { showCapability(XCoreCapability.NATIVE_BRIDGE) }
        }
        findViewById<View>(R.id.btn_control_add_fps)?.let { button ->
            XCoreMotion.applyCardTouchEffect(button)
            button.setOnClickListener { showCapability(XCoreCapability.GRAPHICS_SURFACE) }
        }
        findViewById<View>(R.id.btn_control_profiles)?.let { button ->
            XCoreMotion.applyCardTouchEffect(button)
            button.setOnClickListener { openScreen(LauncherTab.FILES) }
        }
    }

    private fun refreshControls() {
        val bridge = graph.services.contains(XCoreCapability.NATIVE_BRIDGE.serviceId)
        val surface = graph.services.contains(XCoreCapability.GRAPHICS_SURFACE.serviceId)
        findViewById<TextView>(R.id.tv_controls_state)?.text = getString(
            if (bridge && surface) R.string.controls_state_ready else R.string.controls_state_blocked,
        )
        findViewById<TextView>(R.id.tv_controls_note)?.text = buildString {
            append(getString(R.string.controls_note_state, onOff(bridge), onOff(surface)))
            append('\n')
            append(getString(R.string.controls_note_phase, XCoreCapability.NATIVE_BRIDGE.plannedPhase))
        }
        val layoutsDir = controlsLayoutDirectory()
        findViewById<TextView>(R.id.tv_controls_path)?.text = getString(
            R.string.controls_path_label,
            layoutsDir?.absolutePath ?: getString(R.string.stats_no_session),
        )
        val container = findViewById<LinearLayout>(R.id.container_control_layouts) ?: return
        container.removeAllViews()
        val files = layoutsDir?.listFiles()?.filter { file -> file.isFile }.orEmpty().sortedBy { file -> file.name }
        files.forEachIndexed { index, file ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_pack, container, false)
            XCoreMotion.applyCardTouchEffect(row)
            XCoreMotion.animateCardItemEnter(row, index * 35L)
            row.findViewById<TextView>(R.id.tv_pack_title)?.text = file.name
            row.findViewById<TextView>(R.id.tv_pack_meta)?.text =
                getString(R.string.library_entry_detail, formatBytes(file.length()), formatTimestamp(file.lastModified()))
            row.findViewById<TextView>(R.id.tv_pack_kind)?.text = getString(R.string.kind_layout)
            row.setOnClickListener { showInfo(title = file.name, rows = listOf("path" to file.absolutePath)) }
            container.addView(row)
        }
        findViewById<View>(R.id.tv_control_layouts_empty)?.visibility =
            if (files.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun controlsLayoutDirectory(): File? {
        val layout = graph.storageLayout() ?: return null
        val instance = instances.firstOrNull { candidate -> candidate.id == selectedId } ?: return null
        val base = graph.paths.instanceDirectory(layout, instance.id).valueOrNull() ?: return null
        return File(base, "xcore/layouts")
    }

    private fun onOff(value: Boolean): String = if (value) "installed" else "not installed"

    // ---------------------------------------------------------------- settings

    private fun bindSettingsTabs() {
        findViewById<View>(R.id.tab_general)?.setOnClickListener { showSettingsGroup(SettingsGroup.GENERAL) }
        findViewById<View>(R.id.tab_accounts)?.setOnClickListener { showSettingsGroup(SettingsGroup.GAME) }
        findViewById<View>(R.id.tab_about)?.setOnClickListener { showSettingsGroup(SettingsGroup.ABOUT) }
        showSettingsGroup(settingsGroup)
    }

    private fun showSettingsGroup(group: SettingsGroup) {
        settingsGroup = group
        findViewById<View>(R.id.group_general)?.visibility = if (group == SettingsGroup.GENERAL) View.VISIBLE else View.GONE
        findViewById<View>(R.id.group_accounts)?.visibility = if (group == SettingsGroup.GAME) View.VISIBLE else View.GONE
        findViewById<View>(R.id.group_about)?.visibility = if (group == SettingsGroup.ABOUT) View.VISIBLE else View.GONE

        val activeColor = Color.parseColor("#FFFFFF")
        val inactiveColor = Color.parseColor("#AAAAAA")

        findViewById<TextView>(R.id.tv_tab_general)?.setTextColor(if (group == SettingsGroup.GENERAL) activeColor else inactiveColor)
        findViewById<TextView>(R.id.tv_tab_accounts)?.setTextColor(if (group == SettingsGroup.GAME) activeColor else inactiveColor)
        findViewById<TextView>(R.id.tv_tab_about)?.setTextColor(if (group == SettingsGroup.ABOUT) activeColor else inactiveColor)

        findViewById<View>(R.id.v_indicator_general)?.visibility = if (group == SettingsGroup.GENERAL) View.VISIBLE else View.INVISIBLE
        findViewById<View>(R.id.v_indicator_accounts)?.visibility = if (group == SettingsGroup.GAME) View.VISIBLE else View.INVISIBLE
        findViewById<View>(R.id.v_indicator_about)?.visibility = if (group == SettingsGroup.ABOUT) View.VISIBLE else View.INVISIBLE
    }

    private fun bindSettingsRows() {
        findViewById<View>(R.id.btn_language_dropdown)?.setOnClickListener { chooseLanguage() }
        findViewById<View>(R.id.btn_whats_new)?.setOnClickListener {
            showInfo(title = "What's New", rows = listOf("Launcher Version" to installedVersionName(), "Status" to "Up to date"))
        }
        findViewById<View>(R.id.btn_report_bug)?.setOnClickListener {
            showInfo(title = "Report a Launcher Bug", rows = listOf("Tracker" to "https://bugs.mojang.com"))
        }
        findViewById<View>(R.id.btn_third_party_licenses)?.setOnClickListener {
            showInfo(title = "Third-party licenses", rows = listOf("Notice" to "Open source licenses under Apache 2.0 and MIT."))
        }
    }

    /** Re-reads the values the settings rows display, without re-attaching listeners. */
    private fun renderSettings() {
        settingsRowHue = settingsGroup.colorRes
        bindSettingsRows()
        showSettingsGroup(settingsGroup)
    }

    private fun installedVersionName(): String =
        packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"

    private fun loadUiSettings() {
        graph.run({ graph.preferences.uiSettingsNow().asXCoreSuccess() }) { outcome ->
            outcome.valueOrNull()?.let { stored ->
                uiSettings = stored
                XCoreMotion.enabled = stored.animationsEnabled
            }
            if (tab == LauncherTab.SETTINGS) renderSettings()
        }
    }

    private fun setAnimationsEnabled(enabled: Boolean) {
        XCoreMotion.enabled = enabled
        val next = uiSettings.copy(animationsEnabled = enabled)
        graph.run({ graph.preferences.setUiSettings(next) }) { outcome ->
            outcome.errorOrNull()?.let { problem ->
                XCoreMotion.enabled = uiSettings.animationsEnabled
                renderSettings()
                showInfo(title = "Could not store that choice", rows = listOf("error" to problem.format()))
                return@run
            }
            uiSettings = next
            notify(if (enabled) "Animations on" else "Animations off")
        }
    }

    private fun chooseLanguage() {
        showChoice(
            title = "Language",
            note = getString(R.string.choice_language_note),
            options = listOf("English" to { }),
        )
    }

    private fun chooseTheme() {
        showChoice(
            title = "Theme",
            note = getString(R.string.choice_theme_note),
            options = listOf("Dark" to { }),
        )
    }

    private fun chooseRuntimeChannel() {
        showChoice(
            title = "Runtime channel",
            note = getString(R.string.choice_runtime_channel_note),
            options = XCoreRuntimeChannel.entries.map { channel ->
                channel.name.lowercase() to {
                    saveConfig { current -> current.copy(runtimeChannel = channel) }
                }
            },
        )
    }

    private fun chooseLogLevel() {
        showChoice(
            title = "Log level",
            note = getString(R.string.choice_log_level_note),
            options = XCoreLogLevel.entries.filter { level -> level != XCoreLogLevel.NONE }.map { level ->
                level.name.lowercase() to {
                    saveConfig { current -> current.copy(logLevel = level) }
                }
            },
        )
    }

    private fun chooseHistoryWindow() {
        showChoice(
            title = "Launch history",
            note = getString(R.string.choice_history_note),
            options = listOf(7, 30, 90, 365).map { days ->
                "$days days" to {
                    saveConfig { current -> current.copy(keepLaunchHistoryDays = days) }
                }
            },
        )
    }

    private fun showChoice(title: String, note: String, options: List<Pair<String, () -> Unit>>) {
        val sheet = inflateDialog(R.layout.dialog_info)
        sheet.findViewById<TextView>(R.id.tv_dialog_title).text = title
        val container = sheet.findViewById<LinearLayout>(R.id.container_dialog_rows)
        val dialog = dialogFor(sheet)
        options.forEach { (label, action) ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_instance_picker, container, false)
            XCoreMotion.applyCardTouchEffect(row)
            row.findViewById<TextView>(R.id.tv_picker_name)?.text = label
            row.findViewById<TextView>(R.id.tv_picker_detail)?.text = ""
            row.setOnClickListener {
                dialog.dismiss()
                action()
            }
            container.addView(row)
        }
        sheet.findViewById<TextView>(R.id.tv_dialog_note)?.apply {
            text = note
            visibility = View.VISIBLE
        }
        val actions = sheet.findViewById<LinearLayout>(R.id.container_dialog_actions)
        addDialogAction(actions, dialog, getString(R.string.action_close)) { }
        dialog.show()
    }

    private fun saveConfig(transform: (XCoreConfig) -> XCoreConfig) {
        graph.run({ graph.configUseCases.update(transform) }) { outcome ->
            outcome.errorOrNull()?.let { problem ->
                showInfo(
                    title = "That value was not saved",
                    rows = listOf("error" to problem.format()),
                    note = "Validation rejects out-of-range values rather than clamping them, so the stored config is unchanged.",
                )
                return@run
            }
            graph.run({ graph.engine.reloadConfig() }) { renderSettings() }
        }
    }

    private fun notify(text: String) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun formatTimestamp(millis: Long): String = stampFormat.format(Date(millis))

    private fun formatDuration(millis: Long): String = when {
        millis >= 3_600_000L -> String.format(Locale.US, "%.1f h", millis / 3_600_000.0)
        millis >= 60_000L -> "${millis / 60_000L} min"
        else -> "${millis / 1_000L} s"
    }

    // ---------------------------------------------------------------- shell

    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.root)
        val homeContent = findViewById<View>(R.id.home_root) ?: root
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            homeContent.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            screenContainer.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        root.requestApplyInsets()
    }

    private fun openScreen(target: LauncherTab) {
        applyTab(target)
        if (onHome) {
            XCoreMotion.animatePageExit(homeScreen)
            XCoreMotion.animatePageEnter(screenContainer)
            onHome = false
        }
    }

    private fun goHome() {
        if (onHome) return
        XCoreMotion.animatePageExit(screenContainer)
        XCoreMotion.animatePageEnter(homeScreen)
        onHome = true
        paintPlayHeader()
        renderInstances()
    }

    /** The Play page owns the header and the rail highlight the same way a destination does. */
    private fun paintPlayHeader() {
        paintRail(null)
        subLogo?.setImageResource(R.drawable.ic_cube)
        subLogo?.imageTintList = ColorStateList.valueOf(color(R.color.mc_green))
        subTitle?.setText(R.string.nav_play)
        subSubtitle?.setText(R.string.subtitle_home)
        renderRailStatus()
    }

    /**
     * The colour identity of one screen. The launcher keeps a single green accent, so these
     * resolve to the same family; the indirection stays because rows and bars are painted from
     * it at bind time.
     */
    private data class TabHue(
        val colorRes: Int,
        val statBarRes: Int,
    )

    private fun hueFor(target: LauncherTab): TabHue = when (target) {
        LauncherTab.INSTANCES -> TabHue(R.color.xcore_accent, R.drawable.bg_stat_bar_green)
        LauncherTab.FILES -> TabHue(R.color.xcore_orange, R.drawable.bg_stat_bar_orange)
        LauncherTab.LIBRARY -> TabHue(R.color.xcore_blue, R.drawable.bg_stat_bar_blue)
        LauncherTab.STATS -> TabHue(R.color.xcore_violet, R.drawable.bg_stat_bar_violet)
        LauncherTab.LOGS -> TabHue(R.color.xcore_amber, R.drawable.bg_stat_bar_amber)
        LauncherTab.CONTROLS -> TabHue(R.color.xcore_teal, R.drawable.bg_stat_bar_teal)
        LauncherTab.SETTINGS -> TabHue(R.color.xcore_blue, R.drawable.bg_stat_bar_blue)
    }

    /** Hue of the screen on screen right now; rows built while a screen is open read from it. */
    private var currentHue: TabHue = hueFor(LauncherTab.INSTANCES)

    private fun applyTab(target: LauncherTab) {
        tab = target
        val screens = linkedMapOf(
            LauncherTab.INSTANCES to screenInstances,
            LauncherTab.FILES to screenFiles,
            LauncherTab.LIBRARY to screenLibrary,
            LauncherTab.STATS to screenStats,
            LauncherTab.LOGS to screenLogs,
            LauncherTab.CONTROLS to screenControls,
            LauncherTab.SETTINGS to screenSettings,
        )
        screens.forEach { (candidate, view) ->
            view.visibility = if (candidate == target) View.VISIBLE else View.GONE
        }
        screens[target]?.let(XCoreMotion::animatePageEnter)

        val hue = hueFor(target)
        currentHue = hue
        subLogo?.setImageResource(if (target == LauncherTab.SETTINGS) R.drawable.ic_xcore_logo else R.drawable.ic_cube)
        subLogo?.imageTintList =
            if (target == LauncherTab.SETTINGS) null else ColorStateList.valueOf(color(hue.colorRes))
        subTitle?.text = getString(labelFor(target))
        subSubtitle?.text = getString(subtitleFor(target))
        paintRail(target)
        renderRailStatus()
        when (target) {
            LauncherTab.FILES -> refreshFiles()
            LauncherTab.LIBRARY -> refreshLibrary()
            LauncherTab.STATS -> refreshStats()
            LauncherTab.LOGS -> refreshLogs()
            LauncherTab.CONTROLS -> refreshControls()
            LauncherTab.SETTINGS -> renderSettings()
            LauncherTab.INSTANCES -> Unit
        }
    }

    private fun labelFor(target: LauncherTab): Int = when (target) {
        LauncherTab.INSTANCES -> R.string.section_my_instances
        LauncherTab.FILES -> R.string.nav_files
        LauncherTab.LIBRARY -> R.string.library_title
        LauncherTab.STATS -> R.string.stats_title
        LauncherTab.LOGS -> R.string.logs_title
        LauncherTab.CONTROLS -> R.string.controls_title
        LauncherTab.SETTINGS -> R.string.nav_settings
    }

    private fun subtitleFor(target: LauncherTab): Int = when (target) {
        LauncherTab.INSTANCES -> R.string.subtitle_instances
        LauncherTab.FILES -> R.string.subtitle_files
        LauncherTab.LIBRARY -> R.string.library_sub
        LauncherTab.STATS -> R.string.stats_sub
        LauncherTab.LOGS -> R.string.logs_sub
        LauncherTab.CONTROLS -> R.string.controls_sub
        LauncherTab.SETTINGS -> R.string.subtitle_settings
    }

    private fun renderPlayLabel() {
        if (busy) return
        playLabel.text = when {
            selectedId == null -> getString(R.string.home_play)
            states[instances.firstOrNull { candidate -> candidate.id == selectedId }?.versionId]?.isReady == true ->
                getString(R.string.home_play)

            else -> "INSTALL & PLAY"
        }
        playRoot.alpha = if (selectedId == null) 0.45f else 1f
    }

    private fun bindSettingCard(
        includeId: Int,
        title: String,
        subtitle: String,
        iconRes: Int,
        valueText: String? = null,
        showSwitch: Boolean = false,
        switchChecked: Boolean = false,
        showChevron: Boolean = false,
        onToggle: ((Boolean) -> Unit)? = null,
        onClick: () -> Unit = { },
    ) {
        val card = findViewById<View>(includeId) ?: return
        XCoreMotion.applyCardTouchEffect(card)
        card.findViewById<TextView>(R.id.tv_setting_title)?.text = title
        card.findViewById<TextView>(R.id.tv_setting_subtitle)?.text = subtitle
        card.findViewById<ImageView>(R.id.iv_setting_icon)?.apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(
                color(if (tab == LauncherTab.SETTINGS) settingsRowHue else currentHue.colorRes),
            )
        }

        val valView = card.findViewById<TextView>(R.id.row_value)
        val switchView = card.findViewById<Switch>(R.id.row_switch)
        val chevronView = card.findViewById<ImageView>(R.id.row_chevron)

        if (valueText != null && valView != null) {
            valView.visibility = View.VISIBLE
            valView.text = valueText
        } else {
            valView?.visibility = View.GONE
        }

        if (showSwitch && switchView != null) {
            switchView.visibility = View.VISIBLE
            switchView.isChecked = switchChecked
            // The switch itself is not clickable, so tapping anywhere on the row flips it here.
            card.setOnClickListener {
                val next = !switchView.isChecked
                switchView.isChecked = next
                onToggle?.invoke(next)
            }
        } else {
            switchView?.visibility = View.GONE
            if (showChevron && chevronView != null) {
                chevronView.visibility = View.VISIBLE
            } else {
                chevronView?.visibility = View.GONE
            }
            card.setOnClickListener { onClick() }
        }
    }

    private fun addChip(
        parent: LinearLayout,
        label: String,
        fill: Int,
        textColor: Int,
    ) {
        val chip = TextView(this).apply {
            text = label
            setTypeface(null, android.graphics.Typeface.BOLD)
            textSize = 11f
            setPadding(20, 8, 20, 8)
            background = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_chip)
            backgroundTintList = ColorStateList.valueOf(fill)
            setTextColor(textColor)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = 10
            }
        }
        parent.addView(chip)
    }

    private fun inflateDialog(layoutRes: Int): View =
        LayoutInflater.from(this).inflate(layoutRes, findViewById(R.id.container_frame), false)

    private fun dialogFor(content: View): Dialog = Dialog(this).apply {
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        setContentView(content)
        window?.let { sheet ->
            sheet.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            sheet.setWindowAnimations(R.style.XCoreDialogAnimation)
            val capped = (460 * resources.displayMetrics.density).toInt()
            val widest = (resources.displayMetrics.widthPixels * 0.70f).toInt()
            sheet.setLayout(minOf(capped, widest), ViewGroup.LayoutParams.WRAP_CONTENT)
            sheet.setGravity(Gravity.CENTER)
        }
        setCancelable(true)
        XCoreMotion.animateDialogShow(content)
    }

    private fun showInfo(
        title: String,
        rows: List<Pair<String, String>>,
        note: String? = null,
        actions: List<Pair<String, () -> Unit>> = emptyList(),
    ) {
        val sheet = inflateDialog(R.layout.dialog_info)
        sheet.findViewById<TextView>(R.id.tv_dialog_title).text = title
        val container = sheet.findViewById<LinearLayout>(R.id.container_dialog_rows)
        rows.forEach { (key, value) ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_key_value, container, false)
            row.findViewById<TextView>(R.id.tv_kv_key).text = key
            row.findViewById<TextView>(R.id.tv_kv_value).text = value
            container.addView(row)
        }
        val noteView = sheet.findViewById<TextView>(R.id.tv_dialog_note)
        noteView.text = note.orEmpty()
        noteView.visibility = if (note.isNullOrBlank()) View.GONE else View.VISIBLE
        val actionRow = sheet.findViewById<LinearLayout>(R.id.container_dialog_actions)
        val dialog = dialogFor(sheet)
        (actions.ifEmpty { listOf(getString(R.string.action_close) to { }) }).forEach { (label, action) ->
            addDialogAction(actionRow, dialog, label, action)
        }
        dialog.show()
    }

    private fun addDialogAction(
        parent: LinearLayout,
        dialog: Dialog,
        label: String,
        action: () -> Unit,
    ) {
        val button = TextView(this).apply {
            text = label
            setTypeface(null, android.graphics.Typeface.BOLD)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(20, 24, 20, 24)
            background = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_chip)
            backgroundTintList = ColorStateList.valueOf(color(R.color.xcore_accent_soft))
            setTextColor(color(R.color.xcore_accent))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (parent.childCount > 0) marginStart = 10
            }
            XCoreMotion.applyButtonTouchEffect(this)
            setOnClickListener {
                XCoreMotion.animateDialogDismiss(dialog.window?.decorView ?: parent) {
                    dialog.dismiss()
                    action()
                }
            }
        }
        parent.addView(button)
    }

    private fun color(id: Int): Int = androidx.core.content.ContextCompat.getColor(this, id)

    private enum class LauncherTab { INSTANCES, FILES, LIBRARY, STATS, LOGS, CONTROLS, SETTINGS }

    /** The three folders a player puts content in, read straight from the instance directory. */
    private enum class LibraryKind(
        val folder: String,
        val chipId: Int,
        val labelRes: Int,
        val filterLabel: Int,
        val icon: Int,
        val colorRes: Int,
    ) {
        RESOURCE("resourcepacks", R.id.chip_library_resource, R.string.kind_resource, R.string.filter_resource_packs, R.drawable.ic_layers, R.color.xcore_violet),
        SHADER("shaderpacks", R.id.chip_library_shader, R.string.kind_shader, R.string.filter_shader_packs, R.drawable.ic_palette, R.color.xcore_amber),
        WORLD("saves", R.id.chip_library_world, R.string.kind_world, R.string.filter_worlds, R.drawable.ic_grass_block, R.color.xcore_accent),
    }

    private enum class SettingsGroup(
        val pillId: Int,
        val labelRes: Int,
        val icon: Int,
        val colorRes: Int,
    ) {
        GENERAL(R.id.tab_general, R.string.setting_general_title, R.drawable.ic_gear, R.color.xcore_blue),
        GAME(R.id.tab_accounts, R.string.setting_game_title, R.drawable.ic_cube, R.color.xcore_accent),
        ABOUT(R.id.tab_about, R.string.setting_about_title, R.drawable.ic_info, R.color.xcore_amber),
    }

    private data class LibraryEntry(
        val kind: LibraryKind,
        val name: String,
        val path: String,
        val bytes: Long,
        val items: Int,
        val modified: Long,
        val directory: Boolean,
    )

    private data class LibrarySnapshot(
        val entries: List<LibraryEntry>,
        val scope: String,
        val problem: String?,
    )

    private data class Snapshot(
        val instances: List<XCoreInstance>,
        val states: List<XCoreInstallState>,
        val selectedId: String?,
        val problem: String?,
    )

    private data class StorageRow(
        val name: String,
        val path: String,
        val files: Int,
        val bytes: Long,
        val purpose: String,
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format("%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> String.format("%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000L -> String.format("%.1f kB", bytes / 1_000.0)
    else -> "$bytes B"
}
