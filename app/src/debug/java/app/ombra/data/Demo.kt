package app.ombra.data

import android.content.Intent

/**
 * Debug builds only: `--es demo <scenario>[,<extra>...]` runs the UI on scripted state instead of
 * the core, with no network and no :vpn process. Scenarios are in [DemoScenario], extras in
 * [DemoOptions], for example:
 * `adb shell am start -S -n app.ombra.debug/app.ombra.ui.MainActivity --es demo connected,lockdown`.
 */
fun demoCoreFor(intent: Intent?): CoreSource? =
    intent?.getStringExtra(EXTRA_DEMO)?.split(',')?.let { parts ->
        DemoCoreClient(DemoScenario.parse(parts.first().trim()), DemoOptions.parse(parts.drop(1).map(String::trim)))
    }

private const val EXTRA_DEMO = "demo"
