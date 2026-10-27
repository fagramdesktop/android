package xie.fa.gram.helpers.dialogs

import android.content.Context
import android.content.SharedPreferences
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC

/**
 * Built-in local folders.
 *
 * They are ordinary [MessagesController.DialogFilter] entries flagged as `local`, generated from
 * the per-account recipe instead of from the server, so they are never uploaded and never removed
 * by a remote filter sync. The per-account recipe is the single source of truth: the rows in
 * `dialog_filter` are just a device-local cache rebuilt by [ensureLocalFilters].
 */
object LocalFolderHelper {

    /** Deterministic filter id per type, far above the 2..255 range the server hands out. */
    const val FILTER_ID_BASE = 1000

    private const val PREFS_NAME = "inu_local_folders"
    private const val KEY_RECIPE = "recipe"

    private const val DEFAULT_RECIPE = "!USERS,!GROUPS,!SUPERGROUPS,!BASIC_GROUPS,!CHANNELS,!BOTS,!ADMIN,!UNREAD,!UNMUTED"

    enum class FolderType(val filterType: Int) {
        USERS(MessagesController.DIALOG_FILTER_TYPE_USERS),
        GROUPS(MessagesController.DIALOG_FILTER_TYPE_GROUPS_ALL),
        SUPERGROUPS(MessagesController.DIALOG_FILTER_TYPE_MEGAGROUPS),
        BASIC_GROUPS(MessagesController.DIALOG_FILTER_TYPE_GROUPS),
        CHANNELS(MessagesController.DIALOG_FILTER_TYPE_CHANNELS),
        BOTS(MessagesController.DIALOG_FILTER_TYPE_BOTS),
        ADMIN(MessagesController.DIALOG_FILTER_TYPE_ADMIN),
        UNREAD(MessagesController.DIALOG_FILTER_TYPE_UNREAD),
        UNMUTED(MessagesController.DIALOG_FILTER_TYPE_UNMUTED);

        companion object {
            @JvmStatic
            fun of(filterType: Int): FolderType? {
                for (type in values()) {
                    if (type.filterType == filterType) {
                        return type
                    }
                }
                return null
            }
        }
    }

    class FolderState(@JvmField val type: FolderType, @JvmField var enabled: Boolean)

    @JvmStatic
    fun filterId(type: FolderType): Int = FILTER_ID_BASE + type.filterType

    @JvmStatic
    fun isLocalFilterId(id: Int): Boolean = id >= FILTER_ID_BASE

    @JvmStatic
    fun hasLocalFolders(account: Int): Boolean {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return false
        val filters = MessagesController.getInstance(account).dialogFilters
        if (filters != null) {
            for (i in filters.indices) {
                if (filters[i].local) return true
            }
        }
        return getEnabledFolders(account).isNotEmpty()
    }

    @JvmStatic
    fun canMoveAllChats(account: Int): Boolean {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return false
        return UserConfig.getInstance(account).isPremium || hasLocalFolders(account)
    }

    private const val KEY_ALL_CHATS_INDEX = "all_chats_tab_index"

    @JvmStatic
    fun getAllChatsIndex(account: Int): Int {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return 0
        return getPrefs(account).getInt(KEY_ALL_CHATS_INDEX, 0)
    }

