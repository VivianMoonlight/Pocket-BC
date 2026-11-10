package dev.vivian.mobilebc.feature.roomdetails

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.vivian.mobilebc.feature.rooms.R

@Composable
fun RoomMembersList(
    uiState: RoomDetailsUiState,
    interactionEnabled: Boolean,
    onInteract: (Int) -> Unit,
    onOpenBio: (Int) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item("title") {
            Text(
                stringResource(R.string.room_members_count, uiState.characters.size),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
        }
        if (uiState.characters.isEmpty()) {
            item("empty") { Text(stringResource(R.string.room_details_no_online_characters)) }
        }
        items(uiState.characters.sortedByDescending { it.isSelf }, key = { it.memberNumber }) { character ->
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable { onOpenBio(character.memberNumber) }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(character.displayName, style = MaterialTheme.typography.titleMedium)
                    Text("#${character.memberNumber}", style = MaterialTheme.typography.bodySmall)
                    if (character.isSelf) Text(stringResource(R.string.character_you))
                }
                TextButton(
                    onClick = { onInteract(character.memberNumber) },
                    enabled = interactionEnabled && uiState.wardrobe?.busy != true,
                ) {
                    Text(stringResource(R.string.room_member_interact))
                }
            }
        }
    }
}
