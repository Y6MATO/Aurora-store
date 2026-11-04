/*
 * SPDX-FileCopyrightText: 2026 Aurora OSS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.tv.navigation

/**
 * Resolves the first TV route. First launch (intro not completed) shows onboarding; otherwise the
 * user always lands on [Screen.Login], which doubles as the splash. Its [AuthViewModel] re-validates
 * the saved session against Play on every cold start (via a live call) and rebuilds the auth bundle
 * when the saved token is rejected, then auto-advances to [Screen.Home] once verified — or shows the
 * anonymous login button when there is no usable session. Routing a logged-in user straight to Home
 * would skip this refresh and leave a stale token in place (→ 401s on the first request).
 */
fun resolveStartDestination(introCompleted: Boolean): Screen =
    if (introCompleted) Screen.Login else Screen.Onboarding
