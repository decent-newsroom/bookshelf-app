package eu.decentnewsroom.bookshelf.ui.tutorials

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorialCatalogTest {
    @Test
    fun everyStepHasExactlyOneBundledExample() {
        val tutorials = TutorialCatalog.topics.map(TutorialCatalog::get)
        assertEquals(8, tutorials.size)
        val steps = tutorials.flatMap { it.steps }
        assertEquals(22, steps.size)
        tutorials.forEach { tutorial ->
            assertEquals(tutorial.steps.size, tutorial.steps.map { it.id }.distinct().size)
        }
        steps.forEach { step ->
            assertNotNull("Missing example for ${step.id}", step.example)
            assertEquals(null, step.illustrationRes)
        }
        assertEquals(TutorialExample.entries.toSet(), steps.mapNotNull { it.example }.toSet())
        assertEquals(steps.size, steps.mapNotNull { it.example }.distinct().size)
        assertTrue(TutorialExample.entries.all { it.descriptionRes != 0 })
        assertEquals(TutorialExample.entries.size, TutorialExample.entries.map { it.descriptionRes }.distinct().size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun drawableCannotCompeteWithComponentExample() {
        TutorialStep(
            id = "mixed", titleRes = 1, bodyRes = 2,
            illustrationRes = 3, descriptionRes = 4, example = TutorialExample.ReaderControls,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun drawableRequiresDescription() {
        TutorialStep(id = "undescribed", titleRes = 1, bodyRes = 2, illustrationRes = 3)
    }

    @Test
    fun describedDrawableRemainsSupported() {
        val step = TutorialStep(
            id = "drawable", titleRes = 1, bodyRes = 2, illustrationRes = 3, descriptionRes = 4,
        )
        assertEquals(3, step.illustrationRes)
        assertEquals(4, step.descriptionRes)
        assertEquals(null, step.example)
    }
}
