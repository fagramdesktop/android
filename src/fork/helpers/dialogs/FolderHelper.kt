package xie.fa.gram.helpers.dialogs

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.util.Pair
import androidx.core.content.edit
import androidx.core.graphics.withSave
import xie.fa.gram.InuConfig
import xie.fa.gram.helpers.icons.IconHelper
import xie.fa.gram.helpers.security.ParanoiaHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.FilterTabsView
import org.telegram.ui.Stories.recorder.HintView2
import kotlin.math.ceil
import kotlin.math.roundToInt

object FolderHelper {
    private val folderIcons = mapOf(
        // 1. All Chats / Main
        "\uD83D\uDCAC" to R.drawable.filter_all,      // 💬 speech balloon
        "\uD83D\uDCC1" to R.drawable.filter_all,      // 📁 file folder
        "\uD83D\uDCC2" to R.drawable.filter_all,      // 📂 open folder

        // 2. Personal / Private Chats / Direct Messages
        "\uD83D\uDC64" to R.drawable.filter_private,  // 👤 bust in silhouette
        "\uD83E\uDDD1" to R.drawable.filter_private,  // 🧑 person

        // 3. Groups / Supergroups
        "\uD83D\uDC65" to R.drawable.filter_groups,   // 👥 busts in silhouette
        "\uD83D\uDC6A" to R.drawable.filter_groups,   // 👪 family
        "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67" to R.drawable.filter_groups, // 👨‍👩‍👧
        "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC66" to R.drawable.filter_groups, // 👨‍👩‍👦
        "\u2734" to R.drawable.filter_groups,         // ✴️ eight-pointed star (legacy fallback)
        "\uD83C\uDDF4" to R.drawable.filter_groups,   // 🇴 regional letter O (legacy fallback)

        // 4. Channels / Broadcasts
        "\uD83D\uDCE2" to R.drawable.filter_channel,  // 📢 loudspeaker
        "\uD83D\uDCFB" to R.drawable.filter_channel,  // 📻 radio
        "\uD83D\uDCE3" to R.drawable.filter_channel,  // 📣 megaphone

        // 5. Bots
        "\uD83E\uDD16" to R.drawable.filter_bot,      // 🤖 robot face

        // 6. Unread
        "\uD83D\uDCEB" to R.drawable.filter_unread,   // 📫 mailbox with raised flag
        "\uD83D\uDCEC" to R.drawable.filter_unread,   // 📬 mailbox with raised flag and letter
        "\u2709" to R.drawable.filter_unread,         // ✉ envelope
        "\uD83D\uDCE9" to R.drawable.filter_unread,   // 📩 envelope with arrow
        "\uD83D\uDCE8" to R.drawable.filter_unread,   // 📨 incoming envelope
        "\uD83D\uDD14" to R.drawable.filter_unread,   // 🔔 bell
        "\u2705" to R.drawable.filter_unread,         // ✅ checkmark (legacy fallback)

        // 7. Work / Business / Office
        "\uD83D\uDCBC" to R.drawable.filter_work,     // 💼 briefcase

        // 8. Favorite / Starred
        "\u2B50" to R.drawable.filter_star,           // ⭐ star
        "\uD83C\uDF1F" to R.drawable.filter_star,     // 🌟 glowing star
        "\u2728" to R.drawable.filter_star,           // ✨ sparkles

        // 9. Muted
        "\uD83D\uDD15" to R.drawable.filter_muted,    // 🔕 bell with slash
        "\uD83D\uDD07" to R.drawable.filter_muted,    // 🔇 muted speaker

        // Standard categories
        "\uD83D\uDC31" to R.drawable.filter_cat,
        "\uD83D\uDCD5" to R.drawable.filter_book,
        "\uD83D\uDCB0" to R.drawable.filter_money,
        "\uD83C\uDFAE" to R.drawable.filter_game,
        "\uD83D\uDCA1" to R.drawable.filter_light,
        "\uD83D\uDC4C" to R.drawable.filter_like,
        "\uD83C\uDFB5" to R.drawable.filter_note,
        "\uD83C\uDFA8" to R.drawable.filter_palette,
        "\u2708" to R.drawable.filter_travel,
        "\u26BD" to R.drawable.filter_sport,
        "\uD83C\uDF93" to R.drawable.filter_study,
        "\uD83D\uDEEB" to R.drawable.filter_airplane,
        "\uD83D\uDC51" to R.drawable.filter_crown,
        "\uD83C\uDF39" to R.drawable.filter_flower,
        "\uD83C\uDFE0" to R.drawable.filter_home,
        "\u2764" to R.drawable.filter_love,
        "\uD83C\uDFAD" to R.drawable.filter_mask,
        "\uD83C\uDF78" to R.drawable.filter_party,
        "\uD83D\uDCC8" to R.drawable.filter_trade,
        "\uD83D\uDCCB" to R.drawable.filter_setup,
    )

