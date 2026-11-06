package studio.xycore.xcore.auth

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.xycore.xcore.core.XCoreAuthStage
import studio.xycore.xcore.core.XCoreError
import studio.xycore.xcore.core.XCoreErrorKind
import studio.xycore.xcore.core.XCoreResult
import studio.xycore.xcore.core.errorOrNull
import studio.xycore.xcore.core.valueOrNull
import studio.xycore.xcore.core.xCoreRunCatching

/**
 * The ownership rule, tested as the rule table it is.
 *
 * Two answers matter more than the rest. "The entitlement document is not available" must fall back
 * to the narrower profile rule and *say* that it did, because a person who owns the game on a
 * revoked-endpoint day is still a person who owns the game. And "this endpoint timed out" must never
 * become "you do not own Minecraft", because the launcher cannot take a licence away with a timeout.
 */
class XCoreEntitlementCheckerTest {

    private val profile = XCoreMinecraftProfile(uuid = AuthScript.UUID_COMPACT, name = "Badlion")

    private fun checker(transport: ScriptedAuthTransport) = XCoreEntitlementChecker(
        auth = XCoreMicrosoftAuth(config = AuthScript.CONFIGURED, transport = transport, clock = { AuthScript.NOW_MILLIS }),
        clock = { AuthScript.NOW_MILLIS },
    )

    private fun item(
        skuName: String = "",
        displayName: String = "",
        productTitle: String = "",
        contentTrees: List<String> = emptyList(),
    ) = XCoreEntitlementItem(
        skuName = skuName,
        displayName = displayName,
        productTitle = productTitle,
        contentTreeNames = contentTrees,
    )

    /** Runs the pure rule table, so a refusal inside it surfaces as a typed failure like anywhere else. */
    private fun decide(read: XCoreEntitlementRead): XCoreEntitlementVerdict =
        xCoreRunCatching("test.decide", XCoreErrorKind.AUTHENTICATION) {
            XCoreEntitlementChecker(
                auth = XCoreMicrosoftAuth(AuthScript.CONFIGURED, ScriptedAuthTransport()),
                clock = { AuthScript.NOW_MILLIS },
            ).decide(profile, read)
        }.valueOrNull() ?: failTest("the rule table did not answer: ${read}")

    private fun decideFailure(read: XCoreEntitlementRead): XCoreError =
        xCoreRunCatching("test.decide", XCoreErrorKind.AUTHENTICATION) {
            XCoreEntitlementChecker(
                auth = XCoreMicrosoftAuth(AuthScript.CONFIGURED, ScriptedAuthTransport()),
            ).decide(profile, read)
        }.errorOrNull() ?: failTest("expected the rule table to refuse")

    // -------------------------------------------------------------------- the document says

    @Test
    fun aJavaEditionProductInTheListIsTheStrongestAnswer() {
        val verdict = decide(
            XCoreEntitlementRead.Listed(
                items = listOf(item(skuName = "minecraft", displayName = "Minecraft Java Edition", contentTrees = listOf("minecraft.java"))),
                documentFields = "items",
            ),
        )

        assertTrue(verdict.ownsJavaEdition)
        assertEquals(XCoreEntitlementBasis.ENTITLEMENTS_LIST, verdict.basis)
        assertEquals(listOf("Minecraft Java Edition"), verdict.matchedProducts)
        assertEquals(AuthScript.NOW_MILLIS, verdict.checkedAtMillis)
    }

    @Test
    fun aContentTreeLabelCountsTowardIdentifyingTheEdition() {
        val verdict = decide(
            XCoreEntitlementRead.Listed(
                items = listOf(item(skuName = "minecraft_xb8k2l9", contentTrees = listOf("minecraft.java"))),
                documentFields = "items",
            ),
        )

        assertTrue(
            "the sku is opaque, so the content tree is the only thing that names the edition",
            verdict.ownsJavaEdition,
        )
    }

    @Test
    fun aContentTreeThatNamesNeitherEditionIsNotTreatedAsJava() {
        val verdict = decide(
            XCoreEntitlementRead.Listed(
                items = listOf(item(skuName = "game_minecraft_collection", contentTrees = listOf("game_minecraft"))),
                documentFields = "items",
            ),
        )

        assertFalse(verdict.ownsJavaEdition)
        assertTrue(verdict.detail, verdict.detail.contains("lists no Minecraft Java Edition product"))
    }

    @Test
    fun theJavaEditionMarkerIsFoundWhateverCaseTheDocumentUses() {
        val verdict = decide(
            XCoreEntitlementRead.Listed(items = listOf(item(productTitle = "MINECRAFT: Java Edition")), documentFields = "items"),
        )

        assertTrue(verdict.ownsJavaEdition)
        assertEquals(listOf("MINECRAFT: Java Edition"), verdict.matchedProducts)
    }

