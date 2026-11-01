package com.faznet.tv.di

import android.content.Context
import com.faznet.tv.data.Repository
import com.faznet.tv.data.db.FaznetDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): FaznetDatabase = FaznetDatabase.create(context)

    @Provides
    fun repository(@ApplicationContext context: Context): Repository = Repository.get(context)
}