    const val TAB_ICON_SIZE = 24
    const val ICON_GAP = 4

    private fun getIconSize(): Int = TAB_ICON_SIZE

    @JvmStatic
    fun saveMeta(storage: MessagesStorage, filters: List<MessagesController.DialogFilter>) {
        val db = storage.database ?: return
        db.executeFast("DELETE FROM inu_folder_meta").stepThis().dispose()
        val state = db.executeFast("REPLACE INTO inu_folder_meta VALUES(?, ?)")
        for (filter in filters) {
            state.requery()
            state.bindInteger(1, filter.id)
            state.bindString(2, filter.inu_emoticon ?: "")
            state.step()
        }
        state.dispose()
    }

    @JvmStatic
    fun loadMeta(storage: MessagesStorage, account: Int, filters: List<MessagesController.DialogFilter>) {
        val db = storage.database ?: return
        val map = HashMap<Int, String>()
        val cursor = db.queryFinalized("SELECT filter_id, emoticon FROM inu_folder_meta")
        while (cursor.next()) {
            map[cursor.intValue(0)] = cursor.stringValue(1)
        }
        cursor.dispose()
        var hasMissing = false
        for (filter in filters) {
            val cached = map[filter.id]
            if (cached == null) hasMissing = true
            filter.inu_emoticon = if (cached.isNullOrEmpty()) null else cached
        }
        if (hasMissing) {
            val userConfig = UserConfig.getInstance(account)
            userConfig.filtersLoaded = false
            userConfig.preferences.edit { putBoolean("filtersLoaded", false) }
        }
    }

    @JvmStatic
    fun getDefaultsFromFlags(filterFlags: Int): Pair<String, String> {
        val allChats = MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS
        var flags = filterFlags and allChats

        if (flags and allChats == allChats) {
            if (filterFlags and MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_READ != 0) {
                return Pair.create(getString(R.string.FilterNameUnread), "\uD83D\uDCEB")
            }
            if (filterFlags and MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_MUTED != 0) {
                return Pair.create(getString(R.string.FilterNameNonMuted), "\uD83D\uDD14")
            }
        } else if (filterFlags and MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_READ != 0) {
            return Pair.create(getString(R.string.FilterNameUnread), "\uD83D\uDCEB")
        } else if (flags and MessagesController.DIALOG_FILTER_FLAG_CONTACTS != 0) {
            flags = flags and MessagesController.DIALOG_FILTER_FLAG_CONTACTS.inv()
            if (flags == 0 || flags == MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS) {
                return Pair.create(getString(R.string.FilterContacts), "\uD83D\uDC64")
            }
        } else if (flags and MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS != 0) {
            flags = flags and MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS.inv()
            if (flags == 0) return Pair.create(getString(R.string.FilterNonContacts), "\uD83D\uDC64")
        } else if (flags and MessagesController.DIALOG_FILTER_FLAG_GROUPS != 0) {
            flags = flags and MessagesController.DIALOG_FILTER_FLAG_GROUPS.inv()
            if (flags == 0) return Pair.create(getString(R.string.FilterGroups), "\uD83D\uDC65")
        } else if (flags and MessagesController.DIALOG_FILTER_FLAG_BOTS != 0) {
            flags = flags and MessagesController.DIALOG_FILTER_FLAG_BOTS.inv()
            if (flags == 0) return Pair.create(getString(R.string.FilterBots), "\uD83E\uDD16")
        } else if (flags and MessagesController.DIALOG_FILTER_FLAG_CHANNELS != 0) {
            flags = flags and MessagesController.DIALOG_FILTER_FLAG_CHANNELS.inv()
            if (flags == 0) return Pair.create(getString(R.string.FilterChannels), "\uD83D\uDCE2")
        }

        return Pair.create("", "")
    }

