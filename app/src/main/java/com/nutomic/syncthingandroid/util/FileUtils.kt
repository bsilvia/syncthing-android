package com.nutomic.syncthingandroid.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.util.Log
import java.io.File
import java.lang.reflect.Array
import java.util.Arrays
import androidx.core.net.toUri

/**
 * Utils for dealing with Storage Access Framework URIs.
 */
object FileUtils {
    private const val TAG = "FileUtils"

    private const val DOWNLOADS_VOLUME_NAME = "downloads"
    private const val PRIMARY_VOLUME_NAME = "primary"
    private const val HOME_VOLUME_NAME = "home"

    @JvmStatic
    fun getAbsolutePathFromSAFUri(context: Context, safResultUri: Uri?): String? {
        val treeUri = DocumentsContract.buildDocumentUriUsingTree(
            safResultUri,
            DocumentsContract.getTreeDocumentId(safResultUri)
        )
        return getAbsolutePathFromTreeUri(context, treeUri)
    }

    @JvmStatic
    fun getAbsolutePathFromTreeUri(context: Context, treeUri: Uri?): String? {
        if (treeUri == null) {
            Log.w(TAG, "getAbsolutePathFromTreeUri: called with treeUri == null")
            return null
        }

        // Determine volumeId, e.g. "home", "documents"
        val volumeId = getVolumeIdFromTreeUri(treeUri)
        if (volumeId == null) {
            return null
        }

        // Handle Uri referring to internal or external storage.
        var volumePath = getVolumePath(volumeId, context)
        if (volumePath == null) {
            return File.separator
        }
        if (volumePath.endsWith(File.separator)) {
            volumePath = volumePath.substring(0, volumePath.length - 1)
        }
        var documentPath = getDocumentPathFromTreeUri(treeUri)
        if (documentPath.endsWith(File.separator)) {
            documentPath = documentPath.substring(0, documentPath.length - 1)
        }
        if (documentPath.length > 0) {
            if (documentPath.startsWith(File.separator)) {
                return volumePath + documentPath
            } else {
                return volumePath + File.separator + documentPath
            }
        } else {
            return volumePath
        }
    }

    private fun getVolumePath(volumeId: String?, context: Context): String? {
        try {
            if (HOME_VOLUME_NAME == volumeId) {
                Log.v(TAG, "getVolumePath: isHomeVolume")
                // Reading the environment var avoids hard coding the case of the "documents" folder.
                return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                    .getAbsolutePath()
            }
            if (DOWNLOADS_VOLUME_NAME == volumeId) {
                Log.v(TAG, "getVolumePath: isDownloadsVolume")
                // Reading the environment var avoids hard coding the case of the "downloads" folder.
                return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    .absolutePath
            }

            val mStorageManager =
                context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            val storageVolumeClazz = Class.forName("android.os.storage.StorageVolume")
            val getVolumeList = mStorageManager.javaClass.getMethod("getVolumeList")
            val getUuid = storageVolumeClazz.getMethod("getUuid")
            val isPrimary = storageVolumeClazz.getMethod("isPrimary")
            val result = getVolumeList.invoke(mStorageManager)

            val length = Array.getLength(result!!)
            for (i in 0..<length) {
                val storageVolumeElement = Array.get(result, i)
                val uuid = getUuid.invoke(storageVolumeElement) as String?
                val primary = isPrimary.invoke(storageVolumeElement) as Boolean?
                val isPrimaryVolume = (primary == true && PRIMARY_VOLUME_NAME == volumeId)
                val isExternalVolume = ((uuid != null) && uuid == volumeId)
                Log.d(
                    TAG, "Found volume with uuid='" + uuid +
                            "', volumeId='" + volumeId +
                            "', primary=" + primary +
                            ", isPrimaryVolume=" + isPrimaryVolume +
                            ", isExternalVolume=" + isExternalVolume
                )
                if (isPrimaryVolume || isExternalVolume) {
                    Log.v(TAG, "getVolumePath: isPrimaryVolume || isExternalVolume")
                    // Return path if the correct volume corresponding to volumeId was found.
                    return volumeToPath(storageVolumeElement, storageVolumeClazz)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getVolumePath exception", e)
        }
        Log.e(TAG, "getVolumePath failed for volumeId='" + volumeId + "'")
        return null
    }

    @Throws(Exception::class)
    private fun volumeToPath(storageVolumeElement: Any?, storageVolumeClazz: Class<*>): String? {
        try {
            // >= API level 30
            val getDir = storageVolumeClazz.getMethod("getDirectory")
            val file = getDir.invoke(storageVolumeElement) as File?
            return file!!.path
        } catch (e: NoSuchMethodException) {
            // Not present in API level 30, available at some earlier point.
            val getPath = storageVolumeClazz.getMethod("getPath")
            return getPath.invoke(storageVolumeElement) as String?
        }
    }

    /**
     * FileProvider does not support converting the absolute path from
     * getExternalFilesDir() to a "content://" Uri. As "file://" Uri
     * has been blocked since Android 7+, we need to build the Uri
     * manually after discovering the first external storage.
     * This is crucial to assist the user finding a writeable folder
     * to use syncthing's two way sync feature.
     */
    @JvmStatic
    fun getExternalFilesDirUri(context: Context): Uri? {
        try {
            /**
             * Determine the app's private data folder on external storage if present.
             * e.g. "/storage/abcd-efgh/Android/com.nutomic.syncthinandroid/files"
             */
            val externalFilesDir = ArrayList<File?>()
            externalFilesDir.addAll(Arrays.asList<File?>(*context.getExternalFilesDirs(null)))
            externalFilesDir.remove(context.getExternalFilesDir(null))
            if (externalFilesDir.size == 0) {
                Log.w(TAG, "Could not determine app's private files directory on external storage.")
                return null
            }
            val absPath = externalFilesDir.get(0)!!.getAbsolutePath()
            val segments =
                absPath.split("/".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            if (segments.size < 2) {
                Log.w(
                    TAG,
                    "Could not extract volumeId from app's private files path '" + absPath + "'"
                )
                return null
            }
            // Extract the volumeId, e.g. "abcd-efgh"
            val volumeId: String = segments[2]
            // Build the content Uri for our private "files" folder.
            return ("content://com.android.externalstorage.documents/document/" +
                    volumeId + "%3AAndroid%2Fdata%2F" +
                    context.packageName + "%2Ffiles").toUri()
        } catch (e: Exception) {
            Log.w(TAG, "getExternalFilesDirUri exception", e)
        }
        return null
    }

    @JvmStatic
    fun getExternalFilesDirUriJvm(context: Context): Uri? {
        return getExternalFilesDirUri(context)
    }

    private fun getVolumeIdFromTreeUri(treeUri: Uri?): String? {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val split = docId.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        return if (split.isNotEmpty()) {
            split[0]
        } else {
            null
        }
    }

    private fun getDocumentPathFromTreeUri(treeUri: Uri?): String {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val split = docId.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        if (split.size >= 2) return split[1]
        else return File.separator
    }

    @JvmStatic
    fun cutTrailingSlash(path: String): String {
        if (path.endsWith(File.separator)) {
            return path.dropLast(1)
        }
        return path
    }
}
