package com.example.simplephotouploader

/** Draft only: the shift must still be persisted and confirmed by the existing workflow. */
internal class CookShiftWizard(val staffCount: Int) {
    enum class Role { OPERATOR, COOK, ASSISTANT }

    private val choices = Role.entries.associateWith { mutableListOf<String>() }
    private val limits = mutableMapOf<Role, Int>()
    var mainCook: String? = null
        private set

    init { require(staffCount in 1..6) }

    fun selectSingle(employeeId: String) {
        require(staffCount == 1 && employeeId.isNotBlank())
        limits.clear()
        Role.entries.forEach { choices.getValue(it).clear() }
        limits[Role.OPERATOR] = 1
        limits[Role.COOK] = 1
        limits[Role.ASSISTANT] = 0
        choices.getValue(Role.OPERATOR).add(employeeId)
        choices.getValue(Role.COOK).add(employeeId)
        mainCook = employeeId
    }

    fun setCounts(operators: Int, cooks: Int, assistants: Int) {
        require(staffCount > 1)
        require(operators in 1..minOf(2, staffCount))
        require(cooks in 1..minOf(4, staffCount))
        require(assistants in 0..minOf(2, staffCount))
        require(operators + cooks + assistants >= staffCount)
        limits.clear()
        limits.putAll(mapOf(Role.OPERATOR to operators, Role.COOK to cooks, Role.ASSISTANT to assistants))
        Role.entries.forEach { choices.getValue(it).clear() }
        mainCook = null
    }

    fun available(role: Role, employeeIds: List<String>): List<String> {
        val selected = allEmployees()
        return employeeIds.distinct().filter {
            it !in choices.getValue(role) && (selected.size < staffCount || it in selected)
        }
    }

    fun select(role: Role, employeeId: String) {
        require(employeeId.isNotBlank())
        val selected = choices.getValue(role)
        require(selected.size < requireNotNull(limits[role]) && employeeId !in selected)
        val distinctBefore = allEmployees()
        require(employeeId in distinctBefore || distinctBefore.size < staffCount)
        selected.add(employeeId)
        if (role == Role.COOK) mainCook = selected.singleOrNull()
    }

    fun selectMainCook(employeeId: String) {
        require(employeeId in choices.getValue(Role.COOK))
        mainCook = employeeId
    }

    fun selected(role: Role): List<String> = choices.getValue(role).toList()

    fun isComplete(): Boolean = Role.entries.all { choices.getValue(it).size == limits[it] } &&
        allEmployees().size == staffCount && mainCook in choices.getValue(Role.COOK)

    private fun allEmployees(): Set<String> = choices.values.flatten().toSet()
}
