package com.tyvo.keyboard.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable

/**
 * The top bar of every screen below History: the standard Up arrow and the
 * screen's title. Up does what system Back does here, because every screen
 * is reached from the one above it; the arrow is for people who never use
 * the gesture. Auto-mirrored, so it points the right way in RTL languages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubScreenBar(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = actions,
    )
}
