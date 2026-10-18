package com.google.ai.edge.gallery.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [ALTREX] Encrypts the secrets Altrex keeps (Hugging Face token, MCP server headers, skill
 * secrets) with an AES-GCM key held by the Android Keystore. The key never leaves the phone's
 * secure hardware: a copy of the app data (backup, another phone) cannot be read.
 *
 * Values written before this change are plain text; they are still read, and encrypted the next
 * time they are saved.
 */
object SecretCipher {
  private const val TAG = "AGSecretCipher"
  private const val PREFIX = "enc1:"
  private const val KEY_ALIAS = "altrex_secrets_v1"
  private const val IV_BYTES = 12
  private const val TAG_BITS = 128

  /** The encryption itself; replaced in unit tests, where the Android Keystore does not exist. */
  interface Engine {
    fun encrypt(plain: ByteArray): ByteArray

    fun decrypt(data: ByteArray): ByteArray
  }

  @Volatile var engine: Engine = KeystoreEngine

  /** Encrypted form of [plain] (empty stays empty). */
  fun encrypt(plain: String): String {
    if (plain.isEmpty() || plain.startsWith(PREFIX)) return plain
    return try {
      PREFIX + Base64.getEncoder().encodeToString(engine.encrypt(plain.toByteArray(Charsets.UTF_8)))
    } catch (e: Exception) {
      // Without a working Keystore the secret is kept as before rather than lost.
      Log.e(TAG, "Encryption failed, secret kept unencrypted", e)
      plain
    }
  }

  /**
   * Plain form of [stored]. A value that cannot be decrypted (data restored on another phone,
   * Keystore reset) reads as empty: the user enters the secret again.
   */
  fun decrypt(stored: String): String {
    if (!stored.startsWith(PREFIX)) return stored
    return try {
      String(engine.decrypt(Base64.getDecoder().decode(stored.removePrefix(PREFIX))), Charsets.UTF_8)
    } catch (e: Exception) {
      Log.e(TAG, "Cannot decrypt a stored secret", e)
      ""
    }
  }

  fun isEncrypted(stored: String): Boolean = stored.startsWith(PREFIX)

  private object KeystoreEngine : Engine {
    private fun key(): SecretKey {
      val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
      (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
      val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
      generator.init(
        KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
          .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
          .setKeySize(256)
          .build()
      )
      return generator.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.ENCRYPT_MODE, key())
      return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(data: ByteArray): ByteArray {
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES))
      return cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES)
    }
  }
}
