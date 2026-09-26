package com.vcttracker.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.vcttracker.VctApp
import com.vcttracker.data.Loaded
import com.vcttracker.data.Repository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException

sealed interface Ui<out T> {
    data object Loading : Ui<Nothing>
    data class Failed(val message: String) : Ui<Nothing>
    data class Ready<T>(val data: Loaded<T>) : Ui<T>
}

class LoadHandle<T>(
    val state: Ui<T>,
    val refreshing: Boolean,
    val refresh: () -> Unit,
)

@Composable
fun repository(): Repository = (LocalContext.current.applicationContext as VctApp).repository

/**
 * Loads data for a screen, keeps the last good value while refreshing, and optionally
 * polls while the screen is visible (used for live matches).
 */
@Composable
fun <T> rememberLoad(
    vararg keys: Any?,
    pollSeconds: (T) -> Long? = { null },
    fetch: suspend Repository.(force: Boolean) -> Loaded<T>,
): LoadHandle<T> {
    val repo = repository()
    var state by remember(*keys) { mutableStateOf<Ui<T>>(Ui.Loading) }
    var refreshing by remember(*keys) { mutableStateOf(false) }
    var trigger by remember(*keys) { mutableIntStateOf(0) }

    LaunchedEffect(*keys, trigger) {
        val force = trigger > 0
        if (force) refreshing = true
        try {
            state = Ui.Ready(repo.fetch(force))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (state !is Ui.Ready) state = Ui.Failed(friendly(e))
        } finally {
            refreshing = false
        }
    }

    val current = state
    val interval = (current as? Ui.Ready)?.data?.value?.let(pollSeconds)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(*keys, interval) {
        if (interval == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(interval * 1000)
                try {
                    state = Ui.Ready(repo.fetch(true))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Keep showing the last good data; the next tick retries.
                }
            }
        }
    }

    return LoadHandle(current, refreshing) { trigger++ }
}

private fun friendly(e: Exception): String = when (e) {
    is java.net.UnknownHostException -> "You're offline, and nothing is saved for this page yet."
    is java.net.SocketTimeoutException -> "The source took too long to answer."
    is IOException -> e.message?.let { "Couldn't reach the source ($it)." } ?: "Couldn't reach the source."
    else -> "This page changed shape and couldn't be read."
}
