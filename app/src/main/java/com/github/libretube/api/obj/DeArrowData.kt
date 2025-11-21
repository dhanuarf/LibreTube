package com.github.libretube.api.obj

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Serializable
@Parcelize
data class DeArrowData (
    var title: String? = null,
    val thumbnailUrl: String? = null,
) : Parcelable