package com.imageorga.app.index

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat

enum class PhotoAccess { NONE, PARTIAL, FULL }

data class PermissionState(
    val photos: PhotoAccess = PhotoAccess.NONE,
    val notifications: Boolean = false,
    /** True when the system will not restrict the app's background work to save battery. */
    val unrestrictedBattery: Boolean = false,
)

object Permissions {
    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun photoAccess(context: Context): PhotoAccess = when {
        Build.VERSION.SDK_INT >= 33 -> when {
            granted(context, Manifest.permission.READ_MEDIA_IMAGES) -> PhotoAccess.FULL
            Build.VERSION.SDK_INT >= 34 &&
                granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> PhotoAccess.PARTIAL
            else -> PhotoAccess.NONE
        }
        granted(context, Manifest.permission.READ_EXTERNAL_STORAGE) -> PhotoAccess.FULL
        else -> PhotoAccess.NONE
    }

    fun hasPhotoAccess(context: Context) = photoAccess(context) != PhotoAccess.NONE

    fun state(context: Context) = PermissionState(
        photos = photoAccess(context),
        notifications = Notifications.canPost(context),
        unrestrictedBattery = context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName),
    )

    /** Runtime permissions to ask for on this Android version. */
    fun runtimePermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}
