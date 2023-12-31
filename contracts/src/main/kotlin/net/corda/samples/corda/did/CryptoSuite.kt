package net.corda.samples.corda.did

import com.natpryce.Failure
import com.natpryce.Result
import com.natpryce.Success
import net.corda.samples.corda.FailureCode
import net.corda.samples.corda.did.CryptoSuiteFailure.UnknownCryptoSuiteIDFailure

/**
 * Supported Crypto Suites as registered in the "Linked Data Cryptographic Suite Registry" Draft Community Group Report
 * 09 December 2018
 *
 * https://w3c-ccg.github.io/ld-cryptosuite-registry/
 *
 * @param keyID The external identifier for a key of this crypto suite as per the community report.
 *
 * @param signatureID The external identifier for a signature created by a private key of this crypto suite as per the
 * community report.
 */
@Suppress("MemberVisibilityCanBePrivate")
/**
 * @property keyID Verification algorithm
 * @property signatureID Signature algorithm.
 * */
enum class CryptoSuite(
		val keyID: String,
		val signatureID: String
) {
	Ed25519("Ed25519VerificationKey2018", "Ed25519Signature2018"),
	RSA("RsaVerificationKey2018", "RsaSignature2018"),
	EcdsaSecp256k1("EcdsaVerificationKeySecp256k1", "EcdsaSignatureSecp256k1");

	companion object {
		/** Identify suite using Signature Id*/
		fun fromSignatureID(signatureID: String): Result<CryptoSuite, CryptoSuiteFailure> = values().firstOrNull {
			it.signatureID == signatureID
		}?.let {
			Success(it)
		} ?: Failure(UnknownCryptoSuiteIDFailure(signatureID))

		/** Identify suite using Key Id*/
		fun fromKeyID(keyID: String): Result<CryptoSuite, CryptoSuiteFailure> = values().firstOrNull {
			it.keyID == keyID
		}?.let {
			Success(it)
		} ?: Failure(UnknownCryptoSuiteIDFailure(keyID))
	}
}

@Suppress("UNUSED_PARAMETER")
/**
 * @property[UnknownCryptoSuiteIDFailure] Used to identify if unknown crypto suite id is used.
 * */
sealed class CryptoSuiteFailure : FailureCode() {
	class UnknownCryptoSuiteIDFailure(id: String) : CryptoSuiteFailure()
}
