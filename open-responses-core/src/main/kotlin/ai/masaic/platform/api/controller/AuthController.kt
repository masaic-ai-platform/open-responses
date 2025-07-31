package ai.masaic.platform.api.controller

import ai.masaic.openresponses.api.user.UserInfo
import ai.masaic.platform.api.user.auth.google.GoogleTokenVerifier
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@Profile("platform")
@RestController
@RequestMapping("/v1/dashboard/platform/auth")
@CrossOrigin("*")
class AuthController(
    private val googleTokenVerifier: GoogleTokenVerifier
) {

    @PostMapping("/verify")
    suspend fun verifyToken(@RequestBody request: TokenVerificationRequest): ResponseEntity<UserInfo> {
        val userInfo = when(request.authProvider) {
            "Google" -> googleTokenVerifier.verifyAsync(request.token).awaitSingle()
            else -> throw IllegalArgumentException("Auth provider ${request.authProvider} is not supported")
        }
        return ResponseEntity.ok(userInfo)
    }
}

data class TokenVerificationRequest(
    val token: String,
    val authProvider: String = "Google"
) 
