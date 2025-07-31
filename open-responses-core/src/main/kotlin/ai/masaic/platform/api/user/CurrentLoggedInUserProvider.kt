package ai.masaic.platform.api.user

import ai.masaic.openresponses.api.user.CurrentUserProvider
import ai.masaic.openresponses.api.user.UserInfo
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.security.core.context.ReactiveSecurityContextHolder

class CurrentLoggedInUserProvider: CurrentUserProvider {

    /**
     * Get the current Google user information
     */
    override suspend fun getUser(): UserInfo? {
        return ReactiveSecurityContextHolder.getContext()
            .map { it.authentication.principal as? UserInfo }
            .awaitSingleOrNull()
    }
}

