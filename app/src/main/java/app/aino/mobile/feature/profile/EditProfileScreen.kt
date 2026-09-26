package app.aino.mobile.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.tokens.LocalWebColors

/** Web `EditProfileModal` as a full page; section order and copy follow the modal. */
@Composable
fun EditProfileScreen(
    viewModel: ProfileViewModel,
    /** Org "Allow biometric login" (web OrgSettings); gates sign-in use only. */
    biometricLoginAllowed: Boolean = true,
    /** Org enforces attendance verification, where the same credential clocks in/out. */
    attendanceVerificationOn: Boolean = false,
    biometricAvailable: Boolean,
    biometricEnrolled: Boolean,
    biometricMessage: String?,
    biometricError: String?,
    thisDeviceCredentialId: String?,
    onEnableBiometric: () -> Unit,
    /** Forget the local credential after its server row is revoked. */
    onThisDeviceRevoked: () -> Unit,
    onBack: () -> Unit,
    onProfileChanged: (ProfileUser) -> Unit,
    onEmailChanged: (String) -> Unit,
    onAccountDeleted: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = LocalWebColors.current
    LaunchedEffect(Unit) { if (ui.user == null) viewModel.refresh() }
    LaunchedEffect(biometricEnrolled) { viewModel.loadBiometricDevices() }
    fun busy(form: ProfileForm) = form in ui.busy

    ProfilePage("Edit Profile", onBack) {
        ProfileSection("Name & Username", Icons.Outlined.Person) {
            SectionBody {
                ProfileTextField("Full Name", ui.draftName, { viewModel.updateDraft(name = it) }, "Your full name")
                ProfileTextField("Username", ui.draftUsername, { viewModel.updateDraft(username = it) }, "username", prefix = "@")
                NoticeText(ui.notices[ProfileForm.Profile])
                ProfileButton(
                    "Save Changes", "Saving…", busy(ProfileForm.Profile),
                    { viewModel.saveProfile(onProfileChanged) },
                    enabled = ui.draftName.isNotBlank() && ui.draftUsername.isNotBlank(),
                )
            }
        }

        ProfileSection("Email Address", Icons.Outlined.Email) {
            SectionBody {
                ProfileTextField("Email", ui.draftEmail, { viewModel.updateDraft(email = it) }, "you@example.com", KeyboardType.Email)
                NoticeText(ui.notices[ProfileForm.Email])
                ProfileButton(
                    "Update Email", "Saving…", busy(ProfileForm.Email),
                    { viewModel.saveEmail(onEmailChanged) },
                    enabled = ui.draftEmail.isNotBlank(),
                )
            }
        }

        ProfileSection("Change Password", Icons.Outlined.Lock) {
            SectionBody {
                ProfileTextField("Current Password", ui.currentPassword, { viewModel.updateDraft(currentPassword = it) }, "Enter current password", password = true)
                ProfileTextField("New Password", ui.newPassword, { viewModel.updateDraft(newPassword = it) }, "Min 8 characters", password = true)
                ProfileTextField("Confirm New Password", ui.confirmPassword, { viewModel.updateDraft(confirmPassword = it) }, "Repeat new password", password = true)
                NoticeText(ui.notices[ProfileForm.Password])
                ProfileButton(
                    "Change Password", "Saving…", busy(ProfileForm.Password), viewModel::changePassword,
                    enabled = ui.currentPassword.isNotEmpty() && ui.newPassword.isNotEmpty() && ui.confirmPassword.isNotEmpty(),
                )
            }
        }

        // Android counterpart of the modal's desktop "Biometric Login (this device)" section.
        // One enrollment serves sign-in and attendance clock-in/out.
        val showSection = biometricLoginAllowed || attendanceVerificationOn || biometricEnrolled
        if (showSection) ProfileSection("Fingerprint / PIN (this device)", Icons.Outlined.Fingerprint) {
            SectionBody {
                SectionDescription(
                    buildString {
                        append("Use your fingerprint, face unlock or phone PIN ")
                        append(
                            when {
                                biometricLoginAllowed && attendanceVerificationOn -> "to sign in and to clock in / out"
                                attendanceVerificationOn -> "to clock in / out"
                                else -> "to sign in"
                            },
                        )
                        append(" on this phone. A credential is stored encrypted on this device; your fingerprint never leaves it.")
                    },
                )
                if (!biometricLoginAllowed) {
                    Text(
                        "Biometric sign-in is turned off by your organization. Password sign-in is still required.",
                        color = colors.textSecondary, fontSize = 13.sp,
                    )
                }
                if (!biometricAvailable && !biometricEnrolled) {
                    Text(
                        "No fingerprint or screen lock is set up on this phone. Add one in " +
                            "Android Settings → Security, then reopen this page.",
                        color = colors.danger, fontSize = 13.sp,
                    )
                }
                biometricMessage?.let { Text(it, color = colors.success, fontSize = 13.sp) }
                biometricError?.let { Text(it, color = colors.danger, fontSize = 13.sp) }
                if (biometricEnrolled) {
                    val disabling = busy(ProfileForm.Devices)
                    OutlinedButton(
                        onClick = {
                            // Revoke server-side too so the credential cannot sign in again; fall back to a local wipe.
                            if (thisDeviceCredentialId != null) viewModel.revokeBiometricDevice(thisDeviceCredentialId, thisDeviceCredentialId, onThisDeviceRevoked)
                            else onThisDeviceRevoked()
                        },
                        enabled = !disabling,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (disabling) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else { Icon(Icons.Outlined.Delete, null, Modifier.size(16.dp)); Text("  Disable on this device") }
                    }
                } else if (biometricAvailable && (biometricLoginAllowed || attendanceVerificationOn)) {
                    ProfileButton("Enable fingerprint / PIN", "Working…", false, onEnableBiometric, icon = Icons.Outlined.Add)
                }
            }
        }

        if (ui.biometricDevices.isNotEmpty()) {
            ProfileSection("Devices with biometric sign-in", Icons.Outlined.Smartphone) {
                SectionBody {
                    SectionDescription(
                        "These devices can sign in to your account with Face ID, Touch ID, or Windows Hello. " +
                            "Remove any you no longer use — removing a device forces it back to password sign-in.",
                    )
                    NoticeText(ui.notices[ProfileForm.Devices])
                    ui.biometricDevices.forEach { device ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(if (device.platform == "desktop") Icons.Outlined.Computer else Icons.Outlined.Smartphone, null, Modifier.size(18.dp), tint = colors.textSecondary)
                            Column(Modifier.weight(1f)) {
                                Text(device.deviceLabel ?: biometricPlatformLabel(device.platform), color = colors.text, fontSize = 14.sp)
                                Text(
                                    biometricPlatformLabel(device.platform) + (localDate(device.lastUsedAt)?.let { " · last used $it" } ?: ""),
                                    color = colors.textSecondary, fontSize = 12.sp,
                                )
                            }
                            IconButton(
                                onClick = { viewModel.revokeBiometricDevice(device.id, thisDeviceCredentialId, onThisDeviceRevoked) },
                                enabled = !busy(ProfileForm.Devices),
                            ) {
                                Icon(Icons.Outlined.Delete, "Remove device", tint = colors.textSecondary)
                            }
                        }
                    }
                }
            }
        }

        ProfileSection("Danger Zone", Icons.Outlined.WarningAmber, danger = true) {
            SectionBody {
                SectionDescription("Permanently delete your account and all associated data. This action cannot be undone.")
                if (!ui.deleteConfirming) {
                    ProfileButton("Delete My Account", "", false, { viewModel.askDeleteAccount(true) }, danger = true)
                } else {
                    Text("Enter your password to confirm deletion:", color = colors.text, fontSize = 13.sp)
                    ProfileTextField("Password", ui.deletePassword, { viewModel.updateDraft(deletePassword = it) }, "Your password", password = true)
                    ui.notices[ProfileForm.Delete]?.takeIf { !it.ok }?.let { NoticeText(it) }
                    ProfileButton(
                        "Yes, Delete Forever", "Deleting…", busy(ProfileForm.Delete),
                        { viewModel.deleteAccount(onAccountDeleted) },
                        danger = true, icon = Icons.Outlined.Delete,
                    )
                    OutlinedButton(onClick = { viewModel.askDeleteAccount(false) }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                }
            }
        }
    }
}
