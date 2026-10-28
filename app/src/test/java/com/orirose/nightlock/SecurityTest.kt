package com.orirose.nightlock

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Guards what NightLock's accessibility service may do. The permissions and exported components of the
 * final APK are checked by the securityCheckReleaseManifest task in app/build.gradle.kts.
 *
 * Android's accessibility config can't limit a service to "package names only": canRetrieveWindowContent
 * also allows reading text and pressing buttons in other apps. So besides the config, this test checks the
 * code never makes those calls. Widening either is a deliberate change to this test and to README.md's
 * Security section.
 */
class SecurityTest {
    private val android = "http://schemas.android.com/apk/res/android"

    private val accessibility: Element = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(File("src/main/res/xml/accessibility_config.xml"))
        .documentElement

    private val sources: Map<String, String> = File("src/main").walk()
        .filter { it.extension == "kt" || it.extension == "java" }
        .associate { it.name to it.readText() }

    /** Files that touch the accessibility APIs, where even plain words like `text` are suspicious. */
    private val accessibilitySources = sources.filter { (_, code) ->
        "android.view.accessibility" in code || "android.accessibilityservice" in code
    }

    @Test
    fun theScanSeesTheBlocker() {
        // An empty scan would pass everything; make sure the code is where this test looks.
        assertTrue("BlockerService.kt not found under src/main", "BlockerService.kt" in accessibilitySources)
    }

    @Test
    fun accessibilityHearsOnlyWindowChanges() {
        val events = accessibility.getAttributeNS(android, "accessibilityEventTypes").split("|").toSet()
        assertTrue("unexpected events: $events", setOf("typeWindowStateChanged", "typeWindowsChanged").containsAll(events))
        val flags = accessibility.getAttributeNS(android, "accessibilityFlags").split("|").toSet()
        assertEquals(setOf("flagRetrieveInteractiveWindows"), flags)
    }

    @Test
    fun accessibilityConfigAsksForNoActions() {
        val forbidden = listOf(
            "canPerformGestures", "canTakeScreenshot", "canRequestFilterKeyEvents",
            "canRequestTouchExplorationMode", "canControlMagnification", "canRequestFingerprintGestures",
            "canRequestEnhancedWebAccessibility",
        )
        forbidden.forEach { assertTrue("$it must not be set", !accessibility.hasAttributeNS(android, it)) }
    }

    /**
     * A best-effort guard: it catches the usual ways of reading or acting on other apps' content, including
     * the Kotlin property spellings (`serviceInfo = …`, `window.root`). It can't prove the absence of every
     * possible trick; that's what code review is for.
     */
    @Test
    fun codeNeverReadsOrTouchesOtherAppsContent() {
        val forbidden = listOf(
            // Changing the service's own config at runtime, past what the XML grants.
            "serviceInfo", "AccessibilityServiceInfo",
            // Getting at UI elements of other apps.
            "AccessibilityNodeInfo", "rootInActiveWindow", "findAccessibilityNodeInfos", "findFocus",
            "getChild", "getParent", "anchor", "source", "viewIdResourceName",
            // Reading content.
            "getText", "contentDescription", "contentChangeTypes", "className",
            // Acting on other apps.
            "performAction", "dispatchGesture", "takeScreenshot", "onKeyEvent", "onGesture",
            "softKeyboardController", "fingerprintGestureController", "magnificationController",
        )
        accessibilitySources.forEach { (file, code) ->
            forbidden.forEach { assertTrue("$file uses $it", !code.contains(it, ignoreCase = true)) }
            assertTrue("$file reads event or node text", !Regex("""\btext\b""").containsMatchIn(code))
        }
        sources.forEach { (file, code) ->
            listOf("AccessibilityNodeInfo", "performAction", "serviceInfo").forEach {
                assertTrue("$file uses $it", !code.contains(it, ignoreCase = true))
            }
        }
    }

    @Test
    fun windowsAreOnlyAskedForTheirPackage() {
        sources.forEach { (file, code) ->
            val other = Regex("""(\.root\b|getRoot\b)(?!\?\.packageName)""").find(code)
            assertTrue("$file reads window content beyond the package name: ${other?.value}", other == null)
        }
    }

    @Test
    fun theOnlySystemActionIsHome() {
        sources.forEach { (file, code) ->
            val other = Regex("""performGlobalAction\((?!GLOBAL_ACTION_HOME\))""").find(code)
            assertTrue("$file performs a global action other than Home", other == null)
        }
    }
}
