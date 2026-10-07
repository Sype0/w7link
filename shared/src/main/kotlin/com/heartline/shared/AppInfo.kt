// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

/** Values that must match on the watch and the phone. */
object AppInfo {
    const val NAME = "W7Link"
    const val PROTOCOL_VERSION = 1

    /** This fork's repository; the project it is based on is [UPSTREAM_URL]. */
    const val REPO_OWNER = "Sype0"
    const val REPO_NAME = "w7link"
    const val UPSTREAM_URL = "https://github.com/selin2005/heartline"
    const val REPO_URL = "https://github.com/$REPO_OWNER/$REPO_NAME"
    const val RELEASES_URL = "$REPO_URL/releases"
    const val ISSUES_URL = "$REPO_URL/issues"
    /** The fork has no community of its own, and Heartline's doesn't support it: questions go to the issues. */
    const val COMMUNITY_URL = ISSUES_URL
    const val LICENSE_NAME = "GNU AGPL-3.0-or-later"

    /**
     * Version of the Terms of Use and Privacy Policy in legal/. Raising it makes the phone ask
     * every user to accept again (and the watch wait until they have).
     */
    const val TERMS_VERSION = 2
    const val TERMS_URL = "$REPO_URL/blob/main/legal/TERMS_OF_USE.md"
    const val PRIVACY_URL = "$REPO_URL/blob/main/legal/PRIVACY_POLICY.md"
}
