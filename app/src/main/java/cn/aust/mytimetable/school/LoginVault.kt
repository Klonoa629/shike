package cn.aust.mytimetable.school

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Accessed only by the shared school-work queue. No credentials in preferences or logs.
class LoginVault(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "school-login.bin"))
    private val alias = "school-login-v1"

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) throw SchoolFailure("无法读取保存的登录信息，请退出账号后重新登录。")
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    fun save(login: SavedLogin) {
        val data = JsonObject().apply {
            addProperty("account", login.account)
            addProperty("password", login.password)
            addProperty("token", login.session.token)
            addProperty("secret", login.session.secret)
            addProperty("cur_identity", login.session.identity)
        }.toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        val encrypted = try { cipher.doFinal(data) } finally { data.fill(0) }
        val stream = file.startWrite()
        try {
            stream.write(byteArrayOf(1, cipher.iv.size.toByte()))
            stream.write(cipher.iv)
            stream.write(encrypted)
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    fun load(): SavedLogin? {
        // AtomicFile restores an interrupted write before reading when needed.
        val bytes = try { file.readFully() } catch (_: java.io.FileNotFoundException) { return null }
        require(bytes.size > 30 && bytes[0].toInt() == 1)
        val ivSize = bytes[1].toInt() and 255
        require(ivSize == 12 && bytes.size > 2 + ivSize + 16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, bytes.copyOfRange(2, 2 + ivSize)))
        val plain = cipher.doFinal(bytes.copyOfRange(2 + ivSize, bytes.size))
        return try {
            val obj = JsonParser.parseString(plain.toString(Charsets.UTF_8)).asJsonObject
            val account = obj.get("account").asString
            val password = obj.get("password").asString
            require(account.isNotBlank() && password.isNotEmpty())
            SavedLogin(account, password, SchoolProtocol.session(obj))
        } finally {
            plain.fill(0)
        }
    }

    fun clear() {
        file.delete()
        check(!file.baseFile.exists())
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }
}
