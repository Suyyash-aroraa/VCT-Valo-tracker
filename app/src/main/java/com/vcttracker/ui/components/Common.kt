package com.vcttracker.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import com.vcttracker.data.Loaded
import com.vcttracker.data.MatchStatus
import com.vcttracker.ui.theme.ChamferSmall
import com.vcttracker.ui.theme.Vct
import com.vcttracker.ui.theme.VctIcons
import java.util.Locale

/** Screen-to-screen navigation, provided once at the root. */
interface Navigator {
    fun event(id: String)
    fun match(id: String)
    fun team(id: String?)
    fun player(id: String?)
    fun model()
    fun back()
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("Navigator not provided") }

/** Screenshot tests hand images over synchronously here; the app leaves it empty and uses Coil. */
val LocalStaticImages = staticCompositionLocalOf<(String) -> ImageBitmap?> { { null } }

@Composable
fun RemoteImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    if (url == null) return
    val static = LocalStaticImages.current(url)
    if (static != null) {
        Image(static, contentDescription, modifier, contentScale = contentScale)
    } else {
        AsyncImage(url, contentDescription, modifier, contentScale = contentScale)
    }
}

// ------------------------------------------------------------------ images

@Composable
fun TeamLogo(url: String?, name: String, size: Dp, modifier: Modifier = Modifier) {
    val c = Vct.colors
    val fallback = @Composable {
        Box(
            modifier.size(size).background(c.surfaceAlt, ChamferSmall),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                monogram(name),
                style = Vct.type.title.copy(fontSize = (size.value * 0.36f).coerceAtLeast(9f).sp, lineHeight = (size.value * 0.4f).sp),
                color = c.muted,
            )
        }
    }
    if (url == null) {
        fallback()
        return
    }
    val static = LocalStaticImages.current(url)
    if (static != null) {
        Image(static, null, modifier.size(size), contentScale = ContentScale.Fit)
        return
    }
    SubcomposeAsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier.size(size),
        loading = { Box(Modifier.size(size).background(c.surfaceAlt.copy(alpha = 0.5f), ChamferSmall)) },
        error = { fallback() },
    )
}

private fun monogram(name: String): String {
    val words = name.split(' ', '-').filter { it.isNotBlank() }
    return when {
        name == "TBD" || words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(2).uppercase(Locale.US)
        else -> words.take(2).joinToString("") { it.take(1) }.uppercase(Locale.US)
    }
}

/** Country as a boxed two-letter code: reads like a broadcast lower-third, not an emoji. */
@Composable
fun CountryTag(code: String?, modifier: Modifier = Modifier) {
    if (code.isNullOrBlank() || code == "un") return
    val c = Vct.colors
    Text(
        code.uppercase(Locale.US).take(3),
        style = Vct.type.label.copy(fontSize = Vct.type.label.fontSize * 0.85f, letterSpacing = Vct.type.label.letterSpacing * 0.5f),
        color = c.muted,
        modifier = modifier
            .border(1.dp, c.line)
            .padding(horizontal = 3.dp, vertical = 0.dp),
    )
}

// ------------------------------------------------------------------ text

@Composable
fun MonoLabel(text: String, modifier: Modifier = Modifier, color: Color = Vct.colors.muted) {
    Text(text.uppercase(Locale.getDefault()), style = Vct.type.label, color = color, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** "LIVE ———— 3" : a mono label followed by a hairline that runs to the edge. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    trailing: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val c = Vct.colors
    Row(
        modifier.fillMaxWidth().padding(top = 28.dp, bottom = 10.dp).semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(8.dp))
        }
        MonoLabel(title, color = accent ?: c.ink)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(c.line))
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            MonoLabel(trailing)
        }
    }
}

@Composable
fun LiveDot(size: Dp = 8.dp) {
    val t = rememberInfiniteTransition(label = "live")
    val a by t.animateFloat(1f, 0.25f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(size).alpha(a).background(Vct.colors.spike, CircleShape))
}

@Composable
fun StatusTag(status: MatchStatus, modifier: Modifier = Modifier) {
    val c = Vct.colors
    when (status) {
        MatchStatus.LIVE -> Row(
            modifier.background(c.spike, ChamferSmall).padding(horizontal = 7.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("LIVE", style = Vct.type.label, color = c.onSpike)
        }
        MatchStatus.UPCOMING -> Text(
            "SOON", style = Vct.type.label, color = c.ink,
            modifier = modifier.border(1.dp, c.ink).padding(horizontal = 6.dp, vertical = 1.dp),
        )
        MatchStatus.COMPLETED -> Text(
            "DONE", style = Vct.type.label, color = c.faint,
            modifier = modifier.border(1.dp, c.line).padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

// ------------------------------------------------------------------ controls

@Composable
fun ChoiceRow(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp),
) {
    val c = Vct.colors
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .minimumInteractiveComponentSize()
                    .clip(ChamferSmall)
                    .background(if (on) c.ink else Color.Transparent, ChamferSmall)
                    .border(BorderStroke(1.dp, if (on) c.ink else c.line), ChamferSmall)
                    .clickable(role = Role.Tab) { onSelect(i) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(label.uppercase(Locale.getDefault()), style = Vct.type.label, color = if (on) c.background else c.muted)
            }
        }
    }
}

/** Big condensed years; the selected one is ink, the rest recede. */
@Composable
fun YearPicker(years: List<Int>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Vct.colors
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        years.forEach { y ->
            val on = y == selected
            Column(
                Modifier
                    .clickable(role = Role.Tab) { onSelect(y) }
                    .semantics { contentDescription = "Season $y" + if (on) ", selected" else "" }
                    .padding(vertical = 4.dp),
            ) {
                Text(
                    y.toString(),
                    style = Vct.type.display.copy(fontSize = if (on) Vct.type.display.fontSize else Vct.type.title.fontSize),
                    color = if (on) c.ink else c.faint,
                )
                Box(Modifier.width(if (on) 28.dp else 0.dp).height(3.dp).background(c.spike))
            }
        }
    }
}

