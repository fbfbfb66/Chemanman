package com.goings.kaidanzhushou.data.local

import androidx.room.TypeConverter
import com.goings.kaidanzhushou.domain.RecognitionStatus
import com.goings.kaidanzhushou.domain.ReviewStatus

class Converters {
    @TypeConverter fun recognitionToString(value: RecognitionStatus) = value.name
    @TypeConverter fun stringToRecognition(value: String) = RecognitionStatus.valueOf(value)
    @TypeConverter fun reviewToString(value: ReviewStatus) = value.name
    @TypeConverter fun stringToReview(value: String) = ReviewStatus.valueOf(value)
}
