package com.example.simplephotouploader

import org.junit.Assert.*
import org.junit.Test

class CookShiftWizardTest {
    private val operator = CookShiftWizard.Role.OPERATOR
    private val cook = CookShiftWizard.Role.COOK
    private val assistant = CookShiftWizard.Role.ASSISTANT

    @Test fun singleEmployeeIsBothOperatorAndMainCook() {
        val draft = CookShiftWizard(1)
        draft.selectSingle("one")
        assertEquals(listOf("one"), draft.selected(operator))
        assertEquals(listOf("one"), draft.selected(cook))
        assertEquals(emptyList<String>(), draft.selected(assistant))
        assertEquals("one", draft.mainCook)
        assertTrue(draft.isComplete())
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

    @Test fun respectsRequestedScreenLimits() {
        assertThrows(IllegalArgumentException::class.java) { CookShiftWizard(7) }
        val draft = CookShiftWizard(6)
        assertThrows(IllegalArgumentException::class.java) { draft.setCounts(2, 5, 0) }
        assertThrows(IllegalArgumentException::class.java) { draft.setCounts(3, 3, 0) }
        draft.setCounts(2, 4, 0)
    }
}
