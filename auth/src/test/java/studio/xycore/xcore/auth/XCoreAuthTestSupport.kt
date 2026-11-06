package studio.xycore.xcore.auth

import studio.xycore.xcore.core.XCoreAuthStage
import studio.xycore.xcore.core.XCoreError
import studio.xycore.xcore.core.XCoreResult
import studio.xycore.xcore.core.asXCoreFailure
import studio.xycore.xcore.core.asXCoreSuccess

/**
 * Ends a test with a message, as a value the compiler knows cannot be used.
 *
 * `org.junit.Assert.fail` returns `void`, so `answer ?: fail("...")` types as `Any` and every field
 * access after it fails to resolve. This is the same assertion with a `Nothing` return.
 */
internal fun failTest(message: String): Nothing = throw AssertionError(message)

/**
 * Canned answers for the Microsoft and Mojang endpoints, keyed by a fragment of the URL.
 *
 * The whole point of this file is that a test can drive the real pipeline - device code, XBL, XSTS,
 * Minecraft Services, profile, entitlements - without a socket, so the parsing and the refusal rules
 * are the production ones. Nothing here returns a success the production code did not compute.
 */
class ScriptedAuthTransport : XCoreAuthTransport {

    data class RecordedRequest(val method: String, val url: String, val body: String, val bearer: String?)

    val requests = ArrayList<RecordedRequest>()

    private val queues = LinkedHashMap<String, ArrayDeque<XCoreAuthResponse>>()

    fun then(urlContains: String, response: XCoreAuthResponse): ScriptedAuthTransport = apply {
        queues.getOrPut(urlContains) { ArrayDeque() }.addLast(response)
    }

    fun thenJson(urlContains: String, status: Int, json: String): ScriptedAuthTransport =
        then(urlContains, XCoreAuthResponse(status = status, body = json))

    private fun take(method: String, url: String, body: String, bearer: String?): XCoreResult<XCoreAuthResponse> {
        requests += RecordedRequest(method, url, body, bearer)
        val armed = queues.entries.firstOrNull { (match, pending) -> pending.isNotEmpty() && url.contains(match) }
            ?: return XCoreError.Network(
                detail = "the script has no reply left for $method $url",
                url = url,
                retryable = false,
            ).asXCoreFailure()
        val answer = armed.value.removeFirst()
        if (armed.value.isEmpty()) queues.remove(armed.key)
        return answer.asXCoreSuccess()
    }

    override suspend fun postForm(
        url: String,
        fields: Map<String, String>,
        bearer: String?,
    ): XCoreResult<XCoreAuthResponse> =
        take("POST", url, fields.entries.joinToString("&") { "${it.key}=${it.value}" }, bearer)

    override suspend fun postJson(
        url: String,
        payload: String,
        bearer: String?,
    ): XCoreResult<XCoreAuthResponse> = take("POST", url, payload, bearer)

    override suspend fun get(url: String, bearer: String?): XCoreResult<XCoreAuthResponse> =
        take("GET", url, "", bearer)

    /** The value the flow sent for one form field, so a test can assert what left the device. */
    fun formField(urlContains: String, field: String): String? = requests
        .firstOrNull { it.url.contains(urlContains) }
        ?.body
        ?.split("&")
        ?.firstOrNull { it.startsWith("$field=") }
        ?.substringAfter("=")

    fun sentTo(urlFragment: String): List<RecordedRequest> = requests.filter { it.url.contains(urlFragment) }

    /** True when no request in this script carried a password of any kind. */
    fun anyRequestCarriesPassword(): Boolean =
        requests.any { it.body.contains("password", ignoreCase = true) }
}

/**
 * Not a security control.
 *
 * The JVM these tests run on has no Android Keystore, so the vault's format, its corruption handling
 * and its refuse-to-read-foreign-bytes rule are exercised against a cipher that marks its output
 * instead of protecting it. The keystore-backed cipher is the production one, and it is the only
 * cipher any production caller is given.
 */
class MarkingTestCipher : XCoreSecretCipher {

    override val description: String get() = "test cipher (marks bytes, does not protect them)"

    override fun encrypt(plaintext: ByteArray): XCoreResult<ByteArray> =
        (MARKER + plaintext).asXCoreSuccess()

