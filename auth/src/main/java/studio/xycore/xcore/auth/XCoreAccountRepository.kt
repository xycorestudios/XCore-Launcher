package studio.xycore.xcore.auth

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import studio.xycore.xcore.core.XCoreAuthStage
import studio.xycore.xcore.core.XCoreError
import studio.xycore.xcore.core.XCoreErrorKind
import studio.xycore.xcore.core.XCoreLogLevel
import studio.xycore.xcore.core.XCoreLogger
import studio.xycore.xcore.core.XCoreLogTags
import studio.xycore.xcore.core.XCoreResult
import studio.xycore.xcore.core.asXCoreFailure
import studio.xycore.xcore.core.asXCoreSuccess
import studio.xycore.xcore.core.failXCore
import studio.xycore.xcore.core.flatMap
import studio.xycore.xcore.core.getOrThrow
import studio.xycore.xcore.core.map
import studio.xycore.xcore.core.xCoreRun

/**
 * Everything the launcher knows about the accounts on this device.
 *
 * The vault is the only store: there is no in-memory copy that could disagree with the file, so a
 * read always reflects what would survive a process death. The cost is a decryption per read, which
 * is the right trade for a screen that shows a handful of accounts.
 *
 * [cache] holds the last value that was successfully read, purely so the UI can paint a state while
 * a read is in flight. It is never treated as truth: an account missing from the cache but present
 * on disk still has to be read before it can be used.
 */
