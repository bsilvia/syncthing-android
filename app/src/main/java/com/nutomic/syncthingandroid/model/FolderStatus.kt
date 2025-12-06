package com.nutomic.syncthingandroid.model

@Suppress("unused")
class FolderStatus {
    @JvmField
    var globalBytes: Long = 0
    var globalDeleted: Long = 0
    var globalDirectories: Long = 0
    @JvmField
    var globalFiles: Long = 0
    var globalSymlinks: Long = 0
    var ignorePatterns: Boolean = false
    @JvmField
    var invalid: String? = null
    var localBytes: Long = 0
    var localDeleted: Long = 0
    var localDirectories: Long = 0
    var localSymlinks: Long = 0
    var localFiles: Long = 0
    @JvmField
    var inSyncBytes: Long = 0
    @JvmField
    var inSyncFiles: Long = 0
    var needBytes: Long = 0
    @JvmField
    var needDeletes: Long = 0
    @JvmField
    var needDirectories: Long = 0
    @JvmField
    var needFiles: Long = 0
    @JvmField
    var needSymlinks: Long = 0
    var pullErrors: Long = 0
    var sequence: Long = 0
    @JvmField
    var state: String? = null
    var stateChanged: String? = null
    var version: Long = 0
    @JvmField
    var error: String? = null
    var watchError: String? = null
}
