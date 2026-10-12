package com.stickermaker.whatsapp.utils

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.stickermaker.whatsapp.model.Sticker
import com.stickermaker.whatsapp.model.StickerPack
import java.io.File
import java.util.UUID

object StickerStorage {

    private const val PACKS_FILE = "sticker_packs.json"
    private const val STICKERS_DIR = "stickers"
    private val gson = Gson()

    private fun getJsonFile(context: Context): File {
        return File(context.filesDir, PACKS_FILE)
    }

    fun getPackDir(context: Context, packId: String): File {
        val dir = File(File(context.filesDir, STICKERS_DIR), packId)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getStickerFile(context: Context, packId: String, fileName: String): File {
        return File(getPackDir(context, packId), fileName)
    }

    @Synchronized
    fun getAllPacks(context: Context): MutableList<StickerPack> {
        val file = getJsonFile(context)
        if (!file.exists()) return mutableListOf()

        return try {
            val json = file.readText()
            val listType = object : TypeToken<MutableList<StickerPack>>() {}.type
            gson.fromJson<MutableList<StickerPack>>(json, listType) ?: mutableListOf()
        } catch (e: Exception) {
            e.printStackTrace()
            mutableListOf()
        }
    }

    @Synchronized
    fun getPack(context: Context, packId: String): StickerPack? {
        return getAllPacks(context).firstOrNull { it.identifier == packId }
    }

    @Synchronized
    fun savePacks(context: Context, packs: List<StickerPack>) {
        try {
            val json = gson.toJson(packs)
            getJsonFile(context).writeText(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun savePack(context: Context, pack: StickerPack) {
        val packs = getAllPacks(context)
        val index = packs.indexOfFirst { it.identifier == pack.identifier }
        if (index != -1) {
            packs[index] = pack
        } else {
            packs.add(pack)
        }
        savePacks(context, packs)
    }

    @Synchronized
    fun deletePack(context: Context, packId: String) {
        val packs = getAllPacks(context)
        packs.removeAll { it.identifier == packId }
        savePacks(context, packs)

        // Delete directory and sticker files
        getPackDir(context, packId).deleteRecursively()
    }

    /**
     * Imports an image Uri into the specified pack, preserving animated WebP or formatting static WebP.
     */
    fun addStickerToPack(context: Context, packId: String, imageUri: Uri): Boolean {
        val pack = getPack(context, packId) ?: return false
        if (pack.stickers.size >= 30) return false // WhatsApp max limit

        val isAnimated = ImageProcessor.isAnimatedWebP(context, imageUri)

        // WhatsApp strict rule: a pack cannot mix static and animated stickers.
        // If pack is brand new (0 stickers), adopt the sticker's type.
        if (pack.stickers.isEmpty()) {
            pack.animatedStickerPack = isAnimated
        } else {
            if (pack.animatedStickerPack != isAnimated) {
                return false // Type mismatch
            }
        }

        val stickerFileName = "sticker_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.webp"
        val destinationFile = getStickerFile(context, packId, stickerFileName)

        val success = ImageProcessor.processStickerImage(context, imageUri, destinationFile, isAnimated)
        if (!success) return false

        // If pack doesn't have a tray icon yet, create one from this first sticker
        if (pack.trayImageFile.isEmpty()) {
            val trayFileName = "tray_${packId}.webp"
            val trayFile = getStickerFile(context, packId, trayFileName)
            if (ImageProcessor.generateTrayIcon(context, imageUri, trayFile)) {
                pack.trayImageFile = trayFileName
            }
        }

        pack.stickers.add(Sticker(imageFileName = stickerFileName, emojis = listOf("😀")))
        savePack(context, pack)
        return true
    }

    private val signatureCache = mutableMapOf<String, Pair<String, String>>()

    @Synchronized
    fun getAllExistingStickerSignatures(context: Context): Pair<MutableSet<String>, MutableSet<String>> {
        val payloads = mutableSetOf<String>()
        val visuals = mutableSetOf<String>()
        val packs = getAllPacks(context)

        for (pack in packs) {
            val packDir = getPackDir(context, pack.identifier)
            for (sticker in pack.stickers) {
                val file = File(packDir, sticker.imageFileName)
                if (!file.exists()) continue

                val cached = signatureCache[file.absolutePath]
                if (cached != null) {
                    if (cached.first.isNotEmpty()) payloads.add(cached.first)
                    if (cached.second.isNotEmpty()) visuals.add(cached.second)
                } else {
                    val inspection = ImageProcessor.inspectWebP(context, Uri.fromFile(file))
                    if (inspection.isValidWebP) {
                        signatureCache[file.absolutePath] = Pair(inspection.payloadHash, inspection.visualDHash)
                        if (inspection.payloadHash.isNotEmpty()) payloads.add(inspection.payloadHash)
                        if (inspection.visualDHash.isNotEmpty()) visuals.add(inspection.visualDHash)
                    }
                }
            }
        }
        return Pair(payloads, visuals)
    }

    /**
     * Exports a sticker pack as a ZIP file containing the stickers, tray icon, and metadata.
     */
    fun exportPackToZip(context: Context, packId: String): File? {
        val pack = getPack(context, packId) ?: return null
        return try {
            val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val cleanName = pack.name.replace(Regex("[^a-zA-Z0-9_\\-\\s]"), "").trim().replace(Regex("\\s+"), "_").ifEmpty { "stickers" }
            val zipFile = File(exportDir, "${cleanName}_stickers.zip")
            if (zipFile.exists()) zipFile.delete()

            java.util.zip.ZipOutputStream(java.io.FileOutputStream(zipFile)).use { zipOut ->
                // Write pack_metadata.json
                val metadata = mapOf(
                    "name" to pack.name,
                    "publisher" to pack.publisher,
                    "animatedStickerPack" to pack.animatedStickerPack,
                    "trayImageFile" to pack.trayImageFile,
                    "stickers" to pack.stickers
                )
                val json = gson.toJson(metadata)
                zipOut.putNextEntry(java.util.zip.ZipEntry("pack_metadata.json"))
                zipOut.write(json.toByteArray(Charsets.UTF_8))
                zipOut.closeEntry()

                val packDir = getPackDir(context, packId)

                // Write tray icon if exists
                if (pack.trayImageFile.isNotEmpty()) {
                    val trayFile = File(packDir, pack.trayImageFile)
                    if (trayFile.exists()) {
                        zipOut.putNextEntry(java.util.zip.ZipEntry(pack.trayImageFile))
                        trayFile.inputStream().use { it.copyTo(zipOut) }
                        zipOut.closeEntry()
                    }
                }

                // Write each sticker
                for (sticker in pack.stickers) {
                    val file = File(packDir, sticker.imageFileName)
                    if (file.exists()) {
                        zipOut.putNextEntry(java.util.zip.ZipEntry(sticker.imageFileName))
                        file.inputStream().use { it.copyTo(zipOut) }
                        zipOut.closeEntry()
                    }
                }
            }
            zipFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Exports all packs (or a list of specific pack IDs) in a single multi-pack ZIP archive.
     */
    fun exportAllPacksToZip(context: Context, packIds: List<String>? = null): File? {
        val allPacks = getAllPacks(context)
        val targetPacks = if (!packIds.isNullOrEmpty()) {
            allPacks.filter { packIds.contains(it.identifier) }
        } else {
            allPacks
        }
        if (targetPacks.isEmpty()) return null

        return try {
            val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val zipFile = File(exportDir, "coleccion_stickers_${System.currentTimeMillis()}.zip")
            if (zipFile.exists()) zipFile.delete()

            java.util.zip.ZipOutputStream(java.io.FileOutputStream(zipFile)).use { zipOut ->
                // Write collection_metadata.json
                val collectionData = mapOf(
                    "version" to 1,
                    "isCollection" to true,
                    "packs" to targetPacks
                )
                val json = gson.toJson(collectionData)
                zipOut.putNextEntry(java.util.zip.ZipEntry("collection_metadata.json"))
                zipOut.write(json.toByteArray(Charsets.UTF_8))
                zipOut.closeEntry()

                for (pack in targetPacks) {
                    val packDir = getPackDir(context, pack.identifier)

                    // Write tray
                    if (pack.trayImageFile.isNotEmpty()) {
                        val trayFile = File(packDir, pack.trayImageFile)
                        if (trayFile.exists()) {
                            zipOut.putNextEntry(java.util.zip.ZipEntry("${pack.identifier}/${pack.trayImageFile}"))
                            trayFile.inputStream().use { it.copyTo(zipOut) }
                            zipOut.closeEntry()
                        }
                    }

                    // Write stickers
                    for (sticker in pack.stickers) {
                        val file = File(packDir, sticker.imageFileName)
                        if (file.exists()) {
                            zipOut.putNextEntry(java.util.zip.ZipEntry("${pack.identifier}/${sticker.imageFileName}"))
                            file.inputStream().use { it.copyTo(zipOut) }
                            zipOut.closeEntry()
                        }
                    }
                }
            }
            zipFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    data class ZipImportResult(
        val success: Boolean,
        val packName: String,
        val newStickersAdded: Int,
        val duplicatesSkipped: Int,
        val totalInZip: Int,
        val createdPackCount: Int = 1,
        val errorMessage: String? = null
    )

    /**
     * Imports a sticker pack (single pack or multi-pack collection) from a ZIP file,
     * thoroughly checking against duplicates already in the app.
     */
    fun importPackFromZip(context: Context, zipUri: Uri, targetPackId: String? = null): ZipImportResult {
        val tempExtractDir = File(context.cacheDir, "zip_extract_${System.currentTimeMillis()}").apply { mkdirs() }
        return try {
            val inputStream = ImageProcessor.openStream(context, zipUri)
                ?: return ZipImportResult(false, "", 0, 0, 0, errorMessage = "No se pudo abrir el archivo ZIP")

            var collectionJson: String? = null
            var singlePackJson: String? = null
            val extractedFilesMap = mutableMapOf<String, File>() // relative path -> File

            java.util.zip.ZipInputStream(inputStream).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    val entryName = entry.name.replace("\\", "/")
                    if (!entry.isDirectory && entryName.isNotEmpty()) {
                        val simpleName = File(entryName).name
                        if (simpleName.equals("collection_metadata.json", ignoreCase = true)) {
                            collectionJson = zipIn.bufferedReader(Charsets.UTF_8).readText()
                        } else if (simpleName.equals("pack_metadata.json", ignoreCase = true) || simpleName.equals("metadata.json", ignoreCase = true)) {
                            singlePackJson = zipIn.bufferedReader(Charsets.UTF_8).readText()
                        } else if (simpleName.endsWith(".webp", ignoreCase = true)) {
                            val outFile = File(tempExtractDir, entryName)
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { zipIn.copyTo(it) }
                            extractedFilesMap[entryName] = outFile
                        }
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }

            // Get existing signatures across ALL packs currently in the app
            val (existingPayloads, existingVisuals) = getAllExistingStickerSignatures(context)

            // CASE 1: Multi-pack Collection ZIP
            if (!collectionJson.isNullOrEmpty()) {
                val collectionObj = gson.fromJson(collectionJson, Map::class.java)
                val rawPacks = collectionObj["packs"] as? List<Map<String, Any>> ?: emptyList()

                var totalAdded = 0
                var totalDuplicatesSkipped = 0
                var packsCreated = 0
                var totalStickersInZip = 0

                for (packMap in rawPacks) {
                    val packName = (packMap["name"] as? String)?.ifBlank { "Paquete Importado" } ?: "Paquete Importado"
                    val publisher = (packMap["publisher"] as? String)?.ifBlank { "Importado" } ?: "Importado"
                    val isAnimated = (packMap["animatedStickerPack"] as? Boolean) ?: false
                    val packIdentifier = (packMap["identifier"] as? String) ?: ""
                    val stickersList = packMap["stickers"] as? List<Map<String, Any>> ?: emptyList()

                    val validFilesForPack = mutableListOf<File>()
                    for (stk in stickersList) {
                        val imgFile = stk["image_file"] as? String ?: stk["imageFileName"] as? String ?: continue
                        totalStickersInZip++

                        // Find matching file in extractedFilesMap
                        val candidateFile = extractedFilesMap["$packIdentifier/$imgFile"]
                            ?: extractedFilesMap.values.firstOrNull { it.name.equals(imgFile, ignoreCase = true) }
                            ?: continue

                        val inspection = ImageProcessor.inspectWebP(context, Uri.fromFile(candidateFile))
                        if (!inspection.isValidWebP) continue

                        val isDup = (inspection.payloadHash.isNotEmpty() && existingPayloads.contains(inspection.payloadHash)) ||
                                    (inspection.visualDHash.isNotEmpty() && existingVisuals.contains(inspection.visualDHash))

                        if (isDup) {
                            totalDuplicatesSkipped++
                        } else {
                            if (inspection.payloadHash.isNotEmpty()) existingPayloads.add(inspection.payloadHash)
                            if (inspection.visualDHash.isNotEmpty()) existingVisuals.add(inspection.visualDHash)
                            validFilesForPack.add(candidateFile)
                        }
                    }

                    if (validFilesForPack.isNotEmpty()) {
                        val chunks = validFilesForPack.chunked(30)
                        for ((idx, chunk) in chunks.withIndex()) {
                            val newPackId = UUID.randomUUID().toString()
                            val title = if (chunks.size > 1) "$packName (Parte ${idx + 1})" else packName
                            val newPack = StickerPack(
                                identifier = newPackId,
                                name = title,
                                publisher = publisher,
                                trayImageFile = "",
                                animatedStickerPack = isAnimated
                            )
                            savePack(context, newPack)
                            packsCreated++

                            for (file in chunk) {
                                if (addStickerToPack(context, newPackId, Uri.fromFile(file))) {
                                    totalAdded++
                                }
                            }
                        }
                    }
                }

                tempExtractDir.deleteRecursively()
                return ZipImportResult(
                    success = true,
                    packName = "Colección (${packsCreated} paquetes creados)",
                    newStickersAdded = totalAdded,
                    duplicatesSkipped = totalDuplicatesSkipped,
                    totalInZip = totalStickersInZip,
                    createdPackCount = packsCreated
                )
            }

            // CASE 2: Single Pack ZIP or flat list of WebP files
            val extractedFiles = extractedFilesMap.values.toList()
            if (extractedFiles.isEmpty()) {
                tempExtractDir.deleteRecursively()
                return ZipImportResult(false, "", 0, 0, 0, errorMessage = "El archivo ZIP no contiene stickers .webp")
            }

            var metadataPackName: String? = null
            var metadataPublisher: String? = null
            var metadataIsAnimated: Boolean? = null
            var metadataTrayFile: String? = null

            if (!singlePackJson.isNullOrEmpty()) {
                try {
                    val obj = gson.fromJson(singlePackJson, Map::class.java)
                    metadataPackName = obj["name"] as? String
                    metadataPublisher = obj["publisher"] as? String
                    metadataIsAnimated = obj["animatedStickerPack"] as? Boolean
                    metadataTrayFile = obj["trayImageFile"] as? String
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            val finalPackName = metadataPackName?.ifBlank { null }
                ?: (zipUri.lastPathSegment?.substringAfterLast("/")?.removeSuffix(".zip")?.replace("_", " ")?.trim()?.ifBlank { "Stickers Importados" } ?: "Stickers Importados")
            val finalPublisher = metadataPublisher?.ifBlank { "Importado" } ?: "Importado"

            var duplicatesCount = 0
            val validStickers = mutableListOf<Pair<File, StickerInspectionResult>>()

            for (file in extractedFiles) {
                // If it matches tray icon, skip from stickers list
                if (metadataTrayFile != null && file.name.equals(metadataTrayFile, ignoreCase = true)) {
                    continue
                }

                val inspection = ImageProcessor.inspectWebP(context, Uri.fromFile(file))
                if (!inspection.isValidWebP) continue

                val isDup = (inspection.payloadHash.isNotEmpty() && existingPayloads.contains(inspection.payloadHash)) ||
                            (inspection.visualDHash.isNotEmpty() && existingVisuals.contains(inspection.visualDHash))

                if (isDup) {
                    duplicatesCount++
                } else {
                    if (inspection.payloadHash.isNotEmpty()) existingPayloads.add(inspection.payloadHash)
                    if (inspection.visualDHash.isNotEmpty()) existingVisuals.add(inspection.visualDHash)
                    validStickers.add(Pair(file, inspection))
                }
            }

            if (validStickers.isEmpty()) {
                tempExtractDir.deleteRecursively()
                return ZipImportResult(
                    success = true,
                    packName = finalPackName,
                    newStickersAdded = 0,
                    duplicatesSkipped = duplicatesCount,
                    totalInZip = extractedFiles.size,
                    createdPackCount = 0
                )
            }

            // Distribute stickers into packs
            val animatedList = validStickers.filter { it.second.isAnimated }.map { it.first }
            val staticList = validStickers.filter { !it.second.isAnimated }.map { it.first }

            var totalAdded = 0
            var packsCreated = 0

            fun addFilesToPacks(files: List<File>, isAnim: Boolean, baseName: String) {
                val chunks = files.chunked(30)
                for ((idx, chunk) in chunks.withIndex()) {
                    val packId = UUID.randomUUID().toString()
                    val packTitle = if (chunks.size > 1) "$baseName (Parte ${idx + 1})" else baseName
                    val pack = StickerPack(
                        identifier = packId,
                        name = packTitle,
                        publisher = finalPublisher,
                        trayImageFile = "",
                        animatedStickerPack = isAnim
                    )
                    savePack(context, pack)
                    packsCreated++

                    for (file in chunk) {
                        if (addStickerToPack(context, packId, Uri.fromFile(file))) {
                            totalAdded++
                        }
                    }
                }
            }

            if (animatedList.isNotEmpty() && staticList.isNotEmpty()) {
                addFilesToPacks(animatedList, true, "$finalPackName (Animados)")
                addFilesToPacks(staticList, false, "$finalPackName (Estáticos)")
            } else if (animatedList.isNotEmpty()) {
                addFilesToPacks(animatedList, true, finalPackName)
            } else {
                addFilesToPacks(staticList, false, finalPackName)
            }

            tempExtractDir.deleteRecursively()

            ZipImportResult(
                success = true,
                packName = finalPackName,
                newStickersAdded = totalAdded,
                duplicatesSkipped = duplicatesCount,
                totalInZip = extractedFiles.size,
                createdPackCount = packsCreated
            )
        } catch (e: Exception) {
            e.printStackTrace()
            tempExtractDir.deleteRecursively()
            ZipImportResult(false, "", 0, 0, 0, errorMessage = e.message ?: "Error al importar ZIP")
        }
    }

    /**
     * Removes a sticker from the pack.
     */
    fun removeStickerFromPack(context: Context, packId: String, stickerFileName: String): Boolean {
        val pack = getPack(context, packId) ?: return false
        val removed = pack.stickers.removeAll { it.imageFileName == stickerFileName }
        if (removed) {
            val file = getStickerFile(context, packId, stickerFileName)
            signatureCache.remove(file.absolutePath)
            if (file.exists()) file.delete()
            savePack(context, pack)
        }
        return removed
    }
}
