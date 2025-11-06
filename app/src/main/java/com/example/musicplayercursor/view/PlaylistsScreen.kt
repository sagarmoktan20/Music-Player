package com.example.musicplayercursor.view
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp


@Composable
fun PlaylistsScreen() {
//    PageContent(title = "Playlists", color = Color(0xFF4CAF50))
    PageContent(title = "Playlists", color = Color(0xFF4CAF50))
}

@Composable
fun PageContent(title: String, color: Color) {
    Box(modifier = Modifier.fillMaxSize().background(color), contentAlignment = Alignment.Center){
        Text(text = title,  fontSize = 36.sp, fontWeight = FontWeight.Bold, )
    }
}



