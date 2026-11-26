package com.maxrave.kotlinytmusicscraper.models

import kotlinx.serialization.Serializable

@Serializable
data class Button(
    val buttonRenderer: ButtonRenderer? = null,
    val menuRenderer: Menu.MenuRenderer? = null,
) {
    @Serializable
    data class ButtonRenderer(
        val text: Runs? = null,
        val navigationEndpoint: NavigationEndpoint? = null,
        val command: NavigationEndpoint? = null,
        val icon: Icon? = null,
    )
}