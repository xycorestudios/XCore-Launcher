package studio.xycore.xcore.auth

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.xycore.xcore.core.XCoreAuthStage
import studio.xycore.xcore.core.XCoreError
import studio.xycore.xcore.core.XCoreErrorKind
import studio.xycore.xcore.core.XCoreFailureException
import studio.xycore.xcore.core.XCoreResult
import studio.xycore.xcore.core.errorOrNull
import studio.xycore.xcore.core.isSuccess
import studio.xycore.xcore.core.valueOrNull

/**
 * What the Microsoft flow sends, and what it accepts back.
 *
 * Every case here is a failure the protocol really produces - a code that was never approved, a
 * refresh token that was revoked, an endpoint that has been retired - because the brief's testable
 * claim is not "sign-in works" (that needs a real app registration and a real account) but
 * "sign-in never reports success when it did not get one".
 */
class XCoreMicrosoftAuthTest {

    private fun auth(
        transport: ScriptedAuthTransport,
        config: XCoreAuthConfig = AuthScript.CONFIGURED,
    ) = XCoreMicrosoftAuth(config = config, transport = transport, clock = { AuthScript.NOW_MILLIS })

    private fun failure(result: XCoreResult<*>): XCoreError =
        result.errorOrNull() ?: failTest("expected a typed failure, got ${result::class.simpleName}")

    private fun authFailure(result: XCoreResult<*>): XCoreError.Authentication =
        failure(result) as? XCoreError.Authentication
            ?: failTest("expected an authentication failure, got ${failure(result)}")

    private val challenge = XCoreDeviceCodeChallenge(
        clientId = AuthScript.CLIENT_ID,
        deviceCode = "device-code-secret",
        userCode = "ABC-123",
        verificationUri = "https://microsoft.com/devicelogin",
        instructions = "open the page",
        intervalSeconds = 5,
        expiresAtMillis = AuthScript.NOW_MILLIS + 900_000L,
    )

    // ------------------------------------------------------------- the app registration

    @Test
    fun abuildWithoutAnAppRegistrationRefusesToStartSignIn() = runBlocking {
        val transport = ScriptedAuthTransport()
        val outcome = auth(transport, XCoreAuthConfig(clientId = ""))
            .requestDeviceCode()

        val problem = failure(outcome)
        assertEquals(XCoreErrorKind.CONFIGURATION, problem.kind)
        assertTrue(
            "the person has to be told where the registration comes from",
            problem.detail.contains("auth/README.md"),
        )
        assertEquals("no request may leave the device without a registration", 0, transport.requests.size)
    }

    @Test
    fun aShortOrInventedClientIdIsNotAcceptedAsConfigured() {
        assertFalse(XCoreAuthConfig(clientId = "xcore-launcher").isConfigured)
        assertFalse(XCoreAuthConfig(clientId = "x".repeat(31)).isConfigured)
        assertFalse(XCoreAuthConfig(clientId = "x".repeat(38)).isConfigured)
        assertTrue(AuthScript.CONFIGURED.isConfigured)
    }

    @Test
    fun theDeviceCodeRequestCarriesNoPasswordAndAsksForOfflineAccess() = runBlocking {
        val transport = ScriptedAuthTransport().thenJson(AuthScript.DEVICE_URL, 200, AuthScript.DEVICE_CODE)

        val issued = auth(transport).requestDeviceCode().valueOrNull() ?: failTest("device code was not issued")

        assertEquals("ABC-123", issued.userCode)
        assertEquals("device-code-secret", issued.deviceCode)
        assertEquals(AuthScript.NOW_MILLIS + 900_000L, issued.expiresAtMillis)
        assertEquals("XboxLive.signin offline_access", transport.formField(AuthScript.DEVICE_URL, "scope"))
        assertEquals(AuthScript.CLIENT_ID, transport.formField(AuthScript.DEVICE_URL, "client_id"))
        assertFalse("the device-code grant must never carry a password", transport.anyRequestCarriesPassword())
        assertFalse(
            "the prompt has no place for a secret and the log has no reason to print one",
            issued.prompt().toString().contains("device-code-secret"),
        )
    }