    @Test
    fun theBaseSkuWearingTheOtherEditionsNameIsNotJavaEdition() {
        val verdict = decide(
            XCoreEntitlementRead.Listed(
                items = listOf(item(skuName = "minecraft", displayName = "Minecraft for Windows", productTitle = "Minecraft for Windows 10 Edition")),
                documentFields = "items",
            ),
        )

        assertFalse(
            "the Bedrock product is published under the base sku too, so the label has to decide",
            verdict.ownsJavaEdition,
        )
    }

    @Test
    fun owningBothEditionsIsReportedAsTheProductThatWasMatched() {
        val verdict = decide(
            XCoreEntitlementRead.Listed(
                items = listOf(
                    item(skuName = "minecraft", displayName = "Minecraft Java Edition"),
                    item(skuName = "minecraftforwindows", displayName = "Minecraft for Windows"),
                ),
                documentFields = "items",
            ),
        )

        assertTrue(verdict.ownsJavaEdition)
        assertEquals(listOf("Minecraft Java Edition"), verdict.matchedProducts)
    }

    @Test
    fun anEmptyButWellFormedListMeansNoJavaEdition() {
        val verdict = decide(XCoreEntitlementRead.Listed(items = emptyList(), documentFields = "items"))

        assertFalse(verdict.ownsJavaEdition)
        assertEquals(XCoreEntitlementBasis.ENTITLEMENTS_LIST, verdict.basis)
    }

    @Test
    fun aRefusalNamesTheOtherProductWhenThereIsOne() {
        val verdict = decide(
            XCoreEntitlementRead.Listed(
                items = listOf(item(skuName = "minecraftforwindows", displayName = "Minecraft Bedrock for Xbox One")),
                documentFields = "items",
            ),
        )

        assertFalse(verdict.ownsJavaEdition)
        assertTrue(verdict.detail, verdict.detail.contains("Minecraft Bedrock for Xbox One"))
        assertTrue(verdict.detail, verdict.detail.contains("different edition"))
    }

    // -------------------------------------------------------------------- the document cannot say

    @Test
    fun aVanishedEntitlementDocumentFallsBackToTheProfileAndSaysSo() {
        val verdict = decide(XCoreEntitlementRead.EndpointUnavailable(status = 410))

        assertTrue("the account has a Java Edition profile, which is the remaining evidence", verdict.ownsJavaEdition)
        assertEquals(XCoreEntitlementBasis.PROFILE_ONLY, verdict.basis)
        assertTrue(verdict.detail, verdict.detail.contains("410"))
        assertTrue(verdict.detail, verdict.detail.contains("Badlion"))
        assertTrue(verdict.matchedProducts.isEmpty())
    }

    @Test
    fun aDocumentOfAnUnexpectedShapeFallsBackRatherThanRefusing() {
        val verdict = decide(XCoreEntitlementRead.Listed(items = emptyList(), documentFields = "error,message"))

        assertEquals(XCoreEntitlementBasis.PROFILE_ONLY, verdict.basis)
        assertTrue(verdict.detail, verdict.detail.contains("unexpected shape"))
        assertTrue(verdict.detail, verdict.detail.contains("error,message"))
    }

    @Test
    fun aProblemWithTheDocumentIsRefusedAndNeverReadAsAnAbsentLicence() {
        val timeout = XCoreError.Network(detail = "api.minecraftservices.com: read timed out", retryable = true)

        val problem = decideFailure(XCoreEntitlementRead.Problem(timeout))

        assertEquals("the endpoint's own failure is passed through unchanged", timeout, problem)
    }

    // -------------------------------------------------------------------- the endpoints wired up

