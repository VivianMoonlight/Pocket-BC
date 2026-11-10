package dev.vivian.mobilebc.feature.profile

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileSettingsPageTest {
    @Test fun previouslySavedPagesRestoreToTheirNewGroups() {
        assertEquals(ProfileSettingsPage.Account, ProfileSettingsPage.fromSavedName("Connection"))
        assertEquals(ProfileSettingsPage.Notifications, ProfileSettingsPage.fromSavedName("TextDisplay"))
        assertEquals(ProfileSettingsPage.Home, ProfileSettingsPage.fromSavedName("Relationships"))
        assertEquals(ProfileSettingsPage.Home, ProfileSettingsPage.fromSavedName("Character"))
    }

    @Test fun currentDestinationsSurviveRecreationAndUnknownNamesReturnHome() {
        ProfileSettingsPage.entries.forEach { page ->
            assertEquals(page, ProfileSettingsPage.fromSavedName(page.name))
        }
        assertEquals(ProfileSettingsPage.Home, ProfileSettingsPage.fromSavedName("removed-page"))
    }
}
