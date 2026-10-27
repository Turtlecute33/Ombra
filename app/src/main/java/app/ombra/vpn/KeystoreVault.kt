package app.ombra.vpn

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.ProviderException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * One secret in a file, encrypted with a non-exportable Android Keystore AES-256-GCM key (StrongBox
 * when the device has it). No user authentication and no unlocked-device requirement: the VPN must
 * reconnect while the screen is locked. The file lives in noBackupFilesDir; Keystore keys never
 * leave the device, so a copied file is useless elsewhere.
 *
 * File format: version byte, 12-byte IV, ciphertext with the 16-byte GCM tag.
 */
open class KeystoreVault(
    dir: File,
    private val fileName: String,
    private val keyAlias: String,
    aad: String,
    private val logTag: String,
) {
    private val file = File(dir, fileName)
    private val aad = aad.toByteArray()

    sealed interface Read {
        class Secret(val bytes: ByteArray) : Read

        /** No file, or one that can never open again: no Keystore key, a damaged file, an invalidated key. */
        data object Gone : Read

        /** Maybe temporary, for example a Keystore that does not answer. */
        class Failed(val error: Exception) : Read
    }

    @Synchronized
    fun read(): Read {
        if (!file.exists()) return Read.Gone
        return try {
            val blob = file.readBytes()
            if (blob.size <= 1 + IV_SIZE || blob[0] != VERSION) return gone("bad file")
            val key = existingKey() ?: return gone("no Keystore key")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 1, IV_SIZE))
            cipher.updateAAD(aad)
            Read.Secret(cipher.doFinal(blob, 1 + IV_SIZE, blob.size - 1 - IV_SIZE))
        } catch (e: AEADBadTagException) {
            gone(e.javaClass.simpleName)
        } catch (e: KeyPermanentlyInvalidatedException) {
            gone(e.javaClass.simpleName)
        } catch (e: Exception) {
            Log.w(logTag, "stored secret is not readable: ${e.javaClass.simpleName}")
            Read.Failed(e)
        }
    }

    private fun gone(reason: String): Read {
        Log.w(logTag, "stored secret is gone: $reason")
        return Read.Gone
    }

    /** The stored secret, or null when there is none or it cannot be decrypted. */
    fun load(): ByteArray? = (read() as? Read.Secret)?.bytes

    /** Encrypts and atomically replaces the stored secret. Never throws. */
    @Synchronized
    fun store(secret: ByteArray): Boolean = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: createKey())
        cipher.updateAAD(aad)
        val sealed = cipher.doFinal(secret)
        val iv = cipher.iv
        check(iv.size == IV_SIZE) { "unexpected IV size" }
        writeAtomically(byteArrayOf(VERSION) + iv + sealed)
        true
    } catch (e: Exception) {
        Log.e(logTag, "cannot store secret: ${e.javaClass.simpleName}")
        false
    }

    @Synchronized
    fun delete() {
        if (file.exists() && !file.delete()) Log.w(logTag, "cannot delete the stored secret")
    }

    private fun existingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    /** Any StrongBox fault (StrongBoxUnavailableException is one ProviderException) falls back to the TEE. */
    private fun createKey(): SecretKey = try {
        generateKey(strongBox = true)
    } catch (e: ProviderException) {
        Log.w(logTag, "StrongBox key failed: ${e.javaClass.simpleName}")
        generateKey(strongBox = false)
    }

    private fun generateKey(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
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
        val tmp = File(file.parentFile, "$fileName.tmp")
        FileOutputStream(tmp).use {
            it.write(bytes)
            it.fd.sync()
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        syncDir()
    }

    /** Makes the rename durable: data that only this secret opens can be written right after. */
    private fun syncDir() {
        try {
            val fd = Os.open(file.parent, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(fd)
            } finally {
                Os.close(fd)
            }
        } catch (e: ErrnoException) {
            Log.w(logTag, "cannot sync the directory: ${OsConstants.errnoName(e.errno)}")
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
        const val VERSION: Byte = 1
    }
}

/** The WireGuard secret key. Any read failure gives null: Rust then makes a new key. */
class WgKeyVault(dir: File) : KeystoreVault(dir, "wg_key.bin", "ombra_wg_key_v1", "ombra-wg-secret-key-v1", "OmbraWgKeyVault")

/**
 * The key that encrypts the Rust config.json (rust/UPSTREAM.md P13), with the account number and
 * the auth token. Rust resets a config that its key cannot open, so a new key is made only when the
 * old one is gone for good. A failure that may pass throws, so the core does not start this time
 * and the config stays.
 */
class ConfigKeyVault(dir: File) : KeystoreVault(dir, "config_key.bin", "ombra_config_key_v1", "ombra-config-key-v1", "OmbraConfigKeyVault") {
    /** The stored key or a new one; null when a new one cannot be stored (config.json then stays plain text). */
    fun loadOrCreate(): ByteArray? {
        var attempt = 1
        while (true) {
            when (val read = read()) {
                is Read.Secret -> return read.bytes
                Read.Gone -> return create()
                is Read.Failed -> {
                    if (attempt == ATTEMPTS) throw IllegalStateException("config key is not readable", read.error)
                    attempt++
                    Thread.sleep(RETRY_DELAY_MS)
                }
            }
        }
    }

    private fun create(): ByteArray? {
        val key = ByteArray(KEY_SIZE).also { SecureRandom().nextBytes(it) }
        if (store(key)) return key
        key.fill(0)
        return null
    }

    private companion object {
        const val KEY_SIZE = 32
        const val ATTEMPTS = 3
        const val RETRY_DELAY_MS = 200L
    }
}
