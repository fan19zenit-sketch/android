package com.example.simplephotouploader

import android.graphics.Color
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class CookShiftActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var content: LinearLayout
    private var binding: CookBinding? = null
    private var employees: List<CookEmployee> = emptyList()
    private val cooks = linkedSetOf<String>()
    private val operators = linkedSetOf<String>()
    private var mainCook: String? = null
    private var busy = false
    private val preview get() = BuildConfig.BUILD_TYPE == "preview"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(17, 17, 17)
        window.navigationBarColor = Color.rgb(17, 17, 17)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(28))
            setBackgroundColor(Color.rgb(17, 17, 17))
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.rgb(17, 17, 17))
            addView(content)
        })
        loadRoster()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()

    private fun label(text: String, size: Float, color: Int = Color.WHITE): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        if (size >= 20f) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(color)
        setPadding(0, dp(12), 0, dp(8))
    }

    private fun choiceTint() = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(Color.rgb(224, 83, 153), Color.rgb(150, 150, 150)))

    private fun action(text: String, primary: Boolean = false, click: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 16f
        minHeight = dp(52)
        setTextColor(Color.WHITE)
        backgroundTintList = ColorStateList.valueOf(if (primary) Color.rgb(186, 56, 127) else Color.rgb(50, 50, 50))
        setOnClickListener { click() }
    }

    private fun client(): Pair<CookWorkflowClient, String> {
        val prefs = getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE)
        val backend = prefs.getString(AppPrefs.KEY_BACKEND_URL, AppPrefs.DEFAULT_BACKEND_URL).orEmpty()
        val device = prefs.getString(AppPrefs.KEY_DEVICE_UUID, "").orEmpty()
        require(backend.isNotBlank() && device.isNotBlank())
        return CookWorkflowClient(backend) to device
    }

    private fun loadRoster() {
        content.removeAllViews()
        content.addView(label("Смена", 30f))
        content.addView(label("Загрузка…", 16f))
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    if (preview) {
                        val fixture = listOf(CookEmployee("cook-1", "Повар 1", listOf("cook")),
                            CookEmployee("cook-2", "Повар 2", listOf("cook")),
                            CookEmployee("operator-1", "Оператор 1", listOf("operator")))
                        CookBinding("preview", 1, "Europe/Moscow") to fixture
                    } else {
                        val (client, device) = client()
                        check(client.capabilities()?.supportsAttributedCapture == true) { "Сервер пока не поддерживает смены" }
                        val binding = client.initializeBinding(device)
                        check(binding.timezone != null) { "Не настроен часовой пояс филиала" }
                        val roster = requireNotNull(client.roster(device))
                        check(roster.branchRef == binding.branchRef) { "Привязка филиала изменилась" }
                        binding to roster.employees
                    }
                }
            }
            result.onSuccess { (newBinding, roster) ->
                binding = newBinding
                employees = roster
                val previous = withContext(Dispatchers.IO) { CookLocalSession.cache(applicationContext).load() }
                if (previous?.binding == newBinding) {
                    cooks.addAll(previous.shift.cooks.filter { id -> roster.any { it.id == id && "cook" in it.roles } })
                    operators.addAll(previous.shift.operators.filter { id -> roster.any { it.id == id && "operator" in it.roles } })
                    mainCook = previous.shift.mainCookId.takeIf { it in cooks }
                }
                render()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                content.removeAllViews()
                content.addView(label("Смена", 30f))
                content.addView(label(error.message ?: "Не удалось загрузить сотрудников", 16f))
                content.addView(action("Повторить") { loadRoster() })
            }
        }
    }

    private fun render() {
        content.removeAllViews()
        content.addView(label("Смена", 30f))
        content.addView(label("Повара", 20f))
        employees.filter { "cook" in it.roles }.forEach { employee ->
            content.addView(CheckBox(this).apply {
                text = employee.displayName; setTextColor(Color.WHITE); isChecked = employee.id in cooks
                textSize = 16f; minHeight = dp(48); buttonTintList = choiceTint()
                setOnCheckedChangeListener { _, checked ->
                    if (checked) cooks.add(employee.id) else cooks.remove(employee.id)
                    if (mainCook !in cooks) mainCook = cooks.firstOrNull()
                    render()
                }
            })
        }
        if (cooks.isNotEmpty()) {
            content.addView(label("Основной повар", 20f))
            content.addView(RadioGroup(this).apply {
                employees.filter { it.id in cooks }.forEach { employee ->
                    addView(RadioButton(this@CookShiftActivity).apply {
                        id = View.generateViewId(); text = employee.displayName; setTextColor(Color.WHITE)
                        textSize = 16f; minHeight = dp(48); buttonTintList = choiceTint()
                        isChecked = employee.id == mainCook
                        setOnClickListener { mainCook = employee.id }
                    })
                }
            })
        }
        content.addView(label("Операторы", 20f))
        employees.filter { "operator" in it.roles }.forEach { employee ->
            content.addView(CheckBox(this).apply {
                text = employee.displayName; setTextColor(Color.WHITE); isChecked = employee.id in operators
                textSize = 16f; minHeight = dp(48); buttonTintList = choiceTint()
                setOnCheckedChangeListener { _, checked -> if (checked) operators.add(employee.id) else operators.remove(employee.id) }
            })
        }
        content.addView(action("Добавить сотрудника") { addEmployee() })
        content.addView(action("Подтвердить смену", primary = true) { saveShift() }.apply { isEnabled = !busy })
    }

    private fun addEmployee() {
        val name = EditText(this).apply { hint = "Имя сотрудника"; setSingleLine() }
        val choices = arrayOf("Повар", "Оператор")
        val selected = booleanArrayOf(true, false)
        AlertDialog.Builder(this).setTitle("Сотрудник").setView(name)
            .setMultiChoiceItems(choices, selected) { _, index, value -> selected[index] = value }
            .setNegativeButton("Отмена", null).setPositiveButton("Добавить") { _, _ ->
                val text = name.text.toString().trim()
                val roles = listOfNotNull("cook".takeIf { selected[0] }, "operator".takeIf { selected[1] })
                if (text.isBlank() || roles.isEmpty()) return@setPositiveButton
                val currentBinding = binding ?: return@setPositiveButton
                if (busy) return@setPositiveButton
                busy = true
                scope.launch {
                    val result = runCatching { withContext(Dispatchers.IO) {
                        val store = CookPendingEmployeeStore(CookLocalSession.preferences(applicationContext))
                        val existing = store.load()
                        val pending = if (existing?.binding == currentBinding && existing.name == text && existing.roles == roles) existing
                            else PendingCookEmployee(UUID.randomUUID().toString(), currentBinding, text, roles)
                        check(store.save(pending)) { "Не удалось сохранить запрос" }
                        val employee = if (preview) CookEmployee(UUID.randomUUID().toString(), text, roles)
                        else {
                            val (client, device) = client()
                            check(client.initializeBinding(device) == currentBinding) { "Привязка филиала изменилась" }
                            requireNotNull(client.addManual(device, pending.requestId, text, roles, currentBinding))
                        }
                        store.clear()
                        employee
                    } }
                    busy = false
                    result.onSuccess { added -> employees = employees.filterNot { it.id == added.id } + added; render() }
                        .onFailure { if (it is CancellationException) throw it else showError("Не удалось добавить сотрудника") }
                }
            }.show()
    }

    private fun saveShift() {
        val binding = binding ?: return
        val timezone = binding.timezone ?: return
        val main = mainCook
        if (cooks.isEmpty() || operators.isEmpty() || main == null || main !in cooks) {
            showError("Выберите поваров, операторов и основного повара")
            return
        }
        if (busy) return
        busy = true
        val selectedCooks = cooks.sorted()
        val selectedOperators = operators.sorted()
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) {
                val zone = ZoneId.of(timezone)
                val now = Instant.now().atZone(zone)
                val store = CookPendingShiftStore(CookLocalSession.preferences(applicationContext))
                val prior = store.load()
                val pending = if (prior != null && prior.binding == binding && prior.shift.operationalDate == now.toLocalDate().toString()
                    && prior.shift.cooks == selectedCooks && prior.shift.operators == selectedOperators && prior.shift.mainCookId == main) prior
                else PendingCookShift(UUID.randomUUID().toString(), binding,
                    CookShift(UUID.randomUUID().toString(), 0, binding.branchRef, now.toLocalDate().toString(), timezone,
                        now.toOffsetDateTime().toString(), selectedCooks, selectedOperators, main))
                check(store.save(pending)) { "Не удалось сохранить запрос смены" }
                val revision = if (preview) 1 else {
                    val (client, device) = client()
                    check(client.initializeBinding(device) == binding) { "Привязка филиала изменилась" }
                    val result = requireNotNull(client.confirmShift(device, pending.requestId, pending.shift, binding))
                    check(result.first == pending.shift.id && result.second > 0) { "Некорректное подтверждение сервера" }
                    result.second
                }
                val snapshot = CachedCookShift(binding, pending.shift.copy(revision = revision), employees)
                check(CookLocalSession.cache(applicationContext).save(snapshot)) { "Не удалось сохранить смену" }
                store.clear()
            } }
            busy = false
            result.onSuccess { setResult(RESULT_OK); finish() }
                .onFailure { if (it is CancellationException) throw it else showError(it.message ?: "Не удалось подтвердить смену") }
        }
    }

    private fun showError(message: String) {
        if (!isFinishing && !isDestroyed) AlertDialog.Builder(this).setMessage(message).setPositiveButton("Понятно", null).show()
    }
}
