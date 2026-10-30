package studio.xycore.xcore

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import studio.xycore.xcore.core.XCoreEngineState
import studio.xycore.xcore.core.XCoreError
import studio.xycore.xcore.core.XCoreResult
import studio.xycore.xcore.core.asXCoreFailure
import studio.xycore.xcore.core.errorOrNull
import studio.xycore.xcore.core.valueOrNull
import studio.xycore.xcore.data.XCoreGameDirectory
import studio.xycore.xcore.data.XCoreInstance
import studio.xycore.xcore.data.XCoreLoaderKind
import studio.xycore.xcore.domain.XCorePreflightReport
import studio.xycore.xcore.ui.DiagnosticsScreen
import studio.xycore.xcore.ui.FilesScreen
import studio.xycore.xcore.ui.GrassBlock
import studio.xycore.xcore.ui.InstancesScreen
import studio.xycore.xcore.ui.LauncherHeader
import studio.xycore.xcore.ui.MessageDialog
import studio.xycore.xcore.ui.MessageDialogCard
import studio.xycore.xcore.ui.Mono
import studio.xycore.xcore.ui.SettingsScreen
import studio.xycore.xcore.ui.SettingsSectionScreen
import studio.xycore.xcore.ui.StorageRow
import studio.xycore.xcore.ui.SteveHead
import studio.xycore.xcore.ui.XCoreSettingsSection
import studio.xycore.xcore.ui.XCoreUi
import studio.xycore.xcore.ui.formatBytes

