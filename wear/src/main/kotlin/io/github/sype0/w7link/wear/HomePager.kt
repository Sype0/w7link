// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sype0

package io.github.sype0.w7link.wear

import androidx.compose.runtime.Composable
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.HorizontalPagerScaffold

/**
 * The app's home as pages swiped sideways: Health (Heartline's launcher, [health]), then the
 * phone's Media, then the Phone itself (pairing, find my phone, internet).
 */
@Composable
fun HomePager(health: @Composable () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { PAGES })
    HorizontalPagerScaffold(pagerState = pagerState) {
        HorizontalPager(state = pagerState) { page ->
            when (page) {
                PAGE_HEALTH -> health()
                PAGE_MEDIA -> MediaScreen()
                else -> WatchCompanionScreen()
            }
        }
    }
}

private const val PAGE_HEALTH = 0
private const val PAGE_MEDIA = 1
private const val PAGES = 3
