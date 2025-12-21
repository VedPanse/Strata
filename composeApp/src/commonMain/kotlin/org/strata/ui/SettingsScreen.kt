/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.strata.auth.AuthScopes
import org.strata.auth.AuthSession
import org.strata.auth.GoogleAuth
import org.strata.auth.JwtTools
import org.strata.auth.SessionStorage
import org.strata.persistence.MemoryStore
import strata.composeapp.generated.resources.Res
import strata.composeapp.generated.resources.UserProfile

class SettingsScreen : Screen {
    @Composable override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        Row(modifier = Modifier.fillMaxWidth()) {
            // Left navigation pane
            SettingsLeftNavPane(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .weight(0.15f)
                        .padding(horizontal = 5.dp),
                onHome = { navigator.replaceAll(Homescreen()) },
                onAccountSettings = { /* Already on Settings; no-op or refresh */ },
                onLogout = {
                    SessionStorage.clear()
                    navigator.replaceAll(OnboardingScreen())
                },
            )

            // Right content
            Column(
                modifier =
                    Modifier
                        .weight(0.85f)
                        .fillMaxHeight()
                        .padding(end = 30.dp)
                        .verticalScroll(rememberScrollState()),
            ) {
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(end = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(12.dp))

                ProfileSection()
                Spacer(modifier = Modifier.height(16.dp))

                val scope = rememberCoroutineScope()
                var isGoogleConnected by remember { mutableStateOf(SessionStorage.isSignedIn()) }
                ConnectedAccountsSection(
                    connected = isGoogleConnected,
                    onSignOut = {
                        SessionStorage.clear()
                        isGoogleConnected = false
                    },
                    onReconnect = {
                        scope.launch {
                            val res = GoogleAuth.signIn(AuthScopes.ALL)
                            if (res.success) {
                                SessionStorage.save(
                                    AuthSession(
                                        accessToken = res.accessToken,
                                        refreshToken = res.refreshToken,
                                        idToken = res.idToken,
                                    ),
                                )
                                isGoogleConnected = true
                            }
                        }
                    },
                )
                Spacer(modifier = Modifier.height(16.dp))

                PreferencesSection()
                Spacer(modifier = Modifier.height(16.dp))

                PrivacySecuritySection(
                    onDeleteAccount = {
                        SessionStorage.clear()
                    },
                    onDeleteMemory = {
                        MemoryStore.clear()
                    },
                )
                Spacer(modifier = Modifier.height(16.dp))

                SupportLegalSection()
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun SettingsLeftNavPane(
    modifier: Modifier = Modifier,
    onHome: () -> Unit,
    onAccountSettings: () -> Unit,
    onLogout: () -> Unit,
) {
    Column(modifier = modifier) {
        Spacer(modifier = Modifier.height(15.dp))
        SettingsNavItem(label = "Home", icon = Icons.Filled.Home, modifier = Modifier.padding(start = 0.dp)) {
            onHome()
        }
        Spacer(modifier = Modifier.height(8.dp))
        SettingsNavItem(
            label = "Account Settings",
            icon = Icons.Filled.Settings,
            modifier = Modifier.padding(start = 0.dp),
        ) {
            onAccountSettings()
        }
        Spacer(modifier = Modifier.height(12.dp))
        HorizontalLine()
        Spacer(modifier = Modifier.height(12.dp))
        SettingsNavItem(
            label = "Log out",
            icon = Icons.AutoMirrored.Filled.Logout,
            modifier = Modifier.padding(start = 0.dp),
        ) {
            onLogout()
        }
    }
}

@Composable
private fun SettingsNavItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = Color.White,
                disabledContainerColor = Color.Transparent,
                disabledContentColor = Color.White,
            ),
        shape = RoundedCornerShape(25.dp),
        modifier =
            Modifier
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .pointerHoverIcon(PointerIcon.Hand)
                .then(modifier),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Icon(imageVector = icon, contentDescription = label, tint = Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            Text(label, fontSize = 14.sp)
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(16.dp))
                .padding(16.dp),
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun ProfileSection() {
    val session = remember { SessionStorage.read() }
    val idToken = session?.idToken
    var customName by remember { mutableStateOf("") }
    var customEmail by remember { mutableStateOf("") }
    val nameFromGoogle = remember(idToken) { idToken?.let { JwtTools.extractName(it) } }
    val emailFromGoogle = remember(idToken) { idToken?.let { JwtTools.extractEmail(it) } }
    val pictureUrl = remember(idToken) { idToken?.let { JwtTools.extractPicture(it) } }
    val imageBitmap = pictureUrl?.let { rememberNetworkImageBitmap(it) }

    SectionCard(title = "Profile") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (imageBitmap != null) {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp).clip(CircleShape).border(1.dp, Color.DarkGray, CircleShape),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Image(
                    painter = painterResource(Res.drawable.UserProfile),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp).clip(CircleShape).border(1.dp, Color.DarkGray, CircleShape),
                    contentScale = ContentScale.Crop,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                val nameEditable = nameFromGoogle == null
                var nameField by remember { mutableStateOf(customName) }
                LaunchedEffect(nameFromGoogle) { if (nameFromGoogle != null) nameField = nameFromGoogle }
                Text(
                    if (nameFromGoogle != null) {
                        nameFromGoogle
                    } else if (nameField.isNotBlank()) {
                        nameField
                    } else {
                        "Your name"
                    },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(4.dp))
                val emailText = emailFromGoogle ?: if (customEmail.isNotBlank()) customEmail else "Email"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Email,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(emailText, color = Color.Gray, fontSize = 13.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text("Connected since —", color = Color.Gray, fontSize = 12.sp)
            }
            if (nameFromGoogle == null) {
                Button(
                    onClick = { /* Save custom name/email locally - TODO storage */ },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2D2D2D)),
                ) {
                    Text("Save")
                }
            }
        }
    }
}

