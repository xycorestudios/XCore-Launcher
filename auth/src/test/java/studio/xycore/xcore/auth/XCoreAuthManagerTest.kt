package studio.xycore.xcore.auth

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import studio.xycore.xcore.core.XCoreAuthStage
import studio.xycore.xcore.core.XCoreError
import studio.xycore.xcore.core.XCoreErrorKind
import studio.xycore.xcore.core.XCoreResult
import studio.xycore.xcore.core.errorOrNull
import studio.xycore.xcore.core.isFailure
import studio.xycore.xcore.core.isSuccess
import studio.xycore.xcore.core.valueOrNull

/**
 * The sign-in, the session and the vault, driven through the real classes with only the sockets
 * scripted.
 *
 * These cases hold the manager to the promises that make the module usable rather than decorative:
 * one sign-in at a time, Microsoft's own waiting intervals honoured, a declined or expired code
 * discarded instead of re-polled, a spent refresh token marked as needing a real sign-in instead of
 * retried, and a launch credential that only ever comes out of the stored account.
 */
class XCoreAuthManagerTest {

    @JvmField
    @Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private var vaultSequence = 0

    private inner class Fixture(
        val transport: ScriptedAuthTransport = ScriptedAuthTransport(),
        val config: XCoreAuthConfig = AuthScript.CONFIGURED,
        vaultName: String = "vault-${++vaultSequence}.bin",
    ) {
        var now: Long = AuthScript.NOW_MILLIS
        val slept = ArrayList<Long>()
        val vaultFile: File = File(temp.root, vaultName)
        val store: XCoreTokenStore = XCoreTokenStore(file = vaultFile, cipher = MarkingTestCipher())
        val accounts: XCoreAccountRepository = XCoreAccountRepository(store, clock = { now })
        val microsoft: XCoreMicrosoftAuth = XCoreMicrosoftAuth(config = config, transport = transport, clock = { now })
        val manager: XCoreAuthManager = XCoreAuthManager(
            config = config,
            auth = microsoft,
            accounts = accounts,
            entitlements = XCoreEntitlementChecker(auth = microsoft, clock = { now }),
            clock = { now },
            sleep = { millis -> slept += millis },
        )

        suspend fun signIn(): XCoreAccountSummary {
            manager.beginSignIn().valueOrNull() ?: failTest("the scripted device code should have been issued")
            return manager.completeSignIn().valueOrNull() ?: failTest("the scripted sign-in should have completed")
        }

        suspend fun putVault(vault: XCoreAuthVault) {
            store.write(vault).valueOrNull() ?: failTest("the test vault could not be written")
        }

        suspend fun stored(): XCoreStoredAccount? = accounts.activeAccount().valueOrNull()

        /** Moves the clock past the point where a stored game token has to be renewed. */
        fun letTheGameTokenExpire() {
            now = AuthScript.NOW_MILLIS + 86_400_000L + XCoreAuthSession.EXPIRY_SAFETY_MILLIS
        }

        /** The four calls a renewal makes: refresh, Xbox Live, XSTS, Minecraft Services. */
        fun armRenewalChain(newRefreshToken: String? = "fresh-" + AuthScript.MS_REFRESH) {
            val body = buildString {
                append("""{"access_token":"fresh-ms","expires_in":3600""")
                if (newRefreshToken != null) append(""","refresh_token":"$newRefreshToken"""")
                append("}")
            }
            transport.thenJson(AuthScript.TOKEN_URL, 200, body)
                .thenJson(AuthScript.XBL_URL, 200, AuthScript.xbl("xbl-token"))
                .thenJson(AuthScript.XSTS_URL, 200, AuthScript.xbl("xsts-token"))
                .thenJson(AuthScript.MC_LOGIN_URL, 200, AuthScript.MC_LOGIN)
        }
    }

    private fun failure(result: XCoreResult<*>): XCoreError =
        result.errorOrNull() ?: failTest("expected a typed failure, got ${result::class.simpleName}")

    private fun authFailure(result: XCoreResult<*>): XCoreError.Authentication =
        failure(result) as? XCoreError.Authentication
            ?: failTest("expected an authentication failure, got ${failure(result).format()}")

    private fun account(
        id: String = "stored-account",
        profile: XCoreMinecraftProfile? = XCoreMinecraftProfile(uuid = AuthScript.UUID_COMPACT, name = "Badlion"),
        ownsJavaEdition: Boolean = true,
        accessToken: String = AuthScript.GAME_TOKEN,
        refreshToken: String? = AuthScript.MS_REFRESH,
        lastProblem: String? = null,
        requiresInteractiveLogin: Boolean = false,
    ): XCoreStoredAccount = XCoreStoredAccount(
        id = id,
        kind = XCoreAccountKind.MICROSOFT,
        profile = profile,
        accessToken = accessToken,
        accessTokenExpiresAtMillis = AuthScript.NOW_MILLIS + 86_400_000L,
        refreshToken = refreshToken,
        clientToken = "stored-client-token",
        addedAtMillis = AuthScript.NOW_MILLIS,
        ownsJavaEdition = ownsJavaEdition,
        entitlementBasis = if (ownsJavaEdition) XCoreEntitlementBasis.ENTITLEMENTS_LIST else null,
        lastVerifiedAtMillis = AuthScript.NOW_MILLIS,
        lastProblem = lastProblem,
        requiresInteractiveLogin = requiresInteractiveLogin,
    )

    // -------------------------------------------------------------------- starting a sign-in

    @Test
    fun abuildWithoutAnAppRegistrationDoesNotStartASignIn() = runBlocking {
        val fixture = Fixture(config = XCoreAuthConfig(clientId = ""))

        val problem = failure(fixture.manager.beginSignIn())

        assertEquals(XCoreErrorKind.CONFIGURATION, problem.kind)
        assertTrue(problem.detail, problem.detail.contains("auth/README.md"))
        assertEquals(0, fixture.transport.requests.size)
    }

    @Test
    fun aCodeThatIsStillAwaitingApprovalIsReusedRatherThanRequestedAgain() = runBlocking {
        val fixture = Fixture()
        fixture.putVault(
            XCoreAuthVault(
                accounts = listOf(account()),
                pendingChallenge = XCoreDeviceCodeChallenge(
                    clientId = AuthScript.CLIENT_ID,
                    deviceCode = "already-issued-device-code",
                    userCode = "XYZ-789",
                    verificationUri = "https://microsoft.com/devicelogin",
                    instructions = "type this at the page",
                    intervalSeconds = 5,
                    expiresAtMillis = AuthScript.NOW_MILLIS + 900_000L,
                ),
            ),
        )

        val prompt = fixture.manager.beginSignIn().valueOrNull() ?: failTest("the stored challenge should resume")

        assertEquals("XYZ-789", prompt.userCode)
        assertEquals(
            "no second device code request",
            emptyList<ScriptedAuthTransport.RecordedRequest>(),
            fixture.transport.sentTo(AuthScript.DEVICE_URL),
        )
        assertEquals(XCoreAuthState.AwaitingUserApproval(prompt), fixture.manager.current.value)
    }

    @Test
    fun thePromptShownToThePersonCarriesNoRedeemableValue() = runBlocking {
        val fixture = Fixture(AuthScript.happyPath())

        val prompt = fixture.manager.beginSignIn().valueOrNull() ?: failTest("device code")

        assertEquals("ABC-123", prompt.userCode)
        assertEquals("https://microsoft.com/devicelogin", prompt.verificationUri)
        assertFalse(prompt.toString(), prompt.toString().contains("device-code-secret"))
        assertEquals(XCoreAuthState.AwaitingUserApproval(prompt), fixture.manager.current.value)
        assertEquals(
            "the value that redeems the grant stays in the encrypted vault",
            "device-code-secret",
            fixture.store.read().valueOrNull()?.pendingChallenge?.deviceCode,
        )
    }

    // -------------------------------------------------------------------- completing one

    @Test
    fun anApprovedCodeBecomesAStoredAccountAndAUsableSession() = runBlocking {
        val fixture = Fixture(AuthScript.happyPath())

        val summary = fixture.signIn()
        val session = fixture.manager.currentSession().valueOrNull() ?: failTest("the session should be issued")

        assertEquals("Badlion", summary.displayName)
        assertEquals(AuthScript.UUID_COMPACT, summary.uuid)
        assertTrue(summary.ownsJavaEdition)
        assertEquals(XCoreEntitlementBasis.ENTITLEMENTS_LIST, summary.entitlementBasis)
        assertTrue(summary.hasRefreshToken)
        assertEquals(XCoreAuthState.SignedIn(summary, session.expiresAtMillis), fixture.manager.current.value)

        assertEquals(AuthScript.GAME_TOKEN, session.accessToken)
        assertEquals(AuthScript.UUID_COMPACT, session.profile.compactUuid)
        assertEquals(AuthScript.NOW_MILLIS + 86_400_000L, session.expiresAtMillis)
        assertEquals("MSA", session.userType)
        assertTrue(session.clientToken.isNotBlank())

        val fields = session.credentialFields()
        assertEquals("Badlion", fields["auth_player_name"])
        assertEquals(AuthScript.GAME_TOKEN, fields["auth_access_token"])
        assertEquals(XCoreAuthSession.MASK, session.redactedCredentialFields()["auth_access_token"])

        val stored = fixture.stored() ?: failTest("the account should be on disk")
        assertEquals(AuthScript.MS_REFRESH, stored.refreshToken)
        assertEquals(AuthScript.GAME_TOKEN, stored.accessToken)
        assertNull("a completed sign-in leaves no pending code", fixture.store.read().valueOrNull()?.pendingChallenge)
        assertEquals(7, fixture.transport.requests.size)
        assertFalse("no step of this flow has a place for a password", fixture.transport.anyRequestCarriesPassword())
    }

    @Test
    fun microsoftWaitsAreHeldForTheIntervalMicrosoftAskedFor() = runBlocking {
        val transport = ScriptedAuthTransport()
            .thenJson(AuthScript.DEVICE_URL, 200, AuthScript.DEVICE_CODE)
            .then(AuthScript.TOKEN_URL, AuthScript.oauthError(400, "authorization_pending"))
            .then(AuthScript.TOKEN_URL, AuthScript.oauthError(400, "slow_down"))
            .thenJson(AuthScript.TOKEN_URL, 200, AuthScript.TOKENS)
            .thenJson(AuthScript.XBL_URL, 200, AuthScript.xbl("xbl-token"))
            .thenJson(AuthScript.XSTS_URL, 200, AuthScript.xbl("xsts-token"))
            .thenJson(AuthScript.MC_LOGIN_URL, 200, AuthScript.MC_LOGIN)
            .thenJson(AuthScript.PROFILE_URL, 200, AuthScript.PROFILE_JSON)
            .thenJson(AuthScript.ENTITLEMENTS_URL, 200, AuthScript.ENTITLEMENTS_JAVA)
        val fixture = Fixture(transport)

        fixture.manager.beginSignIn()
        fixture.manager.completeSignIn().valueOrNull() ?: failTest("the code was approved on the third poll")

        assertEquals(listOf(5_000L, 15_000L), fixture.slept)
        assertEquals(3, transport.sentTo(AuthScript.TOKEN_URL).size)
    }

    @Test
    fun aDeclinedSignInIsDiscardedAndSaysWhoRefusedIt() = runBlocking {
        val transport = ScriptedAuthTransport()
            .thenJson(AuthScript.DEVICE_URL, 200, AuthScript.DEVICE_CODE)
            .then(AuthScript.TOKEN_URL, AuthScript.oauthError(400, "access_denied", "the user has canceled authentication"))
        val fixture = Fixture(transport)
        fixture.manager.beginSignIn()

        val problem = authFailure(fixture.manager.completeSignIn())

        assertEquals(XCoreAuthStage.USER_CODE, problem.stage)
        assertTrue(problem.detail, problem.detail.contains("declined at Microsoft"))
        assertTrue(problem.detail, problem.detail.contains("the user has canceled authentication"))
        assertTrue(problem.requiresInteractiveLogin)
        assertNull(fixture.store.read().valueOrNull()?.pendingChallenge)
        assertTrue(fixture.manager.current.value is XCoreAuthState.Problem)
        assertEquals(
            "a declined code is polled once, not retried",
            1,
            transport.sentTo(AuthScript.TOKEN_URL).size,
        )
    }

    @Test
    fun aCodeThatAgedOutIsDroppedWithoutAnotherRequest() = runBlocking {
        val transport = ScriptedAuthTransport().thenJson(AuthScript.DEVICE_URL, 200, AuthScript.DEVICE_CODE)
        val fixture = Fixture(transport)
        fixture.manager.beginSignIn()
        fixture.now = AuthScript.NOW_MILLIS + 901_000L

        val problem = authFailure(fixture.manager.completeSignIn())

        assertEquals(XCoreAuthStage.USER_CODE, problem.stage)
        assertTrue(problem.detail, problem.detail.contains("not approved in time"))
        assertEquals(0, transport.sentTo(AuthScript.TOKEN_URL).size)
        assertNull(fixture.store.read().valueOrNull()?.pendingChallenge)
    }

    @Test
    fun closingTheSigninSheetStopsTheLauncherFromPollingThatCode() = runBlocking {
        val transport = AuthScript.happyPath()
        val fixture = Fixture(transport)
        fixture.manager.beginSignIn()

        assertTrue(fixture.manager.cancelSignIn("the person closed the sign-in sheet").isSuccess)

        assertNull(fixture.store.read().valueOrNull()?.pendingChallenge)
        val problem = authFailure(fixture.manager.completeSignIn())
        assertEquals(XCoreAuthStage.USER_CODE, problem.stage)
        assertTrue(problem.detail, problem.detail.contains("ask for a code first"))
        assertEquals(0, transport.sentTo(AuthScript.TOKEN_URL).size)
    }

    @Test
    fun anAccountWithNoGameProfileIsNotRecordedAsSignedIn() = runBlocking {
        val transport = ScriptedAuthTransport()
            .thenJson(AuthScript.DEVICE_URL, 200, AuthScript.DEVICE_CODE)
            .thenJson(AuthScript.TOKEN_URL, 200, AuthScript.TOKENS)
            .thenJson(AuthScript.XBL_URL, 200, AuthScript.xbl("xbl-token"))
            .thenJson(AuthScript.XSTS_URL, 200, AuthScript.xbl("xsts-token"))
            .thenJson(AuthScript.MC_LOGIN_URL, 200, AuthScript.MC_LOGIN)
            .then(AuthScript.PROFILE_URL, XCoreAuthResponse(status = 204, body = ""))
            .thenJson(AuthScript.ENTITLEMENTS_URL, 200, AuthScript.ENTITLEMENTS_JAVA)
        val fixture = Fixture(transport)
        fixture.manager.beginSignIn()

        val problem = authFailure(fixture.manager.completeSignIn())

        assertEquals(XCoreAuthStage.ENTITLEMENT, problem.stage)
        assertTrue(problem.detail, problem.detail.contains("no Minecraft profile record"))
        assertTrue(
            "nothing was signed in",
            fixture.store.read().valueOrNull()?.accounts.isNullOrEmpty(),
        )
    }

    @Test
    fun anAccountThatHoldsOnlyTheOtherEditionIsNotSignedIn() = runBlocking {
        val fixture = Fixture(AuthScript.happyPath(entitlements = AuthScript.ENTITLEMENTS_BEDROCK_ONLY))
        fixture.manager.beginSignIn()

        val problem = authFailure(fixture.manager.completeSignIn())

        assertEquals(XCoreAuthStage.ENTITLEMENT, problem.stage)
        assertTrue(problem.detail, problem.detail.contains("no Minecraft Java Edition product"))
        assertTrue(
            "owning the other game is not the same as owning nothing",
            problem.detail.contains("Minecraft for Windows"),
        )
        assertTrue(fixture.store.read().valueOrNull()?.accounts.isNullOrEmpty())
    }

    // -------------------------------------------------------------------- keeping a session alive

    @Test
    fun aspentRefreshTokenMarksTheAccountInsteadOfBeingRetried() = runBlocking {
        val fixture = Fixture(AuthScript.happyPath())
        fixture.signIn()
        fixture.transport.then(AuthScript.TOKEN_URL, AuthScript.oauthError(400, "invalid_grant"))
        fixture.letTheGameTokenExpire()

        val problem = authFailure(fixture.manager.currentSession())

        assertEquals(XCoreAuthStage.REFRESH, problem.stage)
        assertTrue(problem.requiresInteractiveLogin)
        assertFalse("retrying a spent grant cannot help", problem.retryable)
        assertEquals("one poll at sign-in plus one renewal attempt", 2, fixture.transport.sentTo(AuthScript.TOKEN_URL).size)
        val stored = fixture.stored() ?: failTest("the account stays stored")
        assertTrue("the next attempt must explain itself", stored.requiresInteractiveLogin)
        assertEquals(problem.detail, stored.lastProblem)
        assertTrue(fixture.manager.current.value is XCoreAuthState.Problem)
    }

    @Test
    fun aBusyAuthorityWhileRenewingKeepsTheAccountSignedIn() = runBlocking {
        val fixture = Fixture(AuthScript.happyPath())
        fixture.signIn()
        fixture.transport.then(AuthScript.TOKEN_URL, XCoreAuthResponse(status = 503, body = "", statusText = "Unavailable"))
        fixture.letTheGameTokenExpire()

        val problem = authFailure(fixture.manager.currentSession())

        assertTrue("a 503 is worth trying again", problem.retryable)
        assertEquals(XCoreErrorKind.AUTHENTICATION, problem.kind)
        val stored = fixture.stored() ?: failTest("the account stays stored")
        assertFalse("an outage says nothing about the grant", stored.requiresInteractiveLogin)
        assertEquals(AuthScript.MS_REFRESH, stored.refreshToken)
        assertTrue(fixture.manager.holdsSession().valueOrNull() ?: failTest("holdsSession"))
    }

    @Test
    fun renewalUsesTheStoredRefreshTokenAndKeepsWhatComesBack() = runBlocking {
        val fixture = Fixture(AuthScript.happyPath())
        fixture.signIn()
        fixture.armRenewalChain()
        fixture.letTheGameTokenExpire()

        val session = fixture.manager.currentSession().valueOrNull() ?: failTest("the session should renew")

        val tokenCalls = fixture.transport.sentTo(AuthScript.TOKEN_URL)
        assertEquals(2, tokenCalls.size)
        val renewalBody = tokenCalls.last().body
        assertEquals("refresh_token", renewalBody.field("grant_type"))
        assertEquals(AuthScript.MS_REFRESH, renewalBody.field("refresh_token"))
        assertEquals(AuthScript.GAME_TOKEN, session.accessToken)
        val stored = fixture.stored() ?: failTest("the account stays stored")
        assertEquals("fresh-${AuthScript.MS_REFRESH}", stored.refreshToken)
        assertFalse(stored.requiresInteractiveLogin)
        assertNull(stored.lastProblem)
    }

    @Test
    fun arenewalThatReturnsNoNewTokenKeepsTheOneAlreadyStored() = runBlocking {
        val fixture = Fixture(AuthScript.happyPath())
        fixture.signIn()
        fixture.armRenewalChain(newRefreshToken = null)
        fixture.letTheGameTokenExpire()

        fixture.manager.currentSession().valueOrNull() ?: failTest("the renewal should still succeed")

        val stored = fixture.stored() ?: failTest("the account stays stored")
        assertEquals(
            "Microsoft sent no replacement, so the usable one already on disk is kept",
            AuthScript.MS_REFRESH,
            stored.refreshToken,
        )
        assertNull("keeping a working token is not a problem to warn about", stored.lastProblem)
    }

    @Test
    fun anAccountWithNoRefreshTokenIsToldToSignInAgain() = runBlocking {
        val fixture = Fixture()
        fixture.putVault(
            XCoreAuthVault(
                activeAccountId = "no-refresh",
                accounts = listOf(account(id = "no-refresh", refreshToken = null)),
                clientToken = "c",
            ),
        )
        fixture.letTheGameTokenExpire()

        val problem = authFailure(fixture.manager.currentSession())

        assertEquals(XCoreAuthStage.REFRESH, problem.stage)
        assertTrue(problem.requiresInteractiveLogin)
        assertEquals("no request without a token to refresh with", 0, fixture.transport.requests.size)
        assertTrue(fixture.manager.current.value is XCoreAuthState.Problem)
    }

    // -------------------------------------------------------------------- what refuses a launch

    @Test
    fun anAccountThatCannotProduceASessionSaysWhichRuleRefusedIt() = runBlocking {
        val cases = listOf(
            "no game profile" to (
                account(id = "a", profile = null) to XCoreAuthStage.PROFILE
                ),
            "ownership unconfirmed" to (
                account(id = "b", ownsJavaEdition = false, lastProblem = "no Java Edition product") to XCoreAuthStage.ENTITLEMENT
                ),
            "no access token" to (
                account(id = "c", accessToken = "") to XCoreAuthStage.MINECRAFT_SERVICES
                ),
        )
        for ((label, pair) in cases) {
            val (stored, expectedStage) = pair
            val fixture = Fixture()
            fixture.putVault(XCoreAuthVault(activeAccountId = stored.id, accounts = listOf(stored), clientToken = "c"))

            val problem = authFailure(fixture.manager.currentSession())

            assertEquals(label, expectedStage, problem.stage)
            assertTrue(label, problem.requiresInteractiveLogin)
            assertEquals("$label must not reach the network", 0, fixture.transport.requests.size)
        }

        val empty = Fixture()
        assertTrue(failure(empty.manager.currentSession()).detail.contains("no account is signed in"))
        assertFalse(empty.manager.holdsSession().valueOrNull() ?: failTest("holdsSession"))
    }

    @Test
    fun arecheckThatFindsTheLicenceGoneIsStoredAndBlocksTheNextLaunch() = runBlocking {
        val fixture = Fixture(AuthScript.happyPath())
        fixture.signIn()
        fixture.transport.thenJson(AuthScript.PROFILE_URL, 200, AuthScript.PROFILE_JSON)
        fixture.transport.thenJson(AuthScript.ENTITLEMENTS_URL, 200, AuthScript.ENTITLEMENTS_BEDROCK_ONLY)

        val verdict = fixture.manager.recheckOwnership().valueOrNull()
            ?: failTest("a recheck reports the answer instead of throwing at it")

        assertFalse(verdict.ownsJavaEdition)
        val stored = fixture.stored() ?: failTest("the account stays stored")
        assertFalse("the refusal is on disk, not only in this call", stored.ownsJavaEdition)
        assertEquals(verdict.detail, stored.lastProblem)
        assertEquals(XCoreAuthStage.ENTITLEMENT, authFailure(fixture.manager.currentSession()).stage)
    }

    // -------------------------------------------------------------------- leaving

    @Test
    fun signingOutForgetsTheAccountOnThisDevice() = runBlocking {
        val fixture = Fixture(AuthScript.happyPath())
        val summary = fixture.signIn()
        assertTrue(fixture.manager.holdsSession().valueOrNull() ?: failTest("holdsSession"))

        assertTrue(fixture.manager.signOut(summary.id).isSuccess)

        assertNull(fixture.stored())
        assertEquals(XCoreAuthState.SignedOut, fixture.manager.current.value)
        assertTrue(fixture.manager.signOut().isFailure)
        assertFalse(fixture.manager.holdsSession().valueOrNull() ?: failTest("holdsSession"))
    }

    @Test
    fun signingOutNothingOrUnknownIsAFailureNotANoOp() = runBlocking {
        val fixture = Fixture()

        assertTrue(fixture.manager.signOut("never-stored").isFailure)
        assertEquals(
            "there is no stored account with id 'never-stored' to remove",
            failure(fixture.manager.signOut("never-stored")).detail,
        )
        assertTrue(fixture.manager.signOut().isFailure)
        assertTrue(failure(fixture.manager.signOut()).detail.contains("no signed-in account to sign out of"))
    }

    @Test
    fun forgettingEverythingRemovesTheVaultFile() = runBlocking {
        val fixture = Fixture(AuthScript.happyPath())
        fixture.signIn()
        assertTrue(fixture.vaultFile.exists())

        assertTrue(fixture.manager.forgetEverything().isSuccess)

        assertFalse(fixture.vaultFile.exists())
        assertEquals(XCoreAuthState.SignedOut, fixture.manager.current.value)
    }

    // -------------------------------------------------------------------- coming back up

    @Test
    fun restoringPublishesTheStoredStateWithoutASingleRequest() = runBlocking {
        val fixture = Fixture()
        fixture.putVault(
            XCoreAuthVault(
                activeAccountId = "stored-account",
                accounts = listOf(account()),
                clientToken = "installed-client-token",
            ),
        )

        assertTrue(fixture.manager.restore().isSuccess)

        val state = fixture.manager.current.value as? XCoreAuthState.SignedIn
            ?: failTest("expected signed-in, got ${fixture.manager.current.value}")
        assertEquals("Badlion", state.account.displayName)
        assertEquals("no network call while restoring", 0, fixture.transport.requests.size)
        assertEquals(MarkingTestCipher().description, fixture.manager.protection())
    }

    @Test
    fun restoringADamagedVaultIsReportedInsteadOfLookingSignedOut() = runBlocking {
        val fixture = Fixture()
        fixture.vaultFile.writeBytes("""{"accounts":[]}""".toByteArray(Charsets.UTF_8))

        val problem = failure(fixture.manager.restore())

        assertEquals(XCoreErrorKind.AUTHENTICATION, problem.kind)
        assertEquals(XCoreAuthStage.TOKEN_STORAGE, (problem as XCoreError.Authentication).stage)
        assertEquals(XCoreAuthState.SignedOut, fixture.manager.current.value)
    }

    @Test
    fun theStatusSheetNamesTheRegistrationAndTheStorage() = runBlocking {
        val unconfigured = Fixture(config = XCoreAuthConfig(clientId = ""))
        assertTrue(unconfigured.manager.restore().isSuccess)
        val rows = unconfigured.manager.status().valueOrNull()?.rows() ?: failTest("status")

        assertEquals("not configured", rows.first { it.first == "app registration" }.second)
        assertEquals(MarkingTestCipher().description, rows.first { it.first == "credential storage" }.second)
        assertEquals("0", rows.first { it.first == "accounts held" }.second)
        assertEquals("none", rows.first { it.first == "active account" }.second)
    }

    @Test
    fun selectingAnAccountMakesItTheOneALaunchUses() = runBlocking {
        val fixture = Fixture()
        fixture.putVault(
            XCoreAuthVault(
                activeAccountId = "first",
                accounts = listOf(
                    account(id = "first"),
                    account(id = "second", profile = XCoreMinecraftProfile(uuid = AuthScript.UUID_COMPACT, name = "Second")),
                ),
                clientToken = "c",
            ),
        )

        val chosen = fixture.manager.selectAccount("second").valueOrNull() ?: failTest("select")
        val session = fixture.manager.currentSession().valueOrNull() ?: failTest("session")

        assertEquals("Second", chosen.displayName)
        assertEquals("Second", session.profile.name)
        assertEquals(XCoreAuthState.SignedIn(chosen, session.expiresAtMillis), fixture.manager.current.value)
        assertTrue(
            failure(fixture.manager.selectAccount("third")).detail.contains("no stored account with id 'third'"),
        )
    }
}

/** A form field inside a recorded request body, for assertions about what left the device. */
private fun String.field(name: String): String? =
    split("&").firstOrNull { it.startsWith("$name=") }?.substringAfter("=")
