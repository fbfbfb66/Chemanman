package com.goings.kaidanzhushou

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.goings.kaidanzhushou.ui.KaidanApp
import com.goings.kaidanzhushou.ui.MainViewModel
import com.goings.kaidanzhushou.ui.theme.KaidanTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        MainViewModel.Factory((application as KaidanApplication).container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { KaidanTheme { KaidanApp(viewModel) } }
    }
}
