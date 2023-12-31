package net.corda.samples.example.webserver

import com.natpryce.onFailure
import com.persistent.did.api.*
import net.corda.samples.example.webserver.MainController.Companion.logger
import com.persistent.did.utils.DIDAlreadyExistException
import com.persistent.did.utils.DIDNotFoundException

import com.persistent.did.witness.flows.DeleteDidFlow
import net.corda.core.crypto.sign
import net.corda.core.messaging.startFlow
import net.corda.core.node.services.vault.builder
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.loggerFor
import net.corda.core.utilities.toBase58
import net.corda.samples.corda.did.CordaDid
import net.corda.samples.corda.did.CryptoSuite
import net.corda.samples.corda.did.DidInstruction
import net.corda.samples.witness.flows.CreateDidFlow
import net.corda.samples.witness.flows.UpdateDidFlow
import net.i2p.crypto.eddsa.KeyPairGenerator
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.MediaType.APPLICATION_JSON_VALUE
import org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.async.DeferredResult
import java.net.URI
import java.util.concurrent.Executors

/**
 *  A Spring Boot Server API controller for interacting with the node via RPC.
 */

@Suppress("unused")
@RestController
@RequestMapping("/")

/**
 * @property proxy The node RPC connection object instance
 * @property queryUtils Helper function used to query the ledger.
 * @property apiUtils Helper functions used to form API responses.
 * @property executorService Is used for creating new threads for tasks that are blocking.
 * @property logger Is used for logging
 * */
class MainController(rpc: NodeRPCConnection) {

	companion object {
		val logger = loggerFor<MainController>()

	}

	private val proxy = rpc.proxy
	private val queryUtils = QueryUtil(proxy)
	private val apiUtils = APIUtils()
	private val myLegalName = rpc.proxy.nodeInfo().legalIdentities.first().name

	private val executorService = Executors.newSingleThreadExecutor()

	/**
	 * Returns the node's name.
	 */
	@GetMapping(value = ["me"], produces = [MediaType.APPLICATION_JSON_VALUE])
	fun whoami() = mapOf("me" to myLegalName)

