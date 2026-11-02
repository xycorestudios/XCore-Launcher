package studio.xycore.xcore

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.launch
import studio.xycore.xcore.core.XCoreConfig
import studio.xycore.xcore.core.XCoreEngine
import studio.xycore.xcore.core.XCoreError
import studio.xycore.xcore.core.XCoreEvent
import studio.xycore.xcore.core.XCoreLogRecord
import studio.xycore.xcore.core.XCoreLogLevel
import studio.xycore.xcore.core.XCoreLogTags
import studio.xycore.xcore.core.XCoreResult
import studio.xycore.xcore.core.XCoreRuntimeChannel
import studio.xycore.xcore.core.XCoreRuntimeState
import studio.xycore.xcore.core.XCoreStorageRoot
import studio.xycore.xcore.core.asXCoreSuccess
import studio.xycore.xcore.core.errorOrNull
import studio.xycore.xcore.core.flatMap
import studio.xycore.xcore.core.valueOrNull
import studio.xycore.xcore.data.XCoreGameDirectory
import studio.xycore.xcore.data.XCoreInstance
import studio.xycore.xcore.data.XCoreInstanceStatus
import studio.xycore.xcore.data.XCoreLaunchRecord
import studio.xycore.xcore.data.XCoreLoaderKind
import studio.xycore.xcore.data.XCoreReleaseType
import studio.xycore.xcore.data.XCoreUiSettings
import studio.xycore.xcore.domain.XCoreCapability
import studio.xycore.xcore.domain.XCorePreflightReport
import studio.xycore.xcore.graphics.XCoreGraphicsReadinessReport
import studio.xycore.xcore.graphics.XCoreGraphicsTarget
import studio.xycore.xcore.installer.XCoreInstallPhase
import studio.xycore.xcore.installer.XCoreInstallState
import studio.xycore.xcore.installer.XCoreInstallSummary
import studio.xycore.xcore.installer.XCoreDistributionTermsGate
import studio.xycore.xcore.installer.XCoreTransferListener
import studio.xycore.xcore.runtime.XCoreJavaMajors
import studio.xycore.xcore.runtime.XCoreJvmArguments
import studio.xycore.xcore.runtime.XCoreLaunchTokens
import studio.xycore.xcore.runtime.XCoreLicensePolicy
import studio.xycore.xcore.runtime.XCoreRuntimeInfo
import studio.xycore.xcore.runtime.XCoreRuntimeSource
import studio.xycore.xcore.ui.XCoreMotion
import studio.xycore.xcore.versions.XCoreManifestEntry
import studio.xycore.xcore.versions.XCoreVersionListing

/**
 * The landscape launcher shell.
 */
class MainActivity : ComponentActivity() {

    private lateinit var graph: XCoreGraph

    /** Android 13 and above ask before an install notice may be seen; the answer never blocks an install. */
    private var installNoticePermission: androidx.activity.result.ActivityResultLauncher<String>? = null

    /** The block face the dialogs and the sign-in plaque are set in, resolved once per session. */
    private var pixelFace: android.graphics.Typeface? = null

    private lateinit var homeScreen: View
    private lateinit var screenContainer: View
    private lateinit var sidebar: View
    private lateinit var productLine: TextView
    private lateinit var playTabs: View
    private lateinit var playRoot: View
    private lateinit var playLabel: TextView
    private lateinit var homeName: TextView
    private lateinit var homeVersion: TextView
    private lateinit var playSub: TextView
    private lateinit var accountNameHome: TextView
    private lateinit var accountNameRail: TextView
    private lateinit var loginRoot: View
    private lateinit var loginName: EditText
    private lateinit var loginPass: EditText
    private lateinit var loginRemember: CheckBox
    private lateinit var loginNote: TextView
    private lateinit var screenInstances: View
    private lateinit var screenFiles: View
    private lateinit var screenSettings: View
    private lateinit var screenLibrary: View
    private lateinit var screenStats: View
    private lateinit var screenLogs: View
    private lateinit var screenControls: View
    private lateinit var screenNews: View
    private lateinit var screenPatch: View
    private lateinit var newsRows: LinearLayout
    private lateinit var newsNote: TextView
    private lateinit var patchRows: LinearLayout
    private lateinit var patchNote: TextView
    private lateinit var instanceList: LinearLayout
    private lateinit var emptyState: LinearLayout
    private lateinit var filesContainer: LinearLayout
    private lateinit var filesProblem: TextView
    private lateinit var introRoot: View
    private lateinit var screenHomeSlides: View
    private lateinit var slideFlipper: ViewFlipper
    private lateinit var slideDots: LinearLayout
    private lateinit var homeUpdatesHeading: TextView
    private lateinit var homeUpdates: LinearLayout

    /** The version dropdown on the home band; only one may be open at a time. */
    private var instanceDropdown: ListPopupWindow? = null

    /** Which slide the flipper is on; the dots follow it. */
    private var slideIndex = 0

    /** The intro holds the screen until it closes, and the sign-in plaque waits behind it. */
    private var introFinished = false
    private var pendingSignIn: XCoreUiSettings? = null

    private var tab = LauncherTab.INSTANCES
    private var onHome = true
    private var railCollapsed = false

    /** The header tab the player is on. `INSTALLATIONS` and `PATCH_NOTES` name screens, `PLAY` is the home page. */
    private var playTab = PlayTab.PLAY
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
    private var settingsCategory = SettingsCategory.RUNTIME
    private var uiSettings = XCoreUiSettings()

    /** One runtime fetch at a time; the transfer listener writes into a panel row. */
    private var runtimeInstallRunning = false

    /** Heap and resolution live in the instance's own argument lines, so both are parsed back out. */
    private val heapPattern = Regex("-Xmx(\\d+)([mMgG])")
    private val widthPattern = Regex("--width(\\s+|=)(\\d+)")
    private val heightPattern = Regex("--height(\\s+|=)(\\d+)")

    /**
     * The sign-in rule: the length the launch tokens already enforce, narrowed to the characters the
     * game accepts in a name. Checked at the plaque so a profile is never stored that the pipeline
     * would reject later.
     */
    private val playerNamePattern = Regex("[A-Za-z0-9_]{1,${XCoreLaunchTokens.MAX_PLAYER_NAME}}")

    private val railLabelIds = listOf(R.id.nav_home, R.id.nav_news, R.id.nav_edition, R.id.nav_more, R.id.nav_settings)

    private val stampFormat = SimpleDateFormat("MMM d · HH:mm", Locale.getDefault())
    private val clockFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val abi: String =
        Build.SUPPORTED_ABIS.firstOrNull { candidate -> "64" in candidate } ?: Build.SUPPORTED_ABIS.firstOrNull()
            ?: "arm64-v8a"

    private val eulaUrl: String = XCoreDistributionTermsGate.EULA_URL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph = (application as XCoreApplication).graph
        installNoticePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            graph.logger.log(
                if (granted) XCoreLogLevel.INFO else XCoreLogLevel.WARN,
                XCoreLogTags.UI,
                if (granted) {
                    "install notice permission granted"
                } else {
                    "install notice permission refused; transfers still run, they simply stay unannounced"
                },
            )
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        homeScreen = findViewById(R.id.screen_home)
        screenContainer = findViewById(R.id.container_frame)
        sidebar = findViewById(R.id.sidebar)
        productLine = findViewById(R.id.tv_product_line)
        playTabs = findViewById(R.id.container_play_tabs)
        playRoot = findViewById(R.id.layout_play_button)
        playLabel = findViewById(R.id.tv_play_label)
        homeName = findViewById(R.id.tv_home_name)
        homeVersion = findViewById(R.id.tv_home_version)
        playSub = findViewById(R.id.tv_play_sub)
        accountNameHome = findViewById(R.id.tv_play_account)
        accountNameRail = findViewById(R.id.tv_account_name)
        loginRoot = findViewById(R.id.login_root)
        loginName = findViewById(R.id.field_login_user)
        loginPass = findViewById(R.id.field_login_pass)
        loginRemember = findViewById(R.id.check_login_remember)
        loginNote = findViewById(R.id.tv_login_note)
        screenInstances = findViewById(R.id.screen_instances)
        screenFiles = findViewById(R.id.screen_files)
        screenSettings = findViewById(R.id.screen_settings)
        screenNews = findViewById(R.id.screen_news)
        screenPatch = findViewById(R.id.screen_patch_notes)
        newsRows = findViewById(R.id.container_news)
        newsNote = findViewById(R.id.tv_news_note)
        patchRows = findViewById(R.id.container_patch_notes)
        patchNote = findViewById(R.id.tv_patch_note)
        instanceList = findViewById(R.id.container_instances_list)
        emptyState = findViewById(R.id.container_empty_state)
        filesContainer = findViewById(R.id.container_files)
        filesProblem = findViewById(R.id.tv_files_problem)
        screenLibrary = findViewById(R.id.screen_library)
        screenStats = findViewById(R.id.screen_stats)
        screenLogs = findViewById(R.id.screen_logs)
        screenControls = findViewById(R.id.screen_controls)
        introRoot = findViewById(R.id.intro_root)
        screenHomeSlides = findViewById(R.id.screen_home_slides)
        slideFlipper = findViewById(R.id.flip_home_slides)
        slideDots = findViewById(R.id.container_slide_dots)
        homeUpdatesHeading = findViewById(R.id.tv_home_updates_title)
        homeUpdates = findViewById(R.id.container_home_updates)
        applySystemBarInsets()

        // Apply global motion & touch animations
        XCoreMotion.applyButtonTouchEffect(playRoot)

        // The block carries the game's bitmap face. A @font reference in the layout's fontFamily
        // attribute needs API 26, and this shell still runs on 24, so the typeface is set here.
        // The hero lockup lives in two includes, so each root is scoped separately.
        ResourcesCompat.getFont(this, R.font.silkscreen_bold)?.let { face ->
            pixelFace = face
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
        findViewById<View>(R.id.btn_rail_collapse)?.let {
            XCoreMotion.applyButtonTouchEffect(it)
            it.setOnClickListener { collapseRail() }
        }

        bindRail()
        bindPlayTabs()
        bindLoginScreen()
        bindHomeSlides()
        bindNewsScreen()
        bindLibraryScreen()
        bindStatsScreen()
        bindLogsScreen()
        bindControlsScreen()
        loadUiSettings()
        showIntro()

        bindSettingsCategories()
        bindHomeScreen()

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
        goHome()
        refreshInstances()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val dropdown = instanceDropdown
                when {
                    introRoot.visibility == View.VISIBLE -> dismissIntro()
                    dropdown?.isShowing == true -> dropdown.dismiss()
                    loginRoot.visibility == View.VISIBLE -> finish()
                    onHome -> finish()
                    else -> goHome()
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
            // The settings panels quote the selected instance, so a fresh list repaints an open panel.
            if (tab == LauncherTab.SETTINGS) renderSettings()
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
        val featuredState = featured?.let { states[it.versionId] }
        homeName.text = featured?.name ?: getString(R.string.instance_title_default)
        homeVersion.text = buildString {
            append(featured?.versionId ?: getString(R.string.instance_version_default))
            if (featured != null) {
                append(" · ")
                append(
                    when {
                        featuredState == null -> getString(R.string.install_not_installed)
                        featuredState.isReady -> getString(R.string.install_ready)
                        featuredState.phase == XCoreInstallPhase.FAILED -> getString(R.string.install_failed)
                        else -> featuredState.phase.name.lowercase()
                    },
                )
            }
        }
        renderPlayLabel()
        if (tab == LauncherTab.HOME) renderHomeUpdates()
    }

    private fun instanceRow(instance: XCoreInstance, selected: Boolean): View {
        val row = LayoutInflater.from(this).inflate(R.layout.item_instance, instanceList, false)
        XCoreMotion.applyCardTouchEffect(row)
        val card = row.findViewById<View>(R.id.card_instance)
        card.setBackgroundResource(if (selected) R.drawable.bg_card_selected else R.drawable.bg_card)
        row.findViewById<TextView>(R.id.tv_instance_title).text = instance.name
        row.findViewById<TextView>(R.id.tv_instance_version).text = instance.versionId
        card.setOnClickListener { selectInstance(instance.id) }
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

    // ---------------------------------------------------------------- play page

    /**
     * The Play page's controls. The bar under the artwork is the whole page: the installation that
     * starts, the button that starts it and the profile it starts as.
     */
    private fun bindHomeScreen() {
        homeScreen.findViewById<View>(R.id.chip_account_home)?.let { target ->
            XCoreMotion.applyCardTouchEffect(target)
            target.setOnClickListener { showAccount() }
        }
    }

    // ---------------------------------------------------------------- home slides

    /**
     * The rail's Home page: the artwork turns by itself, a swipe turns it sooner, and the rows under
     * it are this build's own state rather than a promise list.
     */
    private fun bindHomeSlides() {
        paintSlideDots(0)
        val swipe = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onFling(from: MotionEvent?, to: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val start = from ?: return false
                val dx = to.x - start.x
                if (abs(dx) < dp(36) || abs(dx) < abs(to.y - start.y)) return false
                slideTo(slideIndex + if (dx < 0) 1 else -1)
                return true
            }
        })
        slideFlipper.setOnTouchListener { _, event -> swipe.onTouchEvent(event) }
    }

    private fun slideTo(index: Int) {
        val count = slideFlipper.childCount
        if (count == 0) return
        slideIndex = ((index % count) + count) % count
        slideFlipper.displayedChild = slideIndex
        paintSlideDots(slideIndex)
    }

    /** The flipper advances on its own; this only keeps the dots on the child it landed on. */
    private val slideTicker = object : Runnable {
        override fun run() {
            if (screenHomeSlides.visibility != View.VISIBLE) return
            if (slideFlipper.displayedChild != slideIndex) {
                slideIndex = slideFlipper.displayedChild
                paintSlideDots(slideIndex)
            }
            slideFlipper.postDelayed(this, 400L)
        }
    }

