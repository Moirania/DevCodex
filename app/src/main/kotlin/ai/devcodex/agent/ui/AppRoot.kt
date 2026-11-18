package ai.devcodex.agent.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ai.devcodex.agent.AppViewModel
import ai.devcodex.agent.data.AppSettings
import ai.devcodex.agent.ui.screens.ChatScreen
import ai.devcodex.agent.ui.screens.PluginsScreen
import ai.devcodex.agent.ui.screens.ProvidersScreen
import ai.devcodex.agent.ui.screens.SettingsScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

@Composable
fun AppRoot(vm: AppViewModel) {
    val tabs = remember {
        listOf(
            Tab("chat", "对话", Icons.Filled.Chat),
            Tab("workspace", "工作区", Icons.Filled.Folder),
            Tab("providers", "提供商", Icons.Filled.AutoAwesome),
            Tab("plugins", "插件", Icons.Filled.Extension),
            Tab("extensions", "扩展", Icons.Filled.Download),
            Tab("settings", "设置", Icons.Filled.Settings),
        )
    }
    var tabIdx by remember { mutableIntStateOf(0) }
    val nav = rememberNavController()
    // ===== 完整响应式断点（Material3 WindowSizeClass 手写版）=====
    // compact <600dp：手机单栏  medium 600-840dp：底部导航  expanded ≥840dp：左侧常驻 NavigationRail
    var isExpanded by remember { mutableStateOf(false) }
    var isMedium by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val settings by vm.settings.collectAsState(initial = AppSettings())
    val theme = settings.theme

    fun navigate(i: Int) {
        // v0.4.6 重写（装机日志实锤：点"对话"先闪 settings 再进 chat，0.8s 双跳）：
        // 原 popUpTo+restoreState 在栈有中间页时恢复出意外中间态。改为 popBackStack 优先——
        // 栈中有目标直接弹回（一次到位不经中间页闪烁），没有才 navigate 压栈。
        val route = tabs[i].route
        val currentRoute = nav.currentDestination?.route
        ai.devcodex.agent.util.DebugLog.i("Nav", "navigate tab=$route fromTabIdx=$tabIdx fromRoute=$currentRoute")
        if (currentRoute == route) {
            ai.devcodex.agent.util.DebugLog.i("Nav", "navigate skipped (already at $route)")
            return
        }
        tabIdx = i
        if (nav.popBackStack(route, inclusive = false)) {
            ai.devcodex.agent.util.DebugLog.i("Nav", "popped back to $route")
            return
        }
        nav.navigate(route) { launchSingleTop = true }
    }

    // 断点检测（LocalConfiguration 尺寸变化重算）
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    isExpanded = cfg.screenWidthDp >= 840
    isMedium = cfg.screenWidthDp >= 600

    LaunchedEffect(Unit) {
        vm.toast.collect { msg -> msg?.let { snackbar.showSnackbar(it); vm.toast.value = null } }
    }
    // 页面跳转请求（沉浸模式设置出口等）
    LaunchedEffect(Unit) {
        vm.navRequest.collect { req ->
            if (req != null) {
                ai.devcodex.agent.util.DebugLog.i("Nav", "navRequest=$req (tabIdx=$tabIdx route=${nav.currentDestination?.route})")
                val immersiveNow = theme.immersive || theme.navPosition == "immersive"
                when {
                    req == "back" -> runCatching { nav.popBackStack() }
                    req == "terminal" -> runCatching { nav.navigate("terminal") { launchSingleTop = true } }
                    req in listOf("stats", "about", "skills", "theme", "tools") -> {
                        // 设置子页：跳设置页（tab 或路由）并请求对应子页
                        vm.settingsPageRequest.value = req
                        if (immersiveNow) runCatching { nav.navigate("settings") { launchSingleTop = true } }
                        else { val i = tabs.indexOfFirst { it.route == "settings" }; if (i >= 0) navigate(i) }
                    }
                    else -> {
                        if (immersiveNow) runCatching { nav.navigate(req) { launchSingleTop = true } }
                        else {
                            val i = tabs.indexOfFirst { it.route == req }
                            if (i >= 0) navigate(i)
                        }
                    }
                }
                vm.navRequest.value = null
            }
        }
    }
    LaunchedEffect(Unit) {
        if (vm.activeSession.value == null) {
            vm.sessions.value.firstOrNull()?.let { vm.openSession(it.id) } ?: vm.openSession(null)
        }
    }

    // 目的地变化同步 tabIdx（系统返回键退出页面后防止索引失同步——"设置只能打开一次"的根因）
    androidx.compose.runtime.DisposableEffect(nav) {
        val listener = androidx.navigation.NavController.OnDestinationChangedListener { _, destination, _ ->
            val i = tabs.indexOfFirst { it.route == destination.route }
            if (i >= 0) tabIdx = i
            ai.devcodex.agent.util.DebugLog.i("Nav", "destination changed -> ${destination.route} (tabIdx=$tabIdx)")
        }
        nav.addOnDestinationChangedListener(listener)
        onDispose { nav.removeOnDestinationChangedListener(listener) }
    }
    // 沉浸模式逃生：一旦当前页不是 chat（如跳设置），立即恢复导航
    val showNav = !(theme.immersive && tabs[tabIdx].route == "chat" && nav.currentDestination?.route == "chat")

    // v0.4.7 比例缩放：全局字号系数（手机小屏 0.85 / 标准 1.0 / 平板 1.15 / 大屏 1.3），dp 不变只缩字体
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
            density.density, density.fontScale * (settings.theme.uiScale.takeIf { it in 0.7f..1.6f } ?: 1.0f),
        ),
    ) {
    Surface(color = MaterialTheme.colorScheme.background) {
        when {
            // 完整响应式：expanded（≥840dp）自动切左侧常驻 NavigationRail，手动 rail 设置同样生效
            (theme.navPosition == "rail" || isExpanded) && showNav -> {
                // 左侧导航栏（横屏/平板友好，可隐藏底栏）
                Row(Modifier.padding()) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        tabs.forEachIndexed { i, t ->
                            NavigationRailItem(
                                selected = tabIdx == i,
                                onClick = { navigate(i) },
                                icon = { Icon(t.icon, contentDescription = t.label) },
                                label = { Text(t.label) },
                            )
                        }
                    }
                    Scaffold(
                        snackbarHost = { SnackbarHost(snackbar) },
                        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                    ) { padding ->
                        NavHost(
                            navController = nav,
                            startDestination = "chat",
                            modifier = Modifier.padding(padding),
                        ) {
                            composable("chat") { ChatScreen(vm) }
                            composable("workspace") { ai.devcodex.agent.ui.screens.WorkspaceScreen(vm) }
                            composable("providers") { ProvidersScreen(vm) }
                            composable("plugins") { PluginsScreen(vm) }
                            composable("extensions") { ai.devcodex.agent.ui.screens.ExtensionsScreen(vm) }
                            composable("settings") { SettingsScreen(vm) }
                            composable("terminal") { ai.devcodex.agent.ui.screens.TerminalScreen(vm) }
                        }
                    }
                }
            }
            theme.navPosition == "immersive" || !showNav -> {
                // 沉浸模式：无导航栏，仅对话（从设置进出）
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbar) },
                    contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                ) { padding ->
                    NavHost(
                        navController = nav,
                        startDestination = "chat",
                        modifier = Modifier.padding(padding),
                    ) {
                        composable("chat") { ChatScreen(vm) }
                        composable("workspace") { ai.devcodex.agent.ui.screens.WorkspaceScreen(vm) }
                        composable("providers") { ProvidersScreen(vm) }
                        composable("plugins") { PluginsScreen(vm) }
                        composable("extensions") { ai.devcodex.agent.ui.screens.ExtensionsScreen(vm) }
                        composable("settings") { SettingsScreen(vm) }
                            composable("terminal") { ai.devcodex.agent.ui.screens.TerminalScreen(vm) }
                    }
                }
            }
            else -> {
                // 响应式：expanded=左侧常驻 NavigationRail / medium+compact=底部导航
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbar) },
                    contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                    bottomBar = {
                        if (showNav && !isExpanded) {
                            NavigationBar {
                                tabs.forEachIndexed { i, t ->
                                    NavigationBarItem(
                                        selected = tabIdx == i,
                                        onClick = { navigate(i) },
                                        icon = { Icon(t.icon, contentDescription = t.label) },
                                        label = { Text(t.label) },
                                    )
                                }
                            }
                        }
                    },
                ) { padding ->
                    NavHost(
                        navController = nav,
                        startDestination = "chat",
                        modifier = Modifier.padding(padding),
                    ) {
                        composable("chat") { ChatScreen(vm) }
                        composable("workspace") { ai.devcodex.agent.ui.screens.WorkspaceScreen(vm) }
                        composable("providers") { ProvidersScreen(vm) }
                        composable("plugins") { PluginsScreen(vm) }
                        composable("extensions") { ai.devcodex.agent.ui.screens.ExtensionsScreen(vm) }
                        composable("settings") { SettingsScreen(vm) }
                            composable("terminal") { ai.devcodex.agent.ui.screens.TerminalScreen(vm) }
                    }
                }
            }
        }
    }
    }
}