	/**
	 * Method to create a DID in the Corda ledger via REST
	 *
	 * @param[did] This is the unique decentralized identifier generated by the client application.
	 * @param[document] The raw DID document that contains the encoded public keys and their information.
	 * @param[instruction] Contains the encoded signature on the document as well as the action to be performed by the backend create,update,delete etc.
	 * @return A json response with a status code of 200 if create operation was a success,409 if conflict, 400 due to any other error in the sent parameters.
	 * */
	@PutMapping(value = ["{did}"], produces = [APPLICATION_JSON_VALUE], consumes = [MULTIPART_FORM_DATA_VALUE])
	fun createDID(
			@PathVariable(value = "did") did: String,
			@RequestPart("instruction") instruction: String,
			@RequestPart("document") document: String
	): DeferredResult<ResponseEntity<Any?>> {
		/**
		 * Creates an instance of Deferred Result, this will be used to send responses from tasks running in separate threads
		 *
		 * */
		val apiResult = DeferredResult<ResponseEntity<Any?>>()
		try {
			val keyPair1 = KeyPairGenerator().generateKeyPair()
			val encodedPubKey1 = keyPair1.public.encoded.toBase58()
			val document = """{
		|  "@context": "https://w3id.org/did/v1",
		|  "id": "${did}",
		|  "publicKey": [
		|	{
		|	  "id": "$did#keys-1",
		|	  "type": "${CryptoSuite.Ed25519.keyID}",
		|	  "controller": "${did}",
		|	  "publicKeyBase58": "$encodedPubKey1"
		|	}
		|  ]
		|}""".trimMargin()

			val signature1 = keyPair1.private.sign(document.toByteArray(Charsets.UTF_8))

			val encodedSignature1 = signature1.bytes.toBase58()

			val instruction = """{
		|  "action": "create",
		|  "signatures": [
		|	{
		|	  "id": "$did#keys-1",
		|	  "type": "Ed25519Signature2018",
		|	  "signatureBase58": "$encodedSignature1"
		|	}
		|  ]
		|}""".trimMargin()

			/**
			 * Takes instruction,document and did as input, validates them and returns an envelope Object.
			 *
			 * */
			val envelope = apiUtils.generateEnvelope(instruction, document, did, Action.CREATE.action)
			/**
			 *  Checks if the provided 'did' is in the correct format.
			 *
			 * */
			CordaDid.parseExternalForm(did).onFailure {
				apiResult.setErrorResult(ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse(APIMessage.INCORRECT_FORMAT).toResponseObj()))
				return apiResult
			}

			/**
			 * Checks to see if the generated envelope is correct for the creation use case, otherwise returns the appropriate error.
			 *
			 * */
			val envelopeVerified = envelope.validateCreation()
			envelopeVerified.onFailure {
				apiResult.setErrorResult(apiUtils.sendErrorResponse(it.reason))
				return apiResult
			}

			/**
			 * Passing the generated envelope as a parameter to the CreateDidFlow.
			 *
			 * Returns a flow handler
			 * */
			val flowHandler = proxy.startFlowDynamic(CreateDidFlow::class.java, envelope)

			/**
			 * Executes the flow in a separate thread and returns result.
			 *
			 * Throws exception if flow invocation fails
			 * */
			executorService.submit {
				try {
					val result = flowHandler.use { it.returnValue.getOrThrow() }
					apiResult.setResult(ResponseEntity.ok().body(ApiResponse(result.toString()).toResponseObj()))
				} catch (e: IllegalArgumentException) {
					apiResult.setErrorResult(ResponseEntity.badRequest().body(ApiResponse(e.message).toResponseObj()))
				} catch (e: DIDAlreadyExistException) {
					apiResult.setErrorResult(ResponseEntity(ApiResponse(APIMessage.CONFLICT).toResponseObj(), HttpStatus.CONFLICT))
				} catch (e: Exception) {
					logger.error(e.message)
					apiResult.setErrorResult(ResponseEntity.badRequest().body(ApiResponse(e.message).toResponseObj()))
				}
			}
			return apiResult
		} catch (e: Exception) {
			logger.error(e.message)
			apiResult.setErrorResult(ResponseEntity.badRequest().body(ApiResponse(e.message).toResponseObj()))
			return apiResult
		}
	}

	/**
	 * Method to fetch a DID from the Corda ledger via REST
	 *
	 * @param[did] This is the unique decentralized identifier generated by the client application.
	 * @return The raw DID document if found ,returns 404 if not found ,returns 400 error if incorrect format is passed.
	 * */
	@GetMapping("{did}", produces = [APPLICATION_JSON_VALUE])
	fun fetchDIDDocument(@PathVariable(value = "did") did: String): ResponseEntity<Any?> {
		try {
			/**
			 * Converts the "did" from external form to uuid form else returns an error
			 *
			 * */
			val uuid = CordaDid.parseExternalForm(did).onFailure {
				return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse(APIMessage.INCORRECT_FORMAT).toResponseObj())
			}

			builder {
				/**
				 * Query the ledger using the uuid and return raw document else return an error.
				 * */
				val didJson = queryUtils.getDIDDocumentByLinearId(uuid.uuid.toString())
				if (didJson.isNullOrEmpty()) {
					val response = ApiResponse(APIMessage.NOT_FOUND)
					return ResponseEntity(response.toResponseObj(), HttpStatus.NOT_FOUND)
				}
				return ResponseEntity.ok().body(didJson)
			}
		} catch (e: IllegalArgumentException) {
			logger.error(e.toString())
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse(APIMessage.INCORRECT_FORMAT).toResponseObj())
		} catch (e: IllegalStateException) {
			logger.error(e.toString())
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse(APIMessage.INCORRECT_FORMAT).toResponseObj())
		} catch (e: Exception) {
			logger.error(e.toString())
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse(e.message).toResponseObj())
		}
	}

	/**
	 * Method to update a DID in the Corda ledger via REST
	 *
	 * @param[did] This is the unique decentralized identifier generated by the client application.
	 * @param[document] The raw DID document that contains the encoded public keys and their information.
	 * @param[instruction] Contains the encoded signature on the document as well as the action to be performed by the backend create,update,delete etc.
	 * @return A json response with a status code of 200 if update operation was a success,404 if not found, 400 due to any other error in the sent parameters.
	 * */
	@PostMapping(value = ["{did}"], produces = [APPLICATION_JSON_VALUE], consumes = [MULTIPART_FORM_DATA_VALUE])
	fun updateDID(
			@PathVariable(value = "did") did: String,
			@RequestParam("instruction") instruction: String,
			@RequestParam("document") document: String
	): DeferredResult<ResponseEntity<Any?>> {
		/**
		 * Creates an instance of Deferred Result, this will be used to send responses from tasks running in separate threads
		 *
		 * */
		val apiResult = DeferredResult<ResponseEntity<Any?>>()
		try {
			/**
			 * Takes instruction,document and did as input, validates them and returns an envelope Object.
			 *
			 * */
			val envelope = apiUtils.generateEnvelope(instruction, document, did, Action.UPDATE.action)
			/**
			 * Converts the "did" from external form to uuid form else returns an error
			 *
			 * */
			CordaDid.parseExternalForm(did).onFailure {
				apiResult.setErrorResult(ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse(APIMessage.INCORRECT_FORMAT).toResponseObj()));return apiResult
			}

			/**
			 * Passing the generated envelope as a parameter to the UpdateDidFlow.
			 * Returns a flow handler
			 * */
			val flowHandler = proxy.startFlowDynamic(UpdateDidFlow::class.java, envelope)
			/**
			 * Executing the flow in a separate thread and return result.
			 * Throws exception if flow invocation fails
			 * */

			executorService.submit {
				try {
					val result = flowHandler.use { it.returnValue.getOrThrow() }
					apiResult.setResult(ResponseEntity.ok().body(ApiResponse(result.toString()).toResponseObj()))
				} catch (e: IllegalArgumentException) {

					apiResult.setErrorResult(ResponseEntity.badRequest().body(ApiResponse(e.message).toResponseObj()))

				} catch (e: DIDNotFoundException) {

					apiResult.setErrorResult(ResponseEntity(ApiResponse(APIMessage.NOT_FOUND).toResponseObj(), HttpStatus.NOT_FOUND))
				} catch (e: Exception) {
					logger.error(e.message)
					apiResult.setErrorResult(ResponseEntity.badRequest().body(ApiResponse(e.message).toResponseObj()))
				}
			}
			return apiResult
		} catch (e: Exception) {
			logger.error(e.message)
			apiResult.setErrorResult(ResponseEntity.badRequest().body(ApiResponse(e.message).toResponseObj()))
			return apiResult
		}
	}

	/**
	 * Method to delete a DID in the Corda ledger via REST
	 *
	 * @param[did] This is the unique decentralized identifier generated by the client application.
	 * @param[instruction] Contains the encoded signature on the latest DID document as well as the action to be performed by the backend (delete).
	 * @return A json response with a status code of 200 if delete operation was a success,404 if not found , 400 due to any other error in the sent parameters.
	 * */
	@DeleteMapping(value = ["{did}"], produces = [APPLICATION_JSON_VALUE], consumes = [MULTIPART_FORM_DATA_VALUE])
	fun deleteDID(
			@PathVariable(value = "did") did: String,
			@RequestPart("instruction") instruction: String
	): DeferredResult<ResponseEntity<Any?>> {
		val apiResult = DeferredResult<ResponseEntity<Any?>>()
		try {
			/** validating if instruction and did are in the correct format**/
			CordaDid.parseExternalForm(did).onFailure {
				apiResult.setErrorResult(ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse(APIMessage.INCORRECT_FORMAT).toResponseObj()));return apiResult
			}
			val didInstruction = DidInstruction(instruction)
			if (didInstruction.json["action"] != Action.DELETE.action) {
				apiResult.setErrorResult(ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse(APIMessage.INCORRECT_ACTION).toResponseObj()))
				return apiResult
			}
			/**
			 * Passing the instruction and did to the DeleteDidFlow
			 *
			 * */
			val flowHandler = proxy.startFlow(::DeleteDidFlow, instruction, did)
			/**
			 * Executing the flow in a separate thread and return result.
			 *
			 * Throws exception if flow invocation fails
			 * */
			executorService.submit {
				try {
					val result = flowHandler.use { it.returnValue.getOrThrow() }
					apiResult.setResult(ResponseEntity.ok().body(ApiResponse(result.toString()).toResponseObj()))
				} catch (e: IllegalArgumentException) {
					apiResult.setErrorResult(ResponseEntity.badRequest().body(ApiResponse(e.message).toResponseObj()))
				} catch (e: DIDNotFoundException) {
					apiResult.setErrorResult(ResponseEntity(ApiResponse(APIMessage.NOT_FOUND).toResponseObj(), HttpStatus.NOT_FOUND))
				} catch (e: Exception) {
					logger.error(e.message)
					apiResult.setErrorResult(ResponseEntity.badRequest().body(ApiResponse(e.message).toResponseObj()))
				}
			}
			return apiResult
		} catch (e: Exception) {
			logger.error(e.message)
			apiResult.setErrorResult(ResponseEntity.badRequest().body(ApiResponse(e.message).toResponseObj()))
			return apiResult
		}
	}
}
