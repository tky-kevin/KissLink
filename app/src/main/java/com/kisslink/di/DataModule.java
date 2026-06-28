package com.kisslink.di;

import android.content.Context;

import com.kisslink.data.repository.ITransferRepository;
import com.kisslink.data.repository.LiveDataTransferRepository;
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

    /** 領域層倉庫介面（使用 DomainModel，提供搜尋等進階查詢）。 */
    @Provides
    @Singleton
    static ITransferRepository provideITransferRepository(@ApplicationContext Context context) {
        return LiveDataTransferRepository.getInstance(context);
    }
}