    private fun isLegacyHackEmoticon(emoticon: String?): Boolean {
        if (emoticon == null) return false
        val stripped = emoticon.trim().replace("\uFE0F", "")
        return stripped == "\u2734" || stripped == "\uD83C\uDDF4"
    }

    @JvmStatic
    fun getEmoticonFromTitle(title: String?): String? {
        if (title.isNullOrBlank()) return null
        val lower = title.trim().lowercase()

        // 1. All Chats
        val allChatsStr = runCatching { getString(R.string.FilterAllChats).lowercase() }.getOrNull()
        if (lower == "all" || lower == "all chats" || lower == "main" || lower == allChatsStr) {
            return "\uD83D\uDCAC" // 💬
        }

        // 2. Unread
        val unreadStr = runCatching { getString(R.string.FilterNameUnread).lowercase() }.getOrNull()
        val inuUnreadStr = runCatching { getString(R.string.InuBuiltInFolderUnread).lowercase() }.getOrNull()
        if (lower.contains("unread") || lower == "inbox" || lower == unreadStr || lower == inuUnreadStr) {
            return "\uD83D\uDCEB" // 📫
        }

        // 3. Unmuted / Non-Muted (checked before muted so "unmuted" is not caught by "muted")
        val nonMutedStr = runCatching { getString(R.string.FilterNameNonMuted).lowercase() }.getOrNull()
        val inuUnmutedStr = runCatching { getString(R.string.InuBuiltInFolderUnmuted).lowercase() }.getOrNull()
        if (lower.contains("unmuted") || lower.contains("non-muted") || lower.contains("non muted") || lower == nonMutedStr || lower == inuUnmutedStr) {
            return "\uD83D\uDD14" // 🔔
        }

        // 4. Muted
        val mutedStr = runCatching { getString(R.string.NotificationsMuted).lowercase() }.getOrNull()
        if (lower.contains("muted") || lower == "mute" || lower == "silent" || (mutedStr != null && lower.contains(mutedStr))) {
            return "\uD83D\uDD15" // 🔕
        }

        // 5. Bots
        val botsStr = runCatching { getString(R.string.FilterBots).lowercase() }.getOrNull()
        if (lower.contains("bot") || lower == botsStr) {
            return "\uD83E\uDD16" // 🤖
        }

        // 6. Channels / Broadcasts
        val channelsStr = runCatching { getString(R.string.FilterChannels).lowercase() }.getOrNull()
        if (lower.contains("channel") || lower.contains("broadcast") || lower == channelsStr) {
            return "\uD83D\uDCE2" // 📢
        }

        // 7. Groups / Supergroups
        val groupsStr = runCatching { getString(R.string.FilterGroups).lowercase() }.getOrNull()
        val supergroupsStr = runCatching { getString(R.string.InuBuiltInFolderSupergroups).lowercase() }.getOrNull()
        val basicGroupsStr = runCatching { getString(R.string.InuBuiltInFolderBasicGroups).lowercase() }.getOrNull()
        if (lower.contains("group") || lower == groupsStr || lower == supergroupsStr || lower == basicGroupsStr) {
            return "\uD83D\uDC65" // 👥
        }

        // 8. Personal / Private / Direct Messages / Contacts
        val privateStr = runCatching { getString(R.string.PrivateChats).lowercase() }.getOrNull()
        val contactsStr = runCatching { getString(R.string.FilterContacts).lowercase() }.getOrNull()
        val nonContactsStr = runCatching { getString(R.string.FilterNonContacts).lowercase() }.getOrNull()
        if (lower.contains("personal") || lower.contains("private") || lower.contains("direct") || lower == "dm" || lower == "dms" || lower.contains("contact") || lower == privateStr || lower == contactsStr || lower == nonContactsStr) {
            return "\uD83D\uDC64" // 👤
        }

        // 9. Work / Business / Office
        if (lower.contains("work") || lower.contains("job") || lower.contains("business") || lower.contains("office") || lower.contains("corp")) {
            return "\uD83D\uDCBC" // 💼
        }

        // 10. Favorite / Starred
        val favStr = runCatching { getString(R.string.FavoriteStickersShort).lowercase() }.getOrNull()
        if (lower.contains("fav") || lower.contains("star") || lower == favStr) {
            return "\u2B50" // ⭐
        }

        // 11. Admin
        val adminStr = runCatching { getString(R.string.InuBuiltInFolderAdmin).lowercase() }.getOrNull()
        if (lower.contains("admin") || lower == adminStr) {
            return "\uD83D\uDC51" // 👑
        }

        return null
    }

