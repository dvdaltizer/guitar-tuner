package com.slowpixels.guitartuner.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import com.slowpixels.guitartuner.model.Dial
import com.slowpixels.guitartuner.model.Note
import com.slowpixels.guitartuner.model.Tuning
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Whether the tool may listen. */
enum class Mic { Asking, Allowed, Denied, Failed }

/**
 * Where everything sits, as fractions of the face. It looks straight down at the guitar: the strings run up the screen,
 * over the soundhole or the pickups, to the meter at the top, and each note sits where its string ends at the bridge.
 */
private class Place(val w: Float, val h: Float) {
    /** Line weights are given for a 480-wide panel; they scale with the width. */
    val s = w / 480f
    val cx = w / 2f
    val margin = w * 0.06f
    val pivotY = h * 0.36f
    val scaleR = h * 0.32f
    val lampY = pivotY - scaleR - h * 0.035f
    val noteY = h * 0.425f
    val stringsTop = h * 0.52f
    val bridgeY = h * 0.83f
    val letterY = h * 0.905f
    val spacing = w * 0.135f
    private val spacingTop = w * 0.115f

    fun stringX(i: Int) = cx + (i - 2.5f) * spacing
    fun stringTopX(i: Int) = cx + (i - 2.5f) * spacingTop

    /** Which string a touch at [x], [y] lands on, or null above the strings. */
    fun stringAt(x: Float, y: Float): Int? {
        if (y < stringsTop - h * 0.02f) return null
        return ((x - cx) / spacing + 2.5f).roundToInt().takeIf { it in 0..5 }
    }

    companion object {
        /** The furthest the needle swings, and where it lies with nothing playing. */
        const val SWING = 50f
        const val REST = -55f

        /**
         * The needle's angle for a reading, in degrees from upright. Like a VU meter's, the scale is stretched where it
         * matters, around the middle, so the last few cents are a good swing and not a twitch.
         */
        fun angle(cents: Double): Float {
            val share = (abs(cents) / 50.0).coerceAtMost(1.0).pow(0.62)
            return (SWING * share * if (cents < 0) -1.0 else 1.0).toFloat()
        }

        /** How thick each string looks, thickest first, on a 480-wide panel. */
        val STRING_WIDTHS = floatArrayOf(3.4f, 3.0f, 2.6f, 1.9f, 1.6f, 1.3f)
    }
}

private fun rad(deg: Float) = deg * PI.toFloat() / 180f

/**
 * The tuner's face, in Light's line-and-type look: a bare meter, and the six strings below it over the soundhole
 * (acoustic) or the pickups (electric). A string starts slack, in a lazy wave: nothing has been heard from it, so
 * nothing's claimed for it. It tightens as it nears its note and is pulled straight when it gets there, and it stays
 * straight, so a guitar in tune is six clean lines.
 */
@Composable
fun TunerFace(
    dial: Dial,
    tuning: Tuning,
    electric: Boolean,
    held: Int,
    a4: Int,
    mic: Mic,
    modifier: Modifier = Modifier,
    onTap: (Int) -> Unit,
    onHold: (Int) -> Unit,
    onPlate: () -> Unit,
) {
    val colors = LightThemeTokens.colors
    val ink = colors.content
    val faint = colors.contentSecondary
    val paper = colors.background
    val family = LightThemeTokens.typography.heading.fontFamily ?: FontFamily.Default
    val measurer = rememberTextMeasurer()

    // Each new look shakes the strings the other way, while one is sounding and out of tune.
    var flip by remember { mutableStateOf(false) }
    LaunchedEffect(dial) { if (dial.hearing && !dial.inTune && !dial.cents.isNaN()) flip = !flip }

    val listening = dial.string >= 0 && (!dial.cents.isNaN() || held >= 0)
    val heard = if (listening) tuning.notes[dial.string] else null
    val allDone = dial.reached == 63
    val rest = dial.cents.isNaN()
    val lit = !rest && dial.inTune
    val word = when {
        rest || lit -> ""
        dial.cents <= -1.0 -> "TIGHTEN"
        dial.cents >= 1.0 -> "LOOSEN"
        else -> ""
    }
    val plain = when (mic) {
        Mic.Denied -> "NEEDS THE MICROPHONE"
        Mic.Failed -> "THE MICROPHONE WON'T OPEN"
        Mic.Asking -> ""
        Mic.Allowed -> if (allDone) "ALL IN TUNE" else "PLAY A STRING"
    }
    val said = heard?.name?.replace("♭", " flat")?.replace("♯", " sharp")
    val description = buildString {
        append(when {
            heard == null -> plain.lowercase().replaceFirstChar { it.uppercase() }
            lit -> "$said in tune"
            word.isEmpty() -> "$said, close"
            else -> "$said, ${word.lowercase()}"
        })
        append(". Strings: ")
        append(tuning.notes.mapIndexed { i, n -> n.name + (if (dial.reached and (1 shl i) != 0) " in tune" else "") }.joinToString(", "))
        if (held >= 0) append(". Listening for string ${6 - held} only")
    }

    Canvas(
        modifier
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                val p = Place(size.width.toFloat(), size.height.toFloat())
                detectTapGestures(
                    onTap = { o -> p.stringAt(o.x, o.y)?.let(onTap) ?: onPlate() },
                    onLongPress = { o -> p.stringAt(o.x, o.y)?.let(onHold) },
                )
            },
    ) {
        val p = Place(size.width, size.height)
        if (electric) pickups(p, faint) else soundhole(p, faint)
        strings(p, tuning, dial, held, flip, ink, paper, measurer, family)
        scale(p, ink, faint, measurer, family)
        meter(p, dial, heard, lit, word, plain, a4, ink, faint, measurer, family)
    }
}

