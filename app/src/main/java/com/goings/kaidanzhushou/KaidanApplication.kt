package com.goings.kaidanzhushou

import android.app.Application

class KaidanApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
