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
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

class CookShiftActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var content: LinearLayout
    private var binding: CookBinding? = null
    private var employees: List<CookEmployee> = emptyList()
    private val cooks = linkedSetOf<String>()
    private val operators = linkedSetOf<String>()
    private var mainCook: String? = null
    private var busy = false
    private var offlineMode = false
    private val preview get() = BuildConfig.BUILD_TYPE == "preview"
    private data class LoadedRoster(val binding: CookBinding, val employees: List<CookEmployee>, val offline: Boolean)

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
                    val loaded = if (preview) {
                        val secondName = if (intent.getBooleanExtra("test_duplicate_names", false)) "Повар 1" else "Повар 2"
                        val firstRoles = if (intent.getBooleanExtra("test_universal", false)) listOf("cook", "operator") else listOf("cook")
                        val fixture = listOf(CookEmployee("cook-1", "Повар 1", firstRoles),
                            CookEmployee("cook-2", secondName, listOf("cook")),
                            CookEmployee("operator-1", "Оператор 1", listOf("operator"))) +
                            if (intent.getBooleanExtra("test_three_cooks", false))
                                listOf(CookEmployee("cook-3", "Повар 3", listOf("cook"))) else emptyList()
                        LoadedRoster(CookBinding("preview", 1, "Europe/Moscow"), fixture,
                            intent.getBooleanExtra("test_offline", false))
                    } else {
                        val local = CookLocalSession.preferences(applicationContext)
                        val expectedScope = CookLocalSession.identity(applicationContext)
                        try {
                            val (client, device) = client()
                            check(client.capabilities()?.supportsAttributedCapture == true) { "Сервер пока не поддерживает смены" }
                            val current = client.initializeBinding(device)
                            check(current.timezone != null) { "Не настроен часовой пояс филиала" }
                            val roster = requireNotNull(client.roster(device))
                            check(roster.branchRef == current.branchRef) { "Привязка филиала изменилась" }
                            ensureActive()
                            check(CookLocalSession.identity(applicationContext) == expectedScope) { "Настройки подключения изменились" }
                            check(CookDeviceRosterCache(local).save(current, roster.employees)) { "Не удалось сохранить справочник" }
                            LoadedRoster(current, roster.employees, false)
                        } catch (error: IOException) {
                            if (error is CookApiProtocolException || (error is CookApiHttpException &&
                                    error.status !in setOf(408, 429) && error.status < 500)) throw error
                            val cached = CookDeviceRosterCache(local).load()
                            val oldShift = CookShiftCache(local).load()
                            val binding = cached?.binding ?: oldShift?.binding ?: throw error
                            val employees = cached?.employees ?: oldShift?.employees.orEmpty()
                            LoadedRoster(binding, employees, true)
                        }
                    }
                    val scope = CookLocalSession.offlineScope(applicationContext, loaded.binding)
                    val dao = CookOfflineDatabase.get(applicationContext).queue()
                    val local = CookLocalSession.preferences(applicationContext)
                    val active = if (local.contains(CookOfflineSelectionCache.KEY))
                        CookOfflineSelectionCache(local, dao, scope).load()?.takeIf {
                            CookShiftPolicy.usable(it.shift, loaded.binding, it.binding, Instant.now())
                        } else null
                    val pendingRecords = dao.pendingEmployees(scope.backendUrl, scope.deviceUuid, loaded.binding.branchRef, loaded.binding.revision)
                    val pending = pendingRecords.map { person ->
                            val roles = JSONArray(person.rolesJson)
                            CookEmployee(person.provisionalId, person.name, (0 until roles.length()).map { roles.getString(it) })
                        }
                    val confirmed = if (loaded.offline) dao.confirmedEmployees(scope.backendUrl, scope.deviceUuid,
                        loaded.binding.branchRef, loaded.binding.revision).mapNotNull { person ->
                            val id = person.canonicalId ?: return@mapNotNull null
                            val roles = JSONArray(person.rolesJson)
                            CookEmployee(id, person.name, (0 until roles.length()).map { roles.getString(it) })
                        } else emptyList()
                    if (!preview && (pendingRecords.isNotEmpty() ||
                                dao.pendingShifts(scope.backendUrl, scope.deviceUuid, loaded.binding.branchRef, loaded.binding.revision).isNotEmpty())) {
                        val city = getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE).getString(AppPrefs.KEY_CITY, "").orEmpty()
                        runCatching { CookOfflineSyncWorker.enqueue(applicationContext, scope, city) }
                    }
                    val activePeople = active?.employees.orEmpty().filter { person ->
                        if (loaded.offline || preview) true else {
                            val mapped = dao.employee(scope.backendUrl, scope.deviceUuid, loaded.binding.branchRef,
                                loaded.binding.revision, person.id)
                            (mapped != null && mapped.canonicalId == null) ||
                                loaded.employees.any { it.id == (mapped?.canonicalId ?: person.id) }
                        }
                    }
                    val canonicalIds = activePeople.mapNotNull { person ->
                        dao.employee(scope.backendUrl, scope.deviceUuid, loaded.binding.branchRef, loaded.binding.revision, person.id)?.canonicalId
                    }.toSet()
                    loaded.copy(employees = (loaded.employees.filterNot { it.id in canonicalIds } + activePeople + pending +
                        confirmed.filterNot { it.id in canonicalIds })
                        .distinctBy { it.id })
                }
            }
            result.onSuccess { (newBinding, roster, wasOffline) ->
                binding = newBinding
                employees = roster
                offlineMode = wasOffline
                val previous = withContext(Dispatchers.IO) {
                    val scope = CookLocalSession.offlineScope(applicationContext, newBinding)
                    val local = CookLocalSession.preferences(applicationContext)
                    val active = if (local.contains(CookOfflineSelectionCache.KEY))
                        CookOfflineSelectionCache(local, CookOfflineDatabase.get(applicationContext).queue(), scope).load()?.takeIf {
                            CookShiftPolicy.usable(it.shift, newBinding, it.binding, Instant.now())
                        } else null
                    active ?: CookLocalSession.cache(applicationContext).load()
                }
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
        if (offlineMode) content.addView(label("Нет сети", 16f, Color.rgb(255, 180, 210)))
        if (employees.any { CookEmployeeLabels.ambiguous(it, employees) }) {
            content.addView(label("Одинаковые имена: попросите управляющего уточнить сотрудников в общем списке.",
                16f, Color.rgb(255, 180, 210)))
        }
        content.addView(label("Повара", 20f))
        employees.filter { "cook" in it.roles }.forEach { employee ->
            content.addView(CheckBox(this).apply {
                text = CookEmployeeLabels.label(employee, employees); setTextColor(Color.WHITE); isChecked = employee.id in cooks
                textSize = 16f; minHeight = dp(48); buttonTintList = choiceTint()
                isEnabled = !CookEmployeeLabels.ambiguous(employee, employees) || isChecked
                setOnCheckedChangeListener { _, checked ->
                    if (checked) cooks.add(employee.id) else cooks.remove(employee.id)
                    if (!checked && (employee.id == mainCook || mainCook == null)) mainCook = cooks.singleOrNull()
                    else if (checked && mainCook == null && cooks.size == 1) mainCook = employee.id
                    render()
                }
            })
        }
        if (cooks.isNotEmpty()) {
            content.addView(label("Основной повар", 20f))
            content.addView(RadioGroup(this).apply {
                employees.filter { it.id in cooks }.forEach { employee ->
                    addView(RadioButton(this@CookShiftActivity).apply {
                        id = View.generateViewId(); text = CookEmployeeLabels.label(employee, employees); setTextColor(Color.WHITE)
                        textSize = 16f; minHeight = dp(48); buttonTintList = choiceTint()
                        isChecked = employee.id == mainCook
                        isEnabled = !CookEmployeeLabels.ambiguous(employee, employees)
                        setOnClickListener { mainCook = employee.id }
                    })
                }
            })
        }
        content.addView(label("Операторы", 20f))
        employees.filter { "operator" in it.roles }.forEach { employee ->
            content.addView(CheckBox(this).apply {
                text = CookEmployeeLabels.label(employee, employees); setTextColor(Color.WHITE); isChecked = employee.id in operators
                textSize = 16f; minHeight = dp(48); buttonTintList = choiceTint()
                isEnabled = !CookEmployeeLabels.ambiguous(employee, employees) || isChecked
                setOnCheckedChangeListener { _, checked -> if (checked) operators.add(employee.id) else operators.remove(employee.id); render() }
            })
        }
        content.addView(action("Добавить сотрудника") { addEmployee() })
        content.addView(action("Подтвердить смену", primary = true) { saveShift() }.apply { isEnabled = !busy })
    }

    private fun addEmployee() {
        val name = EditText(this).apply { hint = "Имя сотрудника"; setSingleLine() }
        val choices = arrayOf("Повар", "Оператор")
        val selected = booleanArrayOf(true, false)
        val dialog = AlertDialog.Builder(this).setTitle("Сотрудник").setView(name)
            .setMultiChoiceItems(choices, selected) { _, index, value -> selected[index] = value }
            .setNegativeButton("Отмена", null).setPositiveButton("Добавить", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = name.text.toString().trim().replace(Regex("\\s+"), " ")
                val roles = listOfNotNull("cook".takeIf { selected[0] }, "operator".takeIf { selected[1] })
                if (text.isBlank()) {
                    name.error = "Укажите имя сотрудника"
                    return@setOnClickListener
                }
                if (roles.isEmpty()) {
                    Toast.makeText(this, "Выберите должность", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val currentBinding = binding ?: return@setOnClickListener
                if (busy) return@setOnClickListener
                val pending = CookPendingEmployeeStore(CookLocalSession.preferences(applicationContext)).load()
                val isPendingRetry = pending != null && pending.binding == currentBinding && pending.name == text &&
                    pending.roles == roles && !offlineMode && !preview
                if (!isPendingRetry && CookEmployeeLabels.nameExists(text, employees)) {
                    name.error = "Такое имя уже есть. Для другого сотрудника укажите отличающееся имя."
                    return@setOnClickListener
                }
                dialog.dismiss()
                busy = true
                scope.launch {
                    val result = runCatching { withContext(Dispatchers.IO) {
                        val store = CookPendingEmployeeStore(CookLocalSession.preferences(applicationContext))
                        val existing = store.load()
                        if (existing != null) {
                            check(existing.binding == currentBinding && existing.name == text && existing.roles == roles && !offlineMode && !preview) {
                                "Сначала повторите предыдущий запрос сотрудника при наличии сети"
                            }
                            val (client, device) = client()
                            check(client.initializeBinding(device) == currentBinding) { "Привязка филиала изменилась" }
                            val previous = requireNotNull(client.addManual(device, existing.requestId, text, roles, currentBinding))
                            check(store.clear()) { "Не удалось сохранить подтверждение сотрудника" }
                            previous
                        } else {
                            val scope = CookLocalSession.offlineScope(applicationContext, currentBinding)
                            val record = OfflineCookEmployee(scope.backendUrl, scope.deviceUuid, currentBinding.branchRef,
                                currentBinding.revision, UUID.randomUUID().toString(), UUID.randomUUID().toString(), text,
                                JSONArray(roles.sorted()).toString())
                            CookOfflineDatabase.get(applicationContext).queue().enqueueEmployee(record)
                            if (!preview) runCatching {
                                val city = getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE).getString(AppPrefs.KEY_CITY, "").orEmpty()
                                CookOfflineSyncWorker.enqueue(applicationContext, scope, city)
                            }
                            CookEmployee(record.provisionalId, record.name, roles)
                        }
                    } }
                    busy = false
                    result.onSuccess { added -> employees = employees.filterNot { it.id == added.id } + added; render() }
                        .onFailure { if (it is CancellationException) throw it else showError(it.message ?: "Не удалось добавить сотрудника") }
                }
            }
        }
        dialog.show()
    }

    private fun saveShift() {
        val binding = binding ?: return
        val timezone = binding.timezone ?: return
        val main = mainCook
        if (cooks.isEmpty() || operators.isEmpty() || main == null || main !in cooks) {
            showError("Выберите поваров, операторов и основного повара")
            return
        }
        if (employees.any { it.id in cooks + operators && CookEmployeeLabels.ambiguous(it, employees) }) {
            showError("У сотрудников одинаковые имена. Уточните имена в справочнике перед подтверждением смены.")
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
                val scope = CookLocalSession.offlineScope(applicationContext, binding)
                val dao = CookOfflineDatabase.get(applicationContext).queue()
                fun reference(id: String): JSONObject {
                    val temporary = dao.employee(scope.backendUrl, scope.deviceUuid, binding.branchRef, binding.revision, id) != null
                    return JSONObject().put("kind", if (temporary) "provisional" else "employee").put("id", id)
                }
                val hasProvisional = (selectedCooks + selectedOperators).any { id -> reference(id).getString("kind") == "provisional" }
                if (offlineMode || hasProvisional) {
                    val localShift = OfflineCookShift(scope.backendUrl, scope.deviceUuid, binding.branchRef, binding.revision,
                        pending.shift.id, pending.requestId, pending.shift.id, pending.shift.operationalDate,
                        pending.shift.timezone, pending.shift.confirmedAt,
                        JSONArray(selectedCooks.map(::reference)).toString(), JSONArray(selectedOperators.map(::reference)).toString(),
                        reference(main).toString())
                    dao.enqueueShift(localShift)
                    val cache = CookOfflineSelectionCache(CookLocalSession.preferences(applicationContext), dao, scope)
                    check(cache.save(localShift, employees)) { "Не удалось сохранить локальную смену" }
                    if (!preview) runCatching {
                        val city = getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE).getString(AppPrefs.KEY_CITY, "").orEmpty()
                        CookOfflineSyncWorker.enqueue(applicationContext, scope, city)
                    }
                } else {
                    val revision = if (preview) 1 else {
                        val (client, device) = client()
                        check(client.initializeBinding(device) == binding) { "Привязка филиала изменилась" }
                        val result = requireNotNull(client.confirmShift(device, pending.requestId, pending.shift, binding))
                        check(result.first == pending.shift.id && result.second > 0) { "Некорректное подтверждение сервера" }
                        result.second
                    }
                    val snapshot = CachedCookShift(binding, pending.shift.copy(revision = revision), employees)
                    check(CookLocalSession.cache(applicationContext).save(snapshot)) { "Не удалось сохранить смену" }
                    check(CookOfflineSelectionCache(CookLocalSession.preferences(applicationContext), dao, scope).clear()) {
                        "Не удалось обновить выбор смены"
                    }
                }
                check(store.clear()) { "Не удалось сохранить подтверждение смены" }
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
