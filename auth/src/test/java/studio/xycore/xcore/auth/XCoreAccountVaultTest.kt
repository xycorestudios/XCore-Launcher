package studio.xycore.xcore.auth

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import studio.xycore.xcore.core.XCoreAuthStage
import studio.xycore.xcore.core.XCoreError
import studio.xycore.xcore.core.XCoreErrorKind
import studio.xycore.xcore.core.XCoreFailureException
import studio.xycore.xcore.core.XCoreResult
import studio.xycore.xcore.core.asXCoreFailure
import studio.xycore.xcore.core.errorOrNull
import studio.xycore.xcore.core.isFailure
import studio.xycore.xcore.core.isSuccess
import studio.xycore.xcore.core.valueOrNull

/**
 * The account vault: what it keeps, what it refuses to read, and what it never writes.
 *
 * The rules under test are the ones that decide whether a person's stored accounts survive a bad
 * file or a refused write. An unreadable vault is a failure, never "you have no accounts" - because
 * the next sign-in after a fake-empty read would overwrite the accounts that could not be opened.
 */
class XCoreAccountVaultTest {

    @JvmField
    @Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private val cipher = MarkingTestCipher()

    private fun vaultFile(name: String = "vault.bin"): File = File(temp.root, name)

    private fun store(file: File = vaultFile()) = XCoreTokenStore(file = file, cipher = cipher)

    private fun account(
        id: String = "8f5e5a0b1c2d3e4f5a6b7c8d9e0f1a2b",
        token: String = AuthScript.GAME_TOKEN,
        refresh: String? = AuthScript.MS_REFRESH,
    ) = XCoreStoredAccount(
        id = id,
        kind = XCoreAccountKind.MICROSOFT,
        profile = XCoreMinecraftProfile(uuid = AuthScript.UUID_COMPACT, name = "Badlion"),
        accessToken = token,
        accessTokenExpiresAtMillis = AuthScript.NOW_MILLIS + 86_400_000L,
        refreshToken = refresh,
        clientToken = "client-token-value",
        addedAtMillis = AuthScript.NOW_MILLIS,
        ownsJavaEdition = true,
        entitlementBasis = XCoreEntitlementBasis.ENTITLEMENTS_LIST,
        lastVerifiedAtMillis = AuthScript.NOW_MILLIS,
    )

    private val challenge = XCoreDeviceCodeChallenge(
        clientId = AuthScript.CLIENT_ID,
        deviceCode = "device-code-secret",
        userCode = "ABC-123",
        verificationUri = "https://microsoft.com/devicelogin",
        instructions = "open the page and type the code",
        intervalSeconds = 5,
        expiresAtMillis = AuthScript.NOW_MILLIS + 900_000L,
    )

    private fun writeRaw(file: File, json: String) {
        file.writeBytes(cipher.encrypt(json.toByteArray(Charsets.UTF_8)).valueOrNull() ?: failTest("the test cipher cannot mark"))
    }

    // ------------------------------------------------------------- what it keeps

    @Test
    fun anAbsentFileIsAnEmptyVaultAndNotAFailure() = runBlocking {
        val read = store().read()

        assertTrue(read.isSuccess)
        assertEquals(XCoreAuthVault(), read.valueOrNull())
    }

    @Test
    fun everyFieldSurvivesAWriteAndRead() = runBlocking {
        val file = vaultFile()
        val written = XCoreAuthVault(
            activeAccountId = account().id,
            accounts = listOf(account()),
            pendingChallenge = challenge,
            clientToken = "installed-client-token",
        )

        assertEquals(Unit, store(file).write(written).valueOrNull())
        val readBack = store(file).read().valueOrNull() ?: failTest("the vault could not be read back")

        assertEquals(written, readBack)
        assertEquals("device-code-secret", readBack.pendingChallenge?.deviceCode)
        assertEquals(AuthScript.MS_REFRESH, readBack.accounts.single().refreshToken)
    }

