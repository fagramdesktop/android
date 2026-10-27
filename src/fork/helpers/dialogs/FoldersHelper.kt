package xie.fa.gram.helpers.dialogs

import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.FilterTabsView
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl
import xie.fa.gram.InuConfig
import xie.fa.gram.helpers.dialogs.MainTabsHelper
import xie.fa.gram.helpers.theme.NonIslandHelper

object FoldersHelper {
    @JvmStatic
    fun isBottom(): Boolean =
        MainTabsHelper.isMaterial &&
            InuConfig.FOLDERS_BAR_POSITION.value == InuConfig.FoldersBarPositionItem.BOTTOM

    // Flush with bottom tabs / nav bar (no gap)
    @JvmStatic
    fun bottomSlotPx(navigationBarHeight: Int, additionNavigationBarHeight: Int): Int {
        return navigationBarHeight + additionNavigationBarHeight
    }

    // Classic/non-island: 44dp; M3/default: 36dp tabs + 14dp vertical padding = 50dp
    @JvmStatic
    fun barHeightDp(): Int = when {
        NonIslandHelper.foldersBar() -> NonIslandHelper.FOLDERS_BAR_HEIGHT_DP
        else -> 36 + 14
    }

    @JvmStatic
    fun barHeightPx(): Int = AndroidUtilities.dp(barHeightDp().toFloat())

    @JvmStatic
    fun createBackground(
        factory: BlurredBackgroundDrawableViewFactory,
        tabsView: FilterTabsView,
        resourceProvider: Theme.ResourcesProvider?
    ): BlurredBackgroundDrawable {
        val bottom = isBottom()
        val provider = if (bottom) {
            BlurredBackgroundProviderImpl.mainTabs(resourceProvider)
        } else {
            BlurredBackgroundProviderImpl.topPanel(resourceProvider)
        }
        val bg = factory.create(tabsView, provider)
        if (bottom) {
            bg.setRadius(0f)
            bg.setPadding(0)
        } else {
            bg.setRadius(AndroidUtilities.dp(18f).toFloat())
            bg.setPadding(AndroidUtilities.dp(6.666f))
        }
        return bg
    }
}
