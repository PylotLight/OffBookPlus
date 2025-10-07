package com.devlight.offbookplus.data

import androidx.room.TypeConverter
import com.devlight.offbookplus.model.MediaType

class MediaTypeConverter {
    @TypeConverter
    fun fromMediaType(value: MediaType): String {
        return value.name
    }

    @TypeConverter
    fun toMediaType(value: String): MediaType {
        return MediaType.valueOf(value)
    }
}