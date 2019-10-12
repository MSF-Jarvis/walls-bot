package me.msfjarvis.wallsbot

import me.ivmg.telegram.Bot
import me.ivmg.telegram.entities.ChatAction
import me.ivmg.telegram.entities.ParseMode
import me.ivmg.telegram.network.fold
import org.dizitart.kno2.filters.eq
import org.dizitart.no2.objects.ObjectRepository
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException


fun requireNotEmpty(str: String): String {
    return if (str.isNotBlank()) str else {
        throw IllegalArgumentException("Required value was empty")
    }
}

val File.sanitizedName
    get() = nameWithoutExtension.replace('_', ' ')

fun Bot.sendPictureSafe(
        repository: ObjectRepository<CachedFile>,
        chatId: Long,
        baseUrl: String,
        fileToSend: Pair<File, String>,
        replyToMessageId: Long? = null,
        genericCaption: Boolean = false
) {
    val file = fileToSend.first
    val digest = fileToSend.second
    val dbCursor = repository.find(CachedFile::fileHash eq digest)
    val fileId = dbCursor.firstOrDefault()?.fileId
    val caption = if (genericCaption)
        "[Link]($baseUrl/${file.name}"
    else
        "[${file.sanitizedName}]($baseUrl/${file.name})"
    sendChatAction(chatId = chatId, action = ChatAction.UPLOAD_PHOTO)
    sendPhoto(
            chatId = chatId,
            photo = fileId ?: "$baseUrl/${file.name}",
            caption = caption,
            parseMode = ParseMode.MARKDOWN,
            replyToMessageId = replyToMessageId
    ).fold({ response ->
        response?.result?.photo?.get(0)?.fileId?.apply {
            if (dbCursor.size() == 0)
                repository.insert(CachedFile(this, digest))
        }
    }, {
        sendChatAction(chatId = chatId, action = ChatAction.UPLOAD_DOCUMENT)
        val documentMessage = if (fileId == null) {
            sendDocument(
                    chatId = chatId,
                    document = file,
                    caption = caption,
                    parseMode = ParseMode.MARKDOWN,
                    replyToMessageId = replyToMessageId
            )
        } else {
            sendDocument(
                    chatId = chatId,
                    fileId = fileId,
                    caption = caption,
                    parseMode = ParseMode.MARKDOWN,
                    replyToMessageId = replyToMessageId
            )
        }
        documentMessage.fold({ response ->
            response?.result?.document?.fileId?.apply {
                if (dbCursor.size() == 0)
                    repository.insert(CachedFile(this, digest))
            }
        }, {})
    })
}

fun File.calculateMD5(): String? {
    val digest: MessageDigest
    try {
        digest = MessageDigest.getInstance("MD5")
    } catch (e: NoSuchAlgorithmException) {
        println("Exception while getting digest")
        return null
    }

    val inputStream: InputStream
    try {
        inputStream = FileInputStream(this)
    } catch (e: FileNotFoundException) {
        println("Exception while getting FileInputStream")
        return null
    }

    val buffer = ByteArray(8192)
    var read: Int
    try {
        read = inputStream.read(buffer)
        while (read > 0) {
            digest.update(buffer, 0, read)
            read = inputStream.read(buffer)
        }
        val md5sum = digest.digest()
        val bigInt = BigInteger(1, md5sum)
        var output = bigInt.toString(16)
        // Fill to 32 chars
        output = String.format("%32s", output).replace(' ', '0')
        return output
    } catch (e: IOException) {
        throw RuntimeException("Unable to process file for MD5")
    } finally {
        try {
            inputStream.close()
        } catch (e: IOException) {
            println("Exception on closing MD5 input stream")
        }
    }
}
