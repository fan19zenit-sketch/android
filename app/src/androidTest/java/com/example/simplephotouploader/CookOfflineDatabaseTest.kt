package com.example.simplephotouploader

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.io.IOException
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class CookOfflineDatabaseTest {
    private lateinit var db: CookOfflineDatabase
    private lateinit var dao: CookOfflineDao
    private val employeeId = "11111111-1111-4111-8111-111111111111"
    private val employeeRequest = "22222222-2222-4222-8222-222222222222"
    private val shiftId = "33333333-3333-4333-8333-333333333333"
    private val shiftRequest = "44444444-4444-4444-8444-444444444444"
    private val serverShift = "55555555-5555-4555-8555-555555555555"

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, CookOfflineDatabase::class.java).allowMainThreadQueries().build()
        dao = db.queue()
    }

    @After fun cleanup() = db.close()

    private fun employee() = OfflineCookEmployee("https://example.test", "device-a", "branch", 1,
        employeeId, employeeRequest, "Повар", "[\"cook\"]")

    private fun shift() = OfflineCookShift("https://example.test", "device-a", "branch", 1,
        shiftId, shiftRequest, serverShift, "2026-11-06", "Europe/Moscow", "2026-11-06T11:00:00+03:00",
        "[{\"kind\":\"provisional\",\"id\":\"$employeeId\"}]",
        "[{\"kind\":\"employee\",\"id\":\"operator\"}]",
        "{\"kind\":\"provisional\",\"id\":\"$employeeId\"}")

    private fun response(request: Request, body: JSONObject) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(200).message("test").body(body.toString().toResponseBody()).build()

    private fun scope() = CookOfflineScope("https://example.test", "device-a", CookBinding("branch", 1, "Europe/Moscow"))

    private fun server(request: Request, failEmployee: Boolean = false): Response = when (request.url.encodedPath) {
        "/cook-v2/capabilities" -> response(request, JSONObject().put("schemaVersion", 1)
            .put("roster", true).put("shift", true).put("photoContext", true))
        "/cook-v2/binding/initialize" -> response(request, JSONObject().put("branchRef", "branch")
            .put("bindingRevision", 1).put("timezone", "Europe/Moscow"))
        "/cook-v2/employees/manual" -> {
            if (failEmployee) throw IOException("Simulated lost response")
            val body = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
            response(request, JSONObject().put("employeeId", "canonical-cook").put("displayName", body.getString("displayName"))
                .put("roles", body.getJSONArray("roles")).put("branchRef", "branch").put("bindingRevision", 1)
                .put("provisionalEmployeeId", body.getString("provisionalEmployeeId")))
        }
        "/cook-v2/shifts" -> {
            val body = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
            assertEquals("canonical-cook", body.getJSONArray("cooks").getString(0))
            assertEquals("canonical-cook", body.getString("mainCookId"))
            response(request, JSONObject().put("shiftId", body.getString("shiftId")).put("shiftRevision", 1)
                .put("branchRef", "branch").put("bindingRevision", 1).put("provisionalShiftId", body.getString("provisionalShiftId")))
        }
        else -> throw AssertionError(request.url.encodedPath)
    }

    @Test fun multipleEmployeesAndShiftsRemainScopedAndIdempotent() {
        val first = employee()
        val second = first.copy(provisionalId = "66666666-6666-4666-8666-666666666666",
            requestId = "77777777-7777-4777-8777-777777777777")
        dao.enqueueEmployee(first)
        dao.enqueueEmployee(first)
        dao.enqueueEmployee(second)
        dao.enqueueShift(shift())
        dao.enqueueShift(shift())
        assertEquals(2, dao.pendingEmployees(first.backendUrl, first.deviceUuid, first.branchRef, 1).size)
        assertEquals(1, dao.pendingShifts(first.backendUrl, first.deviceUuid, first.branchRef, 1).size)
        assertTrue(dao.pendingEmployees(first.backendUrl, "other-device", first.branchRef, 1).isEmpty())
        assertTrue(dao.pendingEmployees(first.backendUrl, first.deviceUuid, first.branchRef, 2).isEmpty())
        assertTrue(dao.pendingEmployees("https://other.test", first.deviceUuid, first.branchRef, 1).isEmpty())
    }

    @Test fun confirmedNewcomerRemainsAvailableOnlyForItsDeviceAndBinding() {
        val person = employee()
        dao.enqueueEmployee(person)
        dao.confirmEmployee(person.backendUrl, person.deviceUuid, person.branchRef, 1, person.provisionalId, "canonical-cook")
        assertTrue(dao.pendingEmployees(person.backendUrl, person.deviceUuid, person.branchRef, 1).isEmpty())
        assertEquals("canonical-cook", dao.confirmedEmployees(person.backendUrl, person.deviceUuid, person.branchRef, 1).single().canonicalId)
        assertTrue(dao.confirmedEmployees(person.backendUrl, "another-device", person.branchRef, 1).isEmpty())
        assertTrue(dao.confirmedEmployees(person.backendUrl, person.deviceUuid, person.branchRef, 2).isEmpty())
    }

    @Test fun identicalNamesRequireDisambiguation() {
        val first = CookEmployee("a", "Повар", listOf("cook"))
        val second = CookEmployee("b", "  ПОВАР  ", listOf("cook"))
        assertTrue(CookEmployeeLabels.ambiguous(first, listOf(second, first)))
        assertTrue(CookEmployeeLabels.ambiguous(second, listOf(first, second)))
        assertEquals("Повар · уточнить имя", CookEmployeeLabels.label(first, listOf(second, first)))
        assertFalse(CookEmployeeLabels.ambiguous(first, listOf(first)))
        assertEquals("Повар", CookEmployeeLabels.label(first, listOf(first)))
    }

    @Test fun requestAndProvisionalConflictsNeverReplaceStoredData() {
        val first = employee()
        dao.enqueueEmployee(first)
        try {
            dao.enqueueEmployee(first.copy(name = "Другое имя"))
            fail("Same identity must keep the original snapshot")
        } catch (_: IllegalArgumentException) { }
        try {
            dao.enqueueEmployee(first.copy(provisionalId = "66666666-6666-4666-8666-666666666666"))
            fail("A request ID must not be reused for a different identity")
        } catch (_: IllegalArgumentException) { }
        assertEquals(first, dao.employee(first.backendUrl, first.deviceUuid, first.branchRef, 1, first.provisionalId))
    }

    @Test fun acknowledgementsAreIdempotentAndCannotBeRemapped() {
        val person = employee()
        val currentShift = shift()
        dao.enqueueEmployee(person)
        dao.enqueueShift(currentShift)
        dao.confirmEmployee(person.backendUrl, person.deviceUuid, person.branchRef, 1, employeeId, "canonical")
        dao.confirmEmployee(person.backendUrl, person.deviceUuid, person.branchRef, 1, employeeId, "canonical")
        dao.confirmShift(person.backendUrl, person.deviceUuid, person.branchRef, 1, shiftId, 1)
        dao.enqueueEmployee(person)
        dao.enqueueShift(currentShift)
        assertTrue(dao.pendingEmployees(person.backendUrl, person.deviceUuid, person.branchRef, 1).isEmpty())
        assertTrue(dao.pendingShifts(person.backendUrl, person.deviceUuid, person.branchRef, 1).isEmpty())
        try {
            dao.confirmEmployee(person.backendUrl, person.deviceUuid, person.branchRef, 1, employeeId, "different")
            fail("A provisional employee cannot be remapped")
        } catch (_: IllegalArgumentException) { }
        try {
            dao.confirmShift(person.backendUrl, person.deviceUuid, person.branchRef, 1, shiftId, 2)
            fail("A provisional shift cannot be remapped")
        } catch (_: IllegalArgumentException) { }
        assertEquals("canonical", dao.employee(person.backendUrl, person.deviceUuid, person.branchRef, 1, employeeId)!!.canonicalId)
        assertEquals(1, dao.shift(person.backendUrl, person.deviceUuid, person.branchRef, 1, shiftId)!!.canonicalRevision)
    }

    @Test fun invalidRefsAndCrossScopeConfirmationsAreRejected() {
        val person = employee()
        try {
            dao.enqueueEmployee(person.copy(provisionalId = "invalid"))
            fail("Invalid provisional ID")
        } catch (_: IllegalArgumentException) { }
        try {
            dao.enqueueShift(shift().copy(mainCookJson = "{\"kind\":\"employee\",\"id\":\"other\"}"))
            fail("Main cook must be in the shift")
        } catch (_: IllegalArgumentException) { }
        dao.enqueueEmployee(person)
        try {
            dao.confirmEmployee(person.backendUrl, "other", person.branchRef, 1, employeeId, "canonical")
            fail("A different device cannot confirm the employee")
        } catch (_: IllegalArgumentException) { }
        assertNull(dao.employee(person.backendUrl, person.deviceUuid, person.branchRef, 1, employeeId)!!.canonicalId)
    }

    @Test fun pendingDataSurvivesDatabaseReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "offline-test-${UUID.randomUUID()}"
        try {
            val first = Room.databaseBuilder(context, CookOfflineDatabase::class.java, name).allowMainThreadQueries().build()
            try {
                val fileDb = first
                fileDb.queue().enqueueEmployee(employee())
                fileDb.queue().enqueueShift(shift())
            } finally { first.close() }
            val second = Room.databaseBuilder(context, CookOfflineDatabase::class.java, name).allowMainThreadQueries().build()
            try {
                val fileDb = second
                assertEquals(employee(), fileDb.queue().employee("https://example.test", "device-a", "branch", 1, employeeId))
                assertEquals(shift(), fileDb.queue().shift("https://example.test", "device-a", "branch", 1, shiftId))
            } finally { second.close() }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test fun syncRetriesSameIdentityAndResolvesEmployeesBeforeShift() {
        dao.enqueueEmployee(employee())
        dao.enqueueShift(shift())
        var failOnce = true
        val requestIds = mutableListOf<String>()
        val client = CookWorkflowClient("https://example.test") { request ->
            if (request.url.encodedPath == "/cook-v2/employees/manual") {
                requestIds.add(JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).getString("requestId"))
                if (failOnce) { failOnce = false; return@CookWorkflowClient server(request, failEmployee = true) }
            }
            server(request)
        }
        val sync = CookOfflineSync(dao, client)
        try {
            sync.sync(scope())
            fail("An uncertain response must not mark the employee confirmed")
        } catch (_: IOException) { }
        assertNull(dao.employee("https://example.test", "device-a", "branch", 1, employeeId)!!.canonicalId)
        assertEquals(CookOfflineSyncResult(1, 1, 0), sync.sync(scope()))
        assertEquals(listOf(employeeRequest, employeeRequest), requestIds)
        assertEquals("canonical-cook", dao.employee("https://example.test", "device-a", "branch", 1, employeeId)!!.canonicalId)
        assertEquals(1, dao.shift("https://example.test", "device-a", "branch", 1, shiftId)!!.canonicalRevision)
        assertEquals(CookOfflineSyncResult(0, 0, 0), sync.sync(scope()))
    }

    @Test fun syncDoesNotGuessMissingEmployeesOrUseAnotherBackend() {
        dao.enqueueShift(shift())
        var calls = 0
        val client = CookWorkflowClient("https://example.test") { request -> calls++; server(request) }
        val sync = CookOfflineSync(dao, client)
        assertEquals(CookOfflineSyncResult(0, 0, 1), sync.sync(scope()))
        assertNull(dao.shift("https://example.test", "device-a", "branch", 1, shiftId)!!.canonicalRevision)
        val before = calls
        try {
            sync.sync(scope().copy(backendUrl = "https://other.test"))
            fail("Different backend must be rejected before any network call")
        } catch (_: IOException) { }
        assertEquals(before, calls)
    }

    @Test fun interruptedBatchKeepsFirstConfirmationAndRetriesOnlyRemainingEmployee() {
        val first = employee()
        val second = first.copy(provisionalId = "66666666-6666-4666-8666-666666666666",
            requestId = "77777777-7777-4777-8777-777777777777", name = "Второй")
        dao.enqueueEmployee(first)
        dao.enqueueEmployee(second)
        val calls = mutableListOf<String>()
        var failSecond = true
        val client = CookWorkflowClient("https://example.test") { request ->
            if (request.url.encodedPath != "/cook-v2/employees/manual") return@CookWorkflowClient server(request)
            val body = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
            val provisional = body.getString("provisionalEmployeeId")
            calls.add(provisional)
            if (provisional == second.provisionalId && failSecond) {
                failSecond = false
                throw IOException("Simulated lost response")
            }
            response(request, JSONObject().put("employeeId", "canonical-$provisional")
                .put("displayName", body.getString("displayName")).put("roles", body.getJSONArray("roles"))
                .put("branchRef", "branch").put("bindingRevision", 1).put("provisionalEmployeeId", provisional))
        }
        val sync = CookOfflineSync(dao, client)
        try { sync.sync(scope()); fail("Expected the second request to fail") } catch (_: IOException) { }
        assertEquals("canonical-$employeeId", dao.employee(first.backendUrl, first.deviceUuid, first.branchRef, 1, first.provisionalId)!!.canonicalId)
        assertNull(dao.employee(second.backendUrl, second.deviceUuid, second.branchRef, 1, second.provisionalId)!!.canonicalId)
        assertEquals(CookOfflineSyncResult(1, 0, 0), sync.sync(scope()))
        assertEquals(listOf(first.provisionalId, second.provisionalId, second.provisionalId), calls)
    }

    @Test fun workerRetriesTemporaryNetworkErrorsButHoldsConflicts() {
        assertTrue(CookOfflineSyncWorker.shouldRetry(IOException("network")))
        assertTrue(CookOfflineSyncWorker.shouldRetry(CookApiHttpException(429)))
        assertTrue(CookOfflineSyncWorker.shouldRetry(CookApiHttpException(503)))
        assertFalse(CookOfflineSyncWorker.shouldRetry(CookApiHttpException(409)))
        assertFalse(CookOfflineSyncWorker.shouldRetry(CookApiProtocolException("missing acknowledgement")))
        assertFalse(CookOfflineSyncWorker.shouldRetry(CookOfflineConflict("binding changed")))
    }

    @Test fun workerDoesNotSendDataAfterCityChanges() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE)
        val previousCity = prefs.getString(AppPrefs.KEY_CITY, null)
        val previousBackend = prefs.getString(AppPrefs.KEY_BACKEND_URL, null)
        val previousDevice = prefs.getString(AppPrefs.KEY_DEVICE_UUID, null)
        try {
            prefs.edit().putString(AppPrefs.KEY_CITY, "Другой город")
                .putString(AppPrefs.KEY_BACKEND_URL, "https://example.test")
                .putString(AppPrefs.KEY_DEVICE_UUID, "device-a").commit()
            val input = workDataOf("backend" to "https://example.test", "device" to "device-a", "city" to "Исходный город",
                "branch" to "branch", "revision" to 1, "timezone" to "Europe/Moscow")
            val worker = TestListenableWorkerBuilder<CookOfflineSyncWorker>(context).setInputData(input).build()
            assertTrue(runBlocking { worker.doWork() } is ListenableWorker.Result.Success)
        } finally {
            prefs.edit().putString(AppPrefs.KEY_CITY, previousCity).putString(AppPrefs.KEY_BACKEND_URL, previousBackend)
                .putString(AppPrefs.KEY_DEVICE_UUID, previousDevice).commit()
        }
    }

    @Test fun activeOfflineSelectionSurvivesSyncAndRejectsOtherScope() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("offline-selection-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val selection = CookOfflineSelectionCache(prefs, dao, scope())
        dao.enqueueEmployee(employee())
        dao.enqueueShift(shift())
        val roster = listOf(CookEmployee(employeeId, "Повар", listOf("cook")),
            CookEmployee("operator", "Оператор", listOf("operator")))
        assertTrue(selection.save(shift(), roster))
        val before = selection.load()!!
        assertEquals(before, CookShiftSelection.choose(true, before, null))
        assertNull(CookShiftSelection.choose(true, null, before))
        assertEquals(before, CookShiftSelection.choose(false, null, before))
        assertEquals(shiftId, before.shift.id)
        assertEquals(employeeId, before.shift.mainCookId)
        dao.confirmEmployee(scope().backendUrl, scope().deviceUuid, "branch", 1, employeeId, "canonical")
        dao.confirmShift(scope().backendUrl, scope().deviceUuid, "branch", 1, shiftId, 1)
        assertEquals(before, selection.load())
        assertNull(CookOfflineSelectionCache(prefs, dao, scope().copy(deviceUuid = "other")).load())
        try {
            selection.save(shift(), roster.map { if (it.id == employeeId) it.copy(displayName = "Другой") else it })
            fail("Names must match the original provisional employee")
        } catch (_: IllegalArgumentException) { }
        assertEquals(before, selection.load())
    }
}