class XCoreAccountRepository(
    private val store: XCoreTokenStore,
    private val logger: XCoreLogger? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newClientToken: () -> String = { UUID.randomUUID().toString() },
) {

    private val cache = MutableStateFlow<XCoreAuthVault?>(null)

    /** The last vault that was read, or `null` when nothing has been read yet. */
    val lastRead: StateFlow<XCoreAuthVault?> = cache.asStateFlow()

    val location: String get() = store.location

    val protection: String get() = store.protection

    suspend fun vault(): XCoreResult<XCoreAuthVault> = store.read().map { read ->
        cache.value = read
        read
    }

    suspend fun summaries(): XCoreResult<List<XCoreAccountSummary>> =
        vault().map { vault -> vault.accounts.map { it.summary() } }

    suspend fun activeAccount(): XCoreResult<XCoreStoredAccount?> =
        vault().map { it.activeAccount() }

    suspend fun account(id: String): XCoreResult<XCoreStoredAccount?> =
        vault().map { it.account(id) }

    suspend fun pendingChallenge(): XCoreResult<XCoreDeviceCodeChallenge?> = vault().flatMap { vault ->
        val challenge = vault.pendingChallenge ?: return@flatMap null.asXCoreSuccess()
        if (!challenge.isStaleAt(clock())) {
            challenge.asXCoreSuccess()
        } else {
            // A spent challenge is cleared on the way past, so a restart cannot resume polling
            // against a code Microsoft has already thrown away.
            dropChallenge(PENDING_EXPIRED).flatMap { null.asXCoreSuccess() }
        }
    }

    /**
     * The per-install value the game receives as `auth_session`.
     *
     * Generated once and then kept: a launch that used a different one each time would look to the
     * services like a new client on every start.
     */
    suspend fun clientToken(): XCoreResult<String> = vault().flatMap { current ->
        val existing = current.clientToken?.takeIf { it.isNotBlank() }
        if (existing != null) {
            existing.asXCoreSuccess()
        } else {
            store.update { it.copy(clientToken = newClientToken()) }
                .flatMap { written ->
                    val generated = written.clientToken?.takeIf { it.isNotBlank() }
                        ?: return@flatMap XCoreError.Authentication(
                            detail = "the client token was written but is not readable back from the vault",
                            stage = XCoreAuthStage.TOKEN_STORAGE,
                        ).asXCoreFailure()
                    generated.asXCoreSuccess()
                }
        }
    }

    /**
     * Records a signed-in account and makes it active.
     *
     * The id is the profile uuid when the services gave one; before a profile exists it is a fresh
     * random value rather than something derived from a token, because a partial credential printed
     * as an identifier is still a partial credential.
     */
    suspend fun recordSignedIn(
        tokens: XCoreMicrosoftTokens,
        gameToken: String,
        gameTokenExpiresAtMillis: Long,
        profile: XCoreMinecraftProfile?,
        verdict: XCoreEntitlementVerdict?,
        previousAccountId: String? = null,
    ): XCoreResult<XCoreStoredAccount> = store.update { current ->
        val now = clock()
        val id = profile?.uuid?.replace("-", "")
            ?: previousAccountId
            ?: "ms-" + UUID.randomUUID().toString().replace("-", "")
        val account = XCoreStoredAccount(
            id = id,
            kind = XCoreAccountKind.MICROSOFT,
            profile = profile,
            accessToken = gameToken,
            accessTokenExpiresAtMillis = gameTokenExpiresAtMillis,
            refreshToken = tokens.refreshToken,
            clientToken = current.clientToken ?: newClientToken(),
            addedAtMillis = current.account(id)?.addedAtMillis ?: now,
            ownsJavaEdition = verdict?.ownsJavaEdition == true,
            entitlementBasis = verdict?.basis,
            lastVerifiedAtMillis = verdict?.checkedAtMillis,
            requiresInteractiveLogin = false,
            lastProblem = verdict?.detail?.takeIf { !verdict.ownsJavaEdition }
                ?: microsoftRefreshNote(tokens),
        )
        current.copy(
            activeAccountId = account.id,
            accounts = current.accounts.filterNot { it.id == account.id } + account,
            pendingChallenge = null,
        )
    }.flatMap { written ->
        (written.activeAccountId?.let(written::account))?.asXCoreSuccess()
            ?: XCoreError.Authentication(
                detail = "the account was written to the vault but cannot be read back",
                stage = XCoreAuthStage.TOKEN_STORAGE,
            ).asXCoreFailure()
    }

    /** Applies refreshed game tokens to a stored account and returns what is now on disk. */
    suspend fun applyRefresh(
        accountId: String,
        tokens: XCoreMicrosoftTokens,
        gameToken: String,
        gameTokenExpiresAtMillis: Long,
    ): XCoreResult<XCoreStoredAccount> = store.update { current ->
        val existing = current.account(accountId) ?: failXCore(
            XCoreError.Authentication(
                detail = "a refreshed token arrived for '$accountId', which is no longer in the vault",
                stage = XCoreAuthStage.TOKEN_STORAGE,
                requiresInteractiveLogin = true,
            ),
        )
        current.replacedWith(
            existing.copy(
                accessToken = gameToken,
                accessTokenExpiresAtMillis = gameTokenExpiresAtMillis,
                refreshToken = tokens.refreshToken ?: existing.refreshToken,
                requiresInteractiveLogin = false,
                lastProblem = renewalNote(tokens, existing),
                lastVerifiedAtMillis = clock(),
            ),
        )
    }.flatMap { vault ->
        (vault.account(accountId))?.asXCoreSuccess()
            ?: XCoreError.Authentication(
                detail = "the refreshed token for '$accountId' was written but cannot be read back",
                stage = XCoreAuthStage.TOKEN_STORAGE,
            ).asXCoreFailure()
    }

    suspend fun markNeedsInteractive(accountId: String, problem: String): XCoreResult<Unit> =
        store.update { current ->
            val existing = current.account(accountId) ?: return@update current
            current.replacedWith(existing.copy(requiresInteractiveLogin = true, lastProblem = problem))
        }.map { }

    suspend fun rememberVerdict(accountId: String, verdict: XCoreEntitlementVerdict): XCoreResult<Unit> =
        store.update { current ->
            val existing = current.account(accountId) ?: return@update current
            current.replacedWith(existing.withVerdict(verdict, clock()))
        }.map { }

    suspend fun setActive(accountId: String): XCoreResult<Unit> = store.update { current ->
        if (current.account(accountId) == null) {
            failXCore(
                XCoreError.Authentication(
                    detail = "there is no stored account with id '$accountId' to switch to",
                    stage = XCoreAuthStage.TOKEN_STORAGE,
                ),
            )
        }
        current.copy(activeAccountId = accountId)
    }.map { }

    /**
     * Removes an account, including its refresh token.
     *
     * This is a local erase only. Revoking the grant on Microsoft's side means hitting their revoke
     * endpoint, which a launcher cannot do on the person's behalf without telling them; the caller
     * is expected to say so rather than imply the account is signed out everywhere.
     */
    suspend fun remove(accountId: String): XCoreResult<Unit> = store.update { current ->
        if (current.account(accountId) == null) {
            failXCore(
                XCoreError.Authentication(
                    detail = "there is no stored account with id '$accountId' to remove",
                    stage = XCoreAuthStage.TOKEN_STORAGE,
                ),
            )
        }
        current.without(accountId)
    }.map {
        logger?.log(XCoreLogLevel.WARN, XCoreLogTags.AUTH, "account '$accountId' removed from the vault")
    }

    suspend fun saveChallenge(challenge: XCoreDeviceCodeChallenge?): XCoreResult<Unit> =
        store.update { it.copy(pendingChallenge = challenge) }.map { }

    private suspend fun dropChallenge(reason: String): XCoreResult<Unit> =
        store.update { it.copy(pendingChallenge = null) }.map {
            logger?.log(XCoreLogLevel.INFO, XCoreLogTags.AUTH, "pending sign-in cleared: $reason")
        }

    /** Wipes the whole vault file, used by the "forget everything" path and by tests. */
    suspend fun clearAll(): XCoreResult<Unit> = store.clear().map {
        cache.value = null
        Unit
    }

    /**
     * A refresh token is not returned by every Microsoft response - a device-code exchange can
     * reuse consent granted earlier - so its absence is recorded rather than assumed harmless.
     */
    private fun microsoftRefreshNote(tokens: XCoreMicrosoftTokens): String? =
        if (tokens.refreshToken.isNullOrBlank()) {
            "Microsoft returned no refresh token for this sign-in, so it will ask again when the game token expires"
        } else {
            null
        }

    /**
     * A renewal that hands back no new refresh token is not a problem while the stored one is still
     * there to use, so a warning here only belongs on the branch that leaves the account with
     * nothing to renew with.
     */
    private fun renewalNote(tokens: XCoreMicrosoftTokens, stored: XCoreStoredAccount): String? = when {
        !tokens.refreshToken.isNullOrBlank() -> null
        !stored.refreshToken.isNullOrBlank() -> null
        else -> "the renewal returned no refresh token and this account holds none, so it has to sign in " +
            "again when the game token expires"
    }

    companion object {
        const val PENDING_EXPIRED = "the code expired before it was approved"
    }
}

/** A status snapshot the UI can paint without touching a credential. */
data class XCoreAuthStatus(
    val configured: Boolean,
    val accounts: List<XCoreAccountSummary>,
    val activeAccount: XCoreAccountSummary?,
    val awaitingApproval: XCoreSignInPrompt?,
    val protection: String,
    val problem: XCoreError?,
    val nowMillis: Long,
) {
    val signedIn: Boolean get() = activeAccount != null

    fun rows(): List<Pair<String, String>> = buildList {
        add("app registration" to if (configured) "configured" else "not configured")
        add("credential storage" to protection)
        add("accounts held" to accounts.size.toString())
        add(
            "active account" to (activeAccount?.let { account ->
                "${account.displayName} (uuid ${account.uuid}, " +
                    (if (account.ownsJavaEdition) "Java Edition held" else "Java Edition not confirmed") + ")"
            } ?: "none")
        )
        add("awaiting approval" to (awaitingApproval?.let { "${it.userCode} at ${it.verificationUri}" } ?: "no"))
        problem?.let { add("last problem" to it.format()) }
    }
}
