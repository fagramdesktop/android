package xie.fa.gram.helpers.dialogs

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.DialogObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.tgnet.TLRPC
import xie.fa.gram.InuConfig

/**
 * Manages local-only pinned chats and the unified visual ordering of pinned dialogs
 * across the Main "All Chats" list, Archive, and real server-synced folders.
 */
object LocalPinHelper {

    private const val TAG = "LocalPinHelper"
    private const val PREFS_NAME = "inu_local_pins"

    class ContextPinState {
        val list = ArrayList<Long>()        // Visual order of ALL pinned chats in this context (real + local)
        val localSet = HashSet<Long>()     // Subset of dialogIds that are pinned locally only
        val indexMap = HashMap<Long, Int>() // O(1) dialogId -> visual index lookup for comparators
    }

    private val accountStates = ConcurrentHashMap<Int, ConcurrentHashMap<String, ContextPinState>>()
    private val lastSyncedRealOrder = ConcurrentHashMap<Int, ConcurrentHashMap<String, ArrayList<Long>>>()

    private fun getPrefsName(account: Int): String =
        if (account == 0) PREFS_NAME else "${PREFS_NAME}_$account"

    private fun getPrefs(account: Int): SharedPreferences =
        ApplicationLoader.applicationContext.getSharedPreferences(getPrefsName(account), Context.MODE_PRIVATE)

    @JvmStatic
    fun contextKeyForFolder(folderId: Int): String = "folder_$folderId"

    @JvmStatic
    fun contextKeyForFilter(filterId: Int): String = "filter_$filterId"

    private fun rebuildIndexMap(state: ContextPinState) {
        state.indexMap.clear()
        for (i in state.list.indices) {
            state.indexMap[state.list[i]] = i
        }
    }