@Composable
private fun ConnectedAccountsSection(
    connected: Boolean,
    onSignOut: () -> Unit,
    onReconnect: () -> Unit,
) {
    SectionCard(title = "Connected Accounts") {
        AccountRow(
            title = "Google",
            subtitle = "Gmail, Calendar, Tasks, Fit",
            connected = connected,
            lastSynced = "Just now",
            onReconnect = onReconnect,
            onDisconnect = onSignOut,
        )
        androidx.compose.material3.HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp),
            color = Color(0x22FFFFFF),
        )
        AccountRow(
            title = "Finance API",
            subtitle = "Plaid (coming soon)",
            connected = false,
            lastSynced = "—",
            onReconnect = { /* TODO: Launch Plaid Link */ },
            onDisconnect = { },
        )
        androidx.compose.material3.HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp),
            color = Color(0x22FFFFFF),
        )
        AccountRow(
            title = "Other Integrations",
            subtitle = "Outlook, Apple Health (future)",
            connected = false,
            lastSynced = "—",
            onReconnect = { },
            onDisconnect = { },
        )
    }
}

@Composable
private fun AccountRow(
    title: String,
    subtitle: String,
    connected: Boolean,
    lastSynced: String,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = Color.Gray, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "Status: ${if (connected) "Connected" else "Not connected"}",
                color = if (connected) Color(0xFF5FD788) else Color(0xFFE57373),
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(2.dp))
            Text("Last synced: $lastSynced", color = Color.Gray, fontSize = 12.sp)
        }
        Spacer(Modifier.width(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (connected) {
                Button(
                    onClick = onDisconnect,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3A3A)),
                ) {
                    Text("Disconnect")
                }
            } else {
                Button(
                    onClick = onReconnect,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3A3A)),
                ) {
                    Text("Reconnect")
                }
            }
        }
    }
}

@Composable
private fun PreferencesSection() {
    SectionCard(title = "App Preferences") {
        var theme by remember { mutableStateOf("System Default") }
        var accent by remember { mutableStateOf("Blue Gradient") }
        var language by remember { mutableStateOf("English (US)") }
        var calNotif by remember { mutableStateOf(true) }
        var taskNotif by remember { mutableStateOf(true) }
        var financeNotif by remember { mutableStateOf(false) }
        var healthNotif by remember { mutableStateOf(false) }

        LabeledRow("Theme", trailing = { Text(theme, color = Color.Gray) })
        androidx.compose.material3.HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = Color(0x22FFFFFF),
        )
        LabeledRow("Accent Color", trailing = { Text(accent, color = Color.Gray) })
        androidx.compose.material3.HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = Color(0x22FFFFFF),
        )
        LabeledRow("Language & Region", trailing = { Text(language, color = Color.Gray) })
        androidx.compose.material3.HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = Color(0x22FFFFFF),
        )
        LabeledRow("Calendar reminders", trailing = { Switch(checked = calNotif, onCheckedChange = { calNotif = it }) })
        LabeledRow("Task alerts", trailing = { Switch(checked = taskNotif, onCheckedChange = { taskNotif = it }) })
        LabeledRow(
            "Finance insights",
            trailing = { Switch(checked = financeNotif, onCheckedChange = { financeNotif = it }) },
        )
        LabeledRow("Health goals", trailing = { Switch(checked = healthNotif, onCheckedChange = { healthNotif = it }) })
    }
}

@Composable
private fun LabeledRow(
    label: String,
    trailing: @Composable () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun PrivacySecuritySection(
    onDeleteAccount: () -> Unit,
    onDeleteMemory: () -> Unit,
) {
    SectionCard(title = "Privacy & Security") {
        // Manage Permissions (show granted scopes)
        Text("Manage Permissions", fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Column(modifier = Modifier.padding(start = 8.dp)) {
            AuthScopes.ALL.forEach { scope ->
                Text("• $scope", color = Color.Gray, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        androidx.compose.material3.HorizontalDivider(color = Color(0x22FFFFFF))
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFFFB74D))
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Delete LLM memory", fontWeight = FontWeight.Medium, color = Color(0xFFFFB74D))
                Text("Clear saved preferences and facts", color = Color.Gray, fontSize = 12.sp)
            }
            Button(
                onClick = onDeleteMemory,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3A3A)),
            ) {
                Text("Delete")
            }
        }
        Spacer(Modifier.height(12.dp))
        androidx.compose.material3.HorizontalDivider(color = Color(0x22FFFFFF))
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFE57373))
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Delete Account", fontWeight = FontWeight.Medium, color = Color(0xFFE57373))
                Text("Clear all data and integrations", color = Color.Gray, fontSize = 12.sp)
            }
            Button(
                onClick = onDeleteAccount,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3A3A)),
            ) {
                Text("Delete")
            }
        }
    }
}

@Composable
private fun SupportLegalSection() {
    SectionCard(title = "Support & Legal") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Link, contentDescription = null, tint = Color.Gray)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Contact Support", fontWeight = FontWeight.Medium)
                Text("support@strata.app", color = Color.Gray, fontSize = 12.sp)
            }
            Button(onClick = {
                // TODO: launch mailto
            }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3A3A))) {
                Text("Email")
            }
        }
        Spacer(Modifier.height(12.dp))
        androidx.compose.material3.HorizontalDivider(color = Color(0x22FFFFFF))
        Spacer(Modifier.height(12.dp))
        Text("App Version & Build Number", fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text("v0.1.0 (dev)", color = Color.Gray, fontSize = 12.sp)
    }
}
