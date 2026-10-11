package app.aino.mobile.feature.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.aino.mobile.core.designsystem.component.UserAvatar
import app.aino.mobile.core.designsystem.icons.HeroIcons
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val PollDraftSaver = Saver<PollDraft, ArrayList<Any>>(
    save = { draft -> arrayListOf<Any>(draft.question, draft.multiSelect, draft.nextOptionId).apply {
        draft.options.forEach { add(it.id); add(it.text) }
    } },
    restore = { values -> PollDraft(
        question = values[0] as String,
        multiSelect = values[1] as Boolean,
        nextOptionId = values[2] as Int,
        options = values.drop(3).chunked(2).map { PollDraftOption(it[0] as Int, it[1] as String) },
    ) },
)

/** Original AINO poll composer; the draft survives failed sends and configuration changes. */
@Composable
fun PollCreatorDialog(
    onSubmit: (String, List<String>, Boolean) -> Unit,
    onClose: () -> Unit,
    state: PollCreationState = PollCreationState(),
) {
    val colors = signalColors
    var draft by rememberSaveable(stateSaver = PollDraftSaver) { mutableStateOf(PollDraft()) }
    LaunchedEffect(state.createdPollId) { if (state.createdPollId != null) onClose() }
    Dialog(
        onDismissRequest = { if (!state.sending) onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose, enabled = !state.sending) { Icon(HeroIcons.XMark, "Close poll creator", tint = colors.text) }
                Text("New poll", Modifier.weight(1f), color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                TextButton(
                    onClick = { onSubmit(draft.question, draft.options.map { it.text }, draft.multiSelect) },
                    enabled = draft.error == null && !state.sending && state.createdPollId == null,
                ) {
                    if (state.sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.primary)
                    else Icon(HeroIcons.PaperAirplane, null, Modifier.size(18.dp), tint = if (draft.error == null) colors.primary else colors.textSecondary)
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.sending) "Sending" else "Send", color = if (draft.error == null) colors.primary else colors.textSecondary)
                }
            }
            HorizontalDivider(color = colors.divider)
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp).animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("QUESTION", color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                PollField(
                    value = draft.question, label = "Question", placeholder = "What would you like to ask?",
                    max = POLL_QUESTION_LIMIT, enabled = !state.sending, singleLine = false,
                    onChange = { draft = draft.copy(question = it) },
                )
                Text("OPTIONS · ${draft.options.size}/$POLL_MAX_OPTIONS", color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    draft.options.forEachIndexed { index, option ->
                        key(option.id) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("${index + 1}", Modifier.width(28.dp), color = colors.textSecondary, fontWeight = FontWeight.SemiBold)
                                PollField(option.text, "Option ${index + 1}", "Add an answer", POLL_OPTION_LIMIT, !state.sending,
                                    modifier = Modifier.weight(1f), onChange = { draft = draft.updateOption(option.id, it) })
                                if (draft.options.size > POLL_MIN_OPTIONS) IconButton(
                                    onClick = { draft = draft.removeOption(option.id) }, enabled = !state.sending,
                                ) { Icon(HeroIcons.XMark, "Remove option ${index + 1}", tint = colors.textSecondary) }
                            }
                        }
                    }
                }
                TextButton(onClick = { draft = draft.addOption() }, enabled = draft.options.size < POLL_MAX_OPTIONS && !state.sending) {
                    Text(if (draft.options.size == POLL_MAX_OPTIONS) "Maximum of 10 options" else "+ Add option", color = colors.primary)
                }
                HorizontalDivider(color = colors.divider)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).toggleable(
                        value = draft.multiSelect, enabled = !state.sending, role = Role.Switch,
                        onValueChange = { draft = draft.copy(multiSelect = it) },
                    ).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Allow multiple answers", color = colors.text, fontWeight = FontWeight.Medium)
                        Text("People can choose more than one option.", color = colors.textSecondary, fontSize = 13.sp)
                    }
                    Switch(checked = draft.multiSelect, onCheckedChange = null, enabled = !state.sending,
                        colors = SwitchDefaults.colors(checkedTrackColor = colors.primary))
                }
                Text(draft.error ?: "Ready to send. Blank extra options are omitted.", color = colors.textSecondary, fontSize = 13.sp)
                state.error?.let { PollError(it, onRetry = null) }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun PollField(
    value: String,
    label: String,
    placeholder: String,
    max: Int,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    onChange: (String) -> Unit,
) {
    val colors = signalColors
    OutlinedTextField(
        value = value, onValueChange = { onChange(it.take(max)) },
        modifier = modifier.fillMaxWidth(), enabled = enabled, singleLine = singleLine,
        minLines = if (singleLine) 1 else 2, maxLines = if (singleLine) 1 else 5,
        label = { Text(label) }, placeholder = { Text(placeholder) },
        supportingText = { Text("${value.length}/$max", Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.End) },
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = colors.text, unfocusedTextColor = colors.text,
            focusedBorderColor = colors.primary, unfocusedBorderColor = colors.divider,
            focusedLabelColor = colors.primary, unfocusedLabelColor = colors.textSecondary,
            focusedSupportingTextColor = colors.textSecondary, unfocusedSupportingTextColor = colors.textSecondary,
            focusedContainerColor = colors.surface, unfocusedContainerColor = colors.surface,
        ),
    )
}