    @JvmStatic
    fun resolveEmoticon(name: String?, flags: Int): String {
        return getEmoticonFromTitle(name) ?: getDefaultsFromFlags(flags).second
    }

    /** resolve (name, emoticon) for a non-default filter, with flag-based and title-based fallbacks */
    @JvmStatic
    fun getTabInfo(filter: MessagesController.DialogFilter): Pair<String, String> {
        val defaults = getDefaultsFromFlags(filter.flags)
        val name = filter.name?.takeIf { it.isNotEmpty() } ?: defaults.first
        var emoticon = filter.inu_emoticon?.takeIf { it.isNotEmpty() }
        if (emoticon == null || isLegacyHackEmoticon(emoticon)) {
            emoticon = getEmoticonFromTitle(name) ?: defaults.second.takeIf { it.isNotEmpty() }
        }
        return Pair.create(name, emoticon ?: "")
    }

    @JvmStatic
    fun getTabInfo(suggested: TLRPC.TL_dialogFilterSuggested?): Pair<String, String> {
        return getTabInfo(suggested?.filter)
    }

    @JvmStatic
    fun getTabInfo(filter: TLRPC.DialogFilter?): Pair<String, String> {
        if (filter == null) return Pair.create("", "")
        val defaults = getDefaultsFromFlags(filter.flags)
        val name = filter.title?.text?.takeIf { it.isNotEmpty() } ?: defaults.first
        var emoticon = filter.emoticon?.takeIf { it.isNotEmpty() }
        if (emoticon == null || isLegacyHackEmoticon(emoticon)) {
            emoticon = getEmoticonFromTitle(name) ?: defaults.second.takeIf { it.isNotEmpty() }
        }
        return Pair.create(name, emoticon ?: "")
    }

    @JvmStatic
    fun getEmoticon(filter: MessagesController.DialogFilter?): String? {
        if (filter == null) return null
        if (!filter.inu_emoticon.isNullOrEmpty() && !isLegacyHackEmoticon(filter.inu_emoticon)) {
            return filter.inu_emoticon
        }
        val fromTitle = getEmoticonFromTitle(filter.name)
        if (fromTitle != null) return fromTitle
        val defaults = getDefaultsFromFlags(filter.flags)
        return defaults.second.takeIf { it.isNotEmpty() }
    }

