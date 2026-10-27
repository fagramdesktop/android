package xie.fa.gram.ui.settings

import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import xie.fa.gram.InuConfig
import xie.fa.gram.SearchRegistry
import xie.fa.gram.helpers.InuUtils
import xie.fa.gram.helpers.lastfm.LastFmBioSyncHelper
import xie.fa.gram.helpers.lastfm.LastFmStorage

class UserProfileSettingsActivity() : SettingsPageActivity() {

    constructor(account: Int) : this() {
        setCurrentAccount(account)
    }

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuUserProfile)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuUserProfileHeader)))
        items.add(
            UItem.asCheck(
                TOGGLE_PROFILE_PHOTO_GRADIENT_FADE,
                LocaleController.getString(R.string.InuProfilePhotoGradientFade)
            ).setChecked(InuConfig.PROFILE_PHOTO_GRADIENT_FADE.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_REDUCE_PROFILE_MOTION,
                LocaleController.getString(R.string.InuReduceProfileMotion),
            ).setChecked(InuConfig.REDUCE_PROFILE_MOTION.value)
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_DISABLE_PROFILE_SCROLL_SNAP,
                R.string.InuDisableProfileScrollSnap,
                R.string.InuDisableProfileScrollSnapInfo,
                InuConfig.DISABLE_PROFILE_SCROLL_SNAP.value
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_PROFILE_PREFER_MEDIA_TAB,
                R.string.InuProfilePreferMediaTab,
                R.string.InuProfilePreferMediaTabInfo,
                InuConfig.PROFILE_PREFER_MEDIA_TAB.value
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_MEMBERS_FILTER,
                R.string.InuMembersFilter,
                R.string.InuMembersFilterInfo,
                InuConfig.MEMBERS_FILTER.value
            )
        )
        items.add(
            UItem.asCheck(
                TOGGLE_SHOW_PROFILE_GIFT_BUTTON,
                LocaleController.getString(R.string.InuShowProfileGiftButton)
            ).setChecked(InuConfig.SHOW_PROFILE_GIFT_BUTTON.value)
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuUserProfileInformation)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_CLICKABLE_BIO_LINKS,
                R.string.InuClickableBioLinks,
                R.string.InuClickableBioLinksInfo,
                InuConfig.CLICKABLE_BIO_LINKS.value
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_PROFILE_ID_MODE,
                LocaleController.getString(R.string.InuProfileIdMode),
                when (InuConfig.PROFILE_ID_MODE.value) {
                    InuConfig.ProfileIdModeItem.TELEGRAM_ID -> LocaleController.getString(R.string.InuProfileIdModeTelegram)
                    InuConfig.ProfileIdModeItem.BOT_API_ID -> LocaleController.getString(R.string.InuProfileIdModeBotApi)
                    else -> LocaleController.getString(R.string.InuProfileIdModeOff)
                }
            )
        )
        items.add(
            UItem.asCheck(
                TOGGLE_DISABLE_CHAT_TITLE_PHONE,
                LocaleController.getString(R.string.InuDisableChatTitlePhone)
            ).setChecked(InuConfig.DISABLE_CHAT_TITLE_PHONE.value)
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(HEADER_LASTFM, LocaleController.getString(R.string.InuLastFm)))
        val currentUsername = LastFmStorage.getUsername(currentAccount)
        items.add(
            UItem.asButton(
                BUTTON_LASTFM_USERNAME,
                LocaleController.getString(R.string.InuLastFmUsername),
                if (currentUsername.isNotEmpty()) currentUsername else LocaleController.getString(R.string.None)
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_LASTFM_SHOW_ON_PROFILE,
                R.string.InuLastFmShowOnProfile,
                R.string.InuLastFmShowOnProfileInfo,
                LastFmStorage.isShowOnProfile(currentAccount)
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_LASTFM_USE_OWN_KEY,
                R.string.InuLastFmUseOwnApiKey,
                R.string.InuLastFmUseOwnApiKeyInfo,
                LastFmStorage.isUseOwnApiKey(currentAccount)
            )
        )
        if (LastFmStorage.isUseOwnApiKey(currentAccount)) {
            val key = LastFmStorage.getPersonalApiKey(currentAccount)
            items.add(
                UItem.asButton(
                    BUTTON_LASTFM_PERSONAL_KEY,
                    LocaleController.getString(R.string.InuLastFmPersonalApiKey),
                    if (key.isNotEmpty()) "••••••••" else LocaleController.getString(R.string.None)
                )
            )
        }
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_PROFILE_PHOTO_GRADIENT_FADE -> {
                val new = InuConfig.PROFILE_PHOTO_GRADIENT_FADE.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_REDUCE_PROFILE_MOTION -> {
                val new = InuConfig.REDUCE_PROFILE_MOTION.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_PROFILE_SCROLL_SNAP -> {
                val new = InuConfig.DISABLE_PROFILE_SCROLL_SNAP.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_PROFILE_PREFER_MEDIA_TAB -> {
                val new = InuConfig.PROFILE_PREFER_MEDIA_TAB.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_MEMBERS_FILTER -> {
                val new = InuConfig.MEMBERS_FILTER.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_CLICKABLE_BIO_LINKS -> {
                val new = InuConfig.CLICKABLE_BIO_LINKS.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_SHOW_PROFILE_GIFT_BUTTON -> {
                val new = InuConfig.SHOW_PROFILE_GIFT_BUTTON.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            BUTTON_PROFILE_ID_MODE -> RadioItemOptions.show(
                this, view,
                listOf(
                    LocaleController.getString(R.string.InuProfileIdModeOff),
                    LocaleController.getString(R.string.InuProfileIdModeTelegram),
                    LocaleController.getString(R.string.InuProfileIdModeBotApi),
                ),
                InuConfig.PROFILE_ID_MODE.value,
            ) { which ->
                InuConfig.PROFILE_ID_MODE.value = which
            }

            TOGGLE_DISABLE_CHAT_TITLE_PHONE -> {
                val new = InuConfig.DISABLE_CHAT_TITLE_PHONE.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            BUTTON_LASTFM_USERNAME -> showUsernameDialog()

            TOGGLE_LASTFM_SHOW_ON_PROFILE -> {
                val new = !LastFmStorage.isShowOnProfile(currentAccount)
                LastFmStorage.setShowOnProfile(currentAccount, new)
                (view as? NotificationsCheckCell)?.isChecked = new
                (view as? TextCheckCell)?.isChecked = new
                LastFmBioSyncHelper.onShowOnProfileToggled(currentAccount)
            }

            TOGGLE_LASTFM_USE_OWN_KEY -> {
                val new = !LastFmStorage.isUseOwnApiKey(currentAccount)
                LastFmStorage.setUseOwnApiKey(currentAccount, new)
                listView.adapter.update(true)
            }

            BUTTON_LASTFM_PERSONAL_KEY -> showPersonalKeyDialog()
        }
    }

    private fun showUsernameDialog() {
        val ctx = context ?: return
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(AndroidUtilities.dp(24f), AndroidUtilities.dp(8f), AndroidUtilities.dp(24f), 0)
        }

        val input = EditText(ctx).apply {
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setHintTextColor(Theme.getColor(Theme.key_dialogTextHint))
            hint = LocaleController.getString(R.string.InuLastFmUsername)
            setText(LastFmStorage.getUsername(currentAccount))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
            textSize = 16f
        }
        container.addView(input, LinearLayout.LayoutParams(-1, -2))

        AlertDialog.Builder(ctx)
            .setTitle(LocaleController.getString(R.string.InuLastFmUsername))
            .setView(container)
            .setPositiveButton(LocaleController.getString(R.string.OK)) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName != LastFmStorage.getUsername(currentAccount)) {
                    LastFmStorage.setUsername(currentAccount, newName)
                    LastFmBioSyncHelper.onUsernameChanged(currentAccount)
                    listView.adapter.update(true)
                }
            }
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .show()
    }

    private fun showPersonalKeyDialog() {
        val ctx = context ?: return
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(AndroidUtilities.dp(24f), AndroidUtilities.dp(8f), AndroidUtilities.dp(24f), 0)
        }

        val input = EditText(ctx).apply {
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setHintTextColor(Theme.getColor(Theme.key_dialogTextHint))
            hint = LocaleController.getString(R.string.InuLastFmPersonalApiKey)
            setText(LastFmStorage.getPersonalApiKey(currentAccount))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
            textSize = 16f
        }
        container.addView(input, LinearLayout.LayoutParams(-1, -2))

        AlertDialog.Builder(ctx)
            .setTitle(LocaleController.getString(R.string.InuLastFmPersonalApiKey))
            .setView(container)
            .setPositiveButton(LocaleController.getString(R.string.OK)) { _, _ ->
                val newKey = input.text.toString().trim()
                LastFmStorage.setPersonalApiKey(currentAccount, newKey)
                listView.adapter.update(true)
            }
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .show()
    }

    companion object {
        private val TOGGLE_PROFILE_PHOTO_GRADIENT_FADE = InuUtils.generateId()
        private val TOGGLE_REDUCE_PROFILE_MOTION = InuUtils.generateId()
        private val TOGGLE_DISABLE_PROFILE_SCROLL_SNAP = InuUtils.generateId()
        private val TOGGLE_PROFILE_PREFER_MEDIA_TAB = InuUtils.generateId()
        private val TOGGLE_MEMBERS_FILTER = InuUtils.generateId()
        private val TOGGLE_CLICKABLE_BIO_LINKS = InuUtils.generateId()
        private val TOGGLE_SHOW_PROFILE_GIFT_BUTTON = InuUtils.generateId()
        private val BUTTON_PROFILE_ID_MODE = InuUtils.generateId()
        private val TOGGLE_DISABLE_CHAT_TITLE_PHONE = InuUtils.generateId()

        private val HEADER_LASTFM = InuUtils.generateId()
        private val BUTTON_LASTFM_USERNAME = InuUtils.generateId()
        private val TOGGLE_LASTFM_SHOW_ON_PROFILE = InuUtils.generateId()
        private val TOGGLE_LASTFM_USE_OWN_KEY = InuUtils.generateId()
        private val BUTTON_LASTFM_PERSONAL_KEY = InuUtils.generateId()

        @JvmField val PAGE = SearchRegistry.Page(
            slug = "user-profile",
            titleRes = R.string.InuUserProfile,
            iconRes = R.drawable.msg_openprofile,
            factory = ::UserProfileSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("profile-photo-gradient-fade", R.string.InuProfilePhotoGradientFade, TOGGLE_PROFILE_PHOTO_GRADIENT_FADE),
                SearchRegistry.Entry("reduce-profile-motion", R.string.InuReduceProfileMotion, TOGGLE_REDUCE_PROFILE_MOTION),
                SearchRegistry.Entry("disable-profile-scroll-snap", R.string.InuDisableProfileScrollSnap, TOGGLE_DISABLE_PROFILE_SCROLL_SNAP),
                SearchRegistry.Entry("profile-prefer-media-tab", R.string.InuProfilePreferMediaTab, TOGGLE_PROFILE_PREFER_MEDIA_TAB),
                SearchRegistry.Entry("members-filter", R.string.InuMembersFilter, TOGGLE_MEMBERS_FILTER),
                SearchRegistry.Entry("clickable-bio-links", R.string.InuClickableBioLinks, TOGGLE_CLICKABLE_BIO_LINKS),
                SearchRegistry.Entry("show-profile-gift-button", R.string.InuShowProfileGiftButton, TOGGLE_SHOW_PROFILE_GIFT_BUTTON),
                SearchRegistry.Entry("profile-id-mode", R.string.InuProfileIdMode, BUTTON_PROFILE_ID_MODE),
                SearchRegistry.Entry("disable-chat-title-phone", R.string.InuDisableChatTitlePhone, TOGGLE_DISABLE_CHAT_TITLE_PHONE),
                SearchRegistry.Entry("lastfm", R.string.InuLastFm, HEADER_LASTFM),
                SearchRegistry.Entry("lastfm-username", R.string.InuLastFmUsername, BUTTON_LASTFM_USERNAME),
                SearchRegistry.Entry("lastfm-show-on-profile", R.string.InuLastFmShowOnProfile, TOGGLE_LASTFM_SHOW_ON_PROFILE),
                SearchRegistry.Entry("lastfm-use-own-key", R.string.InuLastFmUseOwnApiKey, TOGGLE_LASTFM_USE_OWN_KEY),
                SearchRegistry.Entry("lastfm-personal-key", R.string.InuLastFmPersonalApiKey, BUTTON_LASTFM_PERSONAL_KEY),
            ),
        )
    }
}
