package com.stickermaker.whatsapp.model

import com.google.gson.annotations.SerializedName

data class Sticker(
    @SerializedName("image_file")
    val imageFileName: String,
    @SerializedName("emojis")
    var emojis: List<String> = listOf("😀")
)