    @JvmStatic
    fun saveAllChatsIndex(account: Int, index: Int) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return
        getPrefs(account).edit().putInt(KEY_ALL_CHATS_INDEX, index).apply()
    }

    private fun getPrefsName(account: Int): String =
        if (account == 0) PREFS_NAME else "${PREFS_NAME}_$account"

    private fun getPrefs(account: Int): SharedPreferences =
        ApplicationLoader.applicationContext.getSharedPreferences(getPrefsName(account), Context.MODE_PRIVATE)

    @JvmStatic
    fun getName(type: FolderType): String = when (type) {
        FolderType.USERS -> LocaleController.getString(R.string.PrivateChats)
        FolderType.GROUPS -> LocaleController.getString(R.string.FilterGroups)
        FolderType.SUPERGROUPS -> LocaleController.getString(R.string.InuBuiltInFolderSupergroups)
        FolderType.BASIC_GROUPS -> LocaleController.getString(R.string.InuBuiltInFolderBasicGroups)
        FolderType.CHANNELS -> LocaleController.getString(R.string.FilterChannels)
        FolderType.BOTS -> LocaleController.getString(R.string.FilterBots)
        FolderType.ADMIN -> LocaleController.getString(R.string.InuBuiltInFolderAdmin)
        FolderType.UNREAD -> LocaleController.getString(R.string.InuBuiltInFolderUnread)
        FolderType.UNMUTED -> LocaleController.getString(R.string.InuBuiltInFolderUnmuted)
    }

    @JvmStatic
    fun getEmoticon(type: FolderType): String = when (type) {
        FolderType.USERS -> "\uD83D\uDC64" // 👤
        FolderType.GROUPS -> "\uD83D\uDC65" // 👥
        FolderType.SUPERGROUPS -> "\uD83D\uDC65" // 👥
        FolderType.BASIC_GROUPS -> "\uD83D\uDC65" // 👥
        FolderType.CHANNELS -> "\uD83D\uDCE2" // 📢
        FolderType.BOTS -> "\uD83E\uDD16" // 🤖
        FolderType.ADMIN -> "\uD83D\uDC51" // 👑
        FolderType.UNREAD -> "\uD83D\uDCEB" // 📫
        FolderType.UNMUTED -> "\uD83D\uDD14" // 🔔
    }

    @JvmStatic
    fun getFlags(type: FolderType): Int {
        val excludeArchived = MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_ARCHIVED
        return when (type) {
            FolderType.USERS ->
                MessagesController.DIALOG_FILTER_FLAG_CONTACTS or
                    MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS or excludeArchived
            FolderType.GROUPS ->
                MessagesController.DIALOG_FILTER_FLAG_GROUPS or excludeArchived
            FolderType.SUPERGROUPS ->
                MessagesController.DIALOG_FILTER_FLAG_GROUPS or excludeArchived
            FolderType.BASIC_GROUPS ->
                MessagesController.DIALOG_FILTER_FLAG_GROUPS or excludeArchived
            FolderType.CHANNELS ->
                MessagesController.DIALOG_FILTER_FLAG_CHANNELS or excludeArchived
            FolderType.BOTS ->
                MessagesController.DIALOG_FILTER_FLAG_BOTS or excludeArchived
            FolderType.ADMIN ->
                MessagesController.DIALOG_FILTER_FLAG_GROUPS or
                    MessagesController.DIALOG_FILTER_FLAG_CHANNELS or excludeArchived
            FolderType.UNREAD ->
                MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS or
                    MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_READ or excludeArchived
            FolderType.UNMUTED ->
                MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS or
                    MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_MUTED or excludeArchived
        }
    }

    @JvmStatic
    fun getDescription(type: FolderType): String = when (type) {
        FolderType.USERS ->
            LocaleController.getString(R.string.FilterContacts) + ", " + LocaleController.getString(R.string.FilterNonContacts)
        FolderType.GROUPS -> LocaleController.getString(R.string.FilterGroups)
        FolderType.SUPERGROUPS -> LocaleController.getString(R.string.InuBuiltInFolderSupergroups)
        FolderType.BASIC_GROUPS -> LocaleController.getString(R.string.InuBuiltInFolderBasicGroups)
        FolderType.CHANNELS -> LocaleController.getString(R.string.FilterChannels)
        FolderType.BOTS -> LocaleController.getString(R.string.FilterBots)
        FolderType.ADMIN -> LocaleController.getString(R.string.InuBuiltInFolderAdmin)
        FolderType.UNREAD -> LocaleController.getString(R.string.InuBuiltInFolderUnread)
        FolderType.UNMUTED -> LocaleController.getString(R.string.InuBuiltInFolderUnmuted)
    }

    @JvmStatic
    fun getSubtitle(filter: MessagesController.DialogFilter): String {
        val type = FolderType.of(filter.type) ?: return ""
        var subtitle = LocaleController.getString(R.string.InuLocalFolder) + ": " + getDescription(type)
        val exceptions = filter.alwaysShow.size + filter.neverShow.size
        if (exceptions > 0) {
            subtitle += ", " + LocaleController.formatPluralString("Exception", exceptions)
        }
        return subtitle
    }

    @JvmStatic
    fun getAllFolders(account: Int): ArrayList<FolderState> {
        var recipe = getPrefs(account).getString(KEY_RECIPE, null)
        if (recipe.isNullOrBlank()) {
            recipe = DEFAULT_RECIPE
        }

        val enabledTypes = HashSet<FolderType>()
        for (rawPart in recipe.split(",")) {
            val part = rawPart.trim()
            if (part.isEmpty() || part.startsWith("!")) {
                continue
            }
            try {
                enabledTypes.add(FolderType.valueOf(part))
            } catch (ignore: Exception) {
                continue
            }
        }

        val result = ArrayList<FolderState>()
        for (type in FolderType.values()) {
            result.add(FolderState(type, enabledTypes.contains(type)))
        }
        return result
    }

    @JvmStatic
    fun getEnabledFolders(account: Int): ArrayList<FolderState> {
        val enabled = ArrayList<FolderState>()
        for (state in getAllFolders(account)) {
            if (state.enabled) {
                enabled.add(state)
            }
        }
        return enabled
    }

    @JvmStatic
    fun saveFolders(account: Int, states: List<FolderState>) {
        val builder = StringBuilder()
        for (state in states) {
            if (builder.isNotEmpty()) {
                builder.append(',')
            }
            if (!state.enabled) {
                builder.append('!')
            }
            builder.append(state.type.name)
        }
        val recipe = builder.toString()
        val prefs = getPrefs(account)
        if (recipe != prefs.getString(KEY_RECIPE, null)) {
            prefs.edit().putString(KEY_RECIPE, recipe).apply()
        }
    }

    @JvmStatic
    fun setFolderEnabled(account: Int, type: FolderType, enabled: Boolean) {
        val states = getAllFolders(account)
        for (state in states) {
            if (state.type == type) {
                state.enabled = enabled
            }
        }
        saveFolders(account, states)
    }

    @JvmStatic
    fun disableFolder(account: Int, filterType: Int) {
        val type = FolderType.of(filterType) ?: return
        setFolderEnabled(account, type, false)
    }

    @JvmStatic
    fun getSuggestions(account: Int): ArrayList<TLRPC.TL_dialogFilterSuggested> {
        val result = ArrayList<TLRPC.TL_dialogFilterSuggested>()
        for (state in getAllFolders(account)) {
            if (state.enabled) {
                continue
            }
            val filter = TLRPC.TL_dialogFilter()
            filter.id = filterId(state.type)
            filter.title = TLRPC.TL_textWithEntities()
            filter.title.text = getName(state.type)
            filter.emoticon = getEmoticon(state.type)
            filter.flags = getFlags(state.type)

            val suggested = TLRPC.TL_dialogFilterSuggested()
            suggested.filter = filter
            suggested.description = LocaleController.getString(R.string.InuLocalFolder) + ": " + getDescription(state.type)
            result.add(suggested)
        }
        return result
    }

    @JvmStatic
    fun folderTypeOf(suggested: TLRPC.TL_dialogFilterSuggested?): FolderType? {
        val id = suggested?.filter?.id ?: return null
        if (!isLocalFilterId(id)) {
            return null
        }
        return FolderType.of(id - FILTER_ID_BASE)
    }

    @JvmStatic
    fun folderTypeOf(filterType: Int): FolderType? = FolderType.of(filterType)

    @JvmStatic
    fun ensureLocalFilters(account: Int) {
        val controller = MessagesController.getInstance(account)
        val storage = MessagesStorage.getInstance(account)
        val wanted = getEnabledFolders(account)

        var changed = false

        for (i in controller.dialogFilters.indices.reversed()) {
            val filter = controller.dialogFilters[i]
            if (!filter.local) {
                continue
            }
            val type = FolderType.of(filter.type)
            if (type == null || wanted.none { it.type == type }) {
                controller.removeFilter(filter)
                storage.deleteDialogFilter(filter)
                changed = true
            }
        }

        var nextOrder = 0
        for (filter in controller.dialogFilters) {
            nextOrder = maxOf(nextOrder, filter.order)
        }

        for (state in wanted) {
            val type = state.type
            val name = getName(type)
            val flags = getFlags(type)
            val emoticon = getEmoticon(type)

            val existing = controller.dialogFiltersById.get(filterId(type))
            if (existing == null) {
                val filter = MessagesController.DialogFilter()
                filter.id = filterId(type)
                filter.local = true
                filter.type = type.filterType
                filter.order = ++nextOrder
                filter.name = name
                filter.flags = flags
                filter.inu_emoticon = emoticon
                filter.color = -1
                filter.unreadCount = -1
                filter.pendingUnreadCount = -1
                controller.dialogFilters.add(filter)
                controller.dialogFiltersById.put(filter.id, filter)
                storage.saveDialogFilter(filter, false, true)
                changed = true
            } else if (existing.name != name || existing.flags != flags || existing.inu_emoticon != emoticon) {
                existing.name = name
                existing.flags = flags
                existing.inu_emoticon = emoticon
                storage.saveDialogFilter(existing, false, false)
                changed = true
            }
        }

        if (!changed) {
            return
        }
        controller.dialogFilters.sortWith(compareBy { it.order })
        controller.lockFiltersInternal()
        NotificationCenter.getInstance(account)
            .postNotificationName(NotificationCenter.dialogFiltersUpdated)
    }

    @JvmStatic
    fun saveOrderFromFilters(account: Int, filters: List<MessagesController.DialogFilter>) {
        for (i in filters.indices) {
            if (filters[i].isDefault) {
                saveAllChatsIndex(account, i)
                break
            }
        }
    }

    @JvmStatic
    fun applyRemoteFiltersOrder(
        account: Int,
        dialogFilters: ArrayList<MessagesController.DialogFilter>,
        filtersOrder: ArrayList<Integer>
    ): Boolean {
        if (dialogFilters.isEmpty()) {
            return false
        }

        val remoteServerIds = ArrayList<Int>()
        for (i in 0 until filtersOrder.size) {
            val id = filtersOrder[i].toInt()
            if (id != 0 && !isLocalFilterId(id)) {
                remoteServerIds.add(id)
            }
        }

        val currentServerIds = ArrayList<Int>()
        for (i in 0 until dialogFilters.size) {
            val f = dialogFilters[i]
            if (!f.local && !f.isDefault) {
                currentServerIds.add(f.id)
            }
        }

        val canMoveAllChats = canMoveAllChats(account)
        val defaultIndex = dialogFilters.indexOfFirst { it.isDefault }
        val defaultNeedsMove = !canMoveAllChats && defaultIndex > 0

        if (currentServerIds == remoteServerIds && !defaultNeedsMove) {
            return false
        }

        val filterById = HashMap<Int, MessagesController.DialogFilter>()
        for (f in dialogFilters) {
            filterById[f.id] = f
        }

        val seenIds = HashSet<Int>()
        val serverFiltersToPlace = ArrayList<MessagesController.DialogFilter>()
        for (id in remoteServerIds) {
            val f = filterById[id]
            if (f != null && seenIds.add(id)) {
                serverFiltersToPlace.add(f)
            }
        }

        val placedIds = HashSet<Int>()
        val newList = ArrayList<MessagesController.DialogFilter>()
        var serverIdx = 0

        for (i in 0 until dialogFilters.size) {
            val f = dialogFilters[i]
            if (f.local || f.isDefault) {
                if (placedIds.add(f.id)) {
                    newList.add(f)
                }
            } else {
                if (serverIdx < serverFiltersToPlace.size) {
                    val nextServerFilter = serverFiltersToPlace[serverIdx++]
                    if (placedIds.add(nextServerFilter.id)) {
                        newList.add(nextServerFilter)
                    }
                }
            }
        }

        while (serverIdx < serverFiltersToPlace.size) {
            val nextServerFilter = serverFiltersToPlace[serverIdx++]
            if (placedIds.add(nextServerFilter.id)) {
                newList.add(nextServerFilter)
            }
        }

        for (f in dialogFilters) {
            if (placedIds.add(f.id)) {
                newList.add(f)
            }
        }

        if (!canMoveAllChats) {
            val dIdx = newList.indexOfFirst { it.isDefault }
            if (dIdx > 0) {
                val def = newList.removeAt(dIdx)
                newList.add(0, def)
            }
        }

        dialogFilters.clear()
        dialogFilters.addAll(newList)
        for (i in 0 until dialogFilters.size) {
            dialogFilters[i].order = i
        }

        return true
    }
}
