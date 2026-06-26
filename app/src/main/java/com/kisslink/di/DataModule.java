package com.kisslink.di;

import android.content.Context;

import com.kisslink.data.repository.TransferRepository;

import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.android.qualifiers.ApplicationContext;
import dagger.hilt.components.SingletonComponent;

import javax.inject.Singleton;

@Module
@InstallIn(SingletonComponent.class)
public final class DataModule {

    private DataModule() {}

    @Provides
    @Singleton
    static TransferRepository provideTransferRepository(@ApplicationContext Context context) {
        return TransferRepository.getInstance(context);
    }
}
