package com.morton.trucknav.settings

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class S20SettingsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun persistenceFlowAndAtomicValidation() {
        Settings.init(context)
        val key = "s20Acceptance"
        val original = Settings.get(key)
        val provider = Settings.get("trafficProvider")
        try {
            val flow = Settings.flow(key)
            Settings.set(key, "Dépôt 東京")
            assertEquals("Dépôt 東京", flow.value)
            assertEquals("Dépôt 東京", JSONObject(File(context.filesDir, "settings.json").readText()).getString(key))
            try {
                Settings.update(mapOf(key to "must not persist", "trafficProvider" to "invalid"))
                fail("Invalid settings batch accepted")
            } catch (_: IllegalArgumentException) { }
            assertEquals("Dépôt 東京", Settings.get(key))
            assertEquals(provider, Settings.get("trafficProvider"))
            Settings.set(key, null)
            assertNull(flow.value)
            assertFalse(JSONObject(File(context.filesDir, "settings.json").readText()).has(key))
        } finally { Settings.set(key, original) }
    }

    @Test fun secretMaskingIncludesShortSecrets() {
        Settings.init(context)
        val original = Settings.get("s20AcceptanceKey")
        try {
            Settings.set("s20AcceptanceKey", "fixture-secret-1234")
            assertEquals("••••1234", Settings.snapshot()["s20AcceptanceKey"])
            Settings.set("s20AcceptanceKey", "1234")
            assertEquals("••••", Settings.snapshot()["s20AcceptanceKey"])
        } finally { Settings.set("s20AcceptanceKey", original) }
    }

    @Test fun imageFitAndRejectedUploadPreservesImage() {
        VehicleImage.init(context)
        val original = VehicleImage.bytes()
        try {
            val input = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888)
            input.eraseColor(android.graphics.Color.MAGENTA)
            val png = ByteArrayOutputStream().also { input.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            VehicleImage.put(png)
            val saved = VehicleImage.bytes()!!
            val image = BitmapFactory.decodeByteArray(saved, 0, saved.size)
            assertEquals(256, image.width)
            assertEquals(256, image.height)
            assertEquals(0, android.graphics.Color.alpha(image.getPixel(0, 0)))
            assertEquals(android.graphics.Color.MAGENTA, image.getPixel(128, 128))
            for (bad in listOf(byteArrayOf(1, 2, 3), ByteArray(VehicleImage.MAX_BYTES + 1))) {
                try { VehicleImage.put(bad); fail("Bad image accepted") } catch (_: IllegalArgumentException) { }
                assertArrayEquals(saved, VehicleImage.bytes())
            }
            VehicleImage.delete()
            assertNull(VehicleImage.bitmap.value)
            assertNull(VehicleImage.bytes())
        } finally { if (original != null) VehicleImage.put(original) else VehicleImage.delete() }
    }

    @Test fun initialPositionUsesLastFixThenExtractCentre() {
        val prefs = context.getSharedPreferences("last_fix", android.content.Context.MODE_PRIVATE)
        val oldLat = prefs.getString("lat", null); val oldLng = prefs.getString("lng", null)
        val fixture = File(context.getExternalFilesDir(null), "000-s20-fixture.pmtiles")
        check(!fixture.exists())
        try {
            val header = java.nio.ByteBuffer.allocate(127).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            header.put("PMTiles".toByteArray()); header.put(3.toByte())
            header.putInt(119, -1050000000); header.putInt(123, 400000000)
            fixture.writeBytes(header.array())
            prefs.edit().remove("lat").remove("lng").commit()
            InitialPosition.init(context)
            assertEquals(40.0, InitialPosition.coordinate.lat, 0.000001)
            assertEquals(-105.0, InitialPosition.coordinate.lng, 0.000001)
            prefs.edit().putString("lat", "41.25").putString("lng", "-104.75").commit()
            InitialPosition.init(context)
            assertEquals(41.25, InitialPosition.coordinate.lat, 0.000001)
            assertEquals(-104.75, InitialPosition.coordinate.lng, 0.000001)
        } finally {
            fixture.delete()
            prefs.edit().putString("lat", oldLat).putString("lng", oldLng).commit()
            InitialPosition.init(context)
        }
    }

    @Test fun settingsPaneAndQrControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context.startActivity(android.content.Intent(context, com.morton.trucknav.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        fun find(node: android.view.accessibility.AccessibilityNodeInfo?, label: String, description: Boolean): android.view.accessibility.AccessibilityNodeInfo? {
            if (node == null) return null
            if ((if (description) node.contentDescription?.toString() else node.text?.toString()) == label) return node
            for (i in 0 until node.childCount) find(node.getChild(i), label, description)?.let { return it }
            return null
        }
        fun lookup(label: String): android.view.accessibility.AccessibilityNodeInfo? {
            instrumentation.uiAutomation.clearCache()
            val root = instrumentation.uiAutomation.rootInActiveWindow
            return find(root, label, true) ?: find(root, label, false)
        }
        fun wait(label: String): android.view.accessibility.AccessibilityNodeInfo {
            repeat(60) { lookup(label)?.let { return it }; android.os.SystemClock.sleep(100) }
            error("Control missing: " + label)
        }
        fun tap(label: String) {
            var node = wait(label)
            node.performAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            android.os.SystemClock.sleep(200)
            node = wait(label)
            while (!node.isClickable && node.parent != null) node = node.parent
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            check(!bounds.isEmpty) { "Control is off screen: " + label }
            println("S20 tap " + label + " bounds=" + bounds.toShortString())
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}")).use { it.readBytes() }
            instrumentation.waitForIdleSync()
            android.os.SystemClock.sleep(250)
        }
        fun scrollTo(label: String) {
            fun content(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                if (node == null) return null
                val r = android.graphics.Rect(); node.getBoundsInScreen(r)
                if (node.isScrollable && r.left > context.resources.displayMetrics.widthPixels / 2 && r.height() > 200) return node
                for (i in 0 until node.childCount) content(node.getChild(i))?.let { return it }
                return null
            }
            repeat(10) {
                if (lookup(label) != null) return
                val node = content(instrumentation.uiAutomation.rootInActiveWindow) ?: error("Settings content is not scrollable")
                val r = android.graphics.Rect(); node.getBoundsInScreen(r)
                android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input swipe ${r.centerX()} ${r.bottom - 50} ${r.centerX()} ${r.top + 50} 400")).use { it.readBytes() }
                android.os.SystemClock.sleep(400)
            }
            error("Settings control missing after scrolling: " + label)
        }
        val startupDeadline = android.os.SystemClock.elapsedRealtime() + 30000
        while (lookup("Settings") == null && android.os.SystemClock.elapsedRealtime() < startupDeadline) {
            lookup("Continue to map")?.let { tap("Continue to map") }
            lookup("Got it")?.let { tap("Got it") }
            android.os.SystemClock.sleep(100)
        }
        tap("Settings")
        wait("Places")
        val unitsBefore = Settings.get("units")
        val nightBefore = Settings.get("autoNight")
        val tokenBefore = Settings.get("apiToken")
        try {
            fun section(label: String) {
                repeat(5) {
                    var candidate = lookup(label)
                    while (candidate != null && !candidate.isClickable && candidate.parent != null) candidate = candidate.parent
                    val bounds = android.graphics.Rect()
                    candidate?.getBoundsInScreen(bounds)
                    println("S20 section " + label + " bounds=" + bounds.toShortString() + " displayWidth=" + context.resources.displayMetrics.widthPixels)
                    if (candidate != null && bounds.width() > 0 && bounds.right < context.resources.displayMetrics.widthPixels - 10) { tap(label); return }
                    val row = android.graphics.Rect()
                    wait("Settings sections").getBoundsInScreen(row)
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input swipe ${row.right - 35} ${row.centerY()} ${row.left + 35} ${row.centerY()} 400")).use { it.readBytes() }
                    android.os.SystemClock.sleep(500)
                }
                if (label == "Units") java.io.FileOutputStream(File(context.getExternalFilesDir(null), "s20-units-missing.png")).use { out ->
                    instrumentation.uiAutomation.takeScreenshot()!!.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                error("Section missing: " + label)
            }
            section("Units")
            java.io.FileOutputStream(File(context.getExternalFilesDir(null), "s20-units-test.png")).use { out ->
                instrumentation.uiAutomation.takeScreenshot()!!.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            tap("metric")
            assertEquals("metric", Settings.get("units"))
            section("Voice")
            val before = Settings.get("autoNight") != "false"
            scrollTo("Auto night")
            tap("Auto night")
            assertEquals(!before, Settings.get("autoNight") != "false")
            section("API")
            tap("Show token and QR")
            scrollTo("API token QR")
            wait("API token QR").performAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            android.os.SystemClock.sleep(300)
            val rect = android.graphics.Rect()
            wait("API token QR").getBoundsInScreen(rect)
            val screen = instrumentation.uiAutomation.takeScreenshot()!!
            val qr = Bitmap.createBitmap(screen, rect.left, rect.top, rect.width(), rect.height())
            val pixels = IntArray(qr.width * qr.height)
            qr.getPixels(pixels, 0, qr.width, 0, 0, qr.width, qr.height)
            val luminance = com.google.zxing.RGBLuminanceSource(qr.width, qr.height, pixels)
            val decoded = com.google.zxing.MultiFormatReader().decode(com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(luminance))).text
            assertTrue("QR encodes current token", decoded == Settings.get("apiToken"))
            section("About")
            wait("Export navigation logs")
        } finally {
            Settings.set("units", unitsBefore); Settings.set("autoNight", nightBefore)
            Settings.set("apiToken", tokenBefore)
            lookup("Map")?.let { tap("Map") }
        }
    }

    @Test fun tokenEntropyAndUnitFormatting() {
        val tokens = List(64) { Configuration.newToken() }
        assertEquals(64, tokens.toSet().size)
        assertTrue(tokens.all { it.matches(Regex("[0-9a-f]{64}")) })
        Settings.init(context)
        val before = Settings.get("units")
        try {
            Settings.set("units", "imperial"); assertTrue(Units.distance(1609.344).endsWith(" mi"))
            Settings.set("units", "metric"); assertTrue(Units.distance(1000.0).endsWith(" km"))
        } finally { Settings.set("units", before) }
    }
}
