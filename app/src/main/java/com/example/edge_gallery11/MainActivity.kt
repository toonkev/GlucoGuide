package com.example.edge_gallery11

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.example.edge_gallery11.navigation.AppNavigation
import com.example.edge_gallery11.navigation.BottomNavigationBar
import com.example.edge_gallery11.ui.theme.Edge_gallery11Theme
import com.example.edge_gallery11.viewmodel.EdgeGalleryViewModel

class MainActivity : ComponentActivity() {
    
    private val viewModel: EdgeGalleryViewModel by viewModels()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Edge_gallery11Theme {
                MainScreen(viewModel)
            }
        }
    }
}

@Composable
fun MainScreen(viewModel: EdgeGalleryViewModel) {
    val navController = rememberNavController()
    Scaffold(
        bottomBar = { BottomNavigationBar(navController = navController) }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            AppNavigation(
                navController = navController,
                viewModel = viewModel
            )
        }
    }
}