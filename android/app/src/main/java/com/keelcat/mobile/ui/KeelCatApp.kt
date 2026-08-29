package com.keelcat.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeelCatApp(vm: KeelViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val cfg = state.config

    Scaffold(topBar = { TopAppBar(title = { Text("KeelCat — on-device API maintenance") }) }) { pad ->
        Column(
            modifier = Modifier.fillMaxSize().padding(pad).padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard("Repository") {
                Field("GitHub token", cfg.githubToken, password = true) { v -> vm.updateConfig { it.copy(githubToken = v) } }
                Field("Owner", cfg.owner) { v -> vm.updateConfig { it.copy(owner = v) } }
                Field("Repo", cfg.repo) { v -> vm.updateConfig { it.copy(repo = v) } }
                Field("Default branch", cfg.defaultBranch) { v -> vm.updateConfig { it.copy(defaultBranch = v) } }
            }

            SectionCard("On-device model & Office Kit") {
                Field("Model path (on device)", cfg.modelPath) { v -> vm.updateConfig { it.copy(modelPath = v) } }
                Field("Runner URL (laptop)", cfg.runnerUrl) { v -> vm.updateConfig { it.copy(runnerUrl = v) } }
                Field("Test command", cfg.testCommand) { v -> vm.updateConfig { it.copy(testCommand = v) } }
            }

            SectionCard("Changelog") {
                Field("Paste the dependency changelog", cfg.changelog, singleLine = false) { v ->
                    vm.updateConfig { it.copy(changelog = v) }
                }
            }

            Button(
                onClick = vm::run,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.busy) "Working…" else "Analyze & open PR") }

            StatusCard(state)

            if (state.phase == Phase.DONE || state.phase == Phase.ERROR) {
                OutlinedButton(onClick = vm::reset, modifier = Modifier.fillMaxWidth()) { Text("Reset") }
            }
        }
    }
}

@Composable
private fun StatusCard(state: KeelUiState) {
    SectionCard("Progress") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state.busy) CircularProgressIndicator()
            Text(state.status, style = MaterialTheme.typography.bodyLarge)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            if (state.breakingChanges.isNotEmpty()) {
                Text("Breaking changes:", style = MaterialTheme.typography.titleSmall)
                state.breakingChanges.forEach {
                    Text("• ${it.symbol} (${it.kind})${it.replacement?.let { r -> " → $r" } ?: ""}")
                }
            }
            if (state.affectedPaths.isNotEmpty()) {
                Text("Affected files:", style = MaterialTheme.typography.titleSmall)
                state.affectedPaths.forEach { Text("• $it") }
            }
            state.pr?.let {
                Text("PR #${it.number}: ${it.url}", style = MaterialTheme.typography.titleSmall)
            }
            state.verify?.let {
                Text(if (it.passed) "Verified: tests passed ✅" else "Verify failed (${it.stage}) ❌")
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    password: Boolean = false,
    singleLine: Boolean = true,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = if (password) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth(),
    )
}
