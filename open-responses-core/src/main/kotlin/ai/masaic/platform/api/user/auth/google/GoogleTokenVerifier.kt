package ai.masaic.platform.api.user.auth.google

import ai.masaic.openresponses.api.user.UserInfo
import ai.masaic.platform.api.user.GoogleAuthConfig
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import org.springframework.security.authentication.BadCredentialsException
import reactor.core.publisher.Mono
import java.util.*
import java.util.concurrent.CompletableFuture

/**
 * Verifies Google ID tokens using Google's official SDK
 */
class GoogleTokenVerifier(
    private val googleAuthConfig: GoogleAuthConfig
) {

    private val verifier = GoogleIdTokenVerifier.Builder(NetHttpTransport(), GsonFactory())
        .setAudience(Collections.singletonList(googleAuthConfig.audience))
        .build()

    fun verifyAsync(token: String): Mono<UserInfo> =
        Mono.fromFuture(CompletableFuture.supplyAsync {
                val idToken = verifier.verify(token)
                    ?: throw BadCredentialsException("Invalid Google token")

                val payload = idToken.payload
                UserInfo(
                    userId = payload.email,
                )
        })
        .onErrorMap { BadCredentialsException("Invalid Google token", it) }
} 
