package com.layers.app.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM with keys that live inside the Android Keystore (the key material never leaves the
 * secure hardware). Keys are created without a per-use authentication requirement and are NOT
 * invalidated when a new fingerprint is enrolled: the app itself gates access with BiometricPrompt,
 * so adding a fingerprint never destroys notes and background backups keep working.
 */
class KeystoreCrypto {

    private val lock = Any()

    private fun key(alias: String): SecretKey = synchronized(lock) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .setInvalidatedByBiometricEnrollment(false)
            .build()
        generator.init(spec)
        generator.generateKey()
    }

    fun encryptCipher(alias: String = NOTES_KEY): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key(alias)) }

    fun decryptCipher(iv: ByteArray, alias: String = NOTES_KEY): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(alias), GCMParameterSpec(TAG_BITS, iv))
        }

    fun encryptBytes(plain: ByteArray, alias: String = NOTES_KEY): ByteArray {
        val cipher = encryptCipher(alias)
        val iv = cipher.iv
        val body = cipher.doFinal(plain)
        return iv + body
    }

    fun decryptBytes(data: ByteArray, alias: String = NOTES_KEY): ByteArray {
        val iv = data.copyOfRange(0, IV_BYTES)
        return decryptCipher(iv, alias).doFinal(data, IV_BYTES, data.size - IV_BYTES)
    }

    fun encryptString(plain: String, alias: String = NOTES_KEY): String =
        PREFIX + Base64.encodeToString(encryptBytes(plain.toByteArray(Charsets.UTF_8), alias), Base64.NO_WRAP)

    fun decryptString(data: String, alias: String = NOTES_KEY): String {
        require(data.startsWith(PREFIX)) { "Not an encrypted value" }
        val bytes = Base64.decode(data.removePrefix(PREFIX), Base64.NO_WRAP)
        return String(decryptBytes(bytes, alias), Charsets.UTF_8)
    }

    /** Encrypts a whole stream in 256 KB chunks so large attachments never need to fit in memory. */
    fun encryptStream(input: InputStream, output: OutputStream, alias: String = NOTES_KEY) {
        ChunkedGcm.encrypt(input, output) { encryptCipher(alias) }
    }

    fun decryptStream(input: InputStream, output: OutputStream, alias: String = NOTES_KEY) {
        ChunkedGcm.decrypt(input, output) { iv -> decryptCipher(iv, alias) }
    }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val NOTES_KEY = "layers_notes_key_v1"
        const val BACKUP_PASSWORD_KEY = "layers_backup_password_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val IV_BYTES = 12
        const val PREFIX = "ENC1:"

        fun isEncryptedString(value: String): Boolean = value.startsWith(PREFIX)
    }
}

/**
 * A simple chunked AES-GCM stream format:
 * magic "LYRC" + version byte, then repeated [plainLength:int][iv:12 bytes][ciphertext+tag].
 * Each chunk is authenticated on its own, and the last chunk is flagged by a negative length so
 * truncation is detected.
 */
object ChunkedGcm {
    private val MAGIC = byteArrayOf('L'.code.toByte(), 'Y'.code.toByte(), 'R'.code.toByte(), 'C'.code.toByte())
    private const val VERSION: Byte = 1
    private const val CHUNK = 256 * 1024

    fun isChunkedEncrypted(header: ByteArray): Boolean =
        header.size >= 5 && header.copyOfRange(0, 4).contentEquals(MAGIC) && header[4] == VERSION

    fun encrypt(input: InputStream, output: OutputStream, newCipher: () -> Cipher) {
        val out = DataOutputStream(output)
        out.write(MAGIC)
        out.writeByte(VERSION.toInt())
        val buffer = ByteArray(CHUNK)
        var pending = readFully(input, buffer)
        var next = ByteArray(CHUNK)
        while (true) {
            val nextCount = if (pending == CHUNK) readFully(input, next) else 0
            val isLast = nextCount <= 0
            val cipher = newCipher()
            val iv = cipher.iv
            val body = cipher.doFinal(buffer, 0, maxOf(pending, 0))
            out.writeInt(if (isLast) -(maxOf(pending, 0) + 1) else pending)
            out.write(iv)
            out.write(body)
            if (isLast) break
            System.arraycopy(next, 0, buffer, 0, nextCount)
            pending = nextCount
            next = ByteArray(CHUNK)
        }
        out.flush()
    }

    fun decrypt(input: InputStream, output: OutputStream, cipherFor: (ByteArray) -> Cipher) {
        val inp = DataInputStream(input)
        val header = ByteArray(5)
        inp.readFully(header)
        if (!isChunkedEncrypted(header)) throw IOException("Unknown encrypted format")
        while (true) {
            val marker = try {
                inp.readInt()
            } catch (e: EOFException) {
                throw IOException("Encrypted data is truncated")
            }
            val isLast = marker < 0
            val plainLength = if (isLast) -marker - 1 else marker
            if (plainLength < 0 || plainLength > CHUNK) throw IOException("Corrupt encrypted data")
            val iv = ByteArray(KeystoreCrypto.IV_BYTES)
            inp.readFully(iv)
            val body = ByteArray(plainLength + KeystoreCrypto.TAG_BITS / 8)
            inp.readFully(body)
            val plain = cipherFor(iv).doFinal(body)
            output.write(plain)
            if (isLast) break
        }
        output.flush()
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        return total
    }
}

/** Password-based encryption for backups (PBKDF2-HMAC-SHA256 → AES-256-GCM). */
object PasswordCrypto {
    const val ITERATIONS = 310_000
    const val SALT_BYTES = 16

    private val random = SecureRandom()

    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also { random.nextBytes(it) }

    fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int = ITERATIONS): SecretKey {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password, salt, iterations, 256)
        val bytes = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    fun encryptCipher(key: SecretKey): Cipher {
        val iv = ByteArray(KeystoreCrypto.IV_BYTES).also { random.nextBytes(it) }
        return Cipher.getInstance(KeystoreCrypto.TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(KeystoreCrypto.TAG_BITS, iv))
        }
    }

    fun decryptCipher(key: SecretKey, iv: ByteArray): Cipher =
        Cipher.getInstance(KeystoreCrypto.TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(KeystoreCrypto.TAG_BITS, iv))
        }
}
