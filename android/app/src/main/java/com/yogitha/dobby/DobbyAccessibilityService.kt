package com.yogitha.dobby

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class DobbyAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var scrollAttempts = 0
    
    // Teaching mode observation
    private var teachingSteps = mutableListOf<TeachingStep>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        tryPending()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Handle teaching mode observation
        if (WorkflowManager.isTeaching()) {
            observeEventForTeaching(event)
        }
        
        // Handle normal pending actions
        if (pendingAction == null) return
        val pkg = event?.packageName?.toString().orEmpty()
        if (!pkg.contains("amazon", ignoreCase = true)) return
        tryPending()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }
    
    private fun observeEventForTeaching(event: AccessibilityEvent?) {
        if (event == null) return
        
        val pkg = event.packageName?.toString() ?: ""
        val eventType = eventTypeToString(event.eventType)
        val text = event.text?.joinToString(" ") ?: ""
        val contentDescription = event.contentDescription?.toString() ?: ""
        
        // Only log meaningful events
        if (text.isNotBlank() || contentDescription.isNotBlank() || 
            eventType in listOf("TYPE_VIEW_CLICKED", "TYPE_WINDOW_STATE_CHANGED")) {
            
            val step = TeachingStep(
                timestamp = System.currentTimeMillis(),
                packageName = pkg,
                eventType = eventType,
                text = text,
                contentDescription = contentDescription,
                className = event.className?.toString()
            )
            
            teachingSteps.add(step)
            android.util.Log.e("DobbyTeaching", "Observed: $eventType in $pkg - text: $text")
        }
    }
    
    private fun eventTypeToString(eventType: Int): String {
        return when (eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "TYPE_VIEW_CLICKED"
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "TYPE_WINDOW_STATE_CHANGED"
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> "TYPE_VIEW_FOCUSED"
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "TYPE_VIEW_TEXT_CHANGED"
            else -> "TYPE_$eventType"
        }
    }

    private fun tryPending() {
        val action = pendingAction ?: return
        val root = amazonRoot()
        android.util.Log.e(
            TAG,
            "tryPending action=$action rootPkg=${root?.packageName} rootClass=${root?.className}"
        )
        if (root == null) return
        try {
            if (looksLikePayment(root)) {
                finish("Amazon is on a payment or checkout screen. Take over. Dobby will not pay.")
                return
            }
            if (action == ACTION_ADD_CART) {
                diagnosticLogCartNodes(root)
            }
            val clicked = when (action) {
                ACTION_ADD_CART -> clickAddToCartControl(root)
                ACTION_SETTINGS -> clickFirst(root, SETTINGS_LABELS)
                else -> false
            }
            if (clicked) {
                val message = if (action == ACTION_ADD_CART) {
                    "Added the open product to your Amazon cart."
                } else {
                    "Opened Amazon settings."
                }
                finish(message)
            } else if (action == ACTION_ADD_CART && scrollAttempts < 8) {
                diagnosticLogCartNodes(root)
                val scrolled = scrollForward(root)
                android.util.Log.e(TAG, "Add to Cart not clicked; scroll=$scrolled attempt=$scrollAttempts")
                if (scrolled) {
                    scrollAttempts++
                    mainHandler.postDelayed({ tryPending() }, 400)
                }
            } else if (action == ACTION_ADD_CART) {
                diagnosticLogCartNodes(root)
            }
        } finally {
            recycleSafely(root)
        }
    }

    private fun amazonRoot(): AccessibilityNodeInfo? {
        val active = rootInActiveWindow
        if (isAmazonPackage(active?.packageName?.toString())) {
            return active
        }
        recycleSafely(active)
        val windowList = windows ?: return null
        for (window in windowList) {
            val candidate = window.root ?: continue
            if (isAmazonPackage(candidate.packageName?.toString()) &&
                window.type == AccessibilityWindowInfo.TYPE_APPLICATION
            ) {
                return candidate
            }
            recycleSafely(candidate)
        }
        return null
    }

    private fun clickAddToCartControl(root: AccessibilityNodeInfo): Boolean {
        val candidates = mutableListOf<CartCandidate>()
        collectCartCandidates(root, candidates, 0)
        candidates.sortWith(
            compareByDescending<CartCandidate> { it.score }
                .thenByDescending { it.visible }
                .thenByDescending { it.clickable || it.hasClickAction }
        )
        for (candidate in candidates) {
            logCandidate(candidate, "trying")
            val clicked = performSemanticClick(candidate.node)
            if (clicked) {
                android.util.Log.e(TAG, "ACTION_CLICK succeeded on ${describe(candidate.node)}")
                return true
            }
            android.util.Log.e(TAG, "ACTION_CLICK failed on ${describe(candidate.node)}")
        }
        return false
    }

    private fun collectCartCandidates(
        node: AccessibilityNodeInfo,
        out: MutableList<CartCandidate>,
        depth: Int
    ) {
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val viewId = node.viewIdResourceName.orEmpty()
        val haystack = "$text $desc $viewId".lowercase()
        if (isAddToCartLabel(haystack) && !isNavigationalCart(haystack)) {
            out.add(
                CartCandidate(
                    node = node,
                    score = scoreCandidate(node, haystack),
                    visible = node.isVisibleToUser,
                    clickable = node.isClickable,
                    hasClickAction = hasAction(node, AccessibilityNodeInfo.ACTION_CLICK)
                )
            )
            logCandidate(out.last(), "found")
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectCartCandidates(child, out, depth + 1)
        }
    }

    private fun scoreCandidate(node: AccessibilityNodeInfo, haystack: String): Int {
        var score = 0
        if (node.isVisibleToUser) score += 40
        if (node.isEnabled) score += 20
        if (node.isClickable || hasAction(node, AccessibilityNodeInfo.ACTION_CLICK)) score += 30
        if (haystack.contains("add to cart") || haystack.contains("add to basket")) score += 50
        if (haystack.contains("add-to-cart") || haystack.contains("addtocart")) score += 25
        if (haystack.contains("buy now") || haystack.contains("buy-now")) score -= 80
        if (!node.isEnabled) score -= 40
        return score
    }

    private fun performSemanticClick(node: AccessibilityNodeInfo): Boolean {
        if (!node.isEnabled) return false
        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        val chain = clickChain(node)
        for (target in chain) {
            logNode("click-target", target)
            if (!target.isEnabled) continue
            if (hasAction(target, AccessibilityNodeInfo.ACTION_FOCUS)) {
                target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            }
            if (hasAction(target, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)) {
                target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            }
            if (hasAction(target, AccessibilityNodeInfo.ACTION_CLICK) || target.isClickable) {
                val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                android.util.Log.e(TAG, "perform ACTION_CLICK on ${describe(target)} -> $clicked")
                if (clicked) return true
            }
            val args = Bundle()
            val selected = target.performAction(AccessibilityNodeInfo.ACTION_SELECT, args)
            if (selected && (target.isClickable || hasAction(target, AccessibilityNodeInfo.ACTION_CLICK))) {
                val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (clicked) return true
            }
        }
        return false
    }

    private fun clickChain(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val chain = mutableListOf<AccessibilityNodeInfo>()
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < 8) {
            chain.add(current)
            current = current.parent
            hops++
        }
        return chain
    }

    private fun scrollForward(root: AccessibilityNodeInfo): Boolean {
        val scrollables = mutableListOf<AccessibilityNodeInfo>()
        collectScrollable(root, scrollables)
        for (node in scrollables.asReversed()) {
            if (hasAction(node, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) || node.isScrollable) {
                val ok = node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                android.util.Log.e(TAG, "ACTION_SCROLL_FORWARD on ${describe(node)} -> $ok")
                if (ok) return true
            }
        }
        return false
    }

    private fun collectScrollable(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        if (node.isScrollable || hasAction(node, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            out.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectScrollable(child, out)
        }
    }

    private fun finish(message: String) {
        pendingAction = null
        scrollAttempts = 0
        lastResult = message
    }

    private fun looksLikePayment(node: AccessibilityNodeInfo): Boolean {
        val haystack = collectText(node).lowercase()
        val hasAddToCart = CART_MATCH.containsMatchIn(haystack)
        if (hasAddToCart) return false
        return PAYMENT_MARKERS.any { haystack.contains(it) }
    }

    private fun collectText(node: AccessibilityNodeInfo): String {
        val parts = mutableListOf<String>()
        fun walk(current: AccessibilityNodeInfo?) {
            if (current == null) return
            current.text?.toString()?.let { if (it.isNotBlank()) parts.add(it) }
            current.contentDescription?.toString()?.let { if (it.isNotBlank()) parts.add(it) }
            for (i in 0 until current.childCount) {
                walk(current.getChild(i))
            }
        }
        walk(node)
        return parts.joinToString(" ")
    }

    @Suppress("SameParameterValue")
    private fun clickFirst(node: AccessibilityNodeInfo, labels: List<String>): Boolean {
        for (label in labels) {
            val matches = node.findAccessibilityNodeInfosByText(label) ?: continue
            for (match in matches) {
                val clicked = performSemanticClick(match)
                if (clicked) return true
            }
        }
        return clickByWalk(node, labels)
    }

    private fun clickByWalk(node: AccessibilityNodeInfo, labels: List<String>): Boolean {
        val text = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            .joinToString(" ")
            .lowercase()
        if (labels.any { text.contains(it.lowercase()) }) {
            if (performSemanticClick(node)) return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (clickByWalk(child, labels)) return true
        }
        return false
    }

    private fun diagnosticLogCartNodes(node: AccessibilityNodeInfo?) {
        if (node == null) return
        android.util.Log.e(TAG, "=== START CART DIAGNOSTICS pkg=${node.packageName} class=${node.className} ===")
        fun walk(current: AccessibilityNodeInfo?) {
            if (current == null) return
            val haystack = listOfNotNull(
                current.text?.toString(),
                current.contentDescription?.toString(),
                current.viewIdResourceName
            ).joinToString(" ").lowercase()
            if (
                haystack.contains("add") ||
                haystack.contains("cart") ||
                haystack.contains("basket") ||
                haystack.contains("buy")
            ) {
                logNode("relevant", current)
            }
            for (i in 0 until current.childCount) {
                walk(current.getChild(i))
            }
        }
        walk(node)
        android.util.Log.e(TAG, "=== END CART DIAGNOSTICS ===")
    }

    private fun logCandidate(candidate: CartCandidate, stage: String) {
        android.util.Log.e(TAG, "candidate[$stage] score=${candidate.score} ${describe(candidate.node)}")
    }

    private fun logNode(stage: String, node: AccessibilityNodeInfo) {
        android.util.Log.e(TAG, "$stage ${describe(node)}")
    }

    private fun describe(node: AccessibilityNodeInfo): String {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val parent = node.parent
        val parentInfo = if (parent == null) {
            "none"
        } else {
            "class=${parent.className} clickable=${parent.isClickable} enabled=${parent.isEnabled} actions=${actionNames(parent)}"
        }
        return """
            pkg=${node.packageName}
            class=${node.className}
            text='${node.text}'
            desc='${node.contentDescription}'
            viewId='${node.viewIdResourceName}'
            clickable=${node.isClickable}
            enabled=${node.isEnabled}
            visible=${node.isVisibleToUser}
            actions=${actionNames(node)}
            bounds=$bounds
            parent=[$parentInfo]
        """.trimIndent().replace("\n", " | ")
    }

    private fun actionNames(node: AccessibilityNodeInfo): String {
        return node.actionList.joinToString(",") { it.label?.toString() ?: it.id.toString() }
    }

    private fun hasAction(node: AccessibilityNodeInfo, action: Int): Boolean {
        return node.actionList.any { it.id == action }
    }

    private fun recycleSafely(node: AccessibilityNodeInfo?) {
        try {
            @Suppress("DEPRECATION")
            node?.recycle()
        } catch (_: Exception) {
        }
    }

    private data class CartCandidate(
        val node: AccessibilityNodeInfo,
        val score: Int,
        val visible: Boolean,
        val clickable: Boolean,
        val hasClickAction: Boolean
    )

    companion object {
        const val ACTION_ADD_CART = "add_cart"
        const val ACTION_SETTINGS = "amazon_settings"
        private const val TAG = "DobbyDiagnostics"

        private val CART_MATCH = Regex(
            """add(?:\s+this)?(?:\s+item)?\s+to\s+(?:my\s+|the\s+)?(?:cart|basket)|add-to-cart|addtocart|add_to_cart""",
            RegexOption.IGNORE_CASE
        )

        private val SETTINGS_LABELS = listOf(
            "Your Account",
            "Account",
            "Settings",
            "Customer Service"
        )

        private val PAYMENT_MARKERS = listOf(
            "place your order",
            "place order",
            "select a payment method",
            "enter card",
            "cvv",
            "upi id",
            "add credit card",
            "add debit card"
        )

        @Volatile
        var instance: DobbyAccessibilityService? = null

        @Volatile
        var pendingAction: String? = null

        @Volatile
        var lastResult: String? = null

        fun request(action: String, timeoutMs: Long = 12000L): String {
            lastResult = null
            pendingAction = action
            instance?.scrollAttempts = 0
            instance?.tryPendingPublic()
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < timeoutMs) {
                lastResult?.let { return it }
                Thread.sleep(150)
            }
            pendingAction = null
            return if (instance == null) {
                "Turn on Dobby's accessibility service, then try again. Dobby did not add anything."
            } else {
                "Could not activate Add to Cart on the open Amazon product. Nothing was added."
            }
        }

        fun isConnected(): Boolean = instance != null
        
        fun getTeachingSteps(): List<TeachingStep> {
            return instance?.teachingSteps?.toList() ?: emptyList()
        }
        
        fun clearTeachingSteps() {
            instance?.teachingSteps?.clear()
        }

        private fun isAmazonPackage(pkg: String?): Boolean {
            return pkg?.contains("amazon", ignoreCase = true) == true
        }

        private fun isAddToCartLabel(haystack: String): Boolean {
            return CART_MATCH.containsMatchIn(haystack)
        }

        private fun isNavigationalCart(haystack: String): Boolean {
            if (CART_MATCH.containsMatchIn(haystack)) return false
            return haystack.contains("cart") && (
                haystack.contains("view cart") ||
                    haystack.contains("go to cart") ||
                    Regex("""\bcart\s+\d+\b""").containsMatchIn(haystack)
                )
        }
    }

    fun tryPendingPublic() {
        mainHandler.post { tryPending() }
    }
}
