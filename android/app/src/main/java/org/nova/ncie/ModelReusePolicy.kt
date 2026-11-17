package org.nova.ncie

/**
 * Pure decision for reusing model weights already resident in the native engine.
 * A path match alone is insufficient: the engine must still report ModelReady.
 */
internal object ModelReusePolicy {
    fun canReuse(activePath: String?, requestedPath: String, modelReady: Boolean): Boolean =
        !activePath.isNullOrBlank() &&
            requestedPath.isNotBlank() &&
            activePath == requestedPath &&
            modelReady
}
