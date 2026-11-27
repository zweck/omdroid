package com.phoshdroid.app.launch

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory

class PreferencesActivityManifestTest {
    @Test fun `keyboard preferences target is enabled internally without a launcher entry`() {
        val config = Properties()
        checkNotNull(javaClass.getResourceAsStream("/com/android/tools/test_config.properties"))
            .use { config.load(it) }
        val manifest = File(checkNotNull(config.getProperty("android_merged_manifest")))
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val activities = factory.newDocumentBuilder().parse(manifest).getElementsByTagName("activity")
        val android = "http://schemas.android.com/apk/res/android"
        val target = (0 until activities.length).map { activities.item(it) as Element }
            .single { it.getAttributeNS(android, "name") == "com.termux.x11.LoriePreferences" }
        assertNotEquals("false", target.getAttributeNS(android, "enabled"),
            "The extra-keyboard gear launches this activity explicitly; disabling it crashes the app")
        assertEquals("false", target.getAttributeNS(android, "exported"))
        assertEquals(0, target.getElementsByTagName("intent-filter").length)
    }
}
