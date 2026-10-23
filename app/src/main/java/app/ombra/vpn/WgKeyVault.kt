package app.ombra.vpn

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the WireGuard secret key encrypted with a non-exportable Android Keystore AES-256-GCM key
 * (StrongBox when the device has it). No user authentication and no unlocked-device requirement:
 * the VPN must reconnect while the screen is locked. The file lives in noBackupFilesDir; Keystore
 * keys never leave the device, so a copied file is useless elsewhere.
 *
 * File format: version byte, 12-byte IV, ciphertext with the 16-byte GCM tag.
 */
class WgKeyVault(dir: File) {
    private val file = File(dir, FILE_NAME)

    /** The stored secret, or null when there is none or it cannot be decrypted (Rust then makes a new key). */
    @Synchronized
    fun load(): ByteArray? {
        if (!file.exists()) return null
        return try {
            val blob = file.readBytes()
            require(blob.size > 1 + IV_SIZE && blob[0] == VERSION) { "bad key file" }
            val key = existingKey() ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 1, IV_SIZE))
            cipher.updateAAD(AAD)
            cipher.doFinal(blob, 1 + IV_SIZE, blob.size - 1 - IV_SIZE)
        } catch (e: Exception) {
            Log.w(TAG, "stored WireGuard key is not readable: ${e.javaClass.simpleName}")
            null
        }
    }

    /** Encrypts and atomically replaces the stored secret. Never throws. */
    @Synchronized
    fun store(secret: ByteArray): Boolean = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: createKey())
        cipher.updateAAD(AAD)
        val sealed = cipher.doFinal(secret)
        val iv = cipher.iv
        check(iv.size == IV_SIZE) { "unexpected IV size" }
        writeAtomically(byteArrayOf(VERSION) + iv + sealed)
        true
    } catch (e: Exception) {
        Log.e(TAG, "cannot store WireGuard key: ${e.javaClass.simpleName}")
        false
    }

    private fun existingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun createKey(): SecretKey = try {
        generateKey(strongBox = true)
    } catch (e: StrongBoxUnavailableException) {
        generateKey(strongBox = false)
    }

    private fun generateKey(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private fun writeAtomically(bytes: ByteArray) {
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        FileOutputStream(tmp).use {
            it.write(bytes)
            it.fd.sync()
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private companion object {
        const val TAG = "OmbraWgKeyVault"
        const val FILE_NAME = "wg_key.bin"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "ombra_wg_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
        const val VERSION: Byte = 1
        val AAD = "ombra-wg-secret-key-v1".toByteArray()
    }
}
