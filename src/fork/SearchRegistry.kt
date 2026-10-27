package xie.fa.gram

import android.content.Intent
import xie.fa.gram.helpers.security.ParanoiaHelper
import xie.fa.gram.ui.settings.AnnoyancesSettingsActivity
import xie.fa.gram.ui.settings.AppearanceSettingsActivity
import xie.fa.gram.ui.settings.BackupSettingsActivity
import xie.fa.gram.ui.settings.BehaviorSettingsActivity
import xie.fa.gram.ui.settings.ChatsSettingsActivity
import xie.fa.gram.ui.settings.DialogsSettingsActivity
import xie.fa.gram.ui.settings.InuSettingsActivity
import xie.fa.gram.ui.settings.LastFmSettingsActivity
import xie.fa.gram.ui.settings.MessagesSettingsActivity
import xie.fa.gram.ui.settings.PrivacySecurityActivity
import xie.fa.gram.ui.settings.SettingsPageActivity
import xie.fa.gram.ui.settings.TranslatorSettingsActivity
import xie.fa.gram.ui.settings.UserProfileSettingsActivity
import xie.fa.gram.ui.settings.fonts.FontStackActivity
import xie.fa.gram.ui.settings.fonts.FontsSettingsActivity
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.LaunchActivity
import org.telegram.ui.ProfileActivity

object SearchRegistry {
    /**
     * @param itemId optional — runtime [org.telegram.ui.Components.UItem.id] to highlight on open.
     */
    data class Entry(val slug: String, val titleRes: Int, val itemId: Int = -1)

    data class Page(
        val slug: String,
        val titleRes: Int,
        val iconRes: Int,
        val factory: () -> SettingsPageActivity,
        val entries: List<Entry> = emptyList(),
    )

    private val pages: List<Page> by lazy {
        listOf(
            InuSettingsActivity.PAGE,
            AppearanceSettingsActivity.PAGE,
            FontsSettingsActivity.PAGE,
            FontStackActivity.PAGE,
            ChatsSettingsActivity.PAGE,
            MessagesSettingsActivity.PAGE,
            DialogsSettingsActivity.PAGE,
            UserProfileSettingsActivity.PAGE,
            AnnoyancesSettingsActivity.PAGE,
            BehaviorSettingsActivity.PAGE,
            TranslatorSettingsActivity.PAGE,
            PrivacySecurityActivity.PAGE,
            BackupSettingsActivity.PAGE,
            LastFmSettingsActivity.PAGE,
        )
    }

    private data class Target(val page: Page, val entry: Entry?)

    private val targetBySlug: Map<String, Target> by lazy {
        buildMap {
            for (page in pages) {
                fun add(slug: String, target: Target) {
                    require(put(slug, target) == null) { "SearchRegistry: duplicate slug '$slug'" }
                }
                add(page.slug, Target(page, null))
                for (entry in page.entries) add(entry.slug, Target(page, entry))
            }
        }
    }

    private val slugByItemId: Map<Int, String> by lazy {
        buildMap {
            for (page in pages) for (entry in page.entries) {
                if (entry.itemId != -1) putIfAbsent(entry.itemId, entry.slug)
            }
        }
    }

    fun deepLinkForItemId(itemId: Int): String? =
        slugByItemId[itemId]?.let { "tg://settings/fa/$it" }

    @JvmStatic
    fun extendSearchArray(
        stock: Array<ProfileActivity.SearchAdapter.SearchResult>,
        f: BaseFragment,
    ): Array<ProfileActivity.SearchAdapter.SearchResult> {
        if (ParanoiaHelper.shouldHideSettings()) return stock
        val extra = ArrayList<ProfileActivity.SearchAdapter.SearchResult>()
        for (page in pages) {
            val pageTitle = LocaleController.getString(page.titleRes)
            val parent = "${LocaleController.getString(R.string.InuSettings)} → $pageTitle"
            extra.add(
                ProfileActivity.SearchAdapter.SearchResult(
                    guidFor(page.slug),
                    pageTitle,
                    LocaleController.getString(R.string.InuSettings),
                    page.iconRes,
                ) { f.presentFragment(page.factory().apply { setCurrentAccount(f.currentAccount) }) }.withLink("tg://settings/fa/${page.slug}")
            )
            for (entry in page.entries) {
                val title = LocaleController.getString(entry.titleRes)
                extra.add(
                    ProfileActivity.SearchAdapter.SearchResult(
                        guidFor(entry.slug),
                        title,
                        parent,
                        page.iconRes,
                    ) {
                        f.presentFragment(page.factory().apply { setCurrentAccount(f.currentAccount) }.withHighlight(entry.itemId))
                    }.withLink("tg://settings/fa/${entry.slug}")
                )
            }
        }
        return stock + extra.toTypedArray()
    }

    @JvmStatic
    fun tryHandleDeepLink(activity: LaunchActivity, intent: Intent?): Boolean {
        if (ParanoiaHelper.shouldHideSettings()) return false
        val uri = intent?.data ?: return false
        if (uri.scheme != "tg") return false
        // accept both `tg://settings/fa/<slug>` (host=settings) and `tg:settings/fa/<slug>` (opaque), plus legacy inu prefix
        val segs = when (uri.host) {
            "settings" -> uri.pathSegments
            null -> uri.schemeSpecificPart?.removePrefix("//")
                ?.removePrefix("settings/")?.split('/')
                ?: return false

            else -> return false
        }
        if (segs.size < 2 || (segs[0] != "fa" && segs[0] != "inu")) return false
        val slugIndex = if (segs.size > 2 && (segs[1] == "fa" || segs[1] == "inu")) 2 else 1
        if (segs.size < slugIndex + 1) return false
        val target = targetBySlug[segs[slugIndex]] ?: return false
        val fragment = target.page.factory()
        target.entry?.let { fragment.withHighlight(it.itemId) }
        activity.actionBarLayout.presentFragment(fragment)
        return true
    }

    // stable guid from slug, high bit set to avoid stock guid range (<1000).
    private fun guidFor(slug: String): Int = 0x10000000 or (slug.hashCode() and 0x00FFFFFF)
}
