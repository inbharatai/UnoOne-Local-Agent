package com.unoone.agent.core.model

sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val message: String, val cause: Throwable? = null) : Result<Nothing>()

    /** Returns true if this is a Success result. */
    val isSuccess: Boolean get() = this is Success
    val isError: Boolean get() = this is Error
}

inline fun <T> Result<T>.onSuccess(action: (T) -> Unit): Result<T> {
    if (this is Result.Success) action(data)
    return this
}

inline fun <T> Result<T>.onError(action: (String, Throwable?) -> Unit): Result<T> {
    if (this is Result.Error) action(message, cause)
    return this
}

/**
 * Maps a Success result to a new type. Error results pass through unchanged.
 * If the transform throws, the exception is caught and returned as Result.Error.
 */
inline fun <T, R> Result<T>.mapCatching(transform: (T) -> R): Result<R> {
    return when (this) {
        is Result.Success -> {
            try {
                Result.Success(transform(data))
            } catch (e: Exception) {
                Result.Error("Mapping failed: ${e.message}", e)
            }
        }
        is Result.Error -> Result.Error(message, cause)
    }
}

/**
 * Maps a Success result to a new type. Error results pass through unchanged.
 * Unlike mapCatching, this does NOT catch exceptions from the transform.
 */
inline fun <T, R> Result<T>.map(transform: (T) -> R): Result<R> {
    return when (this) {
        is Result.Success -> Result.Success(transform(data))
        is Result.Error -> this
    }
}

/**
 * FlatMaps a Success result to a new Result. Error results pass through unchanged.
 * If the transform throws, the exception is caught and returned as Result.Error.
 */
inline fun <T, R> Result<T>.flatMapCatching(transform: (T) -> Result<R>): Result<R> {
    return when (this) {
        is Result.Success -> {
            try {
                transform(data)
            } catch (e: Exception) {
                Result.Error("FlatMap failed: ${e.message}", e)
            }
        }
        is Result.Error -> this
    }
}

/**
 * Returns the data if Success, or the default value if Error.
 */
fun <T> Result<T>.getOrDefault(default: T): T {
    return when (this) {
        is Result.Success -> data
        is Result.Error -> default
    }
}

/**
 * Returns the data if Success, or null if Error.
 */
fun <T> Result<T>.getOrNull(): T? {
    return when (this) {
        is Result.Success -> data
        is Result.Error -> null
    }
}

/**
 * Returns the error message if Error, or null if Success.
 */
fun Result<*>.errorOrNull(): String? {
    return when (this) {
        is Result.Success -> null
        is Result.Error -> message
    }
}