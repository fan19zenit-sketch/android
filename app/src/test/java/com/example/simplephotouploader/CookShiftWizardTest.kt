package com.example.simplephotouploader

import org.junit.Assert.*
import org.junit.Test

class CookShiftWizardTest {
    private val operator = CookShiftWizard.Role.OPERATOR
    private val cook = CookShiftWizard.Role.COOK
    private val assistant = CookShiftWizard.Role.ASSISTANT

    @Test fun singleEmployeeIsBothOperatorAndMainCook() {
        val draft = CookShiftWizard(1)
        // The timesheet's default role must not prevent a cook from working alone.
        draft.selectSingle("timesheet-cook-only")
        assertEquals(listOf("timesheet-cook-only"), draft.selected(operator))
        assertEquals(listOf("timesheet-cook-only"), draft.selected(cook))
        assertEquals(emptyList<String>(), draft.selected(assistant))
        assertEquals("timesheet-cook-only", draft.mainCook)
        assertTrue(draft.isComplete())
        draft.selectSingle("replacement")
        assertEquals(listOf("replacement"), draft.selected(operator))
        assertEquals(listOf("replacement"), draft.selected(cook))
        assertEquals("replacement", draft.mainCook)
        assertTrue(draft.isComplete())
        assertThrows(IllegalArgumentException::class.java) { CookShiftWizard(2).selectSingle("one") }
    }

    @Test fun multipleRolesCanOverlapButNotDuplicateWithinRole() {
        val draft = CookShiftWizard(3)
        draft.setCounts(2, 2, 1)
        draft.select(operator, "one")
        assertEquals(listOf("two", "three"), draft.available(operator, listOf("one", "two", "three")))
        assertThrows(IllegalArgumentException::class.java) { draft.select(operator, "one") }
        draft.select(operator, "two")
        draft.select(cook, "one")
        draft.select(cook, "three")
        assertEquals(listOf("one", "two", "three"), draft.available(assistant, listOf("one", "two", "three", "four")))
        draft.select(assistant, "two")
        assertFalse(draft.isComplete())
        draft.selectMainCook("three")
        assertTrue(draft.isComplete())
    }

    @Test fun countsAndActualDistinctEmployeesMustAgree() {
        val draft = CookShiftWizard(3)
        assertThrows(IllegalArgumentException::class.java) { draft.setCounts(1, 1, 0) }
        draft.setCounts(1, 2, 0)
        draft.select(operator, "one")
        draft.select(cook, "one")
        draft.select(cook, "two")
        draft.selectMainCook("one")
        assertFalse(draft.isComplete())
        assertThrows(IllegalArgumentException::class.java) { draft.selectMainCook("three") }
    }

    @Test fun tenPeopleIncludeThreeDistinctTraineesAfterHelpers() {
        val draft = CookShiftWizard(10)
        draft.setCounts(2, 3, 2, 3)
        repeat(2) { draft.select(operator, "op$it") }
        repeat(3) { draft.select(cook, "cook$it") }
        repeat(2) { draft.select(assistant, "helper$it") }
        val intern = CookShiftWizard.Role.TRAINEE
        assertEquals(listOf("new"), draft.available(intern, listOf("op0", "cook0", "helper0", "new")))
        repeat(3) { draft.select(intern, "intern$it") }
        draft.selectMainCook("cook1")
        assertTrue(draft.isComplete())
    }

    @Test fun respectsRequestedScreenLimits() {
        assertThrows(IllegalArgumentException::class.java) { CookShiftWizard(11) }
        val draft = CookShiftWizard(6)
        assertThrows(IllegalArgumentException::class.java) { draft.setCounts(2, 5, 0) }
        assertThrows(IllegalArgumentException::class.java) { draft.setCounts(3, 3, 0) }
        draft.setCounts(2, 4, 0)
    }

    @Test fun goingBackRestoresTheLastPersonForSelection() {
        val draft = CookShiftWizard(2)
        draft.setCounts(1, 2, 0)
        draft.select(operator, "one")
        draft.select(cook, "one")
        draft.select(cook, "two")
        draft.selectMainCook("two")
        draft.removeLast(cook)
        assertEquals("one", draft.mainCook)
        assertEquals(listOf("two"), draft.available(cook, listOf("one", "two")))
        draft.select(cook, "two")
        assertFalse(draft.isComplete())
        draft.selectMainCook("two")
        assertTrue(draft.isComplete())
    }
}
