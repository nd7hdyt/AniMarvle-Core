package androidx.compose.runtime

/**
 * Shim for Compose's `@Stable` marker, used as a documentation annotation on the API models.
 * The vendored source-api is a plain Kotlin/Android module, so we reproduce the annotation
 * rather than pull in the Compose runtime.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION)
annotation class Stable