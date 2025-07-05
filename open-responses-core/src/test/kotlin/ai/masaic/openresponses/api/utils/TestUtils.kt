package ai.masaic.openresponses.api.utils

import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.codec.multipart.FilePart
import org.springframework.web.multipart.MultipartFile
import reactor.core.publisher.Mono
import java.nio.file.Path

fun MultipartFile.toFilePart(): FilePart =
    object : FilePart {
        override fun name(): String = this@toFilePart.name

        override fun headers(): HttpHeaders = HttpHeaders()

        override fun filename(): String = this@toFilePart.originalFilename ?: "unknown"

        override fun transferTo(dest: Path): Mono<Void> = Mono.fromCallable { this@toFilePart.transferTo(dest.toFile()) }.then()

        override fun content() =
            DataBufferUtils.readInputStream(
                { this@toFilePart.inputStream },
                DefaultDataBufferFactory(),
                4096,
            )
    }
