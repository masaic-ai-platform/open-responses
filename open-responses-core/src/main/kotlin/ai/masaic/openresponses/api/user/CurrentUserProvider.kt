package ai.masaic.openresponses.api.user

import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.stereotype.Component
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/**
 * Provider for accessing current user information in coroutines
 */
interface CurrentUserProvider {
    suspend fun googleUser(): LoggedInUserInfo?
    suspend fun getCurrentUserId(): String? = googleUser()?.userId
}

@Component
@ConditionalOnMissingBean(CurrentUserProvider::class)
class NoOpCurrentUserProvider: CurrentUserProvider {
    override suspend fun googleUser(): LoggedInUserInfo? {
        return null
    }
}

data class LoggedInUserInfo(
    val userId: String
)
