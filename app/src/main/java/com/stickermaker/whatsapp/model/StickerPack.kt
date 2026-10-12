package com.stickermaker.whatsapp.model

import com.google.gson.annotations.SerializedName

data class StickerPack(
    @SerializedName("identifier")
    val identifier: String,
    @SerializedName("name")
    var name: String,
    @SerializedName("publisher")
    var publisher: String,
    @SerializedName("tray_image_file")
    var trayImageFile: String,
    @SerializedName("publisher_email")
    val publisherEmail: String = "",
    @SerializedName("publisher_website")
    val publisherWebsite: String = "",
    @SerializedName("privacy_policy_website")
    val privacyPolicyWebsite: String = "",
    @SerializedName("license_agreement_website")
    val licenseAgreementWebsite: String = "",
    @SerializedName("image_data_version")
    val imageDataVersion: String = "1",
    @SerializedName("avoid_cache")
    val avoidCache: Boolean = false,
    @SerializedName("animated_sticker_pack")
    var animatedStickerPack: Boolean = false,
    @SerializedName("stickers")
    val stickers: MutableList<Sticker> = mutableListOf()
) {
    val isReadyForWhatsApp: Boolean
        get() = stickers.size in 3..30 && trayImageFile.isNotEmpty()
}
