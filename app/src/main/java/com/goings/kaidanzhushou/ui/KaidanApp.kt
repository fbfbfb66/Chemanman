package com.goings.kaidanzhushou.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.goings.kaidanzhushou.ui.theme.ErrorRed
import com.goings.kaidanzhushou.ui.theme.Success
import com.goings.kaidanzhushou.ui.theme.Warning as WarningColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    fun batch(id: String) = "batch/$id"
    fun camera(id: String) = "camera/$id"
    fun progress(id: String) = "progress/$id"
    fun review(batchId: String, recordId: String) = "review/$batchId/$recordId"
    fun export(id: String) = "export/$id"
}

@Composable
fun KaidanApp(viewModel: MainViewModel) {
    val nav = rememberNavController()
    var notice by remember { mutableStateOf<UiNotice?>(null) }
    val systemPadding = PaddingValues()
    LaunchedEffect(Unit) {
        viewModel.events.collectLatest {
            notice = it
            delay(if (it.kind == NoticeKind.SUCCESS) 2_000 else 4_000)
            notice = null
        }
    }
    Surface {
        Box(Modifier.fillMaxSize()) {
            NavHost(navController = nav, startDestination = Routes.HOME) {
                composable(Routes.HOME) {
                    HomeScreen(viewModel, systemPadding, onBatch = { nav.navigate(Routes.batch(it)) }, onSettings = { nav.navigate(Routes.SETTINGS) })
                }
                composable("batch/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                    val id = entry.arguments?.getString("id")!!
                    BatchScreen(viewModel, id, systemPadding, nav::popBackStack,
                        onCamera = { nav.navigate(Routes.camera(id)) },
                        onReview = { nav.navigate(Routes.review(id, it)) },
                        onExport = { nav.navigate(Routes.export(id)) })
                }
                composable("camera/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                    CameraScreen(viewModel, entry.arguments?.getString("id")!!, onDone = nav::popBackStack)
                }
                composable("progress/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                    val id = entry.arguments?.getString("id")!!
                    ProgressScreen(viewModel, id, systemPadding, nav::popBackStack, { nav.navigate(Routes.review(id, it)) })
                }
                composable("review/{batchId}/{recordId}", arguments = listOf(navArgument("batchId") { type = NavType.StringType }, navArgument("recordId") { type = NavType.StringType })) { entry ->
                    ReviewScreen(viewModel, entry.arguments?.getString("batchId")!!, entry.arguments?.getString("recordId")!!, systemPadding, nav::popBackStack)
                }
                composable("export/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                    ExportScreen(viewModel, entry.arguments?.getString("id")!!, systemPadding, nav::popBackStack) { recordId ->
                        nav.navigate(Routes.review(entry.arguments?.getString("id")!!, recordId))
                    }
                }
                composable(Routes.SETTINGS) { SettingsScreen(viewModel, systemPadding, nav::popBackStack) }
            }
            AnimatedVisibility(
                visible = notice != null,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp),
            ) {
                notice?.let { TopNotice(it) }
            }
        }
    }
}

@Composable
private fun TopNotice(notice: UiNotice) {
    val color = when (notice.kind) { NoticeKind.SUCCESS -> Success; NoticeKind.WARNING -> WarningColor; NoticeKind.ERROR -> ErrorRed }
    val icon = when (notice.kind) { NoticeKind.SUCCESS -> Icons.Rounded.CheckCircle; NoticeKind.WARNING -> Icons.Rounded.Warning; NoticeKind.ERROR -> Icons.Rounded.Error }
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.shadow(12.dp, RoundedCornerShape(22.dp)).background(Color.White, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color)
        Text(notice.text, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 8.dp))
    }
}
