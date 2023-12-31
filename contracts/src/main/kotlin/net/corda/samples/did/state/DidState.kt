package net.corda.samples.did.state

import com.natpryce.onFailure
import net.corda.samples.did.contract.DidContract
import com.persistent.did.state.DidStateSchemaV1
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState
import net.corda.core.serialization.CordaSerializable
import net.corda.samples.corda.did.CordaDid
import net.corda.samples.corda.did.DidEnvelope

/**
 * @property envelope The DidEnvelope object.
 * @property originator The Corda node (also a trusted anchor node in DID context)
 * @property witnesses Set of witness nodes who will be replicating the did.
 * @property status Status to identify the state of a did.
 * @property linearId equal to the [CordaDid.uuid].
 * @property participants Set of participants nodes who will be replicating the [DidState]
 */
@BelongsToContract(DidContract::class)
data class DidState(
	val envelope: DidEnvelope,
	val originator: Party,
	val witnesses: Set<Party>,
	val status: DidStatus,
	override val linearId: UniqueIdentifier,
	override val participants: List<AbstractParty> = (witnesses + originator).toList()
) : LinearState, QueryableState {

	/**
	 * @param schema [MappedSchema] object
	 */
	override fun generateMappedObject(schema: MappedSchema): PersistentState {

		val did = envelope.document.id().onFailure { throw IllegalArgumentException("Invalid did format") }
		return when (schema) {
			is DidStateSchemaV1 -> DidStateSchemaV1.PersistentDidState(
				originator = originator,
				didExternalForm = did.toExternalForm(),
				status = status,
				linearId = linearId.id
			)
			else                -> throw IllegalArgumentException("Unrecognised schema $schema")
		}
	}

	override fun supportedSchemas() = listOf(DidStateSchemaV1)

	fun isActive() = status == DidStatus.ACTIVE
}

/**
 * Enum to represent the status of [DidState]
 */
@CordaSerializable
enum class DidStatus {
	ACTIVE
}