    private fun paintSlideDots(active: Int) {
        if (slideDots.childCount != slideFlipper.childCount) {
            slideDots.removeAllViews()
            repeat(slideFlipper.childCount) {
                val dot = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(6) }
                    background = androidx.core.content.ContextCompat.getDrawable(
                        this@MainActivity,
                        R.drawable.bg_pixel,
                    )
                }
                slideDots.addView(dot)
            }
        }
        for (index in 0 until slideDots.childCount) {
            slideDots.getChildAt(index).backgroundTintList = ColorStateList.valueOf(
                color(if (index == active) R.color.mc_green_bright else R.color.mc_line_soft),
            )
        }
    }

    /**
     * What this version of the launcher actually runs. The shipped rows carry a value read from the
     * launcher itself; the gaps come from the capability table and say so in two words.
     */
    private fun renderHomeUpdates() {
        homeUpdatesHeading.text = getString(R.string.home_updates_heading, launcherVersion())
        homeUpdates.removeAllViews()
        val ready = instances.count { instance -> states[instance.versionId]?.isReady == true }
        listOf(
            getString(R.string.home_update_rail) to "on this page",
            getString(R.string.home_update_signin) to uiSettings.profileName.ifBlank { "no profile yet" },
            getString(R.string.home_update_play) to getString(R.string.home_update_count_installed, instances.size),
            getString(R.string.home_update_versions) to getString(R.string.home_update_states_note, ready, states.size),
            getString(R.string.home_update_news) to "Mojang version manifest",
            getString(R.string.home_update_settings) to "${uiSettings.language} · ${uiSettings.theme}",
            getString(R.string.home_update_launch) to gapFor(XCoreCapability.LAUNCH_PIPELINE),
            getString(R.string.home_update_microsoft) to gapFor(XCoreCapability.AUTHENTICATION),
            getString(R.string.home_update_skins) to gapFor(XCoreCapability.AUTHENTICATION),
            getString(R.string.home_update_controls) to gapFor(XCoreCapability.NATIVE_BRIDGE),
        ).forEach { (feature, status) ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_key_value, homeUpdates, false)
            row.findViewById<TextView>(R.id.tv_kv_key).text = playerText(feature)
            row.findViewById<TextView>(R.id.tv_kv_value).text = playerText(status)
            homeUpdates.addView(row)
        }
    }

    private fun launcherVersion(): String {
        val info = runCatching {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull()
        return info?.takeIf { candidate -> candidate.isNotBlank() } ?: "this build"
    }

    // ---------------------------------------------------------------- intro

    /**
     * The studio card the launcher opens with, before anything asks the player for a profile.
     *
     * It closes on its own, on a tap anywhere, or on back - whichever comes first - and only then
     * does the sign-in plaque get its chance to show.
     */
    private fun showIntro() {
        ResourcesCompat.getFont(this, R.font.silkscreen_bold)?.let { face ->
            introRoot.findViewById<TextView>(R.id.tv_intro_title)?.typeface = face
        }
        introRoot.setOnClickListener { dismissIntro() }
        introRoot.visibility = View.VISIBLE
        introRoot.bringToFront()
        XCoreMotion.animatePageEnter(introRoot)
        introRoot.postDelayed({ dismissIntro() }, 2600L)
    }

    private fun dismissIntro() {
        if (!::introRoot.isInitialized || introRoot.visibility != View.VISIBLE) return
        introRoot.setOnClickListener(null)
        if (XCoreMotion.enabled) XCoreMotion.animatePageExit(introRoot) else introRoot.visibility = View.GONE
        introFinished = true
        pendingSignIn?.let { stored -> gateOnSignIn(stored) }
    }

    // ---------------------------------------------------------------- sign-in

    /**
     * The plaque the launcher opens on before it shows anything else.
     *
     * Only the player name is kept, because that is the value the launch pipeline will hand to the
     * game as `auth_player_name`; it is checked here against the same rule the game checks there.
     * The password well exists because the reference screen has one, but this build ships no
     * authentication service, so what is typed into it is cleared when the plaque closes.
     */
    private fun bindLoginScreen() {
        applyPixelFace(loginRoot)
        loginRoot.findViewById<View>(R.id.btn_login_submit)?.let { target ->
            XCoreMotion.applyButtonTouchEffect(target)
            target.setOnClickListener { submitSignIn() }
        }
        loginRoot.findViewById<View>(R.id.btn_login_help)?.let { target ->
            XCoreMotion.applyButtonTouchEffect(target)
            target.setOnClickListener { showSignInHelp() }
        }
    }

    /** Open the plaque when no profile has been chosen, or when the last one asked not to be kept. */
    private fun gateOnSignIn(stored: XCoreUiSettings) {
        paintProfile(stored.profileName)
        if (stored.profileName.isNotBlank()) {
            loginName.setText(stored.profileName)
        }
        // A profile that has never signed in is kept by default; one that asked not to be kept is
        // not quietly re-remembered.
        if (stored.profileCredential.isBlank()) loginRemember.isChecked = true else loginRemember.isChecked = stored.keepProfileSignedIn
        // The intro owns the screen until it closes, so the plaque waits for it.
        if (!introFinished) {
            pendingSignIn = stored
            return
        }
        loginRoot.visibility = if (needsSignIn(stored)) View.VISIBLE else View.GONE
    }

    /**
     * A stored profile that asked to be remembered never sees the plaque again. Without that, the
     * player is asked for the password on each start - the profile name alone is not a credential.
     */
    private fun needsSignIn(stored: XCoreUiSettings): Boolean =
        stored.profileName.isBlank() ||
            stored.profileCredential.isBlank() ||
            !stored.keepProfileSignedIn

    private fun submitSignIn() {
        val name = loginName.text.toString().trim()
        if (name.isEmpty()) {
            loginNote.text = getString(R.string.login_name_missing)
            return
        }
        if (!playerNamePattern.matches(name)) {
            loginNote.text = getString(R.string.login_name_invalid)
            return
        }
        val password = loginPass.text.toString()
        if (password.isEmpty()) {
            loginNote.text = getString(R.string.login_pass_missing)
            return
        }
        val saved = uiSettings
        val isReturning = name == saved.profileName && saved.profileCredential.isNotBlank()
        if (isReturning && !passwordMatches(saved.profileCredential, password)) {
            loginNote.text = getString(R.string.login_pass_mismatch)
            loginPass.setText("")
            return
        }
        val credential = if (isReturning) saved.profileCredential else credentialFor(password)
        val next = saved.copy(
            profileName = name,
            keepProfileSignedIn = loginRemember.isChecked,
            profileCredential = credential,
        )
        graph.run({ graph.preferences.setUiSettings(next) }) { outcome ->
            outcome.errorOrNull()?.let { problem ->
                loginNote.text = playerText(problem.format())
                return@run
            }
            uiSettings = next
            loginPass.setText("")
            paintProfile(name)
            loginRoot.visibility = View.GONE
            notify(getString(R.string.login_done, name))
        }
    }

    /**
     * The sign-in is checked on this device only, so the password is kept as a salted digest and
     * never as text. The salt rides in front of the digest so verification can recompute it.
     */
    private fun credentialFor(password: String): String {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        return salt.toHex() + "$" + digest(salt, password)
    }

    private fun passwordMatches(stored: String, password: String): Boolean {
        val saltHex = stored.substringBefore('$')
        val salt = saltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return digest(salt, password) == stored.substringAfter('$')
    }

    private fun digest(salt: ByteArray, password: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(salt + password.toByteArray())
            .toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun paintProfile(name: String) {
        val label = name.ifBlank { getString(R.string.rail_account) }
        accountNameRail.text = label
        accountNameHome.text = label
    }

    private fun showSignInHelp() {
        showInfo(
            title = getString(R.string.login_need_account),
            rows = listOf(
                getString(R.string.login_help_service) to gapFor(XCoreCapability.AUTHENTICATION),
                getString(R.string.login_help_profile) to uiSettings.profileName.ifBlank { "-" },
                "Password" to (if (uiSettings.profileCredential.isBlank()) "not set" else "stored as a digest on this device"),
                getString(R.string.login_help_kept) to
                    if (uiSettings.keepProfileSignedIn) getString(R.string.login_yes) else getString(R.string.login_no),
            ),
            note = getString(R.string.login_help_note),
        )
    }

    // ---------------------------------------------------------------- header tabs

    private data class PlayTabEntry(val includeId: Int, val labelRes: Int, val tab: PlayTab)

    private val playTabEntries = listOf(
        PlayTabEntry(R.id.tab_play, R.string.tab_play, PlayTab.PLAY),
        PlayTabEntry(R.id.tab_installations, R.string.tab_installations, PlayTab.INSTALLATIONS),
        PlayTabEntry(R.id.tab_skins, R.string.tab_skins, PlayTab.SKINS),
        PlayTabEntry(R.id.tab_patch_notes, R.string.tab_patch_notes, PlayTab.PATCH_NOTES),
    )

    /**
     * The four tabs of the Java Edition page.
     *
     * Play is the home page, Installations and Patch notes are screens, and Skins is a gap: the
     * desktop launcher draws a skins panel from the signed-in profile, and this build has neither
     * the profile nor the panel, so the tab says so instead of opening an empty page.
     */
    private fun bindPlayTabs() {
        playTabEntries.forEach { entry ->
            val tab = findViewById<View>(entry.includeId) ?: return@forEach
            tab.findViewById<TextView>(R.id.tv_play_tab_label)?.setText(entry.labelRes)
            tab.setOnClickListener {
                XCoreMotion.applyButtonTouchEffect(it)
                when (entry.tab) {
                    PlayTab.PLAY -> goHome()
                    PlayTab.INSTALLATIONS -> openScreen(LauncherTab.INSTANCES)
                    PlayTab.PATCH_NOTES -> openScreen(LauncherTab.PATCH_NOTES)
                    PlayTab.SKINS -> showSkins()
                }
            }
        }
    }

    private fun paintPlayTabs(showStrip: Boolean) {
        playTabs.visibility = if (showStrip) View.VISIBLE else View.GONE
        if (!showStrip) return
        val tint = ColorStateList.valueOf(color(R.color.mc_white))
        val dim = ColorStateList.valueOf(color(R.color.mc_dim))
        playTabEntries.forEach { entry ->
            val tab = findViewById<View>(entry.includeId) ?: return@forEach
            val active = entry.tab == playTab
            tab.isSelected = active
            tab.findViewById<TextView>(R.id.tv_play_tab_label)?.setTextColor(if (active) tint else dim)
            tab.findViewById<View>(R.id.play_tab_rule)?.visibility = if (active) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun showSkins() {
        val capability = XCoreCapability.AUTHENTICATION
        showInfo(
            title = getString(R.string.tab_skins),
            rows = listOf(
                "status" to if (graph.services.contains(capability.serviceId)) "installed" else "not implemented",
                "service id" to capability.serviceId,
                "in this build" to gapFor(capability),
                "drawn instead" to getString(R.string.rail_account),
            ),
            note = getString(R.string.skins_note),
        )
    }

    // ---------------------------------------------------------------- news & patch notes

    private fun bindNewsScreen() {
        findViewById<View>(R.id.btn_news_refresh)?.let { target ->
            XCoreMotion.applyButtonTouchEffect(target)
            target.setOnClickListener { refreshManifest() }
        }
    }

    /**
     * The one manifest read behind both the rail's News screen and the Play page's Patch notes tab.
     *
     * There is no news service in this launcher, so the rows are the version manifest it does read,
     * newest first, with the timestamps Mojang published for each entry. Patch notes shows the
     * subset the manifest labels a release; the note above each list says which document it came
     * from and when it was fetched, rather than dressing a version table up as journalism.
     */
    private fun refreshManifest() {
        newsNote.setText(R.string.news_pending)
        patchNote.text = feedNote(getString(R.string.feed_loading))
        graph.run({ graph.installations.versions() }) { outcome ->
            val listing = outcome.valueOrNull()
            if (listing == null) {
                val problem = outcome.errorOrNull()?.format()
                    ?: (outcome as? XCoreResult.Cancelled)?.let { cancelled -> "cancelled: ${cancelled.reason ?: cancelled.operation}" }
                    ?: "no versions returned"
                newsNote.text = problem
                patchNote.text = feedNote(problem)
                newsRows.removeAllViews()
                patchRows.removeAllViews()
                return@run
            }
            val provenance = describeManifest(listing)
            newsNote.text = getString(R.string.news_note, provenance)
            patchNote.text = feedNote(provenance)
            val ordered = listing.entries.sortedByDescending { entry -> entry.releaseTimeMillis }
            renderFeed(newsRows, ordered, listing)
            renderFeed(
                patchRows,
                ordered.filter { entry ->
                    entry.releaseType == XCoreReleaseType.RELEASE || entry.releaseType == XCoreReleaseType.OLD_RELEASE
                },
                listing,
            )
        }
    }

    private fun feedNote(state: String): String =
        getString(R.string.patch_note) + "\n" + state

    private fun describeManifest(listing: XCoreVersionListing): String = buildString {
        append("${listing.entries.size} entries")
        append(" · ${if (listing.servedFromCache) "from the local cache" else "fetched now"}")
        append(" · fetched ${formatTimestamp(listing.manifestFetchedAtMillis)}")
        append(" · latest release ${listing.latestRelease ?: getString(R.string.home_version_unknown)}")
        append(" · latest snapshot ${listing.latestSnapshot ?: getString(R.string.home_version_unknown)}")
    }

    private fun renderFeed(
        container: LinearLayout,
        entries: List<XCoreManifestEntry>,
        listing: XCoreVersionListing,
    ) {
        container.removeAllViews()
        if (entries.isEmpty()) {
            container.addView(plainDialogLine(getString(R.string.feed_empty)))
            return
        }
        entries.forEach { entry ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_version_row, container, false)
            XCoreMotion.applyCardTouchEffect(row)
            row.findViewById<TextView>(R.id.tv_version_id).text = entry.id
            row.findViewById<TextView>(R.id.tv_version_type).text = buildString {
                append(entry.releaseType.displayName)
                append(" · ")
                append(stampFormat.format(Date(entry.releaseTimeMillis)))
            }
            row.findViewById<TextView>(R.id.tv_version_installed).text = if (entry.id in listing.installedIds) {
                getString(R.string.news_row_installed)
            } else {
                ""
            }
            row.setOnClickListener { showManifestEntry(entry, entry.id in listing.installedIds) }
            container.addView(row)
        }
    }

    private fun showManifestEntry(entry: XCoreManifestEntry, installed: Boolean) {
        showInfo(
            title = getString(R.string.news_detail_title, entry.id, entry.releaseType.displayName),
            rows = listOf(
                getString(R.string.news_detail_id) to entry.id,
                getString(R.string.news_detail_type) to entry.releaseType.displayName,
                getString(R.string.news_detail_released) to formatTimestamp(entry.releaseTimeMillis),
                getString(R.string.news_detail_updated) to formatTimestamp(entry.timeMillis),
                getString(R.string.news_detail_compliance) to (entry.complianceLevel?.toString() ?: "not stated"),
                getString(R.string.news_detail_profile) to entry.profileUrl,
                getString(R.string.news_detail_sha1) to (entry.profileSha1?.toString() ?: "not published"),
                getString(R.string.news_detail_installed) to if (installed) {
                    getString(R.string.login_yes)
                } else {
                    getString(R.string.login_no)
                },
            ),
            note = "Every field is the manifest's own; none of it was written by the launcher.",
        )
    }

    /** The rail's width, or a strip of icons when the player folds it. */
    private fun collapseRail() {
        railCollapsed = !railCollapsed
        val params = sidebar.layoutParams as LinearLayout.LayoutParams
        params.width = dp(if (railCollapsed) 52 else 212)
        sidebar.layoutParams = params
        railLabelIds.forEach { id ->
            findViewById<View>(id)?.findViewById<TextView>(R.id.tv_nav_label)?.visibility =
                if (railCollapsed) View.GONE else View.VISIBLE
        }
        accountNameRail.visibility = if (railCollapsed) View.GONE else View.VISIBLE
        findViewById<View>(R.id.btn_rail_collapse)?.apply {
            rotation = if (railCollapsed) 270f else 90f
            contentDescription = getString(if (railCollapsed) R.string.rail_expand else R.string.rail_collapse)
        }
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
                1 -> selectInstance(instance.id)

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
        askForNoticePermission()
        val announced = XCoreInstallService.beginInstall(this, instance.versionId)
        if (!announced) {
            graph.logger.log(
                XCoreLogLevel.WARN,
                XCoreLogTags.UI,
                "notifications are switched off for this app on ${instance.versionId}: the install runs unannounced",
            )
        }
        playLabel.text = getString(R.string.install_phase_resolving)
        graph.run({ graph.installations.install(instance.versionId, abi, instance.id) }) { outcome ->
            busy = false
            when (outcome) {
                is XCoreResult.Success -> {
                    XCoreInstallService.finishInstall(
                        this,
                        XCoreInstallService.STATE_INSTALLED,
                        instance.versionId,
                        installNoticeDetail(outcome.value),
                    )
                    showInfo(
                        title = "Installed ${instance.versionId}",
                        rows = summaryRows(outcome.value),
                        note = "Client jar ${outcome.value.clientJarPath}",
                        actions = listOf("Preflight" to { preflight(instance) }),
                    )
                }

                is XCoreResult.Failure -> {
                    XCoreInstallService.finishInstall(
                        this,
                        XCoreInstallService.STATE_FAILED,
                        instance.versionId,
                        outcome.error.format(),
                    )
                    showInfo(
                        title = "Install failed",
                        rows = listOf(
                            "kind" to outcome.error.kind.name.lowercase(),
                            "detail" to outcome.error.format(),
                            "retryable" to outcome.error.retryable.toString(),
                        ),
                        note = "Nothing was reported as installed that is not on disk.",
                    )
                }

                is XCoreResult.Cancelled -> {
                    XCoreInstallService.finishInstall(
                        this,
                        XCoreInstallService.STATE_INTERRUPTED,
                        instance.versionId,
                        "${outcome.operation} - ${outcome.reason ?: "no reason recorded"}",
                    )
                    showInfo(
                        title = "Install interrupted",
                        rows = listOf("operation" to outcome.operation, "reason" to (outcome.reason ?: "none")),
                        note = "Open journal rows let a later run resume instead of starting over.",
                    )
                }
            }
            renderPlayLabel()
            refreshInstances()
        }
    }

    /** The permission Android 13 wants before any notice can be seen; asked once, per install. */
    private fun askForNoticePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) installNoticePermission?.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * What the settled notice quotes: the counts the installer returned, in the order a player reads
     * them. No line here is a promise - they are the same figures the install dialog shows.
     */
    private fun installNoticeDetail(summary: XCoreInstallSummary): String = listOf(
        "${summary.filesWritten} files written · ${formatBytes(summary.bytesWritten)}",
        "already on disk: ${summary.filesAlreadyPresent} files · ${formatBytes(summary.bytesAlreadyPresent)}",
        "libraries ${summary.libraryCount} · natives ${summary.nativeCount} · assets ${summary.assetCount}",
        "client jar ${summary.clientJarPath}",
    ).joinToString("\n")

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
                    note = "This is the preflight only. The launch pipeline itself is not added yet, so no process was started.",
                )
            }
        }
    }

    // ---------------------------------------------------------------- home band

    private fun showAccount() {
        showInfo(
            title = "Account",
            rows = listOf(
                "Profile" to uiSettings.profileName.ifBlank { "-" },
                "Kept on this device" to if (uiSettings.keepProfileSignedIn) getString(R.string.login_yes) else getString(R.string.login_no),
                "Microsoft / Mojang" to gapFor(XCoreCapability.AUTHENTICATION),
                "Effect" to "Nothing can be launched as a named player until it lands.",
            ),
            note = "The name and its password are checked against this device only. Authentication with Mojang or Microsoft is its own capability; this build does not fake a logged-in player.",
        )
    }

    /**
     * The bottom bar's version card is a dropdown, not a plaque: it opens under the card and lists
     * the instances on the device, with the create action as the first row.
     */
    private fun showInstancePicker() {
        val anchor = findViewById<View>(R.id.card_instance_picker) ?: return
        instanceDropdown?.dismiss()

        val rows = mutableListOf<PickerRow>()
        rows += PickerRow(getString(R.string.picker_create_instance), getString(R.string.picker_create_instance_detail), null)
        instances.forEach { instance ->
            val state = states[instance.versionId]
            rows += PickerRow(
                name = instance.name,
                detail = buildString {
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
                },
                instanceId = instance.id,
                selected = instance.id == selectedId,
            )
        }

        val dropdown = ListPopupWindow(this).apply {
            setAnchorView(anchor)
            setModal(true)
            val widest = minOf(dp(300), (resources.displayMetrics.widthPixels * 0.45f).toInt())
            setContentWidth(widest)
            setBackgroundDrawable(
                androidx.core.content.ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_dialog),
            )
            setVerticalOffset(dp(6))
            setAdapter(
                object : ArrayAdapter<PickerRow>(this@MainActivity, 0, rows) {
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val row = convertView
                            ?: LayoutInflater.from(this@MainActivity)
                                .inflate(R.layout.item_instance_picker, parent, false)
                        val entry = getItem(position) ?: return row
                        row.findViewById<TextView>(R.id.tv_picker_name).text = entry.name
                        row.findViewById<TextView>(R.id.tv_picker_detail).text = entry.detail
                        row.findViewById<View>(R.id.row_instance).setBackgroundResource(
                            if (entry.selected) R.drawable.bg_card_selected else R.drawable.bg_card,
                        )
                        row.findViewById<ImageView>(R.id.iv_picker_mark)?.setImageResource(
                            if (entry.instanceId == null) R.drawable.ic_plus else R.drawable.ic_chevron_right,
                        )
                        return row
                    }
                },
            )
            setOnItemClickListener { _, _, position, _ ->
                val entry = rows[position]
                dismiss()
                val id = entry.instanceId
                if (id == null) {
                    showCreateDialog()
                } else {
                    selectInstance(id)
                }
            }
        }
        instanceDropdown = dropdown
        dropdown.show()
    }

    /** One line in the version dropdown: a real instance, or the create action when the id is null. */
    private data class PickerRow(val name: String, val detail: String, val instanceId: String?, val selected: Boolean = false)

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
                    state.error?.let { append(" · ").append(playerText(it.format())) }
                }
            }
            graph.tasks.history(limit = 8).forEach { handle ->
                rows += "task ${handle.name}" to buildString {
                    append(handle.state.name.lowercase())
                    handle.progress?.let { append(" · ").append((it * 100f).toInt()).append('%') }
                    handle.error?.let { append(" · ").append(playerText(it.format())) }
                }
            }
            showInfo(
                title = getString(R.string.downloads_title),
                rows = rows.ifEmpty { listOf("state" to getString(R.string.downloads_empty)) },
                note = outcome.errorOrNull()?.let { "installStates: ${playerText(it.format())}" } ?: getString(R.string.downloads_note),
                actions = listOf(
                    getString(R.string.action_open_game_tree) to { openScreen(LauncherTab.FILES) },
                    getString(R.string.refresh) to { showDownloads() },
                ),
            )
        }
    }

    private fun plainDialogLine(text: String): TextView = TextView(this).apply {
        this.text = playerText(text)
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
        // The narrowest plaque in the launcher: a name, a version and one tick have to fit in it.
        val dialog = dialogFor(sheet, widthDp = 240, maxHeightFraction = 0.60f)
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
                    }
                    listing.entries.take(24).forEachIndexed { idx, entry ->
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
                    // The list lives in its own bounded scroll, so it opens only once it has rows.
                    sheet.findViewById<View>(R.id.scroll_versions)?.visibility = View.VISIBLE
                    applyPixelFace(versions)
                }
            }
        }

        XCoreLoaderKind.entries.take(3).forEach { kind ->
            val chip = TextView(this).apply {
                text = kind.displayName
                textSize = 11f
                setPadding(16, 10, 16, 10)
                gravity = Gravity.CENTER
                background = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_mc_button)
                setTextColor(color(R.color.mc_white))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = 8
                }
                XCoreMotion.applyButtonTouchEffect(this)
                paint.isFakeBoldText = kind == chosenLoader
                setOnClickListener {
                    chosenLoader = kind
                    for (index in 0 until loaders.childCount) {
                        val child = loaders.getChildAt(index) as TextView
                        child.paint.isFakeBoldText = child.text == kind.displayName
                    }
                }
            }
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
        // The chips and the tick went in after the plaque was measured, so they are set now.
        applyPixelFace(sheet)
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
                    showInfo(title = "EULA acceptance not stored", rows = listOf("error" to playerText(problem.format())))
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
                // Creating is not selecting. Until the store holds this id, the next refresh reads
                // the old selection back and the new instance is not the one the PLAY block acts on.
                selectInstance(created.id)
            }
        }
    }

    /**
     * Makes [id] the live instance: remembered first, then repainted from the store so the card, the
     * dropdown tick and every settings panel agree on the same one.
     */
    private fun selectInstance(id: String) {
        graph.run({ graph.instances.select(id) }) { outcome ->
            outcome.errorOrNull()?.let { problem ->
                showInfo(
                    title = "That instance was not selected",
                    rows = listOf("id" to id, "error" to playerText(problem.format())),
                    note = "The launcher keeps showing the instance it could read, rather than guessing.",
                )
            }
            refreshInstances()
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
                "in this build" to gapFor(capability),
            ),
            note = if (installed) {
                "The capability is in the catalog; the section UI for it has not been built yet."
            } else {
                "This control is shown as a gap rather than a working switch: ${capability.displayName} is ${gapFor(capability)}."
            },
        )
    }

    // ---------------------------------------------------------------- left rail

    /** One row in the rail. A null target means the Java Edition page rather than a sub-screen. */
    private data class RailEntry(val includeId: Int, val labelRes: Int, val iconRes: Int, val target: LauncherTab?)

    private val railEntries = listOf(
        RailEntry(R.id.nav_home, R.string.rail_home, R.drawable.ic_home, LauncherTab.HOME),
        RailEntry(R.id.nav_news, R.string.rail_news, R.drawable.ic_bell, LauncherTab.NEWS),
        RailEntry(R.id.nav_edition, R.string.rail_edition_java, R.drawable.ic_grass_block, null),
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
        findViewById<View>(R.id.nav_more)?.apply {
            findViewById<TextView>(R.id.tv_nav_label)?.setText(R.string.rail_more)
            findViewById<ImageView>(R.id.iv_nav_icon)?.setImageResource(R.drawable.ic_more_vert)
            setOnClickListener {
                XCoreMotion.applyButtonTouchEffect(it)
                showMoreScreens()
            }
        }
    }

    /**
     * The screens the reference rail has no row for.
     *
     * The desktop launcher keeps its diagnostics off the navigation, and the player asked for the
     * same rail, so the launcher's own reads - files, library, stats, logs, controls - are folded
     * into one row rather than dropped. Every one of them is still the same live screen.
     */
    private fun showMoreScreens() {
        val targets = listOf(
            LauncherTab.FILES to R.string.nav_files,
            LauncherTab.LIBRARY to R.string.nav_library,
            LauncherTab.STATS to R.string.nav_stats,
            LauncherTab.LOGS to R.string.nav_logs,
            LauncherTab.CONTROLS to R.string.nav_controls,
        )
        showChoice(
            title = getString(R.string.rail_more),
            note = "The launcher's own screens: what is on disk, what was fetched, what has run, what it logged and what the player can press.",
            options = targets.map { (target, label) -> getString(label) to { openScreen(target) } },
        )
    }

    private fun paintRail(target: LauncherTab?) {
        val tint = ColorStateList.valueOf(color(R.color.mc_white))
        val dim = ColorStateList.valueOf(color(R.color.mc_dim))
        railEntries.forEach { entry ->
            val row = findViewById<View>(entry.includeId) ?: return@forEach
            val active = entry.target == target
            row.isSelected = active
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
            append(getString(R.string.controls_note_phase))
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

    private fun bindSettingsCategories() {
        val strip = findViewById<LinearLayout>(R.id.container_settings_categories) ?: return
        strip.removeAllViews()
        SettingsCategory.entries.forEach { category ->
            val tab = LayoutInflater.from(this).inflate(R.layout.item_settings_tab, strip, false)
            tab.tag = category
            tab.findViewById<TextView>(R.id.tv_tab_label)?.setText(category.labelRes)
            tab.findViewById<ImageView>(R.id.iv_tab_icon)?.setImageResource(category.iconRes)
            tab.setOnClickListener { showSettingsCategory(category) }
            strip.addView(tab)
        }
        showSettingsCategory(settingsCategory)
    }

    private fun showSettingsCategory(category: SettingsCategory) {
        settingsCategory = category
        SettingsCategory.entries.forEach { candidate ->
            findViewById<View>(candidate.panelId)?.let { panel ->
                if (candidate == category) XCoreMotion.animatePageEnter(panel) else panel.visibility = View.GONE
            }
        }
        val strip = findViewById<LinearLayout>(R.id.container_settings_categories) ?: return
        val bright = color(R.color.mc_white)
        val dimmed = color(R.color.mc_dim)
        for (index in 0 until strip.childCount) {
            val tab = strip.getChildAt(index)
            val selected = tab.tag == category
            val accent = (tab.tag as? SettingsCategory)?.let { color(it.accentColorRes) } ?: dimmed
            tab.isSelected = selected
            tab.findViewById<TextView>(R.id.tv_tab_label)?.setTextColor(if (selected) bright else dimmed)
            tab.findViewById<ImageView>(R.id.iv_tab_icon)?.imageTintList =
                ColorStateList.valueOf(if (selected) accent else dimmed)
            tab.findViewById<View>(R.id.tab_rule)?.apply {
                visibility = if (selected) View.VISIBLE else View.INVISIBLE
                backgroundTintList = ColorStateList.valueOf(accent)
            }
        }
        renderSettings()
    }

    /**
     * Rebuilds the open panel from launcher state.
     *
     * Nothing here is a snapshot taken when the screen was first drawn: the config is read live, the
     * instance rows come from the database, and the runtime panels come from the runtime manager. A
     * panel that depends on a measurement kicks off the read and fills itself in when it lands.
     */
    private fun renderSettings() {
        val container = findViewById<LinearLayout>(settingsCategory.rowsId) ?: return
        container.removeAllViews()
        findViewById<TextView>(R.id.tv_settings_context)?.text = selectedInstance()?.let { instance ->
            "${instance.name} · ${instance.versionId}"
        } ?: getString(R.string.settings_rail_context)
        when (settingsCategory) {
            SettingsCategory.RUNTIME -> renderRuntimePanel(container)
            SettingsCategory.MEMORY -> renderMemoryPanel(container)
            SettingsCategory.RESOLUTION -> renderResolutionPanel(container)
            SettingsCategory.RENDERER -> renderRendererPanel(container)
            SettingsCategory.ARGUMENTS -> renderArgumentsPanel(container)
            SettingsCategory.STORAGE -> renderStoragePanel(container)
            SettingsCategory.APPEARANCE -> renderAppearancePanel(container)
            SettingsCategory.ACCOUNT -> renderAccountPanel(container)
            SettingsCategory.ABOUT -> renderAboutPanel(container)
        }
    }

    private fun renderRuntimePanel(container: LinearLayout) {
        val config = graph.config.value
        addSection(
            container,
            R.drawable.ic_coffee,
            R.string.settings_sec_source_policy,
            getString(R.string.settings_sec_source_note),
        )
        addRow(
            container,
            R.drawable.ic_download,
            getString(R.string.row_runtime_channel_title),
            getString(R.string.row_runtime_channel_sub),
            value = config.runtimeChannel.name.lowercase(),
        ) { chooseRuntimeChannel() }
        addRow(
            container,
            R.drawable.ic_shield,
            getString(R.string.row_verified_runtime_title),
            getString(R.string.row_verified_runtime_sub),
            switchTo = config.requireVerifiedRuntime,
            onToggle = { enabled -> saveConfig { current -> current.copy(requireVerifiedRuntime = enabled) } },
        )
        addRow(
            container,
            R.drawable.ic_folder,
            getString(R.string.row_runtime_override_title),
            getString(R.string.row_runtime_override_sub),
            value = selectedInstance()?.runtimeComponentId?.takeIf { it.isNotBlank() }
                ?: getString(R.string.settings_value_auto),
        ) { showRuntimeOverridePicker() }
        addRow(
            container,
            R.drawable.ic_refresh,
            getString(R.string.row_scan_runtimes_title),
            getString(R.string.row_scan_runtimes_sub),
        ) { rescanRuntimes(container) }

        addSection(
            container,
            R.drawable.ic_download,
            R.string.settings_sec_fetch,
            getString(R.string.settings_sec_fetch_note),
        )
        addRow(
            container,
            R.drawable.ic_bolt,
            getString(R.string.row_transfers_title),
            getString(R.string.row_transfers_sub),
            value = config.maxConcurrentDownloads.toString(),
        ) {
            showChoice(
                title = getString(R.string.row_transfers_title),
                note = getString(R.string.settings_sec_fetch_note),
                options = listOf(1, 2, 4, 8).map { count ->
                    "$count at a time" to { saveConfig { current -> current.copy(maxConcurrentDownloads = count) } }
                },
            )
        }
        addRow(
            container,
            R.drawable.ic_clock,
            getString(R.string.row_timeout_title),
            getString(R.string.row_timeout_sub),
            value = "${config.httpTimeoutMillis / 1000}s",
        ) {
            showChoice(
                title = getString(R.string.row_timeout_title),
                note = getString(R.string.settings_sec_fetch_note),
                options = listOf(10_000, 20_000, 30_000, 60_000).map { millis ->
                    "${millis / 1000} s" to { saveConfig { current -> current.copy(httpTimeoutMillis = millis) } }
                },
            )
        }
        addFieldRow(
            container,
            R.drawable.ic_document,
            getString(R.string.row_user_agent_title),
            getString(R.string.row_user_agent_sub),
            state = config.userAgent.take(28),
            value = config.userAgent,
            hintRes = R.string.row_user_agent_hint,
            numeric = false,
            showClear = false,
            onApply = { agent -> saveConfig { current -> current.copy(userAgent = agent) } },
            onClear = { },
        )

        val device = graph.deviceFacts
        addNote(
            container,
            buildString {
                append(device.abi)
                append(" · API ").append(device.apiLevel)
                append(" · ").append(device.deviceModel)
                append(" · ")
                append(formatBytes(device.totalRamBytes))
                append(" RAM · ").append(device.execPolicy.explain())
            },
        )

        val expected = settingsCategory
        graph.run({ graph.runtimeEngine.status() }) { outcome ->
            if (settingsCategory != expected) return@run
            val status = outcome.valueOrNull()
            if (status == null) {
                addNote(container, outcome.errorOrNull()?.format() ?: "the runtime status could not be read")
                return@run
            }
            addSection(
                container,
                R.drawable.ic_cube,
                R.string.settings_sec_detected,
                getString(R.string.settings_sec_detected_note),
            )
            if (status.candidates.isEmpty()) {
                addNote(container, getString(R.string.settings_empty_detected))
            }
            status.candidates.forEach { info ->
                addRuntimeRow(
                    container,
                    R.drawable.ic_coffee,
                    info.displayName,
                    runtimeStateLabel(info.state),
                    getString(
                        R.string.settings_runtime_detail,
                        info.id,
                        info.release?.fullVersion ?: "release file unreadable",
                        info.sizeBytes / (1024L * 1024L),
                    ),
                    info.problems.firstOrNull() ?: info.notes.firstOrNull() ?: info.summarize(),
                ) { openRuntimeSheet(info) }
            }
            addSection(
                container,
                R.drawable.ic_download,
                R.string.settings_sec_catalogue,
                getString(R.string.settings_sec_catalogue_note, XCoreRuntimeSource.ALLOWED_HOSTS.joinToString()),
            )
            if (status.availableSources.isEmpty()) {
                addNote(container, getString(R.string.settings_empty_sources))
            }
            status.availableSources.forEach { source ->
                addRuntimeRow(
                    container,
                    R.drawable.ic_document,
                    source.displayName,
                    "Java ${source.javaMajor}",
                    getString(
                        R.string.settings_source_detail,
                        source.javaMajor,
                        source.versionLabel,
                        source.compressedBytes / (1024L * 1024L),
                        XCoreLicensePolicy.canonicalize(source.licenseId),
                    ),
                    if (status.fetchPolicy.allowsNetworkFetch) {
                        getString(R.string.settings_source_note)
                    } else {
                        getString(R.string.settings_fetch_blocked, status.fetchPolicy.refusal().format())
                    },
                ) { offerRuntimeInstall(source, container) }
            }
            status.notes.forEach { note -> addNote(container, note) }
        }
    }

    private fun renderMemoryPanel(container: LinearLayout) {
        val device = graph.deviceFacts
        val builder = XCoreJvmArguments(device)
        val ramMb = (device.totalRamBytes / (1024L * 1024L)).toInt()
        val ceiling = if (ramMb > 0) (ramMb / 2).coerceAtLeast(XCoreJvmArguments.MIN_HEAP_MB.toInt())
        else XCoreJvmArguments.DEFAULT_HEAP_CEILING_MB.toInt()

        addSection(
            container,
            R.drawable.ic_shield,
            R.string.settings_sec_budget,
            getString(R.string.settings_sec_budget_note),
        )
        addRow(
            container,
            R.drawable.ic_ram,
            getString(R.string.row_ram_title),
            getString(R.string.row_ram_sub),
            value = formatBytes(device.totalRamBytes),
        ) {
            showInfo(
                title = getString(R.string.row_ram_title),
                rows = listOf(
                    "total" to formatBytes(device.totalRamBytes),
                    "ceiling" to "$ceiling MB",
                    "floor" to "${XCoreJvmArguments.MIN_HEAP_MB} MB",
                ),
                note = getString(R.string.settings_sec_budget_note),
            )
        }
        addRow(
            container,
            R.drawable.ic_speedometer,
            getString(R.string.row_ceiling_title),
            getString(R.string.row_ceiling_sub),
            value = "$ceiling MB",
        ) {
            showInfo(
                title = getString(R.string.row_ceiling_title),
                rows = listOf(
                    "physical" to "$ramMb MB",
                    "ceiling" to "$ceiling MB",
                    "rule" to "half of physical RAM, never below ${XCoreJvmArguments.MIN_HEAP_MB} MB",
                ),
                note = "The clamp happens at launch, so a stored -Xmx above the ceiling is reduced rather than refused.",
            )
        }
        addRow(
            container,
            R.drawable.ic_info,
            getString(R.string.row_floor_title),
            getString(R.string.row_floor_sub),
            value = "${XCoreJvmArguments.MIN_HEAP_MB} MB",
        ) { showInfo(title = getString(R.string.row_floor_title), rows = emptyList(), note = getString(R.string.settings_sec_budget_note)) }

        addSection(
            container,
            R.drawable.ic_ram,
            R.string.settings_sec_heap,
            getString(R.string.settings_sec_heap_note),
        )
        val instance = selectedInstance()
        if (instance == null) {
            addNote(container, getString(R.string.settings_no_instance))
            return
        }
        val steps = ArrayList<Int>()
        var value = XCoreJvmArguments.MIN_HEAP_MB.toInt()
        while (value <= ceiling) {
            steps += value
            value += 128
        }
        if (steps.isEmpty()) steps += ceiling
        val current = heapPattern.find(instance.jvmArguments)?.groupValues?.get(1)?.toIntOrNull()
            ?: steps.first()
        addSeekRow(
            container,
            R.drawable.ic_ram,
            getString(R.string.row_heap_title),
            getString(R.string.settings_heap_range, ramMb, XCoreJvmArguments.MIN_HEAP_MB.toInt()),
            steps,
            current,
            " MB",
        ) { mb -> storeHeap(instance, mb) }
        val decision = builder.decideHeap(current)
        addNote(
            container,
            getString(
                R.string.settings_heap_granted,
                decision.grantedMb,
                decision.requestedMb,
                decision.reason ?: "within what this device can grant",
            ),
        )
        if (instance.jvmArguments.isNotBlank()) addNote(container, instance.jvmArguments)

        addSection(
            container,
            R.drawable.ic_bolt,
            R.string.settings_sec_presets,
            getString(R.string.settings_sec_presets_note),
        )
        listOf(512, 1024, 2048, 4096).forEach { megabytes ->
            val granted = builder.decideHeap(megabytes).grantedMb
            addRow(
                container,
                R.drawable.ic_cube,
                "$megabytes MB",
                getString(R.string.settings_preset_grants, granted),
                value = if (granted == decision.grantedMb) getString(R.string.settings_value_current)
                else getString(R.string.settings_value_apply),
            ) { storeHeap(instance, megabytes) }
        }
    }

    private fun renderResolutionPanel(container: LinearLayout) {
        addSection(
            container,
            R.drawable.ic_monitor,
            R.string.settings_sec_window,
            getString(R.string.settings_sec_window_note),
        )
        val instance = selectedInstance()
        if (instance == null) {
            addNote(container, getString(R.string.settings_no_instance))
            return
        }
        val width = widthPattern.find(instance.gameArguments)?.groupValues?.get(2)
        val height = heightPattern.find(instance.gameArguments)?.groupValues?.get(2)
        addNote(container, getString(R.string.settings_resolution_gated))
        addFieldRow(
            container,
            R.drawable.ic_monitor,
            getString(R.string.row_width_title),
            getString(R.string.row_resolution_sub),
            state = "stored: ${width ?: getString(R.string.value_none)}",
            value = width.orEmpty(),
            hintRes = R.string.row_width_hint,
            numeric = true,
            showClear = false,
            onApply = { text -> storeResolution(instance, text, height) },
            onClear = { },
        )
        addFieldRow(
            container,
            R.drawable.ic_monitor,
            getString(R.string.row_height_title),
            getString(R.string.row_resolution_sub),
            state = "stored: ${height ?: getString(R.string.value_none)}",
            value = height.orEmpty(),
            hintRes = R.string.row_height_hint,
            numeric = true,
            showClear = true,
            onApply = { text -> storeResolution(instance, width, text) },
            onClear = { clearResolution(instance) },
        )
        if (instance.gameArguments.isNotBlank()) addNote(container, instance.gameArguments)

        addSection(
            container,
            R.drawable.ic_gallery,
            R.string.settings_sec_sizes,
            getString(
                R.string.settings_sec_sizes_note,
                resources.displayMetrics.widthPixels,
                resources.displayMetrics.heightPixels,
            ),
        )
        listOf(854 to 480, 1280 to 720, 1366 to 768, 1920 to 1080).forEach { (w, h) ->
            val stored = width == w.toString() && height == h.toString()
            addRow(
                container,
                R.drawable.ic_monitor,
                "$w × $h",
                getString(R.string.settings_size_written, w, h),
                value = if (stored) getString(R.string.settings_value_current) else getString(R.string.settings_value_apply),
            ) { storeResolution(instance, w.toString(), h.toString()) }
        }
    }

    private fun renderRendererPanel(container: LinearLayout) {
        addSection(
            container,
            R.drawable.ic_renderer,
            R.string.settings_sec_backend,
            getString(R.string.settings_sec_backend_note),
        )
        val expected = settingsCategory
        val versionId = selectedInstance()?.versionId
        val versionsDir = graph.storageLayout()?.dir(XCoreGameDirectory.VERSIONS)
        val target = versionId?.let { id ->
            XCoreGraphicsTarget(
                versionId = id,
                clientJar = versionsDir?.let { directory -> XCoreGraphicsTarget.jarFor(directory, id) },
                lwjglCoordinate = null,
            )
        }
        val marker = container.childCount
        addNote(container, "measuring this device's GPU, surface and native libraries")
        graph.run({ graph.graphics.produceReport(target).asXCoreSuccess() }) { outcome ->
            if (settingsCategory != expected) return@run
            container.removeViews(marker, container.childCount - marker)
            val report = outcome.valueOrNull()
            if (report == null) {
                addNote(container, outcome.errorOrNull()?.format() ?: "the graphics measurement failed")
            } else {
                renderGraphicsRows(container, report)
            }
            renderJavaRequirementRows(container)
        }
    }

    /**
     * The renderer panel's rows, each one filled from a measurement this pass actually took.
     *
     * Nothing here is a promise about a future build: a value of "not added" is the audit saying a
     * JNI entry point does not exist in any library on the device, and the detail sheet names it.
     */
    private fun renderGraphicsRows(
        container: LinearLayout,
        report: XCoreGraphicsReadinessReport,
    ) {
        val capabilities = report.capabilities
        addRow(
            container,
            R.drawable.ic_speedometer,
            "GPU context",
            capabilities.gl?.summarize()
                ?: (capabilities.probeFailure?.detail ?: "this device gave no GL context"),
            value = capabilities.glVersion?.let { version -> "ES $version" } ?: "unavailable",
        ) {
            showInfo(
                title = "GPU context",
                rows = listOf(
                    "abi" to capabilities.device.abi,
                    "driver strings" to (capabilities.gl?.let { "${it.glVendor} / ${it.glRenderer}" } ?: "none"),
                    "GL version" to (capabilities.gl?.glVersionString ?: "unread"),
                    "GLSL" to (capabilities.gl?.glslVersionString ?: "unread"),
                    "max texture" to (capabilities.gl?.maxTextureSize?.toString() ?: "unread"),
                    "extensions" to (capabilities.gl?.extensions?.size?.toString() ?: "0"),
                    "declared features" to capabilities.declaredGlFeatures.joinToString().ifEmpty { "none" },
                    "low RAM device" to capabilities.lowRamDevice.toString(),
                ),
                note = capabilities.probeFailure?.format()
                    ?: "read from a live EGL context on ${capabilities.device.deviceModel}",
            )
        }

        addRow(
            container,
            R.drawable.ic_renderer,
            "Rendering configuration",
            report.baseline?.describe()
                ?: "no baseline: the driver reported no usable GL version to build one from",
            value = when {
                report.baselineVerdict?.usable == true -> "works on this device"
                else -> "not usable"
            },
        ) {
            showInfo(
                title = "Rendering configuration",
                rows = listOf(
                    "configuration" to (report.baseline?.describe() ?: "none"),
                    "verdict" to (report.baselineVerdict?.let { verdict ->
                        if (verdict.usable) "accepted" else "refused: ${verdict.reasons.joinToString()}"
                    } ?: "not validated"),
                    "blockers" to report.blockers.joinToString(" | ").ifEmpty { "none" },
                ),
                note = "this is the device's own configuration, measured from the driver, not a " +
                    "preset for any game version",
            )
        }

        addRow(
            container,
            R.drawable.ic_monitor,
            "Window surface",
            report.windowSurface?.summarize()
                ?: (report.surfaceFailure?.detail ?: "the surface path was not exercised in this pass"),
            value = if (report.canPresentFrames) "presents frames" else "not added",
        ) {
            showInfo(
                title = "Window surface",
                rows = listOf(
                    "surface" to (report.windowSurface?.state?.describe() ?: "none"),
                    "context on surface" to (report.windowSurface?.gl?.summarize() ?: "none"),
                    "swap" to (report.windowSurface?.let { surface ->
                        if (surface.swapSucceeded) {
                            "one buffer handed to the queue"
                        } else {
                            "refused: " + (surface.swapError ?: surface.gl?.swapError ?: "no error code recorded")
                        }
                    } ?: "not attempted"),
                    "failure" to (report.surfaceFailure?.format() ?: "none"),
                ),
                note = "an ImageReader-backed window, not a game view: it proves the surface and " +
                    "context machinery works without claiming anything is being drawn",
            )
        }

        addRow(
            container,
            R.drawable.ic_bolt,
            "Native bridge",
            report.nativeAudit?.summarize() ?: (report.nativeFailure?.detail ?: "the audit did not run"),
            value = if (report.bridgeReady) "ready" else "not added",
        ) {
            val audit = report.nativeAudit
            showInfo(
                title = "Native bridge",
                rows = listOf(
                    "searched" to (audit?.searchedDirectories?.joinToString(" | ") ?: "nothing"),
                    "libraries found" to (audit?.candidates?.size?.toString() ?: "0"),
                    "entry points present" to (audit?.satisfiedEntryPoints
                        ?.joinToString { it.symbol }?.ifEmpty { "none" } ?: "unknown"),
                    "entry points missing" to (audit?.missingEntryPoints
                        ?.joinToString { it.symbol }?.ifEmpty { "none" } ?: "unknown"),
                    "load refusal" to (report.nativeFailure?.format() ?: "none"),
                ),
                note = audit?.let {
                    if (it.isBridgeReady) it.summarize() else "the contract names JNI entry points that " +
                        "no library on this device exports, so there is nothing for the game's " +
                        "windowing calls to reach"
                } ?: "no audit report",
            )
        }

        addRow(
            container,
            R.drawable.ic_shield,
            "Components and licences",
            report.missingComponents.joinToString { it.displayName }
                .ifEmpty { "nothing required is absent" },
            value = "${report.unverifiedLicenses.size} unverified",
        ) {
            showInfo(
                title = "Components and licences",
                rows = graph.graphics.licenseLedger().map { component ->
                    component.displayName to "${component.license}" +
                        (if (component.licenseVerified) "" else " (UNVERIFIED)") +
                        " - ${component.status.label}"
                },
                note = "a licence this build has not read from a shipped file is marked unverified, " +
                    "and nothing proprietary to Microsoft is ever bundled",
            )
        }

        addRow(
            container,
            R.drawable.ic_gamepad,
            "Selected version",
            report.versionVerdict?.let { verdict ->
                if (verdict.usable) "accepted" else verdict.reasons.joinToString(" | ")
            } ?: (report.target?.describe() ?: "no instance is selected"),
            value = if (report.canRunMinecraft) "renderable" else "not added",
        ) {
            showInfo(
                title = "Selected version",
                rows = listOf(
                    "version" to (report.target?.describe() ?: "none"),
                    "requirement" to (report.shaderFloor?.describe()
                        ?: (report.shaderReadFailure?.detail ?: "the client jar's shaders were not read")),
                    "configuration" to (report.versionConfig?.describe() ?: "none"),
                    "verdict" to (report.versionVerdict?.let { verdict ->
                        if (verdict.usable) "accepted" else "refused: ${verdict.reasons.joinToString()}"
                    } ?: "not validated"),
                ),
                note = report.versionConfig?.backend?.blocker
                    ?: "no version configuration exists to assess",
            )
        }
    }

    /**
     * The Java requirement and the native-library rows for the instance being played.
     *
     * These follow the graphics rows because both answer the same question from two sides: the GPU
     * panel says what this device can present, this one says which runtime the selected version needs
     * and what its staged tree actually contains.
     */
    private fun renderJavaRequirementRows(container: LinearLayout) {
        addSection(
            container,
            R.drawable.ic_coffee,
            R.string.settings_sec_requirement,
            getString(R.string.settings_sec_requirement_note),
        )
        val wanted = selectedInstance()
        when {
            wanted == null -> addNote(container, getString(R.string.settings_no_instance))
            else -> {
                val requirement = graph.runtimeEngine.requirementForVersionId(wanted.versionId)
                if (requirement == null) {
                    addNote(container, getString(R.string.settings_requirement_unknown, wanted.versionId))
                } else {
                    addRow(
                        container,
                        R.drawable.ic_coffee,
                        getString(R.string.settings_requirement_row, requirement.javaMajor, requirement.versionId),
                        requirement.detail,
                        value = requirement.source.name.lowercase(),
                    ) {
                        showInfo(
                            title = getString(R.string.settings_sec_requirement),
                            rows = listOf(
                                "version" to requirement.versionId,
                                "java major" to requirement.javaMajor.toString(),
                                "abi" to requirement.abi,
                                "source" to requirement.source.name.lowercase(),
                                "detail" to requirement.detail,
                            ),
                            note = getString(R.string.settings_sec_requirement_note),
                        )
                    }
                    addRow(
                        container,
                        R.drawable.ic_shield,
                        getString(
                            if (requirement.isSupported) R.string.settings_requirement_supported
                            else R.string.settings_requirement_unsupported
                        ),
                        getString(R.string.row_abi_sub),
                        value = requirement.abi,
                    ) { showCapabilities() }
                }
            }
        }

        addSection(
            container,
            R.drawable.ic_terminal,
            R.string.settings_sec_diagnostics,
            getString(R.string.settings_sec_diagnostics_note),
        )
        val expected = settingsCategory
        graph.run({ graph.runtimeEngine.status() }) { outcome ->
            if (settingsCategory != expected) return@run
            val status = outcome.valueOrNull()
            val pinned = selectedInstance()?.runtimeComponentId
            val candidates = status?.candidates.orEmpty()
            val shown = candidates.firstOrNull { info -> info.id == pinned }
                ?: candidates.firstOrNull { info -> info.abi == graph.deviceFacts.abi && info.isComplete }
                ?: candidates.firstOrNull()
            if (shown == null) {
                addNote(container, outcome.errorOrNull()?.format() ?: getString(R.string.settings_runtime_none_available))
                return@run
            }
            addRow(container, R.drawable.ic_layers, getString(R.string.row_natives_title),
                getString(R.string.row_natives_sub), value = shown.displayName) { showNatives(shown) }
            addRow(container, R.drawable.ic_bolt, getString(R.string.row_load_jvm_title),
                getString(R.string.row_load_jvm_sub), value = shown.javaMajor?.toString() ?: "?") { showJvmLoad(shown) }
            addNote(container, status?.summarize().orEmpty())
        }
    }

    private fun renderArgumentsPanel(container: LinearLayout) {
        addSection(
            container,
            R.drawable.ic_terminal,
            R.string.settings_sec_arg_lines,
            getString(R.string.settings_sec_arg_lines_note),
        )
        val instance = selectedInstance()
        if (instance == null) {
            addNote(container, getString(R.string.settings_no_instance))
            return
        }
        val validation = instance.validate().errorOrNull()?.format()
        addFieldRow(
            container,
            R.drawable.ic_coffee,
            getString(R.string.row_jvm_args_title),
            getString(R.string.row_jvm_args_sub),
            state = getString(R.string.settings_value_empty).takeIf { instance.jvmArguments.isBlank() }
                ?: "${instance.jvmArguments.split(" ").size} token(s)",
            value = instance.jvmArguments,
            hintRes = R.string.row_jvm_args_sub,
            numeric = false,
            showClear = true,
            onApply = { text -> storeArguments(instance.copy(jvmArguments = text)) },
            onClear = { storeArguments(instance.copy(jvmArguments = "")) },
        )
        addFieldRow(
            container,
            R.drawable.ic_cube,
            getString(R.string.row_game_args_title),
            getString(R.string.row_game_args_sub),
            state = getString(R.string.settings_value_empty).takeIf { instance.gameArguments.isBlank() }
                ?: "${instance.gameArguments.split(" ").size} token(s)",
            value = instance.gameArguments,
            hintRes = R.string.row_game_args_sub,
            numeric = false,
            showClear = true,
            onApply = { text -> storeArguments(instance.copy(gameArguments = text)) },
            onClear = { storeArguments(instance.copy(gameArguments = "")) },
        )
        addRow(
            container,
            R.drawable.ic_info,
            getString(R.string.settings_preview_title),
            getString(R.string.settings_preview_sub),
            value = validation?.take(18) ?: getString(R.string.install_ready),
        ) {
            showInfo(
                title = instance.name,
                rows = listOf(
                    "jvm_arguments" to instance.jvmArguments.ifBlank { getString(R.string.settings_preview_empty) },
                    "game_arguments" to instance.gameArguments.ifBlank { getString(R.string.settings_preview_empty) },
                    "runtime_component_id" to (instance.runtimeComponentId ?: getString(R.string.settings_value_auto)),
                    "validation" to (validation ?: "row accepted"),
                ),
                note = "The launch pipeline is not added yet, so these lines are stored and shown but not yet run.",
            )
        }

        addSection(
            container,
            R.drawable.ic_bolt,
            R.string.settings_sec_flags,
            getString(R.string.settings_sec_flags_note),
        )
        listOf(
            "-XX:+UseSerialGC" to getString(R.string.flag_serial_gc_sub),
            "-XX:+HeapDumpOnOutOfMemoryError" to getString(R.string.flag_heap_dump_sub),
            "-Xss512k" to getString(R.string.flag_stack_sub),
            "-XX:MaxGCPauseMillis=200" to getString(R.string.flag_gc_pause_sub),
        ).forEach { (flag, subtitle) ->
            val present = instance.jvmArguments.contains(flag)
            addRow(
                container,
                R.drawable.ic_terminal,
                flag,
                subtitle,
                value = if (present) getString(R.string.settings_value_in_line) else getString(R.string.settings_value_not_set),
            ) {
                val next = if (present) {
                    instance.jvmArguments.replace(flag, "").replace(Regex("\\s+"), " ").trim()
                } else {
                    "${instance.jvmArguments} $flag".trim()
                }
                storeArguments(instance.copy(jvmArguments = next))
            }
        }
    }

    private fun renderStoragePanel(container: LinearLayout) {
        addSection(
            container,
            R.drawable.ic_folder,
            R.string.settings_sec_root,
            getString(R.string.settings_sec_root_note),
        )
        val config = graph.config.value
        addRow(
            container,
            R.drawable.ic_folder,
            getString(R.string.row_storage_root_title),
            getString(R.string.row_storage_root_sub),
            value = config.storageRoot.name.lowercase(),
        ) { chooseStorageRoot() }
        addFieldRow(
            container,
            R.drawable.ic_document,
            getString(R.string.row_custom_root_title),
            getString(R.string.row_custom_root_sub),
            state = config.customRootPath?.take(24) ?: getString(R.string.value_none),
            value = config.customRootPath.orEmpty(),
            hintRes = R.string.row_custom_root_sub,
            numeric = false,
            showClear = true,
            onApply = { path -> saveConfig { current -> current.copy(customRootPath = path.ifBlank { null }) } },
            onClear = { saveConfig { current -> current.copy(customRootPath = null) } },
        )
        val layout = graph.storageLayout()
        addNote(
            container,
            layout?.let {
                getString(R.string.settings_root_current, it.plan.rootPath, formatBytes(it.plan.usableBytes))
            } ?: getString(R.string.settings_root_pending),
        )
        layout?.plan?.reason?.let { reason -> addNote(container, "fallback: $reason") }

        addSection(
            container,
            R.drawable.ic_document,
            R.string.settings_sec_dirs,
            getString(R.string.settings_sec_dirs_note),
        )
        if (layout == null) {
            addNote(container, getString(R.string.settings_dirs_empty))
        } else {
            addRow(
                container,
                R.drawable.ic_layers,
                getString(R.string.row_dirs_title),
                getString(R.string.row_dirs_sub, layout.present.size, XCoreGameDirectory.REQUIRED.size),
                value = layout.pathOf(XCoreGameDirectory.INSTANCES).takeLast(28),
            ) { showDirectories(layout) }
            XCoreGameDirectory.REQUIRED.forEach { kind ->
                val directory = layout.dir(kind)
                addRow(
                    container,
                    if (kind.readOnlyByLauncher) R.drawable.ic_shield else R.drawable.ic_folder,
                    kind.relativePath,
                    kind.purpose,
                    value = if (directory?.isDirectory == true) "ok" else "absent",
                ) {
                    showInfo(
                        title = kind.relativePath,
                        rows = listOf(
                            "path" to (directory?.absolutePath ?: layout.pathOf(kind)),
                            "exists" to (directory?.isDirectory?.toString() ?: "not created"),
                            "writable" to (directory?.canWrite()?.toString() ?: "unknown"),
                            "read-only by launcher" to kind.readOnlyByLauncher.toString(),
                            "purpose" to kind.purpose,
                        ),
                    )
                }
            }
        }
        val instance = selectedInstance()
        if (instance != null) {
            addRow(
                container,
                R.drawable.ic_search,
                getString(R.string.row_game_tree_title),
                getString(R.string.row_game_tree_sub),
                value = instance.name,
            ) { inspectInstanceDirectory(instance) }
        }

        addSection(
            container,
            R.drawable.ic_clock,
            R.string.settings_sec_retention,
            getString(R.string.settings_sec_retention_note),
        )
        val limits = graph.config.value
        addRow(
            container,
            R.drawable.ic_terminal,
            getString(R.string.row_log_buffer_title),
            getString(R.string.row_log_buffer_sub),
            value = "${limits.logHistoryCapacity}",
        ) {
            showChoice(
                title = getString(R.string.row_log_buffer_title),
                note = getString(R.string.choice_log_buffer_note),
                options = listOf(500, 2_000, 10_000, 50_000).map { capacity ->
                    "$capacity lines" to { saveConfig { current -> current.copy(logHistoryCapacity = capacity) } }
                },
            )
        }
        addRow(
            container,
            R.drawable.ic_clock,
            getString(R.string.row_history_title),
            getString(R.string.row_history_sub),
            value = "${limits.keepLaunchHistoryDays} d",
        ) { chooseHistoryWindow() }
        addRow(
            container,
            R.drawable.ic_info,
            getString(R.string.row_environment_title),
            getString(R.string.row_environment_sub),
            value = limits.environment.name.lowercase(),
        ) {
            showInfo(
                title = getString(R.string.row_environment_title),
                rows = listOf(
                    "environment" to limits.environment.name.lowercase(),
                    "log level" to limits.logLevel.name.lowercase(),
                    "schema" to "v${limits.schemaVersion}",
                    "user agent" to limits.userAgent,
                ),
                note = "The environment is fixed by the build type; this row reports it rather than switching it.",
            )
        }
    }

    private fun renderAppearancePanel(container: LinearLayout) {
        addSection(container, R.drawable.ic_palette, R.string.settings_sec_look, getString(R.string.settings_sec_look_note))
        addRow(
            container,
            R.drawable.ic_globe,
            getString(R.string.row_language_title),
            getString(R.string.row_language_sub),
            value = uiSettings.language,
        ) { chooseLanguage() }
        addRow(
            container,
            R.drawable.ic_palette,
            getString(R.string.row_theme_title),
            getString(R.string.row_theme_sub),
            value = uiSettings.theme,
        ) { chooseTheme() }
        addRow(
            container,
            R.drawable.ic_bolt,
            getString(R.string.row_animations_title),
            getString(R.string.row_animations_sub),
            switchTo = uiSettings.animationsEnabled,
            onToggle = { enabled -> setAnimationsEnabled(enabled) },
        )

        addSection(container, R.drawable.ic_gear, R.string.settings_sec_engine, getString(R.string.choice_log_level_note))
        val config = graph.config.value
        addRow(
            container,
            R.drawable.ic_terminal,
            getString(R.string.row_log_level_title),
            getString(R.string.row_log_level_sub),
            value = config.logLevel.name.lowercase(),
        ) { chooseLogLevel() }
        addRow(
            container,
            R.drawable.ic_clock,
            getString(R.string.row_history_title),
            getString(R.string.row_history_sub),
            value = "${config.keepLaunchHistoryDays} d",
        ) { chooseHistoryWindow() }
        addRow(
            container,
            R.drawable.ic_download,
            getString(R.string.row_network_title),
            getString(R.string.row_network_sub),
            value = "${config.maxConcurrentDownloads} · ${config.httpTimeoutMillis / 1000}s",
        ) { showNetworkChoices() }
        addRow(
            container,
            R.drawable.ic_refresh,
            getString(R.string.row_reset_title),
            getString(R.string.row_reset_sub),
        ) { confirmReset() }

        addSection(
            container,
            R.drawable.ic_monitor,
            R.string.settings_sec_metrics,
            getString(R.string.settings_sec_metrics_note),
        )
        val metrics = resources.displayMetrics
        val orientation = resources.configuration.orientation
        val orientationLabel = if (orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) "portrait" else "landscape"
        addRow(
            container,
            R.drawable.ic_monitor,
            getString(R.string.row_density_title),
            getString(R.string.row_density_sub),
            value = "${metrics.densityDpi} dpi",
        ) {
            showInfo(
                title = getString(R.string.row_density_title),
                rows = listOf(
                    "densityDpi" to metrics.densityDpi.toString(),
                    "scale" to "%.2f".format(metrics.density),
                    "fontScale" to "%.2f".format(resources.configuration.fontScale),
                    "xdpi / ydpi" to "%.0f / %.0f".format(metrics.xdpi, metrics.ydpi),
                ),
                note = getString(R.string.metrics_dialog_note),
            )
        }
        addRow(
            container,
            R.drawable.ic_palette,
            getString(R.string.row_font_scale_title),
            getString(R.string.row_font_scale_sub),
            value = "%.2f×".format(resources.configuration.fontScale),
        ) {
            showInfo(
                title = getString(R.string.row_font_scale_title),
                rows = listOf("fontScale" to "%.2f".format(resources.configuration.fontScale)),
                note = getString(R.string.metrics_dialog_note),
            )
        }
        addRow(
            container,
            R.drawable.ic_refresh,
            getString(R.string.row_orientation_title),
            getString(R.string.row_orientation_sub),
            value = orientationLabel,
        ) {
            showInfo(
                title = getString(R.string.row_orientation_title),
                rows = listOf(
                    "orientation" to orientationLabel,
                    "window" to "${metrics.widthPixels} × ${metrics.heightPixels} px",
                    "smallest width" to "${resources.configuration.smallestScreenWidthDp} dp",
                ),
                note = getString(R.string.metrics_dialog_note),
            )
        }
    }

    private fun renderAccountPanel(container: LinearLayout) {
        addSection(container, R.drawable.ic_person, R.string.settings_sec_player, getString(R.string.settings_sec_player_note))
        addRow(
            container,
            R.drawable.ic_person,
            getString(R.string.row_account_title),
            getString(R.string.account_signed_out),
            value = gapFor(XCoreCapability.AUTHENTICATION),
        ) { showAccount() }

        addSection(container, R.drawable.ic_shield, R.string.settings_sec_terms, getString(R.string.settings_sec_terms_note))
        val expected = settingsCategory
        graph.run({ graph.installations.termsAcknowledged() }) { outcome ->
            if (settingsCategory != expected) return@run
            val accepted = outcome.valueOrNull() == true
            addRow(
                container,
                R.drawable.ic_shield,
                getString(R.string.row_terms_title),
                getString(R.string.row_terms_sub),
                value = getString(if (accepted) R.string.settings_eula_accepted else R.string.settings_eula_declined),
            ) { showTermsSheet(accepted, container) }
            addNote(container, eulaUrl)
        }
        addRow(
            container,
            R.drawable.ic_document,
            getString(R.string.row_licence_title),
            getString(R.string.row_licence_sub),
        ) { showRuntimeLicences() }

        addSection(
            container,
            R.drawable.ic_info,
            R.string.settings_sec_gaps,
            getString(R.string.settings_sec_gaps_note),
        )
        listOf(XCoreCapability.AUTHENTICATION, XCoreCapability.LAUNCH_PIPELINE).forEach { capability ->
            addRow(
                container,
                R.drawable.ic_shield,
                capability.displayName,
                capability.serviceId,
                value = gapFor(capability),
            ) {
                showInfo(
                    title = capability.displayName,
                    rows = listOf(
                        "service" to capability.serviceId,
                        "gap" to gapFor(capability),
                        "implemented" to capability.implemented.toString(),
                    ),
                    note = playerText(capability.notImplementedError().format()),
                )
            }
        }
    }

    private fun renderAboutPanel(container: LinearLayout) {
        addSection(container, R.drawable.ic_info, R.string.settings_sec_build, getString(R.string.settings_sec_build_note))
        addRow(
            container,
            R.drawable.ic_xcore_logo,
            getString(R.string.row_version_title),
            getString(R.string.row_version_sub),
            value = installedVersionName(),
        ) { showCapabilities() }
        addRow(
            container,
            R.drawable.ic_cube,
            getString(R.string.row_app_id_title),
            getString(R.string.row_app_id_sub),
            value = packageName,
        ) {
            showInfo(
                title = getString(R.string.row_app_id_title),
                rows = listOf(
                    "applicationId" to packageName,
                    "versionName" to installedVersionName(),
                    "data dir" to dataDir.absolutePath,
                    "native lib dir" to (applicationInfo.nativeLibraryDir ?: "none"),
                    "debuggable" to ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0).toString(),
                ),
                note = "This is the id the APK is installed under, read from the package manager.",
            )
        }
        addRow(
            container,
            R.drawable.ic_document,
            getString(R.string.row_schema_title),
            getString(R.string.row_schema_sub),
            value = "v${graph.config.value.schemaVersion}",
        ) {
            graph.run({ graph.diagnostics.report() }) { outcome ->
                showInfo(
                    title = getString(R.string.row_schema_title),
                    rows = listOf("schema" to "v${graph.config.value.schemaVersion}"),
                    note = outcome.valueOrNull() ?: outcome.errorOrNull()?.format().orEmpty(),
                )
            }
        }
        addRow(
            container,
            R.drawable.ic_gear,
            getString(R.string.row_engine_title),
            getString(R.string.row_engine_sub),
            value = graph.engineState.value.name.lowercase(),
        ) {
            showInfo(
                title = getString(R.string.row_engine_title),
                rows = graph.moduleStatuses.value.map { status -> status.id to status.state.name.lowercase() } +
                    ("services" to graph.services.bindings().size.toString()),
            )
        }
        addRow(
            container,
            R.drawable.ic_shield,
            getString(R.string.row_capabilities_title),
            getString(R.string.row_capabilities_sub),
            value = graph.capabilityReport().missing.size.toString(),
        ) { showCapabilities() }

        addSection(container, R.drawable.ic_monitor, R.string.settings_sec_device, graph.deviceFacts.execPolicy.explain())
        val device = graph.deviceFacts
        addRow(
            container,
            R.drawable.ic_monitor,
            getString(R.string.row_api_title),
            getString(R.string.row_api_sub),
            value = "API ${device.apiLevel} · ${device.osVersionRelease}",
        ) {
            showInfo(
                title = getString(R.string.row_api_title),
                rows = listOf(
                    "sdk_int" to device.apiLevel.toString(),
                    "release" to device.osVersionRelease,
                    "model" to device.deviceModel,
                    "native lib dir" to (device.nativeLibraryDir ?: "none reported"),
                    "exec from app home" to device.execPolicy.canExecStagedRuntime.toString(),
                ),
                note = device.execPolicy.explain(),
            )
        }
        addRow(
            container,
            R.drawable.ic_cube,
            getString(R.string.row_abi_title),
            getString(R.string.row_abi_sub),
            value = device.abi,
        ) {
            showInfo(
                title = getString(R.string.row_abi_title),
                rows = listOf(
                    "selected" to device.abi,
                    "all supported" to device.allSupportedAbis.joinToString(),
                    "64-bit" to device.is64Bit.toString(),
                    "os.arch used for rules" to device.osArch(),
                    "Mojang environment" to device.runtimeEnvironment().let { env ->
                        "os.name=${env.osName} os.arch=${env.osArch} version=${env.osVersion ?: "unset"} " +
                            "features=" + if (env.features.isEmpty()) "none" else env.features.joinToString()
                    },
                ),
            )
        }
        addRow(
            container,
            R.drawable.ic_ram,
            getString(R.string.row_ram_title),
            getString(R.string.row_ram_sub),
            value = formatBytes(device.totalRamBytes),
        ) {
            val builder = XCoreJvmArguments(device)
            val stored = selectedInstance()?.jvmArguments
                ?.let { line -> heapPattern.find(line)?.groupValues?.get(1)?.toIntOrNull() }
            val decision = builder.decideHeap(stored ?: XCoreJvmArguments.MIN_HEAP_MB.toInt())
            showInfo(
                title = getString(R.string.row_ram_title),
                rows = listOf(
                    "total" to formatBytes(device.totalRamBytes),
                    "ceiling" to "${decision.grantedMb} MB for a request of ${decision.requestedMb} MB",
                    "floor" to "${XCoreJvmArguments.MIN_HEAP_MB} MB",
                    "this installation asks" to (stored?.let { "$it MB" } ?: "nothing - decided at launch"),
                    "reason" to (decision.reason ?: "within what this device can grant"),
                ),
                note = "The ceiling is half of physical RAM, so a heap above it is clamped rather than trusted.",
            )
        }

        addSection(container, R.drawable.ic_chart, R.string.settings_sec_state, getString(R.string.settings_sec_state_note))
        addRow(
            container,
            R.drawable.ic_terminal,
            getString(R.string.row_diagnostics_title),
            getString(R.string.row_diagnostics_sub),
        ) {
            graph.run({ graph.diagnostics.report() }) { outcome ->
                val report = outcome.valueOrNull()
                val sheet = inflateDialog(R.layout.dialog_info)
                sheet.findViewById<TextView>(R.id.tv_dialog_title).text = getString(R.string.row_diagnostics_title)
                val rows = sheet.findViewById<LinearLayout>(R.id.container_dialog_rows)
                report?.lines()?.filter { it.isNotBlank() }?.forEach { line -> rows.addView(plainDialogLine(line.trim())) }
                sheet.findViewById<TextView>(R.id.tv_dialog_note)?.apply {
                    text = outcome.errorOrNull()?.format() ?: getString(R.string.diagnostics_note)
                    visibility = View.VISIBLE
                }
                val dialog = dialogFor(sheet)
                addDialogAction(
                    sheet.findViewById(R.id.container_dialog_actions),
                    dialog,
                    getString(R.string.action_close),
                ) { }
                dialog.show()
            }
        }
        addRow(
            container,
            R.drawable.ic_clock,
            getString(R.string.row_events_title),
            getString(R.string.row_events_sub),
            value = graph.recentEvents.value.size.toString(),
        ) {
            graph.refreshEvents()
            showInfo(
                title = getString(R.string.row_events_title),
                rows = graph.recentEvents.value.map { event ->
                    stampFormat.format(Date(event.timestampMillis)) to event::class.simpleName.orEmpty()
                },
                note = "Read from the event bus this session; nothing is replayed from storage.",
            )
        }

        addSection(
            container,
            R.drawable.ic_layers,
            R.string.settings_sec_roadmap,
            getString(R.string.settings_sec_roadmap_note),
        )
        val installedServices = graph.capabilityReport().installedServiceIds
        XCoreCapability.IN_LAUNCH_ORDER.forEach { capability ->
            val inBuild = capability.serviceId in installedServices
            addRow(
                container,
                if (inBuild) R.drawable.ic_check else R.drawable.ic_info,
                capability.displayName,
                gapFor(capability),
                value = getString(if (inBuild) R.string.settings_roadmap_in_build else R.string.settings_roadmap_gap),
            ) {
                showInfo(
                    title = capability.displayName,
                    rows = listOf(
                        "service" to capability.serviceId,
                        "gap" to gapFor(capability),
                        "table says" to capability.implemented.toString(),
                        "installed here" to inBuild.toString(),
                    ),
                    note = if (inBuild) getString(R.string.settings_sec_roadmap_note)
                    else playerText(capability.notImplementedError().format()),
                )
            }
        }
    }

    // ---------------------------------------------------------- panel builders

    private fun addSection(container: LinearLayout, iconRes: Int, titleRes: Int, note: String? = null) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_setting_section, container, false)
        val accent = color(settingsCategory.accentColorRes)
        view.findViewById<ImageView>(R.id.iv_section_icon)?.apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(accent)
        }
        view.findViewById<TextView>(R.id.tv_section_title)?.apply {
            setText(titleRes)
            setTextColor(accent)
        }
        view.findViewById<View>(R.id.section_keyline)?.setBackgroundColor(accent)
        view.findViewById<TextView>(R.id.tv_section_note)?.apply {
            text = note.orEmpty()
            visibility = if (note.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        container.addView(view)
    }

    private fun addNote(container: LinearLayout, text: String) {
        if (text.isBlank()) return
        val view = LayoutInflater.from(this).inflate(R.layout.item_setting_note, container, false)
        (view as TextView).text = playerText(text)
        container.addView(view)
    }

    private fun addRow(
        container: LinearLayout,
        iconRes: Int,
        title: String,
        subtitle: String,
        value: String? = null,
        switchTo: Boolean? = null,
        onToggle: ((Boolean) -> Unit)? = null,
        onClick: () -> Unit = { },
    ) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_setting, container, false)
        XCoreMotion.applyCardTouchEffect(row)
        row.findViewById<TextView>(R.id.tv_setting_title)?.text = playerText(title)
        row.findViewById<TextView>(R.id.tv_setting_subtitle)?.text = playerText(subtitle)
        row.findViewById<ImageView>(R.id.iv_setting_icon)?.apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(color(settingsCategory.accentColorRes))
        }
        val valueView = row.findViewById<TextView>(R.id.row_value)
        val switchView = row.findViewById<Switch>(R.id.row_switch)
        val chevron = row.findViewById<ImageView>(R.id.row_chevron)
        valueView?.visibility = if (value != null && switchTo == null) View.VISIBLE else View.GONE
        valueView?.text = value
        switchView?.visibility = if (switchTo != null) View.VISIBLE else View.GONE
        chevron?.visibility = if (switchTo == null) View.VISIBLE else View.GONE
        row.setOnClickListener {
            if (switchTo != null) {
                val next = !(switchView?.isChecked ?: switchTo)
                switchView?.isChecked = next
                onToggle?.invoke(next)
            } else {
                onClick()
            }
        }
        container.addView(row)
    }

    private fun addRuntimeRow(
        container: LinearLayout,
        iconRes: Int,
        name: String,
        state: String,
        meta: String,
        note: String,
        onClick: () -> Unit,
    ) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_runtime_row, container, false)
        XCoreMotion.applyCardTouchEffect(row)
        row.findViewById<ImageView>(R.id.iv_runtime_icon)?.setImageResource(iconRes)
        row.findViewById<TextView>(R.id.tv_runtime_name)?.text = name
        row.findViewById<TextView>(R.id.tv_runtime_state)?.text = state
        row.findViewById<TextView>(R.id.tv_runtime_meta)?.text = meta
        row.findViewById<TextView>(R.id.tv_runtime_note)?.apply {
            text = note
            visibility = if (note.isBlank()) View.GONE else View.VISIBLE
        }
        row.setOnClickListener { onClick() }
        container.addView(row)
    }

    private fun addFieldRow(
        container: LinearLayout,
        iconRes: Int,
        title: String,
        note: String,
        state: String,
        value: String,
        hintRes: Int,
        numeric: Boolean,
        showClear: Boolean,
        onApply: (String) -> Unit,
        onClear: () -> Unit,
    ) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_setting_field, container, false)
        val field = view.findViewById<EditText>(R.id.et_field) ?: return
        view.findViewById<TextView>(R.id.tv_field_title)?.text = title
        view.findViewById<TextView>(R.id.tv_field_note)?.text = note
        view.findViewById<ImageView>(R.id.iv_field_icon)?.setImageResource(iconRes)
        view.findViewById<TextView>(R.id.tv_field_state)?.apply {
            text = state
            visibility = if (state.isBlank()) View.GONE else View.VISIBLE
        }
        if (numeric) field.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        field.setHint(hintRes)
        field.setText(value)
        field.setSelection(value.length)
        view.findViewById<View>(R.id.btn_field_apply)?.setOnClickListener { onApply(field.text.toString().trim()) }
        view.findViewById<View>(R.id.btn_field_clear)?.apply {
            visibility = if (showClear) View.VISIBLE else View.GONE
            setOnClickListener {
                field.setText("")
                onClear()
            }
        }
        container.addView(view)
    }

    private fun addSeekRow(
        container: LinearLayout,
        iconRes: Int,
        title: String,
        note: String,
        steps: List<Int>,
        selected: Int,
        suffix: String,
        onCommit: (Int) -> Unit,
    ) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_setting_seek, container, false)
        val bar = view.findViewById<SeekBar>(R.id.seek_bar) ?: return
        val valueLabel = view.findViewById<TextView>(R.id.tv_seek_value)
        view.findViewById<TextView>(R.id.tv_seek_title)?.text = title
        view.findViewById<TextView>(R.id.tv_seek_note)?.text = note
        view.findViewById<ImageView>(R.id.iv_seek_icon)?.setImageResource(iconRes)
        val startIndex = steps.indexOf(selected).coerceAtLeast(0)
        bar.max = (steps.size - 1).coerceAtLeast(0)
        bar.progress = startIndex
        valueLabel?.text = "${steps.getOrElse(startIndex) { selected }}$suffix"
        view.findViewById<TextView>(R.id.tv_seek_min)?.text = "${steps.firstOrNull() ?: selected}$suffix"
        view.findViewById<TextView>(R.id.tv_seek_max)?.text = "${steps.lastOrNull() ?: selected}$suffix"
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                valueLabel?.text = "${steps.getOrElse(progress) { selected }}$suffix"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                onCommit(steps.getOrElse(seekBar?.progress ?: startIndex) { selected })
            }
        })
        container.addView(view)
    }

    // ------------------------------------------------------------ setting write

    private fun selectedInstance(): XCoreInstance? =
        instances.firstOrNull { candidate -> candidate.id == selectedId }

    private fun runtimeStateLabel(state: XCoreRuntimeState): String = when (state) {
        XCoreRuntimeState.READY -> getString(R.string.settings_state_ready)
        XCoreRuntimeState.BROKEN -> getString(R.string.settings_state_broken)
        else -> state.name.lowercase()
    }

    private fun saveInstance(instance: XCoreInstance, then: (XCoreInstance) -> Unit = { }) {
        graph.run({ graph.repositories.instances.save(instance) }) { outcome ->
            val stored = outcome.valueOrNull()
            if (stored == null) {
                showInfo(
                    title = getString(R.string.settings_rejected_title),
                    rows = listOf(
                        "jvm_arguments" to instance.jvmArguments,
                        "game_arguments" to instance.gameArguments,
                        "error" to (outcome.errorOrNull()?.format() ?: "the row could not be written"),
                    ),
                    note = "The row is validated before it is written, so an argument line the model " +
                        "rejects never reaches the database.",
                )
            } else {
                instances = instances.map { candidate -> if (candidate.id == stored.id) stored else candidate }
                then(stored)
                renderSettings()
            }
        }
    }

    private fun storeArguments(instance: XCoreInstance) {
        saveInstance(instance) { stored ->
            notify(getString(R.string.settings_args_stored, stored.name))
        }
    }

    private fun storeHeap(instance: XCoreInstance, megabytes: Int) {
        val line = instance.jvmArguments.replace(heapPattern, "").replace(Regex("\\s+"), " ").trim()
        val next = (line + " -Xmx${megabytes}m").trim()
        saveInstance(instance.copy(jvmArguments = next)) { stored ->
            notify(getString(R.string.settings_heap_stored, megabytes, stored.name))
        }
    }

    private fun storeResolution(instance: XCoreInstance, width: String?, height: String?) {
        val w = width?.toIntOrNull()
        val h = height?.toIntOrNull()
        if (w == null || h == null) {
            showInfo(
                title = getString(R.string.settings_rejected_title),
                rows = listOf("width" to (width ?: "blank"), "height" to (height ?: "blank")),
                note = "Both dimensions are needed before the pair is written; a half-set window is refused.",
            )
            return
        }
        saveInstance(instance.copy(gameArguments = withResolution(instance.gameArguments, w, h))) { stored ->
            notify(getString(R.string.settings_resolution_stored, w, h, stored.name))
        }
    }

    private fun clearResolution(instance: XCoreInstance) {
        val stripped = instance.gameArguments
            .replace(widthPattern, "")
            .replace(heightPattern, "")
            .replace(Regex("\\s+"), " ")
            .trim()
        saveInstance(instance.copy(gameArguments = stripped)) { stored ->
            notify(getString(R.string.settings_resolution_cleared) + " (${stored.name})")
        }
    }

    private fun withResolution(line: String, width: Int, height: Int): String {
        val stripped = line
            .replace(widthPattern, "")
            .replace(heightPattern, "")
            .replace(Regex("\\s+"), " ")
            .trim()
        return "$stripped --width $width --height $height".trim()
    }

    private fun pinRuntime(instance: XCoreInstance, componentId: String?) {
        graph.run({ graph.repositories.instances.attachRuntime(instance.id, componentId) }) { outcome ->
            val stored = outcome.valueOrNull()
            if (stored == null) {
                showInfo(
                    title = "Runtime not attached",
                    rows = listOf("error" to (outcome.errorOrNull()?.format() ?: "the row could not be written")),
                    note = "The instance table rejects an id that is not a recorded runtime.",
                )
            } else {
                instances = instances.map { candidate -> if (candidate.id == stored.id) stored else candidate }
                notify(
                    if (componentId == null) {
                        getString(R.string.settings_runtime_auto, stored.name)
                    } else {
                        getString(R.string.settings_runtime_pinned, componentId, stored.name)
                    },
                )
                renderSettings()
            }
        }
    }

    private fun rescanRuntimes(container: LinearLayout) {
        addNote(container, "measuring every tree under runtimes/ ...")
        graph.run({ graph.runtimeEngine.scan() }) { outcome ->
            val found = outcome.valueOrNull()
            if (found == null) {
                addNote(container, outcome.errorOrNull()?.format() ?: "the scan could not run")
            } else {
                notify(getString(R.string.settings_scan_done, found.size, found.size))
                renderSettings()
            }
        }
    }

    private fun showRuntimeOverridePicker() {
        val instance = selectedInstance()
        if (instance == null) {
            notify(getString(R.string.settings_no_instance))
            return
        }
        graph.run({ graph.runtimeEngine.status() }) { outcome ->
            val status = outcome.valueOrNull()
            val options = ArrayList<Pair<String, () -> Unit>>()
            options += getString(R.string.settings_runtime_unpin) to { pinRuntime(instance, null) }
            status?.candidates.orEmpty().forEach { info ->
                options += info.summarize() to { pinRuntime(instance, info.id) }
            }
            showChoice(
                title = getString(R.string.row_runtime_override_title),
                note = outcome.errorOrNull()?.format()
                    ?: "Pinning writes the runtime id into the instance row that the launch plan reads.",
                options = options,
            )
        }
    }

    private fun openRuntimeSheet(info: XCoreRuntimeInfo) {
        val rows = listOf(
            "id" to info.id,
            "java" to (info.release?.fullVersion ?: "release file not readable"),
            "abi" to info.abi,
            "state" to runtimeStateLabel(info.state),
            "provenance" to info.provenance.name.lowercase(),
            "archive" to info.archiveRelativePath,
            "home" to info.homeRelativePath,
            "size" to formatBytes(info.sizeBytes),
            "sha1" to (info.sha1?.value ?: getString(R.string.settings_sha_missing)),
            "source" to (info.sourceUrl ?: "not from a network source"),
            "licence" to XCoreLicensePolicy.describe(info.licenseId),
            "elf" to (info.launcherElf?.let { "${it.machineName} · interpreter ${it.interpreter ?: "none"}" }
                ?: "header not read"),
            "executable" to (info.launcherElf?.incompatibility() ?: "not measured"),
            "problems" to info.problems.joinToString("; ").ifBlank { "none" },
            "notes" to info.notes.joinToString("; ").ifBlank { "none" },
        )
        showInfo(
            title = info.displayName,
            rows = rows,
            note = "Every value here was read from the files on this device.",
            actions = listOf(
                getString(R.string.settings_runtime_actions) to {
                    val instance = selectedInstance()
                    if (instance == null) notify(getString(R.string.settings_no_instance)) else pinRuntime(instance, info.id)
                },
                getString(R.string.settings_runtime_reverify) to {
                    graph.run({ graph.runtimeEngine.reverify(info) }) { outcome ->
                        val checked = outcome.valueOrNull()
                        notify(
                            checked?.let { "re-check: ${runtimeStateLabel(it.state)} - ${it.problems.joinToString()}" }
                                ?: (outcome.errorOrNull()?.format() ?: "re-check did not run"),
                        )
                        renderSettings()
                    }
                },
                getString(R.string.settings_runtime_remove) to { confirmRuntimeRemoval(info) },
                getString(R.string.action_close) to { },
            ),
        )
    }

    private fun confirmRuntimeRemoval(info: XCoreRuntimeInfo) {
        showInfo(
            title = getString(R.string.settings_runtime_remove),
            rows = listOf(
                "runtime" to info.id,
                "archive" to info.archiveRelativePath,
                "tree" to info.homeRelativePath,
            ),
            note = "This deletes the files this launcher staged and the row that described them. " +
                "A runtime you imported from elsewhere is removed from the launcher, not from where you put it, " +
                "only if it lives inside the game tree.",
            actions = listOf(
                getString(R.string.settings_runtime_remove) to {
                    graph.run({ graph.runtimeEngine.remove(info) }) { outcome ->
                        val removed = outcome.valueOrNull()
                        notify(
                            removed?.let { getString(R.string.settings_runtime_removed, info.id, it.size) }
                                ?: (outcome.errorOrNull()?.format() ?: "removal did not run"),
                        )
                        renderSettings()
                    }
                },
                getString(R.string.action_cancel) to { },
            ),
        )
    }

    private fun offerRuntimeInstall(source: XCoreRuntimeSource, container: LinearLayout) {
        if (runtimeInstallRunning) {
            notify("an install is already running")
            return
        }
        val policy = graph.runtimeEngine.fetchPolicy()
        if (!policy.allowsNetworkFetch) {
            showInfo(
                title = getString(R.string.settings_fetch_refused_title),
                rows = listOf("channel" to graph.config.value.runtimeChannel.name.lowercase()),
                note = policy.refusal().format(),
            )
            return
        }
        showInfo(
            title = getString(R.string.settings_source_title, source.displayName),
            rows = listOf(
                "java" to source.javaMajor.toString(),
                "abi" to source.abi,
                "publisher version" to source.versionLabel,
                "archive" to source.archiveFormat.name.lowercase(),
                "compressed" to formatBytes(source.compressedBytes),
                "installed size" to "${source.installedSizeKib / 1024} MB",
                "sha256" to source.sha256Hex.take(24) + "…",
                "sha1" to (source.sha1Hex ?: getString(R.string.settings_sha_missing)),
                "licence" to XCoreLicensePolicy.canonicalize(source.licenseId),
                "host" to source.url,
                "needs from elsewhere" to source.dependsOn.joinToString().ifBlank { "none" },
            ),
            note = getString(R.string.settings_source_note) + " " + source.notice,
            actions = listOf(
                "Fetch and verify" to { installRuntime(source, container) },
                getString(R.string.action_cancel) to { },
            ),
        )
    }

    private fun installRuntime(source: XCoreRuntimeSource, container: LinearLayout) {
        runtimeInstallRunning = true
        val progressRow = LayoutInflater.from(this).inflate(R.layout.item_setting_note, container, false) as TextView
        container.addView(progressRow)
        graph.run(
            {
                graph.runtimeEngine.install(
                    source,
                    XCoreTransferListener { progress ->
                        runOnUiThread {
                            progressRow.text = getString(
                                R.string.settings_install_progress,
                                source.displayName,
                                (progress.aggregateFraction * 100f).toInt(),
                            )
                        }
                    },
                )
            },
        ) { outcome ->
            runtimeInstallRunning = false
            val install = outcome.valueOrNull()
            if (install == null) {
                progressRow.text = getString(
                    R.string.settings_install_failed,
                    outcome.errorOrNull()?.format() ?: "no result",
                )
            } else {
                progressRow.text = getString(R.string.settings_installed, source.displayName, install.describe())
                notify("Java ${source.javaMajor} installed")
            }
        }
    }

    private fun showNatives(info: XCoreRuntimeInfo) {
        graph.run({ graph.runtimeEngine.inspectNatives(info) }) { outcome ->
            val inventory = outcome.valueOrNull()
            showInfo(
                title = getString(R.string.row_natives_title),
                rows = inventory?.let {
                    it.files.map { file -> file.role.fileName to file.describe() } +
                        ("home" to it.runtimeHome.absolutePath) +
                        ("searched" to it.searched.joinToString { dir -> dir.name }.ifBlank { "none" })
                } ?: listOf("error" to (outcome.errorOrNull()?.format() ?: "the survey did not run")),
                note = inventory?.summarize().orEmpty(),
            )
        }
    }

    private fun showJvmLoad(info: XCoreRuntimeInfo) {
        graph.run({ graph.runtimeEngine.loadJvm(info) }) { outcome ->
            val loaded = outcome.valueOrNull()
            showInfo(
                title = getString(R.string.row_load_jvm_title),
                rows = loaded?.let {
                    listOf(
                        "library" to it.file.absolutePath,
                        "loaded" to it.loaded.toString(),
                        "message" to (it.message ?: "none"),
                    )
                } ?: listOf("error" to (outcome.errorOrNull()?.format() ?: "the load was not attempted")),
                note = "Whether the platform permits this is decided by the loader, not by the launcher.",
            )
        }
    }

    private fun showDirectories(layout: studio.xycore.xcore.data.XCoreStorageLayout) {
        showInfo(
            title = layout.plan.rootPath,
            rows = listOf("free" to formatBytes(layout.plan.usableBytes), "requested" to layout.plan.requested.name) +
                XCoreGameDirectory.REQUIRED.map { kind ->
                    kind.relativePath to (layout.dir(kind)?.let { dir ->
                        "${if (dir.isDirectory) "present" else "absent"} · ${dir.absolutePath}"
                    } ?: "not prepared")
                },
            note = getString(R.string.settings_sec_dirs_note),
        )
    }

    private fun inspectInstanceDirectory(instance: XCoreInstance) {
        graph.run({ graph.instances.inspectDirectory(instance.id) }) { outcome ->
            val directory = outcome.valueOrNull()
            showInfo(
                title = directory?.path ?: "Inspect failed",
                rows = directory?.let {
                    listOf(
                        "instance" to it.instanceId,
                        "exists" to it.exists.toString(),
                        "entries" to it.entries.toString(),
                        "bytes" to formatBytes(it.bytesUsed),
                        "writable" to it.writable.toString(),
                    )
                } ?: listOf("error" to (outcome.errorOrNull()?.format() ?: "unknown")),
            )
        }
    }

    private fun showNetworkChoices() {
        showChoice(
            title = getString(R.string.row_network_title),
            note = "Parallel transfers and the per-request timeout, both read by the download engine.",
            options = listOf(1, 2, 4, 8).map { count ->
                "$count parallel transfers" to {
                    saveConfig { current -> current.copy(maxConcurrentDownloads = count) }
                }
            } + listOf(10_000, 20_000, 30_000, 60_000).map { millis ->
                "${millis / 1000}s timeout" to {
                    saveConfig { current -> current.copy(httpTimeoutMillis = millis) }
                }
            },
        )
    }

    private fun chooseStorageRoot() {
        showChoice(
            title = getString(R.string.row_storage_root_title),
            note = getString(R.string.settings_sec_root_note),
            options = XCoreStorageRoot.entries.map { root ->
                root.name.lowercase() to {
                    saveConfig { current -> current.copy(storageRoot = root) }
                    notify(getString(R.string.settings_root_stored, root.name.lowercase()))
                }
            },
        )
    }

    private fun confirmReset() {
        showInfo(
            title = getString(R.string.reset_title),
            rows = listOf("schema" to "v${graph.config.value.schemaVersion}"),
            note = getString(R.string.reset_note),
            actions = listOf(
                getString(R.string.action_reset) to {
                    graph.run({ graph.configUseCases.reset() }) { outcome ->
                        if (outcome.errorOrNull() == null) {
                            graph.run({ graph.engine.reloadConfig() }) {
                                notify(getString(R.string.settings_reset_done))
                                renderSettings()
                            }
                        } else {
                            showInfo(
                                title = "Reset failed",
                                rows = listOf("error" to outcome.errorOrNull()?.format().orEmpty()),
                            )
                        }
                    }
                },
                getString(R.string.action_cancel) to { },
            ),
        )
    }

    private fun showTermsSheet(accepted: Boolean, container: LinearLayout) {
        showInfo(
            title = getString(R.string.row_terms_title),
            rows = listOf("state" to getString(if (accepted) R.string.settings_eula_accepted else R.string.settings_eula_declined)),
            note = "Read it at $eulaUrl before accepting. The acceptance is a row in this app's database.",
            actions = buildList {
                if (!accepted) {
                    add(getString(R.string.eula_checkbox) to {
                        graph.run({ graph.installations.acceptTerms() }) { outcome ->
                            if (outcome.errorOrNull() != null) {
                                addNote(container, outcome.errorOrNull()?.format().orEmpty())
                            } else {
                                renderSettings()
                            }
                        }
                    })
                } else {
                    add(getString(R.string.settings_eula_revoke) to {
                        graph.run({ graph.installations.revokeTerms() }) { outcome ->
                            if (outcome.errorOrNull() != null) {
                                addNote(container, outcome.errorOrNull()?.format().orEmpty())
                            } else {
                                renderSettings()
                            }
                        }
                    })
                }
                add(getString(R.string.action_close) to { })
            },
        )
    }

    private fun showRuntimeLicences() {
        showInfo(
            title = getString(R.string.row_licence_title),
            rows = XCoreRuntimeSource.CATALOGUE.map { source ->
                source.displayName to "${XCoreLicensePolicy.canonicalize(source.licenseId)} - " +
                    XCoreLicensePolicy.describe(source.licenseId)
            },
            note = "The launcher ships no runtime of its own. These are the archives it may fetch, " +
                "and the licences their publishers declare.",
        )
    }

    private fun showCapabilities() {
        val report = graph.capabilityReport()
        showInfo(
            title = getString(R.string.row_capabilities_title),
            rows = XCoreCapability.entries.map { capability ->
                capability.displayName to getString(
                    R.string.settings_capability_row,
                    if (capability.serviceId in report.installedServiceIds) "installed" else "absent",
                    gapFor(capability),
                )
            },
            note = report.summarize() + ". " + report.inconsistencies.joinToString().ifBlank {
                "The capability table and the catalog agree."
            },
        )
    }

    private fun installedVersionName(): String =
        packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"

    private fun loadUiSettings() {
        graph.run({ graph.preferences.uiSettingsNow().asXCoreSuccess() }) { outcome ->
            outcome.valueOrNull()?.let { stored ->
                uiSettings = stored
                XCoreMotion.enabled = stored.animationsEnabled
                gateOnSignIn(stored)
            }
            if (tab == LauncherTab.SETTINGS) renderSettings()
        }
    }

    private fun setAnimationsEnabled(enabled: Boolean) {
        XCoreMotion.enabled = enabled
        findViewById<View>(R.id.root)?.let { XCoreMotion.applyPressStateToTree(it) }
        val next = uiSettings.copy(animationsEnabled = enabled)
        graph.run({ graph.preferences.setUiSettings(next) }) { outcome ->
            outcome.errorOrNull()?.let { problem ->
                XCoreMotion.enabled = uiSettings.animationsEnabled
                findViewById<View>(R.id.root)?.let { XCoreMotion.applyPressStateToTree(it) }
                renderSettings()
                showInfo(title = "Could not store that choice", rows = listOf("error" to playerText(problem.format())))
                return@run
            }
            uiSettings = next
            if (tab == LauncherTab.SETTINGS) renderSettings()
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
        // The plaque is sized from its rows, so it is built once they are all in place.
        lateinit var dialog: Dialog
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
        dialog = dialogFor(sheet)
        addDialogAction(actions, dialog, getString(R.string.action_close)) { }
        dialog.show()
    }

    private fun saveConfig(transform: (XCoreConfig) -> XCoreConfig) {
        graph.run({ graph.configUseCases.update(transform) }) { outcome ->
            outcome.errorOrNull()?.let { problem ->
                showInfo(
                    title = "That value was not saved",
                    rows = listOf("error" to playerText(problem.format())),
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
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
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
        instanceDropdown?.dismiss()
        playTab = PlayTab.PLAY
        if (!onHome) {
            XCoreMotion.animatePageExit(screenContainer)
            XCoreMotion.animatePageEnter(homeScreen)
            onHome = true
        }
        paintPlayHeader()
        renderInstances()
    }

    /** The Play page owns the header and the rail highlight the same way a destination does. */
    private fun paintPlayHeader() {
        paintRail(null)
        productLine.setText(R.string.home_product_line)
        paintPlayTabs(showStrip = true)
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
        LauncherTab.HOME -> TabHue(R.color.xcore_accent, R.drawable.bg_stat_bar_green)
        LauncherTab.INSTANCES -> TabHue(R.color.xcore_accent, R.drawable.bg_stat_bar_green)
        LauncherTab.FILES -> TabHue(R.color.xcore_orange, R.drawable.bg_stat_bar_orange)
        LauncherTab.LIBRARY -> TabHue(R.color.xcore_blue, R.drawable.bg_stat_bar_blue)
        LauncherTab.STATS -> TabHue(R.color.xcore_violet, R.drawable.bg_stat_bar_violet)
        LauncherTab.LOGS -> TabHue(R.color.xcore_amber, R.drawable.bg_stat_bar_amber)
        LauncherTab.CONTROLS -> TabHue(R.color.xcore_teal, R.drawable.bg_stat_bar_teal)
        LauncherTab.NEWS -> TabHue(R.color.xcore_accent, R.drawable.bg_stat_bar_green)
        LauncherTab.PATCH_NOTES -> TabHue(R.color.xcore_blue, R.drawable.bg_stat_bar_blue)
        LauncherTab.SETTINGS -> TabHue(R.color.xcore_blue, R.drawable.bg_stat_bar_blue)
    }

    /** Hue of the screen on screen right now; rows built while a screen is open read from it. */
    private var currentHue: TabHue = hueFor(LauncherTab.INSTANCES)

    private fun applyTab(target: LauncherTab) {
        tab = target
        playTab = when (target) {
            LauncherTab.INSTANCES -> PlayTab.INSTALLATIONS
            LauncherTab.PATCH_NOTES -> PlayTab.PATCH_NOTES
            else -> playTab
        }
        val screens = linkedMapOf(
            LauncherTab.HOME to screenHomeSlides,
            LauncherTab.INSTANCES to screenInstances,
            LauncherTab.NEWS to screenNews,
            LauncherTab.PATCH_NOTES to screenPatch,
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
        instanceDropdown?.dismiss()
        if (target == LauncherTab.HOME) {
            paintSlideDots(slideIndex)
            slideFlipper.postDelayed(slideTicker, 400L)
        } else {
            slideFlipper.removeCallbacks(slideTicker)
        }

        val hue = hueFor(target)
        currentHue = hue
        val ownsPlayTabs = target == LauncherTab.INSTANCES || target == LauncherTab.PATCH_NOTES
        productLine.text = if (ownsPlayTabs) {
            getString(R.string.home_product_line)
        } else {
            getString(labelFor(target))
        }
        paintPlayTabs(showStrip = ownsPlayTabs)
        paintRail(target)
        renderRailStatus()
        when (target) {
            LauncherTab.FILES -> refreshFiles()
            LauncherTab.LIBRARY -> refreshLibrary()
            LauncherTab.STATS -> refreshStats()
            LauncherTab.LOGS -> refreshLogs()
            LauncherTab.CONTROLS -> refreshControls()
            LauncherTab.NEWS -> refreshManifest()
            LauncherTab.PATCH_NOTES -> refreshManifest()
            LauncherTab.SETTINGS -> renderSettings()
            LauncherTab.HOME -> renderHomeUpdates()
            LauncherTab.INSTANCES -> Unit
        }
    }

    private fun labelFor(target: LauncherTab): Int = when (target) {
        LauncherTab.HOME -> R.string.rail_home
        LauncherTab.INSTANCES -> R.string.section_my_instances
        LauncherTab.NEWS -> R.string.news_title
        LauncherTab.PATCH_NOTES -> R.string.patch_title
        LauncherTab.FILES -> R.string.nav_files
        LauncherTab.LIBRARY -> R.string.library_title
        LauncherTab.STATS -> R.string.stats_title
        LauncherTab.LOGS -> R.string.logs_title
        LauncherTab.CONTROLS -> R.string.controls_title
        LauncherTab.SETTINGS -> R.string.nav_settings
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
        renderPlaySub()
    }

    /**
     * The line under PLAY: what the block will actually do.
     *
     * It states the version's Java requirement and the heap the argument builder would grant, both
     * of which are decisions the runtime engine already makes, so the caption is a readout of the
     * pending plan rather than a promise about one.
     */
    private fun renderPlaySub() {
        val instance = instances.firstOrNull { candidate -> candidate.id == selectedId }
        if (instance == null) {
            playSub.text = getString(R.string.home_play_pick)
            return
        }
        val state = states[instance.versionId]
        if (state != null && !state.isReady) {
            playSub.text = getString(R.string.home_play_install)
            return
        }
        val java = graph.runtimeEngine.requirementForVersionId(instance.versionId)?.javaMajor?.toString() ?: "?"
        val stored = heapPattern.find(instance.jvmArguments)?.groupValues?.get(1)?.toIntOrNull()
        playSub.text = if (stored == null) {
            getString(R.string.home_play_auto_heap, instance.versionId, java)
        } else {
            val granted = XCoreJvmArguments(graph.deviceFacts).decideHeap(stored).grantedMb
            getString(R.string.home_play_ready, instance.versionId, java, granted)
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

    /**
     * Dialogs are set in the game's own face. A @font reference in a layout's fontFamily attribute
     * needs API 26 and this shell still runs on 24, so the face is put on every text view in the
     * sheet here - which is also why a row added after the plaque is built gets its own pass.
     */
    private fun applyPixelFace(root: View) {
        val face = pixelFace ?: return
        if (root is TextView) {
            val wasBold = root.typeface?.isBold == true
            root.typeface = face
            root.paint.isFakeBoldText = wasBold
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) applyPixelFace(root.getChildAt(index))
        }
    }

    private fun dialogFor(content: View, widthDp: Int = 300, maxHeightFraction: Float = 0.66f): Dialog = Dialog(this).apply {
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        setContentView(content)
        applyPixelFace(content)
        window?.let { sheet ->
            sheet.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            sheet.setWindowAnimations(
                if (XCoreMotion.enabled) R.style.XCoreDialogAnimation else R.style.XCoreDialogAnimationInstant
            )
            // The player asked for small plaques: never wider than the caller's own budget or 45% of
            // the window, and never tall enough to crowd the landscape screen.
            val widthPx = minOf((widthDp * resources.displayMetrics.density).toInt(), (resources.displayMetrics.widthPixels * 0.45f).toInt())
            val heightPx = measuredDialogHeight(content, widthPx, maxHeightFraction)
            sheet.setLayout(widthPx, heightPx ?: ViewGroup.LayoutParams.WRAP_CONTENT)
            sheet.setGravity(Gravity.CENTER)
        }
        setCancelable(true)
    }

    /**
     * A dialog is a card, not a page, so it is measured at its own width and capped below the
     * screen height. A content view whose rows have not been added yet reports nothing and keeps
     * wrapping instead of collapsing to the cap.
     */
    private fun measuredDialogHeight(content: View, widthPx: Int, maxHeightFraction: Float): Int? {
        content.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val natural = content.measuredHeight
        if (natural <= 0) return null
        return minOf(natural, (resources.displayMetrics.heightPixels * maxHeightFraction).toInt())
    }

    private fun showInfo(
        title: String,
        rows: List<Pair<String, String>>,
        note: String? = null,
        actions: List<Pair<String, () -> Unit>> = emptyList(),
    ) {
        val sheet = inflateDialog(R.layout.dialog_info)
        sheet.findViewById<TextView>(R.id.tv_dialog_title).text = playerText(title)
        val container = sheet.findViewById<LinearLayout>(R.id.container_dialog_rows)
        rows.forEach { (key, value) ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_key_value, container, false)
            row.findViewById<TextView>(R.id.tv_kv_key).text = playerText(key)
            row.findViewById<TextView>(R.id.tv_kv_value).text = playerText(value)
            container.addView(row)
        }
        val noteView = sheet.findViewById<TextView>(R.id.tv_dialog_note)
        noteView.text = note?.let(::playerText).orEmpty()
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
            pixelFace?.let { face ->
                typeface = face
                paint.isFakeBoldText = true
            } ?: setTypeface(null, android.graphics.Typeface.BOLD)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(14, 18, 14, 18)
            background = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_mc_button)
            setTextColor(color(R.color.mc_white))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (parent.childCount > 0) marginStart = 8
            }
            XCoreMotion.applyButtonTouchEffect(this)
            setOnClickListener {
                dialog.dismiss()
                action()
            }
        }
        parent.addView(button)
    }

    private fun color(id: Int): Int = androidx.core.content.ContextCompat.getColor(this, id)

    /**
     * How the launcher names a capability the player cannot use yet. The domain keeps a build phase
     * for its own bookkeeping, but the screens never show it - a gap is either not added or soon.
     */
    private fun gapFor(capability: XCoreCapability): String =
        getString(if (capability.implemented) R.string.gap_soon else R.string.gap_not_added)

    /** Strips the phase numbering the domain puts in its messages before any of it reaches a screen. */
    private fun playerText(text: String): String = text
        .replace(Regex("(?i);?\\s*it is scheduled for\\s+phase\\s+\\d+"), "")
        .replace(Regex("(?i)\\(\\s*phase\\s+\\d+\\s*\\)"), "")
        .replace(Regex("(?i)\\bphase\\s+\\d+\\b"), "a later step")
        .replace(Regex("\\s{2,}"), " ")
        .trim()

    /**
     * What the launcher tells a player about a capability. The domain keeps phase numbers so its
     * readiness checks are testable, but a phase is a build-plan detail and the screens say only
     * whether the thing exists in this build.
     */
    private enum class LauncherTab { HOME, INSTANCES, NEWS, PATCH_NOTES, FILES, LIBRARY, STATS, LOGS, CONTROLS, SETTINGS }

    /** The four tabs the Java Edition page owns. */
    private enum class PlayTab { PLAY, INSTALLATIONS, SKINS, PATCH_NOTES }

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

    /**
     * The settings categories, each naming the rail row and the panel it owns.
     *
     * A category is here only where the launcher can change or measure something real. Renderer and
     * Account stay as panels because their content is the capability read and the stored EULA row -
     * not because a switch was invented for them.
     */
    private enum class SettingsCategory(
        val labelRes: Int,
        val iconRes: Int,
        val panelId: Int,
        val rowsId: Int,
        val accentColorRes: Int,
    ) {
        RUNTIME(R.string.settings_cat_runtime, R.drawable.ic_coffee, R.id.panel_runtime, R.id.rows_runtime, R.color.sec_java),
        MEMORY(R.string.settings_cat_memory, R.drawable.ic_ram, R.id.panel_memory, R.id.rows_memory, R.color.sec_memory),
        RESOLUTION(R.string.settings_cat_resolution, R.drawable.ic_monitor, R.id.panel_resolution, R.id.rows_resolution, R.color.sec_resolution),
        RENDERER(R.string.settings_cat_renderer, R.drawable.ic_renderer, R.id.panel_renderer, R.id.rows_renderer, R.color.sec_renderer),
        ARGUMENTS(R.string.settings_cat_arguments, R.drawable.ic_terminal, R.id.panel_arguments, R.id.rows_arguments, R.color.sec_arguments),
        STORAGE(R.string.settings_cat_storage, R.drawable.ic_folder, R.id.panel_storage, R.id.rows_storage, R.color.sec_storage),
        APPEARANCE(R.string.settings_cat_appearance, R.drawable.ic_palette, R.id.panel_appearance, R.id.rows_appearance, R.color.sec_appearance),
        ACCOUNT(R.string.settings_cat_account, R.drawable.ic_person, R.id.panel_account, R.id.rows_account, R.color.sec_account),
        ABOUT(R.string.settings_cat_about, R.drawable.ic_info, R.id.panel_about, R.id.rows_about, R.color.sec_about),
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
