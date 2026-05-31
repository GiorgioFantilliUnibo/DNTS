package domain.authentication

import java.security.MessageDigest

/**
 * Provides cryptographic utility functions for hashing and secure string comparison.
 * This object is primarily used to securely process and verify sensitive data, such as
 * passwords or user tokens.
 */
object Crypto:

  /**
   * The underlying hashing algorithm used for cryptographic operations.
   */
  private val HashAlgorithm = "SHA-256"

  /**
   * Computes the SHA-256 cryptographic hash of a given plain-text string.
   *
   * @param plaintext The plain-text string to be hashed (e.g. a user's password).
   * @return A hexadecimal string representation of the computed hash.
   */
  def sha256(plaintext: String): String =
    val digest = MessageDigest.getInstance(HashAlgorithm)
    digest.digest(plaintext.getBytes("UTF-8")).map("%02x".format(_)).mkString

  /**
   * Securely compares two strings for equality in constant time.
   * Using this method instead of a standard string comparison prevents timing attacks
   * that could otherwise be used to infer the contents of a hash or secret token.
   *
   * @param a The first string to compare.
   * @param b The second string to compare.
   * @return `true` if the underlying byte arrays are equal, `false` otherwise.
   */
  def safeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.getBytes("UTF-8"), b.getBytes("UTF-8"))
