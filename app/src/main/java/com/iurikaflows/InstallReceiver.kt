package com.iurikaflows

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast

// Result of a PackageInstaller session commit
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val repo = intent.getStringExtra("repo") ?: return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Background activity starts are blocked, so outside the app ask through a notification
                if (Store.onChange != null) ctx.startActivity(confirm)
                else Store.notify(ctx, repo.hashCode(), "Mettre à jour $repo", "Touche pour installer", confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)?.let { Store.remember(ctx, repo, it) }
                Store.finished(ctx, repo)
            }
            else -> {
                if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                    val msg = "$repo: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "échec de l'installation"}"
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                }
                Store.finished(ctx, repo)
            }
        }
    }
}