@Composable
fun TabStrip(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Vct.colors
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            tabs.forEachIndexed { i, t ->
                val on = i == selected
                Column(
                    Modifier.clickable(role = Role.Tab) { onSelect(i) }.padding(top = 16.dp),
                    horizontalAlignment = Alignment.Start,
                ) {
                    Text(t.uppercase(Locale.getDefault()), style = Vct.type.label, color = if (on) c.ink else c.faint)
                    Spacer(Modifier.height(16.dp))
                    Box(Modifier.width(if (on) 24.dp else 0.dp).height(2.dp).background(c.spike))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
    }
}

@Composable
fun TopBar(title: String, modifier: Modifier = Modifier, onRefresh: (() -> Unit)? = null) {
    val c = Vct.colors
    val nav = LocalNavigator.current
    Row(
        modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clickable(role = Role.Button) { nav.back() }.semantics { contentDescription = "Back" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(VctIcons.Back, contentDescription = null, tint = c.ink, modifier = Modifier.size(22.dp))
        }
        MonoLabel(title, modifier = Modifier.weight(1f))
        if (onRefresh != null) {
            Box(
                Modifier.size(48.dp).clickable(role = Role.Button) { onRefresh() }.semantics { contentDescription = "Refresh" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(VctIcons.Refresh, contentDescription = null, tint = c.muted, modifier = Modifier.size(20.dp))
            }
        }
    }
}

// ------------------------------------------------------------------ states

/** Handles loading, failure and pull-to-refresh around a loaded value. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> LoadedContent(
    handle: LoadHandle<T>,
    modifier: Modifier = Modifier,
    skeleton: @Composable () -> Unit = { SkeletonList() },
    content: @Composable (Loaded<T>) -> Unit,
) {
    when (val s = handle.state) {
        Ui.Loading -> skeleton()
        is Ui.Failed -> ErrorState(s.message, onRetry = handle.refresh)
        is Ui.Ready -> PullToRefreshBox(
            isRefreshing = handle.refreshing,
            onRefresh = handle.refresh,
            modifier = modifier.fillMaxSize(),
        ) {
            content(s.data)
        }
    }
}

@Composable
fun OfflineNote(loaded: Loaded<*>, modifier: Modifier = Modifier) {
    if (!loaded.offline) return
    val c = Vct.colors
    Row(
        modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
            .border(1.dp, c.line, ChamferSmall).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(c.faint, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(
            "Offline. Showing what was saved ${Time.ago(loaded.fetchedAt)}.",
            style = Vct.type.small, color = c.muted,
        )
    }
}

@Composable
fun SkeletonList(rows: Int = 7, modifier: Modifier = Modifier) {
    val c = Vct.colors
    val t = rememberInfiniteTransition(label = "sk")
    val a by t.animateFloat(0.35f, 0.8f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    Column(
        modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp).alpha(a)
            .semantics { contentDescription = "Loading" },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.width(180.dp).height(34.dp).background(c.surfaceAlt))
        Spacer(Modifier.height(6.dp))
        repeat(rows) { i ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).background(c.surfaceAlt, ChamferSmall))
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.width((140 + (i * 37) % 90).dp).height(12.dp).background(c.surfaceAlt))
                    Box(Modifier.width((80 + (i * 23) % 60).dp).height(9.dp).background(c.surfaceAlt))
                }
            }
        }
    }
}

@Composable
fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val c = Vct.colors
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        MonoLabel("No signal", color = c.spikeText)
        Spacer(Modifier.height(8.dp))
        Text(message, style = Vct.type.heading, color = c.ink, modifier = Modifier.widthIn(max = 420.dp))
        Spacer(Modifier.height(20.dp))
        Row(
            Modifier.clip(ChamferSmall).background(c.ink, ChamferSmall)
                .clickable(role = Role.Button, onClick = onRetry)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(VctIcons.Refresh, null, tint = c.background, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("TRY AGAIN", style = Vct.type.label, color = c.background)
        }
    }
}

@Composable
fun EmptyNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = Vct.type.body, color = Vct.colors.muted, textAlign = TextAlign.Start,
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
    )
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Vct.colors.line))
}

@Composable
fun ScoreText(score: String?, won: Boolean, lost: Boolean, modifier: Modifier = Modifier) {
    val c = Vct.colors
    Text(
        score ?: "–",
        style = Vct.type.score.copy(fontSize = Vct.type.title.fontSize, fontWeight = FontWeight(if (won) 900 else 700)),
        color = when {
            won -> c.spikeText
            lost -> c.faint
            else -> c.ink
        },
        textAlign = TextAlign.End,
        modifier = modifier,
    )
}