    @Test
    fun theStoreReportsWhatProtectsIt() {
        assertEquals(cipher.description, store().protection)
        assertEquals(vaultFile("other.bin").absolutePath, store(vaultFile("other.bin")).location)
    }

    @Test
    fun anUpdateThatRefusesLeavesNoFileBehind() = runBlocking {
        val file = vaultFile("refused.bin")

        val outcome = store(file).update {
            throw XCoreFailureException(
                XCoreError.Authentication(
                    detail = "the test rule refuses this vault",
                    stage = XCoreAuthStage.TOKEN_STORAGE,
                ),
            )
        }

        assertEquals("the test rule refuses this vault", outcome.errorOrNull()?.detail)
        assertFalse("a refused update must not create or rewrite the vault", file.exists())
    }

    @Test
    fun aWriteThatTheCipherRefusesNeverTouchesTheDisk() = runBlocking {
        val file = vaultFile("never-written.bin")
        val refusing = object : XCoreSecretCipher {
            override val description: String get() = "cipher that refuses"

            override fun encrypt(plaintext: ByteArray): XCoreResult<ByteArray> =
                XCoreError.Authentication(
                    detail = "the keystore key is gone",
                    stage = XCoreAuthStage.TOKEN_STORAGE,
                ).asXCoreFailure()

            override fun decrypt(envelope: ByteArray): XCoreResult<ByteArray> = encrypt(envelope)
        }

        val outcome = XCoreTokenStore(file = file, cipher = refusing).write(XCoreAuthVault(accounts = listOf(account())))

        assertEquals(XCoreErrorKind.AUTHENTICATION, outcome.errorOrNull()?.kind)
        assertFalse("a vault that could not be encrypted must not reach the disk", file.exists())
    }

    // ------------------------------------------------------------- what it refuses to read

    @Test
    fun aFileTheCipherDidNotWriteIsAFailureAndNotAnEmptyVault() = runBlocking {
        val file = vaultFile("foreign.bin")
        file.writeBytes("""{"accounts":[]}""".toByteArray(Charsets.UTF_8))

        val problem = store(file).read().errorOrNull() ?: failTest("a foreign vault file must not read as empty")

        assertEquals(XCoreErrorKind.AUTHENTICATION, problem.kind)
        assertEquals(XCoreAuthStage.TOKEN_STORAGE, (problem as XCoreError.Authentication).stage)
    }

    @Test
    fun decryptedNonsenseIsReportedAsCorruption() = runBlocking {
        val file = vaultFile("nonsense.bin")
        writeRaw(file, "{ this is not json")

        val problem = store(file).read().errorOrNull() ?: failTest("corrupt JSON must be reported")

        assertTrue(problem.detail, problem.detail.contains("not JSON") || problem.detail.contains("not an object"))
    }

    @Test
    fun aVaultFromANewerLauncherIsRefusedRatherThanRewritten() = runBlocking {
        val file = vaultFile("future.bin")
        val future = XCoreAuthVault(version = 99, activeAccountId = account().id, accounts = listOf(account()))
        writeRaw(file, XCoreVaultCodec.encode(future))
        val bytesOnDisk = file.readBytes()

        val problem = store(file).read().errorOrNull() ?: failTest("a newer format must be refused")
        assertTrue(problem.detail, problem.detail.contains("newer launcher"))

        // Signing in writes through update(), which reads first: a read this build cannot satisfy
        // must not be allowed to replace the file it failed to understand.
        assertTrue(store(file).update { it.copy(activeAccountId = "whoever") }.isFailure)
        assertArrayEquals("the refused vault must still hold every account it could not parse", bytesOnDisk, file.readBytes())
    }

