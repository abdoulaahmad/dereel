package app.dereel.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.dereel.R
import app.dereel.container
import app.dereel.core.model.Platform
import app.dereel.status.ProtectionAlerts
import app.dereel.ui.activity.ActivityScreen
import app.dereel.ui.help.HelpScreen
import app.dereel.ui.home.HomeScreen
import app.dereel.ui.onboarding.OnboardingScreen
import app.dereel.ui.platform.PlatformScreen
import app.dereel.ui.settings.SettingsScreen
import app.dereel.ui.theme.DereelTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels { MainViewModel.factory(container) }

    /** Counts taps on the "protection has stopped" alert. Each one brings Home forward. */
    private var statusRequests by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) noteStatusRequest(intent)
        setContent { DereelTheme { DereelApp(vm, statusRequests) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        noteStatusRequest(intent)
    }

    private fun noteStatusRequest(intent: Intent?) {
        if (intent?.action == ProtectionAlerts.ACTION_SHOW_STATUS) statusRequests++
    }

    override fun onResume() {
        super.onResume()
        // UX-09, FR-36: fresh status check on foreground entry, including return from Android settings.
        vm.refreshStatus()
    }
}

private enum class TopLevel(val route: String, val label: Int, val icon: ImageVector) {
    HOME("home", R.string.nav_home, Icons.Default.Home),
    ACTIVITY("activity", R.string.nav_activity, Icons.Default.DateRange),
    SETTINGS("settings", R.string.nav_settings, Icons.Default.Settings),
}

@Composable
fun DereelApp(vm: MainViewModel, statusRequests: Int = 0) {
    val state by vm.state.collectAsStateWithLifecycle()
    val dialogs = remember { DialogState() }
    val context = LocalContext.current
    val opener = remember(context, vm, dialogs) { PlatformOpener(context, vm, dialogs) }

    CompositionLocalProvider(LocalDialogs provides dialogs) {
        when {
            !state.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            !state.preferences.onboardingComplete -> Scaffold { padding ->
                Box(Modifier.padding(padding)) { OnboardingScreen(state, vm) }
            }
            else -> MainScaffold(state, vm, opener, statusRequests)
        }
        AppDialogHost(state, vm, dialogs, opener)
    }
}

/** UX-01: three primary destinations. Platform controls open from Home. */
@Composable
private fun MainScaffold(state: MainState, vm: MainViewModel, opener: PlatformOpener, statusRequests: Int) {
    val nav = rememberNavController()
    // The alert promises "tap to see why and fix it": that is Home, whatever screen was open.
    LaunchedEffect(statusRequests) {
        if (statusRequests > 0) nav.popBackStack(TopLevel.HOME.route, inclusive = false)
    }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = TopLevel.entries.any { it.route == route } || route == null

    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    TopLevel.entries.forEach { item ->
                        NavigationBarItem(
                            selected = route == item.route,
                            onClick = {
                                nav.navigate(item.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(stringResource(item.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = TopLevel.HOME.route, modifier = Modifier.padding(padding)) {
            composable(TopLevel.HOME.route) {
                HomeScreen(state, vm, opener, onPlatformSettings = { nav.navigate("platform/${it.id}") })
            }
            composable(TopLevel.ACTIVITY.route) { ActivityScreen(state) }
            composable(TopLevel.SETTINGS.route) {
                SettingsScreen(
                    state,
                    vm,
                    onPlatform = { nav.navigate("platform/${it.id}") },
                    onHelp = { nav.navigate("help") },
                )
            }
            composable("platform/{id}") { entry ->
                val platform = Platform.fromId(entry.arguments?.getString("id"))
                if (platform != null) PlatformScreen(state, platform, vm, opener, onBack = { nav.popBackStack() })
            }
            composable("help") { HelpScreen(onBack = { nav.popBackStack() }) }
        }
    }
}