    @Test
    fun anUnrecognisedVerificationAddressIsRefusedRatherThanShown() = runBlocking {
        val lookAlike = """{"device_code":"d","user_code":"ABC-123","verification_uri":"https://login.mlcrosoft.com/devicelogin","expires_in":900,"interval":5}"""
        val transport = ScriptedAuthTransport().thenJson(AuthScript.DEVICE_URL, 200, lookAlike)

        val problem = authFailure(auth(transport).requestDeviceCode())

        assertEquals(XCoreAuthStage.DISCOVERY, problem.stage)
        assertTrue(problem.detail.contains("login.mlcrosoft.com"))
        assertFalse(XCoreAuthEndpoints.isOpenable("https://login.mlcrosoft.com/devicelogin"))
    }

    @Test
    fun aMissingDeviceCodeInTheAnswerIsAFailureNotAnEmptyChallenge() = runBlocking {
        val transport = ScriptedAuthTransport().thenJson(AuthScript.DEVICE_URL, 200, """{"user_code":"ABC-123","verification_uri":"https://microsoft.com/devicelogin"}""")

        val problem = authFailure(auth(transport).requestDeviceCode())

        assertEquals("Microsoft", problem.detail.substringBefore(" returned"))
        assertTrue(problem.detail.contains("device_code"))
    }

    // ------------------------------------------------------------- polling one answer

    @Test
    fun pendingAndSlowDownAreWaitingInstructionsRatherThanOutcomes() = runBlocking {
        val pending = ScriptedAuthTransport().then(AuthScript.TOKEN_URL, AuthScript.oauthError(400, "authorization_pending"))
        val slowed = ScriptedAuthTransport().then(AuthScript.TOKEN_URL, AuthScript.oauthError(400, "slow_down"))

        val first = auth(pending).pollDeviceCode(challenge).valueOrNull()
        val second = auth(slowed).pollDeviceCode(challenge).valueOrNull()

        assertEquals(XCoreDevicePoll.Pending(XCoreMicrosoftAuth.DEFAULT_POLL_SECONDS), first)
        assertEquals(XCoreDevicePoll.Pending(XCoreMicrosoftAuth.SLOW_DOWN_SECONDS), second)
        assertEquals(
            XCoreMicrosoftAuth.DEVICE_GRANT,
            pending.formField(AuthScript.TOKEN_URL, "grant_type"),
        )
        assertEquals("device-code-secret", pending.formField(AuthScript.TOKEN_URL, "device_code"))
    }

    @Test
    fun eachRefusalKeepsTheReasonMicrosoftGave() = runBlocking {
        val cases = listOf(
            "expired_token" to XCoreDeviceRefusal.EXPIRED,
            "access_denied" to XCoreDeviceRefusal.DECLINED,
            "bad_token" to XCoreDeviceRefusal.ALREADY_REDEEMED,
            "interaction_required" to XCoreDeviceRefusal.AUTHORISATION_REQUIRED,
            "invalid_grant" to XCoreDeviceRefusal.UNKNOWN,
        )
        for ((code, expected) in cases) {
            val transport = ScriptedAuthTransport().then(AuthScript.TOKEN_URL, AuthScript.oauthError(400, code))

            val poll = auth(transport).pollDeviceCode(challenge).valueOrNull()

            val refused = poll as? XCoreDevicePoll.Refused
                ?: failTest("expected '$code' to be a refusal, got $poll")
            assertEquals("refusal reason for $code", expected, refused.reason)
            assertEquals(code, refused.detail)
        }
    }

