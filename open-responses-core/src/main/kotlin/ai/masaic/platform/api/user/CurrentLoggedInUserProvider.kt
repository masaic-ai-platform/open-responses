package ai.masaic.platform.api.user

import ai.masaic.openresponses.api.user.CurrentUserProvider
import ai.masaic.openresponses.api.user.LoggedInUserInfo
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.security.core.context.ReactiveSecurityContextHolder

class CurrentLoggedInUserProvider: CurrentUserProvider {

    /**
     * Get the current Google user information
     */
    override suspend fun googleUser(): LoggedInUserInfo? =
        ReactiveSecurityContextHolder.getContext()
            .map { it.authentication.principal as? LoggedInUserInfo }
            .awaitSingleOrNull()
}

