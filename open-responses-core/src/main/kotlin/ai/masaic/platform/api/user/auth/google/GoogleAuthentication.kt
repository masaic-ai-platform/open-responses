package ai.masaic.platform.api.user.auth.google

import ai.masaic.openresponses.api.user.LoggedInUserInfo
import org.springframework.security.authentication.AbstractAuthenticationToken

/**
 * Pre-authentication token containing Google ID token
 */
class GooglePreAuthToken(val credentials: String) : AbstractAuthenticationToken(emptyList()) {
    override fun getCredentials(): Any = credentials
    override fun getPrincipal(): Any = credentials
}

/**
 * Authenticated Google user token
 */
class GoogleAuthentication(private val userInfo: LoggedInUserInfo) : AbstractAuthenticationToken(emptyList()) {
    override fun getCredentials(): Any? = null
    override fun getPrincipal(): Any = userInfo
    
    init {
        isAuthenticated = true
    }
} 
