package com.anas.applocker

import android.animation.ObjectAnimator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Realistic fake "system storage" screen, opened by tapping the [FakeStorageNotifier]
 * notification. Purely cosmetic/decoy - the "Clean Junk Files Now" button always ends in
 * a fake failure dialog after ~3.5s, matching a real OEM storage-manager's look and feel
 * closely enough to discourage a curious observer from digging further.
 */
class FakeStorageDetailActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var cleanupInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fake_storage_detail)

        val usedLabel = findViewById<TextView>(R.id.storageUsedLabel)
        val progressBar = findViewById<ProgressBar>(R.id.storageProgressBar)
        val percentLabel = findViewById<TextView>(R.id.storagePercentLabel)
        val cleanButton = findViewById<Button>(R.id.cleanJunkButton)
        val cleanupProgress = findViewById<ProgressBar>(R.id.cleanupProgressBar)
        val backButton = findViewById<TextView>(R.id.fakeStorageBackButton)

        usedLabel.text = "127.4 GB / 128.0 GB Used"
        progressBar.max = 1000
        progressBar.progress = 990 // 99%
        percentLabel.text = "99% full"

        backButton.setOnClickListener { finish() }

        cleanButton.setOnClickListener {
            if (cleanupInProgress) return@setOnClickListener
            cleanupInProgress = true
            cleanButton.isEnabled = false
            cleanButton.text = "Scanning system partition..."
            cleanupProgress.visibility = android.view.View.VISIBLE
            cleanupProgress.progress = 0

            val animator = ObjectAnimator.ofInt(cleanupProgress, "progress", 0, 100)
            animator.duration = 3500
            animator.start()

            handler.postDelayed({
                cleanupInProgress = false
                cleanButton.isEnabled = true
                cleanButton.text = "Clean Junk Files Now"
                cleanupProgress.visibility = android.view.View.GONE
                showCleanupFailedDialog()
            }, 3500)
        }
    }

    private fun showCleanupFailedDialog() {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("Cleanup Failed")
            .setMessage("System partition is full. Please delete personal media files manually.")
            .setPositiveButton("OK", null)
            .setCancelable(true)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