// ---- type ----

private fun DrawScope.style(family: FontFamily, px: Float, color: Color, weight: FontWeight = FontWeight.Normal, spacing: Float = 0f) =
    TextStyle(fontFamily = family, fontSize = px.toSp(), color = color, fontWeight = weight, letterSpacing = (px * spacing).toSp())

/** Draws [text] with its middle at [x], [y]. Returns its width. */
private fun DrawScope.centred(m: TextMeasurer, text: String, x: Float, y: Float, style: TextStyle): Float {
    val laid = m.measure(text, style)
    drawText(laid, topLeft = Offset(x - laid.size.width / 2f, y - laid.size.height / 2f))
    return laid.size.width.toFloat()
}

private fun DrawScope.leftAt(m: TextMeasurer, text: String, x: Float, y: Float, style: TextStyle) {
    val laid = m.measure(text, style)
    drawText(laid, topLeft = Offset(x, y - laid.size.height / 2f))
}

private fun DrawScope.rightAt(m: TextMeasurer, text: String, x: Float, y: Float, style: TextStyle) {
    val laid = m.measure(text, style)
    drawText(laid, topLeft = Offset(x - laid.size.width, y - laid.size.height / 2f))
}

// ---- the still picture ----

/** The soundhole: one thin circle, the strings crossing it. */
private fun DrawScope.soundhole(p: Place, faint: Color) {
    val cy = (p.stringsTop + p.bridgeY) / 2f + p.h * 0.02f
    drawCircle(faint, p.w * 0.205f, Offset(p.cx, cy), style = Stroke(1.4f * p.s))
}

/** Two pickups: thin rounded bars under the strings. */
private fun DrawScope.pickups(p: Place, faint: Color) {
    val span = p.bridgeY - p.stringsTop
    val wide = p.spacing * 5.8f
    val tall = p.h * 0.04f
    for (at in floatArrayOf(0.42f, 0.72f)) {
        val cy = p.stringsTop + span * at
        drawRoundRect(faint, Offset(p.cx - wide / 2f, cy - tall / 2f), Size(wide, tall), CornerRadius(tall * 0.25f), style = Stroke(1.4f * p.s))
    }
}

/** The arc and its ticks: a tick every couple of cents near the middle, spreading out towards the ends. */
private fun DrawScope.scale(p: Place, ink: Color, faint: Color, m: TextMeasurer, family: FontFamily) {
    val r = p.scaleR
    fun at(deg: Float, radius: Float) = Offset(p.cx + radius * sin(rad(deg)), p.pivotY - radius * cos(rad(deg)))
    fun tick(deg: Float, long: Float, width: Float, color: Color) = drawLine(color, at(deg, r), at(deg, r + long), width)
    drawArc(ink, -90f - Place.SWING, 2f * Place.SWING, false, Offset(p.cx - r, p.pivotY - r), Size(2 * r, 2 * r), style = Stroke(1.6f * p.s))
    for (c in doubleArrayOf(1.0, 2.0, 3.0, 4.0, 6.0, 7.5, 12.5, 15.0, 17.5, 25.0, 30.0, 35.0, 40.0, 45.0)) for (side in intArrayOf(-1, 1)) {
        tick(Place.angle(c * side), 8f * p.s, 1.2f * p.s, ink)
    }
    val small = style(family, p.h * 0.026f, faint)
    for (c in intArrayOf(5, 10, 20, 50)) for (side in intArrayOf(-1, 1)) {
        val deg = Place.angle(c.toDouble() * side)
        tick(deg, 14f * p.s, 1.6f * p.s, ink)
        val o = at(deg, r + 30f * p.s)
        centred(m, "$c", o.x, o.y, small)
    }
    tick(0f, 18f * p.s, 2f * p.s, ink)
    val sign = style(family, p.h * 0.042f, faint)
    centred(m, "♭", at(-Place.SWING, r - 24f * p.s).x, at(-Place.SWING, r - 24f * p.s).y, sign)
    centred(m, "♯", at(Place.SWING, r - 24f * p.s).x, at(Place.SWING, r - 24f * p.s).y, sign)
}

// ---- the moving parts ----