    @Test
    fun anApprovedCodeProducesTokensAndNotASuccessFlag() = runBlocking {
        val transport = ScriptedAuthTransport().thenJson(AuthScript.TOKEN_URL, 200, AuthScript.TOKENS)

        val poll = auth(transport).pollDeviceCode(challenge).valueOrNull()

        val authorized = poll as? XCoreDevicePoll.Authorized ?: failTest("expected an authorized poll, got $poll")
        assertEquals(AuthScript.MS_ACCESS, authorized.tokens.accessToken)
        assertEquals(AuthScript.MS_REFRESH, authorized.tokens.refreshToken)
        assertEquals(AuthScript.NOW_MILLIS + 3_600_000L, authorized.tokens.expiresAtMillis)
    }

    @Test
    fun aRedirectFromTheTokenEndpointIsRefusedNotResent() = runBlocking {
        val transport = ScriptedAuthTransport().then(
            AuthScript.TOKEN_URL,
            XCoreAuthResponse(status = 302, body = "", redirectLocation = "https://attacker.example/token"),
        )

        val poll = auth(transport).pollDeviceCode(challenge).valueOrNull()

        val problem = (poll as? XCoreDevicePoll.Problem)?.error ?: failTest("expected a redirect to be a problem, got $poll")
        assertTrue(problem.detail.contains("redirect"))
        assertTrue(problem.detail.contains("attacker.example"))
    }

    @Test
    fun anUnparsableAnswerIsAProblemRatherThanAnAssumedPending() = runBlocking {
        val transport = ScriptedAuthTransport().then(
            AuthScript.TOKEN_URL,
            XCoreAuthResponse(status = 400, body = "<html>proxy error</html>", statusText = "Bad Request"),
        )

        val poll = auth(transport).pollDeviceCode(challenge).valueOrNull()

        assertTrue("a body that is not the OAuth error document must not look like 'keep waiting'", poll is XCoreDevicePoll.Problem)
    }

    // ------------------------------------------------------------- refresh

    @Test
    fun aRevokedRefreshTokenDemandsARealSignIn() = runBlocking {
        val transport = ScriptedAuthTransport().then(AuthScript.TOKEN_URL, AuthScript.oauthError(400, "invalid_grant"))

        val problem = authFailure(auth(transport).refresh("refresh-token-spent"))

        assertEquals(XCoreAuthStage.REFRESH, problem.stage)
        assertTrue("only an interactive sign-in can fix a spent grant", problem.requiresInteractiveLogin)
        assertFalse("retrying the same spent token cannot help", problem.retryable)
        assertEquals("refresh-token-spent", transport.formField(AuthScript.TOKEN_URL, "refresh_token"))
        assertEquals("refresh_token", transport.formField(AuthScript.TOKEN_URL, "grant_type"))
    }

    @Test
    fun aBusyAuthorityIsRetryableAndDoesNotLogAnyoneOut() = runBlocking {
        val transport = ScriptedAuthTransport().then(
            AuthScript.TOKEN_URL,
            XCoreAuthResponse(status = 503, body = "", statusText = "Service Unavailable"),
        )

        val problem = authFailure(auth(transport).refresh("refresh-token"))

        assertEquals(XCoreAuthStage.REFRESH, problem.stage)
        assertTrue(problem.retryable)
        assertFalse("a 503 says nothing about the account", problem.requiresInteractiveLogin)
    }

    @Test
    fun aRefreshWithoutRefreshTokenInTheAnswerIsReportedNotInvented() = runBlocking {
        val transport = ScriptedAuthTransport().thenJson(
            AuthScript.TOKEN_URL,
            200,
            """{"access_token":"fresh","expires_in":3600}""",
        )

        val tokens = auth(transport).refresh("refresh-token").valueOrNull() ?: failTest("refresh should succeed")

        assertNull("a token Microsoft did not send cannot be stored as if it had", tokens.refreshToken)
    }

    // ------------------------------------------------------------- the Xbox chain

