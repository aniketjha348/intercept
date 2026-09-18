package com.intercept

import android.app.Application
import com.intercept.di.AppContainer

class InterceptApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

fun android.content.Context.appContainer(): AppContainer =
    (applicationContext as InterceptApp).container
