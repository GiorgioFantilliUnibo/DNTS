package domain.authentication

import java.security.MessageDigest

object Crypto:

  private val HashAlgorithm = "SHA-256"

  def sha256(plaintext: String): String =
    val digest = MessageDigest.getInstance(HashAlgorithm)
    digest.digest(plaintext.getBytes("UTF-8")).map("%02x".format(_)).mkString

  def safeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.getBytes("UTF-8"), b.getBytes("UTF-8"))
