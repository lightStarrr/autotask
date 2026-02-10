package org.avium.autotask.overlay.launch

data class TargetLaunchRequest(
    val question: String?,
    val targetPackage: String,
    val targetActivity: String?,
)
