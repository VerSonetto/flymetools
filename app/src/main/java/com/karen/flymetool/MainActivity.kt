package com.karen.flymetool

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.karen.flymetool.ui.screen.AppNavHost
import com.karen.flymetool.ui.theme.FlymeToolTheme
import com.karen.flymetool.util.FlymeVersionUtils

class MainActivity : ComponentActivity() {

    companion object {
        private var hasShownToast = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (!hasShownToast) {
            hasShownToast = true
            val flymeVersion = FlymeVersionUtils.getFullVersion()
            Toast.makeText(this, flymeVersion, Toast.LENGTH_LONG).show()
        }

        setContent {
            FlymeToolTheme {
                AppNavHost()
            }
        }
    }
}
