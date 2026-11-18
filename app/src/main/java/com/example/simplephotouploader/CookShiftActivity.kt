package com.example.simplephotouploader

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.Gravity
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private val assistants = linkedSetOf<String>()
    private val selectedPeople = linkedSetOf<String>()
    private var roleStage = false
    private var mainCook: String? = null
    private var mainCookImplicit = false
    private var busy = false
    private var offlineMode = false
    private var employeeVisibilityEnabled = false
    private var wizardStep = 0
    private var staffCount = 1
    private var operatorCount = 1
    private var cookCount = 1
    private var assistantCount = 0
    private var wizardChoice: String? = null
    private var wizard: CookShiftWizard? = null
    private var branchTemplate: CookBranchShiftTemplate? = null
    private var branchTemplateEnabled = false
    private var templateRefresh: Job? = null
    private var appliedTemplateRevision: Int? = null
    private var templateChangedDuringEdit = false
    private var refreshedTemplateEmployees: List<CookEmployee>? = null
    private var pizzaStep = 0
    private var pizzaCount = 1
    private val pizzaCooks = mutableListOf<String>()
    private var pizzaChoice: String? = null
    private val preview get() = BuildConfig.BUILD_TYPE == "preview"
    private val pizzaMode get() = BuildConfig.FLAVOR == "clean" &&
        getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE).getString(AppPrefs.KEY_DEVICE_MODE, AppPrefs.DEVICE_MODE_MAIN) == AppPrefs.DEVICE_MODE_PIZZA
    private data class LoadedRoster(val binding: CookBinding, val employees: List<CookEmployee>, val offline: Boolean,
                                    val template: CookBranchShiftTemplate? = null, val employeeVisibility: Boolean = false,
                                    val templateEnabled: Boolean = false)

    private val branded get() = BuildConfig.FLAVOR == "clean"
    private val ink get() = if (branded) getColor(R.color.sushi_ink) else Color.WHITE
    private val paper get() = if (branded) getColor(R.color.sushi_paper) else Color.rgb(17, 17, 17)
    private lateinit var footer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = paper
        if (branded) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        window.navigationBarColor = Color.rgb(17, 17, 17)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            setBackgroundColor(paper)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(paper)
            addView(content)
        }
        footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        setContentView(if (!branded) scroll else LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(paper)
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(ScrollView(this@CookShiftActivity).apply { addView(footer) })
        })
        loadRoster()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    override fun onStart() {
        super.onStart()
        if (!preview && !pizzaMode && BuildConfig.FLAVOR == "clean") {
            templateRefresh = scope.launch {
                while (true) {
                    delay(15_000)
                    refreshBranchTemplate()
                }
            }
        }
    }

    override fun onStop() {
        templateRefresh?.cancel()
        templateRefresh = null
        super.onStop()
    }

    private suspend fun refreshBranchTemplate() {
        val expectedBinding = binding ?: return
        if (!branchTemplateEnabled || busy) return
        val identity = CookLocalSession.identity(applicationContext)
        val updated = try {
            withContext(Dispatchers.IO) {
                val (client, device) = client()
                if (client.capabilities(device)?.branchShiftTemplate != true || client.initializeBinding(device) != expectedBinding)
                    return@withContext null
                val roster = client.roster(device) ?: return@withContext null
                if (roster.branchRef != expectedBinding.branchRef) return@withContext null
                val day = Instant.now().atZone(ZoneId.of(requireNotNull(expectedBinding.timezone))).toLocalDate().toString()
                val template = client.branchShift(device, day, expectedBinding)
                if (template != null && (template.cooks + template.operators + template.assistants)
                        .any { id -> roster.employees.none { it.id == id } }) return@withContext null
                roster to template
            }
        } catch (error: CancellationException) { throw error } catch (_: Exception) { null } ?: return
        if (binding != expectedBinding || CookLocalSession.identity(applicationContext) != identity || busy) return
        val old = branchTemplate
        val next = updated.second
        if (old == next) return
        branchTemplate = next
        if (wizardStep == 0) {
            employees = updated.first.employees
            render()
        } else {
            // Never discard a person's in-progress selection without an explicit action.
            refreshedTemplateEmployees = updated.first.employees
            templateChangedDuringEdit = true
            showTemplateChange()
        }
    }

    private fun showTemplateChange() {
        if (!templateChangedDuringEdit || content.findViewWithTag<View>("template-change") != null) return
        val notice = LinearLayout(this).apply {
            tag = "template-change"
            orientation = LinearLayout.VERTICAL
            addView(label("Состав в боте изменился", 16f, Color.rgb(255, 180, 210)))
            if (branchTemplate != null) addView(action("Показать новый состав") {
                refreshedTemplateEmployees?.let { employees = it }
                if (applyBranchTemplate()) {
                    templateChangedDuringEdit = false
                    refreshedTemplateEmployees = null
                }
                render()
            })
        }
        content.addView(notice, minOf(1, content.childCount))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()

    private fun label(text: String, size: Float, color: Int = ink): TextView = TextView(this).apply {
        this.text = text
        textSize = if (branded && size >= 20f) 26f else size
        if (size >= 20f) {
            if (BuildConfig.FLAVOR == "clean") sushiFont(R.attr.sushiHeadingFont)
            else setTypeface(typeface, Typeface.BOLD)
        }
        setTextColor(if (branded && color != ink) getColor(R.color.sushi_muted) else color)
        setPadding(0, dp(12), 0, dp(8))
    }

    private fun choiceTint() = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        if (branded) intArrayOf(ink, getColor(R.color.sushi_muted)) else intArrayOf(Color.rgb(224, 83, 153), Color.GRAY))

    private fun RadioButton.styleChoice() {
        if (BuildConfig.FLAVOR != "clean") return
        fun card(color: Int) = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat(); setColor(color)
        }
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), card(getColor(R.color.sushi_pink)))
            addState(intArrayOf(), card(Color.rgb(244, 239, 233)))
        }
        buttonDrawable = null
        sushiFont(R.attr.sushiLabelFont)
        gravity = if (text.toString().toIntOrNull() != null) Gravity.CENTER else Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(4), dp(14), dp(4))
        minHeight = dp(48)
        layoutParams = RadioGroup.LayoutParams(RadioGroup.LayoutParams.MATCH_PARENT, RadioGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) }
    }

    private fun RadioGroup.arrangeCounts() {
        val n = childCount
        if (!branded || n < 1 || resources.configuration.screenWidthDp - 40 < n * 52) return
        orientation = RadioGroup.HORIZONTAL
        for (i in 0 until n) getChildAt(i).layoutParams = RadioGroup.LayoutParams(0, -2, 1f)
            .apply { if (i > 0) marginStart = dp(4) }
    }

    private fun action(text: String, primary: Boolean = false, click: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 16f
        minHeight = dp(52)
        setTextColor(ink)
        if (BuildConfig.FLAVOR == "clean") {
            tag = if (primary) "primary-action" else null
            setTextColor(if (primary && (wizardStep != 0 || pizzaMode)) Color.WHITE else ink)
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.WHITE) }
            backgroundTintList = ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(getColor(R.color.sushi_line), if (primary && wizardStep == 0 && !pizzaMode) getColor(R.color.sushi_pink) else if (primary) ink else Color.rgb(239, 232, 224)))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
            stateListAnimator = null
            sushiFont(R.attr.sushiLabelFont)
        } else {
            backgroundTintList = ColorStateList.valueOf(if (primary) Color.rgb(186, 56, 127) else Color.rgb(50, 50, 50))
        }
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
                        val presentation = intent.getBooleanExtra("presentation_names", false)
                        val firstName = if (presentation) "Иванов Иван" else "Повар 1"
                        val secondName = if (intent.getBooleanExtra("test_duplicate_names", false)) firstName
                            else if (presentation) "Петрова Анна" else "Повар 2"
                        val operatorName = if (presentation) "Сидорова Мария" else "Оператор 1"
                        val firstRoles = if (intent.getBooleanExtra("test_universal", false)) listOf("cook", "operator") else listOf("cook")
                        val fixture = listOf(CookEmployee("cook-1", firstName, firstRoles),
                            CookEmployee("cook-2", secondName, listOf("cook")),
                            CookEmployee("operator-1", operatorName, listOf("operator"))) +
                            if (intent.getBooleanExtra("test_three_cooks", false))
                                listOf(CookEmployee("cook-3", "Повар 3", listOf("cook"))) else emptyList()
                        val template = if (intent.getBooleanExtra("test_branch_template", false))
                            CookBranchShiftTemplate("preview", Instant.now().atZone(ZoneId.of("Europe/Moscow")).toLocalDate().toString(),
                                "Europe/Moscow", 1, 3, listOf("operator-1"), listOf("cook-1", "cook-2"), emptyList()) else null
                        LoadedRoster(CookBinding("preview", 1, "Europe/Moscow"), fixture,
                            intent.getBooleanExtra("test_offline", false), template)
                    } else {
                        val local = CookLocalSession.preferences(applicationContext)
                        val expectedScope = CookLocalSession.identity(applicationContext)
                        try {
                            val (client, device) = client()
                            val capabilities = client.capabilities(device)
                            check(capabilities?.supportsAttributedCapture == true) { "Сервер пока не поддерживает смены" }
                            val current = client.initializeBinding(device)
                            check(current.timezone != null) { "Не настроен часовой пояс филиала" }
                            val roster = requireNotNull(client.roster(device))
                            check(roster.branchRef == current.branchRef) { "Привязка филиала изменилась" }
                            ensureActive()
                            check(CookLocalSession.identity(applicationContext) == expectedScope) { "Настройки подключения изменились" }
                            check(CookDeviceRosterCache(local).save(current, roster.employees)) { "Не удалось сохранить справочник" }
                            val template = if (capabilities.branchShiftTemplate) try {
                                val day = Instant.now().atZone(ZoneId.of(current.timezone)).toLocalDate().toString()
                                client.branchShift(device, day, current)?.takeIf { value ->
                                    val known = roster.employees.map { it.id }.toSet()
                                    (value.cooks + value.operators + value.assistants).all { it in known }
                                }
                            } catch (_: IOException) { null } else null
                            LoadedRoster(current, roster.employees, false, template, capabilities.employeeVisibility, capabilities.branchShiftTemplate)
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
            result.onSuccess { (newBinding, roster, wasOffline, template, visibilityEnabled, templateEnabled) ->
                binding = newBinding
                employees = roster
                offlineMode = wasOffline
                branchTemplate = template
                branchTemplateEnabled = templateEnabled
                employeeVisibilityEnabled = visibilityEnabled
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
                    cooks.addAll(previous.shift.cooks.filter { id -> roster.any { it.id == id } })
                    operators.addAll(previous.shift.operators.filter { id -> roster.any { it.id == id } })
                    selectedPeople.addAll(cooks + operators)
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
        if (BuildConfig.FLAVOR == "clean" && !(preview && intent.getBooleanExtra("test_legacy_shift_ui", false))) {
            footer.removeAllViews()
            if (pizzaMode) renderPizzaShift() else renderWizard()
            if (pizzaMode || wizardStep != 0) pinActions()
            return
        }
        content.removeAllViews()
        content.addView(label("Смена", 30f))
        if (offlineMode) content.addView(label("Нет сети", 16f, Color.rgb(255, 180, 210)))
        if (employees.any { CookEmployeeLabels.ambiguous(it, employees) }) {
            content.addView(label("Одинаковые имена: попросите управляющего уточнить сотрудников в общем списке.",
                16f, Color.rgb(255, 180, 210)))
        }
        if (!roleStage) {
            content.addView(label("Кто сегодня работает?", 20f))
            employees.forEach { employee ->
                content.addView(CheckBox(this).apply {
                    text = CookEmployeeLabels.label(employee, employees); setTextColor(ink)
                    isChecked = employee.id in selectedPeople
                    textSize = 16f; minHeight = dp(48); buttonTintList = choiceTint()
                    isEnabled = !CookEmployeeLabels.ambiguous(employee, employees) || isChecked
                    setOnCheckedChangeListener { button, checked ->
                        if (checked) selectedPeople.add(employee.id) else {
                            selectedPeople.remove(employee.id); cooks.remove(employee.id); operators.remove(employee.id)
                            if (mainCook == employee.id) {
                                mainCook = cooks.singleOrNull(); mainCookImplicit = mainCook != null
                            }
                        }
                        if (!checked && CookEmployeeLabels.ambiguous(employee, employees)) button.isEnabled = false
                    }
                })
            }
            content.addView(action("Добавить сотрудника") { addEmployee() })
            content.addView(action("Далее", primary = true) {
                if (selectedPeople.isEmpty()) { showError("Выберите сотрудников смены"); return@action }
                if (mainCook == null) { mainCook = cooks.singleOrNull(); mainCookImplicit = mainCook != null }
                roleStage = true; render()
            })
            return
        }
        content.addView(label("Кто кем работает сегодня?", 20f))
        content.addView(label("Отметьте поваров и оператора. Роли можно совмещать; оператор не обязателен.", 16f))
        employees.filter { it.id in selectedPeople }.forEach { employee ->
            content.addView(label(CookEmployeeLabels.label(employee, employees), 18f))
            content.addView(CheckBox(this).apply {
                tag = "role:${employee.id}:cook"
                text = "Повар"; setTextColor(ink); isChecked = employee.id in cooks
                textSize = 16f; minHeight = dp(48); buttonTintList = choiceTint()
                isEnabled = !CookEmployeeLabels.ambiguous(employee, employees) || isChecked
                setOnCheckedChangeListener { _, checked ->
                    if (checked) cooks.add(employee.id) else cooks.remove(employee.id)
                    if (!checked && (employee.id == mainCook || mainCook == null)) {
                        mainCook = cooks.singleOrNull(); mainCookImplicit = mainCook != null
                    } else if (checked && cooks.size > 1 && mainCookImplicit) {
                        mainCook = null; mainCookImplicit = false
                    } else if (checked && mainCook == null && cooks.size == 1) {
                        mainCook = employee.id; mainCookImplicit = true
                    }
                    render()
                }
            })
            content.addView(CheckBox(this).apply {
                tag = "role:${employee.id}:operator"
                text = "Оператор"; setTextColor(ink); isChecked = employee.id in operators
                textSize = 16f; minHeight = dp(48); buttonTintList = choiceTint()
                isEnabled = !CookEmployeeLabels.ambiguous(employee, employees) || isChecked
                setOnCheckedChangeListener { _, checked -> if (checked) operators.add(employee.id) else operators.remove(employee.id); render() }
            })
        }
        if (cooks.isNotEmpty()) {
            content.addView(label("Основной повар", 20f))
            if (cooks.size > 1) content.addView(label("Остальные повара — дополнительные", 16f))
            content.addView(RadioGroup(this).apply {
                employees.filter { it.id in cooks }.forEach { employee ->
                    addView(RadioButton(this@CookShiftActivity).apply {
                        id = View.generateViewId(); text = CookEmployeeLabels.label(employee, employees); setTextColor(ink)
                        textSize = 16f; minHeight = dp(48); buttonTintList = choiceTint()
                        isChecked = employee.id == mainCook
                        isEnabled = !CookEmployeeLabels.ambiguous(employee, employees)
                        setOnClickListener { mainCook = employee.id; mainCookImplicit = false }
                    })
                }
            })
        }
        content.addView(action("Назад к сотрудникам") { roleStage = false; render() })
        content.addView(action("Подтвердить смену", primary = true) { saveShift() }.apply { isEnabled = !busy })
    }

    private fun pinActions() {
        val buttons = (0 until content.childCount).map(content::getChildAt).filterIsInstance<Button>()
        val actions = listOfNotNull(buttons.find { it.text == "Назад" }, buttons.find { it.tag == "primary-action" })
        footer.setPadding(dp(18), dp(4), dp(18), dp(8))
        for (button in actions) {
            content.removeView(button)
            footer.addView(button, LinearLayout.LayoutParams(0, -2, if (button.tag == "primary-action") 2f else 1f)
                .apply { if (footer.childCount > 0) marginStart = dp(8) })
        }
    }

    private fun renderPizzaShift() {
        content.removeAllViews()
        (content.parent as? ScrollView)?.post { (content.parent as? ScrollView)?.scrollTo(0, 0) }
        content.addView(label("Пицца-цех", 16f))
        if (offlineMode) content.addView(label("Нет сети", 16f, Color.rgb(255, 180, 210)))
        if (pizzaStep == 0) {
            content.addView(label("Сколько поваров сегодня?", 20f))
            content.addView(RadioGroup(this).apply {
                (1..2).forEach { count ->
                    addView(RadioButton(this@CookShiftActivity).apply {
                        id = View.generateViewId(); text = count.toString(); setTextColor(ink)
                        textSize = 20f; minHeight = dp(48); buttonTintList = choiceTint()
                        styleChoice(); isChecked = count == pizzaCount; setOnClickListener { pizzaCount = count }
                    })
                }
                arrangeCounts()
            })
            content.addView(action("Далее", primary = true) {
                pizzaCooks.clear(); pizzaChoice = null; pizzaStep = 1; render()
            })
            return
        }
        if (pizzaStep <= pizzaCount) {
            content.addView(label("Выберите повара $pizzaStep", 20f))
            content.addView(RadioGroup(this).apply {
                employees.filterNot { it.id in pizzaCooks }.forEach { employee ->
                    addView(RadioButton(this@CookShiftActivity).apply {
                        id = View.generateViewId(); text = CookEmployeeLabels.label(employee, employees)
                        setTextColor(ink); textSize = 16f; minHeight = dp(48); buttonTintList = choiceTint()
                        isEnabled = !CookEmployeeLabels.ambiguous(employee, employees)
                        isChecked = pizzaChoice == employee.id; setOnClickListener { pizzaChoice = employee.id }
                        styleChoice()
                    })
                }
            })
            content.addView(action("Добавить сотрудника") { addEmployee() })
            if (employeeVisibilityEnabled) {
                content.addView(action("Убрать сотрудника") { hideEmployee() })
                content.addView(action("Вернуть сотрудника") { restoreEmployee() })
            }
            content.addView(action("Далее", primary = true) {
                val selected = pizzaChoice
                if (selected == null) showError("Выберите сотрудника") else {
                    pizzaCooks.add(selected); pizzaChoice = null; pizzaStep++; render()
                }
            })
            content.addView(action("Назад") {
                pizzaChoice = if (pizzaStep > 1) pizzaCooks.removeAt(pizzaCooks.lastIndex) else null
                pizzaStep--; render()
            })
            return
        }
        content.addView(label("Проверьте состав", 20f))
        pizzaCooks.forEach { id ->
            employees.firstOrNull { it.id == id }?.let { content.addView(label(CookEmployeeLabels.label(it, employees), 16f)) }
        }
        content.addView(action("Начать смену", primary = true) {
            cooks.clear(); cooks.addAll(pizzaCooks)
            operators.clear(); assistants.clear(); selectedPeople.clear(); selectedPeople.addAll(pizzaCooks)
            mainCook = pizzaCooks.firstOrNull()
            saveShift()
        }.apply { isEnabled = !busy })
        content.addView(action("Назад") {
            pizzaChoice = pizzaCooks.removeAt(pizzaCooks.lastIndex); pizzaStep--; render()
        })
    }

    private fun renderWizard() {
        content.removeAllViews()
        footer.setPadding(0, 0, 0, 0)
        content.gravity = if (wizardStep == 0) Gravity.CENTER_VERTICAL else Gravity.TOP
        (content.parent as? ScrollView)?.post { (content.parent as? ScrollView)?.scrollTo(0, 0) }
        if (offlineMode) content.addView(label("Нет сети", 16f, Color.rgb(255, 180, 210)))
        showTemplateChange()
        fun back(step: Int) { content.addView(action("Назад") { wizardChoice = null; wizardStep = step; render() }) }
        fun counts(title: String, values: IntRange, selected: Int, onNext: (Int) -> Unit) {
            content.addView(label(title, 20f))
            var choice = selected.coerceIn(values.first, values.last)
            content.addView(RadioGroup(this).apply {
                values.forEach { number ->
                    addView(RadioButton(this@CookShiftActivity).apply {
                        id = View.generateViewId(); text = number.toString(); setTextColor(ink)
                        textSize = 20f; minHeight = dp(48); buttonTintList = choiceTint()
                        isChecked = number == choice
                        setOnClickListener { choice = number }
                        styleChoice()
                    })
                }
                arrangeCounts()
            })
            content.addView(action("Далее", primary = true) { onNext(choice) })
        }
        when (wizardStep) {
            0 -> {
                val ids = intent.getStringArrayExtra("author_ids").orEmpty()
                val names = intent.getStringArrayExtra("author_names").orEmpty()
                if (ids.size > 1 && ids.size == names.size) {
                    content.addView(action("Основной автор на телефоне") {
                        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Кто готовит дальше?")
                            .setSingleChoiceItems(names, ids.indexOf(intent.getStringExtra("current_author"))) { dialog, index ->
                                setResult(RESULT_OK, android.content.Intent().putExtra("author_id", ids[index])
                                    .putExtra("shift_id", intent.getStringExtra("shift_id"))
                                    .putExtra("revision", intent.getIntExtra("revision", -1)))
                                dialog.dismiss(); finish()
                            }.setNegativeButton("Отмена", null).show()
                    })
                }
                if (branchTemplate != null) content.addView(label("Состав из бота", 16f))
                content.addView(action("Начать смену", primary = true) {
                    if (!applyBranchTemplate()) wizardStep = 1
                    render()
                }.apply { minHeight = dp(80) })
                return
            }
            1 -> {
                counts("Сколько сотрудников?", 1..6, staffCount) {
                    staffCount = it; wizardChoice = null; wizard = if (it == 1) CookShiftWizard(1) else null
                    wizardStep = if (it == 1) 5 else 2; render()
                }
                back(0); return
            }
            2 -> {
                counts("Сколько операторов?", 1..minOf(2, staffCount), operatorCount) {
                    operatorCount = it; wizardStep = 3; render()
                }
                back(1); return
            }
            3 -> {
                counts("Сколько поваров?", 1..minOf(4, staffCount), cookCount) {
                    cookCount = it; wizardStep = 4; render()
                }
                back(2); return
            }
            4 -> {
                counts("Сколько помощников?", 0..minOf(2, staffCount), assistantCount) {
                    assistantCount = it
                    val next = CookShiftWizard(staffCount)
                    if (runCatching { next.setCounts(operatorCount, cookCount, assistantCount) }.isFailure) {
                        showError("Указанных ролей недостаточно для всех сотрудников смены")
                    } else { wizard = next; wizardChoice = null; wizardStep = 5; render() }
                }
                back(3); return
            }
        }
        val draft = wizard ?: run { wizardStep = 1; render(); return }
        val roles = if (staffCount == 1) emptyList() else
            List(operatorCount) { CookShiftWizard.Role.OPERATOR } + List(cookCount) { CookShiftWizard.Role.COOK } +
                List(assistantCount) { CookShiftWizard.Role.ASSISTANT }
        val selectionEnd = 5 + if (staffCount == 1) 1 else roles.size
        if (wizardStep in 5 until selectionEnd) {
            val index = wizardStep - 5
            val role = if (staffCount == 1) null else roles[index]
            val number = if (role == null) 1 else roles.take(index + 1).count { it == role }
            val title = when (role) {
                null -> "Выберите сотрудника"
                CookShiftWizard.Role.OPERATOR -> "Выберите оператора $number"
                CookShiftWizard.Role.COOK -> "Выберите повара $number"
                CookShiftWizard.Role.ASSISTANT -> "Выберите помощника $number"
            }
            content.addView(label(title, 20f))
            val allowed = if (role == null) employees.map { it.id } else draft.available(role, employees.map { it.id })
            content.addView(RadioGroup(this).apply {
                employees.filter { it.id in allowed }.forEach { employee ->
                    addView(RadioButton(this@CookShiftActivity).apply {
                        id = View.generateViewId(); text = CookEmployeeLabels.label(employee, employees)
                        setTextColor(ink); textSize = 16f; minHeight = dp(48); buttonTintList = choiceTint()
                        isEnabled = !CookEmployeeLabels.ambiguous(employee, employees)
                        isChecked = employee.id == wizardChoice
                        setOnClickListener { wizardChoice = employee.id }
                        styleChoice()
                    })
                }
            })
            content.addView(action("Добавить сотрудника") { addEmployee() })
            if (employeeVisibilityEnabled) {
                content.addView(action("Убрать сотрудника") { hideEmployee() })
                content.addView(action("Вернуть сотрудника") { restoreEmployee() })
            }
            content.addView(action("Далее", primary = true) {
                val id = wizardChoice
                if (id == null) showError("Выберите сотрудника") else {
                    if (role == null) draft.selectSingle(id) else draft.select(role, id)
                    wizardChoice = null; wizardStep++; render()
                }
            })
            content.addView(action("Назад") {
                wizardChoice = null
                if (index == 0) { wizard = null; wizardStep = if (staffCount == 1) 1 else 4 }
                else { wizardChoice = draft.selected(roles[index - 1]).last(); draft.removeLast(roles[index - 1]); wizardStep-- }
                render()
            })
            return
        }
        if (wizardStep == selectionEnd && staffCount > 1 && cookCount > 1) {
            content.addView(label("Кто основной повар?", 20f))
            content.addView(RadioGroup(this).apply {
                draft.selected(CookShiftWizard.Role.COOK).forEach { employeeId ->
                    val employee = employees.firstOrNull { it.id == employeeId } ?: return@forEach
                    addView(RadioButton(this@CookShiftActivity).apply {
                        id = View.generateViewId(); text = CookEmployeeLabels.label(employee, employees)
                        setTextColor(ink); textSize = 16f; minHeight = dp(48); buttonTintList = choiceTint()
                        isChecked = employeeId == wizardChoice
                        setOnClickListener { wizardChoice = employeeId }
                        styleChoice()
                    })
                }
            })
            content.addView(action("Далее", primary = true) {
                val selected = wizardChoice
                if (selected == null) showError("Выберите основного повара") else {
                    draft.selectMainCook(selected); wizardChoice = null; wizardStep++; render()
                }
            })
            content.addView(action("Назад") {
                draft.removeLast(roles.last()); wizardChoice = null; wizardStep--; render()
            })
            return
        }
        content.addView(label("Проверьте состав", 20f))
        for ((role, title) in listOf(CookShiftWizard.Role.OPERATOR to "Оператор", CookShiftWizard.Role.COOK to "Повар",
                CookShiftWizard.Role.ASSISTANT to "Помощник")) {
            draft.selected(role).forEach { id ->
                val person = employees.firstOrNull { it.id == id }
                if (person != null) content.addView(label("$title: ${CookEmployeeLabels.label(person, employees)}" +
                    if (role == CookShiftWizard.Role.COOK && id == draft.mainCook) " · основной" else "", 16f))
            }
        }
        content.addView(action("Начать смену", primary = true) {
            if (appliedTemplateRevision != null && templateChangedDuringEdit) {
                showError("Состав в боте изменился. Откройте новый состав перед подтверждением.")
                return@action
            }
            if (!draft.isComplete()) { showError("Проверьте количество сотрудников и выбранный состав"); return@action }
            cooks.clear(); cooks.addAll(draft.selected(CookShiftWizard.Role.COOK))
            operators.clear(); operators.addAll(draft.selected(CookShiftWizard.Role.OPERATOR))
            assistants.clear(); assistants.addAll(draft.selected(CookShiftWizard.Role.ASSISTANT))
            selectedPeople.clear(); selectedPeople.addAll(cooks + operators + assistants)
            mainCook = draft.mainCook
            saveShift()
        }.apply { isEnabled = !busy })
        content.addView(action("Назад") {
            wizardChoice = null
            if (staffCount > 1 && cookCount > 1) wizardStep = selectionEnd
            else if (staffCount == 1) { wizard = CookShiftWizard(1); wizardStep = 5 }
            else { draft.removeLast(roles.last()); wizardStep = selectionEnd - 1 }
            render()
        })
    }

    private fun applyBranchTemplate(): Boolean {
        val template = branchTemplate ?: return false
        return runCatching {
            val people = employees.associateBy { it.id }
            require((template.operators + template.cooks + template.assistants).all {
                val person = people[it]
                person != null && !CookEmployeeLabels.ambiguous(person, employees)
            })
            staffCount = template.staffCount
            operatorCount = template.operators.size
            cookCount = template.cooks.size
            assistantCount = template.assistants.size
            val draft = CookShiftWizard(staffCount)
            if (staffCount == 1) draft.selectSingle(template.cooks.single()) else {
                draft.setCounts(operatorCount, cookCount, assistantCount)
                template.operators.forEach { draft.select(CookShiftWizard.Role.OPERATOR, it) }
                template.cooks.forEach { draft.select(CookShiftWizard.Role.COOK, it) }
                template.assistants.forEach { draft.select(CookShiftWizard.Role.ASSISTANT, it) }
            }
            wizard = draft
            appliedTemplateRevision = template.revision
            wizardChoice = null
            wizardStep = 5 + if (staffCount == 1) 1 else operatorCount + cookCount + assistantCount
            true
        }.getOrElse {
            showError("Не удалось применить состав из бота. Выберите смену вручную.")
            false
        }
    }

    private fun hideEmployee() {
        val available = employees.filterNot { CookEmployeeLabels.ambiguous(it, employees) }
        if (available.isEmpty()) { showError("Нет сотрудников, которых можно однозначно выбрать"); return }
        AlertDialog.Builder(this).setTitle("Убрать из списка филиала")
            .setItems(available.map { CookEmployeeLabels.label(it, employees) }.toTypedArray()) { _, index ->
                val person = available[index]
                AlertDialog.Builder(this).setTitle("Убрать сотрудника?")
                    .setMessage("${person.displayName} не будет предлагаться в новых сменах филиала. Прошлые фото сохранятся; текущая смена не изменится.")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Убрать") { _, _ ->
                        val current = binding ?: return@setPositiveButton
                        if (busy) return@setPositiveButton
                        busy = true
                        scope.launch {
                            val result = runCatching { withContext(Dispatchers.IO) {
                                val (client, device) = client()
                                check(client.capabilities(device)?.employeeVisibility == true) { "Сервер пока не поддерживает это действие" }
                                check(client.initializeBinding(device) == current) { "Привязка филиала изменилась" }
                                client.setEmployeeHidden(device, current, person.id, true)
                                CookDeviceRosterCache(CookLocalSession.preferences(applicationContext))
                                    .save(current, employees.filterNot { it.id == person.id })
                            } }
                            busy = false
                            result.onSuccess { cached ->
                                cooks.clear(); operators.clear(); assistants.clear(); selectedPeople.clear()
                                wizard = null; wizardStep = 0; pizzaCooks.clear(); pizzaStep = 0
                                if (!cached) {
                                    showError("Сотрудник убран на сервере, но список на телефоне не обновлён. Подключитесь к сети и откройте смену снова.")
                                    return@onSuccess
                                }
                                Toast.makeText(this@CookShiftActivity, "Сотрудник убран из новых смен", Toast.LENGTH_SHORT).show()
                                loadRoster()
                            }.onFailure { if (it is CancellationException) throw it else showError("Не удалось подтвердить изменение списка. Проверьте соединение.") }
                        }
                    }.show()
            }.show()
    }

    private fun restoreEmployee() {
        val current = binding ?: return
        if (busy) return
        busy = true
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) {
                val (client, device) = client()
                check(client.capabilities(device)?.employeeVisibility == true)
                check(client.initializeBinding(device) == current)
                client.hiddenEmployees(device, current)
            } }
            busy = false
            result.onSuccess { hidden ->
                val unique = hidden.filter { person -> hidden.count { it.displayName.trim().equals(person.displayName.trim(), true) } == 1 }
                if (unique.isEmpty()) { showError("Нет сотрудников, которых можно однозначно вернуть"); return@onSuccess }
                AlertDialog.Builder(this@CookShiftActivity).setTitle("Вернуть сотрудника")
                    .setItems(unique.map { it.displayName }.toTypedArray()) { _, index ->
                        val person = unique[index]
                        AlertDialog.Builder(this@CookShiftActivity).setMessage("Вернуть ${person.displayName} в список филиала?")
                            .setNegativeButton("Отмена", null).setPositiveButton("Вернуть") { _, _ ->
                                if (busy) return@setPositiveButton
                                busy = true
                                scope.launch {
                                    val restored = runCatching { withContext(Dispatchers.IO) {
                                        val (client, device) = client()
                                        check(client.initializeBinding(device) == current)
                                        client.setEmployeeHidden(device, current, person.id, false)
                                    } }
                                    busy = false
                                    restored.onSuccess {
                                        cooks.clear(); operators.clear(); assistants.clear(); selectedPeople.clear()
                                        wizard = null; wizardStep = 0; pizzaCooks.clear(); pizzaStep = 0
                                        Toast.makeText(this@CookShiftActivity, "Сотрудник возвращён", Toast.LENGTH_SHORT).show()
                                        loadRoster()
                                    }.onFailure { if (it is CancellationException) throw it else showError("Не удалось подтвердить изменение списка") }
                                }
                            }.show()
                    }.show()
            }.onFailure { if (it is CancellationException) throw it else showError("Не удалось загрузить список сотрудников") }
        }
    }

    private fun addEmployee() {
        val name = EditText(this).apply { hint = "Имя сотрудника"; setSingleLine() }
        val choices = arrayOf("Повар / помощник", "Оператор")
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
        if (selectedPeople.any { it !in cooks && it !in operators && it !in assistants }) {
            showError("Отметьте роль каждого выбранного сотрудника")
            return
        }
        if (cooks.isEmpty() || main == null || main !in cooks) {
            showError("Выберите поваров и основного повара")
            return
        }
        if (employees.any { it.id in cooks + operators + assistants && CookEmployeeLabels.ambiguous(it, employees) }) {
            showError("У сотрудников одинаковые имена. Уточните имена в справочнике перед подтверждением смены.")
            return
        }
        if (busy) return
        busy = true
        val selectedCooks = cooks.sorted()
        val selectedOperators = operators.sorted()
        val selectedAssistants = assistants.sorted()
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) {
                val zone = ZoneId.of(timezone)
                val now = Instant.now().atZone(zone)
                val store = CookPendingShiftStore(CookLocalSession.preferences(applicationContext))
                val prior = store.load()
                val pending = if (prior != null && prior.binding == binding && prior.shift.operationalDate == now.toLocalDate().toString()
                    && prior.shift.cooks == selectedCooks && prior.shift.operators == selectedOperators && prior.shift.mainCookId == main
                    && prior.shift.assistants == selectedAssistants) prior
                else PendingCookShift(UUID.randomUUID().toString(), binding,
                    CookShift(UUID.randomUUID().toString(), 0, binding.branchRef, now.toLocalDate().toString(), timezone,
                        now.toOffsetDateTime().toString(), selectedCooks, selectedOperators, main, selectedAssistants))
                check(store.save(pending)) { "Не удалось сохранить запрос смены" }
                val scope = CookLocalSession.offlineScope(applicationContext, binding)
                val dao = CookOfflineDatabase.get(applicationContext).queue()
                fun reference(id: String): JSONObject {
                    val temporary = dao.employee(scope.backendUrl, scope.deviceUuid, binding.branchRef, binding.revision, id) != null
                    return JSONObject().put("kind", if (temporary) "provisional" else "employee").put("id", id)
                }
                val hasProvisional = (selectedCooks + selectedOperators + selectedAssistants).any { id -> reference(id).getString("kind") == "provisional" }
                if (offlineMode || hasProvisional) {
                    val localShift = OfflineCookShift(scope.backendUrl, scope.deviceUuid, binding.branchRef, binding.revision,
                        pending.shift.id, pending.requestId, pending.shift.id, pending.shift.operationalDate,
                        pending.shift.timezone, pending.shift.confirmedAt,
                        JSONArray(selectedCooks.map(::reference)).toString(), JSONArray(selectedOperators.map(::reference)).toString(),
                        reference(main).toString(), assistantsJson = JSONArray(selectedAssistants.map(::reference)).toString())
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
                CookShiftPresenceWorker.enqueueNow(applicationContext)
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