    @Test
    fun aProfileAndAJavaEntitlementVerifyTogether() = runBlocking {
        val transport = ScriptedAuthTransport()
            .thenJson(AuthScript.PROFILE_URL, 200, AuthScript.PROFILE_JSON)
            .thenJson(AuthScript.ENTITLEMENTS_URL, 200, AuthScript.ENTITLEMENTS_JAVA)

        val ownership = checker(transport).verify(AuthScript.GAME_TOKEN).valueOrNull()
            ?: failTest("verification should succeed")

        assertEquals("Badlion", ownership.profile.name)
        assertEquals(AuthScript.UUID_COMPACT, ownership.profile.compactUuid)
        assertTrue(ownership.verdict.ownsJavaEdition)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun anUnavailableEntitlementEndpointStillLetsAProfileOwnerSignIn() = runBlocking {
        val transport = ScriptedAuthTransport()
            .thenJson(AuthScript.PROFILE_URL, 200, AuthScript.PROFILE_JSON)
            .then(AuthScript.ENTITLEMENTS_URL, XCoreAuthResponse(status = 404, body = ""))

        val verdict = checker(transport).verify(AuthScript.GAME_TOKEN).valueOrNull()?.verdict
            ?: failTest("the profile rule should decide")

        assertEquals(XCoreEntitlementBasis.PROFILE_ONLY, verdict.basis)
    }

    @Test
    fun anEntitlementOutageIsNotARefusalToLaunch() = runBlocking {
        val transport = ScriptedAuthTransport()
            .thenJson(AuthScript.PROFILE_URL, 200, AuthScript.PROFILE_JSON)
            .then(AuthScript.ENTITLEMENTS_URL, XCoreAuthResponse(status = 503, body = "", statusText = "Unavailable"))

        val problem = failure(checker(transport).readVerdict(AuthScript.GAME_TOKEN))

        assertEquals(XCoreAuthStage.ENTITLEMENT, (problem as XCoreError.Authentication).stage)
        assertTrue("a 503 is retryable, an absent licence is not", problem.retryable)
        assertFalse(
            "the launcher must not tell a person they own nothing because an endpoint was busy",
            problem.detail.contains("no Minecraft Java Edition product"),
        )
    }

    @Test
    fun aNotOwnedVerdictIsTurnedIntoTheFailureALaunchShows() = runBlocking {
        val transport = ScriptedAuthTransport()
            .thenJson(AuthScript.PROFILE_URL, 200, AuthScript.PROFILE_JSON)
            .thenJson(AuthScript.ENTITLEMENTS_URL, 200, AuthScript.ENTITLEMENTS_BEDROCK_ONLY)
            .thenJson(AuthScript.PROFILE_URL, 200, AuthScript.PROFILE_JSON)
            .thenJson(AuthScript.ENTITLEMENTS_URL, 200, AuthScript.ENTITLEMENTS_BEDROCK_ONLY)
        val subject = checker(transport)

        val problem = failure(subject.verify(AuthScript.GAME_TOKEN))
        val read = subject.readVerdict(AuthScript.GAME_TOKEN)

        assertEquals(XCoreErrorKind.AUTHENTICATION, problem.kind)
        assertTrue((problem as XCoreError.Authentication).requiresInteractiveLogin)
        assertTrue(problem.detail, problem.detail.contains("Minecraft for Windows"))
        assertTrue(
            "rechecking wants the verdict, not an exception, so it can store the answer",
            read.valueOrNull()?.verdict?.ownsJavaEdition == false,
        )
    }

    @Test
    fun anAccountWithNoProfileCannotBeLaunchedAs() = runBlocking {
        val transport = ScriptedAuthTransport()
            .then(AuthScript.PROFILE_URL, XCoreAuthResponse(status = 404, body = ""))

        val problem = failure(checker(transport).verify(AuthScript.GAME_TOKEN))

        assertEquals(XCoreAuthStage.ENTITLEMENT, (problem as XCoreError.Authentication).stage)
        assertTrue(problem.detail, problem.detail.contains("no Minecraft profile record"))
        assertEquals("nothing else is asked when there is no identity", 1, transport.requests.size)
    }

    @Test
    fun aProfileTheServicesRefuseToReturnIsRetriedNotConcluded() = runBlocking {
        val transport = ScriptedAuthTransport()
            .then(AuthScript.PROFILE_URL, XCoreAuthResponse(status = 500, body = "", statusText = "Server Error"))

        val problem = failure(checker(transport).verify(AuthScript.GAME_TOKEN))

        assertTrue((problem as XCoreError.Authentication).retryable)
        assertFalse(problem.requiresInteractiveLogin)
    }

    @Test
    fun aVerdictSummarisesItselfWithTheRuleThatProducedIt() {
        val owned = decide(XCoreEntitlementRead.Listed(items = listOf(item(skuName = "minecraft", displayName = "Minecraft Java Edition")), documentFields = "items"))
        val fallback = decide(XCoreEntitlementRead.EndpointUnavailable(status = null))
        val refused = decide(XCoreEntitlementRead.Listed(items = emptyList(), documentFields = "items"))

        assertTrue(owned.summarize(), owned.summarize().startsWith("owns Minecraft: Java Edition (entitlements list:"))
        assertTrue(fallback.summarize(), fallback.summarize().contains("profile only)"))
        assertTrue(refused.summarize(), refused.summarize().startsWith("does not own Minecraft: Java Edition"))
    }

    private fun failure(result: XCoreResult<*>): XCoreError =
        result.errorOrNull() ?: failTest("expected a typed failure, got ${result::class.simpleName}")
}