    @Test
    fun xboxRegionAndChildAccountRefusalsKeepTheirPublishedCodes() = runBlocking {
        val cases = listOf(
            2148916231L to "region",
            2148916233L to "Xbox profile",
            2148916238L to "family",
        )
        for ((code, expectedWords) in cases) {
            val transport = ScriptedAuthTransport().then(
                AuthScript.XBL_URL,
                XCoreAuthResponse(status = 400, body = """{"XErr":$code,"message":"nope"}"""),
            )

            val problem = authFailure(auth(transport).signInToXboxLive("ms-access"))

            assertEquals(XCoreAuthStage.XBOX_LIVE, problem.stage)
            assertTrue("$code should name itself: ${problem.detail}", problem.detail.contains(code.toString()))
            assertTrue(
                "$code should say what to do about it: ${problem.detail}",
                problem.detail.contains(expectedWords, ignoreCase = true),
            )
        }
    }

    @Test
    fun anUnknownXboxErrorCodeIsPassedThroughUnchanged() = runBlocking {
        val transport = ScriptedAuthTransport().then(
            AuthScript.XBL_URL,
            XCoreAuthResponse(status = 400, body = """{"XErr":987654321,"message":"unpublished"}"""),
        )

        val problem = authFailure(auth(transport).signInToXboxLive("ms-access"))

        assertTrue(problem.detail.contains("987654321"))
    }

    @Test
    fun anXboxTokenWithoutAUserHashCannotBuyAGameSession() = runBlocking {
        val transport = ScriptedAuthTransport().thenJson(AuthScript.XBL_URL, 200, """{"Token":"xbl-token","DisplayClaims":{}}""")

        val problem = authFailure(auth(transport).signInToXboxLive("ms-access"))

        assertEquals(XCoreAuthStage.XBOX_LIVE, problem.stage)
        assertTrue(problem.detail.contains("user hash"))
    }

    @Test
    fun theGameTokenAcceptsBothFieldSpellingsAndDefaultsItsLifetime() = runBlocking {
        val snake = ScriptedAuthTransport().thenJson(
            AuthScript.MC_LOGIN_URL,
            200,
            """{"access_token":"mc-snake","expires_in":60}""",
        )
        val silent = ScriptedAuthTransport().thenJson(AuthScript.MC_LOGIN_URL, 200, """{"accessToken":"mc-camel"}""")

        val first = auth(snake).acquireMinecraftServicesToken(
            XCoreXboxTicket(XCoreMicrosoftAuth.XSTS_RELYING_PARTY, "xsts-token", "233:abcdef"),
        ).valueOrNull() ?: failTest("snake_case answer should be read")
        val second = auth(silent).acquireMinecraftServicesToken(
            XCoreXboxTicket(XCoreMicrosoftAuth.XSTS_RELYING_PARTY, "xsts-token", "233:abcdef"),
        ).valueOrNull() ?: failTest("camelCase answer should be read")

        assertEquals("mc-snake", first.accessToken)
        assertEquals(AuthScript.NOW_MILLIS + 60_000L, first.expiresAtMillis)
        assertEquals("mc-camel", second.accessToken)
        assertEquals(
            AuthScript.NOW_MILLIS + XCoreMicrosoftAuth.DEFAULT_MC_TOKEN_SECONDS * 1000L,
            second.expiresAtMillis,
        )
        assertTrue(
            "the XSTS ticket is presented as XBL3.0 x=<hash>;<token>",
            snake.requests.first().body.contains("XBL3.0 x=233:abcdef;xsts-token"),
        )
    }

    @Test
    fun aRejectedXboxTicketIsNotSilentlyTreatedAsNoGame() = runBlocking {
        val transport = ScriptedAuthTransport().then(
            AuthScript.MC_LOGIN_URL,
            XCoreAuthResponse(status = 451, body = """{"errorMessage":"unavailable for legal reasons"}"""),
        )

        val problem = authFailure(
            auth(transport).acquireMinecraftServicesToken(
                XCoreXboxTicket(XCoreMicrosoftAuth.XSTS_RELYING_PARTY, "xsts-token", "233:abcdef"),
            ),
        )

        assertEquals(XCoreAuthStage.MINECRAFT_SERVICES, problem.stage)
        assertEquals(451, problem.httpStatus)
        assertTrue(problem.detail.contains("unavailable for legal reasons"))
    }

