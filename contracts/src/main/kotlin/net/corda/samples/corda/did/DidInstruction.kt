package net.corda.samples.corda.did

import com.grack.nanojson.JsonObject
import com.natpryce.Failure
import com.natpryce.Result
import com.natpryce.Success
import com.natpryce.flatMap
import com.natpryce.map
import com.natpryce.mapFailure
import com.natpryce.onFailure
import net.corda.core.serialization.CordaSerializable
import net.corda.samples.corda.*
import net.corda.samples.corda.did.Action.Create
import net.corda.samples.corda.did.Action.Delete
import net.corda.samples.corda.did.Action.Read
import net.corda.samples.corda.did.Action.Update
import net.corda.samples.corda.did.DidInstructionFailure.InvalidDidFailure
import net.corda.samples.corda.did.DidInstructionFailure.InvalidInstructionJsonFailure
import net.corda.samples.corda.did.DidInstructionFailure.UnknownActionFailure

/**
 * This encapsulates the instruction string, outlining which action should be performed with the DID document provided
 * along with cryptographic proof of ownership of the DID document in form of a signature.
 *
 * @param json string representation of instruction JSON object.
 */
@CordaSerializable
class DidInstruction(json: String) : JsonBacked(json) {

	/**
	 * Returns the action
	 * @return [DidInstructionResult]
	 */
	fun action(): DidInstructionResult<Action> = json.getMandatoryString("action").mapFailure {
		InvalidInstructionJsonFailure(it)
	}.flatMap {
		it.toAction()
	}

	/**
	 * Returns a set of signatures that use a well-known [CryptoSuite]. Throws an exception if a signature with an
	 * unknown crypto suite is detected.
	 *
	 * @return [DidInstructionResult]
	 */
	fun signatures(): DidInstructionResult<Set<QualifiedSignature>> = json.getMandatoryArray("signatures").mapFailure {
		InvalidInstructionJsonFailure(it)
	}.map { signatures ->
		signatures.filterIsInstance(JsonObject::class.java).map { signature ->
			val suite = signature.getMandatoryCryptoSuiteFromSignatureID("type").mapFailure {
				InvalidInstructionJsonFailure(it)
			}.onFailure { return it }

			val id = signature.getMandatoryUri("id").mapFailure {
				InvalidInstructionJsonFailure(it)
			}.onFailure { return it }
			val listOfEncodings = arrayOf(
				SignatureEncoding.SignatureBase58,
				SignatureEncoding.SignatureMultibase,
				SignatureEncoding.SignatureBase64,
				SignatureEncoding.SignatureHex
			)
			val encodingUsed = listOfEncodings.filter { signature.has(it.encodingId) }.singleOrNull()

			val value = signature.getMandatoryEncoding(encodingUsed?.encodingId).mapFailure {
				InvalidInstructionJsonFailure(it)
			}.onFailure { return it }

			QualifiedSignature(suite, id, value)
		}.toSet()
	}
}

/**
 * Enum to represent DID operations as specified in the w3 specification.
 *
 * Ref: https://w3c-ccg.github.io/did-spec/#did-operations
 * @property[Read] Specify Read action.
 * @property[Create] Specify Create action.
 * @property[Update] Specify Update action.
 * @property[Delete] Specify Delete action.
 */
enum class Action {
	Read,
	Create,
	Update,
	Delete
}

/**
 * @receiver [String]
 * @return [DidInstructionResult]
 */
private fun String.toAction(): DidInstructionResult<Action> = when (this) {
	"read"   -> Success(Read)
	"create" -> Success(Create)
	"update" -> Success(Update)
	"delete" -> Success(Delete)
	else     -> Failure(UnknownActionFailure(this))
}

@Suppress("UNUSED_PARAMETER", "unused")
/**
 * Used to specify failures in instruction json.
 *
 * @property[InvalidInstructionJsonFailure] Instruction malformed
 * @property[InvalidDidFailure] Invalid DID
 * @property[UnknownActionFailure] Invalid action
 * */
sealed class DidInstructionFailure : FailureCode() {
	class InvalidInstructionJsonFailure(val underlying: JsonFailure) : DidInstructionFailure()
	class InvalidDidFailure(underlying: CordaDidFailure) : DidInstructionFailure()
	class UnknownActionFailure(val action: String) : DidInstructionFailure()
}

private typealias DidInstructionResult<T> = Result<T, DidInstructionFailure>
