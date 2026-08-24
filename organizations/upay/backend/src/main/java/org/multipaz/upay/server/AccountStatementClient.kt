package org.multipaz.upay.server

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Configuration
import org.multipaz.server.common.enrollmentServerUrl

/**
 * Reads account statements from the registry (the System of Record).
 *
 * The only unit in UPay that knows the registry's wire format, so a change to it lands here alone.
 */
internal object AccountStatementClient {
    /**
     * Fetches the balance and transaction list for an account.
     *
     * @param accountNumber the account to read, which must have come from an issuer-signed claim
     *   — never from front-end input
     * @return the registry's statement: `{balance, holder_id, holder_name, transactions}`, with
     *   transactions already ordered newest-first by the registry
     * @throws IllegalStateException if the registry URL is not configured, the registry refuses
     *   the read (for instance because the account does not exist), or it answers with something
     *   other than a JSON object
     */
    suspend fun fetchStatement(accountNumber: String): JsonObject {
        val configuration = BackendEnvironment.getInterface(Configuration::class)!!
        // Prefer `registry_url`: enrollment_server_url is the public base URL, so using it would
        // route this server-to-server read out through the reverse proxy and back.
        val registryUrl = configuration.getValue(REGISTRY_URL) ?: configuration.enrollmentServerUrl
            ?: throw IllegalStateException("neither '$REGISTRY_URL' nor 'enrollment_server_url' is configured")
        val httpClient = BackendEnvironment.getInterface(HttpClient::class)!!
        val url = "$registryUrl/payment/account/${accountNumber.encodeURLPathPart()}"
        val response = httpClient.get(url)
        if (response.status != HttpStatusCode.OK) {
            throw IllegalStateException("Registry answered ${response.status} for an account statement")
        }
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    /** Registry address reachable without leaving the deployment. Set by `start-servers.sh`. */
    private const val REGISTRY_URL = "registry_url"
}
