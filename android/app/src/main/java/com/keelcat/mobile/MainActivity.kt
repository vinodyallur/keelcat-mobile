package com.keelcat.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import com.keelcat.mobile.ui.KeelCatApp
import com.keelcat.mobile.ui.KeelViewModel
import com.keelcat.mobile.ui.theme.KeelCatTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                KeelViewModel(applicationContext) as T
        }
        val vm = ViewModelProvider(this as ViewModelStoreOwner, factory)[KeelViewModel::class.java]
        setContent {
            KeelCatTheme { KeelCatApp(vm) }
        }
    }
}
