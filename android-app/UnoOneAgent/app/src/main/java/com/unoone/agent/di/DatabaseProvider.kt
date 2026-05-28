package com.unoone.agent.di

import android.content.Context
import androidx.room.Room
import com.unoone.agent.storage.db.UnoOneDatabase

object DatabaseProvider {
    @Volatile
    private var INSTANCE: UnoOneDatabase? = null

    fun getDatabase(context: Context): UnoOneDatabase {
        return INSTANCE ?: synchronized(this) {
            val instance = Room.databaseBuilder(
                context.applicationContext,
                UnoOneDatabase::class.java,
                "unoone_database"
            ).build()
            INSTANCE = instance
            instance
        }
    }
}