    override fun decrypt(envelope: ByteArray): XCoreResult<ByteArray> {
        if (envelope.size < MARKER.size ||
            !envelope.copyOfRange(0, MARKER.size).contentEquals(MARKER)
        ) {
            return XCoreError.Authentication(
                detail = "the test cipher was handed bytes it did not mark",
                stage = XCoreAuthStage.TOKEN_STORAGE,
            ).asXCoreFailure()
        }
        return envelope.copyOfRange(MARKER.size, envelope.size).asXCoreSuccess()
    }

    companion object {
        private val MARKER = "MARKED:".toByteArray(Charsets.US_ASCII)
    }
}

/** The endpoint documents, written in the shapes the services publish. */
object AuthScript {
    const val CLIENT_ID = "8e0f5c8a-2b1d-4a6e-9c3f-70d1a2b3c4d5"
    const val DEVICE_URL = "devicecode"
    const val TOKEN_URL = "v2.0/token"
    const val XBL_URL = "xboxlive.auth.xboxlive.com"
    const val XSTS_URL = "xsts.auth.xboxlive.com"
    const val MC_LOGIN_URL = "login_with_xbox"
    const val PROFILE_URL = "minecraft/profile"
    const val ENTITLEMENTS_URL = "entitlements/minecraft"

    const val NOW_MILLIS = 1_780_000_000_000L
    const val MS_ACCESS = "ms-access-token"
    const val MS_REFRESH = "ms-refresh-token"
    const val GAME_TOKEN = "mc-services-token"
    const val PROFILE_JSON = """{"id":"8f5e5a0b-1c2d-3e4f-5a6b-7c8d9e0f1a2b","name":"Badlion"}"""
    const val UUID_COMPACT = "8f5e5a0b1c2d3e4f5a6b7c8d9e0f1a2b"

    val CONFIGURED = XCoreAuthConfig(clientId = CLIENT_ID)

    val DEVICE_CODE = """
        {"device_code":"device-code-secret","user_code":"ABC-123","verification_uri":"https://microsoft.com/devicelogin","expires_in":900,"interval":5,"message":"To sign in, use a web browser to open the page"}
    """.trimIndent()

    val TOKENS = """
        {"token_type":"Bearer","expires_in":3600,"scope":"XboxLive.signin offline_access","access_token":"$MS_ACCESS","refresh_token":"$MS_REFRESH"}
    """.trimIndent()

    fun xbl(token: String) = """{"Token":"$token","DisplayClaims":{"xui":[{"uhs":"233:abcdef","xid":12345}]}}"""

    val MC_LOGIN = """{"username":"Badlion","accessToken":"$GAME_TOKEN","tokenType":"Bearer","expiresIn":86400}"""

    val ENTITLEMENTS_JAVA = """{"items":[{"skuName":"minecraft","displayName":"Minecraft Java Edition","productTitle":"","contentTrees":[{"name":"minecraft.java"}]}]}"""

    val ENTITLEMENTS_BEDROCK_ONLY = """{"items":[{"skuName":"minecraftforwindows","displayName":"Minecraft for Windows","productTitle":"Minecraft for Windows 10 Edition","contentTrees":[{"name":"bedrock"}]}]}"""

    fun oauthError(status: Int, code: String, description: String = code) =
        """{"error":"$code","error_description":"$description"}""".let { XCoreAuthResponse(status = status, body = it) }

    /** Every answer the happy path needs, in the order the flow asks for them. */
    fun happyPath(
        entitlements: String = ENTITLEMENTS_JAVA,
        deviceCode: String = DEVICE_CODE,
        tokens: String = TOKENS,
    ): ScriptedAuthTransport = ScriptedAuthTransport()
        .thenJson(DEVICE_URL, 200, deviceCode)
        .thenJson(TOKEN_URL, 200, tokens)
        .thenJson(XBL_URL, 200, xbl("xbl-token"))
        .thenJson(XSTS_URL, 200, xbl("xsts-token"))
        .thenJson(MC_LOGIN_URL, 200, MC_LOGIN)
        .thenJson(PROFILE_URL, 200, PROFILE_JSON)
        .thenJson(ENTITLEMENTS_URL, 200, entitlements)
}
