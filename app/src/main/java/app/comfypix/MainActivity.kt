package app.comfypix

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.comfypix.ui.ComfyTheme
import app.comfypix.ui.CreateScreen
import app.comfypix.ui.GenerateBar
import app.comfypix.ui.LayoutEditorScreen
import app.comfypix.ui.NavBar
import app.comfypix.ui.ResultsScreen
import app.comfypix.ui.SettingsScreen
import app.comfypix.ui.WorkflowsScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ComfyTheme { AppRoot(vm) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: AppViewModel) {
    val snack = remember { SnackbarHostState() }
    val msg = vm.message
    LaunchedEffect(msg) {
        if (msg != null) {
            snack.showSnackbar(msg)
            vm.message = null
        }
    }
    val editing = vm.layoutEditorFor != null
    BackHandler(enabled = editing) { vm.layoutEditorFor = null }
    BackHandler(enabled = !editing && vm.tab != 0) { vm.tab = 0 }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            if (!editing) {
                Column {
                    if (vm.tab <= 1 && vm.current != null) GenerateBar(vm)
                    NavBar(vm)
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (editing) {
                LayoutEditorScreen(vm)
            } else {
                when (vm.tab) {
                    0 -> CreateScreen(vm)
                    1 -> ResultsScreen(vm)
                    2 -> WorkflowsScreen(vm)
                    else -> SettingsScreen(vm)
                }
            }
        }
    }
}