    // ------------------------------------------------------------- profile and entitlements

    @Test
    fun anAccountWithNoProfileAnswersNullAndNotAnError() = runBlocking {
        for (status in listOf(204, 404)) {
            val transport = ScriptedAuthTransport().then(AuthScript.PROFILE_URL, XCoreAuthResponse(status = status, body = ""))

            val read = auth(transport).readProfile(AuthScript.GAME_TOKEN)

            assertTrue("status $status is an answer, not a failure", read.isSuccess)
            assertNull("status $status means no profile record, which the checker has to tell apart", read.valueOrNull())
        }
    }

    @Test
    fun aProfileEndpointOutageStaysAFailure() = runBlocking {
        val transport = ScriptedAuthTransport().then(
            AuthScript.PROFILE_URL,
            XCoreAuthResponse(status = 500, body = "", statusText = "Server Error"),
        )

        val problem = authFailure(auth(transport).readProfile(AuthScript.GAME_TOKEN))

        assertEquals(XCoreAuthStage.PROFILE, problem.stage)
        assertTrue("an outage can be retried, a missing profile cannot", problem.retryable)
    }

    @Test
    fun aGameTokenIsNeverSentInAUrlOrToAnUnapprovedHost() = runBlocking {
        val transport = ScriptedAuthTransport().thenJson(AuthScript.PROFILE_URL, 200, AuthScript.PROFILE_JSON)

        auth(transport).readProfile(AuthScript.GAME_TOKEN)

        val request = transport.requests.single()
        assertEquals("GET", request.method)
        assertEquals(AuthScript.GAME_TOKEN, request.bearer)
        assertFalse(request.url.contains(AuthScript.GAME_TOKEN))
        assertEquals("https://api.minecraftservices.com/minecraft/profile", request.url)
    }

    @Test
    fun theRetiredEntitlementDocumentIsReportedAsUnavailable() = runBlocking {
        for (status in listOf(204, 404, 405, 410)) {
            val transport = ScriptedAuthTransport().then(AuthScript.ENTITLEMENTS_URL, XCoreAuthResponse(status = status, body = ""))

            val read = auth(transport).readEntitlements(AuthScript.GAME_TOKEN).valueOrNull()

            assertEquals("status $status", XCoreEntitlementRead.EndpointUnavailable(status), read)
        }
    }

    @Test
    fun aRejectedGameTokenOnTheEntitlementEndpointIsAFailureNotAnEmptyList() = runBlocking {
        for (status in listOf(401, 403)) {
            val transport = ScriptedAuthTransport().then(
                AuthScript.ENTITLEMENTS_URL,
                XCoreAuthResponse(status = status, body = """{"error":"invalid_token"}"""),
            )

            val problem = authFailure(auth(transport).readEntitlements(AuthScript.GAME_TOKEN))

            assertEquals(XCoreAuthStage.ENTITLEMENT, problem.stage)
            assertTrue("the session has to be re-obtained", problem.requiresInteractiveLogin)
        }
    }

    @Test
    fun listedEntitlementsKeepTheirLabels() = runBlocking {
        val transport = ScriptedAuthTransport().thenJson(
            AuthScript.ENTITLEMENTS_URL,
            200,
            AuthScript.ENTITLEMENTS_JAVA,
        )

        val read = auth(transport).readEntitlements(AuthScript.GAME_TOKEN).valueOrNull()

        val listed = read as? XCoreEntitlementRead.Listed ?: failTest("expected a listed answer, got $read")
        assertEquals(1, listed.items.size)
        assertTrue(listed.items.single().mentionsJavaEdition)
        assertTrue(listed.items.single().isBaseMinecraftSku)
        assertEquals("items", listed.documentFields)
    }

    // ------------------------------------------------------------- the host allowlist

