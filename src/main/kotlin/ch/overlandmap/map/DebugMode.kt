package ch.overlandmap.map

import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * True for a debuggable build. Detected at runtime from the app's manifest flag
 * (set automatically for debug builds, cleared for release), so the shared core
 * needs no per-build-type constant. Gates the Settings "Debug" menu.
 */
fun isDebugBuild(context: Context): Boolean =
    (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
