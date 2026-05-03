package com.karen.flymetool

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.karen.flymetool.ui.screen.AppNavHost
import com.karen.flymetool.ui.theme.FlymeToolTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FlymeToolTheme {
                AppNavHost()
            }
        }
    }
}