    private fun getState(account: Int, contextKey: String): ContextPinState {
        val contexts = accountStates.computeIfAbsent(account) { ConcurrentHashMap() }
        contexts[contextKey]?.let { return it }

        val state = ContextPinState()
        val prefs = getPrefs(account)
        val jsonStr = prefs.getString(contextKey, null)
        if (!jsonStr.isNullOrBlank()) {
            try {
                val arr = JSONArray(jsonStr)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val id = obj.getLong("id")
                    val local = obj.optBoolean("local", false)
                    state.list.add(id)
                    if (local) {
                        state.localSet.add(id)
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Failed to parse pins for $contextKey on account $account", e)
            }
        }
        rebuildIndexMap(state)
        contexts[contextKey] = state
        return state
    }

    private fun saveState(account: Int, contextKey: String, state: ContextPinState) {
        rebuildIndexMap(state)
        val arr = JSONArray()
        for (id in state.list) {
            val obj = JSONObject()
            obj.put("id", id)
            obj.put("local", state.localSet.contains(id))
            arr.put(obj)
        }
        getPrefs(account).edit().putString(contextKey, arr.toString()).commit()
    }

    /**
     * Reconciles current merged list with real server state for a context.
     * Idempotent: repeated calls with the same real state do not change the list or disk.
     */
    @JvmStatic
    fun reconcile(account: Int, contextKey: String, realList: List<Long>): Boolean {
        if (!InuConfig.UNLIMITED_PINNED_CHATS.value) {
            return false
        }
        val state = getState(account, contextKey)
        val realSet = realList.toHashSet()
        var changed = false

        // Seed last synced real order for delta checks
        val orderMap = lastSyncedRealOrder.computeIfAbsent(account) { ConcurrentHashMap() }
        if (!orderMap.containsKey(contextKey)) {
            orderMap[contextKey] = ArrayList(realList)
        }

        // 1. Remove real pins that are no longer pinned on server
        val iterator = state.list.iterator()
        while (iterator.hasNext()) {
            val id = iterator.next()
            if (!state.localSet.contains(id) && !realSet.contains(id)) {
                iterator.remove()
                changed = true
            }
        }

        // 2. Identify real pins in realList missing from state.list and slot them by relative rank
        val existingSet = state.list.toHashSet()
        val missingReal = realList.filter { it !in existingSet }

        if (missingReal.isNotEmpty()) {
            changed = true
            for (newRealId in missingReal) {
                val realIdx = realList.indexOf(newRealId)
                // Find next real pin in realList that is already placed in state.list
                var insertPos = -1
                for (i in (realIdx + 1) until realList.size) {
                    val nextRealId = realList[i]
                    val idxInState = state.list.indexOf(nextRealId)
                    if (idxInState != -1) {
                        insertPos = idxInState
                        break
                    }
                }
                if (insertPos != -1) {
                    state.list.add(insertPos, newRealId)
                } else {
                    // Fallback: look for the last real pin preceding it
                    var prevPos = -1
                    for (i in (realIdx - 1) downTo 0) {
                        val prevRealId = realList[i]
                        val idxInState = state.list.indexOf(prevRealId)
                        if (idxInState != -1) {
                            prevPos = idxInState + 1
                            break
                        }
                    }
                    if (prevPos != -1) {
                        state.list.add(prevPos, newRealId)
                    } else {
                        state.list.add(0, newRealId)
                    }
                }
            }
        }

        // 3. Verify relative order of real pins matches realList
        val currentRealInState = state.list.filter { !state.localSet.contains(it) }
        val expectedRealInState = realList.filter { state.list.contains(it) }
        if (currentRealInState != expectedRealInState) {
            var realPointer = 0
            for (i in 0 until state.list.size) {
                if (!state.localSet.contains(state.list[i])) {
                    if (realPointer < expectedRealInState.size) {
                        state.list[i] = expectedRealInState[realPointer++]
                    }
                }
            }
            changed = true
        }

        if (changed) {
            saveState(account, contextKey, state)
            orderMap[contextKey] = ArrayList(realList)
        }
        return changed
    }

    @JvmStatic
    fun reconcileFolders(account: Int, allDialogs: List<TLRPC.Dialog>) {
        if (!InuConfig.UNLIMITED_PINNED_CHATS.value) {
            return
        }
        val real0 = ArrayList<TLRPC.Dialog>()
        val real1 = ArrayList<TLRPC.Dialog>()
        val size = allDialogs.size
        for (i in 0 until size) {
            if (i >= allDialogs.size) break
            val d = allDialogs[i] ?: continue
            if (d.pinned && d !is TLRPC.TL_dialogFolder) {
                if (d.folder_id == 0) real0.add(d)
                else if (d.folder_id == 1) real1.add(d)
            }
        }
        real0.sortByDescending { it.pinnedNum }
        real1.sortByDescending { it.pinnedNum }
        val controller = MessagesController.getInstance(account)
        if (controller.inu_isFolderLoaded(0)) {
            reconcile(account, contextKeyForFolder(0), real0.map { it.id })
        }
        if (controller.inu_isFolderLoaded(1)) {
            reconcile(account, contextKeyForFolder(1), real1.map { it.id })
        }
    }

    @JvmStatic
    fun getAllLocalPinnedDialogIds(account: Int): List<Long> {
        val result = HashSet<Long>()
        val prefs = getPrefs(account)
        for (key in prefs.all.keys) {
            if (key.startsWith("folder_") || key.startsWith("filter_")) {
                val state = getState(account, key)
                result.addAll(state.localSet)
            }
        }
        return ArrayList(result)
    }

    @JvmStatic
    fun reconcileFilter(account: Int, filter: MessagesController.DialogFilter) {
        if (!InuConfig.UNLIMITED_PINNED_CHATS.value || filter.local) {
            return
        }
        val realPinned = ArrayList<Long>()
        for (i in 0 until filter.pinnedDialogs.size()) {
            val did = filter.pinnedDialogs.keyAt(i)
            if (!DialogObject.isEncryptedDialog(did)) {
                realPinned.add(did)
            }
        }
        realPinned.sortWith { o1, o2 ->
            val idx1 = filter.pinnedDialogs.get(o1)
            val idx2 = filter.pinnedDialogs.get(o2)
            idx1.compareTo(idx2)
        }
        reconcile(account, contextKeyForFilter(filter.id), realPinned)
    }

    @JvmStatic
    fun isPinned(account: Int, folderId: Int, dialogId: Long): Boolean {
        if (!InuConfig.UNLIMITED_PINNED_CHATS.value) return false
        val state = getState(account, contextKeyForFolder(folderId))
        return state.indexMap.containsKey(dialogId)
    }

    @JvmStatic
    fun isFilterPinned(account: Int, filterId: Int, dialogId: Long): Boolean {
        if (!InuConfig.UNLIMITED_PINNED_CHATS.value) return false
        val state = getState(account, contextKeyForFilter(filterId))
        return state.indexMap.containsKey(dialogId)
    }

    @JvmStatic
    fun isLocalPin(account: Int, folderId: Int, dialogId: Long): Boolean {
        if (!InuConfig.UNLIMITED_PINNED_CHATS.value) return false
        val state = getState(account, contextKeyForFolder(folderId))
        return state.localSet.contains(dialogId)
    }

    @JvmStatic
    fun isFilterLocalPin(account: Int, filterId: Int, dialogId: Long): Boolean {
        if (!InuConfig.UNLIMITED_PINNED_CHATS.value) return false
        val state = getState(account, contextKeyForFilter(filterId))
        return state.localSet.contains(dialogId)
    }

    @JvmStatic
    fun getMergedOrderIndex(account: Int, folderId: Int, dialogId: Long): Int {
        val state = getState(account, contextKeyForFolder(folderId))
        return state.indexMap[dialogId] ?: Int.MAX_VALUE
    }

    @JvmStatic
    fun getFilterMergedOrderIndex(account: Int, filterId: Int, dialogId: Long): Int {
        val state = getState(account, contextKeyForFilter(filterId))
        return state.indexMap[dialogId] ?: Int.MAX_VALUE
    }

    @JvmStatic
    fun pinLocally(account: Int, folderId: Int, dialogId: Long) {
        val contextKey = contextKeyForFolder(folderId)
        val state = getState(account, contextKey)
        state.list.remove(dialogId)
        state.list.add(0, dialogId)
        state.localSet.add(dialogId)
        saveState(account, contextKey, state)
        MessagesController.getInstance(account).sortDialogs(null)
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload)
    }

