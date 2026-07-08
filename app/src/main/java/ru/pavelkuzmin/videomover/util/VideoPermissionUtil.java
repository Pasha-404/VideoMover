package ru.pavelkuzmin.videomover.util;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

public final class VideoPermissionUtil {
    public static final int ACCESS_DENIED = 0;
    public static final int ACCESS_FULL = 1;
    public static final int ACCESS_PARTIAL = 2;

    private VideoPermissionUtil() {}

    public static int getAccessState(Context context) {
        if (Build.VERSION.SDK_INT >= 33) {
            if (isGranted(context, Manifest.permission.READ_MEDIA_VIDEO)) {
                return ACCESS_FULL;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                    && isGranted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)) {
                return ACCESS_PARTIAL;
            }
            return ACCESS_DENIED;
        }
        return isGranted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
                ? ACCESS_FULL
                : ACCESS_DENIED;
    }

    public static String[] getPermissionsToRequest() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return new String[]{
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            };
        }
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{Manifest.permission.READ_MEDIA_VIDEO};
        }
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    public static boolean hasAnyAccess(Context context) {
        return getAccessState(context) != ACCESS_DENIED;
    }

    private static boolean isGranted(Context context, String permission) {
        return ContextCompat.checkSelfPermission(context, permission)
                == PackageManager.PERMISSION_GRANTED;
    }
}
