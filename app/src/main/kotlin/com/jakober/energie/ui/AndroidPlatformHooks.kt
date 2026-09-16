package com.jakober.energie.ui

import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** Android-Dienste fuer die gemeinsame Oberflaeche: Adresse per Geocoder, Karte per Intent. */
class AndroidPlatformHooks(private val context: Context) : PlatformHooks {
    override val canOpenMap: Boolean get() = true

    override suspend fun reverseGeocode(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.GERMANY).getFromLocation(lat, lon, 1)?.firstOrNull()?.let { a ->
                listOfNotNull(a.thoroughfare?.let { t -> t + (a.subThoroughfare?.let { " $it" } ?: "") }, a.locality).joinToString(", ")
            }
        }.getOrNull()
    }

    override fun openMap(lat: Double, lon: Double) {
        val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(Auto)")
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
