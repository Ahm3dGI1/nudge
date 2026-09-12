package com.astraedus.nudge.service

import android.view.accessibility.AccessibilityNodeInfo

/** Thin interface extracted for testability. */
interface InAppDetectorApi {
    /**
     * What is on screen: the feature a rule matches on, plus (Instagram only) which surface it was
     * reached from. Null when nothing recognisable is in front of the user.
     */
    fun detect(packageName: String, rootNode: AccessibilityNodeInfo?): InAppDetector.Detection?

    /**
     * The feature alone, for callers that only need to know WHAT the user is looking at (the
     * interaction counter's label, for instance) and not where they came from.
     */
    fun detectFeature(packageName: String, rootNode: AccessibilityNodeInfo?): InAppDetector.Feature? =
        detect(packageName, rootNode)?.feature
}
