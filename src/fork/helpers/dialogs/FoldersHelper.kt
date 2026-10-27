package xie.fa.gram.helpers.dialogs

import org.telegram.messenger.AndroidUtilities
import xie.fa.gram.InuConfig
import xie.fa.gram.helpers.dialogs.MainTabsHelper
import xie.fa.gram.helpers.theme.M3MainTabsHelper
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

    // M3 mode: match M3 bar height; classic/non-island: existing folder bar heights
    @JvmStatic
    fun barHeightDp(): Int = when {
        MainTabsHelper.isMaterial -> M3MainTabsHelper.barHeight
        NonIslandHelper.foldersBar() -> NonIslandHelper.FOLDERS_BAR_HEIGHT_DP
        else -> 36 + 14
    }

    @JvmStatic
    fun barHeightPx(): Int = AndroidUtilities.dp(barHeightDp().toFloat())
}
