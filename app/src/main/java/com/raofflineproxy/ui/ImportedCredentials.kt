package com.raofflineproxy.ui

sealed interface ImportedCredentials {
    val username: String

    data class Token(
        override val username: String,
        val token: String
    ) : ImportedCredentials

    data class Password(
        override val username: String,
        val password: String
    ) : ImportedCredentials
}
