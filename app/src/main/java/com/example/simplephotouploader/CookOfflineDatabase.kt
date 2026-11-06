package com.example.simplephotouploader

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Separate from the existing photo queue; no database is opened until requested. */
@Database(entities = [OfflineCookEmployee::class, OfflineCookShift::class], version = 1, exportSchema = false)
abstract class CookOfflineDatabase : RoomDatabase() {
    abstract fun queue(): CookOfflineDao

    companion object {
        @Volatile private var instance: CookOfflineDatabase? = null

        fun get(context: Context): CookOfflineDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, CookOfflineDatabase::class.java,
                "cook-offline-v2-db").build().also { instance = it }
        }
    }
}

@Entity(tableName = "offline_employees",
    primaryKeys = ["backendUrl", "deviceUuid", "branchRef", "bindingRevision", "provisionalId"],
    indices = [Index(value = ["backendUrl", "deviceUuid", "branchRef", "bindingRevision", "requestId"], unique = true)])
data class OfflineCookEmployee(
    val backendUrl: String, val deviceUuid: String, val branchRef: String, val bindingRevision: Int,
    val provisionalId: String, val requestId: String, val name: String, val rolesJson: String,
    val canonicalId: String? = null,
)

@Entity(tableName = "offline_shifts",
    primaryKeys = ["backendUrl", "deviceUuid", "branchRef", "bindingRevision", "provisionalId"],
    indices = [Index(value = ["backendUrl", "deviceUuid", "branchRef", "bindingRevision", "requestId"], unique = true)])
data class OfflineCookShift(
    val backendUrl: String, val deviceUuid: String, val branchRef: String, val bindingRevision: Int,
    val provisionalId: String, val requestId: String, val serverShiftId: String,
    val operationalDate: String, val timezone: String, val confirmedAt: String,
    val cooksJson: String, val operatorsJson: String, val mainCookJson: String,
    val canonicalRevision: Int? = null,
)

@Dao
abstract class CookOfflineDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract fun insertEmployee(value: OfflineCookEmployee): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract fun insertShift(value: OfflineCookShift): Long

    @Query("""SELECT * FROM offline_employees WHERE backendUrl=:backend AND deviceUuid=:device
        AND branchRef=:branch AND bindingRevision=:revision AND provisionalId=:id""")
    abstract fun employee(backend: String, device: String, branch: String, revision: Int, id: String): OfflineCookEmployee?

    @Query("""SELECT * FROM offline_shifts WHERE backendUrl=:backend AND deviceUuid=:device
        AND branchRef=:branch AND bindingRevision=:revision AND provisionalId=:id""")
    abstract fun shift(backend: String, device: String, branch: String, revision: Int, id: String): OfflineCookShift?

    @Query("""SELECT * FROM offline_shifts WHERE backendUrl=:backend AND deviceUuid=:device
        AND branchRef=:branch AND bindingRevision=:revision AND serverShiftId=:serverId LIMIT 1""")
    protected abstract fun shiftByServerId(backend: String, device: String, branch: String, revision: Int, serverId: String): OfflineCookShift?

    @Query("""SELECT * FROM offline_employees WHERE backendUrl=:backend AND deviceUuid=:device
        AND branchRef=:branch AND bindingRevision=:revision AND canonicalId IS NULL ORDER BY rowid""")
    abstract fun pendingEmployees(backend: String, device: String, branch: String, revision: Int): List<OfflineCookEmployee>

    @Query("""SELECT * FROM offline_shifts WHERE backendUrl=:backend AND deviceUuid=:device
        AND branchRef=:branch AND bindingRevision=:revision AND canonicalRevision IS NULL ORDER BY rowid""")
    abstract fun pendingShifts(backend: String, device: String, branch: String, revision: Int): List<OfflineCookShift>

    @Query("""UPDATE offline_employees SET canonicalId=:canonical WHERE backendUrl=:backend AND deviceUuid=:device
        AND branchRef=:branch AND bindingRevision=:revision AND provisionalId=:id
        AND (canonicalId IS NULL OR canonicalId=:canonical)""")
    protected abstract fun markEmployeeConfirmed(backend: String, device: String, branch: String, revision: Int, id: String, canonical: String): Int

    @Query("""UPDATE offline_shifts SET canonicalRevision=:canonical WHERE backendUrl=:backend AND deviceUuid=:device
        AND branchRef=:branch AND bindingRevision=:revision AND provisionalId=:id
        AND (canonicalRevision IS NULL OR canonicalRevision=:canonical)""")
    protected abstract fun markShiftConfirmed(backend: String, device: String, branch: String, revision: Int, id: String, canonical: Int): Int

    @Transaction
    open fun confirmEmployee(backend: String, device: String, branch: String, revision: Int, id: String, canonical: String) {
        require(canonical.isNotBlank() && canonical.length <= 128)
        require(markEmployeeConfirmed(backend, device, branch, revision, id, canonical) == 1) { "Conflicting employee confirmation" }
    }

    @Transaction
    open fun confirmShift(backend: String, device: String, branch: String, revision: Int, id: String, canonical: Int) {
        require(canonical > 0)
        require(markShiftConfirmed(backend, device, branch, revision, id, canonical) == 1) { "Conflicting shift confirmation" }
    }

    @Transaction
    open fun enqueueEmployee(value: OfflineCookEmployee) {
        CookOfflineValidation.employee(value)
        if (insertEmployee(value) == -1L) {
            val existing = employee(value.backendUrl, value.deviceUuid, value.branchRef, value.bindingRevision, value.provisionalId)
            require(existing?.copy(canonicalId = null) == value) { "Conflicting offline employee" }
        }
    }

    @Transaction
    open fun enqueueShift(value: OfflineCookShift) {
        CookOfflineValidation.shift(value)
        val byServerId = shiftByServerId(value.backendUrl, value.deviceUuid, value.branchRef, value.bindingRevision, value.serverShiftId)
        require(byServerId == null || byServerId.provisionalId == value.provisionalId) { "Conflicting server shift identity" }
        if (insertShift(value) == -1L) {
            val existing = shift(value.backendUrl, value.deviceUuid, value.branchRef, value.bindingRevision, value.provisionalId)
            require(existing?.copy(canonicalRevision = null) == value) { "Conflicting offline shift" }
        }
    }
}