    @Test
    fun plainHttpAndForeignHostsAreRefusedBeforeAConnection() {
        val insecure = XCoreAuthEndpoints.requireAllowed("http://login.microsoftonline.com/common/oauth2/v2.0/token")
        val foreign = XCoreAuthEndpoints.requireAllowed("https://evil.example.com/token")
        val malformed = XCoreAuthEndpoints.requireAllowed("not a url")

        assertEquals(XCoreErrorKind.PERMISSION, failure(insecure).kind)
        assertTrue(failure(insecure).detail.contains("https"))
        assertEquals(XCoreErrorKind.PERMISSION, failure(foreign).kind)
        assertTrue(failure(foreign).detail.contains("evil.example.com"))
        assertEquals(XCoreErrorKind.VALIDATION, failure(malformed).kind)
    }

    @Test
    fun aTenantIsValidatedBecauseItBecomesTheRequestPath() {
        assertEquals(
            "https://login.microsoftonline.com/common/oauth2/v2.0/devicecode",
            XCoreAuthEndpoints.deviceCodeUrl("common"),
        )
        for (attempt in listOf("../tenant", "common?x=1", "", " ".repeat(70))) {
            try {
                XCoreAuthEndpoints.deviceCodeUrl(attempt)
                failTest("'$attempt' must not become a request path")
            } catch (refused: XCoreFailureException) {
                assertEquals(XCoreErrorKind.VALIDATION, refused.error.kind)
            }
        }
    }

    @Test
    fun theEndpointsInTheScriptAreTheOnesTheFlowUses() = runBlocking {
        val transport = AuthScript.happyPath()

        val issued = auth(transport).requestDeviceCode().valueOrNull() ?: failTest("device code")
        auth(transport).pollDeviceCode(issued).valueOrNull()
        val xbox = auth(transport).signInToXboxLive(AuthScript.MS_ACCESS).valueOrNull() ?: failTest("xbl")
        val xsts = auth(transport).requestXstsTicket(xbox.token).valueOrNull() ?: failTest("xsts")
        val game = auth(transport).acquireMinecraftServicesToken(xsts).valueOrNull() ?: failTest("mc")
        val profile = auth(transport).readProfile(game.accessToken).valueOrNull() ?: failTest("profile")
        val entitlements = auth(transport).readEntitlements(game.accessToken).valueOrNull() ?: failTest("entitlements")

        assertEquals("8f5e5a0b-1c2d-3e4f-5a6b-7c8d9e0f1a2b", profile.uuid)
        assertEquals("Badlion", profile.name)
        assertTrue(profile.validate().isSuccess)
        assertNotNull(entitlements)
        assertEquals(7, transport.requests.size)
        assertTrue(transport.requests.map { it.url }.all { it.startsWith("https://") })
        assertFalse(transport.anyRequestCarriesPassword())
        assertEquals(AuthScript.GAME_TOKEN, game.accessToken)
        assertEquals(
            AuthScript.NOW_MILLIS + XCoreMicrosoftAuth.DEFAULT_MC_TOKEN_SECONDS * 1000L,
            game.expiresAtMillis,
        )
        assertEquals("xbl-token", xbox.token)
        assertEquals("xsts-token", xsts.token)
        assertEquals("233:abcdef", xsts.userHash)
    }

    @Test
    fun aProfileNameTheGameWouldRejectIsRefusedAtTheProfileStage() {
        val spoofed = XCoreMinecraftProfile(uuid = AuthScript.UUID_COMPACT, name = "n0t a valid name!").validate()

        assertEquals(XCoreAuthStage.PROFILE, authFailure(spoofed).stage)
        assertTrue(spoofed.isSuccess.not())
    }

    @Test
    fun aUuidShapedLikeARealOnePassesAndAShorterOneDoesNot() {
        assertTrue(XCoreMinecraftProfile(uuid = AuthScript.UUID_COMPACT, name = "Badlion").validate().isSuccess)
        assertTrue(
            XCoreMinecraftProfile(uuid = "8f5e5a0b-1c2d-3e4f-5a6b-7c8d9e0f1a2b", name = "Badlion").validate().isSuccess,
        )
        assertFalse(XCoreMinecraftProfile(uuid = "12345", name = "Badlion").validate().isSuccess)
    }
}