    @JvmStatic
    fun getEmoticon(filter: TLRPC.DialogFilter?): String? {
        if (filter == null) return null
        if (!filter.emoticon.isNullOrEmpty() && !isLegacyHackEmoticon(filter.emoticon)) {
            return filter.emoticon
        }
        val fromTitle = getEmoticonFromTitle(filter.title?.text)
        if (fromTitle != null) return fromTitle
        val defaults = getDefaultsFromFlags(filter.flags)
        return defaults.second.takeIf { it.isNotEmpty() }
    }

    @JvmStatic
    fun getEmoticon(suggested: TLRPC.TL_dialogFilterSuggested?): String? {
        return getEmoticon(suggested?.filter)
    }

    @JvmStatic
    @JvmOverloads
    fun getBaseTabIcon(emoticon: String?, isDefault: Boolean = false): Int {
        if (isDefault) return R.drawable.filter_all
        if (emoticon != null) {
            val stripped = emoticon.trim().replace("\uFE0F", "")
            return folderIcons[stripped] ?: R.drawable.filter_custom
        }
        return R.drawable.filter_custom
    }

    @JvmStatic
    @JvmOverloads
    fun getTabIcon(emoticon: String?, isDefault: Boolean = false): Int {
        val baseId = getBaseTabIcon(emoticon, isDefault)
        return IconHelper.getMappedResId(baseId)
    }

    @JvmStatic
    fun getTabDrawable(context: Context?, resId: Int): Drawable? {
        if (!needIcons() || resId == 0) return null
        return IconHelper.getDrawable(context, resId)?.mutate()
    }

    /** extra dp to add to titleWidth measurement when icon is shown alongside title */
    @JvmStatic
    fun getContentWidth(title: CharSequence?, textPaint: TextPaint): Int {
        val mode = InuConfig.FOLDERS_DISPLAY_MODE.value;
        if (mode == InuConfig.FoldersDisplayModeItem.ICONS_ONLY) return AndroidUtilities.dp(getIconSize().toFloat())

        var w = ceil(HintView2.measureCorrectly(title, textPaint)).toInt();
        if (mode == InuConfig.FoldersDisplayModeItem.TITLES_AND_ICONS) w += AndroidUtilities.dp((getIconSize() + ICON_GAP).toFloat());

        return w
    }

    @JvmStatic
    fun needIcons(): Boolean {
        return InuConfig.FOLDERS_DISPLAY_MODE.value != InuConfig.FoldersDisplayModeItem.TITLES
    }

    @JvmStatic
    fun isIconsOnly(): Boolean {
        return InuConfig.FOLDERS_DISPLAY_MODE.value == InuConfig.FoldersDisplayModeItem.ICONS_ONLY
    }

    @JvmStatic
    fun isTitleOnly(): Boolean {
        return InuConfig.FOLDERS_DISPLAY_MODE.value == InuConfig.FoldersDisplayModeItem.TITLES
    }

    /** text x offset to make room for icon in titles+icons mode */
    @JvmStatic
    fun getTextXOffset(): Float {
        if (InuConfig.FOLDERS_DISPLAY_MODE.value != InuConfig.FoldersDisplayModeItem.TITLES_AND_ICONS) return 0f
        return AndroidUtilities.dp((getIconSize() + ICON_GAP).toFloat()).toFloat()
    }

