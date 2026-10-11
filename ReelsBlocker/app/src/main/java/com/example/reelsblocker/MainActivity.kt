package com.example.reelsblocker

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * MainActivity
 *
 * Minimalist single-screen UI that:
 *  • Shows the overall status (Fully active / Partially active / Inactive)
 *  • Provides one-tap buttons to grant each required permission
 *  • Starts the foreground service if it isn't running
 */
class MainActivity : AppCompatActivity() {

    // ─── Views ────────────────────────────────────────────────────────────────
    private lateinit var statusIcon:    ImageView
    private lateinit var statusText:    TextView
    private lateinit var statusSubtext: TextView

    // Each row (included layout): the row View + its button
    private data class PermRow(val root: View, val button: Button, val title: TextView, val desc: TextView)

    private lateinit var rowAccessibility: PermRow
    private lateinit var rowDeviceAdmin:   PermRow
    private lateinit var rowBattery:       PermRow
    private lateinit var rowService:       PermRow

    // ─── Admin component ──────────────────────────────────────────────────────
    private lateinit var deviceAdminComponent: ComponentName

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        deviceAdminComponent = ComponentName(this, AppDeviceAdminReceiver::class.java)

        bindViews()
        setupRowContent()
        setupClickListeners()
        startBlockerService()
    }

    override fun onResume() {
        super.onResume()
        refreshUI()
    }

    // ─── View binding ─────────────────────────────────────────────────────────

    private fun bindViews() {
        statusIcon    = findViewById(R.id.iv_status_icon)
        statusText    = findViewById(R.id.tv_status_text)
        statusSubtext = findViewById(R.id.tv_status_subtext)

        fun rowFrom(id: Int): PermRow {
            val root   = findViewById<View>(id)
            val button = root.findViewById<Button>(R.id.btn_row_action)
            val title  = root.findViewById<TextView>(R.id.tv_row_title)
            val desc   = root.findViewById<TextView>(R.id.tv_row_desc)
            return PermRow(root, button, title, desc)
        }

        rowAccessibility = rowFrom(R.id.row_accessibility)
        rowDeviceAdmin   = rowFrom(R.id.row_device_admin)
        rowBattery       = rowFrom(R.id.row_battery)
        rowService       = rowFrom(R.id.row_notification)
    }

    /** Populate static text labels for each row. */
    private fun setupRowContent() {
        rowAccessibility.title.setText(R.string.perm_accessibility_title)
        rowAccessibility.desc.setText(R.string.perm_accessibility_desc)

        rowDeviceAdmin.title.setText(R.string.perm_admin_title)
        rowDeviceAdmin.desc.setText(R.string.perm_admin_desc)

        rowBattery.title.setText(R.string.perm_battery_title)
        rowBattery.desc.setText(R.string.perm_battery_desc)

        rowService.title.setText(R.string.perm_service_title)
        rowService.desc.setText(R.string.perm_service_desc)
    }

    // ─── Click listeners ──────────────────────────────────────────────────────

    private fun setupClickListeners() {

        rowAccessibility.button.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.dialog_accessibility_title)
                .setMessage(R.string.dialog_accessibility_message)
                .setPositiveButton(R.string.btn_open_settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        rowDeviceAdmin.button.setOnClickListener {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, deviceAdminComponent)
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    getString(R.string.device_admin_explanation)
                )
            }
            startActivity(intent)
        }

        rowBattery.button.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        rowService.button.setOnClickListener {
            startBlockerService()
            // After 500 ms the UI refreshes automatically via onResume after
            // returning from the settings page; for an in-app restart we
            // refresh immediately.
            refreshUI()
        }
    }

    // ─── UI refresh ───────────────────────────────────────────────────────────

    private fun refreshUI() {
        val accessibilityOk = isAccessibilityServiceEnabled()
        val deviceAdminOk   = isDeviceAdminActive()
        val batteryOk       = isBatteryOptimizationIgnored()
        val serviceRunning  = isForegroundServiceRunning()

        applyRowState(rowAccessibility, accessibilityOk,
            grantedText  = getString(R.string.status_granted),
            pendingText  = getString(R.string.btn_grant))

        applyRowState(rowDeviceAdmin, deviceAdminOk,
            grantedText  = getString(R.string.status_active),
            pendingText  = getString(R.string.btn_activate))

        applyRowState(rowBattery, batteryOk,
            grantedText  = getString(R.string.status_excluded),
            pendingText  = getString(R.string.btn_exclude))

        applyRowState(rowService, serviceRunning,
            grantedText  = getString(R.string.status_running),
            pendingText  = getString(R.string.btn_start))

        // ── Overall status indicator ──────────────────────────────────────
        val allGood        = accessibilityOk && deviceAdminOk && batteryOk && serviceRunning
        val partiallyGood  = accessibilityOk

        when {
            allGood -> {
                statusIcon.setImageResource(R.drawable.ic_status_active)
                statusIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_green))
                statusText.setText(R.string.status_fully_active)
                statusSubtext.setText(R.string.status_fully_active_sub)
            }
            partiallyGood -> {
                statusIcon.setImageResource(R.drawable.ic_status_partial)
                statusIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_yellow))
                statusText.setText(R.string.status_partially_active)
                statusSubtext.setText(R.string.status_partially_active_sub)
            }
            else -> {
                statusIcon.setImageResource(R.drawable.ic_status_inactive)
                statusIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_red))
                statusText.setText(R.string.status_inactive)
                statusSubtext.setText(R.string.status_inactive_sub)
            }
        }
    }

    private fun applyRowState(
        row: PermRow,
        isActive: Boolean,
        grantedText: String,
        pendingText: String
    ) {
        row.button.text    = if (isActive) grantedText else pendingText
        row.button.isEnabled = !isActive
        row.button.alpha   = if (isActive) 0.45f else 1.0f
    }

    // ─── Permission / state checks ────────────────────────────────────────────

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedId = "$packageName/${ReelsBlockerAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(':').any { it.equals(expectedId, ignoreCase = true) }
    }

    private fun isDeviceAdminActive(): Boolean {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(deviceAdminComponent)
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    @Suppress("DEPRECATION")
    private fun isForegroundServiceRunning(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == BlockerForegroundService::class.java.name
        }
    }

    // ─── Service starter ──────────────────────────────────────────────────────

    private fun startBlockerService() {
        val intent = Intent(this, BlockerForegroundService::class.java)
        startForegroundService(intent)
    }
}
