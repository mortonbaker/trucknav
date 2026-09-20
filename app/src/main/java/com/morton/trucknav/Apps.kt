package com.morton.trucknav

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PorterDuff
import android.widget.ImageView

// The cockpit's vocabulary of external apps: what the Apps pane lists and what
// the overlay dock offers when a foreign app is full-screen. Icons are Material
// vector drawables in res/drawable so the same art serves Views and Compose.
data class ExtApp(val label: String, val icon: Int, val pkg: String, val color: Int)

object Apps {
    const val SELF = "com.morton.trucknav"

    val external = listOf(
        ExtApp("Music", R.drawable.ic_music_note, "com.unicornsonlsd.finamp", 0xFFff6b6b.toInt()),
        ExtApp("Audiobooks", R.drawable.ic_headphones, "com.audiobookshelf.app", 0xFFc774ff.toInt()),
        ExtApp("YouTube", R.drawable.ic_smart_display, "org.schabi.newpipe", 0xFFff3b30.toInt()),
        ExtApp("Settings", R.drawable.ic_settings, "com.android.settings", 0xFF9aa4b2.toInt()),
    )

    fun installed(ctx: Context, pkg: String): Boolean =
        try { ctx.packageManager.getPackageInfo(pkg, 0); true } catch (e: PackageManager.NameNotFoundException) { false }

    fun launch(ctx: Context, pkg: String) {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        ctx.startActivity(i)
    }

    fun openSelf(ctx: Context) {
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun iconView(ctx: Context, res: Int, color: Int, sizePx: Int): ImageView =
        ImageView(ctx).apply {
            setImageResource(res)
            setColorFilter(color, PorterDuff.Mode.SRC_IN)
            layoutParams = android.view.ViewGroup.LayoutParams(sizePx, sizePx)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
}