    @Test
    fun anAccountMissingARequiredFieldNamesTheField() = runBlocking {
        val file = vaultFile("missing-field.bin")
        writeRaw(
            file,
            """
                {"version":1,"activeAccountId":"a","accounts":[{"id":"a","kind":"MICROSOFT","accessToken":"t","accessTokenExpiresAtMillis":1,"addedAtMillis":1,"ownsJavaEdition":true,"requiresInteractiveLogin":false}]}
            """.trimIndent(),
        )

        val problem = store(file).read().errorOrNull() ?: failTest("an account with no client token must be refused")

        assertTrue(problem.detail, problem.detail.contains("clientToken"))
    }

    @Test
    fun anUnknownAccountKindIsRefusedRatherThanGuessed() = runBlocking {
        val file = vaultFile("legacy-kind.bin")
        writeRaw(
            file,
            """
                {"version":1,"accounts":[${accountJson(kind = "MOJANG")}],"clientToken":"c"}
            """.trimIndent(),
        )

        val problem = store(file).read().errorOrNull() ?: failTest("Yggdrasil is retired; a build must not pretend otherwise")

        assertTrue(problem.detail, problem.detail.contains("MOJANG"))
    }

    private fun accountJson(kind: String = "MICROSOFT"): String =
        """{"id":"a","kind":"$kind","profile":{"uuid":"${AuthScript.UUID_COMPACT}","name":"Badlion"},""" +
            """"accessToken":"t","accessTokenExpiresAtMillis":1,"clientToken":"c","addedAtMillis":1,""" +
            """"ownsJavaEdition":true,"requiresInteractiveLogin":false}"""

    // ------------------------------------------------------------- what it forgets

    @Test
    fun clearingDeletesTheFile() = runBlocking {
        val file = vaultFile("clear.bin")
        store(file).write(XCoreAuthVault(accounts = listOf(account())))
        assertTrue(file.exists())

        assertTrue(store(file).clear().isSuccess)

        assertFalse(file.exists())
        assertFalse("no temp file may be left next to the vault", File(file.parentFile, file.name + ".tmp").exists())
    }

    @Test
    fun clearingAnAbsentVaultIsNotAnError() = runBlocking {
        assertTrue(store(vaultFile("never-existed.bin")).clear().isSuccess)
    }

    @Test
    fun aRepositoryRemovalTakesTheRefreshTokenWithIt() = runBlocking {
        val file = vaultFile("repo-remove.bin")
        val accounts = XCoreAccountRepository(store(file), clock = { AuthScript.NOW_MILLIS })
        accounts.recordSignedIn(
            tokens = XCoreMicrosoftTokens(AuthScript.MS_ACCESS, AuthScript.MS_REFRESH, AuthScript.NOW_MILLIS + 3_600_000L),
            gameToken = AuthScript.GAME_TOKEN,
            gameTokenExpiresAtMillis = AuthScript.NOW_MILLIS + 86_400_000L,
            profile = XCoreMinecraftProfile(uuid = AuthScript.UUID_COMPACT, name = "Badlion"),
            verdict = verdict(),
        ).getOrThrowForTest()
        assertTrue(file.readBytes().decodeToString().contains(AuthScript.MS_REFRESH))

        accounts.remove(AuthScript.UUID_COMPACT).getOrThrowForTest()

        val remaining = store(file).read().valueOrNull() ?: failTest("the vault must still be readable")
        assertEquals("removing the account removes its tokens, not just its row", emptyList<XCoreStoredAccount>(), remaining.accounts)
        assertFalse(file.readBytes().decodeToString().contains(AuthScript.MS_REFRESH))
    }

    private fun verdict() = XCoreEntitlementVerdict(
        ownsJavaEdition = true,
        basis = XCoreEntitlementBasis.ENTITLEMENTS_LIST,
        matchedProducts = listOf("Minecraft Java Edition"),
        checkedAtMillis = AuthScript.NOW_MILLIS,
        detail = "the entitlement document lists 1 Java Edition product(s)",
    )

    private fun <T> studio.xycore.xcore.core.XCoreResult<T>.getOrThrowForTest(): T =
        valueOrNull() ?: failTest("expected success, got ${errorOrNull()?.format()}")
}