    /** draw the folder icon on the tab. call before drawing text. */
    @JvmStatic
    fun drawTabIcon(
        canvas: Canvas,
        icon: Drawable?,
        colorFilter: ColorFilter?,
        textX: Float,
        viewWidth: Int,
        viewHeight: Int
    ) {
        if (icon == null || !needIcons()) return

        val targetSize = AndroidUtilities.dp(getIconSize().toFloat())
        val srcW = icon.intrinsicWidth.takeIf { it > 0 } ?: targetSize
        val srcH = icon.intrinsicHeight.takeIf { it > 0 } ?: targetSize
        val scale = minOf(targetSize.toFloat() / srcW, targetSize.toFloat() / srcH)
        val drawW = (srcW * scale).roundToInt().coerceAtLeast(1)
        val drawH = (srcH * scale).roundToInt().coerceAtLeast(1)

        val drawX = textX + (targetSize - drawW) / 2f
        val drawY = (viewHeight - drawH) / 2f

        icon.colorFilter = colorFilter
        icon.setBounds(0, 0, drawW, drawH)
        canvas.withSave {
            translate(drawX, drawY)
            icon.draw(this)
        }
    }

    /** adjusted tab padding between tabs */
    @JvmStatic
    fun getTabPadding(): Float {
        if (isIconsOnly()) return 16f
        return FilterTabsView.TAB_PADDING_WIDTH
    }

    /** skip adding default "All Chats" tab when toggle is on AND user has other filters */
    @JvmStatic
    fun shouldSkipDefaultTab(totalFilters: Int): Boolean {
        return InuConfig.HIDE_ALL_CHATS_TAB.value && totalFilters > 1
    }

    /** if selectedType lands on the (now hidden) default filter, return first non-default index */
    @JvmStatic
    fun snapOffDefault(filters: List<MessagesController.DialogFilter>, selectedType: Int): Int {
        if (!shouldSkipDefaultTab(filters.size)) return selectedType
        if (selectedType !in filters.indices || !filters[selectedType].isDefault) return selectedType
        return filters.indexOfFirst { !it.isDefault }.takeIf { it >= 0 } ?: selectedType
    }

    /** after a rebuild that skipped the default tab, refresh selectedTabId + currentPosition */
    @JvmStatic
    fun refreshSelectedTab(filterTabsView: FilterTabsView, selectedType: Int, filtersSize: Int) {
        if (!InuConfig.HIDE_ALL_CHATS_TAB.value) return
        if (selectedType < 0 || selectedType >= filtersSize) return
        filterTabsView.selectTabWithId(selectedType, 1f)
    }

    @JvmStatic
    fun isMuteFilteringActive(): Boolean {
        val mode = InuConfig.FOLDERS_UNREAD_COUNTER_MODE.value
        return mode == InuConfig.FoldersUnreadCounterModeItem.EXCLUDE_MUTED ||
            mode == InuConfig.FoldersUnreadCounterModeItem.EXCLUDE_MUTED_NON_DMS
    }

    @JvmStatic
    @JvmOverloads
    fun shouldExcludeFromCounter(currentAccount: Int, dialogId: Long, user: TLRPC.User? = null): Boolean {
        if (ParanoiaHelper.isHidden(currentAccount, dialogId)) return true
        val mode = InuConfig.FOLDERS_UNREAD_COUNTER_MODE.value
        if (mode == InuConfig.FoldersUnreadCounterModeItem.EXCLUDE_MUTED_NON_DMS && dialogId > 0) {
            // human DM → never excluded; bot → excluded if muted; user info missing → defer to a later call
            if (user == null || !user.bot) return false
        }
        if (mode != InuConfig.FoldersUnreadCounterModeItem.EXCLUDE_MUTED &&
            mode != InuConfig.FoldersUnreadCounterModeItem.EXCLUDE_MUTED_NON_DMS
        ) return false
        return MessagesController.getInstance(currentAccount).isDialogMuted(dialogId, 0)
    }

    /** adjusted tab internal padding (indicator overshoot) */
    @JvmStatic
    fun getTabInternalPadding(): Float {
        if (isIconsOnly()) return FilterTabsView.TAB_INTERNAL_PADDING / 2f
        if (InuConfig.FOLDERS_DISPLAY_MODE.value == InuConfig.FoldersDisplayModeItem.TITLES_AND_ICONS) return 8f
        return FilterTabsView.TAB_INTERNAL_PADDING
    }
}