    @JvmStatic
    fun pinFilterLocally(account: Int, filter: MessagesController.DialogFilter, dialogId: Long) {
        val contextKey = contextKeyForFilter(filter.id)
        val state = getState(account, contextKey)
        state.list.remove(dialogId)
        state.list.add(0, dialogId)
        state.localSet.add(dialogId)
        saveState(account, contextKey, state)
        MessagesController.getInstance(account).onFilterUpdate(filter)
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload)
    }

    @JvmStatic
    fun unpinLocally(account: Int, folderId: Int, dialogId: Long) {
        val contextKey = contextKeyForFolder(folderId)
        val state = getState(account, contextKey)
        state.list.remove(dialogId)
        state.localSet.remove(dialogId)
        saveState(account, contextKey, state)
        MessagesController.getInstance(account).sortDialogs(null)
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload)
    }

    @JvmStatic
    fun unpinFilterLocally(account: Int, filter: MessagesController.DialogFilter, dialogId: Long) {
        val contextKey = contextKeyForFilter(filter.id)
        val state = getState(account, contextKey)
        state.list.remove(dialogId)
        state.localSet.remove(dialogId)
        saveState(account, contextKey, state)
        MessagesController.getInstance(account).onFilterUpdate(filter)
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload)
    }

    @JvmStatic
    fun onMoveDialog(account: Int, folderId: Int, did1: Long, did2: Long) {
        if (!InuConfig.UNLIMITED_PINNED_CHATS.value) return
        val contextKey = contextKeyForFolder(folderId)
        val state = getState(account, contextKey)
        val idx1 = state.list.indexOf(did1)
        val idx2 = state.list.indexOf(did2)
        if (idx1 != -1 && idx2 != -1) {
            Collections.swap(state.list, idx1, idx2)
            saveState(account, contextKey, state)

            // Synchronously update in-memory pinnedNum on real TLRPC.Dialog objects
            // to eliminate any race condition / revert flicker if sortDialogs runs before RPC commit
            val mc = MessagesController.getInstance(account)
            val realDids = state.list.filter { !state.localSet.contains(it) }
            val realDialogs = realDids.mapNotNull { mc.dialogs_dict.get(it) }
            assignRealPinnedNums(realDialogs, realDids, ArrayList())
        }
    }

    @JvmStatic
    fun getRealPinnedDialogsOrder(account: Int, folderId: Int): ArrayList<Long> {
        val state = getState(account, contextKeyForFolder(folderId))
        val realList = ArrayList<Long>()
        for (id in state.list) {
            if (!state.localSet.contains(id)) {
                realList.add(id)
            }
        }
        return realList
    }

    @JvmStatic
    fun onMoveFilterDialog(account: Int, filter: MessagesController.DialogFilter, did1: Long, did2: Long) {
        if (!InuConfig.UNLIMITED_PINNED_CHATS.value) return
        val contextKey = contextKeyForFilter(filter.id)
        val state = getState(account, contextKey)
        val idx1 = state.list.indexOf(did1)
        val idx2 = state.list.indexOf(did2)
        if (idx1 != -1 && idx2 != -1) {
            Collections.swap(state.list, idx1, idx2)
            saveState(account, contextKey, state)
            if (!filter.local) {
                var realIdx = 0
                for (id in state.list) {
                    if (!state.localSet.contains(id) && filter.pinnedDialogs.indexOfKey(id) >= 0) {
                        filter.pinnedDialogs.put(id, realIdx++)
                    }
                }
            }
        }
    }

    @JvmStatic
    fun hasRealOrderChanged(account: Int, folderId: Int, newRealDids: ArrayList<Long>): Boolean {
        if (!InuConfig.UNLIMITED_PINNED_CHATS.value) return true
        val contextKey = contextKeyForFolder(folderId)
        val map = lastSyncedRealOrder.computeIfAbsent(account) { ConcurrentHashMap() }
        val prev = map[contextKey]
        if (prev != null && prev == newRealDids) {
            return false
        }
        map[contextKey] = ArrayList(newRealDids)
        return true
    }

    @JvmStatic
    fun hasRealFilterOrderChanged(account: Int, filter: MessagesController.DialogFilter): Boolean {
        if (!InuConfig.UNLIMITED_PINNED_CHATS.value) return true
        if (filter.local) return false
        val state = getState(account, contextKeyForFilter(filter.id))
        val currentRealInState = ArrayList<Long>()
        for (did in state.list) {
            if (!state.localSet.contains(did) && filter.pinnedDialogs.indexOfKey(did) >= 0) {
                currentRealInState.add(did)
            }
        }
        val contextKey = contextKeyForFilter(filter.id)
        val map = lastSyncedRealOrder.computeIfAbsent(account) { ConcurrentHashMap() }
        val prev = map[contextKey]
        if (prev != null && prev == currentRealInState) {
            return false
        }
        map[contextKey] = ArrayList(currentRealInState)
        return true
    }

    @JvmStatic
    fun assignRealPinnedNums(dialogs: List<TLRPC.Dialog>, dids: List<Long>, pinned: ArrayList<Int>) {
        if (dids.isEmpty()) return
        val existingNums = ArrayList<Int>()
        for (did in dids) {
            for (d in dialogs) {
                if (d.id == did && d.pinnedNum > 0) {
                    existingNums.add(d.pinnedNum)
                    break
                }
            }
        }
        existingNums.sortDescending()
        pinned.clear()
        for (i in dids.indices) {
            val num = if (i < existingNums.size) existingNums[i] else maxOf(1, (existingNums.minOrNull() ?: 1) - 1)
            pinned.add(num)
            for (d in dialogs) {
                if (d.id == dids[i]) {
                    d.pinnedNum = num
                    break
                }
            }
        }
    }
}
