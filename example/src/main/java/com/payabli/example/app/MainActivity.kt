package com.payabli.example.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.payabli.example.app.demo.config.TokenHostResolver
import com.payabli.example.app.demo.ui.nav.PayabliDemoNavHost
import com.payabli.example.app.demo.ui.theme.PayabliDemoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Before setContent, so the first composition already sees the resolved address. The override
        // arrives on the Intent, which is only readable here:
        //   adb shell am start -n com.payabli.example.app/.MainActivity \
        //     -e payabliTokenHost 192.168.1.10:8787
        (application as PayabliDemoApplication).container.applyLaunchOverride(
            intent?.getStringExtra(TokenHostResolver.LAUNCH_EXTRA),
        )

        enableEdgeToEdge()
        setContent {
            PayabliDemoTheme {
                PayabliDemoNavHost()
            }
        }
    }
}
