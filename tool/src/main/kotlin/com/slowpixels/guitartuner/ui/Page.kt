package com.slowpixels.guitartuner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.slowpixels.guitartuner.Hub
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/**
 * A list screen under the tuner. Choosing a tuning closes every list, so the tuner is back in front: each list leaves
 * by itself when it next shows with [Hub.closeLists] set.
 */
abstract class ListScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {
    private val shown = mutableIntStateOf(0)

    override fun willShow() { shown.intValue++ }

    /**
     * The kit draws every screen from the same spot, so what's remembered here is keyed on the screen itself: a "go back"
     * effect from the screen before would otherwise be taken as this one's and never run again.
     */
    @Composable
    override fun Content() {
        val n = shown.intValue
        LaunchedEffect(this, n) { if (Hub.closeLists) goBack() }
        key(this) { Body() }
    }

    @Composable
    abstract fun Body()
}

/** LightOS's theme on a full screen. */
@Composable
fun Themed(content: @Composable ColumnScope.() -> Unit) {
    val colors by LightThemeController.colors.collectAsState()
    LightTheme(colors = colors) {
        Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background), content = content)
    }
}

/** A sub-screen's top: a back arrow and title. */
@Composable
fun BackBar(title: String, back: () -> Unit) = LightTopBar(
    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = back),
    center = LightTopBarCenter.Text(title),
    modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
)

/** A sub-screen: a back arrow and title over a scrolling list. */
@Composable
fun ListPage(title: String, back: () -> Unit, rows: @Composable ColumnScope.() -> Unit) = Themed {
    BackBar(title, back)
    LightScrollView(Modifier.fillMaxSize().padding(horizontal = 1f.gridUnitsAsDp()), content = rows)
}

/** One choice in a list; the one in use has a dot. */
@Composable
fun Choice(label: String, selected: Boolean = false, detail: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().lightClickable(onClick = onClick).semantics(mergeDescendants = true) { this.selected = selected }
            .padding(vertical = 0.6f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            LightText(label.uppercase(), LightTextVariant.Copy)
            if (detail != null) LightText(detail, LightTextVariant.Fine, lighten = true)
        }
        if (selected) LightIcon(LightIcons.SELECT_ON, size = 1.5f, contentDescription = "Selected")
    }
}

/** A row: what it is on the left and, if given, what it's set to on the right. Tappable when [onClick] is given. */
@Composable
fun Item(label: String, value: String? = null, detail: String? = null, caps: Boolean = true, onClick: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.lightClickable(onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) {}.padding(vertical = 0.55f.gridUnitsAsDp()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LightText(if (caps) label.uppercase() else label, LightTextVariant.Copy, Modifier.padding(end = 1f.gridUnitsAsDp()))
            if (value != null) LightText(value, LightTextVariant.Copy, Modifier.weight(1f), align = TextAlign.End, lighten = true,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!detail.isNullOrEmpty()) LightText(detail, LightTextVariant.Fine, lighten = true)
    }
}

/** A few sentences of explanation. */
@Composable
fun Note(text: String, modifier: Modifier = Modifier) =
    LightText(text, LightTextVariant.Paragraph, modifier.fillMaxWidth().padding(vertical = 0.5f.gridUnitsAsDp()))

/** A short line that heads a few rows. */
@Composable
fun Heading(text: String) = LightText(text, LightTextVariant.Subheading,
    Modifier.fillMaxWidth().padding(top = 1f.gridUnitsAsDp(), bottom = 0.25f.gridUnitsAsDp()).semantics { heading() })
