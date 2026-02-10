package org.avium.autotask.overlay.contract

/**
 * Stable public protocol for controlling overlay behavior.
 *
 * This contract is intended for future external integrations.
 */
object OverlayContract {
    const val PROTOCOL_VERSION = 1

    private const val ACTION_PREFIX = "org.avium.autotask.overlay.action"
    private const val EXTRA_PREFIX = "org.avium.autotask.overlay.extra"

    const val ACTION_START = "$ACTION_PREFIX.START"
    const val ACTION_STOP = "$ACTION_PREFIX.STOP"
    const val ACTION_TOGGLE_SIZE = "$ACTION_PREFIX.TOGGLE_SIZE"
    const val ACTION_SET_TOUCH_PASSTHROUGH = "$ACTION_PREFIX.SET_TOUCH_PASSTHROUGH"

    const val EXTRA_QUESTION = "$EXTRA_PREFIX.QUESTION"
    const val EXTRA_TARGET_PACKAGE = "$EXTRA_PREFIX.TARGET_PACKAGE"
    const val EXTRA_TARGET_ACTIVITY = "$EXTRA_PREFIX.TARGET_ACTIVITY"
    const val EXTRA_TOUCH_PASSTHROUGH = "$EXTRA_PREFIX.TOUCH_PASSTHROUGH"
    const val EXTRA_PROTOCOL_VERSION = "$EXTRA_PREFIX.PROTOCOL_VERSION"

    const val DEFAULT_TARGET_PACKAGE = "com.android.dialer"
}
