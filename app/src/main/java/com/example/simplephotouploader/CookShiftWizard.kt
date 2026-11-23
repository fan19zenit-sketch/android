package com.example.simplephotouploader

/** Draft only: the shift must still be persisted and confirmed by the existing workflow. */
internal class CookShiftWizard(val staffCount: Int) {
    enum class Role { OPERATOR, COOK, ASSISTANT, TRAINEE }

    private val choices = Role.entries.associateWith { mutableListOf<String>() }
    private val limits = mutableMapOf<Role, Int>()
    var mainCook: String? = null
        private set

    init { require(staffCount in 1..10) }

    fun selectSingle(employeeId: String) {
        require(staffCount == 1 && employeeId.isNotBlank())
        limits.clear()
        Role.entries.forEach { choices.getValue(it).clear() }
        limits[Role.OPERATOR] = 1
        limits[Role.COOK] = 1
        limits[Role.ASSISTANT] = 0
        limits[Role.TRAINEE] = 0
        choices.getValue(Role.OPERATOR).add(employeeId)
        choices.getValue(Role.COOK).add(employeeId)
        mainCook = employeeId
    }

    fun setCounts(operators: Int, cooks: Int, assistants: Int, trainees: Int = 0) {
        require(staffCount > 1)
        require(operators in 1..minOf(2, staffCount))
        require(cooks in 1..minOf(4, staffCount))
        require(assistants in 0..minOf(2, staffCount))
        require(trainees in 0..3 && maxOf(operators, cooks, assistants) + trainees <= staffCount)
        require(operators + cooks + assistants + trainees >= staffCount)
        limits.clear()
        limits.putAll(mapOf(Role.OPERATOR to operators, Role.COOK to cooks, Role.ASSISTANT to assistants, Role.TRAINEE to trainees))
        Role.entries.forEach { choices.getValue(it).clear() }
        mainCook = null
    }

    fun available(role: Role, employeeIds: List<String>): List<String> {
        val selected = allEmployees()
        val remaining = limits.values.sum() - choices.values.sumOf { it.size } - 1
        return employeeIds.distinct().filter {
            (role != Role.TRAINEE || it !in selected) && (selected + it).size + remaining >= staffCount &&
            it !in choices.getValue(role) && (selected.size < staffCount || it in selected)
        }
    }

    fun select(role: Role, employeeId: String) {
        require(employeeId.isNotBlank())
        val selected = choices.getValue(role)
        require(selected.size < requireNotNull(limits[role]) && employeeId !in selected)
        val distinctBefore = allEmployees()
        require(role != Role.TRAINEE || employeeId !in distinctBefore)
        require(employeeId in distinctBefore || distinctBefore.size < staffCount)
        selected.add(employeeId)
        if (role == Role.COOK) mainCook = selected.singleOrNull()
    }

    fun selectMainCook(employeeId: String) {
        require(employeeId in choices.getValue(Role.COOK))
        mainCook = employeeId
    }

    fun selected(role: Role): List<String> = choices.getValue(role).toList()

    fun removeLast(role: Role) {
        val selected = choices.getValue(role)
        if (selected.isNotEmpty()) {
            val removed = selected.removeAt(selected.lastIndex)
            if (role == Role.COOK && (mainCook == removed || mainCook !in selected)) mainCook = selected.singleOrNull()
        }
    }

    fun isComplete(): Boolean = Role.entries.all { choices.getValue(it).size == limits[it] } &&
        allEmployees().size == staffCount && mainCook in choices.getValue(Role.COOK)

    private fun allEmployees(): Set<String> = choices.values.flatten().toSet()
}