/**
 * The launcher shell. Every row, size and status shown here comes from the engine, the metadata
 * database or the filesystem; where a capability does not exist yet the screen names the phase that
 * will supply it instead of offering a control nothing reads.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF070A08.toInt()
        window.navigationBarColor = 0xFF070A08.toInt()
        val graph = (application as XCoreApplication).graph
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = XCoreUi.Accent,
                    onPrimary = XCoreUi.Background,
                    background = XCoreUi.Background,
                    onBackground = XCoreUi.Text,
                    surface = XCoreUi.Card,
                    onSurface = XCoreUi.Text,
                    surfaceVariant = XCoreUi.CardBorder,
                    onSurfaceVariant = XCoreUi.TextMuted,
                    error = XCoreUi.Danger,
                ),
            ) {
                LauncherApp(graph)
            }
        }
    }
}

private enum class LauncherTab { INSTANCES, FILES, SETTINGS }

@Composable
private fun LauncherApp(graph: XCoreGraph) {
    var tab by remember { mutableStateOf(LauncherTab.INSTANCES) }
    var section by remember { mutableStateOf<XCoreSettingsSection?>(null) }
    var diagnostics by remember { mutableStateOf(false) }
    var instances by remember { mutableStateOf<List<XCoreInstance>>(emptyList()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf<List<StorageRow>>(emptyList()) }
    var filesProblem by remember { mutableStateOf<String?>(null) }
    var createOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<MessageDialog?>(null) }

    val abi = remember { Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a" }
    val engineState by graph.engineState.collectAsState()
    val openSection = section

    fun reloadInstances() {
        graph.run({ graph.instances.list() }) { outcome ->
            instances = outcome.valueOrNull().orEmpty()
            outcome.errorOrNull()?.let { dialog = MessageDialog("Instances", listOf(it.format())) }
        }
    }

    fun reloadFiles() {
        graph.run({
            val layout = graph.storageLayout()
                ?: return@run XCoreError.Storage("no storage layout is prepared; the engine has not probed one").asXCoreFailure()
            withContext(Dispatchers.IO) {
                val rows = XCoreGameDirectory.REQUIRED.mapNotNull { kind ->
                    val directory = layout.dir(kind) ?: return@mapNotNull null
                    val children = directory.walkTopDown().filter { it.isFile }.toList()
                    StorageRow(
                        name = kind.relativePath,
                        path = directory.absolutePath,
                        files = children.size,
                        bytes = children.sumOf { it.length() },
                        purpose = kind.purpose,
                    )
                }
                XCoreResult.Success(rows)
            }
        }) { outcome ->
            files = outcome.valueOrNull().orEmpty()
            filesProblem = outcome.errorOrNull()?.format()
        }
    }

    LaunchedEffect(tab) {
        if (tab == LauncherTab.INSTANCES) reloadInstances()
        if (tab == LauncherTab.FILES) reloadFiles()
    }

    LaunchedEffect(Unit) {
        graph.run({ graph.instances.selected() }) { selectedId = it.valueOrNull()?.id }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(XCoreUi.Background),
    ) {
        if (tab == LauncherTab.SETTINGS && section == null && !diagnostics) {
            SettingsBackdrop()
        }

        Column(modifier = Modifier.fillMaxSize()) {
            LauncherHeader(
                subtitle = when {
                    diagnostics -> "Engine diagnostics"
                    openSection != null -> openSection.subtitle
                    tab == LauncherTab.FILES -> "Game tree files"
                    tab == LauncherTab.SETTINGS -> "Settings"
                    else -> "Play • Explore • Create"
                },
                logo = {
                    if (tab == LauncherTab.SETTINGS) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_xcore_logo),
                            contentDescription = "X-Core Logo",
                            modifier = Modifier.size(40.dp),
                        )
                    } else {
                        GrassBlock(modifier = Modifier.size(40.dp))
                    }
                },
                account = {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(XCoreUi.Card)
                            .border(1.dp, XCoreUi.CardBorder, RoundedCornerShape(12.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SteveHead(modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            painter = painterResource(id = R.drawable.ic_chevron_down),
                            contentDescription = null,
                            tint = XCoreUi.TextMuted,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                },
            )

            Box(modifier = Modifier.weight(1f)) {
                val running = engineState == XCoreEngineState.READY || engineState == XCoreEngineState.DEGRADED
                when {
                    diagnostics -> DiagnosticsScreen(graph, onBack = { diagnostics = false })

                    openSection != null -> SettingsSectionScreen(
                        section = openSection,
                        graph = graph,
                        onBack = { section = null },
                        onOpenDiagnostics = { diagnostics = true },
                    )

                    tab == LauncherTab.INSTANCES -> InstancesScreen(
                        instances = instances,
                        selectedId = selectedId,
                        engineRunning = running,
                        onSelect = { id ->
                            graph.run({ graph.instances.select(id) }) {
                                selectedId = id
                                reloadInstances()
                            }
                        },
                        onDelete = { id ->
                            graph.run({ graph.instances.delete(id, removeFiles = true) }) { outcome ->
                                val deletion = outcome.valueOrNull()
                                dialog = deletion?.let {
                                    MessageDialog(
                                        title = "Deleted '${it.instance.name}'",
                                        lines = listOf(
                                            "row removed: ${it.rowRemoved}",
                                            "bytes freed: ${formatBytes(it.filesRemoved)}",
                                            "path: ${it.path ?: "not resolved"}",
                                            it.error?.format() ?: "files removed cleanly",
                                        ),
                                    )
                                } ?: MessageDialog("Delete failed", listOf(outcome.errorOrNull()?.format() ?: "unknown"))
                                reloadInstances()
                            }
                        },
                        onCreateRequested = { createOpen = true },
                        onInspect = { id ->
                            graph.run({ graph.instances.inspectDirectory(id) }) { outcome ->
                                val directory = outcome.valueOrNull()
                                dialog = directory?.let {
                                    MessageDialog(
                                        title = it.path,
                                        lines = listOf(
                                            "exists: ${it.exists}",
                                            "entries: ${it.entries}",
                                            "bytes: ${formatBytes(it.bytesUsed)}",
                                            "writable: ${it.writable}",
                                        ),
                                    )
                                } ?: MessageDialog("Inspect failed", listOf(outcome.errorOrNull()?.format() ?: "unknown"))
                            }
                        },
                    )

                    tab == LauncherTab.FILES -> FilesScreen(
                        rows = files,
                        problem = filesProblem,
                        onOpen = { row ->
                            dialog = MessageDialog(
                                title = row.name,
                                lines = listOf(row.path, "${row.files} files", formatBytes(row.bytes), row.purpose),
                            )
                        },
                    )

                    else -> SettingsScreen(onOpen = { section = it })
                }
            }

            BottomBar(
                tab = tab,
                onSelect = {
                    tab = it
                    section = null
                    diagnostics = false
                },
            )

            PlayBar(
                visible = tab == LauncherTab.INSTANCES,
                enabled = selectedId != null && !busy,
                label = if (busy) "Working" else "Play",
                onPlay = {
                    val instance = instances.firstOrNull { it.id == selectedId }
                    if (instance == null) {
                        dialog = MessageDialog(
                            title = "Nothing selected",
                            lines = listOf("Pick an instance first. Play never guesses which one you meant."),
                        )
                    } else {
                        busy = true
                        graph.run({ graph.launches.preflight(instance.id, abi, graph.config.value) }) { outcome ->
                            busy = false
                            dialog = outcome.valueOrNull()?.let { preflightDialog(it) }
                                ?: MessageDialog("Preflight failed", listOf(outcome.errorOrNull()?.format() ?: "unknown"))
                        }
                    }
                },
            )
        }
    }

    dialog?.let { message ->
        Dialog(onDismissRequest = { dialog = null }) {
            MessageDialogCard(message)
        }
    }

    if (createOpen) {
        CreateInstanceDialog(
            onDismiss = { createOpen = false },
            onCreate = { name, version, loader ->
                createOpen = false
                graph.run({ graph.instances.create(name, version, loader, "", "") }) { outcome ->
                    val created = outcome.valueOrNull()
                    if (created != null) {
                        selectedId = created.id
                        reloadInstances()
                    } else {
                        dialog = MessageDialog("Could not create instance", listOf(outcome.errorOrNull()?.format() ?: "unknown"))
                    }
                }
            },
        )
    }
}

private fun preflightDialog(report: XCorePreflightReport): MessageDialog = MessageDialog(
    title = if (report.ready) "Ready to launch" else "Blocked",
    lines = report.checks.map { check ->
        "${if (check.passed) "+ " else "- "}${check.label}: ${check.detail}"
    },
    note = "This is the preflight only. The launch pipeline itself is Phase 6, so no process was started.",
)

@Composable
private fun SettingsBackdrop() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 64.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Image(
            painter = painterResource(id = R.drawable.hero_panorama),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .height(230.dp)
                .alpha(0.22f),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun BottomBar(
    tab: LauncherTab,
    onSelect: (LauncherTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(XCoreUi.Card)
            .border(1.dp, XCoreUi.CardBorder, RoundedCornerShape(18.dp))
            .padding(vertical = 8.dp),
    ) {
        LauncherTab.entries.forEach { entry ->
            val active = entry == tab
            val iconRes = when (entry) {
                LauncherTab.INSTANCES -> R.drawable.ic_cube
                LauncherTab.FILES -> R.drawable.ic_folder
                LauncherTab.SETTINGS -> R.drawable.ic_gear
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(entry) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = entry.name,
                    tint = if (active) XCoreUi.Accent else XCoreUi.TextMuted,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.height(4.dp))
                Mono(
                    text = when (entry) {
                        LauncherTab.INSTANCES -> "My Instances"
                        LauncherTab.FILES -> "Files"
                        LauncherTab.SETTINGS -> "Settings"
                    },
                    size = MaterialTheme.typography.labelMedium.fontSize,
                    color = if (active) XCoreUi.Accent else XCoreUi.TextMuted,
                    weight = if (active) FontWeight.Bold else FontWeight.Normal,
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .width(38.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(if (active) XCoreUi.Accent else Color.Transparent),
                )
            }
        }
    }
}

@Composable
private fun PlayBar(
    visible: Boolean,
    enabled: Boolean,
    label: String,
    onPlay: () -> Unit,
) {
    if (!visible) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 12.dp)
            .height(58.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.horizontalGradient(listOf(XCoreUi.AccentDeep, XCoreUi.Accent)))
            .clickable(enabled = enabled, onClick = onPlay),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_play),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
            Mono(
                text = label,
                size = MaterialTheme.typography.headlineSmall.fontSize,
                weight = FontWeight.Bold,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun CreateInstanceDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, version: String, loader: XCoreLoaderKind) -> Unit,
) {
    var name by remember { mutableStateOf("Vanilla") }
    var version by remember { mutableStateOf("1.21.4") }
    var loader by remember { mutableStateOf(XCoreLoaderKind.VANILLA) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(XCoreUi.Card)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Mono(
                text = "New instance",
                size = MaterialTheme.typography.titleLarge.fontSize,
                weight = FontWeight.Bold,
            )
            Mono(
                text = "This writes a metadata row and creates the instance directory. Fetching the " +
                    "version files needs the manifest reader and the download engine (Phases 2 and 3).",
                size = MaterialTheme.typography.bodySmall.fontSize,
                color = XCoreUi.TextMuted,
            )
            LabeledField("Name", name) { name = it }
            LabeledField("Version id", version) { version = it }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                XCoreLoaderKind.entries.take(3).forEach { kind ->
                    val selected = kind == loader
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) XCoreUi.AccentSoft else XCoreUi.Background)
                            .clickable { loader = kind }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Mono(
                            text = kind.displayName,
                            size = MaterialTheme.typography.labelLarge.fontSize,
                            color = if (selected) XCoreUi.Accent else XCoreUi.TextMuted,
                        )
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Mono("Cancel", color = XCoreUi.TextMuted)
                }
                Button(
                    onClick = { onCreate(name.trim(), version.trim(), loader) },
                    modifier = Modifier.weight(1f),
                    enabled = name.isNotBlank() && version.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = XCoreUi.AccentDeep, contentColor = Color.White),
                ) {
                    Mono("Create", weight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Mono(label, size = MaterialTheme.typography.bodySmall.fontSize, color = XCoreUi.TextMuted)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(XCoreUi.Background)
                .padding(10.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                color = XCoreUi.Text,
            ),
        )
    }
}
