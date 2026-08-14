package com.goings.kaidanzhushou.ui

import android.content.Intent
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.collectLatest

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    fun batch(id: String) = "batch/$id"
    fun camera(id: String) = "camera/$id"
    fun progress(id: String) = "progress/$id"
    fun review(batchId: String, recordId: String) = "review/$batchId/$recordId"
    fun issues(id: String) = "issues/$id"
    fun export(id: String) = "export/$id"
}

@Composable
fun KaidanApp(viewModel: MainViewModel) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.events.collectLatest { snackbar.showSnackbar(it) } }
    Surface {
        androidx.compose.material3.Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            NavHost(navController = nav, startDestination = Routes.HOME, modifier = Modifier) {
                composable(Routes.HOME) {
                    HomeScreen(viewModel, padding, onBatch = { nav.navigate(Routes.batch(it)) }, onSettings = { nav.navigate(Routes.SETTINGS) })
                }
                composable("batch/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                    val id = entry.arguments?.getString("id")!!
                    BatchScreen(viewModel, id, padding, nav::popBackStack,
                        onCamera = { nav.navigate(Routes.camera(id)) },
                        onProgress = { nav.navigate(Routes.progress(id)) },
                        onReview = { nav.navigate(Routes.review(id, it)) },
                        onIssues = { nav.navigate(Routes.issues(id)) },
                        onExport = { nav.navigate(Routes.export(id)) })
                }
                composable("camera/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                    CameraScreen(viewModel, entry.arguments?.getString("id")!!, onDone = nav::popBackStack)
                }
                composable("progress/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                    val id = entry.arguments?.getString("id")!!
                    ProgressScreen(viewModel, id, padding, nav::popBackStack, { nav.navigate(Routes.review(id, it)) }, { nav.navigate(Routes.issues(id)) })
                }
                composable("review/{batchId}/{recordId}", arguments = listOf(navArgument("batchId") { type = NavType.StringType }, navArgument("recordId") { type = NavType.StringType })) { entry ->
                    ReviewScreen(viewModel, entry.arguments?.getString("batchId")!!, entry.arguments?.getString("recordId")!!, padding, nav::popBackStack)
                }
                composable("issues/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                    val id = entry.arguments?.getString("id")!!
                    IssuesScreen(viewModel, id, padding, nav::popBackStack) { nav.navigate(Routes.review(id, it)) }
                }
                composable("export/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                    ExportScreen(viewModel, entry.arguments?.getString("id")!!, padding, nav::popBackStack)
                }
                composable(Routes.SETTINGS) { SettingsScreen(viewModel, padding, nav::popBackStack) }
            }
        }
    }
}
