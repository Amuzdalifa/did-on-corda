package net.corda.samples.witness.flows

import co.paralleluniverse.fibers.Suspendable
import com.natpryce.onFailure
import net.corda.samples.did.contract.DidContract
import net.corda.samples.did.state.DidState
import com.persistent.did.utils.DIDNotFoundException
import com.persistent.did.utils.InvalidDIDException
import com.persistent.did.utils.getNotaryFromConfig
import com.persistent.did.utils.loadState
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.ReceiveFinalityFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.samples.corda.did.DidEnvelope

/**
 * Initiating flow to UPDATE a DID on ledger as specified in the w3 specification.
 * Ref: https://w3c-ccg.github.io/did-spec/#update
 *
 * @property envelope the [DidEnvelope] object
 * @property ProgressTracker tracks the progress in the various stages of transaction
 */
@InitiatingFlow
@StartableByRPC

class UpdateDidFlow(val envelope: DidEnvelope) : FlowLogic<SignedTransaction>() {

	@Suppress("ClassName")
	companion object {
		object GENERATING_TRANSACTION : ProgressTracker.Step("Generating transaction based on new DidState.")
		object VERIFYING_TRANSACTION : ProgressTracker.Step("Verifying contract constraints.")
		object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with our private key.")
		object FINALISING_TRANSACTION : ProgressTracker.Step("Obtaining notary signature and recording transaction.") {
			override fun childProgressTracker() = FinalityFlow.tracker()
		}

		fun tracker() = ProgressTracker(
				GENERATING_TRANSACTION,
				VERIFYING_TRANSACTION,
				SIGNING_TRANSACTION,
				FINALISING_TRANSACTION
		)
	}

	override val progressTracker = tracker()

	/**
	 * The flow logic is encapsulated within the call() method.
	 */
	@Suspendable
	override fun call(): SignedTransaction {

		// query the ledger if did exist or not
		val did = envelope.document.id().onFailure { throw InvalidDIDException("Invalid DID passed") }

		val didStates: List<StateAndRef<DidState>> = serviceHub.loadState(UniqueIdentifier(null, did.uuid), DidState::class.java)

		val inputDidState = didStates.let {
			if (it.size != 1) throw DIDNotFoundException("DID with id $did does not exist")
			else it.single()
		}

		// Obtain a reference to the notary we want to use.

		val notary = serviceHub.getNotaryFromConfig()

		// Stage 1.
		progressTracker.currentStep = GENERATING_TRANSACTION

		val outputDidState = inputDidState.state.data.copy(envelope = envelope)
		// Generate an unsigned transaction.
		val txCommand = Command(DidContract.Commands.Update(), listOf(ourIdentity.owningKey))
		val txBuilder = TransactionBuilder(notary)
				.addInputState(inputDidState)
				.addOutputState(outputDidState, DidContract.DID_CONTRACT_ID)
				.addCommand(txCommand)

		// Stage 2.
		progressTracker.currentStep = VERIFYING_TRANSACTION
		// Verify that the transaction is valid.
		txBuilder.verify(serviceHub)

		// Stage 3.
		progressTracker.currentStep = SIGNING_TRANSACTION
		// Sign the transaction.
		val signedTx = serviceHub.signInitialTransaction(txBuilder)

		// Stage 4.
		progressTracker.currentStep = FINALISING_TRANSACTION

		val otherPartySession = inputDidState.state.data.witnesses.map { initiateFlow(it) }.toSet()
		// Notarise and record the transaction in witness parties' vaults.
		return subFlow(FinalityFlow(signedTx, otherPartySession, FINALISING_TRANSACTION.childProgressTracker()))
	}
}

/**
 * Receiver finality flow for [UpdateDidFlow]
 * @property[otherPartySession] FlowSession
 *
 */
@InitiatedBy(UpdateDidFlow::class)
class UpdateDidFinalityFlowResponder(private val otherPartySession: FlowSession) : FlowLogic<Unit>() {
	@Suspendable
	override fun call() {
		subFlow(ReceiveFinalityFlow(otherPartySession))
	}
}

