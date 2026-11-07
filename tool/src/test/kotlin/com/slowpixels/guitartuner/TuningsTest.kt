package com.slowpixels.guitartuner

import com.slowpixels.guitartuner.model.Note
import com.slowpixels.guitartuner.model.Tunings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TuningsTest {
    @Test fun notesSoundWhereTheyShould() {
        assertEquals(82.41, Note.parse("E2").hz(), 0.01)
        assertEquals(110.0, Note.parse("A2").hz(), 0.01)
        assertEquals(329.63, Note.parse("E4").hz(), 0.01)
        assertEquals(77.78, Note.parse("Eb2").hz(), 0.01)
        assertEquals(185.0, Note.parse("F#3").hz(), 0.01)
        assertEquals("F♯", Note.parse("F#3").name)
        assertEquals("E♭", Note.parse("Eb2").name)
        assertEquals("E", Note.parse("Eb2").letter)
        assertEquals("♭", Note.parse("Eb2").accidental)
    }

    @Test fun everyTuningHasSixStrings() {
        for (t in Tunings.all) assertEquals(t.name, 6, t.notes.size)
    }

    @Test fun namesAreUnique() {
        assertEquals(Tunings.all.size, Tunings.all.map { it.id }.toSet().size)
    }

    @Test fun stringsStayWithinWhatAGuitarCanDo() {
        for (t in Tunings.all) for (i in 0 until 6) {
            val n = t.notes[i]
            assertTrue("${t.name} string $i at ${n.name}${n.octave}", n.hz() in 50.0..500.0)
        }
    }

    @Test fun lettersMatchTheNameWhereTheNameSpellsThem() {
        fun plain(name: String) = Tunings.named(name).notes.joinToString("") { it.letter }
        assertEquals("DADGAD", plain("DADGAD"))
        assertEquals("DADDAD", plain("DADDAD"))
        assertEquals("DADEAD", plain("DADEAD"))
        assertEquals("FACGCE", plain("FACGCE"))
    }

    @Test fun halfStepDownIsExactlyThat() {
        val down = Tunings.named("Half step down")
        for (i in 0 until 6) assertEquals(Tunings.standard.notes[i].midi - 1, down.notes[i].midi)
    }

    @Test fun oneAndAHalfDownIsExactlyThat() {
        val down = Tunings.named("1½ steps down")
        for (i in 0 until 6) assertEquals(Tunings.standard.notes[i].midi - 3, down.notes[i].midi)
    }

    @Test fun twoStepsDownIsExactlyThat() {
        val down = Tunings.named("2 steps down")
        for (i in 0 until 6) assertEquals(Tunings.standard.notes[i].midi - 4, down.notes[i].midi)
    }

    @Test fun dropTuningsAreAStandardFamilyWithTheLowStringATonLower() {
        for ((drop, steps) in listOf("Drop D" to 0, "Drop D♭" to 1, "Drop C" to 2, "Drop B" to 3, "Drop B♭" to 4, "Drop A" to 5)) {
            val t = Tunings.named(drop)
            for (i in 1 until 6) assertEquals("$drop string $i", Tunings.standard.notes[i].midi - steps, t.notes[i].midi)
            assertEquals(drop, Tunings.standard.notes[0].midi - steps - 2, t.notes[0].midi)
        }
    }

    @Test fun ordinaryTuningsDontAskForLightStrings() {
        for (name in listOf("Standard", "Open E", "Open A", "Open G", "DADGAD", "Drop D", "Half step up")) {
            assertFalse(name, Tunings.named(name).needsLightStrings)
        }
    }

    @Test fun tuningsThatCouldSnapAStringSaySo() {
        for (name in listOf("Nashville", "New standard", "All fifths", "Iris")) {
            assertTrue(name, Tunings.named(name).needsLightStrings)
        }
    }

    @Test fun anUnknownNameFallsBackToStandard() {
        assertEquals("Standard", Tunings.named("Nonsense").name)
        assertEquals("Standard", Tunings.named(null).name)
    }
}