private fun DrawScope.meter(
    p: Place, dial: Dial, heard: Note?, lit: Boolean, word: String, plain: String, a4: Int,
    ink: Color, faint: Color, m: TextMeasurer, family: FontFamily,
) {
    val rest = dial.cents.isNaN()
    val angle = when {
        rest -> Place.REST
        lit -> 0f
        else -> Place.angle(dial.cents)
    }
    val a = rad(angle)
    val from = 10f * p.s
    val to = p.scaleR + 10f * p.s
    drawLine(ink, Offset(p.cx + from * sin(a), p.pivotY - from * cos(a)), Offset(p.cx + to * sin(a), p.pivotY - to * cos(a)), 2f * p.s, cap = StrokeCap.Round)
    drawCircle(ink, 4f * p.s, Offset(p.cx, p.pivotY))

    // Over the target: hollow until the string is true, then filled.
    val lr = 7f * p.s
    if (lit) drawCircle(ink, lr + 1f * p.s, Offset(p.cx, p.lampY))
    else drawCircle(ink, lr, Offset(p.cx, p.lampY), style = Stroke(1.6f * p.s))

    if (a4 != 440) rightAt(m, "A = $a4", p.w - p.margin, p.lampY, style(family, p.h * 0.026f, faint))

    val y = p.noteY
    if (heard != null) {
        val big = style(family, p.h * 0.11f, ink, FontWeight.Light)
        val width = centred(m, heard.name, p.cx, y, big)
        leftAt(m, "${heard.octave}", p.cx + width / 2f + 6f * p.s, y + p.h * 0.025f, style(family, p.h * 0.032f, faint))
    } else if (plain.isNotEmpty()) {
        centred(m, plain, p.cx, y, style(family, p.h * 0.03f, faint, spacing = 0.12f))
    }
    if (word.isNotEmpty()) {
        val loud = style(family, p.h * 0.03f, ink, spacing = 0.12f)
        if (dial.cents > 0.0) rightAt(m, word, p.w - p.margin, y, loud) else leftAt(m, word, p.margin, y, loud)
    }
}

private fun DrawScope.strings(
    p: Place, tuning: Tuning, dial: Dial, held: Int, flip: Boolean,
    ink: Color, paper: Color, m: TextMeasurer, family: FontFamily,
) {
    val listening = dial.string >= 0 && (!dial.cents.isNaN() || held >= 0)
    val slack = 5f * p.s
    val slowWave = 180f * p.s
    val tightWave = 64f * p.s
    val letterPx = p.h * 0.036f
    val label = style(family, letterPx, ink)
    val labelOn = style(family, letterPx, paper)
    // The saddle the strings cross.
    drawLine(ink, Offset(p.stringX(0) - p.spacing * 0.5f, p.bridgeY), Offset(p.stringX(5) + p.spacing * 0.5f, p.bridgeY), 1.2f * p.s)

    for (i in 0 until 6) {
        val note = tuning.notes[i]
        val mine = dial.string == i
        val active = mine && listening
        val sounding = mine && dial.hearing
        val tuned = dial.reached and (1 shl i) != 0
        val cents = if (mine) dial.cents else Double.NaN
        val inTune = mine && dial.inTune
        val straight = inTune || (tuned && cents.isNaN())
        val off = if (cents.isNaN()) Double.NaN else abs(cents).coerceAtMost(40.0)
        // Slack and slow when it's flat; tight, quick ripples when it's wound too far.
        val bend = when {
            straight -> 0f
            off.isNaN() -> slack
            else -> (1.2f + (off / 40.0).pow(0.6).toFloat() * 10f) * p.s
        }
        val length = if (!cents.isNaN() && cents > 0.0) tightWave else slowWave
        val width = Place.STRING_WIDTHS[i] * p.s + if (sounding) 1f * p.s else 0f

        val topX = p.stringTopX(i)
        val footX = p.stringX(i)
        val saddle = p.bridgeY + (2.5f - i) * 1.2f * p.s
        // Each string lies a little differently, so a slack guitar doesn't look ruled.
        val phase = i * 1.9f + if (flip && sounding && bend > 0f) PI.toFloat() else 0f
        val path = Path()
        path.moveTo(topX, p.stringsTop)
        val span = saddle - p.stringsTop
        var y = 6f * p.s
        while (y < span) {
            val t = y / span
            val hold = sin(t * PI.toFloat()).pow(0.7f)
            val x = topX + (footX - topX) * t + bend * hold * sin(y / length * 2f * PI.toFloat() + phase)
            path.lineTo(x, p.stringsTop + y)
            y += 6f * p.s
        }
        path.lineTo(footX, saddle)
        drawPath(path, paper, style = Stroke(width + 4f * p.s))
        drawPath(path, ink, style = Stroke(width))
        // On over the saddle to its letter.
        drawLine(ink, Offset(footX, saddle), Offset(footX, p.letterY - letterPx * 0.95f), Place.STRING_WIDTHS[i] * p.s)

        // The string's note at its foot; the one being listened for alone is marked.
        val c = Offset(footX, p.letterY)
        if (active) {
            drawCircle(ink, letterPx * 0.85f, c)
            centred(m, note.name, c.x, c.y, labelOn)
        } else {
            centred(m, note.name, c.x, c.y, label)
        }
    }
}
