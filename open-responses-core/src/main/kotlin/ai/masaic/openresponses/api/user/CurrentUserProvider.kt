package ai.masaic.openresponses.api.user

import org.springframework.stereotype.Component
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Profile

/**
 * Provider for accessing current user information in coroutines
 */
interface CurrentUserProvider {
    suspend fun getUser(): UserInfo?
    suspend fun getCurrentUserId(): String? = getUser()?.userId
}

@Profile("!platform")
@Component
class NoOpCurrentUserProvider: CurrentUserProvider {
    override suspend fun getUser(): UserInfo? {
        return null
    }
}

data class UserInfo(
    val userId: String
)