object CookOfflineValidation {
    private fun identity(backend: String, device: String, branch: String, revision: Int, vararg ids: String) {
        require(backend.isNotBlank() && device.isNotBlank() && branch.isNotBlank() && revision > 0)
        ids.forEach { require(UUID.fromString(it).toString() == it) }
    }

    fun employee(value: OfflineCookEmployee) {
        identity(value.backendUrl, value.deviceUuid, value.branchRef, value.bindingRevision, value.provisionalId, value.requestId)
        val roles = JSONArray(value.rolesJson)
        val values = (0 until roles.length()).map { roles.getString(it) }
        require(value.name.isNotBlank() && value.name.length <= 256 && value.canonicalId == null)
        require(values.isNotEmpty() && values.size == values.toSet().size && values.all { it in setOf("cook", "operator") })
    }

    fun shift(value: OfflineCookShift) {
        identity(value.backendUrl, value.deviceUuid, value.branchRef, value.bindingRevision,
            value.provisionalId, value.requestId, value.serverShiftId)
        require(value.canonicalRevision == null)
        LocalDate.parse(value.operationalDate)
        ZoneId.of(value.timezone)
        OffsetDateTime.parse(value.confirmedAt)
        val cooks = references(value.cooksJson)
        val operators = references(value.operatorsJson)
        val main = reference(JSONObject(value.mainCookJson))
        require(cooks.isNotEmpty() && operators.isNotEmpty() && main in cooks)
        require(cooks.size == cooks.toSet().size && operators.size == operators.toSet().size)
        require(cooks.map { it.second }.distinct().size == cooks.size && operators.map { it.second }.distinct().size == operators.size)
    }

    private fun references(raw: String): List<Pair<String, String>> {
        val array = JSONArray(raw)
        return (0 until array.length()).map { reference(array.getJSONObject(it)) }
    }

    private fun reference(value: JSONObject): Pair<String, String> {
        require(value.length() == 2)
        val kind = value.getString("kind")
        val id = value.getString("id")
        require(kind in setOf("employee", "provisional") && id.isNotBlank() && id.length <= 128)
        if (kind == "provisional") require(UUID.fromString(id).toString() == id)
        return kind to id
    }
}