/** Content only: the thread retains its own bubble, reply, sender and delivery footer. */
@Composable
fun ChatPollCard(
    message: ChatMessage,
    poll: ChatPoll?,
    state: PollUiState,
    currentUserId: Long?,
    onVote: (Int) -> Unit,
    onRetry: () -> Unit,
    outgoing: Boolean = false,
    interactionEnabled: Boolean = true,
    onSelect: () -> Unit = {},
    onLongPress: () -> Unit = {},
    footer: @Composable () -> Unit = {},
) {
    val colors = signalColors
    val foreground = if (outgoing) colors.onOutgoing else colors.onIncoming
    val muted = if (outgoing) colors.onOutgoingSecondary else colors.onIncomingSecondary
    val labels = poll?.options ?: message.metadata?.jsonObject?.get("options")?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
    val tally = pollTally(poll, labels.size, currentUserId)
    var resultsOpen by rememberSaveable(message.id) { mutableStateOf(false) }
    LaunchedEffect(message.pollId()) {
        if (poll == null && !state.loading && state.loadError == null) onRetry()
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(HeroIcons.ChartBar, null, Modifier.padding(top = 2.dp).size(20.dp), tint = foreground)
            Text(poll?.question ?: message.body(), color = foreground, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(when {
            poll?.closedAt != null -> "Poll closed · Results only"
            poll == null && state.loadError != null -> "Poll unavailable · Retry below"
            poll == null -> "Loading poll…"
            poll.multiSelect -> "Choose one or more · Tap again to remove"
            else -> "Choose one · Tap again to remove"
        }, color = muted, fontSize = 12.sp)
        labels.forEachIndexed { index, label ->
            val option = tally.options[index]
            PollResultOption(
                label = label, option = option, total = tally.total, foreground = foreground,
                enabled = interactionEnabled && state.canVote(poll), pending = state.pendingOptionIndex == index,
                multiSelect = poll?.multiSelect == true, selectionMode = !interactionEnabled,
                onClick = { if (interactionEnabled) onVote(index) else onSelect() }, onLongPress = onLongPress,
            )
        }
        if (state.loading || state.voting) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(14.dp), color = foreground, strokeWidth = 2.dp)
            Text(if (state.voting) "Updating vote…" else "Refreshing votes…", color = muted, fontSize = 12.sp)
        }
        state.loadError?.let { PollError(it, if (interactionEnabled && !state.loading && !state.voting) onRetry else null, foreground) }
        state.voteError?.let { PollError("$it Tap an option to try again.", onRetry = null, foreground = foreground) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (poll == null) "Votes unavailable" else pollVoteLabel(tally.total), Modifier.weight(1f), color = muted, fontSize = 12.sp)
            footer()
        }
        TextButton(
            onClick = { resultsOpen = true }, enabled = interactionEnabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text("View votes", color = foreground, fontWeight = FontWeight.SemiBold) }
    }
    if (resultsOpen) PollVotesDialog(poll, state, currentUserId, onRetry, onClose = { resultsOpen = false })
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PollResultOption(
    label: String,
    option: PollOptionTally,
    total: Int,
    foreground: Color,
    enabled: Boolean,
    pending: Boolean,
    multiSelect: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val fraction by animateFloatAsState(if (total > 0) option.count.toFloat() / total else 0f, tween(280), label = "poll fraction")
    val marker by animateColorAsState(foreground.copy(alpha = if (option.mine) 1f else .45f), tween(180), label = "poll selection")
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(shape)
            .border(if (option.mine) 2.dp else 1.dp, marker, shape)
            .combinedClickable(enabled = enabled || selectionMode, onClick = onClick, onLongClick = onLongPress)
            .semantics(mergeDescendants = true) {
                selected = option.mine
                stateDescription = "${pollVoteLabel(option.count)}, ${option.percent} percent${if (option.mine) ", selected" else ""}"
                role = if (multiSelect) Role.Checkbox else Role.RadioButton
                if (!enabled && !selectionMode) disabled()
            },
    ) {
        Box(Modifier.matchParentSize()) {
            if (fraction > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(foreground.copy(alpha = .13f)))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(20.dp).border(if (option.mine) 2.dp else 1.dp, marker, if (multiSelect) RoundedCornerShape(5.dp) else CircleShape), contentAlignment = Alignment.Center) {
                if (option.mine) Text("✓", color = foreground, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Text(label, Modifier.weight(1f), color = foreground, fontSize = 15.sp)
            if (pending) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = foreground)
            else AnimatedContent(targetState = option.count, label = "poll count") { count ->
                Text("$count", color = foreground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun PollVotesDialog(
    poll: ChatPoll?,
    state: PollUiState,
    currentUserId: Long?,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = signalColors
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(HeroIcons.XMark, "Close votes", tint = colors.text) }
                Text("Poll votes", color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
            }
            HorizontalDivider(color = colors.divider)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (poll == null) Text("Votes aren't available yet.", color = colors.textSecondary)
                else {
                    Text(poll.question, color = colors.text, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
                    val tally = pollTally(poll, poll.options.size, currentUserId)
                    val people = pollUniqueVoterCount(poll)
                    Text("${pollVoteLabel(tally.total)} · $people ${if (people == 1) "person" else "people"}${if (poll.closedAt != null) " · Closed" else ""}", color = colors.textSecondary, fontSize = 13.sp)
                    if (tally.total == 0) Text("No votes yet. Results will appear here when someone votes.", color = colors.textSecondary)
                    Text("Shares are based on all selections${if (poll.multiSelect) "; a person may vote for several options" else ""}.", color = colors.textSecondary, fontSize = 12.sp)
                }
                if (state.loading || state.voting) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.primary)
                    Text(if (state.voting) "Updating vote…" else "Refreshing votes…", color = colors.textSecondary)
                }
                state.loadError?.let { PollError(it, if (!state.loading && !state.voting) onRetry else null) }
                state.voteError?.let { PollError(it, onRetry = null) }
                poll?.let { loaded ->
                    pollVoterGroups(loaded, currentUserId).forEach { group ->
                        key(loaded.id, group.optionIndex) { PollVoterSection(group, currentUserId) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PollVoterSection(group: PollVoterGroup, currentUserId: Long?) {
    val colors = signalColors
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surface)
            .border(1.dp, if (group.leading) colors.primary.copy(alpha = .55f) else colors.divider, RoundedCornerShape(16.dp))
            .padding(16.dp).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(group.label, Modifier.weight(1f), color = colors.text, fontWeight = FontWeight.SemiBold)
            Text(pollVoteLabel(group.voters.size), color = colors.textSecondary, fontSize = 13.sp)
        }
        if (group.leading) Text(if (group.tied) "Tied for most votes" else "Most votes", color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        if (group.voters.isEmpty()) Text("No votes for this option", color = colors.textSecondary, fontSize = 13.sp)
        group.visibleVoters(expanded).forEach { voter ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val name = voter.displayName(currentUserId)
                UserAvatar(name = voter.fullName, avatarUrl = voter.avatar, size = 36.dp, background = colors.primary)
                Text(name, color = colors.text, fontSize = 15.sp)
            }
        }
        if (group.voters.size > POLL_VISIBLE_VOTERS) TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Show fewer" else "Show ${group.voters.size - POLL_VISIBLE_VOTERS} more", color = colors.primary)
        }
    }
}

@Composable
private fun PollError(message: String, onRetry: (() -> Unit)?, foreground: Color = signalColors.text) {
    Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(message, color = foreground, fontSize = 13.sp)
        if (onRetry != null) TextButton(onClick = onRetry) { Text("Retry", color = foreground, fontWeight = FontWeight.SemiBold) }
    }
}
